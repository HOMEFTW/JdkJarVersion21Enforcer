package com.andgatech.jdkjarversion21enforcer;

import java.util.OptionalInt;

public final class JarVersionPropertyEnforcer {

    public static final String PROPERTY_NAME = "jdk.util.jar.version";
    public static final String REQUIRED_VERSION = "21";
    public static final int REQUIRED_FEATURE_VERSION = 21;

    private JarVersionPropertyEnforcer() {}

    public static void enforce() {
        enforceIfRuntimeAbove21(currentJavaSpecificationVersion());
    }

    public static boolean shouldActivate() {
        return JavaRuntimeVersion.isAbove21(currentJavaSpecificationVersion());
    }

    /**
     * Reflectively reads {@code java.util.jar.JarFile.runtimeVersion().feature()} to verify whether
     * the {@code jdk.util.jar.version} system property was actually picked up by the JDK.
     *
     * <p>
     * <b>Side effect:</b> calling this method triggers {@code JarFile} class initialization,
     * which locks in {@code RUNTIME_VERSION} forever. Therefore this method <b>must not</b> be
     * invoked from a Java Agent {@code premain} / {@code agentmain} or any other very early
     * bootstrap path; it is intended to be called from regular Forge {@code preInit} (by which
     * point Forge has already loaded jars and {@code JarFile} is initialized anyway).
     *
     * @return the feature version reported by {@code JarFile.runtimeVersion()} when available
     *         (Java 9+); {@link OptionalInt#empty()} on Java 8 or if reflection fails.
     */
    public static OptionalInt detectEffectiveJarRuntimeFeatureVersion() {
        try {
            Class<?> jarFileClass = Class.forName("java.util.jar.JarFile");
            Object version = jarFileClass.getMethod("runtimeVersion")
                .invoke(null);
            if (version == null) return OptionalInt.empty();
            Object feature = version.getClass()
                .getMethod("feature")
                .invoke(version);
            if (feature instanceof Integer) {
                return OptionalInt.of((Integer) feature);
            }
            return OptionalInt.empty();
        } catch (ReflectiveOperationException | LinkageError e) {
            return OptionalInt.empty();
        }
    }

    static void enforceIfRuntimeAbove21(String javaSpecificationVersion) {
        if (!JavaRuntimeVersion.isAbove21(javaSpecificationVersion)) {
            return;
        }
        System.setProperty(PROPERTY_NAME, REQUIRED_VERSION);
    }

    private static String currentJavaSpecificationVersion() {
        return System.getProperty("java.specification.version");
    }
}
