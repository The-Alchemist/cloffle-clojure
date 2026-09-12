package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.Expr;
import clojure.lang.Compiler.InvokeExpr;
import clojure.lang.Compiler.KeywordExpr;
import clojure.lang.Compiler.KeywordInvokeExpr;
import clojure.lang.Compiler.LiteralExpr;
import clojure.lang.Compiler.StaticMethodExpr;
import clojure.lang.Compiler.VarExpr;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.Var;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes {@code (json/parse-string s)} whose result is consumed <em>only</em> by a chain of
 * constant accessors, and describes the whole expression as one {@link JsonFusedPlan} so the
 * intermediate maps and vectors never have to exist.
 *
 * <p>Recognized consumer layers, applied to the parse result or to another recognized layer:
 * <ul>
 *   <li>{@code (:k x)} — keyword invocation with a literal keyword
 *   <li>{@code (get x :k)} — arity 2, literal keyword
 *   <li>{@code (get-in x [:a :b])} — arity 2, literal vector of literal keywords
 *   <li>{@code (nth x 3)} — arity 2, literal non-negative integer
 * </ul>
 *
 * <p>The escape analysis is the nesting itself: the rule fires only when the parse call is
 * syntactically the receiver of the chain, so the result has exactly one use and no name. A
 * {@code let}-bound parse result, a parse result passed to a function, or any consumer not in the
 * list above reaches the base case as something other than the parse call and the match fails. Also
 * refuses when any bypassed Var is dynamic, unbound, or no longer holds the root that sanctioned
 * lowering (see {@link BytecodeLowering#sanctionedRootAssumption}).
 */
final class ExprToBytecodeJsonFuse {

    private static final String JSON_NS = "cloffle.json";
    private static final String PARSE_STRING = "parse-string";
    private static final String PARSE_BYTES = "parse-bytes";

    /** Escape hatch for A/B measurement and for disabling the rewrite in the field. */
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("cloffle.json.fuse", "true"));

    private ExprToBytecodeJsonFuse() {
    }

    /** The plan plus the expression producing the JSON text, or null when the pattern does not match. */
    record Match(JsonFusedPlan plan, Expr sourceExpr) {
    }

    static Match match(Expr expr) {
        if (!ENABLED) {
            return null;
        }
        if (!(expr instanceof KeywordInvokeExpr) && !(expr instanceof InvokeExpr)
                && !(expr instanceof StaticMethodExpr)) {
            return null;
        }
        List<JsonFusedPlan.Layer> layers = new ArrayList<>(4);
        Base base = peel(expr, layers);
        if (base == null || layers.isEmpty()) {
            return null;
        }
        java.util.Collections.reverse(layers);

        List<Object> steps = new ArrayList<>(4);
        for (JsonFusedPlan.Layer layer : layers) {
            if (!flatten(layer, steps)) {
                return null;
            }
        }
        if (steps.isEmpty()) {
            return null;
        }

        JsonFusedPlan.Layer[] layerArray = layers.toArray(new JsonFusedPlan.Layer[0]);
        if (!sanctioned(base.parseVar)) {
            return null;
        }
        for (JsonFusedPlan.Layer layer : layerArray) {
            if (layer.var() != null && !sanctioned(layer.var())) {
                return null;
            }
        }
        return new Match(
                new JsonFusedPlan(base.parseVar, base.bytesSource, steps.toArray(), layerArray),
                base.sourceExpr);
    }

    /** The {@code json/parse-*} call the chain bottoms out in. */
    private record Base(Var parseVar, boolean bytesSource, Expr sourceExpr) {
    }

    /**
     * Strips recognized accessor layers off {@code expr} (outermost first, appended to
     * {@code layers}) until the {@code json/parse-*} call, or null if anything else is in the way.
     */
    private static Base peel(Expr expr, List<JsonFusedPlan.Layer> layers) {
        Base parse = parseCall(expr);
        if (parse != null) {
            return parse;
        }
        if (expr instanceof KeywordInvokeExpr kie) {
            layers.add(new JsonFusedPlan.KeywordLayer(kie.kw.k));
            return peel(kie.target, layers);
        }
        // `nth` is rewritten to RT.nth during analysis (:cloffle/unchecked-op), so the Var is
        // already gone by the time we see it and there is nothing left to guard.
        if (expr instanceof StaticMethodExpr sme
                && sme.c == clojure.lang.RT.class
                && "nth".equals(sme.methodName)
                && sme.args.count() == 2) {
            int index = constantIndex((Expr) sme.args.nth(1));
            if (index < 0) {
                return null;
            }
            layers.add(new JsonFusedPlan.NthLayer(null, index));
            return peel((Expr) sme.args.nth(0), layers);
        }
        // Outside a fn body the compiler does not build a KeywordInvokeExpr for `(:k x)`.
        if (expr instanceof InvokeExpr ki
                && !ki.isProtocol
                && ki.fexpr instanceof KeywordExpr ke
                && ki.args.count() == 1) {
            layers.add(new JsonFusedPlan.KeywordLayer(ke.k));
            return peel((Expr) ki.args.nth(0), layers);
        }
        if (!(expr instanceof InvokeExpr ie)
                || ie.isProtocol
                || !(ie.fexpr instanceof VarExpr ve)
                || ve.var.isDynamic()
                || ie.args.count() != 2) {
            return null;
        }
        Var var = ve.var;
        Expr receiver = (Expr) ie.args.nth(0);
        Expr argExpr = (Expr) ie.args.nth(1);
        if (isCoreVar(var, "get") && argExpr instanceof KeywordExpr ke) {
            layers.add(new JsonFusedPlan.GetLayer(var, ke.k));
            return peel(receiver, layers);
        }
        if (isCoreVar(var, "get-in")) {
            IPersistentVector path = constantKeywordPath(argExpr);
            if (path == null) {
                return null;
            }
            layers.add(new JsonFusedPlan.GetInLayer(var, path));
            return peel(receiver, layers);
        }
        if (isCoreVar(var, "nth")) {
            int index = constantIndex(argExpr);
            if (index < 0) {
                return null;
            }
            layers.add(new JsonFusedPlan.NthLayer(var, index));
            return peel(receiver, layers);
        }
        return null;
    }

    private static Base parseCall(Expr expr) {
        if (!(expr instanceof InvokeExpr ie)
                || ie.isProtocol
                || !(ie.fexpr instanceof VarExpr ve)
                || ve.var.isDynamic()
                || ie.args.count() != 1) {
            return null;
        }
        Var var = ve.var;
        if (!JSON_NS.equals(namespaceName(var))) {
            return null;
        }
        String name = var.sym.getName();
        if (PARSE_STRING.equals(name)) {
            return new Base(var, false, (Expr) ie.args.nth(0));
        }
        if (PARSE_BYTES.equals(name)) {
            return new Base(var, true, (Expr) ie.args.nth(0));
        }
        return null;
    }

    private static boolean flatten(JsonFusedPlan.Layer layer, List<Object> steps) {
        if (layer instanceof JsonFusedPlan.KeywordLayer kl) {
            steps.add(kl.keyword());
            return true;
        }
        if (layer instanceof JsonFusedPlan.GetLayer gl) {
            steps.add(gl.keyword());
            return true;
        }
        if (layer instanceof JsonFusedPlan.NthLayer nl) {
            steps.add(Integer.valueOf(nl.index()));
            return true;
        }
        if (layer instanceof JsonFusedPlan.GetInLayer gil) {
            IPersistentVector path = gil.path();
            for (int i = 0; i < path.count(); i++) {
                steps.add(path.nth(i));
            }
            return true;
        }
        return false;
    }

    /**
     * The literal path of {@code (get-in x [..])} when every element is a keyword. Integers are
     * rejected: {@code get} on an out-of-range index is nil where {@code nth} throws, and keeping
     * index steps unambiguously {@code nth}-shaped avoids having to encode that difference.
     */
    private static IPersistentVector constantKeywordPath(Expr expr) {
        if (!(expr instanceof LiteralExpr le) || !(le.val() instanceof IPersistentVector path)) {
            return null;
        }
        int n = path.count();
        if (n == 0) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            if (!(path.nth(i) instanceof Keyword)) {
                return null;
            }
        }
        return path;
    }

    /** A literal non-negative {@code int} index, or -1. */
    private static int constantIndex(Expr expr) {
        if (!(expr instanceof LiteralExpr le)) {
            return -1;
        }
        Object v = le.val();
        if (!(v instanceof Long) && !(v instanceof Integer)) {
            return -1;
        }
        long value = ((Number) v).longValue();
        return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
    }

    private static boolean isCoreVar(Var var, String name) {
        return "clojure.core".equals(namespaceName(var)) && name.equals(var.sym.getName());
    }

    private static String namespaceName(Var var) {
        return var.ns == null ? null : var.ns.getName().getName();
    }

    private static boolean sanctioned(Var var) {
        Object root = var.getLoweringRoot();
        return root != null && root == var.getRawRoot();
    }
}
