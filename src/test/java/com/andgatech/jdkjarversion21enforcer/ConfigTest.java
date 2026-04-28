package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void parseBoolAcceptsCommonFormsAndDefaultsOtherwise() {
        assertTrue(Config.parseBool("true", false));
        assertTrue(Config.parseBool("TRUE", false));
        assertTrue(Config.parseBool("1", false));
        assertTrue(Config.parseBool("yes", false));
        assertTrue(Config.parseBool("on", false));
        assertFalse(Config.parseBool("false", true));
        assertFalse(Config.parseBool("0", true));
        assertFalse(Config.parseBool("no", true));
        assertFalse(Config.parseBool("off", true));
        assertEquals(true, Config.parseBool(null, true));
        assertEquals(false, Config.parseBool(null, false));
        assertEquals(true, Config.parseBool("garbage", true));
    }

    @Test
    void loadOrCreateMaterializesDefaultsWhenAbsent(@TempDir Path tmp) throws IOException {
        Config c = Config.loadOrCreate(tmp);
        assertTrue(c.autoPatchLwjgl3ifyConfig);
        assertTrue(c.autoPatchServerStartScripts);
        assertTrue(c.showRestartPopupAfterPatch);
        assertTrue(c.prelaunchRelaunchPrompt);
        assertFalse(c.prelaunchRelaunchSuppressed);

        Path file = tmp.resolve(Config.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(content.contains(Config.KEY_AUTO_PATCH_LWJGL3IFY + "=true"));
        assertTrue(content.contains(Config.KEY_AUTO_PATCH_SERVER_SCRIPTS + "=true"));
        assertTrue(content.contains(Config.KEY_SHOW_RESTART_POPUP + "=true"));
        assertTrue(content.contains(Config.KEY_PRELAUNCH_RELAUNCH_PROMPT + "=true"));
        assertTrue(content.contains(Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED + "=false"));
    }

    @Test
    void loadOrCreatePreservesUserOverrides(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(Config.FILE_NAME);
        Files.write(
            file,
            (Config.KEY_AUTO_PATCH_LWJGL3IFY + "=false\n"
                + Config.KEY_AUTO_PATCH_SERVER_SCRIPTS
                + "=false\n"
                + Config.KEY_SHOW_RESTART_POPUP
                + "=false\n"
                + Config.KEY_PRELAUNCH_RELAUNCH_PROMPT
                + "=false\n"
                + Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED
                + "=true\n").getBytes(StandardCharsets.UTF_8));

        Config c = Config.loadOrCreate(tmp);
        assertFalse(c.autoPatchLwjgl3ifyConfig);
        assertFalse(c.autoPatchServerStartScripts);
        assertFalse(c.showRestartPopupAfterPatch);
        assertFalse(c.prelaunchRelaunchPrompt);
        assertTrue(c.prelaunchRelaunchSuppressed);

        // Saved version should reflect the loaded values, not the defaults.
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(content.contains(Config.KEY_AUTO_PATCH_LWJGL3IFY + "=false"));
        assertTrue(content.contains(Config.KEY_AUTO_PATCH_SERVER_SCRIPTS + "=false"));
        assertTrue(content.contains(Config.KEY_SHOW_RESTART_POPUP + "=false"));
        assertTrue(content.contains(Config.KEY_PRELAUNCH_RELAUNCH_PROMPT + "=false"));
        assertTrue(content.contains(Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED + "=true"));
    }
}
