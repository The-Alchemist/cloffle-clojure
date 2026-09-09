package net.javacrumbs.cloffle.compiler;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.IPersistentSet;
import clojure.lang.Namespace;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DataStructureTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleCompiler.compile(new StringReader(code), "test-data", "test-data.clj");
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
    public void testKeywordMapLiterals1To4() {
        Object m1 = compileAndRun("{:a 1}");
        assertTrue(m1 instanceof PersistentShapeMap);
        assertEquals(1, ((IPersistentMap) m1).count());
        assertEquals(1L, ((IPersistentMap) m1).valAt(RT.keyword(null, "a")));

        Object m2 = compileAndRun("{:a 1 :b 2}");
        assertTrue(m2 instanceof PersistentShapeMap);
        assertEquals(2, ((IPersistentMap) m2).count());

        Object m3 = compileAndRun("{:c 3 :a 1 :b 2}");
        assertTrue(m3 instanceof PersistentShapeMap);
        IPersistentMap map3 = (IPersistentMap) m3;
        assertEquals(3, map3.count());
        assertEquals(1L, map3.valAt(RT.keyword(null, "a")));
        assertEquals(2L, map3.valAt(RT.keyword(null, "b")));
        assertEquals(3L, map3.valAt(RT.keyword(null, "c")));

        Object m4 = compileAndRun("{:d 4 :b 2 :c 3 :a 1}");
        assertTrue(m4 instanceof PersistentShapeMap);
        IPersistentMap map4 = (IPersistentMap) m4;
        assertEquals(4, map4.count());
        assertEquals(1L, map4.valAt(RT.keyword(null, "a")));
        assertEquals(4L, map4.valAt(RT.keyword(null, "d")));
    }

    @Test
    public void testKeywordMapLiterals9To16WithRuntimeValue() {
        Object m9 = compileAndRun("(let [x 90] {:k8 x :k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7})");
        assertTrue(m9 instanceof PersistentShapeMap16);
        IPersistentMap map9 = (IPersistentMap) m9;
        assertEquals(9, map9.count());
        assertEquals(0L, map9.valAt(RT.keyword(null, "k0")));
        assertEquals(90L, map9.valAt(RT.keyword(null, "k8")));

        Object m10 = compileAndRun("(let [x 100] {:k9 x :k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8})");
        assertTrue(m10 instanceof PersistentShapeMap16);
        assertEquals(10, ((IPersistentMap) m10).count());
        assertEquals(100L, ((IPersistentMap) m10).valAt(RT.keyword(null, "k9")));

        Object m14 = compileAndRun(
                "(let [x 140] {:k13 x :k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12})");
        assertTrue(m14 instanceof PersistentShapeMap16);
        IPersistentMap map14 = (IPersistentMap) m14;
        assertEquals(14, map14.count());
        assertEquals(140L, map14.valAt(RT.keyword(null, "k13")));
        assertEquals(12L, map14.valAt(RT.keyword(null, "k12")));

        Object m16 = compileAndRun(
                "(let [x 160] {:k15 x :k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14})");
        assertTrue(m16 instanceof PersistentShapeMap16);
        IPersistentMap map16 = (IPersistentMap) m16;
        assertEquals(16, map16.count());
        assertEquals(160L, map16.valAt(RT.keyword(null, "k15")));
        assertEquals(0L, map16.valAt(RT.keyword(null, "k0")));
    }

    @Test
    public void testMapLiteralDuplicateKeywordKeys() {
        try {
            compileAndRun("{(keyword \"a\") 1 (keyword \"a\") 2}");
            fail("expected duplicate key");
        } catch (RuntimeException e) {
            Throwable t = e;
            boolean found = false;
            while (t != null) {
                if (t.getMessage() != null && t.getMessage().contains("Duplicate key")) {
                    found = true;
                    break;
                }
                t = t.getCause();
            }
            assertTrue("expected Duplicate key in exception chain", found);
        }
    }

    @Test
    public void testMapLiteralNonKeywordFallback() {
        Object result = compileAndRun("{\"str\" 1 :b 2}");
        assertTrue(result instanceof IPersistentMap);
        assertFalse(result instanceof PersistentShapeMap);
        IPersistentMap m = (IPersistentMap) result;
        assertEquals(2, m.count());
        assertEquals(1L, m.valAt("str"));
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
