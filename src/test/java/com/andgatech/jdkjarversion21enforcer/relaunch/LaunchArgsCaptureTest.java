package com.andgatech.jdkjarversion21enforcer.relaunch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LaunchArgsCaptureTest {

    @BeforeEach
    void reset() {
        LaunchArgsCapture.resetForTesting();
    }

    @AfterEach
    void cleanup() {
        LaunchArgsCapture.resetForTesting();
    }

    @Test
    void rebuildMainArgsReturnsNullBeforeCapture() {
        assertFalse(LaunchArgsCapture.isCaptured());
        assertNull(LaunchArgsCapture.rebuildMainArgs());
    }

    @Test
    void rebuildMainArgsAssemblesAllSourcesInExpectedOrder() {
        List<String> nonOptions = Arrays.asList(
            "--username",
            "C0ntr0l4L",
            "--uuid",
            "abcd-1234",
            "--accessToken",
            "token-with-no-spaces",
            "--userType",
            "msa",
            "--userProperties",
            "{}");
        File gameDir = new File("F:/games/.minecraft");
        File assetsDir = new File("F:/games/.minecraft/assets");

        LaunchArgsCapture.capture(nonOptions, gameDir, assetsDir, "GT New Horizons 2.8.4", null);
        assertTrue(LaunchArgsCapture.isCaptured());

        List<String> rebuilt = LaunchArgsCapture.rebuildMainArgs();
        assertNotNull(rebuilt);
        // First: --version <profile> (preserved with spaces because it was a single String).
        int versionIdx = rebuilt.indexOf("--version");
        assertTrue(versionIdx >= 0);
        assertEquals("GT New Horizons 2.8.4", rebuilt.get(versionIdx + 1));

        // --gameDir / --assetsDir paired up.
        int gameDirIdx = rebuilt.indexOf("--gameDir");
        assertTrue(gameDirIdx >= 0);
        assertEquals(gameDir.getAbsolutePath(), rebuilt.get(gameDirIdx + 1));
        int assetsDirIdx = rebuilt.indexOf("--assetsDir");
        assertTrue(assetsDirIdx >= 0);
        assertEquals(assetsDir.getAbsolutePath(), rebuilt.get(assetsDirIdx + 1));

        // --tweakClass should come from the FMLTweaker fallback (no real Launch class loaded).
        int tweakClassIdx = rebuilt.indexOf("--tweakClass");
        assertTrue(tweakClassIdx >= 0);
        assertEquals("cpw.mods.fml.common.launcher.FMLTweaker", rebuilt.get(tweakClassIdx + 1));

        // All non-option tokens are appended verbatim.
        for (String t : nonOptions) {
            assertTrue(rebuilt.contains(t), "expected non-option token in rebuilt: " + t);
        }
    }

    @Test
    void rebuildMainArgsHandlesNullArgsAndDirs() {
        LaunchArgsCapture.capture(null, null, null, null, null);
        assertTrue(LaunchArgsCapture.isCaptured());
        List<String> rebuilt = LaunchArgsCapture.rebuildMainArgs();
        assertNotNull(rebuilt);
        // No --version / --gameDir / --assetsDir entries, but FMLTweaker fallback still emitted.
        assertFalse(rebuilt.contains("--version"));
        assertFalse(rebuilt.contains("--gameDir"));
        assertFalse(rebuilt.contains("--assetsDir"));
        assertTrue(rebuilt.contains("--tweakClass"));
        assertTrue(rebuilt.contains("cpw.mods.fml.common.launcher.FMLTweaker"));
    }

    @Test
    void rebuildMainArgsPreservesEmptyNonOptionsList() {
        LaunchArgsCapture.capture(Collections.<String>emptyList(), new File("/g"), new File("/a"), "p", null);
        List<String> rebuilt = LaunchArgsCapture.rebuildMainArgs();
        assertNotNull(rebuilt);
        // 2 (--version p) + 2 (--gameDir /g) + 2 (--assetsDir /a) + 2 (--tweakClass FMLTweaker) = 8
        assertEquals(8, rebuilt.size());
    }

    @Test
    void readTweakClassNamesFallsBackToFmlTweaker() {
        // No Launch class on classpath in tests -> heuristic fallback.
        List<String> tweakers = LaunchArgsCapture.readTweakClassNames(null);
        assertEquals(Collections.singletonList("cpw.mods.fml.common.launcher.FMLTweaker"), tweakers);
    }
}
