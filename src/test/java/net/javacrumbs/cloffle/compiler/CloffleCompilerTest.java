package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class CloffleCompilerTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            // Call the Cloffle compiler entrypoint directly for unit-level behavior tests.
            return CloffleCompiler.compile(new StringReader(code), "test", "test.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSimpleAddition() {
        Object result = compileAndRun("(+ 1 2)");
        assertEquals(3L, result);
    }

    @Test
    public void testLet() {
        Object result = compileAndRun("(let [a 10 b 20] (+ a b))");
        assertEquals(30L, result);
    }

    @Test
    public void testDef() {
         compileAndRun("(def test-val 42)");
         Var v = RT.var("user", "test-val");
         assertEquals(42L, v.get());
    }

    @Test
    public void testIf() {
        assertEquals(1L, compileAndRun("(if true 1 2)"));
        assertEquals(2L, compileAndRun("(if false 1 2)"));
        assertEquals(1L, compileAndRun("(if :truthy 1 2)"));
        assertEquals(2L, compileAndRun("(if nil 1 2)"));
    }

    @Test
    public void testFn() {
        // Simple fn invocation
        assertEquals(5L, compileAndRun("((fn [x] (+ x 2)) 3)"));
    }

    @Test
    public void testLoopRecur() {
        assertEquals(10L, compileAndRun("(loop [x 0] (if (< x 10) (recur (inc x)) x))"));
    }

    @Test
    public void restoresContextClassLoaderAfterSuccess() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        compileAndRun("(+ 1 2)");
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    public void restoresContextClassLoaderAfterFailure() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            compileAndRun("(throw (RuntimeException. \"boom\"))");
        } catch (RuntimeException ignored) {
            // expected
        }
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }
}
