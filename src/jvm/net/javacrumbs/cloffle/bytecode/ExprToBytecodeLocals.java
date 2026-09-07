package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.*;

import static net.javacrumbs.cloffle.bytecode.ExprToBytecode.*;

final class ExprToBytecodeLocals {
    private ExprToBytecodeLocals() {
    }

    /**
     * Estimates how many {@link com.oracle.truffle.api.bytecode.BytecodeLocal}s will be allocated by
     * {@link ExprToBytecode#createTrackedLocal}
     * during emission of a fn body. The count includes closure copies, parameter locals,
     * recur infrastructure, and all temporaries from the body expression tree.
     * <p>
     * This is a best-effort estimate. The Truffle builder may allocate more locals than
     * counted here (e.g. {@code beginTryFinally}'s handler lambda is invoked once per exit
     * point, each call re-running {@code convert} and creating locals). A safety multiplier
     * is applied by the caller ({@link ExprToBytecode#convertFnExpr}) to compensate.
     */
    static int countLocalsNeeded(FnExpr fnExpr) {
        int count = 0;

        // Closure copies: one per closed-over binding
        clojure.lang.IPersistentMap closes = fnExpr.closes();
        if (closes != null) count += closes.count();

        // thisLocal (named fn self-reference)
        if (fnExpr.thisName() != null) count += 1;

        clojure.lang.IPersistentCollection methods = fnExpr.methods();
        int methodCount = methods.count();

        // Arity dispatch uses argCountLocal for all fns (every fn needs an arity guard)
        if (methodCount >= 1) count += 1;

        // Each method's locals
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(methods); s != null; s = s.next()) {
            FnMethod fm = (FnMethod) s.first();
            // Parameter locals
            count += fm.reqParms().count();
            if (fm.restParm() != null) count += 1;
            // emitRecurWhileBody: continue + result
            count += 2;
            // Body expression tree
            count += countExprLocals(fm.body());
        }

        return count;
    }

    /**
     * Counts locals allocated by {@link #convert} and its helpers for a single expression.
     * Does NOT recurse into inner {@code fn*} bodies (those get their own root + pool).
     */
    static int countExprLocals(Expr expr) {
        if (expr == null) return 0;

        if (expr instanceof LetExpr le) {
            int c = le.bindingInits.count(); // one local per binding
            for (int i = 0; i < le.bindingInits.count(); i++) {
                BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                c += countExprLocals(bi.init());
            }
            if (le.isLoop) {
                c += 2; // emitRecurWhileBody: continue + result
            }
            c += countExprLocals(le.body);
            return c;
        }
        if (expr instanceof LetFnExpr lfe) {
            int c = lfe.bindingInits.count(); // one local per binding
            for (int i = 0; i < lfe.bindingInits.count(); i++) {
                BindingInit bi = (BindingInit) lfe.bindingInits.nth(i);
                c += countExprLocals(bi.init());
            }
            c += countExprLocals(lfe.body);
            return c;
        }
        if (expr instanceof BodyExpr be) {
            int c = 0;
            for (int i = 0; i < be.exprs().count(); i++) {
                c += countExprLocals((Expr) be.exprs().nth(i));
            }
            return c;
        }
        if (expr instanceof IfExpr ie) {
            return countExprLocals(ie.testExpr) + countExprLocals(ie.thenExpr) + countExprLocals(ie.elseExpr);
        }
        if (expr instanceof InvokeExpr ie) {
            if (isListCall(ie.fexpr, ie.args)) {
                int c = 0;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            if (isNthCall(ie.fexpr, ie.args)) {
                int c = 0;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            if (isFirstCall(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0));
            }
            if (isConsCall(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0)) + countExprLocals((Expr) ie.args.nth(1));
            }
            if (isRestCall(ie.fexpr, ie.args) || isNextCall(ie.fexpr, ie.args)
                    || isNilCall(ie.fexpr, ie.args) || isSomeCall(ie.fexpr, ie.args)
                    || isSeqCall(ie.fexpr, ie.args) || isCountCall(ie.fexpr, ie.args)
                    || isKeywordCall(ie.fexpr, ie.args) || isNameCall(ie.fexpr, ie.args)
                    || isNamespaceCall(ie.fexpr, ie.args) || isStr1Call(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0));
            }
            if (isStr2Call(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0))
                        + countExprLocals((Expr) ie.args.nth(1));
            }
            if (isStr3Call(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0))
                        + countExprLocals((Expr) ie.args.nth(1))
                        + countExprLocals((Expr) ie.args.nth(2));
            }
            if (isIdenticalCall(ie.fexpr, ie.args) || isEquivCall(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0)) + countExprLocals((Expr) ie.args.nth(1));
            }
            if (isKeywordInvoke(ie.fexpr, ie.args)) {
                int c = countExprLocals((Expr) ie.args.nth(0));
                if (ie.args.count() == 2) c += countExprLocals((Expr) ie.args.nth(1));
                return c;
            }
            if (isGetKeywordCall(ie.fexpr, ie.args)) {
                int c = countExprLocals((Expr) ie.args.nth(0));
                if (ie.args.count() == 3) c += countExprLocals((Expr) ie.args.nth(2));
                return c;
            }
            VarExpr resolvedVe = resolveVarExpr(ie.fexpr);
            if (resolvedVe != null && !resolvedVe.var.isDynamic()) {
                int c = 0;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            int c = 1; // fnLocal
            c += countExprLocals(ie.fexpr);
            for (int i = 0; i < ie.args.count(); i++) {
                c += countExprLocals((Expr) ie.args.nth(i));
            }
            return c;
        }
        if (expr instanceof KeywordInvokeExpr kie) {
            return countExprLocals(kie.target);
        }
        if (expr instanceof TryExpr te) {
            int c = 1; // resultLocal
            c += countExprLocals(te.tryExpr);
            if (te.catchExprs.count() > 0) {
                c += 1; // excLocal
                for (int i = 0; i < te.catchExprs.count(); i++) {
                    TryExpr.CatchClause cc = (TryExpr.CatchClause) te.catchExprs.nth(i);
                    c += 1; // handlerLocal
                    c += countExprLocals(cc.handler);
                }
            }
            if (te.finallyExpr != null) {
                // The Truffle builder invokes the finally handler lambda multiple times
                // (once per exit point: normal exit, exception exit, each catch branch).
                // Each invocation re-runs convert() which calls createTrackedLocal for
                // any InvokeExpr/TryExpr/etc inside the finally body.
                int finallyLocals = countExprLocals(te.finallyExpr);
                int exitPoints = 2 + te.catchExprs.count();
                c += finallyLocals * exitPoints;
            }
            return c;
        }
        if (expr instanceof RecurExpr re) {
            int c = re.args.count() > 1 ? re.args.count() : 0;
            for (int i = 0; i < re.args.count(); i++) {
                c += countExprLocals((Expr) re.args.nth(i));
            }
            return c;
        }
        if (expr instanceof CaseExpr ce) {
            int c = 2; // discLocal + keyLocal
            c += countExprLocals(ce.expr);
            for (Expr then : ce.thens.values()) {
                c += countExprLocals(then);
            }
            c += countExprLocals(ce.defaultExpr);
            return c;
        }
        if (expr instanceof FnExpr) {
            return 0; // inner fn gets its own root
        }
        if (expr instanceof StaticMethodExpr sme) {
            if (isRtGetKeywordMethod(sme)) {
                int c = countExprLocals((Expr) sme.args.nth(0));
                if (sme.args.count() == 3) c += countExprLocals((Expr) sme.args.nth(2));
                return c;
            }
            if (isRtNthMethod(sme)) {
                int c = 0;
                for (int i = 0; i < sme.args.count(); i++) {
                    c += countExprLocals((Expr) sme.args.nth(i));
                }
                return c;
            }
            if (isRtFirstMethod(sme)) {
                return countExprLocals((Expr) sme.args.nth(0));
            }
            if (isRtConsMethod(sme)) {
                return countExprLocals((Expr) sme.args.nth(0)) + countExprLocals((Expr) sme.args.nth(1));
            }
            if (isRtCountMethod(sme)) {
                return countExprLocals((Expr) sme.args.nth(0));
            }
            if (isUtilIdenticalMethod(sme) || isUtilEquivMethod(sme)) {
                return countExprLocals((Expr) sme.args.nth(0)) + countExprLocals((Expr) sme.args.nth(1));
            }
            int c = 0;
            for (int i = 0; i < sme.args.count(); i++) {
                c += countExprLocals((Expr) sme.args.nth(i));
            }
            return c;
        }
        if (expr instanceof InstanceMethodExpr ime) {
            if (isSubstringStr1(ime)) {
                return countExprLocals(getSubstringStr1Target(ime));
            }
            int c = countExprLocals(ime.target);
            for (int i = 0; i < ime.args.count(); i++) {
                c += countExprLocals((Expr) ime.args.nth(i));
            }
            return c;
        }
        if (expr instanceof NewExpr ne) {
            int c = 0;
            for (int i = 0; i < ne.args.count(); i++) {
                c += countExprLocals((Expr) ne.args.nth(i));
            }
            return c;
        }
        if (expr instanceof DefExpr de) {
            int c = 0;
            if (de.initProvided && de.init != null) c += countExprLocals(de.init);
            if (de.meta != null) c += countExprLocals(de.meta);
            return c;
        }
        if (expr instanceof AssignExpr ae) {
            return countExprLocals(ae.val);
        }
        if (expr instanceof ThrowExpr te) {
            return countExprLocals(te.excExpr);
        }
        if (expr instanceof MetaExpr me) {
            return countExprLocals(me.expr) + countExprLocals(me.meta);
        }
        if (expr instanceof InstanceOfExpr ioe) {
            return countExprLocals(ioe.expr);
        }
        if (expr instanceof InstanceFieldExpr ife) {
            return countExprLocals(ife.target);
        }
        if (expr instanceof MonitorEnterExpr mee) {
            return countExprLocals(mee.target);
        }
        if (expr instanceof MonitorExitExpr mee) {
            return countExprLocals(mee.target);
        }
        if (expr instanceof ListExpr le) {
            int c = 0;
            for (int i = 0; i < le.args.count(); i++) c += countExprLocals((Expr) le.args.nth(i));
            return c;
        }
        if (expr instanceof VectorLikeExpr ve) {
            int c = 0;
            for (int i = 0; i < ve.args().count(); i++) c += countExprLocals((Expr) ve.args().nth(i));
            return c;
        }
        if (expr instanceof SetExpr se) {
            int c = 0;
            for (int i = 0; i < se.keys.count(); i++) c += countExprLocals((Expr) se.keys.nth(i));
            return c;
        }
        if (expr instanceof MapLikeExpr me) {
            int c = 0;
            for (int i = 0; i < me.keyvals().count(); i++) c += countExprLocals((Expr) me.keyvals().nth(i));
            return c;
        }
        if (expr instanceof StaticInvokeExpr sie) {
            if (isListStatic(sie)) {
                int c = 0;
                for (int i = 0; i < sie.args.count(); i++) {
                    c += countExprLocals((Expr) sie.args.nth(i));
                }
                return c;
            }
            if (isNthStatic(sie)) {
                int c = 0;
                for (int i = 0; i < sie.args.count(); i++) {
                    c += countExprLocals((Expr) sie.args.nth(i));
                }
                return c;
            }
            if (isFirstStatic(sie)) {
                return countExprLocals((Expr) sie.args.nth(0));
            }
            if (isConsStatic(sie)) {
                return countExprLocals((Expr) sie.args.nth(0)) + countExprLocals((Expr) sie.args.nth(1));
            }
            if (isRestStatic(sie) || isNextStatic(sie)
                    || isNilStatic(sie) || isSomeStatic(sie)
                    || isSeqStatic(sie) || isCountStatic(sie)
                    || isKeywordStatic(sie) || isNameStatic(sie)
                    || isNamespaceStatic(sie) || isStr1Static(sie)) {
                return countExprLocals((Expr) sie.args.nth(0));
            }
            if (isStr2Static(sie)) {
                return countExprLocals((Expr) sie.args.nth(0))
                        + countExprLocals((Expr) sie.args.nth(1));
            }
            if (isStr3Static(sie)) {
                return countExprLocals((Expr) sie.args.nth(0))
                        + countExprLocals((Expr) sie.args.nth(1))
                        + countExprLocals((Expr) sie.args.nth(2));
            }
            if (isIdenticalStatic(sie) || isEquivStatic(sie)) {
                return countExprLocals((Expr) sie.args.nth(0)) + countExprLocals((Expr) sie.args.nth(1));
            }
            if (isGetKeywordStatic(sie)) {
                int c = countExprLocals((Expr) sie.args.nth(0));
                if (sie.args.count() == 3) c += countExprLocals((Expr) sie.args.nth(2));
                return c;
            }
            int c = 0;
            for (int i = 0; i < sie.args.count(); i++) c += countExprLocals((Expr) sie.args.nth(i));
            return c;
        }
        if (expr instanceof NewInstanceExpr nie) {
            int c = 0;
            for (int i = 0; i < nie.closesExprs.count(); i++) c += countExprLocals((Expr) nie.closesExprs.nth(i));
            return c;
        }
        // Leaf expressions: ConstantExpr, NilExpr, EmptyExpr, KeywordExpr, StringExpr,
        // BooleanExpr, NumberExpr, LocalBindingExpr, VarExpr, TheVarExpr, ImportExpr,
        // StaticFieldExpr, QualifiedMethodExpr, UnresolvedVarExpr
        return 0;
    }

    /**
     * True if {@code recur} appears inside this expression targeting the <em>current</em> loop/fn
     * recur point.  Traverses {@code if}, {@code do}, and non-loop {@code let*} but stops at
     * {@code loop*} boundaries ({@code LetExpr.isLoop}) because a nested loop establishes its
     * own recur target — any {@code recur} inside belongs to the inner loop, not the outer one.
     */
    static boolean containsRecur(Expr e) {
        if (e instanceof RecurExpr) {
            return true;
        }
        if (e instanceof IfExpr ie) {
            return containsRecur(ie.thenExpr) || containsRecur(ie.elseExpr);
        }
        if (e instanceof BodyExpr be) {
            int n = be.exprs().count();
            for (int i = 0; i < n; i++) {
                if (containsRecur((Expr) be.exprs().nth(i))) {
                    return true;
                }
            }
            return false;
        }
        if (e instanceof LetExpr le) {
            if (le.isLoop) {
                return false;
            }
            return containsRecur(le.body);
        }
        if (e instanceof LetFnExpr lfe) {
            return containsRecur(lfe.body);
        }
        if (e instanceof CaseExpr ce) {
            for (Expr then : ce.thens.values()) {
                if (containsRecur(then)) return true;
            }
            return containsRecur(ce.defaultExpr);
        }
        return false;
    }
}
