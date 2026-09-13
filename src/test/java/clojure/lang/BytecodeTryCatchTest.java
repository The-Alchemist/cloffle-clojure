package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code try}/{@code catch}/{@code finally}, {@code throw}, and the unsupported
 * {@code monitor-enter}/{@code monitor-exit} special forms.
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeTryCatchTest {

    /** Public static field for {@link #tryFinallyRunsWhenTryBodyThrowsAndCatchHandles}. */
    public static int mutableStatic = 0;

    @Test
    public void tryCatchReturnsTryBodyWhenNoThrow() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(try 7 (catch Throwable t 0))"));
    }

    @Test
    public void tryFinallyRunsAndReturnsBody() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(try 1 (finally nil))"));
    }

    /**
     * The bare {@code monitor-enter} / {@code monitor-exit} special forms compile but throw when
     * executed: a JVM monitor cannot be held across the return of a bytecode operation.
     * {@code clojure.core/locking} uses {@link net.javacrumbs.cloffle.CloffleMonitors} instead.
     */
    @Test
    public void monitorEnterThrowsUnsupportedAtRuntime() {
        try {
            BytecodeDslTestSupport.evalBytecode(
                    "(let* [x (Object.)] (do (monitor-enter x) (try 42 (finally (monitor-exit x)))))");
            fail("expected monitor-enter to throw");
        } catch (RuntimeException e) {
            String chain = causeMessages(e);
            assertTrue(chain, chain.contains("monitor-enter"));
            assertTrue(chain, chain.contains("locking"));
        }
    }

    @Test
    public void monitorExitThrowsUnsupportedAtRuntime() {
        try {
            BytecodeDslTestSupport.evalBytecode("(let* [x (Object.)] (do (monitor-exit x) 1))");
            fail("expected monitor-exit to throw");
        } catch (RuntimeException e) {
            String chain = causeMessages(e);
            assertTrue(chain, chain.contains("monitor-exit"));
        }
    }

    /** Guest failures reach the test wrapped in {@code ClojureException} and {@code RuntimeException}. */
    private static String causeMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void tryCatchFinallyWhenNoThrow() {
        assertEquals(5L, BytecodeDslTestSupport.evalBytecode("(try 5 (catch Throwable t 0) (finally nil))"));
    }

    @Test
    public void throwCaughtInTry() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(try (throw (new Exception \"boom\")) (catch Exception e :caught))");
        assertEquals(Keyword.intern("caught"), v);
    }

    /**
     * {@code try} with multiple {@code catch} clauses (first assignable handler wins).
     */
    @Test
    public void tryMultipleCatchClausesMostSpecificWins() {
        assertEquals(
                Keyword.intern(null, "ia"),
                BytecodeDslTestSupport.evalBytecode(
                        "(try (throw (new java.lang.IllegalArgumentException \"x\")) (catch java.lang.IllegalArgumentException e :ia) (catch java.lang.Exception e :ex))"));
        assertEquals(
                Keyword.intern(null, "ex"),
                BytecodeDslTestSupport.evalBytecode(
                        "(try (throw (new java.lang.RuntimeException \"x\")) (catch java.lang.IllegalArgumentException e :ia) (catch java.lang.Exception e :ex))"));
    }

    /**
     * {@code finally} runs when the {@code try} body throws and an outer {@code catch} handles it.
     */
    @Test
    public void tryFinallyRunsWhenTryBodyThrowsAndCatchHandles() {
        BytecodeTryCatchTest.mutableStatic = 0;
        assertNull(
                BytecodeDslTestSupport.evalBytecode(
                        "(try (throw (new Exception \"x\")) (catch Exception e nil) (finally (set! clojure.lang.BytecodeTryCatchTest/mutableStatic 7)))"));
        assertEquals(7, BytecodeTryCatchTest.mutableStatic);
    }

    /** {@code try}/{@code finally} nested inside another {@code try}/{@code finally}. */
    @Test
    public void nestedTryFinallyReturnsInnerBody() {
        assertEquals(
                99L,
                BytecodeDslTestSupport.evalBytecode(
                        "(let* [x (Object.)] (try (try 99 (finally nil)) (finally nil)))"));
    }
}
