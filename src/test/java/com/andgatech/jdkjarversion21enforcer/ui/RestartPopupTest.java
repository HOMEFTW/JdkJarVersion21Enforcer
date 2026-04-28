package com.andgatech.jdkjarversion21enforcer.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestartPopupTest {

    private String previousHeadless;

    @BeforeEach
    void forceHeadless() {
        previousHeadless = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void restoreHeadless() {
        if (previousHeadless == null) {
            System.clearProperty("java.awt.headless");
        } else {
            System.setProperty("java.awt.headless", previousHeadless);
        }
    }

    @Test
    void canShowReturnsFalseUnderHeadless() {
        assertFalse(RestartPopup.canShow());
    }

    @Test
    void showRestartReminderIsNoOpUnderHeadlessAndDoesNotThrow() {
        assertDoesNotThrow(() -> RestartPopup.showRestartReminder("Title", "Body"));
    }

    @Test
    void showRestartReminderToleratesNullsAndEmpty() {
        assertDoesNotThrow(() -> RestartPopup.showRestartReminder("", ""));
        assertDoesNotThrow(() -> RestartPopup.showRestartReminder(null, null));
    }
}
