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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates {@code :cloffle/op} Numbers lowerings under {@code :direct-linking}: binary {@code +} must
 * emit {@code NumbersAdd} with a live long/long specialization. Without the flag, calls stay Var invokes.
 */
public class BytecodeNumbersLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "numbersLowering");
            root.getCallTarget().call();
            BytecodeNode bytecode = root.getBytecodeNode();
            assertNotNull(bytecode, "Bytecode node must be materialized");

            List<SpecializationInfo> all = new ArrayList<>();
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
    void corePlusUsesNumbersAddLongLong() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(+ 1 2)", "NumbersAdd");
        assertActive(all, "doLongLong");
        Object v = BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(+ 1 2)");
        assertSame(Long.class, v.getClass());
        assertEquals(3L, v);
    }

    @Test
    @Tag("direct-linking-on")
    void coreLtUsesNumbersLt() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(< 1 2)", "NumbersLt");
        assertActive(all, "doLongLong");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(< 1 2)"));
    }

    @Test
    @Tag("direct-linking-on")
    void coreIncUsesNumbersInc() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(inc 41)", "NumbersInc");
        assertActive(all, "doLong");
        Object v = BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(inc 41)");
        assertSame(Long.class, v.getClass());
        assertEquals(42L, v);
    }

    @Test
    @Tag("direct-linking-off")
    void plusWithoutDirectLinkingDoesNotEmitNumbersAdd() throws Exception {
        BytecodeDslTestSupport.withDirectLinkingOff((java.util.concurrent.Callable<Void>) () -> {
            CloffleBytecodeRootNode root =
                    BytecodeDslTestSupport.compileRoot("(let [a 1] (+ a 2))", "numbersVar");
            root.getCallTarget().call();
            List<String> names = new ArrayList<>();
            boolean sawNumbersAdd = false;
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                String n = instruction.getName();
                names.add(n);
                if (n.contains("NumbersAdd")) {
                    sawNumbersAdd = true;
                }
            }
            assertFalse(sawNumbersAdd, () -> "NumbersAdd must not emit without :direct-linking; got " + names);
            return null;
        });
    }
}
