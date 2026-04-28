package com.andgatech.jdkjarversion21enforcer;

import java.io.File;
import java.nio.file.Path;
import java.util.OptionalInt;

import com.andgatech.jdkjarversion21enforcer.integration.ServerStartScriptPatcher;
import com.andgatech.jdkjarversion21enforcer.relaunch.RelaunchService;
import com.andgatech.jdkjarversion21enforcer.ui.RestartPopup;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.relauncher.Side;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        String activationLogMessage = activationLogMessage(System.getProperty("java.specification.version"));
        if (activationLogMessage == null) return;

        JarVersionPropertyEnforcer.enforce();
        JdkJarVersion21Enforcer.LOG.info(activationLogMessage);
        JdkJarVersion21Enforcer.LOG
            .info(JdkJarVersion21Enforcer.MOD_NAME + " at version " + JdkJarVersion21Enforcer.VERSION);

        OptionalInt effective = JarVersionPropertyEnforcer.detectEffectiveJarRuntimeFeatureVersion();
        VerificationOutcome outcome = classifyVerification(effective);
        switch (outcome) {
            case EFFECTIVE:
                JdkJarVersion21Enforcer.LOG.info(verificationInfoMessage(effective.getAsInt()));
                // Already effective; no further patching needed.
                return;
            case INEFFECTIVE:
                JdkJarVersion21Enforcer.LOG.warn(verificationWarnMessage(effective.getAsInt()));
                break;
            case UNKNOWN:
            default:
                // Pre-Java 9 or reflective failure; cannot tell. Run patchers anyway, they are no-ops
                // when nothing matches.
                break;
        }

        runAutoPatchersIfPossible(event);
    }

    void runAutoPatchersIfPossible(FMLPreInitializationEvent event) {
        if (event == null) return;
        File configDirFile = event.getModConfigurationDirectory();
        if (configDirFile == null) return;
        Path configDir = configDirFile.toPath();
        Path gameDir = configDir.getParent();
        if (gameDir == null) gameDir = configDir;

        Config cfg = Config.loadOrCreate(configDir);
        Side side = currentSide();

        if (side == Side.SERVER) {
            if (!cfg.autoPatchServerStartScripts) {
                JdkJarVersion21Enforcer.LOG
                    .info("auto_patch_server_start_scripts=false; skipping server script patcher.");
                return;
            }
            runServerScriptPatcher(gameDir, cfg);
            return;
        }

        // Client side: the Tweaker (loaded by LaunchWrapper before Forge mod loading) usually
        // handles this already. preInit only runs the client fallback if the Tweaker did not.
        if (RelaunchService.isAlreadyHandled()) {
            JdkJarVersion21Enforcer.LOG
                .info("Client fallback already handled by the Tweaker stage; preInit will not retry.");
            return;
        }
        RelaunchService.runClientFlow(gameDir, configDir, RelaunchService.Phase.PRE_INIT, JdkJarVersion21Enforcer.LOG);
        RelaunchService.markHandled();
    }

    Side currentSide() {
        try {
            return FMLCommonHandler.instance()
                .getSide();
        } catch (Throwable t) {
            return Side.CLIENT;
        }
    }

    private void runServerScriptPatcher(Path gameDir, Config cfg) {
        ServerStartScriptPatcher.Outcome outcome = ServerStartScriptPatcher.run(gameDir);
        switch (outcome.result) {
            case PATCHED:
                StringBuilder sb = new StringBuilder();
                sb.append("Generated parallel start scripts with `")
                    .append(ServerStartScriptPatcher.D_OPTION)
                    .append("` injected:");
                for (Path p : outcome.patchedFiles) {
                    sb.append("\n  - ")
                        .append(p);
                }
                sb.append(
                    "\nThe original scripts were left untouched. Stop the server and re-launch with one of the generated scripts to make `")
                    .append(ServerStartScriptPatcher.D_OPTION)
                    .append("` actually take effect.");
                JdkJarVersion21Enforcer.LOG.warn(sb.toString());
                // Dedicated servers are typically headless so RestartPopup will silently skip; we
                // still try, in case the operator runs the server with a desktop attached.
                if (cfg.showRestartPopupAfterPatch) {
                    StringBuilder popup = new StringBuilder();
                    popup.append("JDK Jar Version 21 Enforcer just generated the following parallel start scripts:\n");
                    for (Path p : outcome.patchedFiles) {
                        popup.append("  - ")
                            .append(p)
                            .append('\n');
                    }
                    popup.append(
                        "\nThe original scripts were left untouched.\nStop the server and relaunch using one of the generated scripts.\n\n"
                            + "(You can disable this popup by setting ")
                        .append(Config.KEY_SHOW_RESTART_POPUP)
                        .append("=false in\nconfig/")
                        .append(Config.FILE_NAME)
                        .append(".)");
                    RestartPopup.showRestartReminder(
                        JdkJarVersion21Enforcer.MOD_NAME + " — Restart required",
                        popup.toString());
                }
                break;
            case ALREADY_OK:
                JdkJarVersion21Enforcer.LOG.info(
                    "All known server start scripts already include the option or have a previously generated parallel copy.");
                break;
            case NO_SCRIPTS_FOUND:
                JdkJarVersion21Enforcer.LOG.info(
                    "No known server start scripts (start.bat/sh, run.bat/sh, ...) were found in the working directory; cannot auto-generate. Add `-D"
                        + JarVersionPropertyEnforcer.PROPERTY_NAME
                        + "="
                        + JarVersionPropertyEnforcer.REQUIRED_VERSION
                        + "` or `-javaagent:<this jar>` to your start command manually.");
                break;
            case ERROR:
            default:
                JdkJarVersion21Enforcer.LOG
                    .warn("Failed while generating parallel server start scripts. Patch the JVM args manually.");
                break;
        }
    }

    public void init(FMLInitializationEvent event) {
        // No regular Forge registrations are needed.
    }

    public void postInit(FMLPostInitializationEvent event) {
        // Register networking, cross-mod integration, etc.
    }

    public void complete(FMLLoadCompleteEvent event) {
        // No recipes are provided by this mod.
    }

    public void serverStarting(FMLServerStartingEvent event) {
        if (!JarVersionPropertyEnforcer.shouldActivate()) {
            return;
        }
        JdkJarVersion21Enforcer.LOG.info(JdkJarVersion21Enforcer.MOD_NAME + " loaded.");
    }

    public void serverStarted(FMLServerStartedEvent event) {
        // Server-side initialization
    }

    static String activationLogMessage(String javaSpecificationVersion) {
        if (!JavaRuntimeVersion.isAbove21(javaSpecificationVersion)) {
            return null;
        }
        return "Java " + JavaRuntimeVersion.majorVersion(javaSpecificationVersion)
            + " detected; forced "
            + JarVersionPropertyEnforcer.PROPERTY_NAME
            + "="
            + JarVersionPropertyEnforcer.REQUIRED_VERSION
            + ".";
    }

    enum VerificationOutcome {
        EFFECTIVE,
        INEFFECTIVE,
        UNKNOWN
    }

    static VerificationOutcome classifyVerification(OptionalInt effectiveFeatureVersion) {
        if (!effectiveFeatureVersion.isPresent()) return VerificationOutcome.UNKNOWN;
        if (effectiveFeatureVersion.getAsInt() == JarVersionPropertyEnforcer.REQUIRED_FEATURE_VERSION) {
            return VerificationOutcome.EFFECTIVE;
        }
        return VerificationOutcome.INEFFECTIVE;
    }

    static String verificationInfoMessage(int effectiveFeatureVersion) {
        return "Verified java.util.jar.JarFile.runtimeVersion().feature() = " + effectiveFeatureVersion
            + "; "
            + JarVersionPropertyEnforcer.PROPERTY_NAME
            + "="
            + JarVersionPropertyEnforcer.REQUIRED_VERSION
            + " is effective.";
    }

    static String verificationWarnMessage(int effectiveFeatureVersion) {
        return "java.util.jar.JarFile.runtimeVersion().feature() is " + effectiveFeatureVersion
            + " but "
            + JarVersionPropertyEnforcer.REQUIRED_FEATURE_VERSION
            + " was requested. The system property was set too late to take effect."
            + " To make it actually work, add `-javaagent:<path-to-this-jar.jar>` to your JVM arguments,"
            + " or pass `-D"
            + JarVersionPropertyEnforcer.PROPERTY_NAME
            + "="
            + JarVersionPropertyEnforcer.REQUIRED_VERSION
            + "` directly. This jar is itself a valid Java Agent.";
    }

}
