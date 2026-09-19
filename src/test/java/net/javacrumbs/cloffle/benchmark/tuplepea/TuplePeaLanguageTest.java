package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.PersistentTuple;
import clojure.lang.PersistentTuple.PersistentTuple2;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TuplePeaLanguageTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder(TuplePeaLanguage.ID).allowAllAccess(true).build();
        context.enter();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    private CallTarget parse(String program) throws Exception {
        context.parse(Source.newBuilder(TuplePeaLanguage.ID, program, program + ".pea").build());
        return TuplePeaLanguage.takeLastParsed();
    }

    private int callInt(String program, int a, int b) throws Exception {
        return (Integer) parse(program).call(a, b);
    }

    @Test
    public void inMethodConsumesTuple() throws Exception {
        assertEquals(5, callInt("inMethod", 2, 3));
        assertEquals(5, callInt("viaPrivateHelper", 2, 3));
        assertEquals(5, callInt("viaOutOfLineHelper", 2, 3));
        assertEquals(5, callInt("viaTruffleBoundaryHelper", 2, 3));
    }

    @Test
    public void materializedReturnsTuple2() throws Exception {
        Object result = parse("materialized").call(2, 3);
        assertTrue(result instanceof PersistentTuple2);
        PersistentTuple2 t = (PersistentTuple2) result;
        assertEquals(2, t.nth(0));
        assertEquals(3, t.nth(1));
    }

    @Test
    public void twoTupleSum() throws Exception {
        assertEquals(10, callInt("twoTuplesSumThenConsumeNth", 2, 3));
        PersistentTuple2 t = (PersistentTuple2) parse("twoTuplesSumMaterialized").call(2, 3);
        assertEquals(PersistentTuple.create(5, 5), t);
    }

    @Test
    public void bakedBranch() throws Exception {
        assertEquals(5, callInt("branchLazyTupleCreate:1", 2, 3));
        assertEquals(5, callInt("branchLazyTupleCreate:0", 2, 3));
        assertEquals(5, callInt("branchPickOneTuple:1", 2, 3));
        assertEquals(5, callInt("branchPickOneTuple:0", 2, 3));
        assertEquals(10, callInt("branchSumTupleOrDirectSlots:1", 2, 3));
        assertEquals(10, callInt("branchSumTupleOrDirectSlots:0", 2, 3));
    }

    @Test
    public void countedAndWhile() throws Exception {
        int expectedConsume16 = 0;
        for (int i = 0; i < 16; i++) {
            expectedConsume16 += (2 + i) + (3 + i);
        }
        assertEquals(expectedConsume16, callInt("loopCreateConsumeNth", 2, 3));
        assertEquals(expectedConsume16, callInt("forSameTripsAsWhileConsume:16", 2, 3));
        int expectedCountdown16 = 0;
        for (int n = 16; n > 0; n--) {
            expectedCountdown16 += (2 + n) + (3 + n);
        }
        assertEquals(expectedCountdown16, callInt("whileCountdownConsume:16", 2, 3));
    }

    @Test
    public void earlyReturnProfiled() throws Exception {
        assertEquals(callInt("whileCountdownConsume:8", 2, 3),
                callInt("whileEarlyReturnLastIterConsume:8", 2, 3));
    }
}
