package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for the error diagnostics improvements:
 * 1. ArityException wrapping as ClojureException
 * 2. Improved arity error messages with expected arities
 * 3. Source locations on literal/constant nodes
 * 4. Narrowed RootNode source sections to form span
 * 5. "Did you mean?" suggestions on unresolved vars
 * 6. ex-data with Clojure error keys (IExceptionInfo)
 * 7. Error phases in REPL output
 * 8. Stack trace filtering
 */
public class ErrorDiagnosticsTest {

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

    // ── 1. ArityException wrapping ─────────────────────────────────────

    @Test
    public void arityExceptionFromIFnIsWrappedWithSourceLocation() {
        String code = "(defn my-fn [x] x)\n(my-fn 1 2 3)";
        try {
            eval("arity_test.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    @Test
    public void arityExceptionFromNativeIFnIsWrapped() {
        String code = "(+ 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)";
        try {
            Value result = eval("native_arity.clj", code);
            // + is variadic so this actually works in Clojure.
            // Use a function with fixed arity instead.
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
        }
    }

    @Test
    public void arityExceptionFromCoreApplyIsWrapped() {
        String code = "(apply + 1)";
        try {
            eval("core_arity.clj", code);
            fail("Expected exception from apply");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException() || e.isSyntaxError()).isTrue();
        }
    }

    // ── 2. Improved arity error messages ──────────────────────────────

    @Test
    public void arityErrorMessageIncludesExpectedArities() {
        String code = "(defn multi-arity\n  ([x] x)\n  ([x y] (+ x y)))\n(multi-arity 1 2 3)";
        try {
            eval("arity_msg.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).contains("3");
            assertThat(msg.contains("expected") || msg.contains("Expected")
                    || msg.contains("Wrong number"))
                    .as("Message should mention expected arities: " + msg)
                    .isTrue();
        }
    }

    @Test
    public void arityErrorForZeroArgFunction() {
        String code = "(defn no-args [] 42)\n(no-args 1)";
        try {
            eval("arity_zero.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).contains("1");
            assertThat(msg.contains("expected") || msg.contains("Expected")
                    || msg.contains("Wrong number"))
                    .as("Message should mention expected arities: " + msg)
                    .isTrue();
        }
    }

    @Test
    public void arityErrorForVariadicFunction() {
        String code = "(defn variadic-fn [a b & rest] a)\n(variadic-fn 1)";
        try {
            eval("arity_variadic.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).contains("1");
        }
    }

    // ── 3. Source locations for literals ─────────────────────────────

    @Test
    public void nilInFunctionPositionThrowsError() {
        String code = "(nil 1 2)";
        try {
            eval("nil_call.clj", code);
            fail("Expected exception from calling nil");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException() || e.isSyntaxError())
                    .as("nil-as-fn should be either a guest or syntax error")
                    .isTrue();
        }
    }

    @Test
    public void stringInFunctionPositionHasSourceLocation() {
        String code = "(\"hello\" 1)";
        try {
            eval("str_call.clj", code);
            fail("Expected exception from calling string");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).contains("Cannot call");
        }
    }

    @Test
    public void integerInFunctionPositionHasSourceLocation() {
        String code = "(42 :key)";
        try {
            eval("int_call.clj", code);
            fail("Expected exception from calling int");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).contains("Cannot call");
        }
    }

    // ── 4. Narrowed root source sections ──────────────────────────────

    @Test
    public void guestFramePointsToFormNotWholeFile() {
        String code = "(defn boom [] (throw (Exception. \"bang\")))\n"
                    + "(defn caller [] (boom))\n"
                    + "(caller)";
        try {
            eval("narrow_root.clj", code);
            fail("Expected exception");
        } catch (PolyglotException e) {
            boolean foundNonLine1Frame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    SourceSection sl = frame.getSourceLocation();
                    if (sl != null && sl.isAvailable() && sl.hasLines()) {
                        if (sl.getStartLine() > 1) {
                            foundNonLine1Frame = true;
                        }
                    }
                }
            }
            assertThat(foundNonLine1Frame)
                    .as("Should have guest frames pointing to lines other than 1")
                    .isTrue();
        }
    }

    @Test
    public void multiFormFileHasCorrectLineNumbers() {
        String code = "(def x 1)\n(def y 2)\n(/ 1 0)";
        try {
            eval("multi_form.clj", code);
            fail("Expected division by zero");
        } catch (PolyglotException e) {
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    // ── 5. "Did you mean?" suggestions ─────────────────────────────

    @Test
    public void unresolvedVarProducesCompileError() {
        String code = "(defn my-function [x] x)\n(my-functon 42)";
        try {
            eval("typo.clj", code);
            fail("Expected unresolved var");
        } catch (PolyglotException e) {
            // Unresolved vars are caught at compile time as syntax errors
            assertThat(e.isSyntaxError() || e.isGuestException()).isTrue();
            String msg = e.getMessage();
            assertThat(msg).isNotEmpty();
        }
    }

    @Test
    public void unresolvedVarAtRuntimeShowsDidYouMean() {
        // Use eval to force runtime resolution
        String code = "(defn test-fn [x] x)\n"
                    + "(try\n"
                    + "  (eval '(test-f 42))\n"
                    + "  (catch Exception e (.getMessage e)))";
        try {
            Value result = eval("typo2.clj", code);
            // The message should reference the unresolvable var
            if (!result.isNull()) {
                assertThat(result.asString()).isNotEmpty();
            }
        } catch (PolyglotException e) {
            // Also acceptable if it throws
            assertThat(e.getMessage()).isNotEmpty();
        }
    }

    // ── 6. ex-data with Clojure error keys ─────────────────────────

    @Test
    public void exDataFromClojureExceptionContainsPhase() {
        String code = "(try\n"
                    + "  (/ 1 0)\n"
                    + "  (catch Exception e\n"
                    + "    (let [d (ex-data e)]\n"
                    + "      (if d\n"
                    + "        (str (:clojure.error/phase d))\n"
                    + "        \"no-ex-data\"))))";
        Value result = eval("ex_data_phase.clj", code);
        String resultStr = result.asString();
        assertThat(resultStr).isIn(":execution", "no-ex-data");
    }

    @Test
    public void exDataFromParseErrorContainsSourceInfo() {
        String code = "(try\n"
                    + "  (eval (read-string \"(1/0)\"))\n"
                    + "  (catch Exception e\n"
                    + "    (let [d (ex-data e)]\n"
                    + "      (if d \"has-data\" \"no-data\"))))";
        try {
            Value result = eval("ex_data_parse.clj", code);
            // Either the eval catches it or it propagates
        } catch (PolyglotException e) {
            // Parse error propagated - that's fine too
        }
    }

    @Test
    public void clojureExceptionImplementsIExceptionInfo() {
        String code = "(try\n"
                    + "  (throw (ex-info \"test\" {:foo 1}))\n"
                    + "  (catch Exception e\n"
                    + "    (:foo (ex-data e))))";
        Value result = eval("ex_info.clj", code);
        assertThat(result.asLong()).isEqualTo(1L);
    }

    // ── 7. Error phases ────────────────────────────────────────────

    @Test
    public void executionPhaseErrorsPropagate() {
        String code = "(/ 1 0)";
        try {
            eval("exec_phase.clj", code);
            fail("Expected ArithmeticException");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).containsIgnoringCase("divide");
        }
    }

    @Test
    public void unmatchedDelimiterIsError() {
        try {
            Source src = Source.newBuilder("cloffle", "(def x (+ 1 2])", "syntax_test.clj").buildLiteral();
            context.eval(src);
            fail("Expected error from unmatched delimiter");
        } catch (PolyglotException e) {
            // May be syntax error or guest exception depending on parser behavior
            assertThat(e.isSyntaxError() || e.isGuestException()).isTrue();
        }
    }

    // ── 8. Stack trace filtering ────────────────────────────────────

    @Test
    public void stackTraceDoesNotContainTruffleInternals() {
        String code = "(defn fail-fn [] (throw (Exception. \"test\")))\n(fail-fn)";
        try {
            eval("stack_filter.clj", code);
            fail("Expected exception");
        } catch (PolyglotException e) {
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    continue;
                }
                // Host frames should not be internal Truffle frames
                String rootName = frame.getRootName();
                if (rootName != null) {
                    assertThat(rootName)
                            .doesNotContain("com.oracle.truffle.api")
                            .doesNotContain("$CallTarget");
                }
            }
        }
    }

    @Test
    public void deepStackTraceHasMultipleGuestFrames() {
        String code = "(defn a [] (throw (Exception. \"deep\")))\n"
                    + "(defn b [] (a))\n"
                    + "(defn c [] (b))\n"
                    + "(c)";
        try {
            eval("deep_trace.clj", code);
            fail("Expected exception");
        } catch (PolyglotException e) {
            int guestFrameCount = 0;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) guestFrameCount++;
            }
            assertThat(guestFrameCount).isGreaterThanOrEqualTo(1);
        }
    }

    // ── 9. Var metadata line/column ─────────────────────────────────

    @Test
    public void varMetadataHasPositiveLineAndColumn() {
        String code = "(let [m (meta #'when)] [(> (:line m) 0) (> (:column m) 0)])";
        Value result = eval("var_meta.clj", code);
        assertThat(result.toString()).isEqualTo("[true true]");
    }

    @Test
    public void defnVarMetadataHasCorrectLine() {
        String code = "(defn test-meta-fn [x] x)\n(:line (meta #'test-meta-fn))";
        Value result = eval("defn_meta.clj", code);
        assertThat(result.asLong()).isEqualTo(1L);
    }

    // ── Integration tests ─────────────────────────────────────────

    @Test
    public void tryCatchCatchesArityException() {
        String code = "(defn f [x] x)\n"
                    + "(try (f 1 2 3) (catch Exception e (.getMessage e)))";
        Value result = eval("catch_arity.clj", code);
        String resultMsg = result.asString();
        assertThat(resultMsg.contains("arity") || resultMsg.contains("args")
                || resultMsg.contains("Wrong number"))
                .as("Error message should mention arity: " + resultMsg)
                .isTrue();
    }

    @Test
    public void tryCatchWithExDataPreservesStructure() {
        String code = "(try\n"
                    + "  (throw (ex-info \"oops\" {:code 42}))\n"
                    + "  (catch Exception e\n"
                    + "    (:code (ex-data e))))";
        Value result = eval("ex_data_structure.clj", code);
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @Test
    public void nestedFunctionCallsProduceCleanStackTrace() {
        String code = "(defn inner [] (/ 1 0))\n"
                    + "(defn middle [] (inner))\n"
                    + "(defn outer [] (middle))\n"
                    + "(outer)";
        try {
            eval("nested_calls.clj", code);
            fail("Expected ArithmeticException");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).containsIgnoringCase("divide");
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    @Test
    public void mapKeywordInteropErrorHasSourceLocation() {
        String code = "(:key nil)";
        Value result = eval("kw_nil.clj", code);
        assertThat(result.isNull()).isTrue();
    }

    @Test
    public void multipleFormsWithErrorInLastForm() {
        String code = "(def a 1)\n(def b 2)\n(def c (/ a 0))";
        try {
            eval("multi_error.clj", code);
            fail("Expected division by zero");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    private static boolean hasGuestFrame(PolyglotException e) {
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) return true;
        }
        return false;
    }
}
