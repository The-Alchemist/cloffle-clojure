package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gates {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} lowering for analyze-time
 * {@link clojure.lang.Compiler.EphemeralVectorSeqKeywordCreateExpr} and fused
 * {@link clojure.lang.Compiler.VectorKeywordMapFirstExpr}. Requires
 * {@code :locked-call-site-rewrites}; fused {@code (first (map :kw …))} ignores {@code with-redefs}.
 */
public class EphemeralVectorSeqLoweringIntrospectionTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static CloffleBytecodeRootNode compileAndWarm(String form, String rootName) throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(form, rootName);
        root.getCallTarget().call();
        return root;
    }

    private static List<String> instructionNames(String form, String rootName) throws Exception {
        CloffleBytecodeRootNode root = compileAndWarm(form, rootName);
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    private static List<String> instructionNamesWithLockedFolds(String form, String rootName)
            throws Exception {
        return BytecodeDslTestSupport.withLockedCallSiteRewrites(
                () -> instructionNames(form, rootName));
    }

    /** Non-literal vector element blocks analyze-time map constant fold. */
    private static final String MAP_FIRST_ON_ROWS =
            "(first (map :id (vector {:id :one} {:id :two} (identity 0))))";

    @Test
    public void firstOnMapKeywordVectorDoesNotFuseWhenLockedFoldsOff() throws Exception {
        List<String> names = instructionNames(MAP_FIRST_ON_ROWS, "evsMapFirstOff");
        assertTrue("fusion off by default: " + names,
                names.stream().noneMatch(n -> n.endsWith("VectorKeywordMapFirst")));
        assertFalse("expected InvokeVar for #'first or #'map: " + names,
                names.stream().noneMatch(n -> n.contains("InvokeVar")));
    }

    @Test
    public void firstOnMapKeywordVectorFusesToVectorKeywordMapFirst() throws Exception {
        List<String> names = instructionNamesWithLockedFolds(MAP_FIRST_ON_ROWS, "evsMapFirst");
        assertTrue("expected VectorKeywordMapFirst: " + names,
                names.stream().anyMatch(n -> n.endsWith("VectorKeywordMapFirst")));
        assertTrue("fusion should skip EphemeralVectorSeqKeywordCreate: " + names,
                names.stream().noneMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")));
    }

    private static List<String> instructionNamesCompileOnly(String form, String rootName) throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(form, rootName);
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    @Test
    public void mapKeywordOnVectorLowersToEphemeralVectorSeqKeywordCreate() throws Exception {
        List<String> names = BytecodeDslTestSupport.withLockedCallSiteRewrites(
                () -> instructionNamesCompileOnly(
                        "(seq (map :status (vector {:status :ok} {:status :fail} (identity 0))))",
                        "evsMapSeq"));
        assertTrue("expected EphemeralVectorSeqKeywordCreate: " + names,
                names.stream().anyMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")));
    }
}
