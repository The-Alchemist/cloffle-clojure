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
 * Gates tier-3 {@code :cloffle/unchecked-op} rewrites for seq ops → {@code RT} static calls.
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
    public void firstRewritesToRtStaticMethod() throws Exception {
        assertFalse(specializationsOf("(first [1 2])", "StaticMethod1").isEmpty());
    }

    @Test
    public void lazySeqFirstShapeUsesRtFirstNotInvokeVar() throws Exception {
        List<SpecializationInfo> sm1 = specializationsOf("(first (lazy-seq [:first]))", "StaticMethod1");
        List<SpecializationInfo> invoke = specializationsOf("(first (lazy-seq [:first]))", "InvokeVar1");
        assertFalse("expected StaticMethod1 for (first (lazy-seq ...))", sm1.isEmpty());
        assertTrue("expected no InvokeVar1 on #'first", invoke.isEmpty());
    }

    @Test
    public void seqAndNextRewriteToRtStaticMethod() throws Exception {
        assertFalse(specializationsOf("(seq [1])", "StaticMethod1").isEmpty());
        assertFalse(specializationsOf("(next (seq [1 2]))", "StaticMethod1").isEmpty());
        assertFalse(specializationsOf("(rest [1 2])", "StaticMethod1").isEmpty());
    }
}
