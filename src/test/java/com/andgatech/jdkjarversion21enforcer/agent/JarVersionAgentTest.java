package com.andgatech.jdkjarversion21enforcer.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer;

class JarVersionAgentTest {

    private String originalSpec;

    @BeforeEach
    void rememberSpec() {
        originalSpec = System.getProperty("java.specification.version");
    }

    @AfterEach
    void restoreSpec() {
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);
        if (originalSpec == null) {
            System.clearProperty("java.specification.version");
        } else {
            System.setProperty("java.specification.version", originalSpec);
        }
    }

    @Test
    void premainSingleArgEnforcesOnJava22() {
        System.setProperty("java.specification.version", "22");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        JarVersionAgent.premain("");

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void premainTwoArgEnforcesOnJava25() {
        System.setProperty("java.specification.version", "25");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        JarVersionAgent.premain("", null);

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void agentmainSingleArgEnforcesOnJava23() {
        System.setProperty("java.specification.version", "23");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        JarVersionAgent.agentmain("");

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void agentmainTwoArgEnforcesOnJava26() {
        System.setProperty("java.specification.version", "26");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        JarVersionAgent.agentmain("", null);

        assertEquals(
            JarVersionPropertyEnforcer.REQUIRED_VERSION,
            System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }

    @Test
    void agentEntriesDoNothingOnJava21() {
        System.setProperty("java.specification.version", "21");
        System.clearProperty(JarVersionPropertyEnforcer.PROPERTY_NAME);

        JarVersionAgent.premain("");
        JarVersionAgent.premain("", null);
        JarVersionAgent.agentmain("");
        JarVersionAgent.agentmain("", null);

        assertNull(System.getProperty(JarVersionPropertyEnforcer.PROPERTY_NAME));
    }
}
