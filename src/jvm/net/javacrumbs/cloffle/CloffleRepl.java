package net.javacrumbs.cloffle;

import clojure.lang.AFn;
import clojure.lang.IFn;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cloffle REPL entry point: builds a polyglot {@link Context}, then runs bootstrap {@code eval}s that load
 * {@code cloffle.repl}, install a host callback closed over the {@link Context}, and run the launcher.
 * Interactive and script modes are driven from Clojure; each user form / file is evaluated via
 * {@link Context#eval} on that context (through host {@link IFn} callbacks) so errors stay {@link PolyglotException}s
 * and reuse {@link PolyglotErrorConsoleDisplay} (guest {@code load-file} alone can unwrap to a bare
 * {@link java.lang.Exception} with no {@code .clj} stack for diagnostics).
 * <p>
 * To bootstrap {@code clojure.core} from a bytecode archive, set
 * {@code -D}{@value CloffleBytecodeSerializerMain#CORE_BYTECODE_ARCHIVE_PROP}{@code =/path/to/core.bc}
 * on the JVM (see {@link CloffleBytecodeSerializerMain}).
 */
public class CloffleRepl {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";

    /**
     * Eval helpers closed over a polyglot {@link Context}. Exposed to guest code as {@link IFn} values so
     * Clojure calls {@code invoke} (not Java reflection on {@code com.oracle.truffle.host.HostObject}).
     */
    private static final class ReplEvalHost {
        private final Context ctx;

        private ReplEvalHost(Context ctx) {
            this.ctx = ctx;
        }

        private void evalString(String code, String name) {
            try {
                Source src = Source.newBuilder("cloffle", code, name).buildLiteral();
                Value result = ctx.eval(src);
                System.out.println(GREEN + formatResult(result) + RESET);
            } catch (PolyglotException e) {
                PolyglotErrorConsoleDisplay.printError(code, e);
            }
        }

        private void evalFile(String path) throws IOException {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                System.err.println("\u001B[31mFile not found: " + path + "\u001B[0m");
                return;
            }
            String fileContents = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();
            try {
                Source src = Source.newBuilder("cloffle", fileContents, fileName).buildLiteral();
                Value result = ctx.eval(src);
                System.out.println(GREEN + "=> " + formatResult(result) + RESET);
            } catch (PolyglotException e) {
                PolyglotErrorConsoleDisplay.printError(fileContents, e);
            }
        }
    }

    private static IFn replEvalStringFn(ReplEvalHost host) {
        return new AFn() {
            @Override
            public Object invoke(Object arg1, Object arg2) {
                host.evalString((String) arg1, (String) arg2);
                return null;
            }
        };
    }

    private static IFn replEvalFileFn(ReplEvalHost host) {
        return new AFn() {
            @Override
            public Object invoke(Object arg1) {
                try {
                    host.evalFile((String) arg1);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        };
    }

    private static String formatResult(Value result) {
        if (result == null || result.isNull()) {
            return "nil";
        }
        return result.toString();
    }

    /**
     * Parenthesis/bracket/brace balance with string and line-comment rules (used by {@code cloffle.repl}
     * for multiline input).
     */
    public static boolean isBalanced(String input) {
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
            if (inString) {
                continue;
            }
            if (c == ';') {
                while (i < input.length() && input.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            switch (c) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
                default -> {
                }
            }
        }
        return parens <= 0 && brackets <= 0 && braces <= 0 && !inString;
    }

    public static void main(String[] args) {
        String[] filtered = java.util.Arrays.stream(args)
                .filter(a -> !a.isEmpty())
                .toArray(String[]::new);

        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {
            context.initialize("cloffle");
            runGuestLauncher(context, filtered);
        }
    }

    /**
     * Runs the guest {@code cloffle.repl} loop (same as {@link #main} with no script/expression args).
     * Used by {@link CloffleDapMain} with a DAP-enabled context.
     */
    public static void runInteractiveRepl(Context context) {
        context.initialize("cloffle");
        runGuestLauncher(context, new String[0]);
    }

    private static void runGuestLauncher(Context context, String[] args) {
        String installSrc = "(do (require 'cloffle.repl) (fn [s f] (cloffle.repl/install-host-eval! s f)))";
        Source installSource = Source.newBuilder("cloffle", installSrc, "cloffle-install-host").buildLiteral();
        try {
            ReplEvalHost host = new ReplEvalHost(context);
            Value installHostFn = context.eval(installSource);
            installHostFn.execute(context.asValue(replEvalStringFn(host)), context.asValue(replEvalFileFn(host)));
        } catch (PolyglotException e) {
            PolyglotErrorConsoleDisplay.printError(installSrc, e);
            System.exit(1);
        }
        String code = buildBootstrapCode(args);
        Source src = Source.newBuilder("cloffle", code, "cloffle-launcher").buildLiteral();
        try {
            context.eval(src);
        } catch (PolyglotException e) {
            PolyglotErrorConsoleDisplay.printError(code, e);
            System.exit(1);
        }
    }

    private static String buildBootstrapCode(String[] args) {
        StringBuilder sb = new StringBuilder("(cloffle.repl/run-from-launcher");
        for (String a : args) {
            sb.append(' ');
            sb.append(escapeClojureString(a));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String escapeClojureString(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + escapeClojureInner(s) + "\"";
    }

    private static String escapeClojureInner(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
