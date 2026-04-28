package com.andgatech.jdkjarversion21enforcer.tweaker;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import com.andgatech.jdkjarversion21enforcer.JdkJarVersion21Enforcer;
import com.andgatech.jdkjarversion21enforcer.relaunch.LaunchArgsCapture;
import com.andgatech.jdkjarversion21enforcer.relaunch.RelaunchService;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;

/**
 * LaunchWrapper Tweaker entry point. This is the same mechanism lwjgl3ify uses to run code
 * <i>before</i> Forge starts loading mods, despite living in {@code mods/}: FML's
 * {@code CoreModManager} reads {@code TweakClass: ...} from each mod jar's manifest, adds the
 * jar to {@code Launch.classLoader}, and queues the class as an additional Tweaker. LaunchWrapper
 * then invokes its {@link #injectIntoClassLoader(LaunchClassLoader)} after FMLTweaker has
 * finished its own injection.
 *
 * <p>
 * This Tweaker delegates all real work to {@link RelaunchService#runClientFlow}, which performs
 * one of:
 *
 * <ul>
 * <li>Append {@code -Djdk.util.jar.version=21} to lwjgl3ify's {@code customOptions} JSON if
 * lwjgl3ify is installed (the patcher is the cheapest, least intrusive fix).</li>
 * <li>Pop a modal Swing dialog and fork a fresh JVM with the missing argument when lwjgl3ify is
 * <i>not</i> installed and the user did not previously suppress the prompt.</li>
 * </ul>
 *
 * <p>
 * <b>Crucially, this method must not touch {@code java.util.jar.JarFile} reflectively</b> (see
 * the warning on {@link com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer
 * #detectEffectiveJarRuntimeFeatureVersion()}) because doing so locks {@code RUNTIME_VERSION}
 * forever before our child JVM can be spawned.
 */
public final class JdkJarVersion21Tweaker implements ITweaker {

    private File gameDir;

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        // LaunchWrapper hands us the parsed Minecraft launch arguments. We forward all four to
        // LaunchArgsCapture so that JvmRelauncher can rebuild a faithful child-JVM main-args list
        // without depending on ProcessHandle (which Windows often denies for the current process)
        // or on splitting sun.java.command (which destroys quoted args like
        // `--version "GT New Horizons 2.8.4"`).
        this.gameDir = gameDir;
        try {
            LaunchArgsCapture.capture(args, gameDir, assetsDir, profile, JdkJarVersion21Enforcer.LOG);
        } catch (Throwable t) {
            // Capturing args is best-effort; never let it break LaunchWrapper boot.
            try {
                JdkJarVersion21Enforcer.LOG.warn(
                    "LaunchArgsCapture.capture failed: " + t.getClass()
                        .getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        try {
            run();
        } catch (Throwable t) {
            // Never let our optional UX break LaunchWrapper / Forge bootstrap. preInit will get
            // another shot at the same logic.
            try {
                JdkJarVersion21Enforcer.LOG.error(
                    "Tweaker stage failed: " + t.getClass()
                        .getSimpleName() + ": " + t.getMessage(),
                    t);
            } catch (Throwable ignored) {
                // Logger may itself fail if log4j is in a weird state at Tweaker time; we still
                // need to keep going.
            }
        } finally {
            // Mark handled regardless of success so preInit does not retry and double-prompt.
            RelaunchService.markHandled();
        }
    }

    @Override
    public String getLaunchTarget() {
        // We never become the main launch target; FMLTweaker / RfbTweaker do.
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }

    private void run() {
        // Server-side launches do not get a GUI fallback or lwjgl3ify-config rewrite. The server
        // path is handled by ServerStartScriptPatcher in preInit (which has access to the proper
        // configuration directory).
        if (isServerLaunch()) {
            return;
        }

        File dir = (gameDir != null && gameDir.isDirectory()) ? gameDir : new File(".");
        Path gameDirPath = dir.toPath()
            .toAbsolutePath();
        Path configDirPath = gameDirPath.resolve("config");

        RelaunchService
            .runClientFlow(gameDirPath, configDirPath, RelaunchService.Phase.TWEAKER, JdkJarVersion21Enforcer.LOG);
    }

    private static boolean isServerLaunch() {
        try {
            Side side = FMLLaunchHandler.side();
            return side != null && side.isServer();
        } catch (Throwable t) {
            // If FMLLaunchHandler is not yet ready, assume client. The fallback flow is gated on
            // its own checks (Java >= 22, presence of game dir, etc.) so a false negative here is
            // safe.
            return false;
        }
    }
}
