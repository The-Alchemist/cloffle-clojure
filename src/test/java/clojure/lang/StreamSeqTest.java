package clojure.lang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.BeforeClass;
import org.junit.Test;

import static clojure.lang.StreamSeqTestSupport.*;
import static org.junit.Assert.*;

public class StreamSeqTest {

    @BeforeClass
    public static void setUp() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle", "1");
        }
    }

    @Test
    public void testCatalogSamplesExist() {
        for (String sample : SAMPLE_NAMES) {
            String code = codeFor(sample);
            assertNotNull("Expected snippet code for " + sample, code);
            assertFalse("Expected non-empty snippet code for " + sample, code.isEmpty());
        }
    }

    @Test
    public void testTransducerChaining() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value matches = context.eval("cloffle", codeFor(TRANSDUCER_CHAINING));
            assertTrue("Expected [1 3 5]", matches.asBoolean());
        }
    }

    @Test
    public void testCompositionVerification() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value isStreamSeq = context.eval("cloffle", codeFor(COMPOSITION_IS_STREAM_SEQ));
            assertTrue("Expected pipeline to be a StreamSeq", isStreamSeq.asBoolean());

            Value sameSource = context.eval("cloffle", codeFor(COMPOSITION_SAME_SOURCE));
            assertTrue("Expected composed StreamSeq to point directly to root source", sameSource.asBoolean());
        }
    }

    @Test
    public void testSteppingAndMemoization() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle", codeFor(STEPPING_AND_MEMOIZATION));
            assertEquals(3, result.asInt());
        }
    }

    @Test
    public void testEmptyCollections() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle", codeFor(EMPTY_COLLECTIONS));
            assertTrue("Expected empty collections check to pass", result.asBoolean());
        }
    }

    @Test
    public void testInfiniteSequences() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value matches = context.eval("cloffle", codeFor(INFINITE_SEQUENCES));
            assertTrue("Expected [0 2 4]", matches.asBoolean());
        }
    }

    @Test
    public void testDirectJavaPushLoopReduction() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3, 4, 5, 6);
        IFn filterEven = (IFn) RT.var("clojure.core", "filter").invoke(new AFn() {
            @Override
            public Object invoke(Object arg) {
                return ((Number) arg).intValue() % 2 == 0;
            }
        });
        IFn mapSquare = (IFn) RT.var("clojure.core", "map").invoke(new AFn() {
            @Override
            public Object invoke(Object arg) {
                int x = ((Number) arg).intValue();
                return x * x;
            }
        });

        ISeq s1 = StreamSeq.create(filterEven, vec);
        assertNotNull(s1);
        assertTrue(s1 instanceof StreamSeq);

        ISeq s2 = StreamSeq.create(mapSquare, s1);
        assertNotNull(s2);
        assertTrue(s2 instanceof StreamSeq);
        StreamSeq ss = (StreamSeq) s2;

        // Verify push-loop reduction (sum of squares of evens: 2^2 + 4^2 + 6^2 = 4 + 16 + 36 = 56)
        Object sum = ss.reduce(new AFn() {
            @Override
            public Object invoke(Object acc, Object val) {
                return ((Number) acc).intValue() + ((Number) val).intValue();
            }
        }, 0);
        assertEquals(56, ((Number) sum).intValue());

        // Verify pull stepping
        assertEquals(4, ((Number) ss.first()).intValue());
        ISeq next1 = ss.next();
        assertNotNull(next1);
        assertEquals(16, ((Number) next1.first()).intValue());
        ISeq next2 = next1.next();
        assertNotNull(next2);
        assertEquals(36, ((Number) next2.first()).intValue());
        assertNull(next2.next());
    }

    @Test
    public void testMixedPullThenReduce() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle", codeFor(MIXED_PULL_THEN_REDUCE));
            assertTrue("Expected @c to equal 3, not re-executing map side-effects", result.asBoolean());
        }
    }

    @Test
    public void testOneArityReducePushUnrealized() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3, 4, 5, 6);
        IFn filterEven = (IFn) RT.var("clojure.core", "filter").invoke(new AFn() {
            @Override
            public Object invoke(Object arg) {
                return ((Number) arg).intValue() % 2 == 0;
            }
        });
        StreamSeq ss = (StreamSeq) StreamSeq.create(filterEven, vec);
        assertNotNull(ss);
        assertFalse("StreamSeq must not be realized before reduction", ss.isRealized());

        IFn plus = new AFn() {
            @Override
            public Object invoke() {
                return 0;
            }
            @Override
            public Object invoke(Object a, Object b) {
                return ((Number) a).intValue() + ((Number) b).intValue();
            }
        };

        Object sum = ss.reduce(plus);
        assertEquals(12, ((Number) sum).intValue());
        assertFalse("StreamSeq must remain UNREALIZED after 1-arity push reduce", ss.isRealized());

        // Empty collection 1-arity reduce
        StreamSeq emptySs = (StreamSeq) StreamSeq.create(filterEven, RT.vector());
        Object emptySum = emptySs.reduce(plus);
        assertEquals(0, ((Number) emptySum).intValue());
        assertFalse(emptySs.isRealized());

        // 1-element collection 1-arity reduce
        StreamSeq singleSs = (StreamSeq) StreamSeq.create(filterEven, RT.vector(42));
        Object singleVal = singleSs.reduce(plus);
        assertEquals(42, ((Number) singleVal).intValue());
        assertFalse(singleSs.isRealized());
    }

    @Test
    public void testCompletionArityFlush() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle", codeFor(COMPLETION_ARITY_FLUSH));
            assertTrue("Expected partition-all to flush remaining elements on completion", result.asBoolean());

            // Also test with a binary-only fn (no 1-arity) to verify ArityException fallback
            Value binaryOnlyResult = context.eval("cloffle", codeFor(BINARY_ONLY_RF));
            assertTrue("Expected binary-only fn to work without throwing ArityException", binaryOnlyResult.asBoolean());
        }
    }
}
