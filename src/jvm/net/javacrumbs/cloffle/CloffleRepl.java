package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CloffleRepl {

    private static final String RESET   = "\u001B[0m";
    private static final String BOLD    = "\u001B[1m";
    private static final String CYAN    = "\u001B[36m";
    private static final String GREEN   = "\u001B[32m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String RED     = "\u001B[31m";
    private static final String DIM     = "\u001B[2m";

    private static final String GUTTER = "      " + DIM + "│ " + RESET;

    public static void main(String[] args) throws IOException {
        String[] filtered = java.util.Arrays.stream(args)
                .filter(a -> !a.isEmpty())
                .toArray(String[]::new);

        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {

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
