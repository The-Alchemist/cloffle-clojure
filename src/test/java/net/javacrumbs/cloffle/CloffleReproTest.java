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
         // Clojure seqs are list-like from Java/interop perspective.
         Value val = context.eval("cloffle", "(range)");
         assertTrue("LazySeq should expose array/list interop", val.hasArrayElements());
         // Ensure basic element access works and does not force full realization.
         assertEquals(0L, val.getArrayElement(0).asLong());
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
    public void testUnrolledGetInAndAssocIn() {
        assertEquals("Alice", cloffle("(get-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name])"));
        assertEquals(42L, cloffle("(get-in {:a {:b {:c 42}}} [:a :b :c])"));
        assertNull(cloffle("(get-in {:a {:b 1}} [:a :missing])"));
        assertNull(cloffle("(get-in nil [:a :b])"));

        assertEquals("Bob", cloffle("(get-in (assoc-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name] \"Bob\") [:user :profile :name])"));
        assertEquals(99L, cloffle("(get-in (assoc-in {} [:a :b :c] 99) [:a :b :c])"));
        assertEquals(100L, cloffle("(get-in (assoc-in nil [:x :y] 100) [:x :y])"));
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
