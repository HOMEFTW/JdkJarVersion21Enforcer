package com.andgatech.jdkjarversion21enforcer;

public final class JavaRuntimeVersion {

    private static final int ACTIVATION_MAJOR_VERSION = 21;

    private JavaRuntimeVersion() {}

    public static boolean isAbove21(String specificationVersion) {
        return majorVersion(specificationVersion) > ACTIVATION_MAJOR_VERSION;
    }

    public static int majorVersion(String specificationVersion) {
        if ((specificationVersion == null) || specificationVersion.trim()
            .isEmpty()) {
            return 0;
        }

        String trimmed = specificationVersion.trim();
        if (trimmed.startsWith("1.")) {
            trimmed = trimmed.substring(2);
        }

        int separatorIndex = trimmed.indexOf('.');
        if (separatorIndex >= 0) {
            trimmed = trimmed.substring(0, separatorIndex);
        }

        return Integer.parseInt(trimmed);
    }
}
