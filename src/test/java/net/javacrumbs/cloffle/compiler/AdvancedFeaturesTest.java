package net.javacrumbs.cloffle.compiler;

import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdvancedFeaturesTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleBackend.compile(new StringReader(code), "test-adv", "test-adv.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testCase() {
        // (case 1 1 "one" 2 "two" "other")
        assertEquals("one", compileAndRun("(case 1 1 \"one\" 2 \"two\" \"other\")"));
        assertEquals("two", compileAndRun("(case 2 1 \"one\" 2 \"two\" \"other\")"));
        assertEquals("other", compileAndRun("(case 3 1 \"one\" 2 \"two\" \"other\")"));
    }

    @Test
    public void testReify() {
        // (reify clojure.lang.ISeq (first [this] 1))
        Object result = compileAndRun("(reify clojure.lang.ISeq (first [this] 1) (next [this] nil) (more [this] nil) (cons [this o] nil) (equiv [this o] false) (empty [this] nil) (count [this] 1) (seq [this] this))");
        assertTrue(result instanceof ISeq);
        assertEquals(1L, ((ISeq) result).first());
    }

    @Test
    public void testDeftype() {
        // Just defining the type should work and return the class.
        Object result = compileAndRun("(deftype MyType [a])");
        assertTrue(result instanceof Class);
        assertEquals("user.MyType", ((Class<?>)result).getName());
        
        // Now try to instantiate it in the SAME compilation unit (so same classloader)
        // Note: we must use the fully qualified name or ensure import works.
        // deftype macro does import.
        Long val = (Long) compileAndRun("(do (deftype MyType2 [a] clojure.lang.ISeq (first [this] a) (next [this] nil) (more [this] nil) (cons [this o] nil) (equiv [this o] false) (empty [this] nil) (count [this] 1) (seq [this] this)) (.first (new user.MyType2 42)))");
        assertEquals(42L, (long)val);
    }

    @Test
    public void testImport() {
        // (import 'java.util.concurrent.atomic.AtomicInteger) (new AtomicInteger 1)
        Object result = compileAndRun("(do (import 'java.util.concurrent.atomic.AtomicInteger) (new AtomicInteger 1))");
        assertTrue(result instanceof AtomicInteger);
        assertEquals(1, ((AtomicInteger)result).get());
    }

    @Test
    public void testLocking() {
        // (locking x 1)
        // We need an object.
        assertEquals(1L, compileAndRun("(let [x (Object.)] (locking x 1))"));
    }
}
