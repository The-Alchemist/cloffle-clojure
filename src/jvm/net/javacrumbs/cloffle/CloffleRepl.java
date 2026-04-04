package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cloffle REPL entry point: interactive session, a {@code .clj} file, or inline expressions.
 * <p>
 * To bootstrap {@code clojure.core} from a bytecode archive, set
 * {@code -D}{@value CloffleBytecodeSerializerMain#CORE_BYTECODE_ARCHIVE_PROP}{@code =/path/to/core.bc}
 * on the JVM (see {@link CloffleBytecodeSerializerMain}).
 */
public class CloffleRepl {

    private static final String RESET   = "\u001B[0m";
    private static final String BOLD    = "\u001B[1m";
    private static final String CYAN    = "\u001B[36m";
    private static final String GREEN   = "\u001B[32m";
    private static final String RED     = "\u001B[31m";
    private static final String DIM     = "\u001B[2m";

    /** Prefix for bootstrap lines ({@link #replLog}). */
    private static final String REPL_LOG_TAG = "[Cloffle REPL]";

    private static void replLog(String message) {
        System.err.println(BOLD + REPL_LOG_TAG + RESET + " " + message);
        System.err.flush();
    }

    public static void main(String[] args) throws IOException {
        String[] filtered = java.util.Arrays.stream(args)
                .filter(a -> !a.isEmpty())
                .toArray(String[]::new);

        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {
            // Without this, the Truffle language (and RT.init / clojure.core) is lazy until the first eval().
            replLog("Initializing Cloffle (RT.init → clojure.core bootstrap)…");
            context.initialize("cloffle");

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
     * Used by {@link CloffleDapMain} so the DAP-enabled context evaluates each form on the Truffle backend.
     */
    public static void runInteractiveRepl(Context context) throws IOException {
        context.initialize("cloffle");
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
