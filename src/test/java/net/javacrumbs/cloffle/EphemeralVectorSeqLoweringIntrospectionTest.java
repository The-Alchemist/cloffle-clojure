package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} lowering for analyze-time
 * {@link clojure.lang.Compiler.EphemeralVectorSeqKeywordCreateExpr} and fused
 * {@link clojure.lang.Compiler.VectorKeywordMapFirstExpr}. Requires direct linking on for fusion;
 * fused {@code (first (map :kw …))} ignores {@code with-redefs}.
 */
public class EphemeralVectorSeqLoweringIntrospectionTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static CloffleBytecodeRootNode compileAndWarm(String form, String rootName) throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(form, rootName);
        root.getCallTarget().call();
        return root;
    }

    private static List<String> instructionNamesDirectLinkingOff(String form, String rootName)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOff(() -> {
            CloffleBytecodeRootNode root = compileAndWarm(form, rootName);
            List<String> names = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
            return names;
        });
    }

    private static List<String> instructionNamesDirectLinkingOn(String form, String rootName)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            CloffleBytecodeRootNode root = compileAndWarm(form, rootName);
            List<String> names = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
            return names;
        });
    }

    private static List<String> instructionNamesCompileOnlyDirectLinkingOn(String form, String rootName)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(form, rootName);
            List<String> names = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
            return names;
        });
    }

    private static final String MAP_FIRST_ON_ROWS =
            "(first (map :id (vector {:id :one} {:id :two} (identity 0))))";

    @Test
    @Tag("direct-linking-off")
    void firstOnMapKeywordVectorDoesNotFuseWhenLockedFoldsOff() throws Exception {
        List<String> names = instructionNamesDirectLinkingOff(MAP_FIRST_ON_ROWS, "evsMapFirstOff");
        assertTrue(names.stream().noneMatch(n -> n.endsWith("VectorKeywordMapFirst")),
                () -> "fusion off by default: " + names);
        assertFalse(names.stream().noneMatch(n -> n.contains("InvokeVar")),
                () -> "expected InvokeVar for #'first or #'map: " + names);
    }

    @Test
    @Tag("direct-linking-on")
    void firstOnMapKeywordVectorFusesToVectorKeywordMapFirst() throws Exception {
        List<String> names = instructionNamesDirectLinkingOn(MAP_FIRST_ON_ROWS, "evsMapFirst");
        assertTrue(names.stream().anyMatch(n -> n.endsWith("VectorKeywordMapFirst")),
                () -> "expected VectorKeywordMapFirst: " + names);
        assertTrue(names.stream().noneMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")),
                () -> "fusion should skip EphemeralVectorSeqKeywordCreate: " + names);
    }

    @Test
    @Tag("direct-linking-on")
    void firstOnMapKeywordVectorFusesUnderDirectLinkingPerfProfile() throws Exception {
        List<String> names = instructionNamesDirectLinkingOn(MAP_FIRST_ON_ROWS, "evsMapFirstDl");
        assertTrue(names.stream().anyMatch(n -> n.endsWith("VectorKeywordMapFirst")),
                () -> "expected VectorKeywordMapFirst under :direct-linking: " + names);
        assertTrue(names.stream().noneMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")),
                () -> "fusion should skip EphemeralVectorSeqKeywordCreate: " + names);
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordOnVectorLowersToEphemeralVectorSeqKeywordCreate() throws Exception {
        List<String> names = instructionNamesCompileOnlyDirectLinkingOn(
                "(seq (map :status (vector {:status :ok} {:status :fail} (identity 0))))",
                "evsMapSeq");
        assertTrue(names.stream().anyMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")),
                () -> "expected EphemeralVectorSeqKeywordCreate: " + names);
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordOnVectorLowersUnderDirectLinkingPerfProfile() throws Exception {
        List<String> names = instructionNamesCompileOnlyDirectLinkingOn(
                "(seq (map :status (vector {:status :ok} {:status :fail} (identity 0))))",
                "evsMapSeqDl");
        assertTrue(names.stream().anyMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")),
                () -> "expected EphemeralVectorSeqKeywordCreate under :direct-linking: " + names);
    }
}
