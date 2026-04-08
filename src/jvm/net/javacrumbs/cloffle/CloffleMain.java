package net.javacrumbs.cloffle;

import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Cloffle equivalent of {@code clojure.main}. Uses the Polyglot API to run
 * Clojure through Truffle while delegating to {@code cloffle.main/main}
 * (a re-export of {@code clojure.main}) for full CLI behavior (repl, script,
 * -m, -e, -i, etc.).
 *
 * <p>Usage (same as standard clojure.main):
 * <pre>
 *   java -cp ... net.javacrumbs.cloffle.CloffleMain [init-opt*] [main-opt] [arg*]
 * </pre>
 *
 * <p>Examples:
 * <pre>
 *   java -cp ... net.javacrumbs.cloffle.CloffleMain -r
 *   java -cp ... net.javacrumbs.cloffle.CloffleMain -e "(+ 1 2)"
 *   java -cp ... net.javacrumbs.cloffle.CloffleMain script.clj arg1 arg2
 *   java -cp ... net.javacrumbs.cloffle.CloffleMain -m my.ns arg1
 * </pre>
 */
public final class CloffleMain {

    public static void main(String[] args) {
        RT.init();
        runClojureMain(args);
    }

    /**
     * Equivalent of {@code clojure.main.legacy_repl}. For compatibility with
     * code that invokes {@code clojure.lang.Repl.main}.
     */
    public static void legacyRepl(String[] args) {
        RT.init();
        runClojureMain(legacyReplArgs(args));
    }

    /**
     * Equivalent of {@code clojure.main.legacy_script}. For compatibility with
     * code that invokes {@code clojure.lang.Script.main}.
     */
    public static void legacyScript(String[] args) {
        RT.init();
        runClojureMain(legacyScriptArgs(args));
    }

    private static String[] legacyReplArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[]{"-r"};
        }
        String[] out = new String[args.length + 1];
        out[0] = "-r";
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    private static String[] legacyScriptArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[]{"-"};
        }
        return args;
    }

    private static void runClojureMain(String[] args) {
        String argsLiteral = toClojureVectorLiteral(args);
        String code = "(require 'cloffle.main) (apply cloffle.main/main (seq " + argsLiteral + "))";

        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {
            Source src = Source.newBuilder("cloffle", code, "cloffle-main").buildLiteral();
            context.eval(src);
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                throw e;
            }
            System.exit(e.getExitStatus());
        }
    }

    /**
     * Converts Java String[] to a Clojure vector literal with proper escaping.
     */
    private static String toClojureVectorLiteral(String[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        String inner = Arrays.stream(args)
                .map(CloffleMain::escapeForClojureString)
                .collect(Collectors.joining(" ", "[", "]"));
        return inner;
    }

    private static String escapeForClojureString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
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
        sb.append("\"");
        return sb.toString();
    }
}
