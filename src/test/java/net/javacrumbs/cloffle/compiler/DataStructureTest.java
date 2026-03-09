package net.javacrumbs.cloffle.compiler;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.IPersistentSet;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DataStructureTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleBackend.compile(new StringReader(code), "test-data", "test-data.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testVector() {
        Object result = compileAndRun("[1 2 3]");
        assertTrue(result instanceof IPersistentVector);
        IPersistentVector v = (IPersistentVector) result;
        assertEquals(3, v.count());
        assertEquals(1L, v.nth(0));
        assertEquals(2L, v.nth(1));
        assertEquals(3L, v.nth(2));
    }

    @Test
    public void testMap() {
        Object result = compileAndRun("{:a 1 :b 2}");
        assertTrue(result instanceof IPersistentMap);
        IPersistentMap m = (IPersistentMap) result;
        assertEquals(2, m.count());
        assertEquals(1L, m.valAt(RT.keyword(null, "a")));
        assertEquals(2L, m.valAt(RT.keyword(null, "b")));
    }

    @Test
    public void testSet() {
        Object result = compileAndRun("#{1 2 3}");
        assertTrue(result instanceof IPersistentSet);
        IPersistentSet s = (IPersistentSet) result;
        assertEquals(3, s.count());
        assertTrue(s.contains(1L));
        assertTrue(s.contains(2L));
        assertTrue(s.contains(3L));
    }
    
    @Test
    public void testNested() {
        Object result = compileAndRun("{:a [1 2] :b #{3}}");
        assertTrue(result instanceof IPersistentMap);
        IPersistentMap m = (IPersistentMap) result;
        assertTrue(m.valAt(RT.keyword(null, "a")) instanceof IPersistentVector);
        assertTrue(m.valAt(RT.keyword(null, "b")) instanceof IPersistentSet);
    }
}
