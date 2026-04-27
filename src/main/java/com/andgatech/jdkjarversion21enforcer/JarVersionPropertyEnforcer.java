package com.andgatech.jdkjarversion21enforcer;

public final class JarVersionPropertyEnforcer {

    public static final String PROPERTY_NAME = "jdk.util.jar.version";
    public static final String REQUIRED_VERSION = "21";

    private JarVersionPropertyEnforcer() {}

    public static void enforce() {
        System.setProperty(PROPERTY_NAME, REQUIRED_VERSION);
    }
}
