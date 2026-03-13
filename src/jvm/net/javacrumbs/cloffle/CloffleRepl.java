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
import java.util.ArrayList;
import java.util.List;

public class CloffleREPL {

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

            if (filtered.length > 0 && filtered[0].equals("--demo")) {
                runDemos(context);
                return;
            }

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

    private static void runDemos(Context context) throws IOException {
        Path demoDir = Path.of("src/script/demos");
        if (!Files.isDirectory(demoDir)) {
            System.err.println(RED + "Demo directory not found: " + demoDir + RESET);
            return;
        }

        System.out.println();
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println(BOLD + "  Cloffle Source Location Demos" + RESET);
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);

        List<Path> scripts = Files.list(demoDir)
                .filter(p -> p.toString().endsWith(".clj"))
                .sorted()
                .toList();

        for (Path script : scripts) {
            String code = Files.readString(script);
            String fileName = script.getFileName().toString();

            System.out.println();
            System.out.println(BOLD + "───────────────────────────────────────────────────────" + RESET);
            System.out.println(BOLD + "  " + CYAN + fileName + RESET);
            System.out.println(BOLD + "───────────────────────────────────────────────────────" + RESET);

            try {
                Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
                Value result = context.eval(src);
                printNumberedSource(code, List.of());
                System.out.println();
                System.out.println(GREEN + "  => " + formatResult(result) + RESET);
            } catch (PolyglotException e) {
                List<Annotation> annotations = collectAnnotations(e);
                if (annotations.isEmpty()) {
                    annotations = extractParseErrorLocation(e);
                }
                printNumberedSource(code, annotations);
                System.out.println();

                String label = e.isSyntaxError() ? "  Syntax error: " : "  Error: ";
                System.out.println(RED + BOLD + label + RESET + RED + e.getMessage() + RESET);

                if (!annotations.isEmpty()) {
                    System.out.println();
                    System.out.println(BOLD + "  Call stack (guest frames):" + RESET);
                    for (int i = 0; i < annotations.size(); i++) {
                        Annotation a = annotations.get(i);
                        String prefix = i == 0 ? "──▶ " : "    ";
                        String fnSuffix = (a.fnName != null && !a.fnName.isEmpty())
                                ? "  " + DIM + "in " + a.fnName + RESET
                                : "";
                        System.out.println(CYAN + "    " + prefix + a.label + RESET + fnSuffix);
                    }
                }
            }
        }

        System.out.println();
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println(BOLD + "  Done!" + RESET);
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println();
    }

    // ── Error display with squiggly underlines ──────────────────────────

    private static void printError(String code, PolyglotException e) {
        List<Annotation> annotations = collectAnnotations(e);

        if (annotations.isEmpty()) {
            annotations = extractParseErrorLocation(e);
        }

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
        System.err.println(RED + BOLD + label + RESET + RED + msg + RESET);

        if (!annotations.isEmpty()) {
            System.err.println();
            System.err.println(CYAN + "  Call stack (guest frames):" + RESET);
            for (int i = 0; i < annotations.size(); i++) {
                Annotation a = annotations.get(i);
                String prefix = i == 0 ? "──▶ " : "    ";
                String fnSuffix = (a.fnName != null && !a.fnName.isEmpty())
                        ? "  " + DIM + "in " + a.fnName + RESET
                        : "";
                System.err.println(CYAN + "  " + prefix + a.label + RESET + fnSuffix);
            }
        }
    }

    record Annotation(int line, int startCol, int length, String label, String fnName, boolean isPrimary) {}

    static List<Annotation> collectAnnotations(PolyglotException e) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<Annotation> annotations = new ArrayList<>();
        boolean first = true;

        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) continue;
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines() || !sl.hasColumns()) continue;

            Annotation a = makeAnnotation(sl, frame.getRootName(), first);
            String key = a.line + ":" + a.startCol;
            if (seen.add(key)) {
                annotations.add(a);
                first = false;
            }
        }

        List<net.javacrumbs.cloffle.nodes.ClojureException.CallFrame> enriched =
                net.javacrumbs.cloffle.nodes.ClojureException.consumeEnrichedFrames();
        if (!enriched.isEmpty()) {
            int insertPos = Math.min(1, annotations.size());
            for (net.javacrumbs.cloffle.nodes.ClojureException.CallFrame cf : enriched) {
                String key = cf.line() + ":" + cf.column();
                if (!seen.add(key)) continue;

                String loc = cf.sourceName() + ":" + cf.line() + ":" + cf.column();
                String snippet = cf.snippet() != null ? " → " + cf.snippet() : "";
                Annotation a = new Annotation(cf.line(), cf.column(), cf.length(),
                        loc + snippet, cf.fnName(), false);
                annotations.add(insertPos, a);
                insertPos++;
            }
        }
        return annotations;
    }

    private static Annotation makeAnnotation(SourceSection sl, String rootName, boolean isPrimary) {
        int line = sl.getStartLine();
        int col = sl.getStartColumn();
        int len = sl.hasCharIndex()
                ? Math.max(1, sl.getCharLength())
                : Math.max(1, sl.getEndColumn() - sl.getStartColumn() + 1);

        String loc = sl.getSource().getName() + ":" + line + ":" + col;
        String snippet = "";
        try {
            snippet = " → " + sl.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {}

        return new Annotation(line, col, len, loc + snippet, rootName, isPrimary);
    }

    static List<Annotation> extractParseErrorLocation(PolyglotException e) {
        SourceSection sl = e.getSourceLocation();
        if (sl == null || !sl.isAvailable() || !sl.hasLines() || !sl.hasColumns()) {
            return List.of();
        }

        int line = sl.getStartLine();
        int col = sl.getStartColumn();
        int len = sl.hasCharIndex()
                ? Math.max(1, sl.getCharLength())
                : Math.max(1, sl.getEndColumn() - sl.getStartColumn() + 1);

        String loc = sl.getSource().getName() + ":" + line + ":" + col;
        String snippet = "";
        try {
            snippet = " → " + sl.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {}

        return List.of(new Annotation(line, col, len, loc + snippet, null, true));
    }

    static void printNumberedSource(String code, List<Annotation> annotations) {
        String[] lines = code.split("\n", -1);
        System.out.println();
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String lineText = lines[i];

            List<Annotation> lineAnnotations = annotations.stream()
                    .filter(a -> a.line == lineNum)
                    .toList();

            boolean isErrorLine = lineAnnotations.stream().anyMatch(a -> a.isPrimary);

            String lineColor = isErrorLine ? RED : "";
            String lineReset = isErrorLine ? RESET : "";

            System.out.printf(DIM + "  %3d " + DIM + "│ " + RESET + "%s%s%s%n",
                    lineNum, lineColor, lineText, lineReset);

            for (Annotation a : lineAnnotations) {
                String color = a.isPrimary ? RED : YELLOW;
                int underlineStart = a.startCol - 1;
                int underlineLen = Math.min(a.length, lineText.length() - underlineStart);
                underlineLen = Math.max(1, underlineLen);

                StringBuilder squiggly = new StringBuilder();
                squiggly.append(GUTTER);
                squiggly.append(color);
                squiggly.append(" ".repeat(underlineStart));
                if (a.isPrimary) {
                    squiggly.append("^");
                    squiggly.append("~".repeat(Math.max(0, underlineLen - 1)));
                    squiggly.append(" " + BOLD + a.label + RESET);
                } else {
                    squiggly.append("~".repeat(underlineLen));
                    squiggly.append(" " + a.label + RESET);
                }
                System.out.println(squiggly);
            }
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
