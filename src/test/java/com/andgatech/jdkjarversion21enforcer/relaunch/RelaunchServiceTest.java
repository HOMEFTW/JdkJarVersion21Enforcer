package com.andgatech.jdkjarversion21enforcer.relaunch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.andgatech.jdkjarversion21enforcer.Config;
import com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer;
import com.andgatech.jdkjarversion21enforcer.integration.Lwjgl3ifyConfigPatcher;

/**
 * Tests for {@link RelaunchService}. The test JVM is forced headless via
 * {@code -Djava.awt.headless=true} (see build.gradle), so any branch that would otherwise pop a
 * Swing dialog deterministically returns {@link RelaunchService.Outcome#DIALOG_HEADLESS}.
 *
 * <p>
 * Branches that depend on a "high enough" {@code java.specification.version} are exercised by
 * temporarily overriding the system property in {@code @BeforeEach} / {@code @AfterEach}.
 */
class RelaunchServiceTest {

    private String originalJavaSpec;
    private String originalGuard;
    private String originalHandled;
    private String originalJarVersionProperty;

    @BeforeEach
    void snapshotProps() {
        originalJavaSpec = System.getProperty("java.specification.version");
        originalGuard = System.getProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
        originalHandled = System.getProperty(RelaunchService.HANDLED_PROPERTY);
        originalJarVersionProperty = System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
        // Always start each test with a clean handled flag.
        System.clearProperty(RelaunchService.HANDLED_PROPERTY);
        System.clearProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
    }

    @AfterEach
    void restoreProps() {
        restore("java.specification.version", originalJavaSpec);
        restore(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, originalGuard);
        restore(RelaunchService.HANDLED_PROPERTY, originalHandled);
        restore(JarVersionPropertyEnforcer.PROPERTY_NAME, originalJarVersionProperty);
    }

    private static void restore(String key, String prev) {
        if (prev == null) System.clearProperty(key);
        else System.setProperty(key, prev);
    }

    @Test
    void handledFlagDefaultsFalseAndCanBeMarked() {
        assertFalse(RelaunchService.isAlreadyHandled());
        RelaunchService.markHandled();
        assertTrue(RelaunchService.isAlreadyHandled());
    }

    @Test
    void java21ShortCircuits(@TempDir Path tmp) {
        System.setProperty("java.specification.version", "21");
        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, tmp.resolve("config"), RelaunchService.Phase.PRE_INIT, null);
        assertEquals(RelaunchService.Outcome.SKIPPED_JAVA_LE_21, outcome);
    }

    @Test
    void relaunchedChildShortCircuits(@TempDir Path tmp) {
        System.setProperty("java.specification.version", "22");
        System.setProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, "true");
        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, tmp.resolve("config"), RelaunchService.Phase.TWEAKER, null);
        assertEquals(RelaunchService.Outcome.SKIPPED_RELAUNCHED_CHILD, outcome);
    }

    @Test
    void propertyAlreadyOnCommandLineShortCircuits(@TempDir Path tmp) {
        System.setProperty("java.specification.version", "22");
        // Simulate -Djdk.util.jar.version=21 being on the JVM command line. This is what GTNH
        // RFB integration packs effectively do (one way or another) and what users get when they
        // manually pass the flag.
        System.setProperty(JarVersionPropertyEnforcer.PROPERTY_NAME, JarVersionPropertyEnforcer.REQUIRED_VERSION);
        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, tmp.resolve("config"), RelaunchService.Phase.TWEAKER, null);
        assertEquals(RelaunchService.Outcome.SKIPPED_PROPERTY_ALREADY_SET, outcome);
    }

    @Test
    void rfbBootedDoesNotShortCircuitInV052(@TempDir Path tmp) {
        // v0.5.2: RFB mode is *not* a reason to skip. We only log an INFO note. The flow must
        // continue into the patcher / dialog branches, exactly as if RFB were absent. Real-world
        // testing of GTNH official Java 17-25 integration packs showed that they may NOT pass
        // -Djdk.util.jar.version=21 on the JVM command line even in RFB mode, leaving
        // JarFile.RUNTIME_VERSION wrong. We must therefore enforce ourselves regardless.
        Object previousValue = null;
        boolean blackboardAvailable = false;
        java.util.Map<String, Object> blackboard = null;
        try {
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch");
            Object bb = launchClass.getField("blackboard")
                .get(null);
            if (bb == null) {
                bb = new java.util.HashMap<String, Object>();
                launchClass.getField("blackboard")
                    .set(null, bb);
            }
            if (bb instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> typed = (java.util.Map<String, Object>) bb;
                blackboard = typed;
                previousValue = typed.get("lwjgl3ify:rfb-booted");
                typed.put("lwjgl3ify:rfb-booted", Boolean.TRUE);
                blackboardAvailable = true;
            }
        } catch (Throwable ignored) {
            // LaunchWrapper not on the test classpath; we can still assert the no-RFB path.
        }

        try {
            System.setProperty("java.specification.version", "22");
            System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
            // Empty game dir => no lwjgl3ify-relauncher.json => Lwjgl3ifyConfigPatcher returns
            // NO_CONFIG and we fall through to the dialog branch. The test JVM is headless, so
            // the dialog branch deterministically returns DIALOG_HEADLESS.
            RelaunchService.Outcome outcome = RelaunchService
                .runClientFlow(tmp, tmp.resolve("config"), RelaunchService.Phase.TWEAKER, null);
            // Crucially, this MUST NOT be a "skipped because RFB" outcome. The exact non-skip
            // outcome depends on whether headless / config files exist; in this test setup it is
            // DIALOG_HEADLESS.
            assertEquals(RelaunchService.Outcome.DIALOG_HEADLESS, outcome);
            // Side-assertion: the helper still correctly identifies RFB. Only meaningful if the
            // blackboard was wired up.
            if (blackboardAvailable) {
                assertTrue(RelaunchService.isRfbBooted());
            }
        } finally {
            if (blackboard != null) {
                if (previousValue == null) {
                    blackboard.remove("lwjgl3ify:rfb-booted");
                } else {
                    blackboard.put("lwjgl3ify:rfb-booted", previousValue);
                }
            }
        }
    }

    @Test
    void lwjgl3ifyConfigGetsPatchedAndFlowReachesDialog(@TempDir Path tmp) throws IOException {
        // v0.5.3: the patcher no longer short-circuits the flow. We expect lwjgl3ify config to be
        // updated AND the flow to fall through to the dialog branch (here: DIALOG_HEADLESS in the
        // test JVM). Asserting both at once protects against regressions in either step.
        System.setProperty("java.specification.version", "22");
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        Files.write(
            config.resolve("lwjgl3ify-relauncher.json"),
            "{\n  \"customOptions\": []\n}".getBytes(StandardCharsets.UTF_8));

        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, config, RelaunchService.Phase.TWEAKER, null);
        assertEquals(RelaunchService.Outcome.DIALOG_HEADLESS, outcome);

        String written = new String(
            Files.readAllBytes(config.resolve("lwjgl3ify-relauncher.json")),
            StandardCharsets.UTF_8);
        assertTrue(written.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
    }

    @Test
    void lwjgl3ifyConfigAlreadyPresentStillReachesDialog(@TempDir Path tmp) throws IOException {
        // v0.5.3: ALREADY_PRESENT also falls through (the option is in the file but not yet
        // effective in the current JVM, so we still need to ask the user about a relaunch).
        System.setProperty("java.specification.version", "22");
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        String original = "{\n  \"customOptions\": [\"" + Lwjgl3ifyConfigPatcher.DESIRED_OPTION + "\"]\n}";
        Files.write(config.resolve("lwjgl3ify-relauncher.json"), original.getBytes(StandardCharsets.UTF_8));

        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, config, RelaunchService.Phase.TWEAKER, null);
        assertEquals(RelaunchService.Outcome.DIALOG_HEADLESS, outcome);

        // The file should not have been modified (idempotent).
        String written = new String(
            Files.readAllBytes(config.resolve("lwjgl3ify-relauncher.json")),
            StandardCharsets.UTF_8);
        assertEquals(original, written);
    }

    @Test
    void missingLwjgl3ifyConfigGetsCreated(@TempDir Path tmp) throws IOException {
        // v0.5.3: when lwjgl3ify-relauncher.json is missing, the patcher creates a fresh skeleton
        // pre-filled with -Djdk.util.jar.version=21. The flow then continues to the dialog branch
        // (DIALOG_HEADLESS in the test JVM). This persists the option for future non-RFB launches
        // without bothering the user about manual launcher edits.
        System.setProperty("java.specification.version", "22");
        Path config = tmp.resolve("config");
        // Intentionally do NOT create config dir up-front; patchOrCreate must do it.

        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, config, RelaunchService.Phase.TWEAKER, null);
        assertEquals(RelaunchService.Outcome.DIALOG_HEADLESS, outcome);

        Path created = config.resolve("lwjgl3ify-relauncher.json");
        assertTrue(Files.exists(created));
        String written = new String(Files.readAllBytes(created), StandardCharsets.UTF_8);
        assertTrue(written.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
    }

    @Test
    void noLwjgl3ifyButPromptDisabledByConfig(@TempDir Path tmp) throws IOException {
        System.setProperty("java.specification.version", "22");
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        Files.write(
            config.resolve(Config.FILE_NAME),
            (Config.KEY_PRELAUNCH_RELAUNCH_PROMPT + "=false\n").getBytes(StandardCharsets.UTF_8));

        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, config, RelaunchService.Phase.PRE_INIT, null);
        assertEquals(RelaunchService.Outcome.DIALOG_DISABLED, outcome);
    }

    @Test
    void noLwjgl3ifyAndSuppressionFlagIsRespected(@TempDir Path tmp) throws IOException {
        System.setProperty("java.specification.version", "22");
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        Files.write(
            config.resolve(Config.FILE_NAME),
            (Config.KEY_PRELAUNCH_RELAUNCH_SUPPRESSED + "=true\n").getBytes(StandardCharsets.UTF_8));

        RelaunchService.Outcome outcome = RelaunchService
            .runClientFlow(tmp, config, RelaunchService.Phase.PRE_INIT, null);
        assertEquals(RelaunchService.Outcome.DIALOG_SUPPRESSED, outcome);
    }
}
