package com.andgatech.jdkjarversion21enforcer.relaunch;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.logging.log4j.Logger;

/**
 * Forks a fresh JVM whose command line is identical to the current one plus the requested extra
 * JVM args, waits for it to finish, and then terminates the current process with the child's exit
 * code. The point is to give Forge mods a way to restart themselves with additional
 * {@code -D...} / {@code -javaagent:...} flags that must be present on the JVM command line
 * (and therefore cannot be applied from inside Minecraft anymore).
 *
 * <p>
 * The fork uses an {@code @argfile} to safely pass long Minecraft classpaths past Windows'
 * 32k command line limit. {@code inheritIO()} makes the child's stdout/stderr appear in the
 * launcher's log just like a normal launch, and the parent's exit code is the child's exit code so
 * launchers see one continuous game session.
 *
 * <p>
 * To prevent infinite restart loops, callers should add
 * {@code -Djdkjarversion21enforcer.relaunched=true} to {@code extraJvmArgs} (or use
 * {@link #RELAUNCH_GUARD_PROPERTY} / {@link #relaunchGuardArg()}). Child processes then check this
 * flag and skip the dialog.
 */
public final class JvmRelauncher {

    public static final String RELAUNCH_GUARD_PROPERTY = "jdkjarversion21enforcer.relaunched";

    private JvmRelauncher() {}

    public static String relaunchGuardArg() {
        return "-D" + RELAUNCH_GUARD_PROPERTY + "=true";
    }

    /**
     * Returns true if the current JVM is itself a child process spawned by an earlier relaunch.
     */
    public static boolean isRelaunchedChild() {
        return "true".equalsIgnoreCase(System.getProperty(RELAUNCH_GUARD_PROPERTY));
    }

    /**
     * Result of {@link #relaunchAndExit(List, Path, org.apache.logging.log4j.Logger)} when the
     * child process fails fast (exits non-zero in under {@link #FAST_FAIL_GRACE_MS} ms). The
     * caller should fall back to letting the current JVM continue starting normally instead of
     * terminating itself — otherwise the launcher would see a crashed game.
     */
    public static final long FAST_FAIL_GRACE_MS = 5_000L;

    /**
     * Forks a child JVM with the requested extra args appended to the current JVM args, waits for
     * it to terminate, then terminates the current process with the same exit code. <b>This method
     * does not return on success.</b>
     *
     * <p>
     * If the child exits non-zero within {@link #FAST_FAIL_GRACE_MS} ms it is considered to have
     * failed before getting anywhere, and this method returns normally with the child's exit code
     * so the caller can fall back to keeping the current JVM running. The child's stdout / stderr
     * are redirected to {@code <gameDir>/logs/jdkjarversion21enforcer-relaunch-{out,err}.log} for
     * post-mortem inspection.
     *
     * @param extraJvmArgs additional JVM args to prepend (e.g. {@code -Djdk.util.jar.version=21})
     * @param gameDir      the Minecraft instance directory; used to derive the relaunch log file
     *                     path. May be {@code null}, in which case logs are written into the JVM
     *                     working directory.
     * @param logger       optional logger for diagnostic INFO/ERROR messages
     * @return {@code OptionalInt.empty()} if the parent JVM was successfully terminated (this
     *         method does not actually return in that case); otherwise the child's exit code,
     *         signalling a fast-fail that the caller should treat as a soft failure.
     */
    public static java.util.OptionalInt relaunchAndExit(List<String> extraJvmArgs, Path gameDir,
        org.apache.logging.log4j.Logger logger) throws IOException, InterruptedException {
        List<String> command = buildChildCommand(
            currentJavaExecutable(),
            extraJvmArgs,
            currentJvmInputArgs(),
            currentClasspath(),
            currentMainCommand(),
            logger);
        if (logger != null) {
            logger.info("Relaunch: forking child JVM. Full command:");
            for (int i = 0; i < command.size(); i++) {
                logger.info("    [{}] {}", i, command.get(i));
            }
        }
        Path stdoutLog = resolveRelaunchLog(gameDir, "jdkjarversion21enforcer-relaunch-stdout.log");
        Path stderrLog = resolveRelaunchLog(gameDir, "jdkjarversion21enforcer-relaunch-stderr.log");
        // v0.5.7: pass the full argv directly to ProcessBuilder instead of writing a temporary
        // @argfile. Windows ProcessBuilder.start() uses CreateProcessW (the Unicode-safe Win32
        // API) which handles non-ASCII paths (like Chinese characters in the user's install
        // directory) correctly. The previous @argfile approach failed silently on such systems
        // because the C-coded java.exe launcher reads argfiles using the system ANSI codepage
        // (e.g. GBK on Chinese Windows), not UTF-8 — mojibake then breaks every subsequent
        // classpath entry, including the RFB launcher jar.
        long totalLen = 0;
        for (String t : command) totalLen += t.length() + 1;
        if (logger != null) {
            logger.info(
                "Relaunch: child argv has {} tokens, ~{} chars total (Windows CreateProcessW limit: 32767)",
                command.size(),
                totalLen);
            if (totalLen > 32_000) {
                logger.warn(
                    "Relaunch: child argv length {} is dangerously close to the Windows CreateProcessW limit; the start() call may fail.",
                    totalLen);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // Redirect stdout/stderr to files we can hand to the user for diagnosis. We do NOT use
        // inheritIO() because inheritIO closes the parent's standard handles when the parent
        // dies, which contributes to the child being killed in some launcher setups. File
        // redirection is safer.
        pb.redirectOutput(stdoutLog.toFile());
        pb.redirectError(stderrLog.toFile());

        long forkStartedAt = System.currentTimeMillis();
        Process child = pb.start();
        if (logger != null) {
            logger.info(
                "Relaunch: child JVM forked (pid hint via Process: {}). stdout -> {}, stderr -> {}.",
                safePidOf(child),
                stdoutLog,
                stderrLog);
        }
        int exitCode = child.waitFor();
        long elapsed = System.currentTimeMillis() - forkStartedAt;
        if (exitCode != 0 && elapsed < FAST_FAIL_GRACE_MS) {
            // Fast fail: do NOT terminate the parent. Let the caller decide whether to keep
            // the current JVM running so the user can at least play the game without -D.
            if (logger != null) {
                logger.error(
                    "Relaunch: child JVM exited with code {} in {} ms (fast fail). See {} and {} for details. The current JVM will keep running so the game can still launch (without -Djdk.util.jar.version=21).",
                    exitCode,
                    elapsed,
                    stdoutLog,
                    stderrLog);
            }
            return java.util.OptionalInt.of(exitCode);
        }
        if (logger != null) {
            logger.info(
                "Relaunch: child JVM exited normally with code {} after {} ms. Terminating parent JVM with the same code.",
                exitCode,
                elapsed);
        }
        // Use System.exit so shutdown hooks run cleanly; the launcher will see this exit code
        // as the game's exit code, which is what we want.
        System.exit(exitCode);
        return java.util.OptionalInt.empty(); // unreachable
    }

    private static Path resolveRelaunchLog(Path gameDir, String fileName) {
        Path base;
        if (gameDir != null) {
            base = gameDir.resolve("logs");
        } else {
            base = Paths.get(System.getProperty("user.dir", "."));
        }
        try {
            Files.createDirectories(base);
        } catch (IOException ignored) {
            base = Paths.get(System.getProperty("user.dir", "."));
        }
        return base.resolve(fileName);
    }

    private static String safePidOf(Process child) {
        // {@code Process#pid()} is Java 9+, but this project compiles against Java 8 source level
        // (Jabel relaxes the runtime to Java 22+). Use reflection to call it when available, and
        // fall back to a question mark otherwise. The diagnostic value of a missing PID is low
        // enough that we never want this method to throw or block compilation.
        try {
            Object pid = Process.class.getMethod("pid")
                .invoke(child);
            return String.valueOf(pid);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    static String currentJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return "java";
        }
        boolean windows = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
        return javaHome + (windows ? "/bin/java.exe" : "/bin/java");
    }

    static List<String> currentJvmInputArgs() {
        List<String> args = ManagementFactory.getRuntimeMXBean()
            .getInputArguments();
        return (args == null) ? Collections.emptyList() : new ArrayList<>(args);
    }

    static String currentClasspath() {
        String cp = System.getProperty("java.class.path");
        return (cp == null) ? "" : cp;
    }

    static String currentMainCommand() {
        String cmd = System.getProperty("sun.java.command");
        return (cmd == null) ? "" : cmd;
    }

    /**
     * Build a fresh argv. {@code extraJvmArgs} are placed first so the user can quickly identify
     * them in the launcher log. JVM input args that conflict (same {@code -Dkey} or same
     * {@code -javaagent} jar) are dropped.
     */
    static List<String> buildChildCommand(String javaExecutable, List<String> extraJvmArgs,
        List<String> existingJvmArgs, String classpath, String mainCommand) {
        return buildChildCommand(javaExecutable, extraJvmArgs, existingJvmArgs, classpath, mainCommand, null);
    }

    static List<String> buildChildCommand(String javaExecutable, List<String> extraJvmArgs,
        List<String> existingJvmArgs, String classpath, String mainCommand, Logger logger) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable);

        // Step 1: append our extra args first.
        cmd.addAll(extraJvmArgs);

        // Step 2: append existing JVM args, skipping any that we just declared a replacement for.
        Set<String> dKeysWeAdded = new HashSet<>();
        Set<String> agentJarsWeAdded = new HashSet<>();
        for (String a : extraJvmArgs) {
            String key = extractDPropertyKey(a);
            if (key != null) dKeysWeAdded.add(key);
            String agent = extractAgentJarPath(a);
            if (agent != null) agentJarsWeAdded.add(agent);
        }
        for (String a : existingJvmArgs) {
            String key = extractDPropertyKey(a);
            if (key != null && dKeysWeAdded.contains(key)) continue;
            String agent = extractAgentJarPath(a);
            if (agent != null && agentJarsWeAdded.contains(agent)) continue;
            cmd.add(a);
        }

        // Step 3: classpath. We may need to prepend extra entries discovered by walking class
        // loader code sources. This is critical for launchers like GTNH RFB that swap in a custom
        // system class loader via -Djava.system.class.loader and then strip the launcher's own
        // jar from java.class.path after bootstrap. If we forward -Djava.system.class.loader to
        // the child but not the jar containing that class, child startup fails in initPhase3
        // with ClassNotFoundException.
        List<String> extraEntries = discoverExtraClasspathEntries(existingJvmArgs, logger);
        String mergedCp = mergeClasspath(extraEntries, classpath);
        if (!mergedCp.isEmpty()) {
            cmd.add("-cp");
            cmd.add(mergedCp);
        }

        // Step 4: main class + args. sun.java.command is "MainClass arg1 arg2 ..." separated by
        // single spaces, which silently destroys args that contain spaces — a real problem for
        // Minecraft, where `--version "GT New Horizons 2.8.4"` is common. We therefore prefer
        // ProcessHandle.info().arguments(), which on Java 9+ returns the OS-level argv with token
        // boundaries intact; if it is unavailable (Windows can refuse) we fall back to the legacy
        // split.
        appendMainClassAndArgs(cmd, mainCommand, logger);

        return cmd;
    }

    static void appendMainClassAndArgs(List<String> cmd, String mainCommand, Logger logger) {
        if (mainCommand == null || mainCommand.isEmpty()) return;
        // The main class is whatever comes before the first space in sun.java.command. The space
        // is unambiguous here because main class names cannot contain spaces.
        int firstSpace = mainCommand.indexOf(' ');
        String mainClass = (firstSpace < 0) ? mainCommand : mainCommand.substring(0, firstSpace);
        if (mainClass.isEmpty()) return;

        cmd.add(mainClass);

        // PRIORITY 1: args we captured at LaunchWrapper Tweaker stage. This is the most reliable
        // source on Windows where Process Handle.info() is often denied by the OS, and on any
        // launcher that quotes args containing spaces (e.g. `--version "GT New Horizons 2.8.4"`).
        List<String> capturedArgs = LaunchArgsCapture.rebuildMainArgs();
        if (capturedArgs != null && !capturedArgs.isEmpty()) {
            cmd.addAll(capturedArgs);
            if (logger != null) logger.info(
                "[main-args] using LaunchArgsCapture (captured at Tweaker stage) for main args ({} tokens, all quote boundaries preserved)",
                capturedArgs.size());
            return;
        }

        // PRIORITY 2: ProcessHandle.info().arguments() / commandLine().
        List<String> rawMainArgs = tryGetMainArgsFromProcessHandle(mainClass, logger);
        if (rawMainArgs != null) {
            cmd.addAll(rawMainArgs);
            if (logger != null) logger.info(
                "[main-args] using ProcessHandle.info().arguments() for main args ({} tokens, spaces preserved)",
                rawMainArgs.size());
            return;
        }

        // PRIORITY 3: simple split-by-space. Args containing spaces will be fragmented; this is
        // the pre-v0.5.8 behaviour and is known to break Minecraft launchers that pass
        // `--version "GT New Horizons 2.8.4"`.
        if (firstSpace >= 0) {
            String tail = mainCommand.substring(firstSpace + 1);
            int fragmentCount = 0;
            for (String tok : tail.split(" ")) {
                if (!tok.isEmpty()) {
                    cmd.add(tok);
                    fragmentCount++;
                }
            }
            if (logger != null) logger.warn(
                "[main-args] FALLBACK: split sun.java.command tail by spaces into {} tokens; args containing spaces will be broken (e.g. `--version \"GT New Horizons\"` becomes 4 separate tokens). The relaunched game may load the wrong gameDir / version.",
                fragmentCount);
        }
    }

    /**
     * Reflectively calls {@code ProcessHandle.current().info().arguments()} (Java 9+) to recover
     * the raw OS-level argv of the parent JVM, with token boundaries intact. Returns the args
     * <em>after</em> the {@code mainClass} token (so JVM args / -cp / mainClass itself are
     * skipped). Returns {@code null} if the API is unavailable or did not return the expected
     * data; the caller must fall back to a less reliable source.
     */
    static List<String> tryGetMainArgsFromProcessHandle(String mainClass, Logger logger) {
        // Step 1: try ProcessHandle.info().arguments() (the cleanest API).
        String[] args = tryProcessHandleArguments(logger);
        if (args != null) {
            int mainArgsStart = locateMainArgsStart(args, mainClass, logger);
            if (mainArgsStart >= 0 && mainArgsStart <= args.length) {
                List<String> result = new ArrayList<>(args.length - mainArgsStart);
                for (int j = mainArgsStart; j < args.length; j++) result.add(args[j]);
                return result;
            }
            if (logger != null) logger.warn(
                "[main-args] could not locate main args boundary in ProcessHandle argv (mainClass='{}'); trying commandLine() fallback",
                mainClass);
        }

        // Step 2: fall back to ProcessHandle.info().commandLine() (single string), tokenize it
        // ourselves with shell-like quoting rules. This is what kicks in on Windows when
        // arguments() returns Optional.empty() but commandLine() may still work.
        String[] tokens = tryProcessHandleCommandLine(logger);
        if (tokens != null) {
            int mainArgsStart = locateMainArgsStart(tokens, mainClass, logger);
            if (mainArgsStart >= 0 && mainArgsStart <= tokens.length) {
                List<String> result = new ArrayList<>(tokens.length - mainArgsStart);
                for (int j = mainArgsStart; j < tokens.length; j++) result.add(tokens[j]);
                return result;
            }
            if (logger != null) logger.warn(
                "[main-args] could not locate main args boundary in commandLine() tokenization (mainClass='{}'); falling back to sun.java.command split",
                mainClass);
        }

        return null;
    }

    private static String[] tryProcessHandleArguments(Logger logger) {
        try {
            Class<?> phCls = Class.forName("java.lang.ProcessHandle");
            Object current = phCls.getMethod("current")
                .invoke(null);
            Object info = phCls.getMethod("info")
                .invoke(current);
            // IMPORTANT: look up `arguments` on the public interface ProcessHandle.Info, NOT on
            // `info.getClass()` (which is the package-private impl ProcessHandleImpl$Info — Java 9+
            // modules forbid reflective access to members of impl classes from outside java.base).
            Class<?> infoCls = Class.forName("java.lang.ProcessHandle$Info");
            Object argsOpt = infoCls.getMethod("arguments")
                .invoke(info);
            if (argsOpt == null) {
                if (logger != null) logger.info("[main-args] ProcessHandle.info().arguments() returned null Optional");
                return null;
            }
            Class<?> optCls = Class.forName("java.util.Optional");
            Boolean present = (Boolean) optCls.getMethod("isPresent")
                .invoke(argsOpt);
            if (!Boolean.TRUE.equals(present)) {
                if (logger != null) logger.warn(
                    "[main-args] ProcessHandle.info().arguments() is empty (typical on Windows when the OS denies the query); will try commandLine() instead");
                return null;
            }
            String[] args = (String[]) optCls.getMethod("get")
                .invoke(argsOpt);
            if (args == null) {
                if (logger != null) logger.info("[main-args] ProcessHandle returned null array inside Optional");
                return null;
            }
            if (logger != null) {
                logger.info("[main-args] ProcessHandle.info().arguments() returned {} OS-level tokens:", args.length);
                for (int i = 0; i < args.length; i++) {
                    logger.info("[main-args]     args[{}] = {}", i, args[i]);
                }
            }
            return args;
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[main-args] ProcessHandle.arguments() reflection failed: {}: {}",
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
            return null;
        }
    }

    private static String[] tryProcessHandleCommandLine(Logger logger) {
        try {
            Class<?> phCls = Class.forName("java.lang.ProcessHandle");
            Object current = phCls.getMethod("current")
                .invoke(null);
            Object info = phCls.getMethod("info")
                .invoke(current);
            // Same module-access caveat as in tryProcessHandleArguments: look up `commandLine` on
            // the public interface ProcessHandle.Info instead of the impl class.
            Class<?> infoCls = Class.forName("java.lang.ProcessHandle$Info");
            Object cmdLineOpt = infoCls.getMethod("commandLine")
                .invoke(info);
            if (cmdLineOpt == null) {
                if (logger != null)
                    logger.info("[main-args] ProcessHandle.info().commandLine() returned null Optional");
                return null;
            }
            Class<?> optCls = Class.forName("java.util.Optional");
            Boolean present = (Boolean) optCls.getMethod("isPresent")
                .invoke(cmdLineOpt);
            if (!Boolean.TRUE.equals(present)) {
                if (logger != null) logger.warn(
                    "[main-args] ProcessHandle.info().commandLine() is empty; falling back to sun.java.command split");
                return null;
            }
            String cmdLine = (String) optCls.getMethod("get")
                .invoke(cmdLineOpt);
            if (cmdLine == null || cmdLine.isEmpty()) {
                if (logger != null)
                    logger.info("[main-args] ProcessHandle.commandLine() returned null or empty string");
                return null;
            }
            if (logger != null) logger.info("[main-args] ProcessHandle.info().commandLine() = {}", cmdLine);
            String[] tokens = tokenizeCommandLine(cmdLine);
            if (logger != null) {
                logger.info("[main-args] tokenized into {} tokens:", tokens.length);
                for (int i = 0; i < tokens.length; i++) {
                    logger.info("[main-args]     cmdline[{}] = {}", i, tokens[i]);
                }
            }
            return tokens;
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[main-args] ProcessHandle.commandLine() reflection failed: {}: {}",
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
            return null;
        }
    }

    /**
     * Tokenizes a command line string with shell-like quoting rules: tokens are split on
     * whitespace, but matched double-quotes (and single-quotes) preserve any whitespace inside
     * them. Backslash inside double-quotes escapes the next character. Good enough for parsing
     * the output of {@code GetCommandLineW} on Windows or {@code /proc/.../cmdline} on Linux.
     */
    static String[] tokenizeCommandLine(String cmdLine) {
        if (cmdLine == null) return new String[0];
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        boolean haveToken = false;
        for (int i = 0; i < cmdLine.length(); i++) {
            char c = cmdLine.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < cmdLine.length()) {
                    char next = cmdLine.charAt(i + 1);
                    if (next == quoteChar || next == '\\') {
                        cur.append(next);
                        i++;
                        continue;
                    }
                }
                if (c == quoteChar) {
                    inQuote = false;
                    quoteChar = 0;
                    continue;
                }
                cur.append(c);
                haveToken = true;
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true;
                    quoteChar = c;
                    haveToken = true;
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    if (haveToken) {
                        out.add(cur.toString());
                        cur.setLength(0);
                        haveToken = false;
                    }
                    continue;
                }
                cur.append(c);
                haveToken = true;
            }
        }
        if (haveToken) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /**
     * Walks the OS argv to find the index of the first <em>main argument</em> (i.e. the first
     * token after the main class / jar). Three strategies, tried in order:
     *
     * <ol>
     * <li><b>{@code -jar <path>} mode</b>: when the OS argv contains {@code -jar <jar-path>}
     * (HMCL / many launchers do this) the main args start two tokens after the {@code -jar}
     * flag.</li>
     * <li><b>Exact main class match</b>: when the OS argv contains the {@code mainClass} string
     * verbatim (typical for {@code -cp ... <main-class>} mode).</li>
     * <li><b>Path suffix match</b>: when {@code mainClass} looks like a jar basename (e.g.
     * {@code mmc-bootstrap-1.0.jar}, which is what {@code sun.java.command} reports under
     * {@code -jar} mode) and the OS argv has the full absolute path. Tokens whose tail (after a
     * {@code /} or {@code \}) equals {@code mainClass} are accepted.</li>
     * </ol>
     */
    static int locateMainArgsStart(String[] args, String mainClass, Logger logger) {
        if (args == null) return -1;

        // Strategy 1: -jar <path>
        for (int i = 0; i < args.length - 1; i++) {
            if ("-jar".equals(args[i])) {
                if (logger != null) logger.info(
                    "[main-args] strategy 1 hit: -jar at index {}, jar path at {} ({}); main args start at {}",
                    i,
                    i + 1,
                    args[i + 1],
                    i + 2);
                return i + 2;
            }
        }

        if (mainClass == null || mainClass.isEmpty()) return -1;

        // Strategy 2: exact main class match
        for (int i = 0; i < args.length; i++) {
            if (mainClass.equals(args[i])) {
                if (logger != null)
                    logger.info("[main-args] strategy 2 hit: exact match for mainClass '{}' at index {}", mainClass, i);
                return i + 1;
            }
        }

        // Strategy 3: path-suffix match (handles -jar mode where sun.java.command stripped the
        // path, leaving us with just the jar basename while OS argv still has the full path).
        String slashSuffix = "/" + mainClass;
        String backslashSuffix = "\\" + mainClass;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a != null && (a.endsWith(slashSuffix) || a.endsWith(backslashSuffix))) {
                if (logger != null) logger
                    .info("[main-args] strategy 3 hit: path-suffix match for '{}' at index {} ({})", mainClass, i, a);
                return i + 1;
            }
        }

        return -1;
    }

    static String extractDPropertyKey(String arg) {
        if (arg == null || !arg.startsWith("-D")) return null;
        int eq = arg.indexOf('=');
        return (eq < 0) ? arg.substring(2) : arg.substring(2, eq);
    }

    static String extractAgentJarPath(String arg) {
        if (arg == null || !arg.startsWith("-javaagent:")) return null;
        String rest = arg.substring("-javaagent:".length());
        int eq = rest.indexOf('=');
        return (eq < 0) ? rest : rest.substring(0, eq);
    }

    /**
     * Some launchers (notably GTNH's RFB / RetroFuturaBootstrap) install a custom system class
     * loader via {@code -Djava.system.class.loader=<FQN>} and then strip the launcher's own jar(s)
     * from {@code java.class.path} after bootstrap. If we propagate the {@code -D} flag to the
     * child JVM but not the jar containing that class, child startup fails in {@code initPhase3}
     * with {@link ClassNotFoundException}.
     *
     * <p>
     * This method tries two precise strategies to locate the jar containing such a class:
     * <ol>
     * <li><b>{@link CodeSource}</b>: works for well-behaved classes whose ClassLoader sets a
     * proper {@link ProtectionDomain}. Many custom ClassLoaders do not.</li>
     * <li><b>{@code ClassLoader.getResource(<class file>)}</b>: returns a URL like
     * {@code jar:file:/path/to/rfb.jar!/com/.../X.class} which we parse to extract the jar path.
     * This works for any ClassLoader that exposes the .class file as a resource, regardless of
     * ProtectionDomain.</li>
     * </ol>
     *
     * <p>
     * <b>Note (v0.5.12):</b> we used to additionally harvest <em>every</em> URL from the
     * {@link URLClassLoader} chain (context loader, system loader, our own loader, and their
     * parents). That over-collected: {@code LaunchClassLoader} extends {@code URLClassLoader} and
     * has every Forge / GTNH mod jar as a URL, so on the child {@code -cp} we ended up
     * prepending all 156 mod jars on top of the same jars already present in
     * {@code java.class.path}. {@code mergeClasspath}'s {@link LinkedHashSet} dedupe only catches
     * exact-string duplicates, so any minor path-format difference (case, trailing slash) leaked
     * a duplicate through. The child JVM's FML then scanned mods/ once + scanned the {@code -cp}
     * mod jars twice, leading to "Found a duplicate mod" errors and an aborted boot. Strategies A
     * and B alone are precise: they only return the jar(s) actually containing the system
     * class-loader FQN, so nothing in {@code java.class.path} is duplicated.
     */
    public static List<String> discoverExtraClasspathEntries(List<String> existingJvmArgs, Logger logger) {
        Set<String> extra = new LinkedHashSet<>();

        // Strategy A + B: scan -Djava.system.class.loader and resolve via CodeSource +
        // ClassLoader.getResource on the FQN.
        String liveSysLoader = System.getProperty("java.system.class.loader");
        if (liveSysLoader != null && !liveSysLoader.trim()
            .isEmpty()) {
            if (logger != null)
                logger.info("[relaunch-cp] live java.system.class.loader = '{}', resolving jar path…", liveSysLoader);
            addClassLocation(liveSysLoader, extra, logger);
        }
        if (existingJvmArgs != null) {
            for (String arg : existingJvmArgs) {
                if (arg != null && arg.startsWith("-Djava.system.class.loader=")) {
                    String fqn = arg.substring("-Djava.system.class.loader=".length());
                    if (logger != null) logger.info("[relaunch-cp] input-arg java.system.class.loader = '{}'", fqn);
                    addClassLocation(fqn, extra, logger);
                }
            }
        }

        if (logger != null) {
            logger.info("[relaunch-cp] discovered {} extra entries to prepend onto child -cp:", extra.size());
            int i = 0;
            for (String e : extra) {
                logger.info("[relaunch-cp]     [{}] {}", i++, e);
            }
        }
        return new ArrayList<>(extra);
    }

    /**
     * Backwards-compatible overload retained for existing tests; in production callers always
     * pass a logger so they get diagnostic output when something goes wrong.
     */
    static List<String> discoverExtraClasspathEntries(List<String> existingJvmArgs) {
        return discoverExtraClasspathEntries(existingJvmArgs, null);
    }

    private static void addClassLocation(String fqcn, Set<String> sink, Logger logger) {
        if (fqcn == null) return;
        String trimmed = fqcn.trim();
        if (trimmed.isEmpty()) return;
        Class<?> cls;
        try {
            cls = Class.forName(trimmed);
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[relaunch-cp] Class.forName('{}') failed: {}: {}",
                trimmed,
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
            return;
        }
        if (logger != null)
            logger.info("[relaunch-cp] Class.forName('{}') OK; loader = {}", trimmed, cls.getClassLoader());

        // Strategy A: ProtectionDomain -> CodeSource -> Location.
        boolean foundViaCodeSource = false;
        try {
            ProtectionDomain pd = cls.getProtectionDomain();
            if (pd != null) {
                CodeSource cs = pd.getCodeSource();
                if (cs != null) {
                    URL loc = cs.getLocation();
                    if (loc != null) {
                        Path p = Paths.get(loc.toURI());
                        sink.add(p.toString());
                        foundViaCodeSource = true;
                        if (logger != null) logger.info("[relaunch-cp] CodeSource for '{}' -> {}", trimmed, p);
                    } else if (logger != null)
                        logger.warn("[relaunch-cp] CodeSource for '{}' has null Location URL", trimmed);
                } else if (logger != null) logger.warn("[relaunch-cp] '{}' has null CodeSource", trimmed);
            } else if (logger != null) logger.warn("[relaunch-cp] '{}' has null ProtectionDomain", trimmed);
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[relaunch-cp] CodeSource lookup for '{}' failed: {}: {}",
                trimmed,
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
        }

        // Strategy B (always run, even if A succeeded): resolve via ClassLoader.getResource. This
        // catches custom ClassLoaders that omit ProtectionDomain but still expose the .class file
        // as a resource. Returns paths look like jar:file:/.../rfb.jar!/com/.../X.class which we
        // parse to extract just the jar path.
        try {
            String resourceName = trimmed.replace('.', '/') + ".class";
            ClassLoader cl = cls.getClassLoader();
            URL resourceUrl = (cl == null) ? ClassLoader.getSystemResource(resourceName) : cl.getResource(resourceName);
            if (resourceUrl == null) {
                if (logger != null && !foundViaCodeSource)
                    logger.warn("[relaunch-cp] getResource('{}') returned null", resourceName);
            } else {
                String parsed = parseJarPathFromResourceUrl(resourceUrl, resourceName);
                if (parsed != null) {
                    boolean added = sink.add(parsed);
                    if (logger != null) logger.info(
                        "[relaunch-cp] getResource('{}') -> {} ({})",
                        resourceName,
                        parsed,
                        added ? "new entry" : "duplicate, ignored");
                } else if (logger != null) logger
                    .warn("[relaunch-cp] getResource('{}') returned unparseable URL: {}", resourceName, resourceUrl);
            }
        } catch (Throwable t) {
            if (logger != null) logger.warn(
                "[relaunch-cp] getResource lookup for '{}' failed: {}: {}",
                trimmed,
                t.getClass()
                    .getSimpleName(),
                t.getMessage());
        }
    }

    /**
     * Parses a {@code jar:file:/.../foo.jar!/path/to/Class.class} URL down to {@code /.../foo.jar},
     * or a loose {@code file:/.../path/to/Class.class} URL down to the directory that would be
     * the corresponding classpath entry. Returns {@code null} if the URL has neither shape.
     */
    static String parseJarPathFromResourceUrl(URL resourceUrl, String resourceName) {
        if (resourceUrl == null) return null;
        String s = resourceUrl.toString();
        try {
            if (s.startsWith("jar:") && s.contains("!/")) {
                String inner = s.substring("jar:".length(), s.indexOf("!/"));
                return Paths.get(URI.create(inner))
                    .toString();
            }
            if (s.startsWith("file:") && resourceName != null && s.endsWith("/" + resourceName)) {
                String dir = s.substring(0, s.length() - resourceName.length() - 1);
                return Paths.get(URI.create(dir))
                    .toString();
            }
        } catch (Throwable ignored) {
            // Fall through to null.
        }
        return null;
    }

    // (v0.5.12) The former `harvestUrlClassLoaderChain` helper has been removed. See the doc
    // comment on `discoverExtraClasspathEntries` for the rationale: harvesting every URL of every
    // visible URLClassLoader chain meant we re-prepended all 156 mod jars on top of an identical
    // set already in `java.class.path`, leading to FML "Found a duplicate mod" errors in the
    // child JVM once the rest of the boot sequence finally got that far (post-v0.5.11).

    static String mergeClasspath(List<String> extraEntries, String existingClasspath) {
        Set<String> ordered = new LinkedHashSet<>();
        if (extraEntries != null) {
            for (String e : extraEntries) {
                if (e != null && !e.isEmpty()) ordered.add(e);
            }
        }
        if (existingClasspath != null && !existingClasspath.isEmpty()) {
            for (String e : existingClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (e != null && !e.isEmpty()) ordered.add(e);
            }
        }
        return String.join(File.pathSeparator, ordered);
    }

    static Path writeArgfile(List<String> command) throws IOException {
        // The first command element is the java executable itself, which is passed as argv[0],
        // not in the argfile. Everything after goes in.
        List<String> escaped = new ArrayList<>(command.size() - 1);
        for (int i = 1; i < command.size(); i++) {
            escaped.add(escapeArgfileToken(command.get(i)));
        }
        Path argfile = Files.createTempFile("jdkjarversion21enforcer-relaunch-", ".argfile");
        Files.write(argfile, escaped, StandardCharsets.UTF_8);
        return argfile;
    }

    /**
     * Escape a single token for inclusion in a Java {@code @argfile}. We always quote and escape
     * backslashes / quotes; Java's @argfile parser accepts this universally and it sidesteps any
     * tricky whitespace handling.
     */
    static String escapeArgfileToken(String token) {
        StringBuilder sb = new StringBuilder(token.length() + 2);
        sb.append('"');
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    // Convenience overload used by tests.
    static List<String> buildChildCommandForTest(String javaExe, List<String> extras, String... existing) {
        return buildChildCommand(
            javaExe,
            extras,
            new ArrayList<>(Arrays.asList(existing)),
            "lib/foo.jar:lib/bar.jar",
            "net.minecraft.launchwrapper.Launch --version 1.7.10");
    }
}
