package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI demo that shows source line/column tracking in action,
 * with squiggly-underline annotations on error locations.
 */
public class SourceLocationDemo {

    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String DIM    = "\u001B[2m";
    private static final String MAGENTA = "\u001B[35m";

    private static final String GUTTER = "      " + DIM + "│ " + RESET;

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

            footer("Done!");
        }
    }

    private static void demo(Context context, String fileName, String code) {
        section(fileName);
        printNumberedSource(code, List.of());
        System.out.println();
        System.out.println(YELLOW + "  Evaluating..." + RESET);

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
            printNumberedSource(code, List.of());
            System.out.println(GREEN + "  => (no error)" + RESET);
        } catch (PolyglotException e) {
            List<Annotation> annotations = collectAnnotations(e);
            printNumberedSource(code, annotations);
            System.out.println();

            System.out.println(RED + BOLD + "  Error: " + RESET + RED + e.getMessage() + RESET);
            System.out.println();

            if (!annotations.isEmpty()) {
                System.out.println(BOLD + "  Call stack (guest frames):" + RESET);
                for (int i = 0; i < annotations.size(); i++) {
                    Annotation a = annotations.get(i);
                    String prefix = i == 0 ? "──▶ " : "    ";
                    System.out.println(CYAN + "    " + prefix + a.label + RESET);
                }
            }
        }
        System.out.println();
    }

    record Annotation(int line, int startCol, int length, String label, boolean isPrimary) {}

    private static List<Annotation> collectAnnotations(PolyglotException e) {
        List<Annotation> annotations = new ArrayList<>();
        boolean first = true;
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) continue;
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines() || !sl.hasColumns()) continue;

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

            annotations.add(new Annotation(line, col, len, loc + snippet, first));
            first = false;
        }
        return annotations;
    }

    private static void printNumberedSource(String code, List<Annotation> annotations) {
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
