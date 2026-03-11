package net.javacrumbs.cloffle;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import org.graalvm.polyglot.Context;
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

    private Object clojure(String expr) {
        return mikera.cljutils.Clojure.eval(expr);
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
    public void letfnMutualRecursionMatchesClojure() {
        String expr = "(letfn [(evenish [n] (if (zero? n) true (oddish (dec n)))) " +
                "(oddish [n] (if (zero? n) false (evenish (dec n))))] (evenish 10))";

        assertEquals(clojure(expr), cloffle(expr));
    }

    @Test
    public void reifyCapturingOuterLocalMatchesClojure() {
        String expr = "(let [x 41] (.call (reify java.util.concurrent.Callable (call [this] (+ x 1)))))";

        assertEquals(((Number) clojure(expr)).longValue(), ((Number) cloffle(expr)).longValue());
    }

    @Test
    public void protocolDispatchMatchesClojure() {
        String expr = "(do " +
                "(defprotocol PCompatReproOne (pcompat-repro-one [x])) " +
                "(deftype PCompatTypeReproOne [] PCompatReproOne (pcompat-repro-one [this] 42)) " +
                "(pcompat-repro-one (PCompatTypeReproOne.)))";

        assertEquals(((Number) clojure(expr)).longValue(), ((Number) cloffle(expr)).longValue());
    }

    @Test
    public void hostEvalOnlyDefmacroReturnValueMatchesClojure() {
        String expr = "(defmacro host-only-macro-repro-one [x] x)";

        Object clj = clojure(expr);
        assertNotNull(clj);
        assertTrue(clj instanceof Var);
        Object cfl = cloffle(expr);
        assertNotNull(cfl);
        assertEquals(clj.toString(), cfl.toString());
    }

    @Test
    public void hostEvalOnlyDefprotocolReturnValueMatchesClojure() {
        String expr = "(defprotocol PHostOnlyReproOne (phost-only-repro-one [x]))";

        Object clj = clojure(expr);
        assertNotNull(clj);
        Object cfl = cloffle(expr);
        assertNotNull(cfl);
        assertEquals(clj.toString(), cfl.toString());
    }

    @Test
    public void doWithOnlyHostEvalFormsReturnsLastValueLikeClojure() {
        String expr = "(do (defmacro host-only-do-m1 [x] x) (defmacro host-only-do-m2 [x] x))";

        Object clj = clojure(expr);
        assertNotNull(clj);
        assertTrue(clj instanceof Var);
        Object cfl = cloffle(expr);
        assertNotNull(cfl);
        assertEquals(clj.toString(), cfl.toString());
    }
}
