package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

/**
 * CLI demo that shows source line/column tracking in action,
 * with squiggly-underline annotations on error locations.
 *
 * <p><b>Per-expression source (now available):</b> Previously, guest stack frames
 * only had coarse source (e.g. whole file or whole form). Now each frame gets
 * the <em>exact line and column</em> of the expression for that activation:
 * the call site for invokes (e.g. the {@code (inner)} in {@code (defn outer [] (inner))}),
 * the condition for {@code if}, the binding for {@code def}, etc. So when an
 * exception is thrown, the polyglot stack trace shows the precise location of
 * each call (e.g. line 5 for {@code (outer)}, line 4 for {@code (inner)}, line 3
 * for {@code (fail)}). The demo {@code per_expression_source.clj} illustrates this.
 */
public class SourceLocationDemo {

    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String RED    = "\u001B[31m";
    private static final String DIM    = "\u001B[2m";


    public static void main(String[] args) {
        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {

            header("Cloffle Source Location Demo");

            demo(context, "demo1.clj", "(+ 1 2)");

            demo(context, "demo2.clj", """
                    (let [x 10
                          y 20]
                      (+ x y))""");

            demo(context, "demo3.clj", """
                    (do
                      (defn square [n]
                        (* n n))
                      (square 7))""");

            demo(context, "demo4.clj", """
                    (if (< 1 2)
                      (if (> 3 4)
                        "both"
                        "only-first")
                      "neither")""");

            demo(context, "demo5.clj", """
                    (loop [sum 0
                           cnt 5]
                      (if (= cnt 0)
                        sum
                        (recur (+ sum cnt)
                               (dec cnt))))""");

            errorDemo(context, "error_demo.clj", """
                    (do
                      (defn kaboom []
                        (throw (RuntimeException. "something went wrong")))
                      (defn call-kaboom []
                        (kaboom))
                      (call-kaboom))""");

            errorDemo(context, "arity_error.clj", """
                    (do
                      (defn greet [name]
                        (str "Hello, " name))
                      (greet "Alice" "Bob" "Charlie"))""");

            errorDemo(context, "interop.clj",
                    "(.substring \"hello\" 100)");

            errorDemo(context, "deep_stack.clj", """
                    (do
                      (defn level-3 []
                        (throw (Exception. "deep failure")))
                      (defn level-2 []
                        (level-3))
                      (defn level-1 []
                        (level-2))
                      (level-1))""");

            errorDemo(context, "per_expression_source.clj", """
                    (do
                      (defn fail []
                        (throw (RuntimeException. "thrown from fail")))
                      (defn inner []
                        (fail))
                      (defn outer []
                        (inner))
                      (outer))""");

            errorDemo(context, "macro_throw.clj", """
                    (do
                      (defn validate [x]
                        (when-not (pos? x)
                          (throw (RuntimeException. "must be positive"))))
                      (validate -1))""");

            footer("Done!");
        }
    }

    private static void demo(Context context, String fileName, String code) {
        section(fileName);
        printNumberedSource(code, ErrorLocation.NONE);
        System.out.println();

        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        Value result = context.eval(src);

        String display = result.isNull() ? "nil" : result.toString();
        System.out.println(GREEN + "  => " + display + RESET);
        System.out.println();
    }

    private static void errorDemo(Context context, String fileName, String code) {
        section(fileName);

        try {
            Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
            context.eval(src);
            printNumberedSource(code, ErrorLocation.NONE);
            System.out.println(GREEN + "  => (no error)" + RESET);
        } catch (PolyglotException e) {
            ErrorLocation loc = findErrorLocation(e);
            printNumberedSource(code, loc);
            System.out.println();
            System.out.printf(RED + BOLD + "  error" + RESET + DIM + "[" + RESET
                    + CYAN + "%s:%d:%d" + RESET + DIM + "]" + RESET + ": "
                    + RED + "%s" + RESET + "%n", fileName, loc.startLine, loc.startCol, e.getMessage());
        }
        System.out.println();
    }

    record ErrorLocation(int startLine, int startCol, int endLine, int endCol) {
        static final ErrorLocation NONE = new ErrorLocation(-1, -1, -1, -1);
    }

    private static ErrorLocation findErrorLocation(PolyglotException e) {
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) continue;
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines() || !sl.hasColumns()) continue;

            return new ErrorLocation(
                    sl.getStartLine(), sl.getStartColumn(),
                    sl.getEndLine(), sl.getEndColumn());
        }
        return ErrorLocation.NONE;
    }

    private static void printNumberedSource(String code, ErrorLocation err) {
        String[] lines = code.split("\n", -1);
        System.out.println();
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String lineText = lines[i];
            boolean inErrorRange = lineNum >= err.startLine && lineNum <= err.endLine;

            System.out.printf(DIM + "  %3d " + DIM + "│ " + RESET + "%s%s%s%n",
                    lineNum, inErrorRange ? RED : "", lineText, inErrorRange ? RESET : "");
        }
    }

    private static void header(String title) {
        System.out.println();
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println();
    }

    private static void footer(String title) {
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println(BOLD + "═══════════════════════════════════════════════════════" + RESET);
        System.out.println();
    }

    private static void section(String fileName) {
        System.out.println(BOLD + "───────────────────────────────────────────────────────" + RESET);
        System.out.println(BOLD + "  " + CYAN + fileName + RESET);
        System.out.println(BOLD + "───────────────────────────────────────────────────────" + RESET);
    }
}
