package clojure.lang;

import net.javacrumbs.cloffle.benchmark.SnippetBenchmarkSupport;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URLClassLoader;

/**
 * Compile guest Clojure source with the stock {@code clojure.jar} in an isolated {@link URLClassLoader}.
 * Sets {@code -Dclojure.compiler.direct-linking=} before {@code clojure.lang.Compiler} is first loaded in that loader.
 */
public final class StockClojureCompileSupport {

    private StockClojureCompileSupport() {
    }

    public static void compileSource(String source, boolean directLinking) throws Exception {
        String prop = "clojure.compiler.direct-linking";
        String prev = System.getProperty(prop);
        System.setProperty(prop, Boolean.toString(directLinking));
        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        try {
            URLClassLoader cl = SnippetBenchmarkSupport.createStockClojureClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> rtClass = cl.loadClass("clojure.lang.RT");
            rtClass.getMethod("init").invoke(null);
            Class<?> compilerClass = cl.loadClass("clojure.lang.Compiler");
            Method load = compilerClass.getMethod("load", java.io.Reader.class);
            load.invoke(null, new StringReader(source));
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }

    public static Object invokeStockVar(String ns, String name, boolean directLinking) throws Exception {
        String prop = "clojure.compiler.direct-linking";
        String prev = System.getProperty(prop);
        System.setProperty(prop, Boolean.toString(directLinking));
        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        try {
            URLClassLoader cl = SnippetBenchmarkSupport.createStockClojureClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> rtClass = cl.loadClass("clojure.lang.RT");
            rtClass.getMethod("init").invoke(null);
            Object var = rtClass.getMethod("var", String.class, String.class).invoke(null, ns, name);
            return var.getClass().getMethod("invoke").invoke(var);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }
}
