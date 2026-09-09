package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.*;

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
            if (ie.fexpr instanceof VarExpr ve && !ve.var.isDynamic()) {
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
            int c = 0;
            for (int i = 0; i < sme.args.count(); i++) {
                c += countExprLocals((Expr) sme.args.nth(i));
            }
            return c;
        }
        if (expr instanceof InstanceMethodExpr ime) {
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
     * Collects every {@link LocalBinding} that {@code expr} can read, so a caller can tell which
     * {@code let*} bindings are dead in a body and may be cleared.
     * <p>
     * A binding captured by an inner {@code fn*} counts as read. Cloffle closures do not copy the
     * captured value when the closure is created: {@code emitClosureCopies} reads the parent frame
     * through {@code LoadLocalMaterialized} when the closure is <em>invoked</em>, so a captured slot
     * has to stay live even after the last textual read. Clojure's {@code closes} propagates a
     * binding to every enclosing {@code ObjExpr}, so collecting {@code closes()} at each nested
     * {@code fn*} without descending into it also covers bindings captured further in.
     * <p>
     * Returns {@code false} when an expression type is not recognized. The set is then incomplete
     * and no clearing decision may be based on it, which keeps an unknown node from being read as
     * "reads nothing".
     */
    static boolean collectReadBindings(Expr expr, java.util.Set<LocalBinding> out) {
        return walkLocals(expr, out, false);
    }

    /**
     * Bindings captured by nested {@code fn*} / {@code reify*} reachable from {@code expr}.
     * Stops at each closure after recording its {@code closes()} set. Returns {@code false} when
     * an unknown expression type means the capture set may be incomplete.
     */
    static boolean collectCapturedBindings(Expr expr, java.util.Set<LocalBinding> out) {
        return walkLocals(expr, out, true);
    }

    private static boolean walkLocals(Expr expr, java.util.Set<LocalBinding> out, boolean capturesOnly) {
        if (expr == null) return true;

        if (expr instanceof LocalBindingExpr lbe) {
            if (!capturesOnly) {
                out.add(lbe.b);
            }
            return true;
        }
        if (expr instanceof FnExpr fe) {
            return addCloses(fe.closes(), out);
        }
        if (expr instanceof NewInstanceExpr nie) {
            if (!addCloses(nie.closes(), out)) return false;
            if (capturesOnly) {
                return true;
            }
            return collectAll(nie.closesExprs, out, capturesOnly);
        }
        if (expr instanceof LetExpr le) {
            for (int i = 0; i < le.bindingInits.count(); i++) {
                BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                if (!walkLocals(bi.init(), out, capturesOnly)) return false;
            }
            return walkLocals(le.body, out, capturesOnly);
        }
        if (expr instanceof LetFnExpr lfe) {
            for (int i = 0; i < lfe.bindingInits.count(); i++) {
                BindingInit bi = (BindingInit) lfe.bindingInits.nth(i);
                if (!walkLocals(bi.init(), out, capturesOnly)) return false;
            }
            return walkLocals(lfe.body, out, capturesOnly);
        }
        if (expr instanceof BodyExpr be) {
            return collectAll(be.exprs(), out, capturesOnly);
        }
        if (expr instanceof IfExpr ie) {
            return walkLocals(ie.testExpr, out, capturesOnly)
                    && walkLocals(ie.thenExpr, out, capturesOnly)
                    && walkLocals(ie.elseExpr, out, capturesOnly);
        }
        if (expr instanceof InvokeExpr ie) {
            return walkLocals(ie.fexpr, out, capturesOnly) && collectAll(ie.args, out, capturesOnly);
        }
        if (expr instanceof KeywordInvokeExpr kie) {
            return walkLocals(kie.target, out, capturesOnly);
        }
        if (expr instanceof TryExpr te) {
            if (!walkLocals(te.tryExpr, out, capturesOnly)) return false;
            for (int i = 0; i < te.catchExprs.count(); i++) {
                TryExpr.CatchClause cc = (TryExpr.CatchClause) te.catchExprs.nth(i);
                if (!walkLocals(cc.handler, out, capturesOnly)) return false;
            }
            return walkLocals(te.finallyExpr, out, capturesOnly);
        }
        if (expr instanceof RecurExpr re) {
            return collectAll(re.args, out, capturesOnly);
        }
        if (expr instanceof CaseExpr ce) {
            if (!walkLocals(ce.expr, out, capturesOnly)) return false;
            for (Expr then : ce.thens.values()) {
                if (!walkLocals(then, out, capturesOnly)) return false;
            }
            return walkLocals(ce.defaultExpr, out, capturesOnly);
        }
        if (expr instanceof StaticMethodExpr sme) {
            return collectAll(sme.args, out, capturesOnly);
        }
        if (expr instanceof InstanceMethodExpr ime) {
            return walkLocals(ime.target, out, capturesOnly) && collectAll(ime.args, out, capturesOnly);
        }
        if (expr instanceof NewExpr ne) {
            return collectAll(ne.args, out, capturesOnly);
        }
        if (expr instanceof DefExpr de) {
            if (de.initProvided && !walkLocals(de.init, out, capturesOnly)) return false;
            return walkLocals(de.meta, out, capturesOnly);
        }
        if (expr instanceof AssignExpr ae) {
            if (!(ae.target instanceof Expr assignTarget)) return false;
            return walkLocals(assignTarget, out, capturesOnly) && walkLocals(ae.val, out, capturesOnly);
        }
        if (expr instanceof ThrowExpr te) {
            return walkLocals(te.excExpr, out, capturesOnly);
        }
        if (expr instanceof MetaExpr me) {
            return walkLocals(me.expr, out, capturesOnly) && walkLocals(me.meta, out, capturesOnly);
        }
        if (expr instanceof InstanceOfExpr ioe) {
            return walkLocals(ioe.expr, out, capturesOnly);
        }
        if (expr instanceof InstanceFieldExpr ife) {
            return walkLocals(ife.target, out, capturesOnly);
        }
        if (expr instanceof MonitorEnterExpr mee) {
            return walkLocals(mee.target, out, capturesOnly);
        }
        if (expr instanceof MonitorExitExpr mxe) {
            return walkLocals(mxe.target, out, capturesOnly);
        }
        if (expr instanceof ListExpr le) {
            return collectAll(le.args, out, capturesOnly);
        }
        if (expr instanceof VectorLikeExpr ve) {
            return collectAll(ve.args(), out, capturesOnly);
        }
        if (expr instanceof SetExpr se) {
            return collectAll(se.keys, out, capturesOnly);
        }
        if (expr instanceof MapLikeExpr me) {
            return collectAll(me.keyvals(), out, capturesOnly);
        }
        if (expr instanceof StaticInvokeExpr sie) {
            return collectAll(sie.args, out, capturesOnly);
        }
        return expr instanceof ConstantExpr
                || expr instanceof NilExpr
                || expr instanceof EmptyExpr
                || expr instanceof KeywordExpr
                || expr instanceof StringExpr
                || expr instanceof BooleanExpr
                || expr instanceof NumberExpr
                || expr instanceof VarExpr
                || expr instanceof TheVarExpr
                || expr instanceof ImportExpr
                || expr instanceof StaticFieldExpr
                || expr instanceof QualifiedMethodExpr
                || expr instanceof UnresolvedVarExpr;
    }

    private static boolean addCloses(clojure.lang.IPersistentMap closes, java.util.Set<LocalBinding> out) {
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(closes); s != null; s = s.next()) {
            out.add((LocalBinding) ((java.util.Map.Entry) s.first()).getKey());
        }
        return true;
    }

    private static boolean collectAll(clojure.lang.IPersistentVector exprs, java.util.Set<LocalBinding> out,
                                      boolean capturesOnly) {
        if (exprs == null) return true;
        for (int i = 0; i < exprs.count(); i++) {
            if (!walkLocals((Expr) exprs.nth(i), out, capturesOnly)) return false;
        }
        return true;
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
