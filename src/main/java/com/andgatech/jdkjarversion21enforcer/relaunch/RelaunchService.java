package com.andgatech.jdkjarversion21enforcer.relaunch;

import java.nio.file.Path;
import java.util.Arrays;

import org.apache.logging.log4j.Logger;

import com.andgatech.jdkjarversion21enforcer.Config;
import com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer;
import com.andgatech.jdkjarversion21enforcer.JavaRuntimeVersion;
import com.andgatech.jdkjarversion21enforcer.JdkJarVersion21Enforcer;
import com.andgatech.jdkjarversion21enforcer.integration.Lwjgl3ifyConfigPatcher;
import com.andgatech.jdkjarversion21enforcer.ui.ManualLauncherInstructionsPopup;
import com.andgatech.jdkjarversion21enforcer.ui.RelaunchPromptDialog;
import com.andgatech.jdkjarversion21enforcer.ui.RestartPopup;

/**
 * Stateless orchestrator for the client-side "make
 * {@code -Djdk.util.jar.version=21} actually take effect" flow. Used by both:
 *
 * <ul>
 * <li>{@code com.andgatech.jdkjarversion21enforcer.tweaker.JdkJarVersion21Tweaker} during the
 * LaunchWrapper Tweaker {@code injectIntoClassLoader} phase (earliest possible point a jar in
 * {@code mods/} can run code; matches lwjgl3ify's own behaviour).</li>
 * <li>{@link com.andgatech.jdkjarversion21enforcer.CommonProxy#preInit} as a fallback in case the
 * Tweaker entry point did not run (legacy launchers, dev environments where TweakClass is not
 * picked up, etc.).</li>
 * </ul>
 *
 * <p>
 * Both phases run the exact same logic; this class exists so we never have two copies. The
 * behaviour intentionally avoids touching {@code java.util.jar.JarFile} on the
 * {@link Phase#TWEAKER} path because doing so would lock {@code RUNTIME_VERSION} forever before
 * we get a chance to fork a child JVM.
 */
public final class RelaunchService {

    /**
     * System property used by the Tweaker to signal {@code preInit} that the client-side fallback
     * has already been handled. {@code preInit} consults this property and skips its own
     * {@link Lwjgl3ifyConfigPatcher} / {@link RelaunchPromptDialog} invocation if set.
     */
    public static final String HANDLED_PROPERTY = "jdkjarversion21enforcer.client.handled";

    public enum Phase {
        TWEAKER,
        PRE_INIT
    }

    public enum Outcome {
        /** Java is at most 21; no enforcement needed. */
        SKIPPED_JAVA_LE_21,
        /** This JVM is itself a child spawned by an earlier relaunch; do not loop. */
        SKIPPED_RELAUNCHED_CHILD,
        /**
         * {@code -Djdk.util.jar.version=21} was already passed on the JVM command line, so the
         * launcher / integration pack already handled the problem. We must not pop a dialog or
         * touch any config in this case.
         */
        SKIPPED_PROPERTY_ALREADY_SET,
        /** lwjgl3ify config was found but already contains the desired option. */
        LWJGL3IFY_ALREADY_PRESENT,
        /** lwjgl3ify config was found and we appended the option. User should restart. */
        LWJGL3IFY_APPLIED,
        /** Failed to read or write the lwjgl3ify config. */
        LWJGL3IFY_ERROR,
        /** lwjgl3ify is not installed; relaunch prompt is disabled by user config. */
        DIALOG_DISABLED,
        /** lwjgl3ify is not installed; user previously chose "Don't ask again". */
        DIALOG_SUPPRESSED,
        /** lwjgl3ify is not installed; running headless so no dialog is possible. */
        DIALOG_HEADLESS,
        /** User chose "Skip this time" in the dialog. */
        DIALOG_SKIPPED_ONCE,
        /** User chose "Don't ask again" in the dialog. Suppression flag persisted. */
        DIALOG_SUPPRESS_FOREVER,
        /**
         * User chose "Restart Now". Normally {@link JvmRelauncher#relaunchAndExit(java.util.List)}
         * halts the JVM and we never observe this outcome from the caller. It is returned only on
         * pathological failure paths (e.g. {@code halt} not honoured).
         */
        DIALOG_RESTART_RETURNED
    }

    private RelaunchService() {}

    /**
     * Runs the full client-side flow synchronously. The caller (Tweaker / preInit) only needs to
     * pass game/config directories and a logger.
     *
     * @param gameDir   the Minecraft instance root (where {@code config/} and {@code mods/} live)
     * @param configDir the configuration directory (typically {@code <gameDir>/config})
     * @param phase     phase used purely for log prefixing so users can tell where the message
     *                  came from
     * @param log       logger to use (defaults to mod logger if null)
     * @return an {@link Outcome} describing what happened (mainly for tests / logs)
     */
    public static Outcome runClientFlow(Path gameDir, Path configDir, Phase phase, Logger log) {
        Logger logger = (log != null) ? log : JdkJarVersion21Enforcer.LOG;
        String tag = "[" + phase.name()
            .toLowerCase() + "] ";

        if (!JavaRuntimeVersion.isAbove21(System.getProperty("java.specification.version"))) {
            return Outcome.SKIPPED_JAVA_LE_21;
        }
        if (JvmRelauncher.isRelaunchedChild()) {
            // Could happen if the parent forked us but the launcher stripped our flag. Bail out
            // before we loop.
            return Outcome.SKIPPED_RELAUNCHED_CHILD;
        }
        if (JarVersionPropertyEnforcer.REQUIRED_VERSION
            .equals(System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME))) {
            // The launcher / integration pack already passed -Djdk.util.jar.version=21 on the JVM
            // command line, which means JarFile.RUNTIME_VERSION will pick it up before we even
            // reach Forge. There is nothing for us to do.
            logger.info(
                tag + "-D"
                    + JarVersionPropertyEnforcer.PROPERTY_NAME
                    + "="
                    + JarVersionPropertyEnforcer.REQUIRED_VERSION
                    + " was already supplied on the JVM command line; skipping all client-side fallback.");
            return Outcome.SKIPPED_PROPERTY_ALREADY_SET;
        }
        // v0.5.2: RFB mode is *not* a reason to skip. Real-world testing showed that GTNH
        // official Java 17-25 integration packs may NOT pass -Djdk.util.jar.version=21 even when
        // they are RFB-booted, leaving JarFile.RUNTIME_VERSION wrong. So we run the full flow
        // (patcher / dialog / fork) regardless of RFB. We log RFB detection only to help users
        // understand the environment when they read logs.
        if (isRfbBooted()) {
            logger.info(
                tag + "Detected RFB-booted environment (lwjgl3ify:rfb-booted=true on Launch.blackboard). Continuing with the full enforcement flow because the integration pack did not pass -Djdk.util.jar.version=21 on the command line.");
        }

        Config cfg = Config.loadOrCreate(configDir);

        // Step 1: write / refresh lwjgl3ify-relauncher.json so future non-RFB launches are
        // self-healing. Even on RFB-booted runs (where lwjgl3ify's relauncher is bypassed) this
        // file has no harmful side-effects, and if the user later switches launcher mode our
        // entry will be honoured. The result also feeds into the dialog message so the user
        // knows what we just did.
        RelaunchPromptDialog.PatcherStatus patcherStatus = RelaunchPromptDialog.PatcherStatus.NO_CONFIG_OR_ERROR;
        if (cfg.autoPatchLwjgl3ifyConfig) {
            Lwjgl3ifyConfigPatcher.Result r = Lwjgl3ifyConfigPatcher.patchOrCreate(gameDir);
            switch (r) {
                case CREATED:
                    patcherStatus = RelaunchPromptDialog.PatcherStatus.CREATED;
                    logger.info(
                        tag + "Created "
                            + Lwjgl3ifyConfigPatcher.CONFIG_RELATIVE_PATH
                            + " with `"
                            + Lwjgl3ifyConfigPatcher.DESIRED_OPTION
                            + "` pre-filled. Future non-RFB launches will pick it up automatically.");
                    break;
                case APPLIED:
                    patcherStatus = RelaunchPromptDialog.PatcherStatus.APPLIED;
                    logger.info(
                        tag + "Appended `"
                            + Lwjgl3ifyConfigPatcher.DESIRED_OPTION
                            + "` to lwjgl3ify customOptions ("
                            + Lwjgl3ifyConfigPatcher.CONFIG_RELATIVE_PATH
                            + "). Future non-RFB launches will pick it up automatically.");
                    break;
                case ALREADY_PRESENT:
                    patcherStatus = RelaunchPromptDialog.PatcherStatus.ALREADY_PRESENT;
                    logger.info(
                        tag + "lwjgl3ify customOptions already contains `"
                            + Lwjgl3ifyConfigPatcher.DESIRED_OPTION
                            + "`, but the property is not effective in this JVM (likely an RFB-booted launch).");
                    break;
                case ERROR:
                case NO_CONFIG:
                default:
                    patcherStatus = RelaunchPromptDialog.PatcherStatus.NO_CONFIG_OR_ERROR;
                    logger.warn(
                        tag + "Could not read/write lwjgl3ify-relauncher.json ("
                            + r
                            + "). Persistent configuration via lwjgl3ify is unavailable.");
                    break;
            }
        } else {
            logger.info(tag + "auto_patch_lwjgl3ify_config=false; skipping lwjgl3ify config write.");
        }

        // Step 2: ask the user what to do for the *current* launch (the lwjgl3ify config we just
        // wrote only helps the *next* launch, and only on non-RFB launchers).
        String desiredOption = "-D" + JarVersionPropertyEnforcer.PROPERTY_NAME
            + "="
            + JarVersionPropertyEnforcer.REQUIRED_VERSION;
        if (!cfg.prelaunchRelaunchPrompt) {
            logger.info(
                tag + "Relaunch prompt disabled by config ("
                    + Config.KEY_PRELAUNCH_RELAUNCH_PROMPT
                    + "=false). The current JVM will run without `"
                    + desiredOption
                    + "`.");
            ManualLauncherInstructionsPopup.show(JdkJarVersion21Enforcer.MOD_NAME, desiredOption, null);
            return Outcome.DIALOG_DISABLED;
        }
        if (cfg.prelaunchRelaunchSuppressed) {
            logger.info(
                tag + "Relaunch prompt is permanently suppressed ("
                    + Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED
                    + "=true). The current JVM will run without `"
                    + desiredOption
                    + "`.");
            ManualLauncherInstructionsPopup.show(JdkJarVersion21Enforcer.MOD_NAME, desiredOption, null);
            return Outcome.DIALOG_SUPPRESSED;
        }
        if (!RestartPopup.canShow()) {
            logger.info(
                tag + "Headless environment detected; skipping relaunch prompt. The current JVM will run without `"
                    + desiredOption
                    + "`.");
            return Outcome.DIALOG_HEADLESS;
        }

        String javaSpec = System.getProperty("java.specification.version", "?");
        RelaunchPromptDialog.Outcome choice = RelaunchPromptDialog.askUserModally(
            javaSpec,
            JdkJarVersion21Enforcer.MOD_NAME,
            desiredOption,
            Config.FILE_NAME,
            Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED,
            patcherStatus);
        switch (choice) {
            case RESTART_NOW:
                boolean handed = attemptJvmRelaunch(logger, tag, gameDir);
                // exit() should have terminated us already on success. If we are still here, the
                // relaunch fast-failed (or threw); the caller will continue starting the game in
                // the current JVM — better that than a launcher "crashed" dialog with no
                // playable game.
                if (!handed) {
                    logger.error(
                        tag + "Child JVM did not start cleanly; the current JVM will continue running so the game still launches (without `"
                            + desiredOption
                            + "`).");
                }
                ManualLauncherInstructionsPopup.show(JdkJarVersion21Enforcer.MOD_NAME, desiredOption, null);
                return Outcome.DIALOG_RESTART_RETURNED;
            case SUPPRESS_FOREVER:
                cfg.prelaunchRelaunchSuppressed = true;
                try {
                    Config.save(configDir.resolve(Config.FILE_NAME), cfg);
                    logger.info(
                        tag + "User chose 'Don't ask again'; wrote "
                            + Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED
                            + "=true to "
                            + Config.FILE_NAME
                            + ".");
                } catch (Throwable t) {
                    logger.warn(
                        tag + "Failed to persist suppression flag: "
                            + t.getMessage()
                            + ". The dialog will appear again next launch.");
                }
                ManualLauncherInstructionsPopup.show(JdkJarVersion21Enforcer.MOD_NAME, desiredOption, null);
                return Outcome.DIALOG_SUPPRESS_FOREVER;
            case SKIP_ONCE:
            default:
                logger.warn(
                    tag + "User skipped relaunch this time; game will run without `"
                        + desiredOption
                        + "`. Compatibility issues are likely on Java 22+.");
                ManualLauncherInstructionsPopup.show(JdkJarVersion21Enforcer.MOD_NAME, desiredOption, null);
                return Outcome.DIALOG_SKIPPED_ONCE;
        }
    }

    /**
     * Returns true if the child JVM was started and ran long enough to be considered a successful
     * handoff (in which case {@link JvmRelauncher#relaunchAndExit(List, Path,
     * org.apache.logging.log4j.Logger)} will have terminated this JVM and we should never have
     * returned). Returns false on fast-fail or exception, in which case the caller must fall back
     * to keeping the current JVM alive so the launcher does not see a crash.
     */
    private static boolean attemptJvmRelaunch(Logger logger, String tag, Path gameDir) {
        try {
            logger.warn(
                tag + "Forking a child JVM with `-D"
                    + JarVersionPropertyEnforcer.PROPERTY_NAME
                    + "=21` and "
                    + JvmRelauncher.relaunchGuardArg()
                    + ". The current process will exit with the child's exit code on success.");
            java.util.OptionalInt fastFail = JvmRelauncher.relaunchAndExit(
                Arrays.asList(
                    "-D" + JarVersionPropertyEnforcer.PROPERTY_NAME + "=" + JarVersionPropertyEnforcer.REQUIRED_VERSION,
                    JvmRelauncher.relaunchGuardArg()),
                gameDir,
                logger);
            // If we got here at all, the child fast-failed (relaunchAndExit normally never
            // returns on success because it calls System.exit).
            return !fastFail.isPresent();
        } catch (Throwable t) {
            logger.error(
                tag + "Failed to relaunch JVM: "
                    + t.getClass()
                        .getSimpleName()
                    + ": "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * Returns true if a previous Tweaker invocation already handled the client fallback this JVM.
     * {@code preInit} should consult this and skip the dialog/patcher path so we never bother the
     * user twice.
     */
    public static boolean isAlreadyHandled() {
        return "true".equalsIgnoreCase(System.getProperty(HANDLED_PROPERTY));
    }

    /**
     * Reflectively reads {@code net.minecraft.launchwrapper.Launch.blackboard.get(
     * "lwjgl3ify:rfb-booted")}. Returns true only when the value is {@link Boolean#TRUE}, matching
     * lwjgl3ify's own check. All exceptions (LaunchWrapper not on classpath in dev / tests, etc.)
     * map to {@code false} so the rest of the flow proceeds as if RFB were absent.
     *
     * <p>
     * Reflection is used so this class compiles and runs in environments where LaunchWrapper is
     * not present (unit tests, standalone Java Agent usage, etc.).
     */
    public static boolean isRfbBooted() {
        try {
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch");
            Object blackboard = launchClass.getField("blackboard")
                .get(null);
            if (blackboard instanceof java.util.Map) {
                Object value = ((java.util.Map<?, ?>) blackboard).get("lwjgl3ify:rfb-booted");
                return Boolean.TRUE.equals(value);
            }
        } catch (Throwable ignored) {
            // LaunchWrapper not present, blackboard not initialised, etc. Either way, behave as
            // if RFB is not active.
        }
        return false;
    }

    /**
     * Records that this JVM has handled the client fallback once. Set by the Tweaker after it
     * runs (regardless of outcome) so that {@code preInit} won't redo the work.
     */
    public static void markHandled() {
        System.setProperty(HANDLED_PROPERTY, "true");
    }
}
