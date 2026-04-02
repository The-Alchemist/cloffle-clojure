package net.javacrumbs.cloffle.compiler;

import clojure.lang.RT;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Loads {@code clojure/core.clj} through {@link CloffleCompiler#compile} on the default bytecode backend
 * ({@code RT.init()} → {@link RT#load(String)}). Fails on the first form that breaks the bytecode path.
 */
public class CoreBytecodeLoadSmokeTest {

    @Test
    public void rtInitLoadsCoreCljWithBytecodeExecution() {
        System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        try {
            assertTrue(CloffleCompiler.useBytecodeExecution());
            RT.init();
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }
}
