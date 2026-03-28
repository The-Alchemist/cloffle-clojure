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
    public void macroErrorSourceLocationUsesRealSourceName() {
        String code = "(defmacro real-src-macro [x]\n  (/ 1 0)\n  `(+ ~x 1))\n(real-src-macro 42)";
        try {
            eval("real_source.clj", code);
            fail("Expected exception from macro body");
        } catch (PolyglotException e) {
            assertThat(e.getSourceLocation()).as("Should have source location").isNotNull();
            assertThat(e.getSourceLocation().getSource().getName())
                    .as("Source location should reference 'real_source.clj', not a synthetic source")
                    .isEqualTo("real_source.clj");
        }
    }

    @Test
    public void nestedMacroChainShowsExpansionPath() {
        String code = """
            (defmacro nested-inner [x]
              (/ 1 0)
              `(+ ~x 1))
            (defmacro nested-outer [x]
              `(nested-inner ~x))
            (nested-outer 42)""";
        try {
            eval("nested_macro.clj", code);
            fail("Expected exception from nested macro body");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            assertThat(e.getMessage()).contains("nested-inner");
            assertThat(e.getMessage()).contains("macro chain");
            assertThat(e.getMessage()).contains("nested-outer");
        }
    }

    @Test
    public void deepCauseMessageSurfacesInParseError() {
        String code = """
            (defmacro deep-cause-macro [x]
              (try
                (/ 1 0)
                (catch Exception e
                  (throw (RuntimeException. "outer wrap" e))))
              `(+ ~x 1))
            (deep-cause-macro 42)""";
        try {
            eval("deep_cause.clj", code);
            fail("Expected exception from macro body");
        } catch (PolyglotException e) {
            String message = e.getMessage();
            assertThat(message).as("Should contain the wrapper message").contains("outer wrap");
            assertThat(message).as("Should surface the root cause").contains("Divide by zero");
        }
    }

    @Test
    public void compilerExceptionSourceSectionUsesDataLineColumn() {
        String code = "(defmacro ce-data-test [x]\n  (/ 1 0)\n  `(+ ~x 1))\n(ce-data-test 42)";
        try {
            eval("ce_data.clj", code);
            fail("Expected exception from macro body");
        } catch (PolyglotException e) {
            assertThat(e.getSourceLocation()).as("Should have source location").isNotNull();
            int line = e.getSourceLocation().getStartLine();
            assertThat(line)
                    .as("SourceSection line should come from CompilerException data (line 4), not reader position")
                    .isEqualTo(4);
        }
    }

    @Test
    public void macroErrorReportsCorrectLineColumn() {
        String code = "(defmacro blow-up [x]\n  (/ 1 0)\n  `(+ ~x 1))\n(blow-up 42)";
        try {
            eval("line_col_test.clj", code);
            fail("Expected exception from macro body");
        } catch (PolyglotException e) {
            assertThat(e.getMessage())
                    .as("Should report line 4 (the macro call site), not (0:0)")
                    .contains("4:");
            assertThat(e.getMessage()).doesNotContain("(0:0)");
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

    // ── Macro behavior tests ────────────────────────────────────────

    @Test
    public void macroWithGensymExpandsCorrectly() {
        String code = """
            (defmacro with-temp [& body]
              `(let [tmp# 42]
                 (+ tmp# ~@body)))
            (with-temp 8)""";
        Value result = eval("macro_gensym.clj", code);
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void macroWithSplicingUnquote() {
        String code = """
            (defmacro sum-all [& exprs]
              `(+ ~@exprs))
            (sum-all 1 2 3 4 5)""";
        Value result = eval("macro_splice.clj", code);
        assertThat(result.asLong()).isEqualTo(15L);
    }

    @Test
    public void variadicMacroExpandsCorrectly() {
        String code = """
            (defmacro log-and-return [msg & body]
              `(do ~@body))
            (log-and-return "test" (+ 1 2) (* 3 4))""";
        Value result = eval("macro_variadic.clj", code);
        assertThat(result.asLong()).isEqualTo(12L);
    }

    @Test
    public void macroExpandingToLetWorks() {
        String code = """
            (defmacro bind-and-add [a b]
              `(let [x# ~a
                     y# ~b]
                 (+ x# y#)))
            (bind-and-add 17 25)""";
        Value result = eval("macro_let.clj", code);
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void macroExpandingToTryWorks() {
        String code = """
            (defmacro safe-div [a b]
              `(try
                 (/ ~a ~b)
                 (catch ArithmeticException ~'e -1)))
            (safe-div 10 0)""";
        Value result = eval("macro_try.clj", code);
        assertThat(result.asLong()).isEqualTo(-1L);
    }

    @Test
    public void macroWithDestructuringArgs() {
        String code = """
            (defmacro swap-pair [[a b]]
              `[~b ~a])
            (swap-pair [1 2])""";
        Value result = eval("macro_destructure.clj", code);
        assertThat(result.getArraySize()).isEqualTo(2);
        assertThat(result.getArrayElement(0).asLong()).isEqualTo(2L);
        assertThat(result.getArrayElement(1).asLong()).isEqualTo(1L);
    }

    @Test
    public void macroReturningNonSeqLiteral() {
        String code = """
            (defmacro always-42 []
              42)
            (+ (always-42) 8)""";
        Value result = eval("macro_literal.clj", code);
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void macroReturningNil() {
        String code = """
            (defmacro return-nil []
              nil)
            (return-nil)""";
        Value result = eval("macro_nil.clj", code);
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void recursiveMacroExpansion() {
        String code = """
            (defmacro count-down [n]
              (if (pos? n)
                `(+ 1 (count-down ~(dec n)))
                0))
            (count-down 5)""";
        Value result = eval("macro_recursive.clj", code);
        assertThat(result.asLong()).isEqualTo(5L);
    }

    @Test
    public void multipleMacroCallsAreIsolated() {
        String code = """
            (defmacro make-adder [n]
              `(fn [x#] (+ x# ~n)))
            (let [add3 (make-adder 3)
                  add7 (make-adder 7)]
              (+ (add3 10) (add7 10)))""";
        Value result = eval("macro_isolated.clj", code);
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void threeLevelMacroNesting() {
        String code = """
            (defmacro level-3 [x] `(+ ~x 1))
            (defmacro level-2 [x] `(level-3 (+ ~x 10)))
            (defmacro level-1 [x] `(level-2 (+ ~x 100)))
            (level-1 0)""";
        Value result = eval("macro_3level.clj", code);
        assertThat(result.asLong()).isEqualTo(111L);
    }

    @Test
    public void macroWithDocstring() {
        String code = """
            (defmacro documented-macro
              "This macro doubles its argument"
              [x]
              `(+ ~x ~x))
            (documented-macro 21)""";
        Value result = eval("macro_docstring.clj", code);
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void macroThrowsSpecificExceptionType() {
        String code = """
            (defmacro strict-pos [x]
              (when-not (number? x)
                (throw (IllegalArgumentException.
                         (str "Expected number literal, got: " (type x)))))
              `(inc ~x))
            (strict-pos 5)""";
        Value result = eval("macro_iae.clj", code);
        assertThat(result.asLong()).isEqualTo(6L);
    }

    @Test
    public void macroThrowsSpecificExceptionTypeOnBadInput() {
        String code = """
            (defmacro strict-pos2 [x]
              (when-not (and (number? x) (pos? x))
                (throw (IllegalArgumentException.
                         (str "Need positive number, got: " x))))
              `(dec ~x))
            (strict-pos2 -3)""";
        try {
            eval("macro_iae_err.clj", code);
            fail("Expected IllegalArgumentException from macro");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Need positive number, got: -3");
        }
    }

    @Test
    public void coreMacroDoseqWorks() {
        String code = """
            (let [a (atom 0)]
              (doseq [i [1 2 3 4 5]]
                (swap! a + i))
              @a)""";
        Value result = eval("macro_doseq.clj", code);
        assertThat(result.asLong()).isEqualTo(15L);
    }

    @Test
    public void coreMacroForWorks() {
        String code = """
            (vec (for [x [1 2 3]
                       y [10 20]]
                   (+ x y)))""";
        Value result = eval("macro_for.clj", code);
        assertThat(result.getArraySize()).isEqualTo(6);
        assertThat(result.getArrayElement(0).asLong()).isEqualTo(11L);
        assertThat(result.getArrayElement(5).asLong()).isEqualTo(23L);
    }

    @Test
    public void coreMacroWhenLetWorks() {
        String code = """
            (when-let [x (get {:a 42} :a)]
              (+ x 8))""";
        Value result = eval("macro_when_let.clj", code);
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void coreMacroWhenLetNilReturnsNil() {
        String code = """
            (when-let [x (get {:a 42} :b)]
              (+ x 8))""";
        Value result = eval("macro_when_let_nil.clj", code);
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void coreMacroIfLetWorks() {
        String code = """
            (if-let [x (get {:a 42} :a)]
              (+ x 8)
              -1)""";
        Value result = eval("macro_if_let.clj", code);
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void coreMacroIfLetElseBranch() {
        String code = """
            (if-let [x (get {:a 42} :b)]
              (+ x 8)
              -1)""";
        Value result = eval("macro_if_let_else.clj", code);
        assertThat(result.asLong()).isEqualTo(-1L);
    }

    @Test
    public void coreMacroThreadLastWorks() {
        String code = """
            (->> [1 2 3 4 5]
                 (filter odd?)
                 (map inc)
                 (reduce +))""";
        Value result = eval("macro_thread_last.clj", code);
        assertThat(result.asLong()).isEqualTo(12L);
    }

    @Test
    public void coreMacroAsThreadWorks() {
        String code = """
            (as-> 0 v
              (+ v 10)
              (+ v 20)
              (* v 2))""";
        Value result = eval("macro_as_thread.clj", code);
        assertThat(result.asLong()).isEqualTo(60L);
    }

    @Test
    public void coreMacroLetfnWorks() {
        String code = """
            (letfn [(even? [n] (if (zero? n) true (odd? (dec n))))
                    (odd? [n] (if (zero? n) false (even? (dec n))))]
              [(even? 10) (odd? 7)])""";
        Value result = eval("macro_letfn.clj", code);
        assertThat(result.getArraySize()).isEqualTo(2);
        assertThat(result.getArrayElement(0).asBoolean()).isTrue();
        assertThat(result.getArrayElement(1).asBoolean()).isTrue();
    }

    @Test
    public void coreMacroSomeThreadWorks() {
        String code = """
            (some-> {:a {:b 42}}
                    :a
                    :b
                    inc)""";
        Value result = eval("macro_some_thread.clj", code);
        assertThat(result.asLong()).isEqualTo(43L);
    }

    @Test
    public void coreMacroSomeThreadShortCircuitsOnNil() {
        String code = """
            (some-> {:a {:b 42}}
                    :c
                    :b
                    inc)""";
        Value result = eval("macro_some_nil.clj", code);
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void macroExpandTimeResolveWorks() {
        String code = """
            (defmacro resolved-name [sym]
              (let [v (resolve sym)]
                (str v)))
            (resolved-name +)""";
        Value result = eval("macro_resolve.clj", code);
        assertThat(result.asString()).contains("clojure.core/+");
    }

    @Test
    public void nestedMacroChainErrorShowsAllLevels() {
        String code = """
            (defmacro chain-c [x]
              (/ 1 0)
              `(+ ~x 1))
            (defmacro chain-b [x]
              `(chain-c ~x))
            (defmacro chain-a [x]
              `(chain-b ~x))
            (chain-a 42)""";
        try {
            eval("macro_3chain.clj", code);
            fail("Expected exception from 3-level macro chain");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            assertThat(e.getMessage()).contains("chain-c");
            assertThat(e.getMessage()).contains("chain-b");
            assertThat(e.getMessage()).contains("chain-a");
        }
    }

    // ── Root SourceSection tests ──────────────────────────────────────

    @Test
    public void eagerEvalFormErrorHasSourceLocation() {
        String code = """
            (defmacro eager-test-macro [x]
              `(+ ~x 1))
            (eager-test-macro (/ 1 0))""";
        try {
            eval("eager_source.clj", code);
            fail("Expected runtime exception from eager-test-macro expansion result");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            assertThat(e.getSourceLocation()).as("Error should have source location").isNotNull();
        }
    }

    @Test
    public void letBindingErrorHasSourceLocation() {
        String code = """
            (let [x (/ 1 0)]
              x)""";
        try {
            eval("let_binding.clj", code);
            fail("Expected Divide by zero in let binding");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            boolean hasLocatedFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null
                        && frame.getSourceLocation().getStartLine() > 0) {
                    hasLocatedFrame = true;
                    break;
                }
            }
            assertThat(hasLocatedFrame)
                    .as("Error in let binding should have guest frame with line info")
                    .isTrue();
        }
    }

    @Test
    public void stackFrameShowsFunctionName() {
        String code = """
            (defn named-fn-test []
              (/ 1 0))
            (named-fn-test)""";
        try {
            eval("fn_name.clj", code);
            fail("Expected Divide by zero");
        } catch (PolyglotException e) {
            boolean foundName = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    String rootName = frame.getRootName();
                    if (rootName != null && rootName.contains("named-fn-test")) {
                        foundName = true;
                        break;
                    }
                }
            }
            assertThat(foundName)
                    .as("Guest stack frame should show function name 'named-fn-test'")
                    .isTrue();
        }
    }

    @Test
    public void fnErrorHasSourceLocationForArgs() {
        String code = """
            (defn use-args [a b]
              (/ a b))
            (use-args 1 0)""";
        try {
            eval("fn_args.clj", code);
            fail("Expected Divide by zero");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            boolean hasLocatedFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                    String srcName = frame.getSourceLocation().getSource().getName();
                    if ("fn_args.clj".equals(srcName) && frame.getSourceLocation().getStartLine() > 0) {
                        hasLocatedFrame = true;
                        break;
                    }
                }
            }
            assertThat(hasLocatedFrame)
                    .as("Error in fn should have guest frame with real source name")
                    .isTrue();
        }
    }

    @Test
    public void bodyExprNodeHasSourceLocation() {
        String code = """
            (do
              (+ 1 2)
              (/ 1 0))""";
        try {
            eval("body_expr.clj", code);
            fail("Expected Divide by zero");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            boolean hasLocatedFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null
                        && frame.getSourceLocation().getStartLine() > 0) {
                    hasLocatedFrame = true;
                    break;
                }
            }
            assertThat(hasLocatedFrame)
                    .as("Error in do body should have guest frame with line info")
                    .isTrue();
        }
    }

    @Test
    public void runtimeErrorInDefnHasSourceLocation() {
        String code = """
            (defn rt-err-fn []
              (/ 1 0))
            (rt-err-fn)""";
        try {
            eval("rt_err.clj", code);
            fail("Expected runtime exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            boolean hasGuestWithSource = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                    hasGuestWithSource = true;
                    break;
                }
            }
            assertThat(hasGuestWithSource)
                    .as("Runtime error should have guest frame with source location")
                    .isTrue();
        }
    }

    // ── Integration: error quality across scenarios ────────────────────

    @Test
    public void javaInteropErrorHasSourceLocation() {
        String code = """
            (Integer/parseInt "not-a-number")""";
        try {
            eval("interop.clj", code);
            fail("Expected NumberFormatException");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("NumberFormatException");
            boolean hasLocated = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null
                        && "interop.clj".equals(frame.getSourceLocation().getSource().getName())) {
                    hasLocated = true;
                    break;
                }
            }
            assertThat(hasLocated)
                    .as("Java interop error should have guest frame with real source name")
                    .isTrue();
        }
    }

    @Test
    public void tryCatchRethrowPreservesLocation() {
        String code = """
            (try
              (/ 1 0)
              (catch Exception e
                (throw e)))""";
        try {
            eval("rethrow.clj", code);
            fail("Expected Divide by zero");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
        }
    }

    @Test
    public void nestedFnCallsShowMultipleGuestFrames() {
        String code = """
            (defn inner-fn [] (/ 1 0))
            (defn outer-fn [] (inner-fn))
            (outer-fn)""";
        try {
            eval("nested_fns.clj", code);
            fail("Expected Divide by zero");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            int guestFrameCount = 0;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) guestFrameCount++;
            }
            assertThat(guestFrameCount)
                    .as("Nested fn calls should produce multiple guest frames")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    // ── Additional macro edge-case and error tests ────────────────────

    @Test
    public void macroExpandingToDoBlockWorks() {
        Value result = eval("do-macro.clj", """
            (defmacro with-side-effects [& body]
              `(do ~@body))
            (with-side-effects 1 2 3)""");
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void macroExpandingToIfWorks() {
        Value result = eval("if-macro.clj", """
            (defmacro my-if [test then else]
              `(if ~test ~then ~else))
            (my-if true 42 99)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void macroExpandingToDefnWorks() {
        Value result = eval("defn-macro.clj", """
            (defmacro def-doubler [name]
              `(defn ~name [x#] (* 2 x#)))
            (def-doubler dbl)
            (dbl 21)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void macroWithKeywordArgWorks() {
        Value result = eval("kwarg-macro.clj", """
            (defmacro with-opts [& {:keys [x y] :or {x 0 y 0}}]
              `(+ ~x ~y))
            (with-opts :x 10 :y 32)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void macroGeneratingAnonFnWorks() {
        Value result = eval("anon-fn-macro.clj", """
            (defmacro make-adder [n]
              `(fn [x#] (+ x# ~n)))
            ((make-adder 10) 32)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void anaphoricMacroWorks() {
        Value result = eval("anaphoric.clj", """
            (defmacro aif [test then else]
              `(let [~'it ~test]
                 (if ~'it ~then ~else)))
            (aif (+ 2 3) (* it 10) -1)""");
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void macroWithMultipleSplicedBodyForms() {
        Value result = eval("spliced-body.clj", """
            (defmacro with-logging [& body]
              `(let [result# (do ~@body)]
                 result#))
            (with-logging (+ 1 2) (* 3 4) 42)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void condpViaMacroWorks() {
        Value result = eval("condp.clj", """
            (condp = 3
              1 "one"
              2 "two"
              3 "three"
              "other")""");
        assertThat(result.asString()).isEqualTo("three");
    }

    @Test
    public void withOpenMacroWorks() {
        Value result = eval("with-open.clj", """
            (with-open [w (java.io.StringWriter.)]
              (.write w "hello")
              (.toString w))""");
        assertThat(result.asString()).isEqualTo("hello");
    }

    @Test
    public void bindingMacroWorks() {
        Value result = eval("binding.clj", """
            (def ^:dynamic *val* 1)
            (defn get-val [] *val*)
            (binding [*val* 42]
              (get-val))""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void dotoPaired() {
        Value result = eval("doto.clj", """
            (let [m (doto (java.util.HashMap.)
                      (.put "a" 1)
                      (.put "b" 2))]
              (.size m))""");
        assertThat(result.asInt()).isEqualTo(2);
    }

    @Test
    public void threadFirstWithInteropWorks() {
        Value result = eval("thread-interop.clj", """
            (-> "hello world"
                .toUpperCase
                (.replace "WORLD" "CLOJURE")
                .length)""");
        assertThat(result.asInt()).isEqualTo(13);
    }

    @Test
    public void threadLastWithCollectionOpsWorks() {
        Value result = eval("thread-last-coll.clj", """
            (->> (range 5) (filter even?) (map inc) (reduce +))""");
        assertThat(result.asLong()).isEqualTo(9L);
    }

    @Test
    public void commentFormReturnsNil() {
        Value result = eval("comment.clj", """
            (comment (/ 1 0) (throw (Exception.)) :whatever)""");
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void assertMacroPassesOnTruth() {
        Value result = eval("assert-pass.clj", """
            (do (assert true) 42)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void assertMacroFailsWithError() {
        try {
            eval("assert-fail.clj", "(assert false \"should fail\")");
            fail("Expected exception from assert");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Assert failed");
        }
    }

    @Test
    public void delayAndDerefWorks() {
        Value result = eval("delay.clj", """
            (let [d (delay (+ 20 22))]
              @d)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void whenFirstMacroWorks() {
        Value result = eval("when-first.clj", """
            (when-first [x [10 20 30]] (+ x 1))""");
        assertThat(result.asLong()).isEqualTo(11L);
    }

    @Test
    public void whenFirstMacroEmptyReturnsNil() {
        Value result = eval("when-first-empty.clj", """
            (when-first [x []] :nope)""");
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void ifSomeMacroWorks() {
        Value result = eval("if-some.clj", """
            (if-some [x 42] (+ x 8) -1)""");
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void ifSomeNilBranch() {
        Value result = eval("if-some-nil.clj", """
            (if-some [x nil] (+ x 8) -1)""");
        assertThat(result.asLong()).isEqualTo(-1L);
    }

    @Test
    public void whenSomeMacroWorks() {
        Value result = eval("when-some.clj", """
            (when-some [x 42] (+ x 8))""");
        assertThat(result.asLong()).isEqualTo(50L);
    }

    @Test
    public void lazyCatMacroWorks() {
        Value result = eval("lazy-cat.clj", """
            (str (vec (take 6 (lazy-cat [1 2] [3 4] [5 6 7]))))""");
        assertThat(result.asString()).isEqualTo("[1 2 3 4 5 6]");
    }

    @Test
    public void loopDestructuringWorks() {
        Value result = eval("loop-destructure.clj", """
            (loop [[x & xs] [1 2 3 4 5]
                   acc 0]
              (if x
                (recur xs (+ acc x))
                acc))""");
        assertThat(result.asLong()).isEqualTo(15L);
    }

    @Test
    public void forWithWhenAndLetWorks() {
        Value result = eval("for-modifiers.clj", """
            (str (vec (for [x (range 10)
                           :when (odd? x)
                           :let [sq (* x x)]]
                        sq)))""");
        assertThat(result.asString()).isEqualTo("[1 9 25 49 81]");
    }

    @Test
    public void lockingMacroWorks() {
        Value result = eval("locking.clj", """
            (let [lock (Object.)
                  a (atom 0)]
              (locking lock
                (swap! a inc))
              @a)""");
        assertThat(result.asLong()).isEqualTo(1L);
    }

    @Test
    public void declareThenForwardRefWorks() {
        Value result = eval("declare.clj", """
            (declare fwd-fn)
            (defn calls-fwd [] (fwd-fn 5))
            (defn fwd-fn [x] (* x x))
            (calls-fwd)""");
        assertThat(result.asLong()).isEqualTo(25L);
    }

    @Test
    public void timeMacroReturnsValue() {
        Value result = eval("time.clj", """
            (time (+ 20 22))""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void definlineMacroWorks() {
        Value result = eval("definline.clj", """
            (definline my-inc [x] `(+ ~x 1))
            (my-inc 41)""");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void withLocalVarsMacroWorks() {
        Value result = eval("with-local-vars.clj", """
            (with-local-vars [x 10 y 20]
              (+ (var-get x) (var-get y)))""");
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void runtimeErrorInMacroExpansionHasSourceLocation() {
        try {
            eval("macro-runtime-err.clj", """
                (defmacro bad-macro []
                  (/ 1 0))
                (bad-macro)""");
            fail("Expected exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
            assertThat(e.getSourceLocation()).isNotNull();
        }
    }

    @Test
    public void deeplyNestedMacroErrorHasLocation() {
        try {
            eval("deep-macro-err.clj", """
                (defmacro level-a [x] `(level-b ~x))
                (defmacro level-b [x] `(level-c ~x))
                (defmacro level-c [x] `(/ ~x 0))
                (level-a 42)""");
            fail("Expected exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Divide by zero");
        }
    }

    @Test
    public void macroExpansionWithSideEffectingBodyWorks() {
        Value result = eval("macro-side-effect.clj", """
            (def counter (atom 0))
            (defmacro do-and-count [& body]
              `(do
                 (swap! counter inc)
                 ~@body))
            (do-and-count (+ 1 2))
            (do-and-count (+ 3 4))
            @counter""");
        assertThat(result.asLong()).isEqualTo(2L);
    }

    @Test
    public void condThreadMacroCompositionWorks() {
        Value result = eval("cond-thread.clj", """
            (let [m (cond-> {:a 1}
                      true (assoc :b 2)
                      false (assoc :c 3)
                      true (assoc :d 4))]
              (str (contains? m :b) " " (contains? m :c) " " (contains? m :d)))""");
        assertThat(result.asString()).isEqualTo("true false true");
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
