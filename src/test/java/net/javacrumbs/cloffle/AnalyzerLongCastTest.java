package net.javacrumbs.cloffle;

import org.junit.Test;
import java.util.Map;

public class AnalyzerLongCastTest {
    static {
        mikera.cljutils.Clojure.require("clojure.tools.analyzer.jvm");
    }

    @Test
    public void inspectLongCast() {
        String expr = "(clojure.tools.analyzer.jvm/analyze '(long 42))";
        Map<?, ?> result = (Map<?, ?>) mikera.cljutils.Clojure.eval(expr);
        System.out.println("op: " + result.get(clojure.lang.Keyword.intern("op")));
        System.out.println("class: " + result.get(clojure.lang.Keyword.intern("class")));
        System.out.println("method: " + result.get(clojure.lang.Keyword.intern("method")));
        System.out.println("Full tree keys: " + result.keySet());

        Object args = result.get(clojure.lang.Keyword.intern("args"));
        System.out.println("args: " + args);
    }

    @Test
    public void inspectLongCastOfRatio() {
        String expr = "(clojure.tools.analyzer.jvm/analyze '(long (/ 10 3)))";
        Map<?, ?> result = (Map<?, ?>) mikera.cljutils.Clojure.eval(expr);
        System.out.println("op: " + result.get(clojure.lang.Keyword.intern("op")));
        System.out.println("class: " + result.get(clojure.lang.Keyword.intern("class")));
        System.out.println("method: " + result.get(clojure.lang.Keyword.intern("method")));
    }

    @Test
    public void whatMethodsDoesRTHave() {
        for (java.lang.reflect.Method m : clojure.lang.RT.class.getMethods()) {
            if (m.getName().contains("long") || m.getName().contains("Long")) {
                System.out.println(m);
            }
        }
    }
}
