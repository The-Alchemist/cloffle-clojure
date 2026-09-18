package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.Expr;
import clojure.lang.Compiler.InvokeExpr;
import clojure.lang.Compiler.LiteralExpr;
import clojure.lang.Compiler.VarExpr;
import clojure.lang.Var;

/** Recognizes {@code (cloffle.json/project source constant-schema)}, the matching
 * {@code (cloffle.json/select source constant-pointers)}, and their three-argument options forms.
 * One-argument {@code project} is unschemed parse and is not lowered here. */
final class ExprToBytecodeJsonTypedProject {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("cloffle.json.typed-project", "true"));

    private ExprToBytecodeJsonTypedProject() {
    }

    record Match(JsonTypedProjectPlan plan, Expr sourceExpr) {
    }

    static Match match(Expr expr) {
        if (!ENABLED || !(expr instanceof InvokeExpr invoke)
                || invoke.isProtocol
                || !(invoke.fexpr instanceof VarExpr varExpr)
                || varExpr.var.isDynamic()
                || (invoke.args.count() != 2 && invoke.args.count() != 3)
                || !"cloffle.json".equals(namespaceName(varExpr.var))
                || !sanctioned(varExpr.var)
                || !(invoke.args.nth(1) instanceof LiteralExpr literal)) {
            return null;
        }
        String name = varExpr.var.sym.getName();
        boolean select = "select".equals(name);
        if (!select && !"project".equals(name)) {
            return null;
        }
        Object options = null;
        if (invoke.args.count() == 3) {
            if (!(invoke.args.nth(2) instanceof LiteralExpr optionLiteral)) {
                return null;
            }
            options = optionLiteral.val();
        }
        JsonTypedProjectPlan plan = select
                ? JsonTypedProjectPlan.compileSelect(varExpr.var, literal.val(), options)
                : JsonTypedProjectPlan.compile(varExpr.var, literal.val(), options);
        return new Match(plan, (Expr) invoke.args.nth(0));
    }

    private static String namespaceName(Var var) {
        return var.ns == null ? null : var.ns.getName().getName();
    }

    private static boolean sanctioned(Var var) {
        Object root = var.getLoweringRoot();
        return root != null && root == var.getRawRoot();
    }
}
