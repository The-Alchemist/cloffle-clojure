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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gates tier-3 {@code :cloffle/unchecked-op} rewrite for {@code into} → {@code RT.into} analyze sites.
 */
public class IntoCallSiteRewriteIntrospectionTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "intoRewrite");
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
    public void intoTwoArgRewritesToRtStaticMethod() throws Exception {
        String form = "(into [] (vector 1 (identity 2)))";
        assertFalse("expected StaticMethod2 for non-constant vector call",
                specializationsOf(form, "StaticMethod2").isEmpty());
    }

    @Test
    public void intoEmptyLiteralVectorConstantFoldsWithoutRtIntoBytecode() throws Exception {
        assertTrue("literal (into [] [1 2]) should not emit RT.into StaticMethod2",
                specializationsOf("(into [] [1 2])", "StaticMethod2").isEmpty());
    }

    @Test
    public void intoDoesNotUseInvokeVarOnTwoArgForm() throws Exception {
        assertFalse(specializationsOf(
                "(into [] (vector 1 (identity 2)))", "StaticMethod2").isEmpty());
    }
}
