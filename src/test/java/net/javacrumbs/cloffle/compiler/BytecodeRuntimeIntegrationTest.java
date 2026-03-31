package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runtime integration: {@link CloffleCompiler#compile} with {@code -Dcloffle.execution=bytecode} over a multi-form
 * script (classpath {@code /cloffle/bootstrap_slice.clj}). Matches the {@code Compiler.load} pipeline (analyze →
 * execute) without loading {@code clojure.core} first — same constraint as {@link clojure.lang.ExprToBytecodeTest}.
 * <p>
 * We only bind {@link RT#CURRENT_NS} to {@code user}; we do not call {@link RT#init()} (that requires
 * {@code clojure.core} to be loaded first).
 */
public class BytecodeRuntimeIntegrationTest {

    @BeforeClass
    public static void bindUserNamespace() {
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    @Test
    public void compileBootstrapSliceUsesBytecodeExecutionPath() throws Exception {
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try {
            assertTrue(CloffleCompiler.useBytecodeExecution());
            String text;
            try (InputStream in = BytecodeRuntimeIntegrationTest.class.getResourceAsStream("/cloffle/bootstrap_slice.clj")) {
                assertNotNull("classpath resource /cloffle/bootstrap_slice.clj", in);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Object last =
                    CloffleCompiler.compile(new StringReader(text), "bootstrap_slice.clj", "bootstrap_slice.clj");
            assertEquals(42L, last);
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }
}
