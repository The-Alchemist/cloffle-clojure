package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Reproducer for "The constant parameter must not be null" error
 * when a {@code case} expression tests against {@code nil}.
 * <p>
 * The {@code case} macro produces a {@code ConstantExpr(null)} for the
 * {@code nil} test value. {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode}
 * must emit {@code emitLoadNull()} instead of {@code emitLoadConstant(null)}.
 */
public class CaseNilConstantTest {

    /**
     * Minimal reproducer: {@code case} with {@code nil} as a test value.
     * Models the pattern from {@code clojure.spec.alpha/accept-nil?}.
     */
    @Test
    public void caseWithNilTestValue() {
        String code = "(fn* [x] (case* x 0 0 :default {0 [nil :was-nil]} :compact :hash-equiv nil))";
        Object fn = BytecodeDslTestSupport.evalBytecode(code);
        // Invoke with nil arg — should match the nil case
        Object result = ((IFn) fn).invoke(null);
        assertEquals(":was-nil", RT.printString(result));
    }

    /**
     * Same pattern but the discriminant is a non-nil value that falls through to default.
     */
    @Test
    public void caseWithNilTestValueDefaultPath() {
        String code = "(fn* [x] (case* x 0 0 :default {0 [nil :was-nil]} :compact :hash-equiv nil))";
        Object fn = BytecodeDslTestSupport.evalBytecode(code);
        Object result = ((IFn) fn).invoke("other");
        assertEquals(":default", RT.printString(result));
    }

    /**
     * {@code case} with both {@code nil} and keyword test values, modelling
     * the {@code accept-nil?} function from {@code clojure.spec.alpha}.
     */
    @Test
    public void caseWithNilAndKeywordTests() {
        // case* with hash-identity: nil maps to :was-nil, :ok maps to :was-ok
        // nil hashCode = 0, :ok hashCode = some int
        // We use the higher-level case macro form to get realistic CaseExpr shape
        String code =
                "(let* [f (fn* [op]"
                + "         (case* op 0 0 :unknown"
                + "           {0 [nil :got-nil]}"
                + "           :compact :hash-equiv nil))]"
                + "  [(f nil) (f :something)])";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("[:got-nil :unknown]", RT.printString(result));
    }

    /**
     * AST parity: same case expression returns identical results via AST and bytecode.
     */
    @Test
    public void caseNilAstParity() {
        String code = "(fn* [x] (case* x 0 0 :default {0 [nil :was-nil]} :compact :hash-equiv nil))";

        Object fnAst = BytecodeDslTestSupport.evalAst(code);
        Object fnBc = BytecodeDslTestSupport.evalBytecode(code);

        assertEquals(
                RT.printString(((IFn) fnAst).invoke(null)),
                RT.printString(((IFn) fnBc).invoke(null)));
        assertEquals(
                RT.printString(((IFn) fnAst).invoke("other")),
                RT.printString(((IFn) fnBc).invoke("other")));
    }
}
