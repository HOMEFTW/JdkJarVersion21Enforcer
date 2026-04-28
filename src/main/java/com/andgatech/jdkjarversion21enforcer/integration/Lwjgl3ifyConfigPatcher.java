package com.andgatech.jdkjarversion21enforcer.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Detects an installed lwjgl3ify {@code config/lwjgl3ify-relauncher.json} and appends
 * {@code -Djdk.util.jar.version=21} to its {@code customOptions} array if not already present.
 *
 * <p>
 * lwjgl3ify itself is responsible for picking up {@code customOptions} on the <i>next</i>
 * launch and forwarding them as JVM args to its child Minecraft process, so this is the simplest
 * possible way to make {@code -Djdk.util.jar.version=21} actually take effect for GTNH players who
 * already use lwjgl3ify (which is the vast majority).
 *
 * <p>
 * The original JSON is rewritten only if a change was made. Pretty-printing is preserved to
 * stay close to lwjgl3ify's own serializer output.
 */
public final class Lwjgl3ifyConfigPatcher {

    public static final String DESIRED_OPTION = "-Djdk.util.jar.version=21";
    public static final String CONFIG_RELATIVE_PATH = "config/lwjgl3ify-relauncher.json";

    public enum Result {
        /** File existed and we appended the option. */
        APPLIED,
        /** File existed and the option was already there. */
        ALREADY_PRESENT,
        /**
         * File did not exist and we did not try to create it. Reserved for {@link #patchIfNeeded}
         * (legacy callers that prefer the dialog fallback over creating files).
         */
        NO_CONFIG,
        /**
         * File did not exist and we just created a minimal skeleton containing the desired
         * option. lwjgl3ify will pick it up on the next non-RFB launch (RFB-booted runs ignore
         * this file altogether, but writing it has no side-effects there).
         */
        CREATED,
        /** Read / parse / write failed. */
        ERROR
    }

    private Lwjgl3ifyConfigPatcher() {}

    public static Result patchIfNeeded(Path gameDir) {
        Path configFile = gameDir.resolve("config")
            .resolve("lwjgl3ify-relauncher.json");
        return patchFile(configFile);
    }

    /**
     * Same as {@link #patchIfNeeded(Path)} but, when the file does not exist, creates a minimal
     * skeleton {@code { "customOptions": ["-Djdk.util.jar.version=21"] }} so that lwjgl3ify will
     * have something to read on the next launch. Returns {@link Result#CREATED} in that case.
     *
     * <p>
     * Even on RFB-booted environments where lwjgl3ify's relauncher path is bypassed, creating the
     * file has no harmful side-effects: nothing else reads it, and if the user later switches to
     * a non-RFB launcher our entry will be honoured.
     */
    public static Result patchOrCreate(Path gameDir) {
        Path configFile = gameDir.resolve("config")
            .resolve("lwjgl3ify-relauncher.json");
        return patchOrCreateFile(configFile);
    }

    static Result patchOrCreateFile(Path configFile) {
        if (Files.isRegularFile(configFile)) {
            return patchFile(configFile);
        }
        try {
            Files.createDirectories(configFile.getParent());
            JsonObject root = new JsonObject();
            JsonArray opts = new JsonArray();
            opts.add(new com.google.gson.JsonPrimitive(DESIRED_OPTION));
            root.add("customOptions", opts);
            Gson gson = new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            Files.write(
                configFile,
                gson.toJson(root)
                    .getBytes(StandardCharsets.UTF_8));
            return Result.CREATED;
        } catch (IOException e) {
            return Result.ERROR;
        }
    }

    static Result patchFile(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return Result.NO_CONFIG;
        }
        try {
            String original = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            JsonElement rootElement = parseJson(original);
            if (rootElement == null || !rootElement.isJsonObject()) {
                return Result.ERROR;
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonArray opts = (root.has("customOptions") && root.get("customOptions")
                .isJsonArray()) ? root.getAsJsonArray("customOptions") : new JsonArray();
            for (JsonElement e : opts) {
                if (e != null && e.isJsonPrimitive()
                    && DESIRED_OPTION.equals(
                        e.getAsString()
                            .trim())) {
                    return Result.ALREADY_PRESENT;
                }
            }
            opts.add(new com.google.gson.JsonPrimitive(DESIRED_OPTION));
            root.add("customOptions", opts);
            // disableHtmlEscaping so `=` in `-Djdk.util.jar.version=21` is not turned into `\u003d`.
            // lwjgl3ify's own Gson parses both forms identically, but the literal form is much
            // more readable for users editing the file by hand.
            Gson gson = new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            String out = gson.toJson(root);
            Files.write(configFile, out.getBytes(StandardCharsets.UTF_8));
            return Result.APPLIED;
        } catch (IOException | JsonParseException e) {
            return Result.ERROR;
        }
    }

    @SuppressWarnings("deprecation")
    private static JsonElement parseJson(String text) {
        // Use the constructor + parse(String) form for compatibility with the Gson 2.2.4 that ships
        // with vanilla Minecraft 1.7.10. JsonParser.parseString(...) is Gson 2.8.6+.
        return new JsonParser().parse(text);
    }
}
