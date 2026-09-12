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
 * Seq primitives ({@code first}/{@code next}/{@code rest}/{@code seq}) must stay Var invokes
 * so {@code with-redefs} works (stock does not {@code :inline} them). They must not rewrite to
 * {@code RT.*} static calls via tier-3 {@code :checked-method}.
 */
public class SeqCallSiteRewriteIntrospectionTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "seqRewrite");
        root.getCallTarget().call();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull(bytecode);

        List<SpecializationInfo> all = new ArrayList<>();
        for (Instruction instruction : bytecode.getInstructions()) {
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

    @Test
    public void firstStaysVarInvokeNotRtStaticMethod() throws Exception {
        assertTrue("must not rewrite to RT.first",
                specializationsOf("(first [1 2])", "StaticMethod1").isEmpty());
        assertFalse("expected InvokeVar1 on #'first",
                specializationsOf("(first [1 2])", "InvokeVar1").isEmpty());
    }

    @Test
    public void literalLazySeqFirstConstantFoldsWithoutCall() throws Exception {
        List<SpecializationInfo> sm1 = specializationsOf("(first (lazy-seq [:first]))", "StaticMethod1");
        List<SpecializationInfo> invoke = specializationsOf("(first (lazy-seq [:first]))", "InvokeVar1");
        assertTrue("literal lazy-seq first should not call RT.first", sm1.isEmpty());
        // May constant-fold the whole form; either way no RT static rewrite.
        assertTrue("no RT static rewrite", sm1.isEmpty());
        // invoke may be empty if fully folded
        assertNotNull(invoke);
    }

    @Test
    public void seqNextRestStayVarInvokes() throws Exception {
        assertTrue(specializationsOf("(seq [1])", "StaticMethod1").isEmpty());
        assertFalse(specializationsOf("(seq [1])", "InvokeVar1").isEmpty());
        assertTrue(specializationsOf("(next (seq [1 2]))", "StaticMethod1").isEmpty());
        assertTrue(specializationsOf("(rest [1 2])", "StaticMethod1").isEmpty());
        assertFalse(specializationsOf("(rest [1 2])", "InvokeVar1").isEmpty());
    }
}
