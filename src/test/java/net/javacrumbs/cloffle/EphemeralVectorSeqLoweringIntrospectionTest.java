package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Gates {@link ExprToBytecode} lowering for analyze-time
 * {@code EphemeralVectorSeq/create} with a keyword (not {@code :cloffle/op} on {@code #'map}).
 * {@code VectorKeywordMapFirst} fusion requires {@code RT/first} in the analyzed tree; seq
 * primitives are not rewritten to {@code RT} statics so {@code with-redefs} on {@code #'first}
 * is observed.
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

    /** Non-literal vector element blocks analyze-time map constant fold. */
    private static final String MAP_FIRST_ON_ROWS =
            "(first (map :id (vector {:id :one} {:id :two} (identity 0))))";

    @Test
    public void firstOnMapKeywordVectorUsesEvsAndVarInvoke() throws Exception {
        List<String> names = instructionNames(MAP_FIRST_ON_ROWS, "evsMapFirst");
        assertTrue("expected EphemeralVectorSeqKeywordCreate: " + names,
                names.stream().anyMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")));
        assertTrue("expected #'first via InvokeVar: " + names,
                names.stream().anyMatch(n -> n.endsWith("InvokeVar1")));
        assertTrue("VectorKeywordMapFirst needs RT/first rewrite: " + names,
                names.stream().noneMatch(n -> n.endsWith("VectorKeywordMapFirst")));
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
        List<String> names = instructionNamesCompileOnly(
                "(seq (map :status (vector {:status :ok} {:status :fail} (identity 0))))",
                "evsMapSeq");
        assertTrue("expected EphemeralVectorSeqKeywordCreate: " + names,
                names.stream().anyMatch(n -> n.contains("EphemeralVectorSeqKeywordCreate")));
    }
}
