package com.andgatech.jdkjarversion21enforcer.relaunch;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Logger;

/**
 * Captures the parsed Minecraft launch arguments at LaunchWrapper Tweaker time, so that
 * {@link JvmRelauncher} can reconstruct a faithful child-JVM command line without depending on
 * {@code ProcessHandle.info().arguments()} (which Windows often denies for the current process,
 * yielding {@code Optional.empty()}) or splitting {@code sun.java.command} by spaces (which
 * destroys quoted args such as {@code --version "GT New Horizons 2.8.4"}).
 *
 * <p>
 * LaunchWrapper invokes {@code ITweaker.acceptOptions(List<String> args, File gameDir, File
 * assetsDir, String profile)} after its {@code OptionParser} has consumed the well-known options
 * ({@code --version}, {@code --gameDir}, {@code --assetsDir}, {@code --tweakClass}). The remaining
 * {@code args} list is the leftover non-option args, with each {@code String} element preserving
 * its original boundaries (so {@code --username} and {@code --accessToken} pairs stay intact even
 * if their values contained whitespace originally — though Minecraft never does this in
 * practice). Combined with the three out-of-band values, we have enough material to rebuild the
 * complete launch arg list.
 *
 * <p>
 * The {@code --tweakClass} value(s) are read reflectively from
 * {@link net.minecraft.launchwrapper.Launch} static fields — first {@code tweakClassNames}
 * (modern LaunchWrapper), then a heuristic fallback to {@code cpw.mods.fml.common.launcher
 * .FMLTweaker} (the only tweaker GTNH/Forge environments ever require at the JVM-args boundary).
 */
public final class LaunchArgsCapture {

    private static volatile boolean captured = false;
    private static List<String> nonOptionArgs = Collections.emptyList();
    private static String profile;
    private static String gameDirPath;
    private static String assetsDirPath;
    private static List<String> tweakClassNames = Collections.emptyList();

    private LaunchArgsCapture() {}

    /**
     * Called from {@code JdkJarVersion21Tweaker.acceptOptions(...)} with whatever LaunchWrapper
     * passed us. Subsequent calls overwrite earlier ones (the last Tweaker wins, but in practice
     * we only ever capture once because LaunchWrapper invokes each Tweaker exactly once).
     */
    public static void capture(List<String> args, File gameDir, File assetsDir, String prof, Logger logger) {
        nonOptionArgs = (args == null) ? Collections.<String>emptyList() : new ArrayList<>(args);
        profile = prof;
        gameDirPath = (gameDir != null) ? gameDir.getAbsolutePath() : null;
        assetsDirPath = (assetsDir != null) ? assetsDir.getAbsolutePath() : null;
        tweakClassNames = readTweakClassNames(logger);
        captured = true;
        if (logger != null) {
            logger.info(
                "[launch-args] captured at Tweaker stage: profile={}, gameDir={}, assetsDir={}, tweakers={}, nonOptions ({} tokens):",
                profile,
                gameDirPath,
                assetsDirPath,
                tweakClassNames,
                nonOptionArgs.size());
            for (int i = 0; i < nonOptionArgs.size(); i++) {
                logger.info("[launch-args]     nonOpt[{}] = {}", i, nonOptionArgs.get(i));
            }
        }
    }

    /**
     * Reconstructs the launch-time main args. Returns {@code null} if {@link #capture} was never
     * called (e.g. our Tweaker did not run for some reason); callers should fall back to less
     * reliable sources.
     */
    public static List<String> rebuildMainArgs() {
        if (!captured) return null;
        List<String> out = new ArrayList<>();
        if (profile != null) {
            out.add("--version");
            out.add(profile);
        }
        if (gameDirPath != null) {
            out.add("--gameDir");
            out.add(gameDirPath);
        }
        if (assetsDirPath != null) {
            out.add("--assetsDir");
            out.add(assetsDirPath);
        }
        Set<String> tweakClassesEmitted = new LinkedHashSet<>();
        for (String tc : tweakClassNames) {
            if (tc == null || tc.isEmpty()) continue;
            if (tweakClassesEmitted.add(tc)) {
                out.add("--tweakClass");
                out.add(tc);
            }
        }
        // nonOptionArgs include things like `--username <name> --uuid <uuid> --accessToken <tok>
        // --userType mojang --userProperties {}`. Pass them through verbatim — the List<String>
        // already has each token as a separate element.
        out.addAll(nonOptionArgs);
        return out;
    }

    public static boolean isCaptured() {
        return captured;
    }

    /**
     * Reads the set of {@code --tweakClass} arguments LaunchWrapper has already collected.
     * Modern LaunchWrapper exposes this as {@code Launch.tweakClassNames}; if reflection cannot
     * find it, we fall back to a hard-coded {@code cpw.mods.fml.common.launcher.FMLTweaker}
     * (which every Forge / GTNH environment uses). Returns a defensive copy.
     */
    static List<String> readTweakClassNames(Logger logger) {
        try {
            Class<?> launchCls = Class.forName("net.minecraft.launchwrapper.Launch");
            for (String fieldName : new String[] { "tweakClassNames", "tweakClasses", "tweaks" }) {
                try {
                    Field f = launchCls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value instanceof List) {
                        List<?> list = (List<?>) value;
                        List<String> out = new ArrayList<>(list.size());
                        for (Object o : list) {
                            if (o instanceof String) out.add((String) o);
                        }
                        if (!out.isEmpty()) {
                            if (logger != null) logger
                                .info("[launch-args] read Launch.{} ({} entries): {}", fieldName, out.size(), out);
                            return out;
                        }
                    }
                } catch (NoSuchFieldException ignored) {
                    // try next
                }
            }
            if (logger != null) logger.warn(
                "[launch-args] could not find any tweakClass list field on net.minecraft.launchwrapper.Launch; falling back to FMLTweaker");
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[launch-args] tweakClass reflection failed: {}: {}",
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
        }
        return Collections.singletonList("cpw.mods.fml.common.launcher.FMLTweaker");
    }

    /** Test-only: reset captured state. */
    static void resetForTesting() {
        captured = false;
        nonOptionArgs = Collections.emptyList();
        profile = null;
        gameDirPath = null;
        assetsDirPath = null;
        tweakClassNames = Collections.emptyList();
    }
}
