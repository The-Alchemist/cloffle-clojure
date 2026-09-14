package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates tier-2 {@code TupleConj} lowering for arity-2 {@code conj} under {@code :direct-linking}.
 * Analyze-time literal {@code conj} folding stays disabled; with the flag, literal chains emit
 * one {@code TupleConj} per arity-2 call.
 */
public class ConjLoweringIntrospectionTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> tupleConjSpecializations(String form) throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
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
            assertTrue(tupleConjSites > 0, () -> "expected TupleConj instructions in: " + form);
            return all;
        });
    }

    @Test
    @Tag("direct-linking-on")
    void dynamicConjUsesTupleConjLowering() throws Exception {
        List<SpecializationInfo> specs = tupleConjSpecializations("(conj [] (System/nanoTime))");
        assertFalse(specs.isEmpty(), "expected live TupleConj specializations");
    }

    @Test
    @Tag("direct-linking-on")
    void singleConjOntoLiteralTupleUsesTupleConj() throws Exception {
        List<SpecializationInfo> specs =
                tupleConjSpecializations("(peek (conj [:v1 :v2] (System/nanoTime)))");
        assertFalse(specs.isEmpty());
    }

    @Test
    @Tag("direct-linking-on")
    void literalConjChainFromEmptyUsesTupleConjPerArity() throws Exception {
        int sites = BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(
                    "(conj (conj (conj [] :v1) :v2) :v3)", "conjFold");
            int n = 0;
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                if (instruction.getName().endsWith("TupleConj")) {
                    n++;
                }
            }
            return n;
        });
        assertEquals(3, sites, "literal conj chain should not constant-fold at analyze time");
    }
}
