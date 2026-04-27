package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.andgatech.jdkjarversion21enforcer.core.JarVersion21CorePlugin;

class CorePluginAndProxyEnforcementTest {

    private String originalJavaSpecificationVersion;

    @BeforeEach
    void rememberJavaSpecificationVersion() {
        originalJavaSpecificationVersion = System.getProperty("java.specification.version");
    }

    @AfterEach
    void restoreProperty() {
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
        if (originalJavaSpecificationVersion == null) {
            System.clearProperty("java.specification.version");
        } else {
            System.setProperty("java.specification.version", originalJavaSpecificationVersion);
        }
    }

    @Test
    void corePluginConstructorDoesNotEnforceJarVersionForJava21() {
        System.setProperty("java.specification.version", "21");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        new JarVersion21CorePlugin();

        assertNull(System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void corePluginConstructorEnforcesJarVersionForJava22() {
        System.setProperty(JarVersionPropertyEnforcer.PROPERTY_NAME, "8");
        System.setProperty("java.specification.version", "22");

        new JarVersion21CorePlugin();

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void activationLogMessageIsEmptyForJava21OrLower() {
        assertNull(CommonProxy.activationLogMessage("21"));
    }

    @Test
    void activationLogMessageIncludesJavaVersionAndForcedPropertyForJava22AndHigher() {
        assertEquals("Java 22 detected; forced jdk.util.jar.version=21.", CommonProxy.activationLogMessage("22"));
        assertEquals("Java 23 detected; forced jdk.util.jar.version=21.", CommonProxy.activationLogMessage("23"));
        assertEquals("Java 25 detected; forced jdk.util.jar.version=21.", CommonProxy.activationLogMessage("25"));
        assertEquals("Java 26 detected; forced jdk.util.jar.version=21.", CommonProxy.activationLogMessage("26"));
    }

    @Test
    void commonProxyPreInitDoesNotEnforceJarVersionForJava21() {
        System.setProperty("java.specification.version", "21");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        new CommonProxy().preInit(null);

        assertNull(System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void commonProxyPreInitEnforcesJarVersionAsFallbackForJava22() {
        System.setProperty(JarVersionPropertyEnforcer.PROPERTY_NAME, "17");
        System.setProperty("java.specification.version", "22");

        new CommonProxy().preInit(null);

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }
}
