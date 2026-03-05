package net.javacrumbs.cloffle;

import org.junit.Test;

/**
 * Audit what types real Clojure returns for various expressions.
 * This helps identify which types need TruffleObject wrappers in Cloffle.
 */
public class ClojureTypeAuditTest {

    private void audit(String label, String expression) {
        try {
            Object result = mikera.cljutils.Clojure.eval(expression);
            if (result == null) {
                System.out.printf("%-35s => %-40s  (null)%n", label, "null");
            } else {
                String interop = isInteropSafe(result) ? "OK" : "NEEDS WRAPPER";
                System.out.printf("%-35s => %-40s  %s%n", label, result.getClass().getName(), interop);
            }
        } catch (Exception e) {
            System.out.printf("%-35s => ERROR: %s%n", label, e.getMessage());
        }
    }

    private boolean isInteropSafe(Object value) {
        return value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof Float
            || value instanceof Double
            || value instanceof Character
            || value instanceof String
            || value instanceof com.oracle.truffle.api.interop.TruffleObject;
    }

    @Test
    public void auditAllReturnTypes() {
        System.out.println("=== Primitives & Literals ===");
        audit("long literal", "42");
        audit("double literal", "3.14");
        audit("boolean true", "true");
        audit("boolean false", "false");
        audit("string literal", "\"hello\"");
        audit("char literal", "\\a");
        audit("nil literal", "nil");
        audit("keyword literal", ":foo");
        audit("symbol literal", "'foo");
        audit("regex literal", "#\"abc\"");

        System.out.println("\n=== Numeric types ===");
        audit("big integer", "99999999999999999999N");
        audit("big decimal", "3.14M");
        audit("ratio", "22/7");
        audit("integer (via Java)", "(Integer/valueOf 42)");
        audit("short (via Java)", "(Short/valueOf (short 1))");
        audit("byte (via Java)", "(Byte/valueOf (byte 1))");
        audit("float (via Java)", "(Float/valueOf (float 1.5))");

        System.out.println("\n=== Collections ===");
        audit("vector", "[1 2 3]");
        audit("list", "'(1 2 3)");
        audit("hash-map", "{:a 1 :b 2}");
        audit("hash-set", "#{1 2 3}");
        audit("sorted-map", "(sorted-map :a 1 :b 2)");
        audit("sorted-set", "(sorted-set 1 2 3)");
        audit("empty vector", "[]");
        audit("empty list", "'()");
        audit("empty map", "{}");
        audit("empty set", "#{}");

        System.out.println("\n=== Sequences ===");
        audit("range", "(range 5)");
        audit("map result", "(map inc [1 2 3])");
        audit("filter result", "(filter odd? [1 2 3])");
        audit("cons", "(cons 0 [1 2])");
        audit("seq of vector", "(seq [1 2 3])");
        audit("lazy-seq", "(lazy-seq [1 2 3])");
        audit("repeat (take 3)", "(take 3 (repeat 1))");
        audit("iterate (take 3)", "(take 3 (iterate inc 0))");

        System.out.println("\n=== Functions ===");
        audit("fn", "(fn [x] x)");
        audit("defn result", "(defn audit-fn1 [x] x)");
        audit("partial", "(partial + 1)");
        audit("comp", "(comp inc inc)");
        audit("complement", "(complement nil?)");

        System.out.println("\n=== Atoms & Refs ===");
        audit("atom", "(atom 42)");
        audit("deref atom", "@(atom 42)");
        audit("ref", "(ref 42)");
        audit("deref ref", "@(ref 42)");

        System.out.println("\n=== Java Interop ===");
        audit("Math/PI", "Math/PI");
        audit("System/out", "System/out");
        audit(".toUpperCase", "(.toUpperCase \"hello\")");
        audit("new ArrayList", "(java.util.ArrayList.)");
        audit("Class object", "String");
        audit("Java array", "(int-array [1 2 3])");
        audit("java.util.Date", "(java.util.Date.)");

        System.out.println("\n=== Def/Var ===");
        audit("def", "(def audit-val 42)");
        audit("var via #'", "(do (def audit-val2 42) #'audit-val2)");
        audit("deref var", "(do (def audit-val3 42) @#'audit-val3)");

        System.out.println("\n=== Special returns ===");
        audit("(if false 1)", "(if false 1)");
        audit("(when false 1)", "(when false 1)");
        audit("(do)", "(do)");
        audit("(println \"hi\")", "(println \"hi\")");
    }
}
