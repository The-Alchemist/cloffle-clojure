package net.javacrumbs.cloffle.compiler;

import clojure.lang.RT;
import org.junit.Test;

/**
 * Loads {@code clojure/core.clj} through {@link CloffleCompiler#compile} with
 * {@link CloffleCompiler#EXECUTION_PROPERTY}={@link CloffleCompiler#EXECUTION_BYTECODE} ({@code RT.init()} →
 * {@link RT#load(String)}). Fails on the first form that breaks the bytecode path — fix and extend until green.
 */
public class CoreBytecodeLoadSmokeTest {

    @Test
    public void rtInitLoadsCoreCljWithBytecodeExecution() {
        System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try {
            RT.init();
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }
}
