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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Gates {@code :cloffle/op} Numbers lowerings under {@code :direct-linking}: binary {@code +} must
 * emit {@code NumbersAdd} with a live long/long specialization. Without the flag, calls stay Var invokes.
 */
public class BytecodeNumbersLoweringIntrospectionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingPerfProfile(() -> {
            CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "numbersLowering");
            root.getCallTarget().call();
            BytecodeNode bytecode = root.getBytecodeNode();
            assertNotNull("Bytecode node must be materialized", bytecode);

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
            assertFalse("No " + instructionSuffix + " for: " + form, all.isEmpty());
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
        assertNotNull(methodName + " must be present; found " + all, found);
        assertTrue(methodName + " must be live; found " + all, found.isActive());
    }

    @Test
    public void corePlusUsesNumbersAddLongLong() throws Exception {
        List<SpecializationInfo> all = specializationsOf("(+ 1 2)", "NumbersAdd");
        assertActive(all, "doLongLong");
        Object v = BytecodeDslTestSupport.withDirectLinkingPerfProfile(
                () -> BytecodeDslTestSupport.evalBytecode("(+ 1 2)"));
        assertSame(Long.class, v.getClass());
        assertEquals(3L, v);
    }

    @Test
    public void coreLtUsesNumbersLt() throws Exception {
        List<SpecializationInfo> all = specializationsOf("(< 1 2)", "NumbersLt");
        assertActive(all, "doLongLong");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.withDirectLinkingPerfProfile(
                () -> BytecodeDslTestSupport.evalBytecode("(< 1 2)")));
    }

    @Test
    public void coreIncUsesNumbersInc() throws Exception {
        List<SpecializationInfo> all = specializationsOf("(inc 41)", "NumbersInc");
        assertActive(all, "doLong");
        Object v = BytecodeDslTestSupport.withDirectLinkingPerfProfile(
                () -> BytecodeDslTestSupport.evalBytecode("(inc 41)"));
        assertSame(Long.class, v.getClass());
        assertEquals(42L, v);
    }

    @Test
    public void plusWithoutDirectLinkingDoesNotEmitNumbersAdd() throws Exception {
        // Non-literal args avoid analyze-time constant fold to ConstLong.
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(
                "(let [a 1] (+ a 2))", "numbersVar");
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
        assertFalse("NumbersAdd must not emit without :direct-linking; got " + names, sawNumbersAdd);
    }
}
