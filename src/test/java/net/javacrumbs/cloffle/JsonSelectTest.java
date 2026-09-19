package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@code cloffle.json/select}: RFC 6901 pointers resolved in one scan. */
public class JsonSelectTest {

    @BeforeClass
    public static void loadJsonNs() throws Exception {
        RT.init();
        RT.load("cloffle/json");
        RT.var("cloffle.json", "select").rearmLoweringRoot();
    }

    private static Object eval(String form) {
        return BytecodeDslTestSupport.evalBytecode(form);
    }

    /** Evaluates {@code (cloffle.json/select "<json>" [<pointers>])}. */
    private static IPersistentMap select(String json, String... pointers) {
        StringBuilder form = new StringBuilder("(cloffle.json/select \"")
                .append(json.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\" [");
        for (String pointer : pointers) {
            form.append('"').append(pointer.replace("\"", "\\\"")).append("\" ");
        }
        return (IPersistentMap) eval(form.append("])").toString());
    }

    @Test
    public void readsNestedMembersInOnePass() {
        IPersistentMap result = select(
                "{\"data\":{\"id\":\"1\",\"attributes\":{\"title\":\"Nine\"}},"
                        + "\"meta\":{\"request-id\":\"r-7\"}}",
                "/data/id", "/data/attributes/title", "/meta/request-id");
        assertEquals("1", result.valAt("/data/id"));
        assertEquals("Nine", result.valAt("/data/attributes/title"));
        assertEquals("r-7", result.valAt("/meta/request-id"));
    }

    @Test
    public void numericTokenReadsAnArrayElement() {
        IPersistentMap result = select(
                "{\"statuses\":[{\"text\":\"first\"},{\"text\":\"second\"}]}",
                "/statuses/0/text", "/statuses/1/text");
        assertEquals("first", result.valAt("/statuses/0/text"));
        assertEquals("second", result.valAt("/statuses/1/text"));
    }

    @Test
    public void numericTokenAlsoReadsAMemberOfThatName() {
        // RFC 6901 leaves this to the document: the same pointer addresses either.
        IPersistentMap result = select("{\"counts\":{\"0\":11,\"1\":22}}",
                "/counts/0", "/counts/1");
        assertEquals(11L, result.valAt("/counts/0"));
        assertEquals(22L, result.valAt("/counts/1"));
    }

    @Test
    public void valuesKeepTheirJsonTypes() {
        IPersistentMap result = select(
                "{\"n\":42,\"big\":123456789012345678901234567890,\"f\":2.5,"
                        + "\"t\":true,\"nil\":null,\"s\":\"x\"}",
                "/n", "/big", "/f", "/t", "/nil", "/s");
        assertEquals(42L, result.valAt("/n"));
        assertEquals(2.5, (Double) result.valAt("/f"), 1e-9);
        assertEquals(Boolean.TRUE, result.valAt("/t"));
        assertNull(result.valAt("/nil"));
        assertEquals("x", result.valAt("/s"));
        assertEquals(new java.math.BigInteger("123456789012345678901234567890"),
                ((clojure.lang.BigInt) result.valAt("/big")).toBigInteger());
    }

    @Test
    public void pointersCanTargetContainers() {
        IPersistentMap result = select(
                "{\"owner\":{\"login\":\"clojure\",\"id\":7},\"tags\":[1,2,3]}",
                "/owner", "/tags");
        IPersistentMap owner = (IPersistentMap) result.valAt("/owner");
        assertEquals("clojure", owner.valAt(clojure.lang.Keyword.intern("login")));
        assertEquals(7L, owner.valAt(clojure.lang.Keyword.intern("id")));
        IPersistentVector tags = (IPersistentVector) result.valAt("/tags");
        assertEquals(3, tags.count());
        assertEquals(2L, tags.nth(1));
    }

    @Test
    public void emptyPointerSelectsTheWholeDocument() {
        IPersistentMap result = select("{\"id\":7}", "");
        IPersistentMap whole = (IPersistentMap) result.valAt("");
        assertEquals(7L, whole.valAt(clojure.lang.Keyword.intern("id")));
    }

    @Test
    public void escapedTokensAddressAwkwardNames() {
        IPersistentMap result = select("{\"a/b\":1,\"m~n\":2}", "/a~1b", "/m~0n");
        assertEquals(1L, result.valAt("/a~1b"));
        assertEquals(2L, result.valAt("/m~0n"));
    }

    @Test
    public void missingPointersAreAbsentFromTheResult() {
        IPersistentMap result = select("{\"id\":7}", "/id", "/nope", "/deep/missing");
        assertEquals(7L, result.valAt("/id"));
        assertEquals(1, result.count());
        assertFalse(result.containsKey("/nope"));
    }

    @Test
    public void escapedStringValuesAreDecoded() {
        IPersistentMap result = select("{\"s\":\"a\\nb\\u0041\"}", "/s");
        assertEquals("a\nbA", result.valAt("/s"));
    }

    @Test
    public void constantPointerVectorLowersToTypedOperation() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> roots = BytecodeDslTestSupport.compileRootNodes(
                "(cloffle.json/select \"{\\\"id\\\":7}\" [\"/id\"])", "jsonSelect");
        List<String> names = new ArrayList<>();
        for (CloffleBytecodeRootNode root : roots.getNodes()) {
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
        }
        assertTrue(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
    }

    @Test
    public void runtimePointerCollectionStillWorks() {
        IPersistentMap result = (IPersistentMap) eval(
                "(cloffle.json/select \"{\\\"id\\\":7}\" (vec [\"/id\"]))");
        assertEquals(7L, result.valAt("/id"));
    }

    @Test
    public void malformedPointersAreRejected() {
        try {
            eval("(cloffle.json/select \"{}\" [\"data/id\"])");
            fail("expected a parse failure");
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause != null && !(cause instanceof IllegalArgumentException)) {
                cause = cause.getCause();
            }
            assertTrue(String.valueOf(t), cause != null);
        }
    }
}
