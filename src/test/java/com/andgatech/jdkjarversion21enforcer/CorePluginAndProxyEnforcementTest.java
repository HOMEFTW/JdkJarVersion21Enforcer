package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.andgatech.jdkjarversion21enforcer.core.JarVersion21CorePlugin;

class CorePluginAndProxyEnforcementTest {

    @AfterEach
    void restoreProperty() {
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
    }

    @Test
    void corePluginConstructorEnforcesJarVersion() {
        System.setProperty(JarVersionPropertyEnforcer.PROPERTY_NAME, "8");

        new JarVersion21CorePlugin();

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void commonProxyPreInitEnforcesJarVersionAsFallback() {
        System.setProperty(JarVersionPropertyEnforcer.PROPERTY_NAME, "17");

        new CommonProxy().preInit(null);

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }
}
