package net.javacrumbs.cloffle.bytecode;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Verifies that truthy {@code *unchecked-math*} restores wrapping core arithmetic through the
 * {@code :cloffle/unchecked-op} call-site rewrite, and that the default path stays checked.
 */
public class UncheckedMathInlineTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle").allowAllAccess(true).build();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private Object eval(String expr) {
        Value result = context.eval("cloffle", expr);
        return result.isNull() ? null : result.as(Object.class);
    }

    private Object withUncheckedMath(String flag, String form) {
        return eval("(binding [*unchecked-math* " + flag + "]"
                + "  (try (eval '" + form + ")"
                + "    (catch ArithmeticException _ :threw)))");
    }

    private Object warningsFor(String form) {
        return eval("(let [w (java.io.StringWriter.)]"
                + "  (binding [*unchecked-math* :warn-on-boxed *err* w]"
                + "    (eval '" + form + "))"
                + "  (str w))");
    }

    private Object reflectionWarningsFor(String form) {
        return eval("(let [w (java.io.StringWriter.)]"
                + "  (binding [*warn-on-reflection* true *err* w]"
                + "    (eval '" + form + "))"
                + "  (str w))");
    }

    private void assertWraps(String form, long wrapped) {
        assertEquals(form + " under true", wrapped, withUncheckedMath("true", form));
        assertEquals(
                form + " under :warn-on-boxed",
                wrapped,
                withUncheckedMath(":warn-on-boxed", form));
        assertEquals(
                form + " under false",
                ":threw",
                String.valueOf(withUncheckedMath("false", form)));
    }

    @Test
    public void addWraps() {
        assertWraps("(+ Long/MAX_VALUE 1)", Long.MIN_VALUE);
    }

    @Test
    public void subtractWraps() {
        assertWraps("(- Long/MIN_VALUE 1)", Long.MAX_VALUE);
    }

    @Test
    public void multiplyWraps() {
        assertWraps("(* Long/MAX_VALUE 3)", Long.MAX_VALUE * 3);
    }

    @Test
    public void incWraps() {
        assertWraps("(inc Long/MAX_VALUE)", Long.MIN_VALUE);
    }

    @Test
    public void decWraps() {
        assertWraps("(dec Long/MIN_VALUE)", Long.MAX_VALUE);
    }

    @Test
    public void naryAddWraps() {
        assertWraps("(+ Long/MAX_VALUE 1 1)", Long.MIN_VALUE + 1);
    }

    @Test
    public void intCoercionTruncatesWhenUnchecked() {
        assertEquals(0L, ((Number) withUncheckedMath("true", "(int 4294967296)")).longValue());
        assertEquals(":threw", String.valueOf(withUncheckedMath("false", "(int 4294967296)")));
    }

    @Test
    public void castCallSitesKeepPrimitiveTypesWithoutReflectionWarning() {
        assertEquals(
                "",
                eval("(let [w (java.io.StringWriter.)]"
                        + "  (binding [*warn-on-reflection* true *err* w]"
                        + "    (eval '(do"
                        + "             (.lastIndexOf \"a\\nb\" (int \\newline))"
                        + "             (Math/scalb 1.0 (int 2))"
                        + "             (Math/abs (double 1))"
                        + "             (bit-and 15 7)"
                        + "             (Character/toLowerCase (char \\A))"
                        + "             (let [buf (char-array 4)"
                        + "                   r (java.io.StringReader. \"ab\")"
                        + "                   wr (java.io.StringWriter.)]"
                        + "               (let [n (.read r buf)]"
                        + "                 (.write wr buf 0 n))))))"
                        + "  (str w))"));
    }

    @Test
    public void defaultStaysChecked() {
        assertEquals(
                ":threw",
                String.valueOf(
                        eval("(try (+ Long/MAX_VALUE 1)"
                                + " (catch ArithmeticException _ :threw))")));
    }

    /**
     * test.check's splitmix pipeline casts an argument to long, then interleaves bit operations,
     * multiplication, and decrement. Every result must stay primitive long so the following
     * operation selects its primitive overload instead of emitting an Object/long boxed warning.
     */
    @Test
    public void bitPipelineKeepsPrimitiveLongWithoutBoxedMathWarning() {
        assertEquals(
                "",
                warningsFor(
                        "((fn [x]"
                                + " (let [x (long x)]"
                                + "   (dec (* (bit-xor (unsigned-bit-shift-right x 30) x) 5))))"
                                + " 123)"));
    }

    /** :checked-method bit ops rewrite even when {@code *unchecked-math*} is false. */
    @Test
    public void bitPipelineWithoutUncheckedMathFlagAvoidsReflectionWarning() {
        assertEquals(
                "",
                reflectionWarningsFor(
                        "((fn [x]"
                                + " (let [x (long x)]"
                                + "   (dec (* (bit-xor (unsigned-bit-shift-right x 30) x) 5))))"
                                + " 123)"));
    }

    private void assertNoReflectionWarnings(String form) {
        assertEquals("", reflectionWarningsFor(form));
    }

    @Test
    public void alwaysRewritePredicatesAndCoercions() {
        assertNoReflectionWarnings("(do (zero? 0) (pos? 1) (neg? -1) (num 3) (abs -7))");
        assertEquals(true, eval("(zero? 0)"));
        assertEquals(false, eval("(zero? 1)"));
        assertEquals(7L, ((Number) eval("(abs -7)")).longValue());
    }

    @Test
    public void alwaysRewriteQuotRemAndNaryMinMax() {
        assertNoReflectionWarnings("(quot 10 3)");
        assertNoReflectionWarnings("(rem 10 3)");
        assertNoReflectionWarnings("(min 3 1 2)");
        assertNoReflectionWarnings("(max 1 3 2)");
        assertEquals(3L, ((Number) eval("(quot 10 3)")).longValue());
        assertEquals(1L, ((Number) eval("(rem 10 3)")).longValue());
        assertEquals(1L, ((Number) eval("(min 3 1 2)")).longValue());
        assertEquals(3L, ((Number) eval("(max 1 3 2)")).longValue());
    }

    @Test
    public void alwaysRewriteUncheckedAndPromotingOps() {
        assertNoReflectionWarnings("(unchecked-add 1 2)");
        assertNoReflectionWarnings("(unchecked-inc 0)");
        assertNoReflectionWarnings("(+' 1 2 3)");
        assertEquals(3L, ((Number) eval("(unchecked-add 1 2)")).longValue());
        assertEquals(6L, ((Number) eval("(+' 1 2 3)")).longValue());
    }

    @Test
    public void alwaysRewriteDoublePredicatesAndArrayHelpers() {
        assertNoReflectionWarnings("(NaN? ##NaN)");
        assertNoReflectionWarnings("(infinite? ##Inf)");
        assertNoReflectionWarnings("(alength (int-array 4))");
        assertEquals(true, eval("(NaN? ##NaN)"));
        assertEquals(true, eval("(infinite? ##Inf)"));
        assertEquals(4L, ((Number) eval("(alength (int-array 4))")).longValue());
        assertEquals(4L, ((Number) eval("(alength (aclone (int-array 4)))")).longValue());
    }

    @Test
    public void variadicBitAndFoldMatchesDirect() {
        assertNoReflectionWarnings("(bit-and 15 7 3)");
        assertEquals(3L, ((Number) eval("(bit-and 15 7 3)")).longValue());
    }

    @Test
    public void naryDivideHostFoldOnlyWhenUncheckedMathTruthy() {
        assertEquals(
                2L,
                ((Number) withUncheckedMath("true", "(/ 8 2 2)")).longValue());
    }

    @Test
    public void intCastCheckedRejectsOutOfRangeWhenFlagFalse() {
        assertEquals(":threw", String.valueOf(withUncheckedMath("false", "(int 4294967296)")));
        assertEquals(42L, ((Number) eval("(int 42)")).longValue());
    }
}
