package net.javacrumbs.cloffle;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CloffleReproTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private Object cloffle(String expr) {
        Value result = context.eval("cloffle", expr);
        if (result.isNull()) return null;
        if (result.isBoolean()) return result.asBoolean();
        if (result.isString()) return result.asString();
        // Return raw object for numbers to check type
        return result.as(Object.class);
    }

    @Test
    public void testNativeCallArguments() {
        IFn checkArgs = new clojure.lang.AFn() {
            @Override
            public Object invoke(Object arg1, Object arg2) {
                if (arg1 instanceof com.oracle.truffle.api.frame.MaterializedFrame ||
                    arg1 instanceof com.oracle.truffle.api.frame.VirtualFrame) {
                    throw new RuntimeException("Received Frame as first argument!");
                }
                return "OK";
            }
        };

        RT.var("user", "check-args", checkArgs);

        try {
            Object result = cloffle("(user/check-args 1 2)");
            assertEquals("OK", result);
        } finally {
            RT.var("user", "check-args").unbindRoot();
        }
    }

    @Test
    public void testInstanceCheckNil() {
        Object result = cloffle("(instance? Object nil)");
        assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void testLazySeqRealization() {
        Value val = context.eval("cloffle", "(range)");
        assertFalse("Infinite seqs must not advertise array size", val.hasArrayElements());
        assertTrue("Uncounted seqs expose iterator interop", val.hasIterator());
        Value it = val.getIterator();
        assertTrue(it.hasIteratorNextElement());
        assertEquals(0L, it.getIteratorNextElement().asLong());
    }

    @Test
    public void countedSeqsKeepArrayInterop() {
        Value range3 = context.eval("cloffle", "(range 3)");
        assertTrue(range3.hasArrayElements());
        assertEquals(3L, range3.getArraySize());
        assertEquals(0L, range3.getArrayElement(0).asLong());

        Value list = context.eval("cloffle", "(list 1 2)");
        assertTrue(list.hasArrayElements());
        assertEquals(2L, list.getArraySize());
        assertEquals(1L, list.getArrayElement(0).asLong());
    }

    @Test
    public void uncountedSeqsAreIteratorOnly() {
        Value cycle = context.eval("cloffle", "(cycle [1])");
        assertFalse(cycle.hasArrayElements());
        assertTrue(cycle.hasIterator());
        Value cycleIt = cycle.getIterator();
        assertEquals(1L, cycleIt.getIteratorNextElement().asLong());

        Value lazy = context.eval("cloffle", "(lazy-seq (cons 7 (lazy-seq nil)))");
        assertFalse(lazy.hasArrayElements());
        assertTrue(lazy.hasIterator());
        assertEquals(7L, lazy.getIterator().getIteratorNextElement().asLong());
    }

    @Test
    public void testIntWidening() {
        // Issue: Int vs Long Widening
        Value val = context.eval("cloffle", "Integer/MAX_VALUE");
        Object raw = val.as(Object.class);
        assertEquals(Integer.class, raw.getClass());
    }

    @Test
    public void testDefMetadata() {
        cloffle("(do (in-ns 'user) (def ^:dynamic *my-dynamic-var* 1))");
        Var v = RT.var("user", "*my-dynamic-var*");
        assertTrue("Var should be dynamic", v.isDynamic());

        Object result = cloffle("(binding [user/*my-dynamic-var* 2] user/*my-dynamic-var*)");
        // binding returns result of body. *my-dynamic-var* is 2 (Long).
        assertEquals(2L, ((Number)result).longValue());
    }

    @Test
    public void polyglotBoundaryPreservesThrownRuntimeExceptionDetails() {
        try {
            context.eval("cloffle", "(throw (RuntimeException. \"boom\"))");
            fail("Expected PolyglotException");
        } catch (PolyglotException e) {
            assertTrue(e.isGuestException());
            String detail = polyglotExceptionDetail(e);
            assertTrue("detail: " + detail, detail.contains("boom"));
        }
    }

    @Test
    public void polyglotBoundaryPreservesInteropExceptionDetails() {
        try {
            context.eval("cloffle", "(.substring \"hello\" 100)");
            fail("Expected PolyglotException");
        } catch (PolyglotException e) {
            assertTrue(e.isGuestException());
            String detail = polyglotExceptionDetail(e);
            assertTrue("detail: " + detail,
                    detail.contains("StringIndexOutOfBoundsException")
                            || detail.contains("out of bounds")
                            || detail.contains("Range ["));
        }
    }

    @Test
    public void testKeywordLookupsAndDefaults() {
        assertEquals(2L, cloffle("(:b {:a 1 :b 2})"));
        assertEquals("default", cloffle("(:missing {:a 1} \"default\")"));
        assertEquals(2L, cloffle("(get {:a 1 :b 2} :b)"));
        assertEquals("default", cloffle("(get {:a 1} :missing \"default\")"));
        assertEquals("not-found", cloffle("(get-in {:a {:b 1}} [:a :missing] \"not-found\")"));
        assertEquals("not-found", cloffle("(get-in nil [:a :b] \"not-found\")"));
        assertEquals(1L, cloffle("(get-in {:a 1} [:a] \"not-found\")"));
    }


    @Test
    public void testDissocAndLargerMapLiterals() {
        assertEquals(2, ((Number) cloffle("(count (dissoc {:a 1 :b 2 :c 3} :b))")).intValue());
        assertEquals(1, ((Number) cloffle("(:a (dissoc {:a 1 :b 2 :c 3} :b))")).intValue());
        assertNull(cloffle("(:b (dissoc {:a 1 :b 2 :c 3} :b))"));
        assertEquals(0, ((Number) cloffle("(count (dissoc {:a 1 :b 2 :c 3} :a :b :c))")).intValue());
        assertEquals(5, ((Number) cloffle("(:e {:a 1 :b 2 :c 3 :d 4 :e 5})")).intValue());
        assertEquals(6, ((Number) cloffle("(:f {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6})")).intValue());
        assertEquals(8, ((Number) cloffle("(:h {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8})")).intValue());
        assertEquals(5, ((Number) cloffle("(count {:a 1 :b 2 :c 3 :d 4 :e 5})")).intValue());
    }

    @Test
    public void testUnrolledGetInAndAssocIn() {
        assertEquals("Alice", cloffle("(get-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name])"));
        assertEquals(42L, cloffle("(get-in {:a {:b {:c 42}}} [:a :b :c])"));
        assertNull(cloffle("(get-in {:a {:b 1}} [:a :missing])"));
        assertNull(cloffle("(get-in nil [:a :b])"));

        assertEquals("Bob", cloffle("(get-in (assoc-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name] \"Bob\") [:user :profile :name])"));
        assertEquals(99L, cloffle("(get-in (assoc-in {} [:a :b :c] 99) [:a :b :c])"));
        assertEquals(100L, cloffle("(get-in (assoc-in nil [:x :y] 100) [:x :y])"));

        assertEquals("nf", cloffle("(get-in {:a 1} [:missing] \"nf\")"));
        assertEquals(Boolean.TRUE, cloffle("(= {:a 1} (get-in {:a 1} []))"));
        assertEquals(42L, cloffle("(let [x :b] (get-in {:a {:b 42}} [:a x]))"));
        assertEquals(30L, cloffle("(:c (:b (:a (update-in {:a {:b {:c 10}}} [:a :b :c] * 3))))"));
        assertEquals(10L, cloffle("(let [ks [:a :b]] (get-in {:a {:b 10}} ks))"));
    }

    @Test
    public void testTupleDestructuring() {
        assertEquals(6L, ((Number) cloffle("(let [[x y z] [1 2 3]] (+ x (+ y z)))")).longValue());
        assertEquals(30L, ((Number) cloffle("(let [[a b & more] [10 20 30 40]] (+ a b))")).longValue());
        assertEquals(2, ((Number) cloffle("(let [[a b & more] [10 20 30 40]] (count more))")).intValue());
        assertEquals(2L, ((Number) cloffle("(let [[first-elem & rest-elems] (conj [1 2] 3)] (first rest-elems))")).longValue());
        assertEquals(3L, ((Number) cloffle("(let [[first-elem & rest-elems] (conj [1 2] 3)] (peek (vec rest-elems)))")).longValue());
    }

    /**
     * GraalVM does not always repeat the guest {@link Throwable} class/message in
     * {@link PolyglotException#getMessage()}; include host/guest exception details when present.
     */
    private static String polyglotExceptionDetail(PolyglotException e) {
        StringBuilder sb = new StringBuilder();
        String m = e.getMessage();
        if (m != null) {
            sb.append(m);
        }
        try {
            Value go = e.getGuestObject();
            if (go != null && !go.isNull() && go.isHostObject()) {
                Object ho = go.asHostObject();
                if (ho instanceof Throwable t) {
                    sb.append(' ').append(t.getClass().getName()).append(' ')
                            .append(String.valueOf(t.getMessage()));
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (e.isHostException()) {
            Throwable h = e.asHostException();
            sb.append(' ').append(h.getClass().getName()).append(' ')
                    .append(String.valueOf(h.getMessage()));
        }
        return sb.toString();
    }
}
