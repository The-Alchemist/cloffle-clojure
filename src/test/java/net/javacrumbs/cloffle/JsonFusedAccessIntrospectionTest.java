package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.Keyword;
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
import static org.junit.Assert.assertTrue;

/**
 * Gates the fused {@code json/parse-string} + constant-access rewrite: it must fire on the
 * "parse then project one field" shapes, and must <em>not</em> fire as soon as the parse result
 * gets a name, is handed to something unrecognized, or is itself the value of the expression.
 */
public class JsonFusedAccessIntrospectionTest {

    private static final String JSONAPI =
            "{\\\"data\\\":{\\\"attributes\\\":{\\\"title\\\":\\\"T\\\"}}}";
    private static final String ENTITY = "{\\\"email\\\":\\\"a@b.test\\\"}";
    private static final String ROWS = "[{\\\"name\\\":\\\"a\\\"},{\\\"name\\\":\\\"b\\\"}]";

    @BeforeClass
    public static void loadJsonNs() throws Exception {
        RT.init();
        RT.load("cloffle/json");
        RT.var("cloffle.json", "parse-string").rearmLoweringRoot();
        RT.var("cloffle.json", "parse-bytes").rearmLoweringRoot();
        RT.var("clojure.core", "get-in").rearmLoweringRoot();
        RT.var("clojure.core", "select-keys").rearmLoweringRoot();
    }

    /** Instruction names across every root the form compiles to (a {@code let} body nests roots). */
    private static List<String> instructions(String form) throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> roots =
                BytecodeDslTestSupport.compileRootNodes(form, "jsonFuse");
        List<String> names = new ArrayList<>();
        for (CloffleBytecodeRootNode root : roots.getNodes()) {
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
        }
        return names;
    }

    private static boolean fuses(String form) throws Exception {
        return instructions(form).stream().anyMatch(n -> n.contains("JsonFusedExtract"));
    }

    private static void assertFuses(String form) throws Exception {
        assertTrue("expected JsonFusedExtract for " + form + " but got " + instructions(form), fuses(form));
    }

    private static void assertDoesNotFuse(String form) throws Exception {
        assertFalse("expected no JsonFusedExtract for " + form, fuses(form));
    }

    @Test
    public void firesOnGetInWithLiteralKeywordPath() throws Exception {
        assertFuses("(get-in (cloffle.json/parse-string \"" + JSONAPI + "\") [:data :attributes :title])");
    }

    @Test
    public void firesOnKeywordInvocation() throws Exception {
        assertFuses("(:email (cloffle.json/parse-string \"" + ENTITY + "\"))");
    }

    @Test
    public void firesOnNthThenKeyword() throws Exception {
        assertFuses("(:name (nth (cloffle.json/parse-string \"" + ROWS + "\") 1))");
    }

    @Test
    public void firesOnGetWithLiteralKeyword() throws Exception {
        assertFuses("(get (cloffle.json/parse-string \"" + ENTITY + "\") :email)");
    }

    @Test
    public void firesOnChainedKeywordLookups() throws Exception {
        assertFuses("(:title (:attributes (:data (cloffle.json/parse-string \"" + JSONAPI + "\"))))");
    }

    @Test
    public void firesOnParseBytes() throws Exception {
        assertFuses("(:email (cloffle.json/parse-bytes (.getBytes \"" + ENTITY + "\" \"UTF-8\")))");
    }

    /** {@code ->} expands to plain nesting, so the idiomatic threading form is covered. */
    @Test
    public void firesThroughTheThreadingMacro() throws Exception {
        assertFuses("(-> (cloffle.json/parse-string \"" + JSONAPI + "\") :data :attributes :title)");
    }

    /** {@code some->} introduces a {@code let}, which names the parse result. */
    @Test
    public void doesNotFireThroughSomeThreading() throws Exception {
        assertDoesNotFuse(
                "(some-> (cloffle.json/parse-string \"" + JSONAPI + "\") :data :attributes :title)");
    }

    @Test
    public void doesNotFireWhenTheParseResultIsBoundToALocal() throws Exception {
        assertDoesNotFuse("(let [m (cloffle.json/parse-string \"" + ENTITY + "\")] (:email m))");
    }

    @Test
    public void doesNotFireWhenTheParseResultAlsoEscapes() throws Exception {
        assertDoesNotFuse(
                "(let [m (cloffle.json/parse-string \"" + ENTITY + "\")] [(:email m) m])");
    }

    @Test
    public void doesNotFireWhenTheParseResultIsTheValue() throws Exception {
        assertDoesNotFuse("(cloffle.json/parse-string \"" + ENTITY + "\")");
    }

    @Test
    public void doesNotFireWhenTheParseResultIsPassedToAFunction() throws Exception {
        assertDoesNotFuse("(count (cloffle.json/parse-string \"" + ENTITY + "\"))");
        assertDoesNotFuse("(vector (cloffle.json/parse-string \"" + ENTITY + "\"))");
        assertDoesNotFuse("(keys (cloffle.json/parse-string \"" + ENTITY + "\"))");
    }

    @Test
    public void doesNotFireOnANonConstantPath() throws Exception {
        assertDoesNotFuse(
                "(let [ks [:data]] (get-in (cloffle.json/parse-string \"" + JSONAPI + "\") ks))");
        assertDoesNotFuse(
                "(let [i 1] (:name (nth (cloffle.json/parse-string \"" + ROWS + "\") i)))");
        assertDoesNotFuse(
                "(let [k :email] (get (cloffle.json/parse-string \"" + ENTITY + "\") k))");
    }

    @Test
    public void doesNotFireOnIndexStepsInsideGetIn() throws Exception {
        assertDoesNotFuse("(get-in (cloffle.json/parse-string \"" + ROWS + "\") [0 :name])");
    }

    @Test
    public void doesNotFireOnANegativeNthIndex() throws Exception {
        assertDoesNotFuse("(:name (nth (cloffle.json/parse-string \"" + ROWS + "\") -1))");
    }

    @Test
    public void doesNotFireOnNotFoundArities() throws Exception {
        assertDoesNotFuse("(get (cloffle.json/parse-string \"" + ENTITY + "\") :nope :dflt)");
        assertDoesNotFuse("(get-in (cloffle.json/parse-string \"" + ENTITY + "\") [:nope] :dflt)");
        assertDoesNotFuse("(nth (cloffle.json/parse-string \"" + ROWS + "\") 9 nil)");
    }

    @Test
    public void doesNotFireOnTheTwoArgParseForm() throws Exception {
        assertDoesNotFuse("(:email (cloffle.json/parse-string \"" + ENTITY + "\" nil))");
    }

    @Test
    public void fusedSiteReturnsTheSameValuesAsTheUnfusedForm() {
        assertEquals("T", BytecodeDslTestSupport.evalBytecode(
                "(get-in (cloffle.json/parse-string \"" + JSONAPI + "\") [:data :attributes :title])"));
        assertEquals("a@b.test", BytecodeDslTestSupport.evalBytecode(
                "(:email (cloffle.json/parse-string \"" + ENTITY + "\"))"));
        assertEquals("b", BytecodeDslTestSupport.evalBytecode(
                "(:name (nth (cloffle.json/parse-string \"" + ROWS + "\") 1))"));
        assertEquals(null, BytecodeDslTestSupport.evalBytecode(
                "(get-in (cloffle.json/parse-string \"" + JSONAPI + "\") [:data :nope :title])"));
    }

    /** A redefined Var must be observed even though the call site was lowered. */
    @Test
    public void redefinitionOfTheParseVarIsObserved() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(with-redefs [cloffle.json/parse-string (fn [_] {:email \"redefined\"})]"
                        + " (:email (cloffle.json/parse-string \"" + ENTITY + "\")))");
        assertEquals("redefined", v);
    }

    @Test
    public void redefinitionOfGetInIsObserved() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(with-redefs [clojure.core/get-in (fn [_ _] :hijacked)]"
                        + " (get-in (cloffle.json/parse-string \"" + JSONAPI + "\") [:data :attributes :title]))");
        assertEquals(Keyword.intern("hijacked"), v);
    }

    @Test
    public void badSourceTypeFailsLikeTheUnfusedForm() {
        try {
            BytecodeDslTestSupport.evalBytecode("(:email (cloffle.json/parse-string 42))");
        } catch (RuntimeException expected) {
            return;
        }
        org.junit.Assert.fail("expected the parse function's own argument error");
    }
}
