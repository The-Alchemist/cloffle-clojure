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
 * Gates {@code :cloffle/op :IsSome} on 1-arg {@code clojure.core/some?} under {@code :direct-linking}.
 */
public class BytecodeIsSomeLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            var roots = BytecodeDslTestSupport.compileRootNodes(form, "isSomeLowering");
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
    void coreSomeUsesIsSome() throws Exception {
        List<SpecializationInfo> all =
                specializationsDirectLinkingOn("((fn [x] (some? x)) :a)", "IsSome");
        assertActive(all, "doCheck");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(some? :a)"));
        assertEquals(Boolean.FALSE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(some? nil)"));
    }

    @Test
    @Tag("direct-linking-off")
    void someWithoutDirectLinkingDoesNotEmitIsSome() throws Exception {
        BytecodeDslTestSupport.withDirectLinkingOff((java.util.concurrent.Callable<Void>) () -> {
            CloffleBytecodeRootNode root =
                    BytecodeDslTestSupport.compileRoot("((fn [x] (some? x)) :a)", "isSomeVar");
            root.getCallTarget().call();
            List<String> names = new ArrayList<>();
            boolean sawIsSome = false;
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                String n = instruction.getName();
                names.add(n);
                if (n.contains("IsSome")) {
                    sawIsSome = true;
                }
            }
            assertFalse(sawIsSome, () -> "IsSome must not emit without :direct-linking; got " + names);
            return null;
        });
    }
}
