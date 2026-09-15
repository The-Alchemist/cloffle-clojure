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
 * Gates {@code :cloffle/op :cloffle.op/IsNil} on 1-arg {@code clojure.core/nil?} under {@code :direct-linking}.
 */
public class BytecodeIsNilLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            var roots = BytecodeDslTestSupport.compileRootNodes(form, "isNilLowering");
            roots.getNode(0).getCallTarget().call();

            List<SpecializationInfo> all = new ArrayList<>();
            for (int i = 0; i < roots.count(); i++) {
                CloffleBytecodeRootNode root = roots.getNode(i);
                BytecodeNode bytecode = root.getBytecodeNode();
                assertNotNull(bytecode, "Bytecode node must be materialized");
                for (Instruction instruction : bytecode.getInstructions()) {
                    String iname = instruction.getName();
                    if (!(iname.endsWith(instructionSuffix)
                            || iname.contains("." + instructionSuffix + "$")
                            || iname.endsWith("." + instructionSuffix))) {
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
            }
            assertFalse(all.isEmpty(), () -> "No " + instructionSuffix + " for: " + form);
            return all;
        });
    }

    private static void assertActive(List<SpecializationInfo> all, String methodName) {
        SpecializationInfo found = null;
        for (SpecializationInfo info : all) {
            if (info.getMethodName().equals(methodName)) {
                found = info;
                break;
            }
        }
        assertNotNull(found, () -> methodName + " must be present; found " + all);
        assertTrue(found.isActive(), () -> methodName + " must be live; found " + all);
    }

    @Test
    @Tag("direct-linking-on")
    void coreNilUsesIsNil() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(nil? nil)", "IsNil");
        assertActive(all, "doCheck");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(nil? nil)"));
        assertEquals(Boolean.FALSE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(nil? :a)"));
    }

    @Test
    @Tag("direct-linking-on")
    void coreNilDynamicLocalUsesIsNil() throws Exception {
        // Avoid literal nil binding — constant-folds away IsNil.
        List<SpecializationInfo> all =
                specializationsDirectLinkingOn("((fn [x] (nil? x)) (identity nil))", "IsNil");
        assertActive(all, "doCheck");
        assertEquals(Boolean.TRUE,
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("((fn [x] (nil? x)) (identity nil))"));
        assertEquals(Boolean.FALSE,
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("((fn [x] (nil? x)) 1)"));
    }

    @Test
    @Tag("direct-linking-off")
    void nilWithoutDirectLinkingDoesNotEmitIsNil() throws Exception {
        BytecodeDslTestSupport.withDirectLinkingOff((java.util.concurrent.Callable<Void>) () -> {
            CloffleBytecodeRootNode root =
                    BytecodeDslTestSupport.compileRoot("(let [x nil] (nil? x))", "isNilVar");
            root.getCallTarget().call();
            List<String> names = new ArrayList<>();
            boolean sawIsNil = false;
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                String n = instruction.getName();
                names.add(n);
                if (n.contains("IsNil")) {
                    sawIsNil = true;
                }
            }
            assertFalse(sawIsNil, () -> "IsNil must not emit without :direct-linking; got " + names);
            return null;
        });
    }

    @Test
    @Tag("direct-linking-on")
    void someCallSiteUsesIsSomeNotInvokeVar() throws Exception {
        BytecodeDslTestSupport.withDirectLinkingOn((java.util.concurrent.Callable<Void>) () -> {
            var roots = BytecodeDslTestSupport.compileRootNodes("((fn [x] (some? x)) :a)", "someProbe");
            roots.getNode(0).getCallTarget().call();
            List<String> names = new ArrayList<>();
            boolean sawInvokeVar = false;
            boolean sawIsSome = false;
            for (int i = 0; i < roots.count(); i++) {
                for (Instruction instruction : roots.getNode(i).getBytecodeNode().getInstructions()) {
                    String n = instruction.getName();
                    names.add(n);
                    if (n.contains("InvokeVar")) {
                        sawInvokeVar = true;
                    }
                    if (n.contains("IsSome")) {
                        sawIsSome = true;
                    }
                }
            }
            assertTrue(sawIsSome, () -> "expected IsSome for some? call site; got " + names);
            assertFalse(sawInvokeVar, () -> "some? must not remain InvokeVar; got " + names);
            return null;
        });
    }
}
