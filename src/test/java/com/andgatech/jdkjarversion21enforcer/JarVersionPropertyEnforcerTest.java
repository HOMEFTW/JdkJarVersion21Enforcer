package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

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

    @Test
    void detectorReturnsAReasonableFeatureVersionOnJava9Plus() {
        // Tests run on the build JDK (Zulu 21 per gradle.properties), so JarFile.runtimeVersion()
        // must be available and return a feature version >= 9.
        OptionalInt detected = JarVersionPropertyEnforcer.detectEffectiveJarRuntimeFeatureVersion();
        int specMajor = JavaRuntimeVersion.majorVersion(System.getProperty("java.specification.version"));
        if (specMajor >= 9) {
            assertTrue(detected.isPresent(), "Expected JarFile.runtimeVersion() to be reachable on Java " + specMajor);
            assertTrue(detected.getAsInt() >= 9, "Feature version was " + detected.getAsInt());
        }
    }
}
