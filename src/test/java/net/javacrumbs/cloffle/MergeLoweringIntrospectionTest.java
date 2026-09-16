package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.benchmark.ClojureClasspathResources;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
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
 * Gates {@code :cloffle/op ShapeMapMerge} and map-literal → {@code KeywordAssoc} unrolling for
 * {@code clojure.core/merge}.
 */
public class MergeLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    private static Value evalWithDirectLinking(Context context, String code) {
        try {
            return BytecodeDslTestSupport.withDirectLinkingOn(() -> context.eval("cloffle", code));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Context createContext() {
        return Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .option("engine.BackgroundCompilation", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .build();
    }

    private static String guestSource(String name) {
        return ClojureClasspathResources.read("guest-compilation/" + name + ".clj");
    }

    private static void loadGuest(Context context) {
        evalWithDirectLinking(context, guestSource("merge-lowering"));
    }

    private static List<SpecializationInfo> specializationsOf(
            String namespace, String fnName, String instructionSuffix) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        assertNotNull(var, "Var must exist: " + namespace + "/" + fnName);
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull(bytecode, "Bytecode node must be materialized");

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
        assertFalse(all.isEmpty(),
                "No " + instructionSuffix + " instruction was emitted for " + namespace + "/" + fnName
                        + " — the :cloffle/op lowering did not fire at all");
        return all;
    }

    private static boolean hasInstruction(String namespace, String fnName, String instructionSuffix) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            if (instruction.getName().endsWith(instructionSuffix)) {
                return true;
            }
        }
        return false;
    }

    private static SpecializationInfo find(List<SpecializationInfo> all, String methodName) {
        for (SpecializationInfo info : all) {
            if (info.getMethodName().equals(methodName)) {
                return info;
            }
        }
        return null;
    }

    private static void assertActive(List<SpecializationInfo> all, String methodName) {
        SpecializationInfo info = find(all, methodName);
        assertNotNull(info, methodName + " must be present; found " + all);
        assertTrue(info.isActive(), methodName + " must be the live specialization; found " + all);
    }

    @Test
    @Tag("direct-linking-on")
    void mergeLiteralUnrollsToKeywordAssoc() {
        try (Context context = createContext()) {
            loadGuest(context);
            Value fn = evalWithDirectLinking(context, "test.guest.merge-lowering/merge-literal");
            assertEquals(200, fn.execute(context.eval("cloffle", "{:a 1}")).asInt());

            assertTrue(hasInstruction("test.guest.merge-lowering", "merge-literal", "KeywordAssoc"),
                    "literal RHS merge must unroll to KeywordAssoc");
            assertFalse(hasInstruction("test.guest.merge-lowering", "merge-literal", "ShapeMapMerge"),
                    "literal RHS must not emit ShapeMapMerge");

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.merge-lowering", "merge-literal", "KeywordAssoc");
            assertActive(all, "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void mergeRuntimeUsesShapeMapMergeTransition() {
        try (Context context = createContext()) {
            loadGuest(context);
            Value fn = evalWithDirectLinking(context, "test.guest.merge-lowering/merge-runtime");
            for (int i = 0; i < 10; i++) {
                Value result = fn.execute(
                        context.eval("cloffle", "{:id 1 :role :admin}"),
                        context.eval("cloffle", "{:role :user :active true}"));
                assertEquals("user", result.asString());
            }

            assertTrue(hasInstruction("test.guest.merge-lowering", "merge-runtime", "ShapeMapMerge"));
            List<SpecializationInfo> all =
                    specializationsOf("test.guest.merge-lowering", "merge-runtime", "ShapeMapMerge");
            assertActive(all, "doShapeMapShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void mergeNilLeftUsesNullSpecialization() {
        try (Context context = createContext()) {
            loadGuest(context);
            Value fn = evalWithDirectLinking(context, "test.guest.merge-lowering/merge-nil-left");
            assertEquals(1, fn.execute(context.eval("cloffle", "{:a 1}")).asInt());

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.merge-lowering", "merge-nil-left", "ShapeMapMerge");
            assertActive(all, "doNullLeft");
        }
    }
}
