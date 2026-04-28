package com.andgatech.jdkjarversion21enforcer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RelaunchPromptDialogTest {

    @Test
    void askUserModallyDefaultsToSkipUnderHeadless() {
        RelaunchPromptDialog.Outcome outcome = RelaunchPromptDialog.askUserModally(
            "25",
            "JDK Jar Version 21 Enforcer",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.CREATED);
        assertEquals(RelaunchPromptDialog.Outcome.SKIP_ONCE, outcome);
    }

    @Test
    void buildMessageMentionsJavaVersionAndDesiredOption() {
        String msg = RelaunchPromptDialog.buildMessage(
            "25",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.CREATED);
        assertTrue(msg.contains("Java 25"), msg);
        assertTrue(msg.contains("-Djdk.util.jar.version=21"), msg);
        assertTrue(msg.contains("jdkjarversion21enforcer.cfg"), msg);
        assertTrue(msg.contains("prelaunch_relaunch_suppressed"), msg);
        assertTrue(msg.contains("Apply & Restart Now"), msg);
        assertTrue(msg.contains("Skip"), msg);
        assertTrue(msg.contains("Don't ask again"), msg);
    }

    @Test
    void buildMessageReflectsPatcherStatusVariants() {
        // CREATED branch must mention that a fresh file was created.
        String created = RelaunchPromptDialog.buildMessage(
            "25",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.CREATED);
        assertTrue(created.contains("created"), created);

        // APPLIED branch should explain we just appended the option.
        String applied = RelaunchPromptDialog.buildMessage(
            "25",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.APPLIED);
        assertTrue(applied.contains("appended"), applied);

        // ALREADY_PRESENT branch should mention the option was already there.
        String already = RelaunchPromptDialog.buildMessage(
            "25",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.ALREADY_PRESENT);
        assertTrue(already.contains("already contains"), already);

        // NO_CONFIG_OR_ERROR branch should signal that persistence failed.
        String noConfig = RelaunchPromptDialog.buildMessage(
            "25",
            "-Djdk.util.jar.version=21",
            "jdkjarversion21enforcer.cfg",
            "prelaunch_relaunch_suppressed",
            RelaunchPromptDialog.PatcherStatus.NO_CONFIG_OR_ERROR);
        assertTrue(noConfig.contains("could not be written"), noConfig);
    }
}
