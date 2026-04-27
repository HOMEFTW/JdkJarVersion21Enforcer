package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JarVersionPropertyEnforcerTest {

    private static final String PROPERTY_NAME = "jdk.util.jar.version";

    @AfterEach
    void restoreProperty() {
        System.clearProperty(PROPERTY_NAME);
    }

    @Test
    void doesNotSetJarVersionForJava21OrLower() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("1.8");
        assertNull(System.getProperty(PROPERTY_NAME));

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("17");
        assertNull(System.getProperty(PROPERTY_NAME));

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("21");
        assertNull(System.getProperty(PROPERTY_NAME));
    }

    @Test
    void enforcesJarVersionForJava22() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("22");

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }

    @Test
    void enforcesJarVersionForJava23() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("23");

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }

    @Test
    void enforcesJarVersionForJava25() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("25");

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }

    @Test
    void enforcesJarVersionForJava26() {
        System.clearProperty(PROPERTY_NAME);

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("26");

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }

    @Test
    void overridesExistingJarVersionOnlyWhenRuntimeIsAbove21() {
        System.setProperty(PROPERTY_NAME, "8");

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("21");

        assertEquals("8", System.getProperty(PROPERTY_NAME));

        JarVersionPropertyEnforcer.enforceIfRuntimeAbove21("22");

        assertEquals("21", System.getProperty(PROPERTY_NAME));
    }
}
