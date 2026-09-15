package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class FilteredEphemeralVectorSeqTest {

    @BeforeAll
    public static void initCore() {
        RT.init();
    }

    private static IFn isOdd() {
        return new AFn() {
            @Override
            public Object invoke(Object arg) {
                return RT.booleanCast(Util.equiv(Numbers.remainder(arg, 2), 1L));
            }
        };
    }

    @Test
    public void createEmptyWhenNoMatches() {
        IPersistentVector v = (IPersistentVector) RT.vector(2, 4);
        ISeq s = FilteredEphemeralVectorSeq.create(isOdd(), v, 0);
        assertSame(PersistentList.EMPTY, s);
        assertEquals(0, RT.count(s));
        assertNull(RT.first(s));
    }

    @Test
    public void createMappedEmptyWhenNoMatches() {
        IPersistentVector v = (IPersistentVector) RT.vector(2, 4);
        ISeq s = FilteredEphemeralVectorSeq.createMapped(Keyword.intern("id"), isOdd(), v, 0);
        assertSame(PersistentList.EMPTY, s);
    }

    @Test
    public void createWalksMatchingElements() {
        IPersistentVector v = (IPersistentVector) RT.vector(1, 2, 3, 4);
        FilteredEphemeralVectorSeq s = (FilteredEphemeralVectorSeq) FilteredEphemeralVectorSeq.create(isOdd(), v, 0);
        assertEquals(1L, ((Number) s.first()).longValue());
        ISeq tail = s.next();
        assertEquals(3L, ((Number) tail.first()).longValue());
        assertNull(tail.next());
        assertEquals(2, s.count());
    }

    @Test
    public void createMappedAppliesMapOnRetained() {
        IPersistentVector v = (IPersistentVector) RT.vector(
                RT.map(Keyword.intern("id"), Keyword.intern("one"), Keyword.intern("status"), Keyword.intern("ok")),
                RT.map(Keyword.intern("id"), Keyword.intern("two"), Keyword.intern("status"), Keyword.intern("fail")));
        IFn statusOk = new AFn() {
            @Override
            public Object invoke(Object row) {
                return RT.booleanCast(Util.equiv(Keyword.intern("ok"), RT.get(row, Keyword.intern("status"))));
            }
        };
        ISeq s = FilteredEphemeralVectorSeq.createMapped(Keyword.intern("id"), statusOk, v, 0);
        assertTrue(s instanceof FilteredEphemeralVectorSeq);
        assertEquals(Keyword.intern("one"), s.first());
        assertNull(s.next());
    }

    @Test
    public void countReInvokesPred() {
        AtomicInteger predCalls = new AtomicInteger();
        IFn pred = new AFn() {
            @Override
            public Object invoke(Object arg) {
                predCalls.incrementAndGet();
                return RT.T;
            }
        };
        IPersistentVector v = (IPersistentVector) RT.vector(1, 2, 3);
        FilteredEphemeralVectorSeq s = (FilteredEphemeralVectorSeq) FilteredEphemeralVectorSeq.create(pred, v, 0);
        predCalls.set(0);
        assertEquals(3, s.count());
        assertEquals(3, predCalls.get());
        predCalls.set(0);
        assertEquals(3, s.count());
        assertEquals(3, predCalls.get());
    }

    @Test
    public void reduceSupportsReduced() {
        IPersistentVector v = (IPersistentVector) RT.vector(1, 2, 3, 4, 5);
        IFn rf = new AFn() {
            @Override
            public Object invoke(Object acc, Object x) {
                long sum = ((Number) acc).longValue() + ((Number) x).longValue();
                if (sum > 3) {
                    return new Reduced(sum);
                }
                return sum;
            }
        };
        Object result = ((FilteredEphemeralVectorSeq) FilteredEphemeralVectorSeq.create(isOdd(), v, 0))
                .reduce(rf, 0L);
        assertEquals(4L, ((Number) result).longValue());
    }

    @Test
    public void materializeFilterThenMapEmpty() {
        IPersistentVector v = (IPersistentVector) RT.vector(2, 4);
        IPersistentVector out = FilteredEphemeralVectorSeq.materializeFilterThenMap(
                Keyword.intern("id"), isOdd(), v);
        assertEquals(0, out.count());
    }

    @Test
    public void materializeMapThenFilterOrder() {
        IPersistentVector v = (IPersistentVector) RT.vector(
                RT.map(Keyword.intern("id"), Keyword.intern("one"), Keyword.intern("status"), Keyword.intern("ok")),
                RT.map(Keyword.intern("id"), Keyword.intern("two"), Keyword.intern("status"), Keyword.intern("fail")));
        IFn idIsOne = new AFn() {
            @Override
            public Object invoke(Object mappedId) {
                return RT.booleanCast(Util.equiv(Keyword.intern("one"), mappedId));
            }
        };
        IPersistentVector out = FilteredEphemeralVectorSeq.materializeMapThenFilter(
                Keyword.intern("id"), idIsOne, v);
        assertEquals(1, out.count());
        assertEquals(Keyword.intern("one"), out.nth(0));
    }

    @Test
    public void equivToLazyFilterValues() {
        IPersistentVector v = (IPersistentVector) RT.vector(1, 2, 3);
        ISeq fevs = FilteredEphemeralVectorSeq.create(isOdd(), v, 0);
        ISeq lazy = (ISeq) RT.seq(RT.var("clojure.core", "filter").invoke(isOdd(), v));
        assertTrue(fevs.equiv(lazy));
        assertEquals(RT.count(lazy), RT.count(fevs));
    }
}
