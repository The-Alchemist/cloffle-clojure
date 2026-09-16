package clojure.lang;

import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression: another test must not leave {@link RT#CURRENT_NS} root on an empty namespace and
 * break {@link BytecodeDslTestSupport} analyze (see {@code BytecodeRuntimeIntegrationTest} history).
 */
public class BytecodeDslAnalyzeIsolationTest {

    private static final Symbol BOOTSTRAP_NS =
            Symbol.intern("cloffle.bootstrap-runtime-integration");

    private Object previousNs;

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    @AfterEach
    void restoreUserNs() {
        if (previousNs != null) {
            RT.CURRENT_NS.bindRoot(previousNs);
            previousNs = null;
        }
    }

    @Test
    void compileRootExpressionResolvesCoreDespitePollutedCurrentNsRoot() throws Exception {
        previousNs = RT.CURRENT_NS.deref();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(BOOTSTRAP_NS));

        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRootExpression(
                "(first (map identity [1]))", "analyzeIsolation");
        assertNotNull(root.getBytecodeNode());
        root.getCallTarget().call();
    }
}
