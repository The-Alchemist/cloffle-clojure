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
}
