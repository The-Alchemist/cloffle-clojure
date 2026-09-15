package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates tier-3 {@code :cloffle/unchecked-op} rewrite for {@code nth} → {@code RT.nth} analyze sites,
 * then {@link net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode.VectorNth2} /
 * {@link net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode.VectorNth3} bytecode lowering.
 */
public class NthCallSiteRewriteIntrospectionTest {

    @BeforeAll
    public static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "nthRewrite");
        root.getCallTarget().call();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull(bytecode);

        List<SpecializationInfo> all = new ArrayList<>();
        for (Instruction instruction : bytecode.getInstructions()) {
            String name = instruction.getName();
            if (!(name.endsWith(instructionSuffix)
                    || name.contains("." + instructionSuffix + "$")
                    || name.endsWith("." + instructionSuffix))) {
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

    @Test
    public void nthTwoArgByteCodeUsesVectorNth2() throws Exception {
        List<SpecializationInfo> specs = specializationsOf("(nth [1 2] 0)", "VectorNth2");
        assertFalse(specs.isEmpty(), "expected VectorNth2 for (nth coll i)");
    }

    @Test
    public void nthThreeArgByteCodeUsesVectorNth3() throws Exception {
        List<SpecializationInfo> specs = specializationsOf("(nth [1 2] 0 nil)", "VectorNth3");
        assertFalse(specs.isEmpty(), "expected VectorNth3 for (nth coll i nf)");
    }

    @Test
    public void nthDoesNotUseGenericStaticMethodInvoke() throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot("(nth [1 2] 0)", "nthRewrite");
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        assertTrue(names.stream().anyMatch(n -> n.endsWith("VectorNth2")
                        || n.contains(".VectorNth2$")), "RT.nth should lower to VectorNth, not StaticMethod: " + names);
        assertTrue(names.stream().noneMatch(n -> n.contains("StaticMethod2") && n.contains("nth")));
    }
}
