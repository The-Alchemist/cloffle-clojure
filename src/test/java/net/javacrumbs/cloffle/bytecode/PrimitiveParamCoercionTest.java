package net.javacrumbs.cloffle.bytecode;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verifies stock-compatible narrowing of primitive-hinted function parameters. */
public class PrimitiveParamCoercionTest {

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

    @Test
    public void longParamNarrowsDouble() {
        assertEquals(
                "java.lang.Long",
                eval("(let [f (fn [^long x] (.getName (class x)))] (f 5.5))"));
        assertEquals(5L, eval("(let [f (fn [^long x] x)] (f 5.5))"));
        assertEquals(-5L, eval("(let [f (fn [^long x] x)] (f -5.5))"));
    }

    @Test
    public void longParamNarrowsBigIntAndRatio() {
        assertEquals(5L, eval("(let [f (fn [^long x] x)] (f 5N))"));
        assertEquals(2L, eval("(let [f (fn [^long x] x)] (f 5/2))"));
    }

    @Test
    public void longParamLeavesLongAlone() {
        assertEquals(7L, eval("(let [f (fn [^long x] x)] (f 7))"));
    }

    @Test
    public void doubleParamWidensLong() {
        assertEquals(
                "java.lang.Double",
                eval("(let [f (fn [^double x] (.getName (class x)))] (f 3))"));
        assertEquals(3.0, eval("(let [f (fn [^double x] x)] (f 3))"));
    }

    @Test
    public void unhintedParamIsUntouched() {
        assertEquals(
                "java.lang.Double",
                eval("(let [f (fn [x] (.getName (class x)))] (f 5.5))"));
    }

    @Test
    public void narrowedParamStoresIntoLongArray() {
        assertEquals(
                5L,
                eval("(let [f (fn [^long x]"
                        + " (let [^longs a (long-array 1)]"
                        + " (aset a 0 x) (aget a 0)))]"
                        + " (f 5.5))"));
    }

    @Test
    public void multiArityNarrowsPerArity() {
        assertEquals(9L, eval("(let [f (fn ([^long x] x) ([x y] [x y]))] (f 9.9))"));
    }

    @Test
    public void longParamRejectsNonNumber() {
        try {
            eval("(let [f (fn [^long x] x)] (f \"nope\"))");
            fail("expected a cast failure for a non-numeric ^long argument");
        } catch (PolyglotException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cast"));
        }
    }
}
