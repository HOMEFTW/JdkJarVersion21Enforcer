package com.andgatech.jdkjarversion21enforcer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {

    public static final String FILE_NAME = "jdkjarversion21enforcer.cfg";

    public static final String KEY_AUTO_PATCH_LWJGL3IFY = "auto_patch_lwjgl3ify_config";
    public static final String KEY_AUTO_PATCH_SERVER_SCRIPTS = "auto_patch_server_start_scripts";
    public static final String KEY_SHOW_RESTART_POPUP = "show_restart_popup_after_patch";
    public static final String KEY_PRELAUNCH_RELAUNCH_PROMPT = "prelaunch_relaunch_prompt";
    public static final String KEY_PRELAUNCH_RELAUNCH_SUPPRESSED = "prelaunch_relaunch_suppressed";

    public boolean autoPatchLwjgl3ifyConfig = true;
    public boolean autoPatchServerStartScripts = true;
    public boolean showRestartPopupAfterPatch = true;
    public boolean prelaunchRelaunchPrompt = true;
    public boolean prelaunchRelaunchSuppressed = false;

    public static Config loadOrCreate(Path configDir) {
        Config c = new Config();
        Path file = configDir.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                Properties p = new Properties();
                p.load(in);
                c.autoPatchLwjgl3ifyConfig = parseBool(
                    p.getProperty(KEY_AUTO_PATCH_LWJGL3IFY),
                    c.autoPatchLwjgl3ifyConfig);
                c.autoPatchServerStartScripts = parseBool(
                    p.getProperty(KEY_AUTO_PATCH_SERVER_SCRIPTS),
                    c.autoPatchServerStartScripts);
                c.showRestartPopupAfterPatch = parseBool(
                    p.getProperty(KEY_SHOW_RESTART_POPUP),
                    c.showRestartPopupAfterPatch);
                c.prelaunchRelaunchPrompt = parseBool(
                    p.getProperty(KEY_PRELAUNCH_RELAUNCH_PROMPT),
                    c.prelaunchRelaunchPrompt);
                c.prelaunchRelaunchSuppressed = parseBool(
                    p.getProperty(KEY_PRELAUNCH_RELAUNCH_SUPPRESSED),
                    c.prelaunchRelaunchSuppressed);
            } catch (IOException e) {
                JdkJarVersion21Enforcer.LOG.warn("Failed to read " + FILE_NAME + ", using defaults: " + e.getMessage());
            }
        }
        try {
            save(file, c);
        } catch (IOException e) {
            JdkJarVersion21Enforcer.LOG.warn("Failed to (re)write " + FILE_NAME + ": " + e.getMessage());
        }
        return c;
    }

    static boolean parseBool(String raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        String s = raw.trim()
            .toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) return false;
        return defaultValue;
    }

    public static void save(Path file, Config c) throws IOException {
        if (!Files.isDirectory(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# JDK Jar Version 21 Enforcer config\n");
        sb.append("# Lines starting with '#' are comments. Boolean values: true/false/1/0/yes/no/on/off.\n");
        sb.append('\n');
        sb.append("# Client-side: when lwjgl3ify is installed, append `-Djdk.util.jar.version=21` to its\n");
        sb.append("# customOptions (config/lwjgl3ify-relauncher.json) so the next launch picks it up\n");
        sb.append("# automatically. Default: true.\n");
        sb.append(KEY_AUTO_PATCH_LWJGL3IFY)
            .append('=')
            .append(c.autoPatchLwjgl3ifyConfig)
            .append('\n');
        sb.append('\n');
        sb.append("# Server-side: when the running JVM does not already have -Djdk.util.jar.version=21\n");
        sb.append("# or -javaagent:<jdkjarversion21enforcer-*.jar>, scan the working directory for known\n");
        sb.append("# start scripts (start.bat/sh, run.bat/sh, ...) and write a parallel\n");
        sb.append("# <name>-with-jdk-jar-21.<ext> file that injects the option. The original script is\n");
        sb.append("# never modified. Default: true.\n");
        sb.append(KEY_AUTO_PATCH_SERVER_SCRIPTS)
            .append('=')
            .append(c.autoPatchServerStartScripts)
            .append('\n');
        sb.append('\n');
        sb.append("# When a patch was actually applied this run (lwjgl3ify config edited or a\n");
        sb.append("# parallel start script generated), pop up a Swing dialog reminding the user to\n");
        sb.append("# restart the game/server. Auto-skipped on headless environments (e.g. dedicated\n");
        sb.append("# servers without a display). Default: true.\n");
        sb.append(KEY_SHOW_RESTART_POPUP)
            .append('=')
            .append(c.showRestartPopupAfterPatch)
            .append('\n');
        sb.append('\n');
        sb.append("# Client-side fallback when neither -javaagent nor -D was passed AND lwjgl3ify is\n");
        sb.append("# not installed: open a modal Swing dialog with three options (Restart Now /\n");
        sb.append("# Skip / Don't ask again). Choosing \"Restart Now\" forks a fresh JVM with the\n");
        sb.append("# missing -Djdk.util.jar.version=21 added and terminates the current process.\n");
        sb.append("# Auto-skipped on headless environments. Default: true.\n");
        sb.append(KEY_PRELAUNCH_RELAUNCH_PROMPT)
            .append('=')
            .append(c.prelaunchRelaunchPrompt)
            .append('\n');
        sb.append('\n');
        sb.append("# Set to true automatically when the user picks \"Don't ask again\" in the dialog\n");
        sb.append("# above. Manually flip back to false to re-enable the prompt. Default: false.\n");
        sb.append(KEY_PRELAUNCH_RELAUNCH_SUPPRESSED)
            .append('=')
            .append(c.prelaunchRelaunchSuppressed)
            .append('\n');
        Files.write(
            file,
            sb.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
