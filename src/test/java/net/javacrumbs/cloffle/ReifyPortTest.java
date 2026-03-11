package net.javacrumbs.cloffle;

import clojure.lang.ISeq;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Tests for reify behavior — before and after porting from Compiler bytecode
 * to Cloffle/Truffle APIs.
 *
 * <p>These tests document the current (pre-port) behavior and define the
 * specification for the post-port implementation. All tests should pass
 * in both states.
 */
public class ReifyPortTest {

    private Context context;

    @BeforeClass
    public static void initClojure() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

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
        if (result == null || result.isNull()) return null;
        if (result.isBoolean()) return result.asBoolean();
        if (result.isString()) return result.asString();
        return result.as(Object.class);
    }

    private Object clojure(String expr) {
        return mikera.cljutils.Clojure.eval(expr);
    }

    // -------------------------------------------------------------------------
    // BEFORE: Current implementation (Compiler bytecode-generated classes)
    // AFTER:  Truffle-API-based implementation (Proxy or equivalent)
    // All tests define the same contract — they must pass in both cases.
    // -------------------------------------------------------------------------

    // --- Java interface implementations ---

    @Test
    public void reifyRunnable_returnsFromRun() {
        // Runnable.run() returns void; Clojure returns nil for side-effect-only
        Object result = cloffle("(let [r (reify Runnable (run [this] 42))] (.run r))");
        assertThat(result).isNull();
    }

    @Test
    public void reifyCallable_returnsValue() {
        Object result = cloffle("(.call (reify java.util.concurrent.Callable (call [this] 99)))");
        assertThat(((Number) result).intValue()).isEqualTo(99);
    }

    @Test
    public void reifyCapturingOuterLocal_matchesClojure() {
        String expr = "(let [x 41] (.call (reify java.util.concurrent.Callable (call [this] (+ x 1)))))";
        assertEquals(((Number) clojure(expr)).longValue(), ((Number) cloffle(expr)).longValue());
    }

    @Test
    public void reifyCapturingMultipleOuterLocals_matchesClojure() {
        String expr = "(let [x 40 y 2] (.call (reify java.util.concurrent.Callable (call [this] (+ x y)))))";
        assertEquals(((Number) clojure(expr)).longValue(), ((Number) cloffle(expr)).longValue());
    }

    @Test
    public void reifyInstanceOf_javaInterface() {
        Object result = cloffle("(reify java.util.concurrent.Callable (call [this] 42))");
        assertThat(result).isInstanceOf(Callable.class);
        assertThat(result).isInstanceOf(Object.class);
    }

    @Test
    public void reifyCallableFromJava() throws Exception {
        Object reified = cloffle("(reify java.util.concurrent.Callable (call [this] 123))");
        Callable<?> c = (Callable<?>) reified;
        assertEquals(123, ((Number) c.call()).intValue());
    }

    @Test
    public void reifyRunnableFromJava() throws Exception {
        // Pass reify to Java FutureTask and run it — verifies Java interop
        Object reified = cloffle("(reify Runnable (run [this] 42))");
        assertThat(reified).isInstanceOf(Runnable.class);
        Runnable r = (Runnable) reified;
        r.run(); // no exception
        FutureTask<?> task = new FutureTask<>(r, null);
        task.run();
        assertThat(task.get()).isNull();
    }

    // --- Clojure interface (ISeq) ---

    @Test
    public void reifyISeq_firstAndNext() {
        Object result = cloffle(
                "(reify clojure.lang.ISeq (first [this] 1) (next [this] nil) (more [this] nil) " +
                "(cons [this o] nil) (equiv [this o] false) (empty [this] nil) (count [this] 1) (seq [this] this))");
        assertThat(result).isInstanceOf(ISeq.class);
        assertEquals(1L, ((ISeq) result).first());
        assertThat(((ISeq) result).next()).isNull();
    }

    // --- Direct CloffleCompiler path (no polyglot Context) ---

    @Test
    public void reifyViaCloffleCompiler_directPath() throws Exception {
        String code = "(.call (reify java.util.concurrent.Callable (call [this] 77)))";
        Object result = net.javacrumbs.cloffle.compiler.CloffleCompiler.compile(
                new StringReader(code), "reify-port-test", "reify_port_test.clj");
        assertNotNull(result);
        assertEquals(77, ((Number) result).intValue());
    }

    @Test
    public void reifyWithClosedOversViaCloffleCompiler() throws Exception {
        String code = "(let [a 10 b 5] (.call (reify java.util.concurrent.Callable (call [this] (+ a b)))))";
        Object result = net.javacrumbs.cloffle.compiler.CloffleCompiler.compile(
                new StringReader(code), "reify-port-test", "reify_port_test.clj");
        assertNotNull(result);
        assertEquals(15, ((Number) result).intValue());
    }

    // --- Multiple interfaces ---

    @Test
    public void reifyMultipleInterfaces() {
        Object result = cloffle(
                "(let [r (reify Runnable java.util.concurrent.Callable " +
                "         (run [this] nil) " +
                "         (call [this] 42))] " +
                "  (.run r) (.call r))");
        assertEquals(42, ((Number) result).intValue());
    }

    // --- Method with arguments ---

    @Test
    public void reifyWithMethodArgs() {
        // Comparable.compareTo
        Object result = cloffle(
                "(.compareTo (reify java.lang.Comparable (compareTo [this o] 7)) \"ignored\")");
        assertEquals(7, ((Number) result).intValue());
    }

    // --- Edge cases ---

    @Test
    public void reifyWithMeta_preservesMetadata() {
        Object result = cloffle(
                "(let [r (with-meta (reify Object (toString [this] \"x\")) {:tag :foo})] " +
                "  (:tag (meta r)))");
        assertNotNull(result);
        assertThat(result.toString()).isEqualTo("foo");
    }

    @Test
    public void reifyReturnedAndStored() {
        Object result = cloffle(
                "(let [f (fn [] (reify java.util.concurrent.Callable (call [this] 99)))] " +
                "  (.call (f)))");
        assertEquals(99, ((Number) result).intValue());
    }
}
