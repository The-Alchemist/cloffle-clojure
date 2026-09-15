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
 * Gates {@code :cloffle/op :cloffle.op/UtilEquiv} on 2-arg {@code clojure.core/=} under {@code :direct-linking}.
 * Distinct from {@code ==} / {@code NumbersEquiv}.
 */
public class BytecodeUtilEquivLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    private static List<SpecializationInfo> specializationsDirectLinkingOn(String form, String instructionSuffix)
            throws Exception {
        return BytecodeDslTestSupport.withDirectLinkingOn(() -> {
            CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot(form, "utilEquivLowering");
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
    void coreEqualsKeywordsUsesUtilEquiv() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(= :a :a)", "UtilEquiv");
        assertActive(all, "doCheck");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(= :a :a)"));
        assertEquals(Boolean.FALSE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(= :a :b)"));
    }

    @Test
    @Tag("direct-linking-on")
    void coreEqualsLongsUsesUtilEquiv() throws Exception {
        List<SpecializationInfo> all = specializationsDirectLinkingOn("(= 1 1)", "UtilEquiv");
        assertActive(all, "doCheck");
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(= 1 1)"));
    }

    @Test
    @Tag("direct-linking-on")
    void coreEqualsLongAndIntegerStillTrue() throws Exception {
        // Util.equiv (not Numbers.equiv): boxed Long and Integer compare equal.
        assertEquals(Boolean.TRUE,
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(= 1 (int 1))"));
    }

    @Test
    @Tag("direct-linking-off")
    void equalsWithoutDirectLinkingDoesNotEmitUtilEquiv() throws Exception {
        BytecodeDslTestSupport.withDirectLinkingOff((java.util.concurrent.Callable<Void>) () -> {
            CloffleBytecodeRootNode root =
                    BytecodeDslTestSupport.compileRoot("(let [a :a] (= a :a))", "utilEquivVar");
            root.getCallTarget().call();
            List<String> names = new ArrayList<>();
            boolean sawUtilEquiv = false;
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                String n = instruction.getName();
                names.add(n);
                if (n.contains("UtilEquiv")) {
                    sawUtilEquiv = true;
                }
            }
            assertFalse(sawUtilEquiv, () -> "UtilEquiv must not emit without :direct-linking; got " + names);
            return null;
        });
    }
}
