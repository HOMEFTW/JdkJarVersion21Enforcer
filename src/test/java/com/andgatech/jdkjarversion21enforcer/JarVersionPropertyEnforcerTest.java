package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JarVersionPropertyEnforcerTest {

    private static final String PROPERTY_NAME = "jdk.util.jar.version";

    @AfterEach
    void restoreProperty() {
        System.clearProperty(PROPERTY_NAME);
    }

    @Test
    void enforceSetsJarVersionTo21WhenMissing() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforce();

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }

    @Test
    void enforceOverridesExistingJarVersion() {
        System.setProperty(PROPERTY_NAME, "8");

        JarVersionPropertyEnforcer.enforce();

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }
}
