package com.andgatech.jdkjarversion21enforcer.agent;

import java.lang.instrument.Instrumentation;

import com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer;

/**
 * Java Agent entry point for {@code -javaagent:<this-jar>}.
 *
 * <p>
 * Unlike the Forge {@code IFMLLoadingPlugin} entry, {@code premain} runs before any user class
 * (including {@link java.util.jar.JarFile}) is loaded, so {@link System#setProperty(String, String)}
 * issued here is guaranteed to be observed when {@code JarFile} computes its
 * {@code RUNTIME_VERSION} static field.
 *
 * <p>
 * Both single-arg and two-arg overloads of {@code premain} / {@code agentmain} are provided so
 * the JVM agent loader can pick whichever signature it prefers (the Java Instrumentation spec
 * allows either).
 *
 * <p>
 * This class intentionally avoids touching {@link java.util.jar.JarFile} in any way: doing so
 * would trigger its class initializer and defeat the whole purpose of the agent.
 */
public final class JarVersionAgent {

    private JarVersionAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        JarVersionPropertyEnforcer.enforce();
    }

    public static void premain(String agentArgs) {
        JarVersionPropertyEnforcer.enforce();
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        JarVersionPropertyEnforcer.enforce();
    }

    public static void agentmain(String agentArgs) {
        JarVersionPropertyEnforcer.enforce();
    }
}
