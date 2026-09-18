package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.BindingInit;
import clojure.lang.Compiler.BodyExpr;
import clojure.lang.Compiler.Expr;
import clojure.lang.Compiler.InvokeExpr;
import clojure.lang.Compiler.KeywordExpr;
import clojure.lang.Compiler.KeywordInvokeExpr;
import clojure.lang.Compiler.LetExpr;
import clojure.lang.Compiler.LiteralExpr;
import clojure.lang.Compiler.LocalBinding;
import clojure.lang.Compiler.LocalBindingExpr;
import clojure.lang.Compiler.MapExpr;
import clojure.lang.Compiler.StaticMethodExpr;
import clojure.lang.Compiler.VarExpr;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.Var;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recognizes a {@code json/parse-*} whose result is consumed only by constant accessors, including
 * {@code let}-bound locals, map literals, and {@code select-keys}, and describes the expression as
 * one {@link JsonProjectPlan} so the input maps never have to exist.
 */
final class ExprToBytecodeJsonProject {

    private static final String JSON_NS = "cloffle.json";
    private static final String PARSE_STRING = "parse-string";
    private static final String PARSE_BYTES = "parse-bytes";

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("cloffle.json.project", "true"));

    private ExprToBytecodeJsonProject() {
    }

    record Match(JsonProjectPlan plan, Expr sourceExpr) {
    }

    static Match match(Expr expr) {
        if (!ENABLED) {
            return null;
        }
        Match m = matchLet(expr);
        if (m != null) {
            return m;
        }
        return matchSelectKeysOnParse(expr);
    }

    private static Match matchLet(Expr expr) {
        if (!(expr instanceof LetExpr le) || le.isLoop || le.bindingInits.count() != 1) {
            return null;
        }
        BindingInit bi = (BindingInit) le.bindingInits.nth(0);
        Base parse = parseCall(bi.init());
        if (parse == null || !sanctioned(parse.parseVar)) {
            return null;
        }
        Expr body = unwrapBody(le.body);
        if (body == null) {
            return null;
        }
        return matchConsumed(body, bi.binding(), parse);
    }

    private static Match matchSelectKeysOnParse(Expr expr) {
        SelectKeys sk = selectKeysCall(expr);
        if (sk == null) {
            return null;
        }
        Base parse = parseCall(sk.receiver);
        if (parse == null || !sanctioned(parse.parseVar)) {
            return null;
        }
        return finishSelectKeys(parse, sk.keys, sk.var, parse.sourceExpr);
    }

    private static Match matchConsumed(Expr body, LocalBinding lb, Base parse) {
        if (body instanceof MapExpr me) {
            return matchMapLiteral(me, lb, parse);
        }
        SelectKeys sk = selectKeysCall(body);
        if (sk != null && isLocal(sk.receiver, lb)) {
            return finishSelectKeys(parse, sk.keys, sk.var, parse.sourceExpr);
        }
        Object[] path = accessorPath(body, lb);
        if (path == null || path.length == 0) {
            return null;
        }
        JsonProjectPlan.TrieBuilder trie = new JsonProjectPlan.TrieBuilder();
        int slot = trie.internPath(path);
        if (slot < 0) {
            return null;
        }
        Set<Var> vars = new LinkedHashSet<>();
        collectAccessorVars(body, vars);
        return new Match(
                new JsonProjectPlan(
                        parse.parseVar, parse.bytesSource, trie.finish(), trie.slotCount(),
                        JsonProjectPlan.Kind.SCALAR, null, new int[] {slot}, null,
                        new Object[][] {path}, vars.toArray(new Var[0])),
                parse.sourceExpr);
    }

    private static Match matchMapLiteral(MapExpr me, LocalBinding lb, Base parse) {
        int n = me.keyvals.count() / 2;
        if (n < 1 || n > 16) {
            return null;
        }
        Keyword[] keys = new Keyword[n];
        int[] slots = new int[n];
        Object[][] paths = new Object[n][];
        JsonProjectPlan.TrieBuilder trie = new JsonProjectPlan.TrieBuilder();
        Set<Var> vars = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            Keyword key = constantKeyword((Expr) me.keyvals.nth(i * 2));
            if (key == null) {
                return null;
            }
            Expr val = (Expr) me.keyvals.nth(i * 2 + 1);
            Object[] path = accessorPath(val, lb);
            if (path == null || path.length == 0) {
                return null;
            }
            int slot = trie.internPath(path);
            if (slot < 0) {
                return null;
            }
            keys[i] = key;
            slots[i] = slot;
            paths[i] = path;
            collectAccessorVars(val, vars);
        }
        MapShape shape = n <= PersistentShapeMap.MAX_SHAPE_KEYS ? MapShape.of(keys) : null;
        return new Match(
                new JsonProjectPlan(
                        parse.parseVar, parse.bytesSource, trie.finish(), trie.slotCount(),
                        JsonProjectPlan.Kind.MAP, keys, slots, shape, paths,
                        vars.toArray(new Var[0])),
                parse.sourceExpr);
    }

    private static Match finishSelectKeys(Base parse, Keyword[] keys, Var selectKeysVar, Expr source) {
        JsonProjectPlan.TrieBuilder trie = new JsonProjectPlan.TrieBuilder();
        int[] slots = new int[keys.length];
        Object[][] paths = new Object[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            Object[] path = new Object[] {keys[i]};
            int slot = trie.internPath(path);
            if (slot < 0) {
                return null;
            }
            slots[i] = slot;
            paths[i] = path;
        }
        MapShape shape = keys.length <= PersistentShapeMap.MAX_SHAPE_KEYS ? MapShape.of(keys) : null;
        Var[] vars = selectKeysVar == null ? new Var[0] : new Var[] {selectKeysVar};
        return new Match(
                new JsonProjectPlan(
                        parse.parseVar, parse.bytesSource, trie.finish(), trie.slotCount(),
                        JsonProjectPlan.Kind.SELECT_KEYS, keys, slots, shape, paths, vars),
                source);
    }

    private static Expr unwrapBody(Expr expr) {
        if (expr instanceof BodyExpr be) {
            if (be.exprs().count() != 1) {
                return null;
            }
            return (Expr) be.exprs().nth(0);
        }
        return expr;
    }

    private record Base(Var parseVar, boolean bytesSource, Expr sourceExpr) {
    }

    private record SelectKeys(Expr receiver, Keyword[] keys, Var var) {
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

    private static SelectKeys selectKeysCall(Expr expr) {
        if (!(expr instanceof InvokeExpr ie)
                || ie.isProtocol
                || !(ie.fexpr instanceof VarExpr ve)
                || ve.var.isDynamic()
                || ie.args.count() != 2) {
            return null;
        }
        if (!isCoreVar(ve.var, "select-keys") || !sanctioned(ve.var)) {
            return null;
        }
        Keyword[] keys = constantKeywordVector((Expr) ie.args.nth(1));
        if (keys == null || keys.length == 0 || keys.length > 16) {
            return null;
        }
        return new SelectKeys((Expr) ie.args.nth(0), keys, ve.var);
    }

    /**
     * Path of constant accessors from {@code expr} down to {@code lb}, or null.
     */
    private static Object[] accessorPath(Expr expr, LocalBinding lb) {
        List<Object> steps = new ArrayList<>(4);
        if (!peel(expr, lb, steps)) {
            return null;
        }
        java.util.Collections.reverse(steps);
        return steps.toArray();
    }

    private static boolean peel(Expr expr, LocalBinding lb, List<Object> steps) {
        if (isLocal(expr, lb)) {
            return true;
        }
        if (expr instanceof KeywordInvokeExpr kie) {
            steps.add(kie.kw.k);
            return peel(kie.target, lb, steps);
        }
        if (expr instanceof StaticMethodExpr sme
                && sme.c == clojure.lang.RT.class
                && "nth".equals(sme.methodName)
                && sme.args.count() == 2) {
            int index = constantIndex((Expr) sme.args.nth(1));
            if (index < 0) {
                return false;
            }
            steps.add(Integer.valueOf(index));
            return peel((Expr) sme.args.nth(0), lb, steps);
        }
        if (expr instanceof InvokeExpr ki
                && !ki.isProtocol
                && ki.fexpr instanceof KeywordExpr ke
                && ki.args.count() == 1) {
            steps.add(ke.k);
            return peel((Expr) ki.args.nth(0), lb, steps);
        }
        if (!(expr instanceof InvokeExpr ie)
                || ie.isProtocol
                || !(ie.fexpr instanceof VarExpr ve)
                || ve.var.isDynamic()
                || ie.args.count() != 2) {
            return false;
        }
        Var var = ve.var;
        Expr receiver = (Expr) ie.args.nth(0);
        Expr argExpr = (Expr) ie.args.nth(1);
        if (isCoreVar(var, "get") && argExpr instanceof KeywordExpr ke) {
            if (!sanctioned(var)) {
                return false;
            }
            steps.add(ke.k);
            return peel(receiver, lb, steps);
        }
        if (isCoreVar(var, "get-in")) {
            if (!sanctioned(var)) {
                return false;
            }
            IPersistentVector path = constantKeywordPath(argExpr);
            if (path == null) {
                return false;
            }
            for (int i = path.count() - 1; i >= 0; i--) {
                steps.add(path.nth(i));
            }
            return peel(receiver, lb, steps);
        }
        if (isCoreVar(var, "nth")) {
            if (!sanctioned(var)) {
                return false;
            }
            int index = constantIndex(argExpr);
            if (index < 0) {
                return false;
            }
            steps.add(Integer.valueOf(index));
            return peel(receiver, lb, steps);
        }
        return false;
    }

    /**
     * Peel adds steps outermost-first, then we reverse. For get-in we added inner keys first
     * (reversed path order) so after the global reverse they are outermost-first. get-in path
     * [:data :attributes :title] should become those steps in order.
     *
     * peel on get-in: we add title, attributes, data (inner to outer because we iterate
     * path.count-1 down to 0). Then peel receiver. After reverse of the whole list, data comes
     * first. Correct.
     */
    private static void collectAccessorVars(Expr expr, Set<Var> vars) {
        if (expr instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve) {
            Var var = ve.var;
            if (isCoreVar(var, "get") || isCoreVar(var, "get-in") || isCoreVar(var, "nth")
                    || isCoreVar(var, "select-keys")) {
                vars.add(var);
            }
            if (ie.args.count() > 0) {
                collectAccessorVars((Expr) ie.args.nth(0), vars);
            }
        } else if (expr instanceof KeywordInvokeExpr kie) {
            collectAccessorVars(kie.target, vars);
        } else if (expr instanceof StaticMethodExpr sme && sme.args.count() > 0) {
            collectAccessorVars((Expr) sme.args.nth(0), vars);
        }
    }

    private static boolean isLocal(Expr expr, LocalBinding lb) {
        return expr instanceof LocalBindingExpr lbe && lbe.b == lb;
    }

    private static Keyword constantKeyword(Expr expr) {
        if (expr instanceof KeywordExpr ke) {
            return ke.k;
        }
        if (expr instanceof LiteralExpr le && le.val() instanceof Keyword kw) {
            return kw;
        }
        return null;
    }

    private static Keyword[] constantKeywordVector(Expr expr) {
        if (!(expr instanceof LiteralExpr le) || !(le.val() instanceof IPersistentVector path)) {
            return null;
        }
        int n = path.count();
        if (n == 0) {
            return null;
        }
        Keyword[] keys = new Keyword[n];
        for (int i = 0; i < n; i++) {
            if (!(path.nth(i) instanceof Keyword kw)) {
                return null;
            }
            keys[i] = kw;
        }
        return keys;
    }

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
