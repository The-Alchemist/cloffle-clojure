package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
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
import java.util.List;

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
    private static final String YELLOW  = "\u001B[33m";
    private static final String RED     = "\u001B[31m";
    private static final String DIM     = "\u001B[2m";

    private static final String GUTTER = "      " + DIM + "│ " + RESET;

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

    public static void main(String[] args) throws IOException {
        String dumpPath = System.getProperty(CORE_BYTECODE_DUMP_PROP);
        if (dumpPath != null && !dumpPath.isBlank()) {
            Path out = Path.of(dumpPath.trim());
            try {
                replLog("Dumping clojure.core bytecode archive (RT.init from source first)…");
                long t0 = System.nanoTime();
                RT.init();
                // Match CoreCljBytecodeSerializationRoundTripTest: *ns* root for compile-style thread snapshot.
                RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
                CloffleCoreBytecodeArchive.writeFromClasspathCore(out);
                long dumpNanos = System.nanoTime() - t0;
                double dumpSeconds = dumpNanos / 1_000_000_000.0;
                replLog(String.format("Dump finished in %.3f seconds → %s", dumpSeconds, out.toAbsolutePath()));
                System.out.println(BOLD + "Wrote core bytecode archive" + RESET + " → " + out.toAbsolutePath());
                System.out.println(DIM + "Replay with: -D" + CORE_BYTECODE_ARCHIVE_PROP + "=" + out.toAbsolutePath()
                        + RESET);
            } catch (Exception e) {
                System.err.println(RED + "core bytecode dump failed: " + e.getMessage() + RESET);
                e.printStackTrace(System.err);
                System.exit(1);
            }
            return;
        }

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
            printError(code, e);
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
            printError(code, e);
        }
    }

    // ── Error display with source underlines ───────────────────────────

    private static void printError(String code, PolyglotException e) {
        List<PolyglotErrorLocations.Region> annotations = PolyglotErrorLocations.collect(e);

        if (!annotations.isEmpty()) {
            printNumberedSource(code, annotations);
            System.out.println();
        }

        String msg = e.getMessage();
        String label;
        if (e.isInternalError()) {
            label = "Internal error: ";
        } else if (e.isSyntaxError()) {
            label = "Syntax error: ";
        } else {
            label = "Error: ";
        }

        String phaseInfo = formatPhase(e, annotations, code);
        if (phaseInfo != null) {
            label = phaseInfo;
        }

        System.err.println(RED + BOLD + label + RESET + RED + msg + RESET);

        // Numbered source + squiggles already show each span; avoid repeating the same "file:line → snippet"
        // labels. Only list additional guest frames when there is more than one distinct region.
        if (annotations.size() > 1) {
            System.err.println();
            System.err.println(CYAN + "  Call stack (guest frames):" + RESET);
            for (int i = 0; i < annotations.size(); i++) {
                PolyglotErrorLocations.Region a = annotations.get(i);
                String prefix = i == 0 ? "──▶ " : "    ";
                String fnSuffix = (a.fnName() != null && !a.fnName().isEmpty())
                        ? "  " + DIM + "in " + a.fnName() + RESET
                        : "";
                System.err.println(CYAN + "  " + prefix + shortRegionLabel(a) + RESET + fnSuffix);
            }
        }
    }

    /** {@code file:line:col} only; drops {@code → snippet} which duplicates numbered source above. */
    private static String shortRegionLabel(PolyglotErrorLocations.Region a) {
        String lab = a.label();
        int arrow = lab.indexOf(" → ");
        return arrow >= 0 ? lab.substring(0, arrow) : lab;
    }

    /**
     * {@code source:line:column} for the caret label and phase banner.
     * <p>
     * Truffle {@link PolyglotErrorLocations.Region} uses: {@code line}/{@code startCol} = <em>first</em>
     * character of the attributed {@code SourceSection}; {@code endLine}/{@code endCol} = <em>last</em>
     * character. For a multi-line span that wraps {@code defn} + body, that last character is often the
     * closing {@code )} of the {@code defn}, not the end of the {@code (throw ...)} form. When we find
     * {@code (throw} on the end line, we narrow the displayed endpoint (and underline) to the balanced
     * list so the numbers match the highlighted range. Prefer fixing spans in the guest (bytecode
     * {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} nests
     * {@link net.javacrumbs.cloffle.ast.ExprSourceSpans} per expr); this
     * stays as a display fallback when Polyglot still reports a wide multi-line primary region.
     */
    private static String displayFileLineCol(PolyglotErrorLocations.Region a, String fullSource) {
        String sl = shortRegionLabel(a);
        if (a.primary() && a.endLine() > a.line()) {
            int[] elc = adjustedEndLineAndColForPrimaryMulti(a, fullSource);
            sl = sl.replaceFirst(":\\d+:\\d+$", ":" + elc[0] + ":" + elc[1]);
        }
        return sl;
    }

    /** {@code [endLine, endCol]} 1-based inclusive column of the fault character for the banner. */
    private static int[] adjustedEndLineAndColForPrimaryMulti(
            PolyglotErrorLocations.Region a, String fullSource) {
        if (fullSource == null) {
            return new int[]{a.endLine(), a.endCol()};
        }
        String[] lines = fullSource.split("\n", -1);
        int li = a.endLine() - 1;
        if (li < 0 || li >= lines.length) {
            return new int[]{a.endLine(), a.endCol()};
        }
        int[] tr = throwFormRange0(lines[li]);
        if (tr == null) {
            return new int[]{a.endLine(), a.endCol()};
        }
        return new int[]{a.endLine(), tr[0] + tr[1]};
    }

    /**
     * Balanced {@code (throw ...)} on one source line, or {@code null}. {@code tr[0]} is 0-based start
     * index, {@code tr[1]} is length in {@link String} code units (same basis as Truffle columns here).
     */
    private static int[] throwFormRange0(String lineText) {
        int i = lineText.indexOf("(throw");
        if (i < 0) {
            return null;
        }
        int end = endExclusiveAfterBalancedList(lineText, i);
        if (end <= i) {
            return null;
        }
        return new int[]{i, end - i};
    }

    /** Exclusive end index after the {@code )} that matches {@code '('} at {@code openParenIndex}. */
    private static int endExclusiveAfterBalancedList(String s, int openParenIndex) {
        if (openParenIndex < 0 || openParenIndex >= s.length() || s.charAt(openParenIndex) != '(') {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int j = openParenIndex; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inString) {
                if (c == '\\' && j + 1 < s.length()) {
                    j++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == ';') {
                while (j + 1 < s.length() && s.charAt(j + 1) != '\n') {
                    j++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j + 1;
                }
            }
        }
        return -1;
    }

    static void printNumberedSource(String code, List<PolyglotErrorLocations.Region> annotations) {
        String[] lines = code.split("\n", -1);
        System.out.println();
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String lineText = lines[i];

            // Polyglot often attributes a multi-line span (e.g. defn head + body). For the primary
            // region, only underline the last line — the actual fault site (e.g. throw) — not the
            // enclosing form’s first line.
            List<PolyglotErrorLocations.Region> lineAnnotations = annotations.stream()
                    .filter(a -> primarySpanCoversLine(a, lineNum))
                    .toList();

            boolean isErrorLine = lineAnnotations.stream().anyMatch(PolyglotErrorLocations.Region::primary);

            String lineColor = isErrorLine ? RED : "";
            String lineReset = isErrorLine ? RESET : "";

            System.out.printf(DIM + "  %3d " + DIM + "│ " + RESET + "%s%s%s%n",
                    lineNum, lineColor, lineText, lineReset);

            for (PolyglotErrorLocations.Region a : lineAnnotations) {
                String color = a.primary() ? RED : YELLOW;
                int underlineStart;
                int underlineLen;
                boolean useCaret;

                if (a.endLine() > a.line()) {
                    if (lineNum == a.line()) {
                        underlineStart = Math.max(0, a.startCol() - 1);
                        underlineLen = Math.max(1, lineText.length() - underlineStart);
                        useCaret = false;
                    } else if (lineNum == a.endLine()) {
                        if (a.primary()) {
                            int[] tr = throwFormRange0(lineText);
                            if (tr != null) {
                                underlineStart = tr[0];
                                underlineLen = tr[1];
                            } else {
                                underlineStart = firstNonWhitespaceIndex(lineText);
                                underlineLen = Math.max(1, a.endCol() - underlineStart);
                            }
                        } else {
                            underlineStart = 0;
                            underlineLen = Math.max(1, lineText.length() - underlineStart);
                        }
                        useCaret = a.primary();
                    } else {
                        underlineStart = 0;
                        underlineLen = Math.max(1, lineText.length());
                        useCaret = false;
                    }
                } else {
                    underlineStart = Math.max(0, a.startCol() - 1);
                    underlineLen = Math.min(a.length(), Math.max(0, lineText.length() - underlineStart));
                    underlineLen = Math.max(1, underlineLen);
                    useCaret = a.primary();
                }

                StringBuilder squiggly = new StringBuilder();
                squiggly.append(GUTTER);
                squiggly.append(color);
                squiggly.append(" ".repeat(underlineStart));
                String locTag = a.primary() ? displayFileLineCol(a, code) : shortRegionLabel(a);
                String fnTag = (a.fnName() != null && !a.fnName().isEmpty())
                        ? DIM + "  in " + a.fnName() + RESET
                        : "";
                if (useCaret) {
                    squiggly.append("^");
                    squiggly.append("~".repeat(Math.max(0, underlineLen - 1)));
                    squiggly.append(" " + BOLD + locTag + RESET + fnTag);
                } else {
                    squiggly.append("~".repeat(underlineLen));
                    squiggly.append(" " + locTag + RESET + fnTag);
                }
                System.out.println(squiggly);
            }
        }
    }

    private static int firstNonWhitespaceIndex(String lineText) {
        int u = 0;
        while (u < lineText.length() && Character.isWhitespace(lineText.charAt(u))) {
            u++;
        }
        return u;
    }

    private static boolean primarySpanCoversLine(PolyglotErrorLocations.Region a, int lineNum) {
        if (a.primary() && a.endLine() > a.line()) {
            return lineNum == a.endLine();
        }
        return lineNum >= a.line() && lineNum <= a.endLine();
    }

    /**
     * Extracts the error phase from the exception context and formats
     * a phase-aware label like "Syntax error (read-source) at (foo.clj:4:3)".
     */
    private static String formatPhase(
            PolyglotException e, List<PolyglotErrorLocations.Region> annotations, String fullSource) {
        clojure.lang.Keyword phase = net.javacrumbs.cloffle.nodes.ClojureException.consumePhase();

        if (phase == null) {
            if (e.isSyntaxError()) {
                phase = clojure.lang.Keyword.intern(null, "read-source");
            }
        }

        if (phase == null) return null;

        String phaseName = phase.getName();
        String location = "";
        if (!annotations.isEmpty()) {
            PolyglotErrorLocations.Region primary = annotations.get(0);
            location = " at (" + displayFileLineCol(primary, fullSource) + ")";
        } else {
            SourceSection sl = e.getSourceLocation();
            if (sl != null && sl.isAvailable() && sl.hasLines()) {
                location = " at (" + sl.getSource().getName() + ":" + sl.getStartLine()
                        + ":" + sl.getStartColumn() + ")";
            }
        }

        String category;
        if ("read-source".equals(phaseName) || "macro-syntax-check".equals(phaseName)) {
            category = "Syntax error";
        } else if ("macroexpansion".equals(phaseName)) {
            category = "Syntax error (macroexpansion)";
        } else if ("compile-syntax-check".equals(phaseName) || "compilation".equals(phaseName)) {
            category = "Compile error";
        } else if ("print-eval-result".equals(phaseName)) {
            category = "Error printing result";
        } else {
            category = "Execution error";
        }

        return category + " (" + phaseName + ")" + location + ": ";
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
