package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeDeserializer;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNodeGen;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerializer;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Truffle {@link Source} / {@link SourceSection} behavior for bytecode
 * compiled with {@link ExprToBytecode#BYTECODE_CONFIG} ({@code WITH_SOURCE}), and serialization of
 * embedded {@link Source} constants.
 * <p>
 * Multi-line Clojure snippets use Java {@linkplain java.lang.String text blocks} so sources read like
 * real files; {@link #crlfLineEndingsStillFullSpan} normalizes {@code \\n} to {@code \\r\\n} after the
 * fact so the expected line-ending style is explicit. {@code fn*} tail {@code recur} source tests use the
 * same core-free forms as {@link ExprToBytecodeTest#fnStarRecurToMethodHead()} — see
 * {@link #fnStarRecurInnerRootsExposeFullSourceSpan}.
 *
 * @see BytecodeDslTestSupport
 * @see ExprToBytecodeTest
 */
public class ExprToBytecodeSourceLocationTest {

    private static void assertSourceSectionIsFullSpan(SourceSection sec, String code) {
        assertNotNull(sec);
        Source src = sec.getSource();
        assertNotNull(src);
        SourceSection expected = src.createSection(0, code.length());
        assertEquals(expected.getCharIndex(), sec.getCharIndex());
        assertEquals(expected.getCharLength(), sec.getCharLength());
        assertEquals(expected.getStartLine(), sec.getStartLine());
        assertEquals(expected.getStartColumn(), sec.getStartColumn());
        assertEquals(expected.getEndLine(), sec.getEndLine());
        assertEquals(expected.getEndColumn(), sec.getEndColumn());
        assertEquals(code, sec.getCharacters().toString());
    }

    @Test
    public void serializationRoundTripPreservesExecution() throws Exception {
        String code = "42";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes(code, "roundTrip");
        CloffleBytecodeRootNode original = nodes.getNode(0);
        Object before = original.getCallTarget().call();
        assertEquals(42L, before);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        byte[] serialized = baos.toByteArray();
        assertTrue(serialized.length > 0);

        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(serialized));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeRootNodeGen.deserialize(
                        null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
        CloffleBytecodeRootNode copy = deserialized.getNode(0);
        assertNotNull(copy);
        Object after = copy.getCallTarget().call();
        assertEquals(42L, after);
    }

    @Test
    public void rootSourceSectionSpansFullSourceText() throws Exception {
        String code = "99";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        SourceSection sec = root.getSourceSection();
        assertSourceSectionIsFullSpan(sec, code);
        assertEquals(BytecodeDslTestSupport.DEFAULT_BYTECODE_SOURCE_NAME, sec.getSource().getName());
    }

    @Test
    public void rootSourceSectionCharactersMatchExpressionSource() throws Exception {
        String code = "(if true 1 2)";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineSourceSectionMatchesFullTextBounds() throws Exception {
        String code =
                """
                (do
                  1
                  2)""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineIfBranchesEachOnOwnLine() throws Exception {
        String code =
                """
                (if false
                  1
                  2)""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(2L, root.getCallTarget().call());
    }

    @Test
    public void multiLineLetStarBindingsAndBody() throws Exception {
        String code =
                """
                (let* [a 1
                       b 2]
                  b)""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(2L, root.getCallTarget().call());
    }

    @Test
    public void multiLineTryCatch() throws Exception {
        String code =
                """
                (try
                  7
                  (catch Throwable t 0))""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(7L, root.getCallTarget().call());
    }

    @Test
    public void multiLineTryFinally() throws Exception {
        String code =
                """
                (try
                  :a
                  (finally nil))""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(Keyword.intern(null, "a"), root.getCallTarget().call());
    }

    @Test
    public void multiLineNestedDoAndIf() throws Exception {
        String code =
                """
                (do
                  (if true
                    1
                    2)
                  3)""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(3L, root.getCallTarget().call());
    }

    @Test
    public void multiLineQuotedList() throws Exception {
        String code =
                """
                (quote
                  (1 2 3))""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineVectorLiteral() throws Exception {
        String code =
                """
                [1
                 2
                 3]""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineMapLiteral() throws Exception {
        String code =
                """
                {:a 1
                 :b 2}""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineSetLiteral() throws Exception {
        String code =
                """
                #{1
                  2
                  3}""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void multiLineCaseStarIntCompact() throws Exception {
        String code =
                """
                (let* [x 1]
                  (case* x 0 0 :none {1 [1 :a] 2 [2 :b]} :compact :int))""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(Keyword.intern(null, "a"), root.getCallTarget().call());
    }

    @Test
    public void multiLineFnStarInvokeBodyOnFollowingLines() throws Exception {
        String code =
                """
                ((fn* ([]
                  42)))""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(42L, root.getCallTarget().call());
    }

    @Test
    public void multiLineFnStarRestArgs() throws Exception {
        String code =
                """
                ((fn* ([x & rest]
                  rest))
                 1 2 3)""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void manyBlankLinesBeforeExpression() throws Exception {
        String code =
                """


                  :x""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(Keyword.intern(null, "x"), root.getCallTarget().call());
    }

    @Test
    public void crlfLineEndingsStillFullSpan() throws Exception {
        String code =
                """
                (do
                  1
                  2)"""
                        .replace("\n", "\r\n");
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
        assertEquals(2L, root.getCallTarget().call());
    }

    @Test
    public void leadingNewlineAndIndentStillFullSpan() throws Exception {
        String code =
                """

                  42""";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void customSourceFileNameAppearsOnSection() throws Exception {
        String code = "1";
        String name = "custom/path/to_file.clj";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code, "namedRoot", name);
        SourceSection sec = root.getSourceSection();
        assertSourceSectionIsFullSpan(sec, code);
        assertEquals(name, sec.getSource().getName());
    }

    @Test
    public void rootSourceLanguageIdIsCloffle() throws Exception {
        SourceSection sec = BytecodeDslTestSupport.compileRoot("nil").getSourceSection();
        assertEquals("cloffle", sec.getSource().getLanguage());
    }

    @Test
    public void unicodeSourceTextPreservesCharSpan() throws Exception {
        String code = "\"" + "\u03B1" + "\"";
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(code);
        assertSourceSectionIsFullSpan(root.getSourceSection(), code);
    }

    @Test
    public void serializationRoundTripPreservesSourceMetadata() throws Exception {
        String code = "(if true 1 2)";
        String sourceName = "meta-test.clj";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes(code, "metaRoot", sourceName);
        Source beforeSrc = nodes.getNode(0).getSourceSection().getSource();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(baos.toByteArray()));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeRootNodeGen.deserialize(
                        null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
        Source afterSrc = deserialized.getNode(0).getSourceSection().getSource();

        assertEquals(beforeSrc.getName(), afterSrc.getName());
        assertEquals(beforeSrc.getLanguage(), afterSrc.getLanguage());
        assertEquals(beforeSrc.getCharacters().toString(), afterSrc.getCharacters().toString());
    }

    @Test
    public void deserializationRootSourceSectionMatchesSerializedOriginal() throws Exception {
        String code = "(do :a :b)";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes(code, "secRoundTrip");
        SourceSection before = nodes.getNode(0).getSourceSection();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(baos.toByteArray()));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeRootNodeGen.deserialize(
                        null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
        SourceSection after = deserialized.getNode(0).getSourceSection();

        assertEquals(before.getCharIndex(), after.getCharIndex());
        assertEquals(before.getCharLength(), after.getCharLength());
        assertEquals(before.getStartLine(), after.getStartLine());
        assertEquals(before.getStartColumn(), after.getStartColumn());
        assertEquals(before.getEndLine(), after.getEndLine());
        assertEquals(before.getEndColumn(), after.getEndColumn());
        assertEquals(before.getCharacters().toString(), after.getCharacters().toString());
    }

    /**
     * {@code fn*} bodies compile to additional bytecode roots; with {@code WITH_SOURCE} each should
     * still carry a section spanning the same {@link Source} text as the outer compilation unit.
     */
    @Test
    public void fnStarInnerRootsExposeFullSourceSpan() throws Exception {
        String code = "((fn* ([] 42)))";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = BytecodeDslTestSupport.compileRootNodes(code, "outer");
        assertTrue("expected at least outer + inner fn root", nodes.count() >= 2);
        for (int i = 0; i < nodes.count(); i++) {
            CloffleBytecodeRootNode n = nodes.getNode(i);
            assertSourceSectionIsFullSpan(n.getSourceSection(), code);
        }
    }

    @Test
    public void multiLineFnStarInnerRootsEachExposeFullSourceSpan() throws Exception {
        String code =
                """
                ((fn* ([]
                  (do
                    1
                    2))))""";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = BytecodeDslTestSupport.compileRootNodes(code, "outer");
        assertTrue("expected outer + inner fn root", nodes.count() >= 2);
        for (int i = 0; i < nodes.count(); i++) {
            assertSourceSectionIsFullSpan(nodes.getNode(i).getSourceSection(), code);
        }
        assertEquals(2L, nodes.getNode(0).getCallTarget().call());
    }

    /**
     * {@code fn*} method-head {@code recur} uses the same {@code While}-based lowering as {@code loop*};
     * inner bytecode roots should still attach a full-span section. Form matches
     * {@link ExprToBytecodeTest#fnStarRecurToMethodHead()} (valid {@code fn*} without {@code clojure.core}).
     */
    @Test
    public void fnStarRecurInnerRootsExposeFullSourceSpan() throws Exception {
        String code =
                """
                ((fn* [x]
                   (if (clojure.lang.Util/equiv x 0)
                     (recur 1)
                     x))
                 0)""";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = BytecodeDslTestSupport.compileRootNodes(code, "fnRecurSrc");
        assertTrue("expected outer + inner fn root", nodes.count() >= 2);
        for (int i = 0; i < nodes.count(); i++) {
            assertSourceSectionIsFullSpan(nodes.getNode(i).getSourceSection(), code);
        }
        assertEquals(1L, nodes.getNode(0).getCallTarget().call());
    }
}
