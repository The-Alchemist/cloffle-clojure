package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gates {@link ExprToBytecode} lowering for analyze-time
 * {@code EphemeralVectorSeq/create} with a keyword (not {@code :cloffle/op} on {@code #'map}).
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

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = compileAndWarm(form, "evsLowering");
        List<SpecializationInfo> all = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            if (!instruction.getName().endsWith(instructionSuffix)) {
                continue;
            }
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                    List<SpecializationInfo> info = argument.getSpecializationInfo();
                    if (info != null) {
                        all.addAll(info);
                    }
                }
            }
        }
        return all;
    }

    /** Non-literal vector element blocks analyze-time map constant fold. */
    private static final String MAP_FIRST_ON_ROWS =
            "(first (map :id (vector {:id :one} {:id :two} (identity 0))))";

    @Test
    public void firstMapKeywordOnVectorLowersToVectorKeywordMapFirst() throws Exception {
        List<String> names = instructionNames(MAP_FIRST_ON_ROWS, "evsMapFirst");
        assertTrue("expected VectorKeywordMapFirst: " + names,
                names.stream().anyMatch(n -> n.endsWith("VectorKeywordMapFirst")));
        assertTrue(names.stream().noneMatch(n -> n.contains("StaticMethod3")
                && n.contains("EphemeralVectorSeq")));
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
                names.stream().anyMatch(n -> n.endsWith("EphemeralVectorSeqKeywordCreate")));
    }

    @Test
    public void vectorKeywordMapFirstSpecializes() throws Exception {
        List<SpecializationInfo> specs = specializationsOf(MAP_FIRST_ON_ROWS, "VectorKeywordMapFirst");
        assertFalse("expected VectorKeywordMapFirst specializations", specs.isEmpty());
    }
}
