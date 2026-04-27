package com.andgatech.jdkjarversion21enforcer;

public final class JarVersionPropertyEnforcer {

    public static final String PROPERTY_NAME = "jdk.util.jar.version";
    public static final String REQUIRED_VERSION = "21";

    private JarVersionPropertyEnforcer() {}

    public static void enforce() {
        enforceIfRuntimeAbove21(currentJavaSpecificationVersion());
    }

    public static boolean shouldActivate() {
        return JavaRuntimeVersion.isAbove21(currentJavaSpecificationVersion());
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
