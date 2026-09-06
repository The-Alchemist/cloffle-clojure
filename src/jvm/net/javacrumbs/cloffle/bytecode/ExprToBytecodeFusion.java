package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.*;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Util;
import clojure.lang.Var;

final class ExprToBytecodeFusion {
    private ExprToBytecodeFusion() {
    }

    static boolean isCoreVar(Var var, String name) {
        return var != null
                && var.ns != null
                && "clojure.core".equals(var.ns.name.getName())
                && name.equals(var.sym.getName());
    }

    static boolean isKeywordInvoke(Expr fexpr, IPersistentVector args) {
        return fexpr instanceof KeywordExpr && (args.count() == 1 || args.count() == 2);
    }

    static boolean isGetKeywordCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "get")) {
            return (args.count() == 2 || args.count() == 3) && args.nth(1) instanceof KeywordExpr;
        }
        return false;
    }

    static boolean isRtGetKeywordMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "get".equals(sme.methodName)
                && (sme.args.count() == 2 || sme.args.count() == 3)
                && sme.args.nth(1) instanceof KeywordExpr;
    }

    static boolean isGetKeywordStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "get")
                && (sie.args.count() == 2 || sie.args.count() == 3)
                && sie.args.nth(1) instanceof KeywordExpr;
    }

    static boolean isGetInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "get-in")) {
            return (args.count() == 2 || args.count() == 3) && args.nth(1) instanceof VectorLikeExpr;
        }
        return false;
    }

    static boolean isAssocInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "assoc-in")) {
            return args.count() == 3 && args.nth(1) instanceof VectorLikeExpr;
        }
        return false;
    }

    static boolean isAssocCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "assoc")) {
            return args.count() >= 3 && ((args.count() - 1) % 2 == 0);
        }
        return false;
    }

    static boolean isGetInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "get-in") && (sie.args.count() == 2 || sie.args.count() == 3) && sie.args.nth(1) instanceof VectorLikeExpr;
    }

    static boolean isAssocInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "assoc-in") && sie.args.count() == 3 && sie.args.nth(1) instanceof VectorLikeExpr;
    }

    static boolean isAssocStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "assoc") && sie.args.count() >= 3 && ((sie.args.count() - 1) % 2 == 0);
    }

    static boolean isDissocCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "dissoc")) {
            return args.count() >= 2;
        }
        return false;
    }

    static boolean isDissocStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "dissoc") && sie.args.count() >= 2;
    }

    static boolean isUpdateInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "update-in")) {
            return args.count() >= 3 && args.nth(1) instanceof VectorLikeExpr;
        }
        return false;
    }

    static boolean isUpdateCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "update")) {
            return args.count() >= 3;
        }
        return false;
    }

    static boolean isUpdateInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "update-in") && sie.args.count() >= 3 && sie.args.nth(1) instanceof VectorLikeExpr;
    }

    static boolean isUpdateStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "update") && sie.args.count() >= 3;
    }

    static boolean isMergeWithMapLiteral(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "merge")) {
            return args.count() == 2 && args.nth(1) instanceof MapLikeExpr;
        }
        return false;
    }

    static boolean isMergeStaticWithMapLiteral(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "merge") && sie.args.count() == 2 && sie.args.nth(1) instanceof MapLikeExpr;
    }

    static boolean isNthCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "nth")) {
            return args.count() == 2 || args.count() == 3;
        }
        return false;
    }

    static boolean isNthStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "nth") && (sie.args.count() == 2 || sie.args.count() == 3);
    }

    static boolean isRtNthMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "nth".equals(sme.methodName) && (sme.args.count() == 2 || sme.args.count() == 3);
    }

    static VarExpr resolveVarExpr(Expr expr) {
        if (expr instanceof VarExpr ve) {
            return ve;
        }
        if (expr instanceof LocalBindingExpr lbe && lbe.b != null && !lbe.b.isArg && !lbe.b.recurMistmatch) {
            return resolveVarExpr(lbe.b.init);
        }
        return null;
    }

    static Expr resolveLocalInit(Expr expr) {
        while (expr instanceof LocalBindingExpr lbe && lbe.b != null && !lbe.b.isArg && !lbe.b.recurMistmatch && lbe.b.init != null) {
            expr = lbe.b.init;
        }
        return expr;
    }

    static Expr unwrapSingleBody(Expr expr) {
        while (expr instanceof BodyExpr be && be.exprs().count() == 1) {
            expr = (Expr) be.exprs().nth(0);
        }
        return expr;
    }

    static boolean isPure(Expr expr) {
        expr = unwrapSingleBody(expr);
        if (expr instanceof LiteralExpr || expr instanceof EmptyExpr) {
            return true;
        }
        if (expr instanceof LocalBindingExpr) {
            return true;
        }
        if (expr instanceof VectorExpr ve) {
            for (int i = 0; i < ve.args.count(); i++) {
                if (!isPure((Expr) ve.args.nth(i))) return false;
            }
            return true;
        }
        return false;
    }

    static boolean isConsCall(Expr fexpr, IPersistentVector args) {
        VarExpr ve = resolveVarExpr(fexpr);
        if (ve != null && isCoreVar(ve.var, "cons")) {
            return args.count() == 2;
        }
        return false;
    }

    static boolean isConsStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "cons") && sie.args.count() == 2;
    }

    static boolean isRtConsMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "cons".equals(sme.methodName) && sme.args.count() == 2;
    }

    static boolean isCoreSeqCall(Expr fexpr, IPersistentVector args) {
        VarExpr ve = resolveVarExpr(fexpr);
        if (ve != null && isCoreVar(ve.var, "seq")) {
            return args.count() == 1;
        }
        return false;
    }

    static boolean isCoreSeqStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "seq") && sie.args.count() == 1;
    }

    static boolean isRtSeqMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "seq".equals(sme.methodName) && sme.args.count() == 1;
    }

    record ConsTarget(Expr x, Expr coll) {}
    record ListTarget(IPersistentVector args) {}

    static ConsTarget getConsTarget(Expr expr) {
        expr = unwrapSingleBody(expr);
        if (expr instanceof InvokeExpr ie && isConsCall(ie.fexpr, ie.args)) {
            return new ConsTarget((Expr) ie.args.nth(0), (Expr) ie.args.nth(1));
        }
        if (expr instanceof StaticInvokeExpr sie && isConsStatic(sie)) {
            return new ConsTarget((Expr) sie.args.nth(0), (Expr) sie.args.nth(1));
        }
        if (expr instanceof StaticMethodExpr sme && isRtConsMethod(sme)) {
            return new ConsTarget((Expr) sme.args.nth(0), (Expr) sme.args.nth(1));
        }
        return null;
    }

    static Expr getSeqTarget(Expr expr) {
        expr = unwrapSingleBody(expr);
        if (expr instanceof InvokeExpr ie && isCoreSeqCall(ie.fexpr, ie.args)) {
            return (Expr) ie.args.nth(0);
        }
        if (expr instanceof StaticInvokeExpr sie && isCoreSeqStatic(sie)) {
            return (Expr) sie.args.nth(0);
        }
        if (expr instanceof StaticMethodExpr sme && isRtSeqMethod(sme)) {
            return (Expr) sme.args.nth(0);
        }
        return null;
    }

    static ListTarget getListTarget(Expr expr) {
        expr = unwrapSingleBody(expr);
        if (expr instanceof InvokeExpr ie && isListCall(ie.fexpr, ie.args)) {
            return new ListTarget(ie.args);
        }
        if (expr instanceof StaticInvokeExpr sie && isListStatic(sie)) {
            return new ListTarget(sie.args);
        }
        return null;
    }

    static Expr getFirstLazySeqBody(Expr target) {
        target = unwrapSingleBody(target);
        if (target instanceof NewExpr ne && ne.c == clojure.lang.LazySeq.class && ne.args.count() == 1) {
            Expr fnArg = unwrapSingleBody((Expr) ne.args.nth(0));
            if (fnArg instanceof FnExpr fe && fe.methods != null && fe.methods.count() == 1) {
                FnMethod fm = (FnMethod) RT.first(fe.methods);
                if (fm.numParams() == 0 && fm.body() != null) {
                    return fm.body();
                }
            }
        }
        return null;
    }

    static boolean isFirstCall(Expr fexpr, IPersistentVector args) {
        VarExpr ve = resolveVarExpr(fexpr);
        if (ve != null && isCoreVar(ve.var, "first")) {
            return args.count() == 1;
        }
        return false;
    }

    static boolean isFirstStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "first") && sie.args.count() == 1;
    }

    static boolean isRtFirstMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "first".equals(sme.methodName) && sme.args.count() == 1;
    }

    static boolean isRtRestMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && ("more".equals(sme.methodName) || "rest".equals(sme.methodName)) && sme.args.count() == 1;
    }

    static boolean isRestCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "rest")) {
            return args.count() == 1;
        }
        return false;
    }

    static boolean isRestStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "rest") && sie.args.count() == 1;
    }

    static boolean isNextCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "next")) {
            return args.count() == 1;
        }
        return false;
    }

    static boolean isNextStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "next") && sie.args.count() == 1;
    }

    static boolean isRtNextMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "next".equals(sme.methodName) && sme.args.count() == 1;
    }

    static boolean isListCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "list")) && args.count() <= 8;
    }

    static boolean isListStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "list") && sie.args.count() <= 8;
    }

    static boolean isNilCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "nil?")) && args.count() == 1;
    }

    static boolean isNilStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "nil?") && sie.args.count() == 1;
    }

    static boolean isSomeCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "some?")) && args.count() == 1;
    }

    static boolean isSomeStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "some?") && sie.args.count() == 1;
    }

    static boolean isSeqCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "seq?")) && args.count() == 1;
    }

    static boolean isSeqStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "seq?") && sie.args.count() == 1;
    }

    static boolean isIdenticalCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "identical?")) && args.count() == 2;
    }

    static boolean isEquivCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "=")) && args.count() == 2;
    }

    static boolean isIdenticalStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "identical?") && sie.args.count() == 2;
    }

    static boolean isEquivStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "=") && sie.args.count() == 2;
    }

    static boolean isUtilIdenticalMethod(StaticMethodExpr sme) {
        return sme.c == Util.class && "identical".equals(sme.methodName) && sme.args.count() == 2;
    }

    static boolean isUtilEquivMethod(StaticMethodExpr sme) {
        return sme.c == Util.class && "equiv".equals(sme.methodName) && sme.args.count() == 2;
    }

    static boolean isCountCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "count")) && args.count() == 1;
    }

    static boolean isCountStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "count") && sie.args.count() == 1;
    }

    static boolean isRtCountMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "count".equals(sme.methodName) && sme.args.count() == 1;
    }

    static boolean isKeywordCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "keyword?")) && args.count() == 1;
    }

    static boolean isKeywordStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "keyword?") && sie.args.count() == 1;
    }

    static boolean isNameCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "name")) && args.count() == 1;
    }

    static boolean isNameStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "name") && sie.args.count() == 1;
    }

    static boolean isNamespaceCall(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "namespace")) && args.count() == 1;
    }

    static boolean isNamespaceStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "namespace") && sie.args.count() == 1;
    }

    static boolean isStr1Call(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "str")) && args.count() == 1;
    }

    static boolean isStr1Static(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "str") && sie.args.count() == 1;
    }

    static boolean isStr2Call(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "str")) && args.count() == 2;
    }

    static boolean isStr2Static(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "str") && sie.args.count() == 2;
    }

    static boolean isStr3Call(Expr fexpr, IPersistentVector args) {
        return (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "str")) && args.count() == 3;
    }

    static boolean isStr3Static(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "str") && sie.args.count() == 3;
    }

    static boolean isConstantOne(Expr expr) {
        if (expr instanceof NumberExpr ne) {
            return (ne.n instanceof Integer || ne.n instanceof Long) && ne.n.intValue() == 1;
        }
        if (expr instanceof ConstantExpr ce) {
            return ce.v instanceof Number num && (num instanceof Integer || num instanceof Long) && num.intValue() == 1;
        }
        return false;
    }

    static boolean isStr1(Expr expr) {
        if (expr instanceof StaticInvokeExpr sie) {
            return isCoreVar(sie.var, "str") && sie.args.count() == 1;
        }
        if (expr instanceof InvokeExpr ie) {
            return ie.fexpr instanceof VarExpr ve && isCoreVar(ve.var, "str") && ie.args.count() == 1;
        }
        return false;
    }

    static Expr getStr1Arg(Expr expr) {
        if (expr instanceof StaticInvokeExpr sie) {
            return (Expr) sie.args.nth(0);
        }
        if (expr instanceof InvokeExpr ie) {
            return (Expr) ie.args.nth(0);
        }
        return null;
    }

    static boolean isSubstringStr1(InstanceMethodExpr ime) {
        return "substring".equals(ime.methodName)
                && ime.args.count() == 1
                && isConstantOne((Expr) ime.args.nth(0))
                && isStr1(ime.target);
    }

    static Expr getSubstringStr1Target(InstanceMethodExpr ime) {
        return getStr1Arg(ime.target);
    }

    static Expr getKeywordCheckTarget(Expr testExpr) {
        if (testExpr instanceof StaticInvokeExpr sie) {
            if (isCoreVar(sie.var, "keyword?") && sie.args.count() == 1) {
                return (Expr) sie.args.nth(0);
            }
        }
        if (testExpr instanceof InvokeExpr ie) {
            if (ie.fexpr instanceof VarExpr ve && isCoreVar(ve.var, "keyword?") && ie.args.count() == 1) {
                return (Expr) ie.args.nth(0);
            }
        }
        if (testExpr instanceof InstanceOfExpr ioe) {
            if (ioe.c == Keyword.class) {
                return ioe.expr;
            }
        }
        return null;
    }

    static Expr getKeywordStripTarget(Expr thenExpr) {
        if (thenExpr instanceof InstanceMethodExpr ime && isSubstringStr1(ime)) {
            return getSubstringStr1Target(ime);
        }
        if (thenExpr instanceof StaticInvokeExpr sie) {
            if (isCoreVar(sie.var, "name") && sie.args.count() == 1) {
                return (Expr) sie.args.nth(0);
            }
        }
        if (thenExpr instanceof InvokeExpr ie) {
            if (ie.fexpr instanceof VarExpr ve && isCoreVar(ve.var, "name") && ie.args.count() == 1) {
                return (Expr) ie.args.nth(0);
            }
        }
        return null;
    }

    static boolean isSameExprTarget(Expr a, Expr b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof LocalBindingExpr lba && b instanceof LocalBindingExpr lbb) {
            return lba.b == lbb.b;
        }
        return false;
    }

    static boolean isKeywordFieldNamePattern(IfExpr ie) {
        Expr testTarget = getKeywordCheckTarget(ie.testExpr);
        if (testTarget == null) {
            return false;
        }
        Expr thenTarget = getKeywordStripTarget(ie.thenExpr);
        if (thenTarget == null || !isSameExprTarget(testTarget, thenTarget)) {
            return false;
        }
        Expr elseTarget = isStr1(ie.elseExpr) ? getStr1Arg(ie.elseExpr) : ie.elseExpr;
        return isSameExprTarget(testTarget, elseTarget);
    }

    static Expr getKeywordFieldNameTarget(IfExpr ie) {
        return getKeywordCheckTarget(ie.testExpr);
    }

    static IPersistentVector getExtraArgs(IPersistentVector args, int startIndex) {
        if (args == null || args.count() <= startIndex) {
            return PersistentVector.EMPTY;
        }
        IPersistentVector extra = PersistentVector.EMPTY;
        for (int i = startIndex; i < args.count(); i++) {
            extra = (IPersistentVector) extra.cons(args.nth(i));
        }
        return extra;
    }

    static boolean isRtAssocMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "assoc".equals(sme.methodName) && sme.args.count() == 3;
    }
}
