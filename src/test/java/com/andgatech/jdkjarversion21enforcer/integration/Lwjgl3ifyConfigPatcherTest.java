package com.andgatech.jdkjarversion21enforcer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Lwjgl3ifyConfigPatcherTest {

    @Test
    void returnsNoConfigWhenFileMissing(@TempDir Path tmp) {
        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.NO_CONFIG, result);
    }

    @Test
    void patchOrCreateBuildsSkeletonWhenFileMissing(@TempDir Path tmp) throws IOException {
        // v0.5.3: patchOrCreate is the new entry point used by RelaunchService. When the file is
        // missing it must create the parent dir if needed AND write a minimal skeleton JSON
        // containing the desired option, so that lwjgl3ify will pick it up on the next launch.
        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchOrCreate(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.CREATED, result);

        Path created = tmp.resolve("config")
            .resolve("lwjgl3ify-relauncher.json");
        assertTrue(Files.exists(created));
        String content = new String(Files.readAllBytes(created), StandardCharsets.UTF_8);
        assertTrue(content.contains("customOptions"), content);
        assertTrue(content.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION), content);
    }

    @Test
    void patchOrCreateOnExistingFileBehavesAsPatchIfNeeded(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, "{\n  \"customOptions\": [\n    \"-Xmx4G\"\n  ]\n}");
        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchOrCreate(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.APPLIED, result);
        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(after.contains("-Xmx4G"));
        assertTrue(after.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
    }

    @Test
    void appendsDesiredOptionWhenAbsent(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, "{\n  \"customOptions\": [\n    \"-Xmx4G\"\n  ]\n}");

        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.APPLIED, result);

        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(after.contains("-Xmx4G"));
        assertTrue(after.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
    }

    @Test
    void doesNotDuplicateExistingOption(@TempDir Path tmp) throws IOException {
        String existing = "{\n  \"customOptions\": [\n    \"" + Lwjgl3ifyConfigPatcher.DESIRED_OPTION + "\"\n  ]\n}";
        Path file = writeConfig(tmp, existing);

        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.ALREADY_PRESENT, result);

        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // File should be byte-for-byte unchanged.
        assertEquals(existing, after);
    }

    @Test
    void addsCustomOptionsArrayWhenMissing(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, "{\n  \"minMemoryMB\": 512\n}");

        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.APPLIED, result);

        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(after.contains("\"customOptions\""));
        assertTrue(after.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
        assertTrue(after.contains("512"));
    }

    @Test
    void returnsErrorOnMalformedJson(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, "this is not json");
        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.ERROR, result);

        // Original content must be preserved on error.
        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals("this is not json", after);
    }

    @Test
    void appliedWritePreservesOtherFields(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, "{\n  \"minMemoryMB\": 1024,\n  \"customOptions\": [\n    \"-Xmx8G\"\n  ]\n}");

        Lwjgl3ifyConfigPatcher.Result result = Lwjgl3ifyConfigPatcher.patchIfNeeded(tmp);
        assertEquals(Lwjgl3ifyConfigPatcher.Result.APPLIED, result);

        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(after.contains("\"minMemoryMB\""));
        assertTrue(after.contains("1024"));
        assertTrue(after.contains("-Xmx8G"));
        assertTrue(after.contains(Lwjgl3ifyConfigPatcher.DESIRED_OPTION));
        assertNotEquals(after, "{\n  \"minMemoryMB\": 1024,\n  \"customOptions\": [\n    \"-Xmx8G\"\n  ]\n}");
    }

    private static Path writeConfig(Path tmp, String contents) throws IOException {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve("lwjgl3ify-relauncher.json");
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
