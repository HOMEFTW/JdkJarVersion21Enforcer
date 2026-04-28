package com.andgatech.jdkjarversion21enforcer.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManualLauncherInstructionsPopupTest {

    @Test
    void buildMessageMentionsDesiredOptionAndCommonLaunchers() {
        String msg = ManualLauncherInstructionsPopup
            .buildMessage("-Djdk.util.jar.version=21", "jdkjarversion21enforcer-0.5.3.jar");
        assertTrue(msg.contains("-Djdk.util.jar.version=21"), msg);
        assertTrue(msg.contains("PrismLauncher"), msg);
        assertTrue(msg.contains("HMCL"), msg);
        // The agent example must be included when a jar name is provided.
        assertTrue(msg.contains("-javaagent:mods/jdkjarversion21enforcer-0.5.3.jar"), msg);
    }

    @Test
    void buildMessageOmitsAgentExampleWhenJarNameNull() {
        String msg = ManualLauncherInstructionsPopup.buildMessage("-Djdk.util.jar.version=21", null);
        assertTrue(msg.contains("-Djdk.util.jar.version=21"), msg);
        assertFalse(msg.contains("-javaagent:"), msg);
    }

    @Test
    void showDoesNotThrowInHeadlessEnvironment() {
        // Test JVM is headless via build.gradle (-Djava.awt.headless=true), so show() should
        // short-circuit at RestartPopup.canShow() and never touch AWT. The contract is "no throw,
        // no hang, no popup".
        ManualLauncherInstructionsPopup.show("JDK Jar Version 21 Enforcer", "-Djdk.util.jar.version=21", null);
    }
}
