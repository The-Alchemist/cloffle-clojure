package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Reproducer for {@code VerifyError: Bad type on operand stack} when
 * {@code defrecord} is used via the Cloffle bytecode backend.
 * <p>
 * {@code defrecord} expands to {@code deftype*} with extra fields
 * {@code __meta __extmap ^int __hash ^int __hasheq}. The {@code ^int}
 * metadata must survive through macro expansion and analysis so the
 * generated constructor has {@code int} parameter types for those fields.
 * <p>
 * Root cause: Truffle's {@code ConstantsBuffer} deduplicates constants
 * using {@code Object.equals()}, but {@link Symbol#equals(Object)} ignores
 * metadata. Two symbols with the same name but different metadata collapse
 * to whichever was added first.
 */
public class DefrecordVerifyErrorTest {

    @Test
    public void deftypeStarWithIntFields() {
        String name = "DRVerify_" + System.nanoTime();
        String code =
                "(deftype* " + name + " " + name
                + " [^int x]"
                + " :implements [])";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertNull("deftype* expression should be null", result);
    }

    @Test
    public void deftypeStarWithRecordFields() {
        String name = "DRVerifyRec_" + System.nanoTime();
        String code =
                "(deftype* " + name + " " + name
                + " [a b __meta __extmap ^int __hash ^int __hasheq]"
                + " :implements [clojure.lang.IHashEq]"
                + " (hasheq [this] __hasheq))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertNull("deftype* expression should be null", result);
    }

    /**
     * Verifies that metadata on constants survives Truffle constant-pool
     * deduplication. A function that returns two quoted symbols with the
     * same name but different metadata must return distinct metadata for
     * each.
     */
    /**
     * Two quoted symbols with the same name but different metadata must
     * retain their respective metadata, even when compiled in the same
     * bytecode root (which shares a Truffle constant pool).
     */
    @Test
    public void constantSymbolMetadataNotDeduplicated() {
        // First: a symbol with only :unsynchronized-mutable — no :tag
        Object metaA = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/meta '^:unsynchronized-mutable __hash)");
        assertTrue(metaA instanceof IPersistentMap);
        assertNull(":tag should be absent on first symbol",
                ((IPersistentMap) metaA).valAt(RT.TAG_KEY));

        // Second: same name but with ^int :tag — must survive constant dedup
        Object metaB = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/meta '^int ^:unsynchronized-mutable __hash)");
        assertTrue(metaB instanceof IPersistentMap);
        assertEquals(":tag must be int on second symbol",
                Symbol.intern("int"), ((IPersistentMap) metaB).valAt(RT.TAG_KEY));

        // Combined: both in the same compilation unit (same constant pool)
        Object tag = BytecodeDslTestSupport.evalBytecode(
                "(let* [a '^:unsynchronized-mutable __hash"
                + "      b '^int ^:unsynchronized-mutable __hash]"
                + "  (.valAt (clojure.lang.RT/meta b) :tag))");
        assertEquals(":tag must survive dedup in same root",
                Symbol.intern("int"), tag);
    }
}
