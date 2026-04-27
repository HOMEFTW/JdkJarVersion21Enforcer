package com.andgatech.jdkjarversion21enforcer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaRuntimeVersionTest {

    @Test
    void parsesLegacyAndModernJavaSpecificationVersions() {
        assertEquals(8, JavaRuntimeVersion.majorVersion("1.8"));
        assertEquals(17, JavaRuntimeVersion.majorVersion("17"));
        assertEquals(21, JavaRuntimeVersion.majorVersion("21"));
        assertEquals(22, JavaRuntimeVersion.majorVersion("22"));
        assertEquals(23, JavaRuntimeVersion.majorVersion("23"));
        assertEquals(25, JavaRuntimeVersion.majorVersion("25"));
        assertEquals(26, JavaRuntimeVersion.majorVersion("26"));
    }

    @Test
    void treatsJava22AndHigherAsNeedingTheJarVersionOverride() {
        assertFalse(JavaRuntimeVersion.isAbove21("1.8"));
        assertFalse(JavaRuntimeVersion.isAbove21("17"));
        assertFalse(JavaRuntimeVersion.isAbove21("21"));
        assertTrue(JavaRuntimeVersion.isAbove21("22"));
        assertTrue(JavaRuntimeVersion.isAbove21("23"));
        assertTrue(JavaRuntimeVersion.isAbove21("25"));
        assertTrue(JavaRuntimeVersion.isAbove21("26"));
    }
}
