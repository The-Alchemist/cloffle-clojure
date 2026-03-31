package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@code try}/{@code catch}/{@code finally}, {@code throw}, and
 * {@code monitor-enter}/{@code monitor-exit}.
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
     * {@code monitor-enter} / {@code monitor-exit} special forms (used by {@code locking} in
     * {@code clojure.core}).
     */
    @Test
    public void monitorEnterExitWithTryFinallyReturnsBody() {
        assertEquals(
                42L,
                BytecodeDslTestSupport.evalBytecode(
                        "(let* [x (Object.)] (do (monitor-enter x) (try 42 (finally (monitor-exit x)))))"));
    }

    @Test
    public void monitorEnterReentrantOnSameObject() {
        assertEquals(
                1L,
                BytecodeDslTestSupport.evalBytecode(
                        "(let* [x (Object.)] (do (monitor-enter x) (monitor-enter x) (monitor-exit x) (monitor-exit x) 1))"));
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

    /**
     * Nested {@code try}/{@code finally} under {@code monitor-enter}/{@code monitor-exit}
     * (same nesting as expanded {@code locking}).
     */
    @Test
    public void nestedTryFinallyUnderMonitorEnterExit() {
        assertEquals(
                99L,
                BytecodeDslTestSupport.evalBytecode(
                        "(let* [x (Object.)] (do (monitor-enter x) (try (try 99 (finally nil)) (finally (monitor-exit x)))))"));
    }
}
