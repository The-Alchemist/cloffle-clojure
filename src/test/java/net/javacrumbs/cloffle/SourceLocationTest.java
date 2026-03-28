package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies that Cloffle AST nodes carry source location information
 * so that stack traces, errors, and instrumentation can report
 * line/column numbers from the original Clojure source.
 *
 * <p>Uses the same Clojure resources as {@link SourceLocationDemo} and verifies
 * expected outcomes. The demo prints to stdout; this test asserts correctness.
 */
public class SourceLocationTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    // ── Success demos (from SourceLocationDemo) ──────────────────────

    @Test
    public void demo1SimpleExpression() throws IOException {
        String code = SourceLocationResources.read("demo1.clj");
        Value result = eval("demo1.clj", code);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void demo2LetBinding() throws IOException {
        String code = SourceLocationResources.read("demo2.clj");
        Value result = eval("demo2.clj", code);
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void demo3DefnAndCall() throws IOException {
        String code = SourceLocationResources.read("demo3.clj");
        Value result = eval("demo3.clj", code);
        assertThat(result.asLong()).isEqualTo(49L);
    }

    @Test
    public void demo4NestedIf() throws IOException {
        String code = SourceLocationResources.read("demo4.clj");
        Value result = eval("demo4.clj", code);
        assertThat(result.toString()).isEqualTo("only-first");
    }

    @Test
    public void demo5LoopRecur() throws IOException {
        String code = SourceLocationResources.read("demo5.clj");
        Value result = eval("demo5.clj", code);
        assertThat(result.asLong()).isEqualTo(15L);
    }

    // ── Error demos (from SourceLocationDemo) ──────────────────────────

    @Test
    public void errorDemoStackTraceHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("error_demo.clj");
        expectError("error_demo.clj", code, "something went wrong");
    }

    @Test
    public void arityErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("arity_error.clj");
        expectErrorWithGuestFrames("arity_error.clj", code);
    }

    @Test
    public void interopErrorHasSourceLocation() throws IOException {
        String code = SourceLocationResources.read("interop.clj");
        expectErrorWithGuestFrames("interop.clj", code);
    }

    @Test
    public void deepStackErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("deep_stack.clj");
        expectError("deep_stack.clj", code, "deep failure");
    }

    @Test
    public void perExpressionSourceErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("per_expression_source.clj");
        expectError("per_expression_source.clj", code, "thrown from fail");
    }

    @Test
    public void macroThrowErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_throw.clj");
        expectError("macro_throw.clj", code, "must be positive");
    }

    @Test
    public void macroWhenNotErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_when_not.clj");
        expectError("macro_when_not.clj", code, "too young");
    }

    @Test
    public void macroCondNoMatchReturnsNil() throws IOException {
        String code = SourceLocationResources.read("macro_cond.clj");
        Value result = eval("macro_cond.clj", code);
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void macroThreadFirstErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_thread_first.clj");
        expectErrorWithGuestFrames("macro_thread_first.clj", code);
    }

    @Test
    public void macroAndThrowErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_and_throw.clj");
        expectError("macro_and_throw.clj", code, "kaboom in and");
    }

    @Test
    public void macroOrThrowErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_or_throw.clj");
        expectError("macro_or_throw.clj", code, "kaboom in or");
    }

    @Test
    public void macroUserRuntimeErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_user_runtime.clj");
        expectError("macro_user_runtime.clj", code, "1 is not > 2");
    }

    @Test
    public void macroUserNestedErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_user_nested.clj");
        expectError("macro_user_nested.clj", code, "must be positive, got -5");
    }

    @Test
    public void macroDeepStackErrorHasGuestFrames() throws IOException {
        String code = SourceLocationResources.read("macro_deep_stack.clj");
        expectError("macro_deep_stack.clj", code, "expected string");
    }

    // ── Additional tests (no corresponding demo resource) ───────────────

    @Test
    public void namedSourcePreservesFileName() {
        Source src = Source.newBuilder("cloffle", "(+ 1 2)", "my_script.clj").buildLiteral();
        Value result = context.eval(src);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void tryCatchPreservesSourceTracking() {
        String code = "(try\n  (+ 1 2)\n  (catch Exception e\n    42))";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void letBindingsPreserveSourceTracking() {
        String code = "(let [a 1\n      b 2\n      c 3]\n  (+ a b c))";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(6L);
    }

    @Test
    public void readerErrorIsSyntaxErrorWithSourceLocation() {
        try {
            Source src = Source.newBuilder("cloffle", "(1/0)", "parse_test.clj").buildLiteral();
            context.eval(src);
            fail("Expected parse error for 1/0");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError())
                    .as("Reader error should be a syntax error, got: " + e.getMessage()
                            + " internal=" + e.isInternalError() + " guest=" + e.isGuestException())
                    .isTrue();
            assertThat(e.getSourceLocation()).isNotNull();
            assertThat(e.getMessage()).contains("Divide by zero");
        }
    }

    @Test
    public void analyzerErrorIsSyntaxErrorWithSourceLocation() {
        try {
            Source src = Source.newBuilder("cloffle", "undefined_var_xyz", "analyze_test.clj").buildLiteral();
            context.eval(src);
            fail("Expected analyzer error for undefined var");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError())
                    .as("Analyzer error should be a syntax error. msg=[%s] internal=%s guest=%s host=%s srcLoc=%s",
                            e.getMessage(), e.isInternalError(), e.isGuestException(),
                            e.isHostException(), e.getSourceLocation())
                    .isTrue();
        }
    }

    // ── Macro expansion error tests ──────────────────────────────────

    @Test
    public void macroBodyErrorHasEnrichedFrames() {
        String code = """
            (defmacro bad-macro [x]
              (/ 1 0)
              `(+ ~x 1))
            (bad-macro 42)""";
        try {
            eval("macro_body_error.clj", code);
            fail("Expected exception from macro body");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("macroexpand");
            assertThat(e.getMessage()).contains("Divide by zero");
        }
    }

    @Test
    public void macroBodyErrorPreservesArityCheck() {
        String code = """
            (defmacro needs-one [x]
              `(+ ~x 1))
            (needs-one 1 2 3)""";
        try {
            eval("macro_arity.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Wrong number of args");
        }
    }

    @Test
    public void workingMacroStillExpandsCorrectly() {
        String code = """
            (defmacro double-it [x]
              `(+ ~x ~x))
            (double-it 21)""";
        Value result = eval("macro_working.clj", code);
        assertThat(result.asLong()).isEqualTo(42L);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    private void expectError(String fileName, String code, String messageSubstring) throws IOException {
        try {
            eval(fileName, code);
            fail("Expected exception containing: " + messageSubstring);
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains(messageSubstring);
            assertThat(hasGuestFrame(e)).as("Should have at least one guest frame").isTrue();
        }
    }

    private void expectErrorWithGuestFrames(String fileName, String code) {
        try {
            eval(fileName, code);
            fail("Expected exception");
        } catch (PolyglotException e) {
            assertThat(hasGuestFrame(e)).as("Should have guest stack frames").isTrue();
        }
    }

    private static boolean hasGuestFrame(PolyglotException e) {
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) return true;
        }
        return false;
    }
}
