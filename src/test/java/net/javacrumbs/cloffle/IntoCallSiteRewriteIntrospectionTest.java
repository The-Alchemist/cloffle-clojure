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
 * {@code into} must stay a Var invoke by default so {@code with-redefs} matches stock.
 * Analyze-time constant folds require {@code :locked-call-site-rewrites}.
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

    private static List<SpecializationInfo> specializationsWithLockedFolds(
            String form, String instructionSuffix) throws Exception {
        return BytecodeDslTestSupport.withLockedCallSiteRewrites(
                () -> specializationsOf(form, instructionSuffix));
    }

    @Test
    public void intoTwoArgStaysVarInvokeByDefault() throws Exception {
        String form = "(into [] (vector 1 (identity 2)))";
        assertTrue("must not rewrite to RT.into StaticMethod2 by default",
                specializationsOf(form, "StaticMethod2").isEmpty());
        assertFalse("expected InvokeVar2 on #'into",
                specializationsOf(form, "InvokeVar2").isEmpty());
    }

    @Test
    public void intoEmptyLiteralVectorDoesNotConstantFoldByDefault() throws Exception {
        // Without locked folds, literal (into [] [1 2]) still Var-invokes #'into.
        assertFalse("expected InvokeVar2 when locked folds off",
                specializationsOf("(into [] [1 2])", "InvokeVar2").isEmpty());
    }

    @Test
    public void intoEmptyLiteralVectorConstantFoldsWithLockedRewrites() throws Exception {
        assertTrue("literal (into [] [1 2]) should not emit RT.into StaticMethod2",
                specializationsWithLockedFolds("(into [] [1 2])", "StaticMethod2").isEmpty());
        assertTrue("literal into should constant-fold (no InvokeVar2)",
                specializationsWithLockedFolds("(into [] [1 2])", "InvokeVar2").isEmpty());
    }
}
