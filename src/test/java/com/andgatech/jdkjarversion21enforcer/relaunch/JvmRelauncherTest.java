package com.andgatech.jdkjarversion21enforcer.relaunch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JvmRelauncherTest {

    @BeforeEach
    void resetCapturedLaunchArgs() {
        // Tests in this class invoke buildChildCommand, which now consults LaunchArgsCapture as
        // its first source of main args. Reset the static state between tests so that one test
        // capturing args cannot leak into another and break the assumed argv layout.
        LaunchArgsCapture.resetForTesting();
    }

    @Test
    void extractDPropertyKeyHandlesAllShapes() {
        assertEquals("foo", JvmRelauncher.extractDPropertyKey("-Dfoo=bar"));
        assertEquals("foo", JvmRelauncher.extractDPropertyKey("-Dfoo"));
        assertEquals("a.b.c", JvmRelauncher.extractDPropertyKey("-Da.b.c=value with spaces"));
        assertNull(JvmRelauncher.extractDPropertyKey("-Xmx4G"));
        assertNull(JvmRelauncher.extractDPropertyKey("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assertNull(JvmRelauncher.extractDPropertyKey(null));
    }

    @Test
    void extractAgentJarPathHandlesQuotedAndSimplePaths() {
        assertEquals("foo.jar", JvmRelauncher.extractAgentJarPath("-javaagent:foo.jar"));
        assertEquals("/tmp/foo.jar", JvmRelauncher.extractAgentJarPath("-javaagent:/tmp/foo.jar"));
        assertEquals("foo.jar", JvmRelauncher.extractAgentJarPath("-javaagent:foo.jar=opt1=opt2"));
        assertNull(JvmRelauncher.extractAgentJarPath("-Xmx4G"));
        assertNull(JvmRelauncher.extractAgentJarPath(null));
    }

    @Test
    void buildChildCommandPrependsExtrasAndKeepsExistingArgs() {
        List<String> existing = Arrays.asList("-Xmx4G", "-Xms1G", "-XX:+UseG1GC");
        List<String> extras = Arrays.asList("-Djdk.util.jar.version=21", "-Djdkjarversion21enforcer.relaunched=true");

        List<String> cmd = JvmRelauncher.buildChildCommand(
            "/jdk/bin/java",
            extras,
            existing,
            "lib/a.jar:lib/b.jar",
            "net.minecraft.launchwrapper.Launch --version 1.7.10");

        assertEquals("/jdk/bin/java", cmd.get(0));
        // Extras come first (1, 2)
        assertEquals("-Djdk.util.jar.version=21", cmd.get(1));
        assertEquals("-Djdkjarversion21enforcer.relaunched=true", cmd.get(2));
        // Then existing args
        assertEquals("-Xmx4G", cmd.get(3));
        assertEquals("-Xms1G", cmd.get(4));
        assertEquals("-XX:+UseG1GC", cmd.get(5));
        // Then -cp + classpath
        assertEquals("-cp", cmd.get(6));
        assertEquals("lib/a.jar:lib/b.jar", cmd.get(7));
        // Then main class + main args
        assertEquals("net.minecraft.launchwrapper.Launch", cmd.get(8));
        assertEquals("--version", cmd.get(9));
        assertEquals("1.7.10", cmd.get(10));
    }

    @Test
    void buildChildCommandDropsConflictingDProperty() {
        List<String> existing = Arrays.asList("-Xmx4G", "-Djdk.util.jar.version=8", "-Dother=value");
        List<String> extras = Collections.singletonList("-Djdk.util.jar.version=21");

        List<String> cmd = JvmRelauncher.buildChildCommand("java", extras, existing, "", "Main");

        // Should contain our new value, not the old one.
        assertTrue(cmd.contains("-Djdk.util.jar.version=21"));
        assertFalse(cmd.contains("-Djdk.util.jar.version=8"));
        // Other -D args are kept.
        assertTrue(cmd.contains("-Dother=value"));
        // Non-conflicting JVM args are kept.
        assertTrue(cmd.contains("-Xmx4G"));
    }

    @Test
    void buildChildCommandDropsConflictingJavaagent() {
        List<String> existing = Arrays.asList("-javaagent:/path/to/our.jar", "-javaagent:/path/to/other.jar");
        List<String> extras = Collections.singletonList("-javaagent:/path/to/our.jar=newopt");

        List<String> cmd = JvmRelauncher.buildChildCommand("java", extras, existing, "", "Main");

        // Conflicting agent (same jar path) is dropped from existing args.
        assertEquals(
            1,
            cmd.stream()
                .filter(s -> s.startsWith("-javaagent:/path/to/our.jar"))
                .count());
        // Other agent stays.
        assertTrue(cmd.contains("-javaagent:/path/to/other.jar"));
    }

    @Test
    void buildChildCommandHandlesEmptyClasspathAndMain() {
        List<String> cmd = JvmRelauncher
            .buildChildCommand("java", Collections.singletonList("-Dfoo=bar"), Collections.emptyList(), "", "");
        assertEquals(Arrays.asList("java", "-Dfoo=bar"), cmd);
    }

    @Test
    void escapeArgfileTokenAlwaysQuotesAndEscapesBackslashesAndQuotes() {
        assertEquals("\"foo\"", JvmRelauncher.escapeArgfileToken("foo"));
        assertEquals("\"foo bar\"", JvmRelauncher.escapeArgfileToken("foo bar"));
        assertEquals("\"a\\\\b\"", JvmRelauncher.escapeArgfileToken("a\\b"));
        assertEquals("\"a\\\"b\"", JvmRelauncher.escapeArgfileToken("a\"b"));
        assertEquals("\"C:\\\\path\\\\to\\\\jar.jar\"", JvmRelauncher.escapeArgfileToken("C:\\path\\to\\jar.jar"));
    }

    @Test
    void writeArgfileSkipsArgvZeroAndQuotesEverything() throws IOException {
        List<String> command = Arrays
            .asList("/jdk/bin/java", "-Dfoo=bar", "-cp", "lib/a.jar:lib/b.jar", "Main", "arg with spaces");
        Path argfile = JvmRelauncher.writeArgfile(command);
        try {
            assertTrue(Files.isRegularFile(argfile));
            String content = new String(Files.readAllBytes(argfile), StandardCharsets.UTF_8);
            String[] lines = content.split("\\r?\\n");
            // argv[0] (the java executable) is NOT in the argfile; it's argv[0] of ProcessBuilder.
            assertEquals(5, lines.length);
            assertEquals("\"-Dfoo=bar\"", lines[0]);
            assertEquals("\"-cp\"", lines[1]);
            assertEquals("\"lib/a.jar:lib/b.jar\"", lines[2]);
            assertEquals("\"Main\"", lines[3]);
            assertEquals("\"arg with spaces\"", lines[4]);
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    @Test
    void isRelaunchedChildReadsSystemProperty() {
        String saved = System.getProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
        try {
            System.clearProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
            assertFalse(JvmRelauncher.isRelaunchedChild());

            System.setProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, "true");
            assertTrue(JvmRelauncher.isRelaunchedChild());

            System.setProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, "false");
            assertFalse(JvmRelauncher.isRelaunchedChild());
        } finally {
            if (saved == null) {
                System.clearProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
            } else {
                System.setProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, saved);
            }
        }
    }

    @Test
    void relaunchGuardArgIsCorrectlyFormatted() {
        assertEquals("-Djdkjarversion21enforcer.relaunched=true", JvmRelauncher.relaunchGuardArg());
    }

    @Test
    void currentJavaExecutableUsesJavaHome() {
        String exe = JvmRelauncher.currentJavaExecutable();
        assertTrue(exe.endsWith("java") || exe.endsWith("java.exe"), exe);
    }

    @Test
    void mergeClasspathPrependsExtrasAndDeduplicates() {
        String sep = java.io.File.pathSeparator;
        // Plain prepend.
        assertEquals("a.jar" + sep + "b.jar", JvmRelauncher.mergeClasspath(Arrays.asList("a.jar"), "b.jar"));
        // Extras already in existing classpath are not duplicated.
        assertEquals(
            "a.jar" + sep + "b.jar",
            JvmRelauncher.mergeClasspath(Arrays.asList("a.jar"), "a.jar" + sep + "b.jar"));
        // Empty extras: passthrough.
        assertEquals("a.jar", JvmRelauncher.mergeClasspath(Collections.emptyList(), "a.jar"));
        // Empty existing: just extras joined.
        assertEquals("a.jar" + sep + "b.jar", JvmRelauncher.mergeClasspath(Arrays.asList("a.jar", "b.jar"), ""));
        // Both empty.
        assertEquals("", JvmRelauncher.mergeClasspath(Collections.emptyList(), ""));
        // Null inputs should not blow up.
        assertEquals("", JvmRelauncher.mergeClasspath(null, null));
        assertEquals("a.jar", JvmRelauncher.mergeClasspath(null, "a.jar"));
        assertEquals("a.jar", JvmRelauncher.mergeClasspath(Arrays.asList("a.jar"), null));
    }

    @Test
    void discoverExtraClasspathEntriesIncludesScannedJavaSystemClassLoaderArg() {
        // Looking up our own test class via its FQN proves the helper finds CodeSource for any
        // resolvable class, simulating what we do for RfbSystemClassLoader in real GTNH RFB
        // launches. We pass the FQN through both possible channels (live system property and
        // -D arg list) to verify both code paths resolve the same jar.
        String saved = System.getProperty("java.system.class.loader");
        try {
            System.setProperty("java.system.class.loader", JvmRelauncherTest.class.getName());
            List<String> live = JvmRelauncher.discoverExtraClasspathEntries(Collections.emptyList());
            assertFalse(live.isEmpty(), "Expected to discover the test class's code source");
            // Each entry should point at a real path on disk (the test classes directory or jar).
            for (String entry : live) {
                assertTrue(java.nio.file.Files.exists(java.nio.file.Paths.get(entry)), entry);
            }

            System.clearProperty("java.system.class.loader");
            List<String> viaArgs = JvmRelauncher.discoverExtraClasspathEntries(
                Collections.singletonList("-Djava.system.class.loader=" + JvmRelauncherTest.class.getName()));
            assertEquals(live, viaArgs);
        } finally {
            if (saved == null) System.clearProperty("java.system.class.loader");
            else System.setProperty("java.system.class.loader", saved);
        }
    }

    @Test
    void parseJarPathFromResourceUrlHandlesJarUrlAndLooseFileUrl() throws Exception {
        String resourceName = "com/example/Foo.class";
        // jar:file:/.../foo.jar!/com/example/Foo.class -> /.../foo.jar
        java.net.URL jarUrl = new java.net.URL("jar:file:/D:/games/mods/foo.jar!/" + resourceName);
        String parsed = JvmRelauncher.parseJarPathFromResourceUrl(jarUrl, resourceName);
        assertTrue(
            parsed != null && parsed.replace('\\', '/')
                .endsWith("/games/mods/foo.jar"),
            parsed);

        // file:/.../bin/com/example/Foo.class -> /.../bin
        java.net.URL fileUrl = new java.net.URL("file:/D:/games/bin/" + resourceName);
        String parsedDir = JvmRelauncher.parseJarPathFromResourceUrl(fileUrl, resourceName);
        assertTrue(
            parsedDir != null && parsedDir.replace('\\', '/')
                .endsWith("/games/bin"),
            parsedDir);

        // Unknown shape (e.g. http) -> null
        java.net.URL httpUrl = new java.net.URL("http://example.com/" + resourceName);
        assertNull(JvmRelauncher.parseJarPathFromResourceUrl(httpUrl, resourceName));

        // Null url -> null
        assertNull(JvmRelauncher.parseJarPathFromResourceUrl(null, resourceName));
    }

    @Test
    void locateMainArgsStartFindsJarMode() {
        // -jar <path> mode: main args start two tokens after -jar.
        String[] args = { "-Xmx8G", "-Djdk.foo=bar", "-jar", "F:\\path\\to\\app.jar", "--version", "GT New Horizons",
            "--gameDir", "..." };
        int idx = JvmRelauncher.locateMainArgsStart(args, "app.jar", null);
        assertEquals(4, idx);
    }

    @Test
    void locateMainArgsStartFindsExactMainClass() {
        String[] args = { "-Xmx8G", "-cp", "lib/a.jar:lib/b.jar", "org.foo.Main", "arg1", "arg with spaces" };
        int idx = JvmRelauncher.locateMainArgsStart(args, "org.foo.Main", null);
        assertEquals(4, idx);
    }

    @Test
    void locateMainArgsStartFindsPathSuffixForJarBasename() {
        // sun.java.command reports just the basename, but OS argv has full path.
        String[] args = { "-Xmx8G", "F:\\path\\with-spaces\\app.jar", "arg1", "arg2" };
        int idx = JvmRelauncher.locateMainArgsStart(args, "app.jar", null);
        // Strategy 3 (path-suffix) should match args[1].
        assertEquals(2, idx);
    }

    @Test
    void locateMainArgsStartReturnsNegativeWhenNotFound() {
        String[] args = { "-Xmx8G", "-Djava.foo=bar" };
        assertEquals(-1, JvmRelauncher.locateMainArgsStart(args, "org.foo.Main", null));
    }

    @Test
    void tokenizeCommandLineHandlesQuotedArgsWithSpaces() {
        // Typical Windows GetCommandLineW output:
        // java.exe -Xmx8G -cp "lib;ot her.jar" org.foo.Main --version "GT New Horizons 2.8.4"
        String[] tokens = JvmRelauncher.tokenizeCommandLine(
            "java.exe -Xmx8G -cp \"lib;ot her.jar\" org.foo.Main --version \"GT New Horizons 2.8.4\"");
        assertEquals(7, tokens.length);
        assertEquals("java.exe", tokens[0]);
        assertEquals("-Xmx8G", tokens[1]);
        assertEquals("-cp", tokens[2]);
        assertEquals("lib;ot her.jar", tokens[3]);
        assertEquals("org.foo.Main", tokens[4]);
        assertEquals("--version", tokens[5]);
        assertEquals("GT New Horizons 2.8.4", tokens[6]);
    }

    @Test
    void tokenizeCommandLineHandlesEscapedQuotesAndBackslashes() {
        String[] tokens = JvmRelauncher.tokenizeCommandLine("a \"b\\\\c\\\"d\" e");
        assertEquals(3, tokens.length);
        assertEquals("a", tokens[0]);
        assertEquals("b\\c\"d", tokens[1]);
        assertEquals("e", tokens[2]);
    }

    @Test
    void tokenizeCommandLineEmptyOrNull() {
        assertEquals(0, JvmRelauncher.tokenizeCommandLine(null).length);
        assertEquals(0, JvmRelauncher.tokenizeCommandLine("").length);
        assertEquals(0, JvmRelauncher.tokenizeCommandLine("   ").length);
    }

    @Test
    void discoverExtraClasspathEntriesIsSafeWhenClassMissing() {
        // Bogus FQN: the helper must not throw and must return an empty list.
        String saved = System.getProperty("java.system.class.loader");
        try {
            System.clearProperty("java.system.class.loader");
            List<String> result = JvmRelauncher.discoverExtraClasspathEntries(
                Collections.singletonList("-Djava.system.class.loader=this.class.does.not.Exist"));
            assertTrue(result.isEmpty());
        } finally {
            if (saved != null) System.setProperty("java.system.class.loader", saved);
        }
    }
}
