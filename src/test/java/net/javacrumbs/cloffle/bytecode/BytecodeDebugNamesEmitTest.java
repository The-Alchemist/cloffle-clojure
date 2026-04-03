package net.javacrumbs.cloffle.bytecode;

import clojure.lang.BytecodeDslTestSupport;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Ensures parameter / closure-copy symbols are recorded for debugger scope (see {@link BytecodeLocalScope}).
 */
public class BytecodeDebugNamesEmitTest {

    @Test
    public void topLevelFnRegistersParamDebugNames() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes("(fn* ([a b] a))", "t");
        boolean foundA = false;
        boolean foundB = false;
        for (int i = 0; i < nodes.count(); i++) {
            CloffleBytecodeRootNode n = nodes.getNode(i);
            Map<Integer, String> m = n.getBytecodeLocalOffsetDebugNames();
            if (m.isEmpty()) {
                continue;
            }
            if (m.containsValue("a")) {
                foundA = true;
            }
            if (m.containsValue("b")) {
                foundB = true;
            }
        }
        assertTrue("expected debug name 'a' on some bytecode root", foundA);
        assertTrue("expected debug name 'b' on some bytecode root", foundB);
    }
}
