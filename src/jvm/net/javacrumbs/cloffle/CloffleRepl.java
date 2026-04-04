package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;

/**
 * Cloffle REPL entry point. Optional bytecode cache flags (processed before positional args):
 * {@code --disable-cache}, {@code --enable-cache}, {@code --cache-file <path>} or {@code --cache-file=<path>}.
 */
public class CloffleRepl {

    /** Write a core bytecode archive (requires one normal {@link RT#init()} first). */
    public static final String CORE_BYTECODE_DUMP_PROP = "cloffle.core.bytecode.dump";

    /** Load {@code clojure.core} from this file at {@link RT#init()} instead of parsing source. */
    public static final String CORE_BYTECODE_ARCHIVE_PROP = "cloffle.core.bytecode.archive";

    private static final String RESET   = "\u001B[0m";
    private static final String BOLD    = "\u001B[1m";
    private static final String CYAN    = "\u001B[36m";
    private static final String GREEN   = "\u001B[32m";
    private static final String RED     = "\u001B[31m";
    private static final String DIM     = "\u001B[2m";

    /** Prefix for bootstrap / cache lines ({@link #replLog}). */
    private static final String REPL_LOG_TAG = "[Cloffle REPL]";

    /** Bootstrap / cache messages on stderr so they are visible next to GraalVM and {@code [Cloffle]} loader lines. */
    private static void replLog(String message) {
        System.err.println(BOLD + REPL_LOG_TAG + RESET + " " + message);
        System.err.flush();
    }

    private enum BytecodeCacheCliMode {
        /** Use only {@code -Dcloffle.core.bytecode.archive} (after CLI preprocessing). */
        INHERIT,
        ON,
        OFF
    }

    /**
     * Removes cache CLI tokens from {@code args}, applies them to system properties in order (later flags win).
     * {@code --cache-file} sets {@link #CORE_BYTECODE_ARCHIVE_PROP}. {@code --disable-cache} clears it so
     * {@code clojure.core} loads from source even if {@code -Dcloffle.core.bytecode.archive} was set on the JVM.
     */
    private static String[] filterArgsApplyBytecodeCacheCli(String[] args) {
        ArrayList<String> positional = new ArrayList<>(args.length);
        BytecodeCacheCliMode mode = BytecodeCacheCliMode.INHERIT;
        String cacheFileFromCli = null;
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            if ("--disable-cache".equals(a)) {
                mode = BytecodeCacheCliMode.OFF;
                cacheFileFromCli = null;
                i++;
            } else if ("--enable-cache".equals(a)) {
                mode = BytecodeCacheCliMode.ON;
                i++;
            } else if ("--cache-file".equals(a)) {
                if (i + 1 >= args.length) {
                    exitWithBytecodeCacheCliError("--cache-file requires a path");
                }
                cacheFileFromCli = args[i + 1];
                mode = BytecodeCacheCliMode.ON;
                i += 2;
            } else if (a.startsWith("--cache-file=")) {
                cacheFileFromCli = a.substring("--cache-file=".length());
                if (cacheFileFromCli.isEmpty()) {
                    exitWithBytecodeCacheCliError("--cache-file= requires a non-empty path");
                }
                mode = BytecodeCacheCliMode.ON;
                i++;
            } else {
                positional.add(a);
                i++;
            }
        }
        applyBytecodeCacheCli(mode, cacheFileFromCli);
        return positional.toArray(new String[0]);
    }

    private static void exitWithBytecodeCacheCliError(String message) {
        System.err.println(RED + BOLD + REPL_LOG_TAG + RESET + RED + " " + message + RESET);
        System.exit(1);
    }

    private static void applyBytecodeCacheCli(BytecodeCacheCliMode mode, String cacheFileFromCli) {
        switch (mode) {
            case OFF -> System.clearProperty(CORE_BYTECODE_ARCHIVE_PROP);
            case ON -> {
                if (cacheFileFromCli != null && !cacheFileFromCli.isBlank()) {
                    System.setProperty(CORE_BYTECODE_ARCHIVE_PROP, cacheFileFromCli.trim());
                }
                String ap = System.getProperty(CORE_BYTECODE_ARCHIVE_PROP);
                boolean haveArchive = ap != null && !ap.isBlank();
                if (!haveArchive) {
                    exitWithBytecodeCacheCliError(
                            "--enable-cache requires --cache-file <path> or -D" + CORE_BYTECODE_ARCHIVE_PROP);
                }
            }
            case INHERIT -> {}
        }
    }

    private static String calculateRelativePath(Path path) {
        Path here = Path.of(".").toAbsolutePath().normalize();
        Path there = path.toAbsolutePath().normalize();
        try {
            return here.relativize(there).toString();
        } catch (IllegalArgumentException e) {
            // Fallback if they are not compatible (e.g., different providers) -- use absolute path
            return there.toString();
        }
    }


    public static void main(String[] args) throws IOException {
        String[] argsAfterCacheCli = filterArgsApplyBytecodeCacheCli(args);

        String archivePath = System.getProperty(CORE_BYTECODE_ARCHIVE_PROP);
        if (archivePath != null && !archivePath.isBlank()) {
            replLog("clojure.core will load from bytecode archive file:");
            replLog("  " + Path.of(archivePath.trim()).toAbsolutePath());
            replLog("Loader prints [Cloffle] … bytecode cache … lines during context startup (unless -Dcloffle.core.bytecode.quiet=true).");
        }
        if (Boolean.getBoolean("cloffle.core.bytecode.quiet")
                && archivePath != null
                && !archivePath.isBlank()) {
            replLog("Note: cloffle.core.bytecode.quiet=true — cache timing lines from the loader are suppressed.");
        }

        String[] filtered = java.util.Arrays.stream(argsAfterCacheCli)
                .filter(a -> !a.isEmpty())
                .toArray(String[]::new);

        replLog("Creating Polyglot context (runs RT.init → clojure.core bootstrap here)…");
        long contextStartNanos = System.nanoTime();
        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {
            long contextMs = (System.nanoTime() - contextStartNanos) / 1_000_000L;

            if (filtered.length > 0 && filtered[0].endsWith(".clj")) {
                runFile(context, filtered[0]);
                return;
            }

            if (filtered.length > 0) {
                String expr = String.join(" ", filtered);
                evalAndPrint(context, expr, "repl");
                return;
            }

            repl(context);
        }
    }

    /**
     * Runs the same line-based Cloffle Polyglot REPL as {@link #main} with no script / expression args.
     * Used by {@link ClofficeDapMain} so the DAP-enabled context evaluates each form on the Truffle backend.
     */
    public static void runInteractiveRepl(Context context) throws IOException {
        repl(context);
    }

    private static void repl(Context context) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println(BOLD + "Cloffle REPL" + RESET + " (Clojure on Truffle)");
        System.out.println(DIM + "Type an expression, or :quit to exit." + RESET);
        System.out.println();

        StringBuilder buffer = new StringBuilder();
        boolean multiline = false;
        int evalCount = 0;

        while (true) {
            System.out.print(multiline ? (DIM + "  .. " + RESET) : (CYAN + "cloffle=> " + RESET));
            System.out.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }

            String trimmed = line.trim();
            if (!multiline && trimmed.equals(":quit")) {
                break;
            }

            buffer.append(line).append('\n');
            String input = buffer.toString().trim();

            if (!isBalanced(input)) {
                multiline = true;
                continue;
            }

            multiline = false;
            buffer.setLength(0);

            if (input.isEmpty()) {
                continue;
            }

            evalCount++;
            evalAndPrint(context, input, "repl-" + evalCount);
        }

        System.out.println(DIM + "Bye." + RESET);
    }

    private static void evalAndPrint(Context context, String code, String name) {
        try {
            Source src = Source.newBuilder("cloffle", code, name).buildLiteral();
            Value result = context.eval(src);
            System.out.println(GREEN + formatResult(result) + RESET);
        } catch (PolyglotException e) {
            PolyglotErrorConsoleDisplay.printError(code, e);
        }
    }

    private static void runFile(Context context, String path) throws IOException {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            System.err.println(RED + "File not found: " + path + RESET);
            return;
        }
        String code = Files.readString(filePath);
        String fileName = filePath.getFileName().toString();

        System.out.println(BOLD + "── " + CYAN + fileName + RESET);
        try {
            Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
            Value result = context.eval(src);
            System.out.println(GREEN + "=> " + formatResult(result) + RESET);
        } catch (PolyglotException e) {
            PolyglotErrorConsoleDisplay.printError(code, e);
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private static String formatResult(Value result) {
        if (result == null || result.isNull()) {
            return "nil";
        }
        return result.toString();
    }

    private static boolean isBalanced(String input) {
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        boolean inString = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == ';') {
                while (i < input.length() && input.charAt(i) != '\n') i++;
                continue;
            }
            switch (c) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
            }
        }
        return parens <= 0 && brackets <= 0 && braces <= 0 && !inString;
    }
}
