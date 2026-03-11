package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JavaInteropTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleCompiler.compile(new StringReader(code), "test-interop", "test-interop.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testStaticMethod() {
        // Math/abs is a static method
        assertEquals(10L, compileAndRun("(Math/abs -10)"));
    }

    @Test
    public void testInstanceMethod() {
        // .toUpperCase is an instance method on String
        assertEquals("HELLO", compileAndRun("(.toUpperCase \"hello\")"));
    }

    @Test
    public void testConstructor() {
        // (new String "foo")
        Object result = compileAndRun("(new String \"foo\")");
        assertEquals("foo", result);
        assertTrue(result instanceof String);
    }
    
    @Test
    public void testStaticField() {
        // Math/PI
        Object result = compileAndRun("Math/PI");
        assertEquals(Math.PI, result);
    }
}
