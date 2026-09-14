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
 * Seq primitives ({@code first}/{@code next}/{@code rest}/{@code seq}) must stay Var invokes
 * so {@code with-redefs} works (stock does not {@code :inline} them). They must not rewrite to
 * {@code RT.*} static calls via tier-3 {@code :checked-method}.
 */
public class SeqCallSiteRewriteIntrospectionTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOff(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOff(
                () -> collectSpecializations(form, instructionSuffix));
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(
                () -> collectSpecializations(form, instructionSuffix));
    }

    private static List<SpecializationInfo> collectSpecializations(String form, String instructionSuffix)
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
    @Tag("direct-linking-off")
    void firstStaysVarInvokeNotRtStaticMethod() throws Exception {
        assertTrue(specializationsDirectLinkingOff("(first [1 2])", "StaticMethod1").isEmpty(),
                "must not rewrite to RT.first");
        assertFalse(specializationsDirectLinkingOff("(first [1 2])", "InvokeVar1").isEmpty(),
                "expected InvokeVar1 on #'first");
    }

    @Test
    @Tag("direct-linking-off")
    void literalLazySeqFirstStaysVarInvokeByDefault() throws Exception {
        List<SpecializationInfo> sm1 =
                specializationsDirectLinkingOff("(first (lazy-seq [:first]))", "StaticMethod1");
        List<SpecializationInfo> invoke =
                specializationsDirectLinkingOff("(first (lazy-seq [:first]))", "InvokeVar1");
        assertTrue(sm1.isEmpty(), "literal lazy-seq first must not call RT.first");
        assertFalse(invoke.isEmpty(), "expected InvokeVar1 when direct linking off");
    }

    @Test
    @Tag("direct-linking-on")
    void literalLazySeqFirstConstantFoldsWithLockedRewrites() throws Exception {
        List<SpecializationInfo> sm1 =
                specializationsDirectLinkingOn("(first (lazy-seq [:first]))", "StaticMethod1");
        List<SpecializationInfo> invoke =
                specializationsDirectLinkingOn("(first (lazy-seq [:first]))", "InvokeVar1");
        assertTrue(sm1.isEmpty(), "literal lazy-seq first should not call RT.first");
        assertNotNull(invoke);
    }

    @Test
    @Tag("direct-linking-off")
    void seqNextRestStayVarInvokes() throws Exception {
        assertTrue(specializationsDirectLinkingOff("(seq [1])", "StaticMethod1").isEmpty());
        assertFalse(specializationsDirectLinkingOff("(seq [1])", "InvokeVar1").isEmpty());
        assertTrue(specializationsDirectLinkingOff("(next (seq [1 2]))", "StaticMethod1").isEmpty());
        assertTrue(specializationsDirectLinkingOff("(rest [1 2])", "StaticMethod1").isEmpty());
        assertFalse(specializationsDirectLinkingOff("(rest [1 2])", "InvokeVar1").isEmpty());
    }
}
