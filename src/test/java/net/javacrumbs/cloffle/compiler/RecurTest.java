package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;

public class RecurTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleBackend.compile(new StringReader(code), "test-recur", "test-recur.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testFnRecur() {
        // ( (fn [x] (if (< x 5) (recur (inc x)) x)) 0 )
        assertEquals(5L, compileAndRun("((fn [x] (if (< x 5) (recur (inc x)) x)) 0)"));
    }
}
