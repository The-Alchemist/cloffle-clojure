package net.javacrumbs.cloffle;

import org.junit.Test;

import java.util.Map;

/**
 * Inspect what clojure.tools.analyzer.jvm produces for various expressions
 * so we know what ConstNodeBuilder needs to handle.
 */
public class AnalyzerAuditTest {

    static {
        mikera.cljutils.Clojure.require("clojure.tools.analyzer.jvm");
    }

    private void audit(String label, String expression) {
        try {
            String analyzeExpr = "(clojure.tools.analyzer.jvm/analyze '" + expression + ")";
            Map<?, ?> result = (Map<?, ?>) mikera.cljutils.Clojure.eval(analyzeExpr);
            Object op = result.get(clojure.lang.Keyword.intern("op"));
            Object type = result.get(clojure.lang.Keyword.intern("type"));
            Object tag = result.get(clojure.lang.Keyword.intern("tag"));
            Object val = result.get(clojure.lang.Keyword.intern("val"));
            System.out.printf("%-30s op=%-15s type=%-15s tag=%-35s val-class=%-35s%n",
                    label, op, type, tag,
                    val != null ? val.getClass().getName() : "null");
        } catch (Exception e) {
            System.out.printf("%-30s ERROR: %s%n", label, e.getMessage());
        }
    }

    @Test
    public void auditAnalyzerOutput() {
        System.out.println("=== Primitives (already supported) ===");
        audit("long", "42");
        audit("double", "3.14");
        audit("boolean", "true");
        audit("string", "\"hello\"");
        audit("nil", "nil");

        System.out.println("\n=== High-priority types ===");
        audit("keyword", ":foo");
        audit("symbol", "'foo");
        audit("vector", "[1 2 3]");
        audit("list", "'(1 2 3)");
        audit("map", "{:a 1 :b 2}");
        audit("set", "#{1 2 3}");
        audit("empty vector", "[]");
        audit("empty map", "{}");
        audit("empty set", "#{}");

        System.out.println("\n=== Char & Regex ===");
        audit("char", "\\a");
        audit("regex", "#\"abc\"");

        System.out.println("\n=== Numeric edge cases ===");
        audit("ratio", "22/7");
        audit("bigint", "99999999999999999999N");
        audit("bigdecimal", "3.14M");

        System.out.println("\n=== Compound: vector of keywords ===");
        audit("[:a :b :c]", "[:a :b :c]");
        audit("{:a 1}", "{:a 1}");

        System.out.println("\n=== Non-literal map/vector/set ===");
        audit("{:a (+ 1 2)}", "{:a (+ 1 2)}");
        audit("[(+ 1 2)]", "[(+ 1 2)]");
        audit("#{(+ 1 2)}", "#{(+ 1 2)}");
    }

    @Test
    public void auditTryAst() {
        System.out.println("=== :try with catch and finally ===");
        String tryExpr = "(clojure.tools.analyzer.jvm/analyze '(try (+ 1 2) (catch Exception e 42) (finally (+ 3 4))))";
        Map<?, ?> r = (Map<?, ?>) mikera.cljutils.Clojure.eval(tryExpr);
        System.out.println("op: " + r.get(clojure.lang.Keyword.intern("op")));
        System.out.println("children: " + r.get(clojure.lang.Keyword.intern("children")));

        Map<?,?> body = (Map<?,?>) r.get(clojure.lang.Keyword.intern("body"));
        System.out.println("body op: " + body.get(clojure.lang.Keyword.intern("op")));

        java.util.List<?> catches = (java.util.List<?>) r.get(clojure.lang.Keyword.intern("catches"));
        System.out.println("catches count: " + catches.size());
        Map<?,?> c = (Map<?,?>) catches.get(0);
        System.out.println("catch op: " + c.get(clojure.lang.Keyword.intern("op")));
        System.out.println("catch children: " + c.get(clojure.lang.Keyword.intern("children")));
        System.out.println("catch class: " + c.get(clojure.lang.Keyword.intern("class")));
        Map<?,?> local = (Map<?,?>) c.get(clojure.lang.Keyword.intern("local"));
        System.out.println("catch local name: " + local.get(clojure.lang.Keyword.intern("name")));
        System.out.println("catch local op: " + local.get(clojure.lang.Keyword.intern("op")));
        System.out.println("catch body op: " + ((Map<?,?>)c.get(clojure.lang.Keyword.intern("body"))).get(clojure.lang.Keyword.intern("op")));

        Map<?,?> fin = (Map<?,?>) r.get(clojure.lang.Keyword.intern("finally"));
        System.out.println("finally op: " + (fin != null ? fin.get(clojure.lang.Keyword.intern("op")) : "null"));

        System.out.println("\n=== :try without finally ===");
        Map<?, ?> r2 = (Map<?, ?>) mikera.cljutils.Clojure.eval(
            "(clojure.tools.analyzer.jvm/analyze '(try (+ 1 2) (catch Exception e 42)))");
        System.out.println("finally: " + r2.get(clojure.lang.Keyword.intern("finally")));

        System.out.println("\n=== :try without catch ===");
        Map<?, ?> r3 = (Map<?, ?>) mikera.cljutils.Clojure.eval(
            "(clojure.tools.analyzer.jvm/analyze '(try (+ 1 2) (finally (+ 3 4))))");
        java.util.List<?> c3 = (java.util.List<?>) r3.get(clojure.lang.Keyword.intern("catches"));
        System.out.println("catches count: " + c3.size());
    }

    @Test
    public void auditReifyAst() {
        System.out.println("=== :reify ===");
        String expr = "(clojure.tools.analyzer.jvm/analyze '(reify Runnable (run [this] (println \"hello\"))))";
        Map<?, ?> r = (Map<?, ?>) mikera.cljutils.Clojure.eval(expr);
        printAst(r, 0);
    }

    private void printAst(Map<?, ?> m, int indent) {
        String pad = " ".repeat(indent);
        for (var entry : m.entrySet()) {
            Object k = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof Map) {
                System.out.println(pad + k + ": {MAP}");
            } else if (v instanceof java.util.List<?> list) {
                System.out.println(pad + k + ": [LIST size=" + list.size() + "]");
            } else if (v instanceof java.util.Set<?> set) {
                System.out.println(pad + k + ": #{SET " + set + "}");
            } else {
                System.out.println(pad + k + ": " + v + " (" + (v != null ? v.getClass().getSimpleName() : "null") + ")");
            }
        }
        // Print method details
        Object methods = m.get(clojure.lang.Keyword.intern("methods"));
        if (methods instanceof java.util.List<?> methodList) {
            for (int i = 0; i < methodList.size(); i++) {
                System.out.println(pad + "--- method[" + i + "] ---");
                Map<?, ?> method = (Map<?, ?>) methodList.get(i);
                for (var entry : method.entrySet()) {
                    Object k = entry.getKey();
                    Object v = entry.getValue();
                    if (v instanceof Map) {
                        System.out.println(pad + "  " + k + ": {MAP op=" + ((Map<?,?>)v).get(clojure.lang.Keyword.intern("op")) + "}");
                    } else if (v instanceof java.util.List<?> list) {
                        System.out.println(pad + "  " + k + ": [LIST size=" + list.size() + "]");
                        if (k.toString().equals(":params")) {
                            for (var p : list) {
                                Map<?,?> pm = (Map<?,?>) p;
                                System.out.println(pad + "    param: name=" + pm.get(clojure.lang.Keyword.intern("name")) + " op=" + pm.get(clojure.lang.Keyword.intern("op")));
                            }
                        }
                    } else {
                        System.out.println(pad + "  " + k + ": " + v + " (" + (v != null ? v.getClass().getSimpleName() : "null") + ")");
                    }
                }
            }
        }
    }
}
