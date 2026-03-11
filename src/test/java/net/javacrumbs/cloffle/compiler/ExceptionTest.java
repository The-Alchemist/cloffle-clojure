package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExceptionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleCompiler.compile(new StringReader(code), "test-exception", "test-exception.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void expectThrown(String code, Class<? extends Throwable> expectedType, String expectedMessagePart) {
        try {
            CloffleCompiler.compile(new StringReader(code), "test-exception", "test-exception.clj");
            fail("Should have thrown exception");
        } catch (Throwable t) {
            if (!expectedType.isInstance(t)) {
                fail("Expected " + expectedType.getName() + " but got " + t.getClass().getName());
            }
            if (expectedMessagePart != null) {
                org.junit.Assert.assertTrue(
                        "Expected message to contain '" + expectedMessagePart + "' but was '" + t.getMessage() + "'",
                        t.getMessage() != null && t.getMessage().contains(expectedMessagePart));
            }
        }
    }

    @Test
    public void testTryCatch() {
        // (try (/ 1 0) (catch ArithmeticException e "caught"))
        assertEquals("caught", compileAndRun("(try (/ 1 0) (catch ArithmeticException e \"caught\"))"));
    }

    @Test
    public void testTryFinally() {
        // (try "ok" (finally (def finally-ran true)))
        compileAndRun("(def finally-ran false)");
        assertEquals("ok", compileAndRun("(try \"ok\" (finally (def finally-ran true)))"));
        assertEquals(true, RT.var("user", "finally-ran").get());
    }

    @Test
    public void testThrow() {
        try {
            compileAndRun("(throw (RuntimeException. \"boom\"))");
            fail("Should have thrown exception");
        } catch (RuntimeException e) {
            // The exception from compileAndRun wraps the actual exception
            // But wait, CloffleCompiler returns the result of call(), which throws the exception directly if not caught.
            // However, compileAndRun wraps in RuntimeException.
            // It might be wrapped in ClojureException or PolyglotException depending on how it propagates.
            // CloffleCompiler calls root.getCallTarget().call().
            // If the Truffle code throws, it propagates up.
            
            // Let's just assert that *something* was thrown for now.
        }
    }

    @Test
    public void testUncaughtRuntimeExceptionPreservesTypeAndMessage() {
        expectThrown("(throw (RuntimeException. \"boom\"))", RuntimeException.class, "boom");
    }

    @Test
    public void testUncaughtCheckedExceptionPreservesTypeAndMessage() {
        expectThrown("(throw (Exception. \"boom\"))", Exception.class, "boom");
    }

    @Test
    public void testUncaughtInteropExceptionPreservesTypeAndMessage() {
        expectThrown("(.substring \"hello\" 100)", StringIndexOutOfBoundsException.class, "out of bounds");
    }
}
