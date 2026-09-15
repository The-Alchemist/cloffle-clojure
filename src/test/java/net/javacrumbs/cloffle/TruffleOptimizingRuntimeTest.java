package net.javacrumbs.cloffle;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against shading Truffle into an uber/nested JAR. That layout cannot load
 * {@code truffleattach}, so Graal falls back to the interpreter-only engine and prints
 * {@code [engine] WARNING: The polyglot engine uses a fallback runtime…}.
 */
public class TruffleOptimizingRuntimeTest {

    @Test
    public void truffleApiIsStandaloneJar() {
        assertStandaloneTruffleJar(Truffle.class, "truffle-api");
    }

    @Test
    public void truffleRuntimeIsStandaloneJar() throws ClassNotFoundException {
        Class<?> runtimeAccess = Class.forName("com.oracle.truffle.runtime.hotspot.HotSpotTruffleRuntimeAccess");
        assertStandaloneTruffleJar(runtimeAccess, "truffle-runtime");
    }

    @Test
    public void truffleRuntimeIsNotDefaultFallback() {
        TruffleRuntime runtime = Truffle.getRuntime();
        String className = runtime.getClass().getName();
        assertFalse(className.contains("DefaultTruffleRuntime"), "DefaultTruffleRuntime means guest code will not JIT (uberjar / missing truffle-runtime). Runtime: "
                        + className);
    }

    @Test
    public void engineIsNotFallbackInterpreter() {
        try (Engine engine = Engine.create()) {
            String impl = engine.getImplementationName();
            assertNotNull(impl);
            assertFalse("Interpreted".equalsIgnoreCase(impl), "Polyglot is using the fallback interpreter (" + impl
                            + "). Keep truffle-api and truffle-runtime as standalone JARs on -cp, not an uberjar. See "
                            + "https://www.graalvm.org/latest/reference-manual/embed-languages/#runtime-optimization-support");
        }
    }

    private static void assertStandaloneTruffleJar(Class<?> clazz, String jarNamePrefix) {
        CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, "No CodeSource for " + clazz.getName());
        URL loc = codeSource.getLocation();
        assertNotNull(loc, "No CodeSource location for " + clazz.getName());
        String url = loc.toString();
        assertFalse(url.contains("BOOT-INF") || url.contains("jar:nested:") || url.contains("!/"), "Truffle class loaded from a nested/shaded location (uberjar): " + url);
        File file;
        try {
            file = new File(loc.toURI());
        } catch (URISyntaxException e) {
            fail("Truffle CodeSource is not a file URI (uberjar/nested?): " + url);
            return;
        }
        assertTrue(file.isFile(), "Truffle must be a real JAR on the classpath, got: " + file);
        String name = file.getName();
        assertTrue(name.startsWith(jarNamePrefix) && name.endsWith(".jar"), "Expected standalone " + jarNamePrefix + "-*.jar, got: " + name);
    }
}
