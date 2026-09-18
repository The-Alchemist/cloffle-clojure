package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.Keyword;
import clojure.lang.PersistentShapeMap;
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
 * Gates the multi-path SAX {@code JsonProject} rewrite: it must fire on {@code let}-bound
 * projections and {@code select-keys}, and must not fire when the parse result escapes.
 */
public class JsonProjectIntrospectionTest {

    private static final String JSONAPI =
            "{\\\"data\\\":{\\\"id\\\":\\\"article-101\\\",\\\"attributes\\\":{\\\"title\\\":\\\"T\\\"}},\\\"meta\\\":{\\\"request-id\\\":\\\"r1\\\"}}";
    private static final String ENTITY = "{\\\"email\\\":\\\"a@b.test\\\",\\\"id\\\":\\\"u1\\\"}";

    @BeforeClass
    public static void loadJsonNs() throws Exception {
        RT.init();
        RT.load("cloffle/json");
        RT.var("cloffle.json", "parse-string").rearmLoweringRoot();
        RT.var("cloffle.json", "parse-bytes").rearmLoweringRoot();
        RT.var("clojure.core", "get-in").rearmLoweringRoot();
        RT.var("clojure.core", "select-keys").rearmLoweringRoot();
    }

    private static List<String> instructions(String form) throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> roots =
                BytecodeDslTestSupport.compileRootNodes(form, "jsonProject");
        List<String> names = new ArrayList<>();
        for (CloffleBytecodeRootNode root : roots.getNodes()) {
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
        }
        return names;
    }

    private static boolean projects(String form) throws Exception {
        return instructions(form).stream().anyMatch(n -> n.contains("JsonProject"));
    }

    private static void assertProjects(String form) throws Exception {
        assertTrue("expected JsonProject for " + form + " but got " + instructions(form), projects(form));
    }

    private static void assertDoesNotProject(String form) throws Exception {
        assertFalse("expected no JsonProject for " + form + " but got " + instructions(form),
                projects(form));
    }

    @Test
    public void firesOnLetBoundKeywordLookup() throws Exception {
        assertProjects("(let [m (cloffle.json/parse-string \"" + ENTITY + "\")] (:email m))");
    }

    @Test
    public void firesOnLetBoundMapLiteral() throws Exception {
        assertProjects(
                "(let [m (cloffle.json/parse-string \"" + JSONAPI + "\")]"
                        + " {:title (get-in m [:data :attributes :title])"
                        + "  :id (get-in m [:data :id])"
                        + "  :rid (get-in m [:meta :request-id])})");
    }

    @Test
    public void firesOnSelectKeysOfParse() throws Exception {
        assertProjects("(select-keys (cloffle.json/parse-string \"" + ENTITY + "\") [:email :id])");
    }

    @Test
    public void firesOnLetBoundSelectKeys() throws Exception {
        assertProjects(
                "(let [m (cloffle.json/parse-string \"" + ENTITY + "\")] (select-keys m [:email :id]))");
    }

    @Test
    public void doesNotFireWhenTheParseResultEscapes() throws Exception {
        assertDoesNotProject(
                "(let [m (cloffle.json/parse-string \"" + ENTITY + "\")] [(:email m) m])");
    }

    @Test
    public void doesNotFireOnBareParse() throws Exception {
        assertDoesNotProject("(cloffle.json/parse-string \"" + ENTITY + "\")");
    }

    @Test
    public void doesNotFireThroughSomeThreading() throws Exception {
        assertDoesNotProject(
                "(some-> (cloffle.json/parse-string \"" + JSONAPI + "\") :data :attributes :title)");
    }

    @Test
    public void doesNotFireOnSeq() throws Exception {
        assertDoesNotProject("(seq (cloffle.json/parse-string \"" + ENTITY + "\"))");
    }

    @Test
    public void doesNotFireOnDynamicKey() throws Exception {
        assertDoesNotProject(
                "(let [m (cloffle.json/parse-string \"" + ENTITY + "\") k :email] (get m k))");
    }

    @Test
    public void letProjectionReturnsTheSameValues() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(let [m (cloffle.json/parse-string \"" + JSONAPI + "\")]"
                        + " {:title (get-in m [:data :attributes :title])"
                        + "  :id (get-in m [:data :id])})");
        assertTrue(v instanceof PersistentShapeMap);
        PersistentShapeMap m = (PersistentShapeMap) v;
        assertEquals("T", m.valAt(Keyword.intern("title")));
        assertEquals("article-101", m.valAt(Keyword.intern("id")));
    }

    @Test
    public void selectKeysOmitsMissing() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(select-keys (cloffle.json/parse-string \"" + ENTITY + "\") [:email :nope])");
        assertEquals("a@b.test", RT.get(v, Keyword.intern("email")));
        assertEquals(null, RT.get(v, Keyword.intern("nope")));
        assertEquals(1, RT.count(v));
    }
}
