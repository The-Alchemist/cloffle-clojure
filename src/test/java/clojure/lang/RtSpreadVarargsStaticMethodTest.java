package clojure.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cloffle-only: spread host interop for single-slot varargs statics (e.g. {@code RT.vector(Object...)}).
 * Stock Clojure JAR compile still rejects multi-arg {@code (RT/vector …)}; compare-performance snippets
 * should use vector literals or {@code (vector …)} for both JMH legs.
 */
public class RtSpreadVarargsStaticMethodTest {

    @Test
    public void rtVectorSpreadCompilesAndRuns() {
        Object n = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/count (clojure.lang.RT/vector :a :b :c :d))");
        assertEquals(4, ((Number) n).intValue());
    }
}
