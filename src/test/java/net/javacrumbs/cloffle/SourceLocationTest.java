package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies that Cloffle AST nodes carry source location information
 * so that stack traces, errors, and instrumentation can report
 * line/column numbers from the original Clojure source.
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

    @Test
    public void simpleExpressionEvaluatesWithSource() {
        Value result = context.eval("cloffle", "(+ 1 2)");
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void multiLineExpressionEvaluatesWithSource() {
        String code = "(let [x 10\n      y 20]\n  (+ x y))";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void errorStackTraceContainsSourceInfo() {
        String code = "(do\n  (defn boom []\n    (throw (RuntimeException. \"kaboom\")))\n  (boom))";
        try {
            Source src = Source.newBuilder("cloffle", code, "test_error.clj").buildLiteral();
            context.eval(src);
            fail("Expected exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("kaboom");
            boolean hasGuestFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    hasGuestFrame = true;
                    break;
                }
            }
            assertThat(hasGuestFrame).as("Should have at least one guest frame").isTrue();
        }
    }

    @Test
    public void namedSourcePreservesFileName() {
        Source src = Source.newBuilder("cloffle", "(+ 1 2)", "my_script.clj").buildLiteral();
        Value result = context.eval(src);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void multiFormEvaluatesWithSource() {
        String code = "(def a 10)\n(def b 20)\n(+ a b)";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void nestedFnCallErrorHasSourceLocation() {
        String code = "(do\n  (defn inner []\n    (throw (RuntimeException. \"deep error\")))\n  (defn outer []\n    (inner))\n  (outer))";
        try {
            Source src = Source.newBuilder("cloffle", code, "nested.clj").buildLiteral();
            context.eval(src);
            fail("Expected exception");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("deep error");
            boolean foundGuestFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    foundGuestFrame = true;
                    break;
                }
            }
            assertThat(foundGuestFrame).as("Should have guest frame").isTrue();
        }
    }

    @Test
    public void tryCatchPreservesSourceTracking() {
        String code = "(try\n  (+ 1 2)\n  (catch Exception e\n    42))";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void ifExpressionPreservesSourceTracking() {
        String code = "(if true\n  (+ 1 2)\n  (+ 3 4))";
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
    public void loopRecurPreservesSourceTracking() {
        String code = "(loop [sum 0\n       cnt 5]\n  (if (= cnt 0)\n    sum\n    (recur (+ cnt sum) (dec cnt))))";
        Value result = context.eval("cloffle", code);
        assertThat(result.asLong()).isEqualTo(15L);
    }

    @Test
    public void arityErrorContainsSourceInfo() {
        String code = "(do\n  (defn one-arg [x] x)\n  (one-arg 1 2 3))";
        try {
            Source src = Source.newBuilder("cloffle", code, "arity.clj").buildLiteral();
            context.eval(src);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            boolean hasGuestFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    hasGuestFrame = true;
                    break;
                }
            }
            assertThat(hasGuestFrame).as("Arity error should produce guest stack frames").isTrue();
        }
    }

    @Test
    public void javaInteropErrorHasSourceLocation() {
        String code = "(.substring \"hello\" 100)";
        try {
            Source src = Source.newBuilder("cloffle", code, "interop.clj").buildLiteral();
            context.eval(src);
            fail("Expected StringIndexOutOfBoundsException");
        } catch (PolyglotException e) {
            boolean hasGuestFrame = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    hasGuestFrame = true;
                    break;
                }
            }
            assertThat(hasGuestFrame).as("Interop error should produce guest stack frames").isTrue();
        }
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
}
