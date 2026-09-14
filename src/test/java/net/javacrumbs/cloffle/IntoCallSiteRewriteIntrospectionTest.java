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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code into} must stay a Var invoke by default so {@code with-redefs} matches stock.
 * Analyze-time constant folds require direct linking on.
 */
public class IntoCallSiteRewriteIntrospectionTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOff(
                () -> collectSpecializations(form, instructionSuffix, "intoRewrite"));
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(
                () -> collectSpecializations(form, instructionSuffix, "intoRewrite"));
    }

    private static List<SpecializationInfo> collectSpecializations(
            String form, String instructionSuffix, String rootName) throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, rootName);
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
    @Tag("direct-linking-off")
    void intoTwoArgStaysVarInvokeByDefault() throws Exception {
        String form = "(into [] (vector 1 (identity 2)))";
        assertTrue(specializationsOf(form, "StaticMethod2").isEmpty(),
                "must not rewrite to RT.into StaticMethod2 by default");
        assertFalse(specializationsOf(form, "InvokeVar2").isEmpty(),
                "expected InvokeVar2 on #'into");
    }

    @Test
    @Tag("direct-linking-off")
    void intoEmptyLiteralVectorDoesNotConstantFoldByDefault() throws Exception {
        assertFalse(specializationsOf("(into [] [1 2])", "InvokeVar2").isEmpty(),
                "expected InvokeVar2 when direct linking off");
    }

    @Test
    @Tag("direct-linking-on")
    void intoEmptyLiteralVectorConstantFoldsWithLockedRewrites() throws Exception {
        assertTrue(specializationsDirectLinkingOn("(into [] [1 2])", "StaticMethod2").isEmpty(),
                "literal (into [] [1 2]) should not emit RT.into StaticMethod2");
        assertTrue(specializationsDirectLinkingOn("(into [] [1 2])", "InvokeVar2").isEmpty(),
                "literal into should constant-fold (no InvokeVar2)");
    }
}
