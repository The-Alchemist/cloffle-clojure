package net.javacrumbs.cloffle;

import java.io.IOException;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * CLI demo that shows source line/column tracking in action,
 * with squiggly-underline annotations on error locations (same rendering as
 * {@link CloffleRepl} via {@link PolyglotErrorConsoleDisplay}).
 *
 * <p><b>Per-expression source (now available):</b> Previously, guest stack frames
 * only had coarse source (e.g. whole file or whole form). Now each frame gets
 * the <em>exact line and column</em> of the expression for that activation:
 * the call site for invokes (e.g. the {@code (inner)} in {@code (defn outer [] (inner))}),
 * the condition for {@code if}, the binding for {@code def}, etc. So when an
 * exception is thrown, the polyglot stack trace shows the precise location of
 * each call (e.g. line 5 for {@code (outer)}, line 4 for {@code (inner)}, line 3
 * for {@code (fail)}). The demo {@code per_expression_source.clj} illustrates this.
 *
 * <p><b>Macro source tracking:</b> The second half of this demo explores where
 * the "red squiggly underline" lands when errors occur inside macro-expanded
 * code: {@code when-not}, {@code cond}, {@code ->}, {@code and}/{@code or},
 * and user-defined {@code defmacro} forms. The key question: does the error
 * point at the <em>macro call site</em> (the code the user wrote) or somewhere
 * in the expansion (code the user never sees)?
 */
public class SourceLocationDemo {

    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";

    public static void main(String[] args) throws IOException {
        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {

            header("Cloffle Source Location Demo");

            demo(context, "demo1.clj", SourceLocationResources.read("demo1.clj"));
            demo(context, "demo2.clj", SourceLocationResources.read("demo2.clj"));
            demo(context, "demo3.clj", SourceLocationResources.read("demo3.clj"));
            demo(context, "demo4.clj", SourceLocationResources.read("demo4.clj"));
            demo(context, "demo5.clj", SourceLocationResources.read("demo5.clj"));

            errorDemo(context, "error_demo.clj", SourceLocationResources.read("error_demo.clj"));
            errorDemo(context, "arity_error.clj", SourceLocationResources.read("arity_error.clj"));
            errorDemo(context, "interop.clj", SourceLocationResources.read("interop.clj"));
            errorDemo(context, "deep_stack.clj", SourceLocationResources.read("deep_stack.clj"));
            errorDemo(context, "per_expression_source.clj", SourceLocationResources.read("per_expression_source.clj"));
            errorDemo(context, "macro_throw.clj", SourceLocationResources.read("macro_throw.clj"));

            // ── Macro source-location demos ──────────────────────────
            // These test WHERE the red squiggly underline lands when an
            // error originates in code produced by macro expansion.
            // Ideally: at the macro call site the user wrote, not inside
            // the invisible expansion.

            header("Macro Source Location Demos");

            // 1. when-not: macro expands to (if (not pred) (do body) nil)
            //    The throw is inside the when-not body — does the error
            //    point at (when-not ...) or at (throw ...)?
            errorDemo(context, "macro_when_not.clj", SourceLocationResources.read("macro_when_not.clj"));

            // 2. cond: macro expands to nested if-else chain.
            //    No branch matches, falls through to default throw.
            errorDemo(context, "macro_cond.clj", SourceLocationResources.read("macro_cond.clj"));

            // 3. -> (thread-first): macro rewrites into nested calls.
            //    The error is in the *expanded* call — where does it point?
            errorDemo(context, "macro_thread_first.clj", SourceLocationResources.read("macro_thread_first.clj"));

            // 4. and / or short-circuit macros: expand to let + if chains.
            //    Force a throw in a position that and/or evaluates.
            errorDemo(context, "macro_and_throw.clj", SourceLocationResources.read("macro_and_throw.clj"));
            errorDemo(context, "macro_or_throw.clj", SourceLocationResources.read("macro_or_throw.clj"));

            // 5. User-defined defmacro: the user writes a macro whose
            //    expanded code throws at runtime. The defmacro is inside a
            //    (do ...) block — exercises the eager-host-eval-in-do fix.

            // 5a. Runtime error in user macro expansion
            errorDemo(context, "macro_user_runtime.clj", SourceLocationResources.read("macro_user_runtime.clj"));

            // 5b. Nested user macro calling built-in macro
            errorDemo(context, "macro_user_nested.clj", SourceLocationResources.read("macro_user_nested.clj"));

            // 6. Deep stack through macros: chain of calls where each
            //    level uses a different macro, so the stack trace shows
            //    how source tracks through multiple macro expansions.
            errorDemo(context, "macro_deep_stack.clj", SourceLocationResources.read("macro_deep_stack.clj"));

            footer("Done!");
        }
    }

    private static void demo(Context context, String fileName, String code) {
        section(fileName);
        PolyglotErrorConsoleDisplay.printNumberedSource(code, List.of());
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
            PolyglotErrorConsoleDisplay.printNumberedSource(code, List.of());
            System.out.println(GREEN + "  => (no error)" + RESET);
        } catch (PolyglotException e) {
            PolyglotErrorConsoleDisplay.printError(code, e);
        }
        System.out.println();
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
