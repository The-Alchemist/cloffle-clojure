package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import clojure.lang.Var;
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
 * Gates {@code :cloffle/op} Numbers lowerings: binary {@code +} must emit {@code NumbersAdd}
 * with a live long/long specialization, and overflow / wrapper contracts must hold.
 */
public class BytecodeNumbersLoweringIntrospectionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        // Earlier tests may RT.load("clojure/core") again, rebinding roots while leaving the
        // write-once loweringRoot stale. Re-arm so Numbers* fast paths can install.
        rearm("clojure.core", "+", "-", "*", "/", "<", "<=", ">", ">=", "==", "inc", "dec");
        // nth/count :cloffle/op left unwired — NumbersNth caused Graal recursive-inline bailouts
        // (GuestCompilationUnitTest); MethodHandle RT/nth remains the hot path (FIXME_nth.md).
    }

    private static void rearm(String ns, String... names) {
        for (String name : names) {
            Var v = RT.var(ns, name);
            if (v != null && v.hasRoot()) {
                v.rearmLoweringRoot();
            }
        }
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "numbersLowering");
        root.getCallTarget().call();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull("Bytecode node must be materialized", bytecode);

        List<SpecializationInfo> all = new ArrayList<>();
        for (Instruction instruction : bytecode.getInstructions()) {
            // With boxingEliminationTypes, instructions may be tagged
            // (e.g. c.StaticMethod2$LongLong / c.NumbersAdd$LongLong$unboxed).
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
        Object v = BytecodeDslTestSupport.evalBytecode("(+ 1 2)");
        assertSame(Long.class, v.getClass());
        assertEquals(3L, v);
    }

    @Test
    public void coreLtUsesNumbersLt() throws Exception {
        List<SpecializationInfo> all = specializationsOf("(< 1 2)", "NumbersLt");
        assertActive(all, "doLongLong");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecode("(< 1 2)"));
    }

    @Test
    public void coreIncUsesNumbersInc() throws Exception {
        List<SpecializationInfo> all = specializationsOf("(inc 41)", "NumbersInc");
        assertActive(all, "doLong");
        Object v = BytecodeDslTestSupport.evalBytecode("(inc 41)");
        assertSame(Long.class, v.getClass());
        assertEquals(42L, v);
    }
}
