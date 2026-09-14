package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Cloffle {@code :direct-linking}: eligible Var call sites pin the analyze-time
 * {@code IFn}/{@code ClojureClosure} root into {@code InvokePinned*} so {@code with-redefs}
 * does not rewire that site.
 */
public class DirectLinkingPinnedInvokeTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
        RT.CURRENT_NS.bindRoot(clojure.lang.Namespace.findOrCreate(Symbol.intern("user")));
    }

    private static List<String> instructionNames(CloffleBytecodeRootNode root) {
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    @Test
    public void withoutDirectLinkingUsesInvokeVar() throws Exception {
        BytecodeDslTestSupport.evalBytecode(
                "(def dl-plain (fn [x] (str \"plain-\" x)))");
        CloffleBytecodeRootNode root =
                BytecodeDslTestSupport.compileRootExpression("(dl-plain :a)", "dlPlain");
        List<String> names = instructionNames(root);
        assertTrue("expected InvokeVar without direct-linking: " + names,
                names.stream().anyMatch(n -> n.contains("InvokeVar")));
        assertTrue("must not pin without flag: " + names,
                names.stream().noneMatch(n -> n.contains("InvokePinned")));
        assertEquals("plain-:a", root.getCallTarget().call());
    }

    @Test
    public void withDirectLinkingEmitsInvokePinned() throws Exception {
        BytecodeDslTestSupport.evalBytecode(
                "(def dl-pinned (fn [x] (str \"pinned-\" x)))");
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.withDirectLinking(
                () -> BytecodeDslTestSupport.compileRootExpression("(dl-pinned :b)", "dlPinned"));
        List<String> names = instructionNames(root);
        assertTrue("expected InvokePinned with direct-linking: " + names,
                names.stream().anyMatch(n -> n.contains("InvokePinned")));
        assertTrue("must not use InvokeVar when pinned: " + names,
                names.stream().noneMatch(n -> n.contains("InvokeVar")));
        assertEquals("pinned-:b", root.getCallTarget().call());
    }

    @Test
    public void pinnedCallSiteIgnoresWithRedefs() throws Exception {
        BytecodeDslTestSupport.evalBytecode(
                "(def dl-freeze (fn [x] (str \"orig-\" x)))");
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.withDirectLinking(
                () -> BytecodeDslTestSupport.compileRootExpression("(dl-freeze :c)", "dlFreeze"));
        assertEquals("orig-:c", root.getCallTarget().call());

        Object mocked = BytecodeDslTestSupport.evalBytecode(
                "(with-redefs [dl-freeze (fn [x] (str \"mock-\" x))] (dl-freeze :c))");
        assertEquals("mock-:c", mocked);

        // Already-compiled pinned site keeps the original root.
        assertEquals("orig-:c", root.getCallTarget().call());
    }

    @Test
    public void withoutDirectLinkingHonorsWithRedefsOnCompiledSite() throws Exception {
        BytecodeDslTestSupport.evalBytecode(
                "(def dl-varpath (fn [x] (str \"orig-\" x)))");
        CloffleBytecodeRootNode root =
                BytecodeDslTestSupport.compileRootExpression("(dl-varpath :d)", "dlVarpath");
        assertEquals("orig-:d", root.getCallTarget().call());

        Var v = (Var) RT.var("user", "dl-varpath");
        Object old = v.getRawRoot();
        try {
            v.bindRoot(BytecodeDslTestSupport.evalBytecode("(fn [x] (str \"mock-\" x))"));
            assertEquals("mock-:d", root.getCallTarget().call());
        } finally {
            v.bindRoot(old);
        }
    }

    @Test
    public void redefMetaSkipsPinningUnderDirectLinking() throws Exception {
        BytecodeDslTestSupport.evalBytecode(
                "(def ^:redef dl-redefable (fn [x] (str \"orig-\" x)))");
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.withDirectLinking(
                () -> BytecodeDslTestSupport.compileRootExpression(
                        "(dl-redefable :e)", "dlRedef"));
        List<String> names = instructionNames(root);
        assertTrue("^:redef must stay on InvokeVar: " + names,
                names.stream().anyMatch(n -> n.contains("InvokeVar")));
        assertTrue("^:redef must not pin: " + names,
                names.stream().noneMatch(n -> n.contains("InvokePinned")));

        assertEquals("orig-:e", root.getCallTarget().call());
        Var v = (Var) RT.var("user", "dl-redefable");
        Object old = v.getRawRoot();
        try {
            v.bindRoot(BytecodeDslTestSupport.evalBytecode("(fn [x] (str \"mock-\" x))"));
            assertEquals("mock-:e", root.getCallTarget().call());
        } finally {
            v.bindRoot(old);
        }
    }

    @Test
    public void deftypeUnderDirectLinkingDoesNotNeedPinnedAsm() throws Exception {
        // NewInstanceExpr method bodies still emit JVM ASM; pinning would throw
        // "Can't emit ASM for Cloffle pinned StaticInvokeExpr" (e.g. clojure.gvec).
        Object ok = BytecodeDslTestSupport.withDirectLinking(
                () -> BytecodeDslTestSupport.evalBytecode(
                        "(do (deftype DlAsmSafe [x] Object (toString [this] (str \"box:\" x))) true)"));
        assertEquals(Boolean.TRUE, ok);
    }
}
