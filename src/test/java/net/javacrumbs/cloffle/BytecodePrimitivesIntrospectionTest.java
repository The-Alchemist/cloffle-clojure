package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.bytecode.BytecodeStaticMethod;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gates primitive transport: {@code ConstLong} / {@code StaticMethod2} long specializations must
 * actually be emitted and live. Result-only tests stay green on the generic Object path.
 */
public class BytecodePrimitivesIntrospectionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsOf(String form, String instructionSuffix)
            throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "introspectRoot");
        // Execute so specializations activate.
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
        assertFalse(
                "No " + instructionSuffix + " instruction for: " + form,
                all.isEmpty());
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
    public void constLongIsEmittedForIntegerLiteral() throws Exception {
        List<SpecializationInfo> specs = specializationsOf("42", "ConstLong");
        assertActive(specs, "doLong");
    }

    @Test
    public void staticMethodLongLongIsLiveForNumbersAdd() throws Exception {
        String form = "(clojure.lang.Numbers/add 1 2)";
        List<SpecializationInfo> specs = specializationsOf(form, "StaticMethod2");
        assertActive(specs, "doLongLong");
    }

    @Test
    public void staticMethodIntReturnIsLiveForRtCount() throws Exception {
        String form =
                "(clojure.lang.RT/count (clojure.lang.RT/conj (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY 1) 2))";
        List<SpecializationInfo> specs = specializationsOf(form, "StaticMethod1");
        assertActive(specs, "doIntReturn");
    }

    @Test
    public void primitiveShapeGuardsRemainVisibleToPartialEvaluation() throws Exception {
        String[] guards = {
                "isLong0", "isDouble0", "isInt0",
                "isLong1", "isDouble1", "isInt1", "isIntReturn1",
                "isLongLong2", "isDoubleDouble2", "isIntInt2",
                "isObjectInt2", "isBoolLongLong2", "isBoolDoubleDouble2"
        };
        for (String guard : guards) {
            assertNull(
                    guard + " must not cross a Truffle boundary on every primitive operation",
                    BytecodeStaticMethod.class
                            .getMethod(guard, Object.class)
                            .getAnnotation(TruffleBoundary.class));
        }
    }

    @Test
    public void staticFieldUsesCachedGetter() throws Exception {
        List<SpecializationInfo> specs = specializationsOf("Long/MAX_VALUE", "StaticField");
        assertActive(specs, "doCached");
    }
}
