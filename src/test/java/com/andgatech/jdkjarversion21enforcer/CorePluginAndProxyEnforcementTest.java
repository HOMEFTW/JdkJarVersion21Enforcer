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
