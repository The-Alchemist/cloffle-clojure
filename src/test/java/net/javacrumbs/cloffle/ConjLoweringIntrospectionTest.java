package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gates tier-2 {@code TupleConj} lowering for arity-2 {@code conj} on the tuple growth ladder.
 * Analyze-time literal {@code conj} folding is disabled so {@code with-redefs} on {@code #'conj}
 * is observed; literal chains still emit one {@code TupleConj} per arity-2 call.
 */
public class ConjLoweringIntrospectionTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> tupleConjSpecializations(String form) throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(form, "conjLowering");
        root.getCallTarget().call();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull(bytecode);

        List<SpecializationInfo> all = new ArrayList<>();
        int tupleConjSites = 0;
        for (Instruction instruction : bytecode.getInstructions()) {
            if (!instruction.getName().endsWith("TupleConj")) {
                continue;
            }
            tupleConjSites++;
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                    List<SpecializationInfo> info = argument.getSpecializationInfo();
                    if (info != null) {
                        all.addAll(info);
                    }
                }
            }
        }
        assertTrue("expected TupleConj instructions in: " + form, tupleConjSites > 0);
        return all;
    }

    @Test
    public void dynamicConjUsesTupleConjLowering() throws Exception {
        List<SpecializationInfo> specs = tupleConjSpecializations(
                "(conj [] (System/nanoTime))");
        assertFalse("expected live TupleConj specializations", specs.isEmpty());
    }

    @Test
    public void singleConjOntoLiteralTupleUsesTupleConj() throws Exception {
        List<SpecializationInfo> specs = tupleConjSpecializations(
                "(peek (conj [:v1 :v2] (System/nanoTime)))");
        assertFalse(specs.isEmpty());
    }

    @Test
    public void literalConjChainFromEmptyUsesTupleConjPerArity() throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(
                "(conj (conj (conj [] :v1) :v2) :v3)", "conjFold");
        int sites = 0;
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            if (instruction.getName().endsWith("TupleConj")) {
                sites++;
            }
        }
        assertEquals("literal conj chain should not constant-fold at analyze time", 3, sites);
    }
}
