package net.javacrumbs.cloffle;

import clojure.lang.MappedMapSeq;
import clojure.lang.MappedVectorSeq;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import static org.junit.Assert.*;

public class MapOptimizationTest {

    @Test
    public void testTypeChecking() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value isMappedVec = context.eval("cloffle", "(instance? clojure.lang.MappedVectorSeq (map inc [1 2 3]))");
            assertTrue("Expected MappedVectorSeq", isMappedVec.asBoolean());

            Value isMappedMap = context.eval("cloffle", "(instance? clojure.lang.MappedMapSeq (map identity {:a 1 :b 2}))");
            assertTrue("Expected MappedMapSeq", isMappedMap.asBoolean());

            // Other collections use lazy-seq
            Value isLazySeq = context.eval("cloffle", "(instance? clojure.lang.LazySeq (map inc '(1 2 3)))");
            assertTrue("Expected LazySeq for list", isLazySeq.asBoolean());
        }
    }

    @Test
    public void testMemoizationViaCoreApi() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(let [c (atom 0)\n" +
                    "      s (map (fn [x] (swap! c inc) (inc x)) [1 2])]\n" +
                    "  (assert (= (first s) 2))\n" +
                    "  (assert (= (first s) 2))\n" +
                    "  (assert (= @c 1))\n" +
                    "  @c)");
            assertEquals(1, result.asInt());

            Value mapResult = context.eval("cloffle",
                    "(let [c (atom 0)\n" +
                    "      s (map (fn [e] (swap! c inc) (val e)) {:x 10 :y 20})]\n" +
                    "  (first s)\n" +
                    "  (first s)\n" +
                    "  @c)");
            assertEquals(1, mapResult.asInt());
        }
    }

    @Test
    public void testChainedPipelines() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(->> [1 2 3]\n" +
                    "     (map inc)\n" +
                    "     (map (fn [x] (* 2 x)))\n" +
                    "     (into []))");
            assertEquals(3, result.getArraySize());
            assertEquals(4, result.getArrayElement(0).asInt());
            assertEquals(6, result.getArrayElement(1).asInt());
            assertEquals(8, result.getArrayElement(2).asInt());
        }
    }

    @Test
    public void testNestedVectorOfMaps() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(->> [{:count 10} {:count 20}]\n" +
                    "     (map (fn [m] (update m :count inc)))\n" +
                    "     (map :count)\n" +
                    "     (into []))");
            assertEquals(2, result.getArraySize());
            assertEquals(11, result.getArrayElement(0).asInt());
            assertEquals(21, result.getArrayElement(1).asInt());
        }
    }

    @Test
    public void testInfiniteLazySequences() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(into [] (take 3 (map inc (iterate inc 0))))");
            assertEquals(3, result.getArraySize());
            assertEquals(1, result.getArrayElement(0).asInt());
            assertEquals(2, result.getArrayElement(1).asInt());
            assertEquals(3, result.getArrayElement(2).asInt());
        }
    }

    @Test
    public void testNilCollection() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle", "(map inc nil)");
            assertEquals(0, result.getArraySize());

            Value seqResult = context.eval("cloffle", "(seq (map inc nil))");
            assertTrue(seqResult.isNull());
        }
    }

    @Test
    public void testEmptyVectorAndMap() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value vEmpty = context.eval("cloffle", "(map inc [])");
            assertEquals(0, vEmpty.getArraySize());
            assertTrue(context.eval("cloffle", "(seq (map inc []))").isNull());

            Value mEmpty = context.eval("cloffle", "(map identity {})");
            assertEquals(0, mEmpty.getArraySize());
            assertTrue(context.eval("cloffle", "(seq (map identity {}))").isNull());
        }
    }
}
