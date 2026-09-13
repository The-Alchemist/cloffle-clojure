package net.javacrumbs.cloffle;

import clojure.lang.BigInt;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.LazySeq;
import clojure.lang.RT;
import clojure.lang.Ratio;
import clojure.lang.Symbol;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import net.javacrumbs.cloffle.nodes.ClojureScope;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Interop contract for Clojure values shown in debugger scopes and nested expansion.
 */
public class DebuggerValueInteropTest {

    private Engine engine;
    private Context context;
    private InteropLibrary interop;

    @Before
    public void setUp() {
        RT.init();
        engine = Engine.create();
        context = CloffleEvalTestSupport.newDebuggerContext(engine, "interop");
        interop = InteropLibrary.getUncached();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    public void keywordIsStringInterop() throws UnsupportedMessageException {
        Keyword kw = Keyword.intern("status", "ok");
        assertTrue(InteropLibrary.isValidValue(kw));
        assertTrue(interop.isString(kw));
        assertEquals(":status/ok", interop.asString(kw));
        assertEquals(":status/ok", interop.toDisplayString(kw, false));
        assertFalse(interop.isExecutable(kw));
    }

    @Test
    public void symbolIsStringNotExecutable() throws UnsupportedMessageException {
        Symbol sym = Symbol.intern("my.ns", "name");
        assertTrue(InteropLibrary.isValidValue(sym));
        assertTrue(interop.isString(sym));
        assertEquals("my.ns/name", interop.asString(sym));
        assertFalse(interop.isExecutable(sym));
    }

    @Test
    public void bigIntAndRatioAreNumbers() throws UnsupportedMessageException {
        BigInt bi = BigInt.valueOf(42L);
        assertTrue(interop.isNumber(bi));
        assertTrue(interop.fitsInLong(bi));
        assertEquals(42L, interop.asLong(bi));

        Ratio ratio = new Ratio(BigInteger.ONE, BigInteger.valueOf(2));
        assertTrue(interop.isNumber(ratio));
        assertTrue(interop.fitsInDouble(ratio));
        assertEquals(0.5, interop.asDouble(ratio), 0.0);
        assertEquals("1/2", interop.toDisplayString(ratio, false));
    }

    @Test
    public void mapHashExpansionWrapsNestedBigInt() throws Exception {
        Keyword key = Keyword.intern("n");
        IPersistentMap map = RT.map(key, BigInt.valueOf(7L));
        assertTrue(interop.hasHashEntries(map));
        Object nested = interop.readHashValue(map, key);
        assertTrue(InteropLibrary.isValidValue(nested));
        assertTrue(interop.isNumber(nested));
        assertEquals(7L, interop.asLong(nested));
    }

    @Test
    public void shapeMapHashExpansion() throws Exception {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");
        IPersistentMap map = RT.map(a, 1L, b, 2L);
        assertTrue(interop.hasHashEntries(map));
        assertEquals(2L, interop.getHashSize(map));
        assertTrue(interop.isHashEntryReadable(map, a));
        assertEquals(1L, interop.readHashValue(map, a));
    }

    @Test
    public void consSeqUsesIteratorNotArray() throws Exception {
        Object list = new clojure.lang.Cons(1L, new clojure.lang.Cons(2L, null));
        assertFalse(interop.hasArrayElements(list));
        assertTrue(interop.hasIterator(list));
    }

    @Test
    public void lazySeqHasIterator() throws Exception {
        clojure.lang.IFn thunk = new clojure.lang.AFn() {
            @Override
            public Object invoke() {
                return new clojure.lang.Cons(1L, null);
            }
        };
        Object lazy = new LazySeq(thunk);
        assertTrue(interop.hasIterator(lazy));
    }

    @Test
    public void nilScopeValue() throws UnsupportedMessageException {
        ClojureScope.NullValue nv = ClojureScope.NullValue.INSTANCE;
        assertTrue(interop.isNull(nv));
        assertEquals("nil", interop.toDisplayString(nv, false));
    }
}
