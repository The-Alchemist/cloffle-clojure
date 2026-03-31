package net.javacrumbs.cloffle.compiler;

import clojure.lang.RT;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

/**
 * <b>Real-load</b> bytecode coverage: after {@link RT#init()}, {@link CloffleCompiler#compile} runs the same
 * {@code Compiler.load} pipeline as production, so {@code require} / {@code ns} macro expansions exercise
 * {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} with whatever {@link clojure.lang.Compiler.Expr} shapes
 * {@code clojure.core} actually produces.
 * <p>
 * Gated by {@code -Dcoffle.test.require-ns=true} so default {@code run-bytecode-dsl-tests} stays fast (bootstrapping
 * {@code clojure.core} is expensive). Enable for local/CI jobs that chase {@code require} parity on the bytecode path.
 */
public class RequireNsBytecodeIntegrationTest {

    private static final String ENABLE_PROPERTY = "coffle.test.require-ns";

    @BeforeClass
    public static void initRtAndRequirePath() {
        assumeTrue(
                "set -D" + ENABLE_PROPERTY + "=true to run (loads clojure.core via RT.init)",
                Boolean.getBoolean(ENABLE_PROPERTY));
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try {
            RT.init();
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }

    @Test
    public void requireClojureStringBytecodeReturnsNil() throws Exception {
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try {
            Object last =
                    CloffleCompiler.compile(
                            new StringReader("(require 'clojure.string)"),
                            "require_ns_test.clj",
                            "require_ns_test.clj");
            assertNull(last);
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }
}
