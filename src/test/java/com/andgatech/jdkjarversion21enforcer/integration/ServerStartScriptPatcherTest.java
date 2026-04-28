package com.andgatech.jdkjarversion21enforcer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerStartScriptPatcherTest {

    @Test
    void noScriptsFoundOnEmptyDir(@TempDir Path tmp) {
        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.NO_SCRIPTS_FOUND, out.result);
        assertTrue(out.patchedFiles.isEmpty());
    }

    @Test
    void writesParallelBatScriptWithDOption(@TempDir Path tmp) throws IOException {
        Path orig = tmp.resolve("start.bat");
        Files.write(
            orig,
            ("@echo off\r\n" + "java -Xmx4G -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui\r\n")
                .getBytes(StandardCharsets.UTF_8));

        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.PATCHED, out.result);
        assertEquals(1, out.patchedFiles.size());

        Path generated = tmp.resolve("start-with-jdk-jar-21.bat");
        assertTrue(Files.isRegularFile(generated));
        String generatedContent = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(generatedContent.contains(ServerStartScriptPatcher.D_OPTION));
        assertTrue(generatedContent.contains("forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"));
        // Original must be untouched.
        String origContent = new String(Files.readAllBytes(orig), StandardCharsets.UTF_8);
        assertFalse(origContent.contains(ServerStartScriptPatcher.D_OPTION));
        // CRLF should be preserved on the patched line.
        assertTrue(generatedContent.contains("\r\n"));
    }

    @Test
    void writesParallelShScriptWithDOption(@TempDir Path tmp) throws IOException {
        Path orig = tmp.resolve("run.sh");
        Files.write(
            orig,
            ("#!/usr/bin/env bash\nset -e\njava -Xmx4G -jar forge-server.jar nogui\n")
                .getBytes(StandardCharsets.UTF_8));

        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.PATCHED, out.result);

        Path generated = tmp.resolve("run-with-jdk-jar-21.sh");
        assertTrue(Files.isRegularFile(generated));
        String generatedContent = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(generatedContent.contains(ServerStartScriptPatcher.D_OPTION));
        assertTrue(generatedContent.contains("forge-server.jar"));
        // The shebang and `set -e` must remain unchanged.
        assertTrue(generatedContent.startsWith("#!/usr/bin/env bash"));
        assertTrue(generatedContent.contains("set -e"));
    }

    @Test
    void skipsScriptsAlreadyContainingDOption(@TempDir Path tmp) throws IOException {
        Path orig = tmp.resolve("start.sh");
        Files.write(
            orig,
            ("#!/bin/sh\njava " + ServerStartScriptPatcher.D_OPTION + " -jar forge-server.jar nogui\n")
                .getBytes(StandardCharsets.UTF_8));

        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.ALREADY_OK, out.result);
        assertFalse(Files.exists(tmp.resolve("start-with-jdk-jar-21.sh")));
    }

    @Test
    void skipsScriptsReferencingOurJar(@TempDir Path tmp) throws IOException {
        Path orig = tmp.resolve("start.sh");
        Files.write(
            orig,
            ("#!/bin/sh\njava -javaagent:mods/jdkjarversion21enforcer-0.3.0.jar -jar forge-server.jar nogui\n")
                .getBytes(StandardCharsets.UTF_8));

        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.ALREADY_OK, out.result);
        assertFalse(Files.exists(tmp.resolve("start-with-jdk-jar-21.sh")));
    }

    @Test
    void doesNotOverwriteExistingParallelFile(@TempDir Path tmp) throws IOException {
        Path orig = tmp.resolve("start.bat");
        Files.write(orig, ("@echo off\r\njava -jar server.jar\r\n").getBytes(StandardCharsets.UTF_8));
        Path existing = tmp.resolve("start-with-jdk-jar-21.bat");
        Files.write(existing, "STALE".getBytes(StandardCharsets.UTF_8));

        ServerStartScriptPatcher.Outcome out = ServerStartScriptPatcher.run(tmp);
        assertEquals(ServerStartScriptPatcher.Result.ALREADY_OK, out.result);

        String existingAfter = new String(Files.readAllBytes(existing), StandardCharsets.UTF_8);
        assertEquals("STALE", existingAfter);
    }

    @Test
    void skipsCommentLinesAndBlankLines() {
        String input = "# java is the command\n" + "// java is also the command\n"
            + ":: java is the command (cmd)\n"
            + "REM java is the command\n"
            + "echo nothing here\n"
            + "\n"
            + "java -jar forge.jar\n";
        String out = ServerStartScriptPatcher.injectDOption(input);
        // Only the last line should be patched.
        String[] lines = out.split("\n", -1);
        assertTrue(lines[0].startsWith("#"));
        assertTrue(lines[1].startsWith("//"));
        assertTrue(lines[2].startsWith("::"));
        assertTrue(
            lines[3].toLowerCase()
                .startsWith("rem"));
        assertEquals("echo nothing here", lines[4]);
        assertEquals("", lines[5]);
        assertTrue(lines[6].contains("java " + ServerStartScriptPatcher.D_OPTION + " -jar forge.jar"));
    }

    @Test
    void handlesQuotedJavaExecutable() {
        String input = "\"C:\\Program Files\\Zulu\\zulu-21\\bin\\java.exe\" -jar forge-server.jar nogui";
        String out = ServerStartScriptPatcher.injectDOption(input);
        assertTrue(
            out.contains(
                "\"C:\\Program Files\\Zulu\\zulu-21\\bin\\java.exe\" " + ServerStartScriptPatcher.D_OPTION + " -jar"),
            out);
    }

    @Test
    void handlesJavaExeUnquoted() {
        String input = "javaw -jar server.jar";
        String out = ServerStartScriptPatcher.injectDOption(input);
        assertEquals("javaw " + ServerStartScriptPatcher.D_OPTION + " -jar server.jar", out);
    }

    @Test
    void doesNothingWhenNoJavaCommandLineFound() {
        String input = "@echo off\nset FOO=bar\necho hello\n";
        assertEquals(input, ServerStartScriptPatcher.injectDOption(input));
    }

    @Test
    void parallelPathDerivation() {
        assertEquals(
            "start-with-jdk-jar-21.bat",
            ServerStartScriptPatcher.parallelPathFor(java.nio.file.Paths.get("dir", "start.bat"))
                .getFileName()
                .toString());
        assertEquals(
            "ServerStart-with-jdk-jar-21.sh",
            ServerStartScriptPatcher.parallelPathFor(java.nio.file.Paths.get("dir", "ServerStart.sh"))
                .getFileName()
                .toString());
    }
}
