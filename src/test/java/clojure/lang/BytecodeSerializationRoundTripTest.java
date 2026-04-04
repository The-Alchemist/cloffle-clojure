package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerialization;
import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * AOT wire format: every top-level form in classpath {@code clojure/core.clj} uses the same compile spine as
 * {@link CloffleCoreBytecodeArchive#writeArchive} / {@code dump-core} (see {@link
 * CloffleCoreBytecodeArchive#compileEachTopLevelForm}): same source path/name, reader bindings, and
 * {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} root names ({@code core_form_}<em>n</em>). Each form is
 * executed, serialized, deserialized, and executed again; results must match.
 * <p>
 * {@link RT#init()} has already loaded the full {@code clojure.core} namespace, so analysis remains valid.
 * Re-evaluating trailing {@code (load "core_proxy")} … forms can double-define / reload; this test targets bytecode
 * round-trip coverage, not a clean second bootstrap. Same text and labels as {@link
 * CloffleCoreBytecodeArchive#writeFromClasspathCore} (what {@code dump-bytecode-archive} serializes).
 */
public class BytecodeSerializationRoundTripTest {

    @BeforeClass
    public static void initRtAndUserNs() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
        RT.CHECK_SPECS = false;
    }

    @Test
    public void serializeDeserializeAllTopLevelFormsInCoreCljMatchesEval() throws Exception {
        String text = CloffleCoreBytecodeArchive.readClasspathCoreCljText();
        assertTrue("classpath clojure/core.clj is non-empty", !text.isEmpty());

        final int[] formCount = {0};
        CloffleCoreBytecodeArchive.compileEachTopLevelForm(
                text,
                CloffleCoreBytecodeArchive.CORE_BYTECODE_SOURCE_PATH,
                CloffleCoreBytecodeArchive.CORE_BYTECODE_SOURCE_NAME,
                (formIndex, nodes) -> {
                    formCount[0]++;
                    CloffleBytecodeRootNode original = nodes.getNode(0);
                    Object expected = original.getCallTarget().call();

                    byte[] wire = CloffleBytecodeSerialization.serializeRootNodes(nodes);
                    BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                            CloffleBytecodeSerialization.deserializeRootNodes(wire);
                    Object actual = deserialized.getNode(0).getCallTarget().call();
                    assertTrue(
                            "core.clj form #"
                                    + formIndex
                                    + " round-trip mismatch: expected "
                                    + RT.printString(expected)
                                    + " got "
                                    + RT.printString(actual),
                            Util.equiv(expected, actual));
                });
        assertTrue("expected at least one top-level form in core.clj", formCount[0] > 0);
    }
}
