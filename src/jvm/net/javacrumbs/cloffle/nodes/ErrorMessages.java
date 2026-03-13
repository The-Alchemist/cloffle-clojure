package net.javacrumbs.cloffle.nodes;

import clojure.lang.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utilities for producing Clojure-friendly error messages.
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(
        Map.entry("clojure.lang.PersistentVector", "vector"),
        Map.entry("clojure.lang.PersistentArrayMap", "map"),
        Map.entry("clojure.lang.PersistentHashMap", "map"),
        Map.entry("clojure.lang.PersistentTreeMap", "sorted-map"),
        Map.entry("clojure.lang.PersistentHashSet", "set"),
        Map.entry("clojure.lang.PersistentTreeSet", "sorted-set"),
        Map.entry("clojure.lang.PersistentList", "list"),
        Map.entry("clojure.lang.PersistentList$EmptyList", "list"),
        Map.entry("clojure.lang.LazySeq", "lazy-seq"),
        Map.entry("clojure.lang.Cons", "list"),
        Map.entry("clojure.lang.Symbol", "symbol"),
        Map.entry("clojure.lang.Keyword", "keyword"),
        Map.entry("clojure.lang.Ratio", "ratio"),
        Map.entry("clojure.lang.BigInt", "bigint"),
        Map.entry("clojure.lang.Var", "var"),
        Map.entry("clojure.lang.Atom", "atom"),
        Map.entry("clojure.lang.Ref", "ref"),
        Map.entry("clojure.lang.Agent", "agent"),
        Map.entry("clojure.lang.Namespace", "namespace"),
        Map.entry("clojure.lang.MapEntry", "map-entry"),
        Map.entry("java.lang.String", "string"),
        Map.entry("java.lang.Long", "integer"),
        Map.entry("java.lang.Integer", "integer"),
        Map.entry("java.lang.Double", "float"),
        Map.entry("java.lang.Float", "float"),
        Map.entry("java.lang.Boolean", "boolean"),
        Map.entry("java.lang.Character", "character"),
        Map.entry("java.math.BigDecimal", "bigdec"),
        Map.entry("java.math.BigInteger", "biginteger"),
        Map.entry("java.util.regex.Pattern", "regex")
    );

    public static String clojureTypeName(Object value) {
        if (value == null) return "nil";
        String className = value.getClass().getName();
        String name = TYPE_NAMES.get(className);
        if (name != null) return name;
        if (value instanceof IFn) return "function";
        if (value instanceof ISeq) return "seq";
        if (value instanceof IPersistentMap) return "map";
        if (value instanceof IPersistentVector) return "vector";
        if (value instanceof IPersistentSet) return "set";
        if (value instanceof IPersistentList) return "list";
        return className;
    }

    public static String truncateValue(Object value, int maxLen) {
        String s = RT.printString(value);
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    public static String cannotCallMessage(Object fnValue) {
        String typeName = clojureTypeName(fnValue);
        String valStr = truncateValue(fnValue, 40);
        return "Cannot call " + valStr + " as a function -- it is a " + typeName;
    }

    /**
     * Finds the closest matching var name by edit distance.
     * Returns null if no close match is found.
     */
    public static String didYouMean(String name, Namespace ns) {
        if (ns == null) return null;
        int bestDistance = Integer.MAX_VALUE;
        String bestMatch = null;

        for (Object entry : ns.getMappings()) {
            if (entry instanceof MapEntry me) {
                String candidate = me.key().toString();
                int dist = editDistance(name, candidate);
                if (dist < bestDistance && dist <= Math.max(2, name.length() / 3)) {
                    bestDistance = dist;
                    bestMatch = candidate;
                }
            }
        }
        return bestMatch;
    }

    static int editDistance(String a, String b) {
        int lenA = a.length(), lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) prev[j] = j;
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lenB];
    }

    public static String formatArities(FnMethodNode[] methods) {
        List<String> arities = new ArrayList<>();
        for (FnMethodNode m : methods) {
            if (m.isVariadic()) {
                arities.add(m.getFixedArity() + "+");
            } else {
                arities.add(String.valueOf(m.getFixedArity()));
            }
        }
        return String.join(", ", arities);
    }

    private static final java.util.Set<String> JAVA_LANG_EXCEPTIONS = java.util.Set.of(
        "ArithmeticException", "ArrayIndexOutOfBoundsException",
        "ClassCastException", "ClassNotFoundException",
        "IllegalArgumentException", "IllegalStateException",
        "IndexOutOfBoundsException", "NegativeArraySizeException",
        "NullPointerException", "NumberFormatException",
        "SecurityException", "StringIndexOutOfBoundsException",
        "UnsupportedOperationException", "StackOverflowError"
    );

    public static String formatException(Throwable t) {
        if (t instanceof NullPointerException) {
            String msg = t.getMessage();
            if (msg != null && !msg.isEmpty()) {
                return "NullPointerException: " + msg;
            }
            return "NullPointerException -- cannot call a method on nil";
        }

        String className = t.getClass().getSimpleName();
        String detail = t.getMessage();

        if (JAVA_LANG_EXCEPTIONS.contains(className)) {
            if (detail != null && !detail.isEmpty()) {
                return className + ": " + detail;
            }
            return className;
        }

        String fullName = t.getClass().getName();
        if (detail != null && !detail.isEmpty()) {
            return fullName + ": " + detail;
        }
        return fullName;
    }
}
