package com.andgatech.jdkjarversion21enforcer.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates parallel "with-jdk-jar-21" copies of the server start scripts found in the working
 * directory, with {@code -Djdk.util.jar.version=21} injected right after the {@code java}
 * command. The user's original script is <b>never</b> modified.
 *
 * <p>
 * This patcher is meant to be invoked from {@code preInit} on dedicated servers. It is
 * deliberately conservative:
 * <ul>
 * <li>Only known script names are considered.</li>
 * <li>Scripts that already contain {@code -Djdk.util.jar.version=21} or reference our jar
 * (heuristic on {@code jdkjarversion21enforcer}) are skipped.</li>
 * <li>Existing parallel files are skipped (no overwrite).</li>
 * <li>Comment lines (#, ::, REM, //) are skipped.</li>
 * <li>Only lines that look like a real Java command line ({@code -jar} or {@code .jar}) are
 * patched, and only the first such line per script.</li>
 * </ul>
 */
public final class ServerStartScriptPatcher {

    public static final String D_OPTION = "-Djdk.util.jar.version=21";

    public static final List<String> CANDIDATE_NAMES = Collections.unmodifiableList(
        Arrays.asList(
            "start.bat",
            "start.cmd",
            "start.sh",
            "run.bat",
            "run.cmd",
            "run.sh",
            "start-server.bat",
            "start-server.sh",
            "startserver.bat",
            "startserver.sh",
            "ServerStart.bat",
            "ServerStart.sh"));

    // \w doesn't include `.`, so `\bjava\w*\b` would only match `java` in `java.exe`. The
    // optional `(?:\.exe)?` ensures we cover the Windows quoted-path case too.
    private static final Pattern JAVA_TOKEN = Pattern.compile("\\bjava\\w*(?:\\.exe)?\\b");

    private ServerStartScriptPatcher() {}

    public enum Result {
        PATCHED,
        ALREADY_OK,
        NO_SCRIPTS_FOUND,
        ERROR
    }

    public static final class Outcome {

        public final Result result;
        public final List<Path> patchedFiles;
        public final List<Path> skippedFiles;

        public Outcome(Result result, List<Path> patchedFiles, List<Path> skippedFiles) {
            this.result = result;
            this.patchedFiles = Collections.unmodifiableList(patchedFiles);
            this.skippedFiles = Collections.unmodifiableList(skippedFiles);
        }
    }

    public static Outcome run(Path workDir) {
        List<Path> patched = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        boolean anyFound = false;
        for (String name : CANDIDATE_NAMES) {
            Path orig = workDir.resolve(name);
            if (!Files.isRegularFile(orig)) continue;
            anyFound = true;
            try {
                String content = new String(Files.readAllBytes(orig), StandardCharsets.UTF_8);
                if (content.contains(D_OPTION) || content.contains("jdkjarversion21enforcer")) {
                    skipped.add(orig);
                    continue;
                }
                Path target = parallelPathFor(orig);
                if (Files.exists(target)) {
                    skipped.add(orig);
                    continue;
                }
                String patchedContent = injectDOption(content);
                if (patchedContent.equals(content)) {
                    skipped.add(orig);
                    continue;
                }
                Files.write(target, patchedContent.getBytes(StandardCharsets.UTF_8));
                patched.add(target);
            } catch (IOException e) {
                return new Outcome(Result.ERROR, patched, skipped);
            }
        }
        if (!anyFound) return new Outcome(Result.NO_SCRIPTS_FOUND, patched, skipped);
        if (patched.isEmpty()) return new Outcome(Result.ALREADY_OK, patched, skipped);
        return new Outcome(Result.PATCHED, patched, skipped);
    }

    static Path parallelPathFor(Path original) {
        String name = original.getFileName()
            .toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext = (dot >= 0) ? name.substring(dot) : "";
        return original.resolveSibling(base + "-with-jdk-jar-21" + ext);
    }

    static String injectDOption(String content) {
        // Detect line ending used by the file so we don't churn CRLF <-> LF.
        String[] lines = content.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String stripped = stripCarriageReturn(rawLine);
            if (isCommentOrBlank(stripped)) continue;
            if (!looksLikeJavaInvocation(stripped)) continue;
            Matcher m = JAVA_TOKEN.matcher(stripped);
            int lastEnd = -1;
            while (m.find()) lastEnd = m.end();
            if (lastEnd < 0) continue;
            int insertPos = lastEnd;
            // Skip a possible closing quote right after the java token (e.g. "java.exe").
            if (insertPos < stripped.length() && stripped.charAt(insertPos) == '"') {
                insertPos++;
            }
            String patched = stripped.substring(0, insertPos) + " " + D_OPTION + stripped.substring(insertPos);
            // Preserve original CR if present.
            if (rawLine.endsWith("\r")) {
                patched = patched + "\r";
            }
            lines[i] = patched;
            changed = true;
            break;
        }
        if (!changed) return content;
        return String.join("\n", lines);
    }

    private static String stripCarriageReturn(String line) {
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static boolean isCommentOrBlank(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.startsWith("#")) return true;
        if (trimmed.startsWith("//")) return true;
        if (trimmed.startsWith("::")) return true;
        String lower = trimmed.toLowerCase();
        return lower.startsWith("rem ") || lower.equals("rem");
    }

    private static boolean looksLikeJavaInvocation(String line) {
        if (!JAVA_TOKEN.matcher(line)
            .find()) return false;
        return line.contains("-jar") || line.contains(".jar");
    }
}
