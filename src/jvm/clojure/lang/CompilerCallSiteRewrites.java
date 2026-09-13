/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
 */
package clojure.lang;

import static clojure.lang.Compiler.*;

/** Analyze-time call-site folds and rewrites (InvokeExpr, StaticInvoke, host statics). */
final class CompilerCallSiteRewrites {
    private CompilerCallSiteRewrites() {
    }

    static Expr tryFoldOrRewrite(Expr fexpr, IPersistentVector args, Symbol tag, boolean tailPosition) {
        Expr folded = tryConstantFoldTupleConj(fexpr, args);
        if (folded == null) folded = tryConstantFoldVecQuotedLiteral(fexpr, args);
        if (folded == null) folded = tryConstantFoldMapIdentity(fexpr, args);
        if (folded == null) folded = tryConstantFoldMapvIdentity(fexpr, args);
        if (folded == null) folded = tryConstantFoldFirstLazySeqLiteral(fexpr, args);
        if (folded == null) folded = tryRewriteFirstOnKeywordMapEvs(fexpr, args);
        if (folded == null) folded = tryConstantFoldStrLiterals(fexpr, args);
        if (folded == null) folded = tryConstantFoldMapPureOnLiteralVector(fexpr, args);
        if (folded == null) folded = tryRewriteMapEphemeralVectorSeqPure(fexpr, args, tag, tailPosition);
        if (folded == null) folded = tryConstantFoldIntoEmptyVector(fexpr, args);
        if (folded == null) folded = tryConstantFoldInto3ArgTransducer(fexpr, args);
        if (folded == null) folded = tryRewriteInto3ArgFilterMapMaterialize(fexpr, args, tag, tailPosition);
        if (folded == null) folded = tryRewriteFilterEphemeralVectorPure(fexpr, args, tag, tailPosition);
        return folded;
    }

private static final Keyword CLOFFLE_OP_TUPLE_CONJ = Keyword.intern("TupleConj");
private static final int FOLD_MAX_SMALL_VECTOR = 8;

/** Analyze folds that erase call sites require {@link Var#isCloffleLocked}. */
private static boolean lockedEquals(Var expected, Var actual) {
	return expected.equals(actual) && Var.isCloffleLocked(actual);
}

/** Match a resolved host static call by {@link java.lang.reflect.Method} identity. */
static boolean isStaticMethod(StaticMethodExpr sme, java.lang.reflect.Method expected) {
	if (sme.c != expected.getDeclaringClass()) {
		return false;
	}
	if (sme.method != null) {
		return expected.equals(sme.method);
	}
	return expected.getName().equals(sme.methodName)
			&& sme.args.count() == expected.getParameterCount();
}

private static StaticMethodExpr staticCall(Symbol tag, java.lang.reflect.Method m,
		IPersistentVector args, boolean tailPosition) {
	return new StaticMethodExpr((String) SOURCE.deref(), lineDeref(), columnDeref(), tag,
			m.getDeclaringClass(), m.getName(), m, args, tailPosition);
}

private static final class CompFilterMapKeyword {
	final Expr predExpr;
	final Expr mapFnExpr;
	/** {@code true} when xf applies map before filter on elements (comp filter map). */
	final boolean mapBeforeFilter;

	CompFilterMapKeyword(Expr predExpr, Expr mapFnExpr, boolean mapBeforeFilter) {
		this.predExpr = predExpr;
		this.mapFnExpr = mapFnExpr;
		this.mapBeforeFilter = mapBeforeFilter;
	}
}

/**
 * Constant-fold {@code (map identity <literal vector ≤8>)} to the vector literal (identity is a no-op).
 */
private static Expr tryConstantFoldMapIdentity(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 2 || !(fexpr instanceof VarExpr mapVe)) {
		return null;
	}
	if (!lockedEquals(mapVar, mapVe.var)) {
		return null;
	}
	Expr fnExpr = (Expr) argExprs.nth(0);
	if (!(fnExpr instanceof VarExpr idVe) || !identityVar.equals(idVe.var)) {
		return null;
	}
	Expr collExpr = (Expr) argExprs.nth(1);
	IPersistentVector vec = vectorLiteralForFold(collExpr);
	if (vec == null) {
		return null;
	}
	IPersistentVector argFormExprs = collExpr instanceof ConstantVectorExpr cve ? cve.args
			: PersistentVector.EMPTY;
	return new ConstantVectorExpr(argFormExprs, vec);
}

/**
 * Constant-fold {@code (mapv identity <literal vector ≤8>)} to the vector (bench hygiene / same
 * shape as map-identity fold).
 */
private static Expr tryConstantFoldMapvIdentity(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 2 || !(fexpr instanceof VarExpr mapvVe)) {
		return null;
	}
	if (!lockedEquals(mapvVar, mapvVe.var)) {
		return null;
	}
	Expr fnExpr = (Expr) argExprs.nth(0);
	if (!isIdentityFnExprForMap(fnExpr)) {
		return null;
	}
	IPersistentVector vec = vectorLiteralForFold((Expr) argExprs.nth(1));
	if (vec == null) {
		return null;
	}
	return new ConstantVectorExpr(PersistentVector.EMPTY, vec);
}

/**
 * Constant-fold {@code (first (lazy-seq LITERAL))} when the lazy-seq body is a single literal
 * collection (vector/list) — PEA ladder for {@code lazy-seq-first} without runtime LazySeq.
 */
private static Expr tryConstantFoldFirstLazySeqLiteral(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 1 || !(fexpr instanceof VarExpr firstVe) || !lockedEquals(firstVar, firstVe.var)) {
		return null;
	}
	return constantFoldFirstLazySeqArg((Expr) argExprs.nth(0));
}

static Expr tryConstantFoldRtFirstLazySeqStaticMethod(StaticMethodExpr sm) {
	if (!lockedCallSiteRewritesEnabled() || !isStaticMethod(sm, RT_FIRST_METHOD)) {
		return null;
	}
	Expr arg = (Expr) sm.args.nth(0);
	Expr fused = tryRewriteFirstOnKeywordMapEvsArg(arg);
	if (fused != null) {
		return fused;
	}
	return constantFoldFirstLazySeqArg(arg);
}

/**
 * Fuse {@code (first (map :kw vectorish))} when analyze already rewrote map to
 * {@link EphemeralVectorSeqKeywordCreateExpr} with index 0. Ignores {@code with-redefs} on {@code #'first}.
 */
private static Expr tryRewriteFirstOnKeywordMapEvs(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 1 || !(fexpr instanceof VarExpr firstVe) || !lockedEquals(firstVar, firstVe.var)) {
		return null;
	}
	return tryRewriteFirstOnKeywordMapEvsArg((Expr) argExprs.nth(0));
}

private static Expr tryRewriteFirstOnKeywordMapEvsArg(Expr arg) {
	arg = unwrapMetaExpr(arg);
	if (!(arg instanceof EphemeralVectorSeqKeywordCreateExpr evs)) {
		return null;
	}
	if (!isNumberExprZero(evs.index)) {
		return null;
	}
	return new VectorKeywordMapFirstExpr(
			(String) SOURCE.deref(), lineDeref(), columnDeref(), evs.keyword, evs.coll);
}

private static boolean isNumberExprZero(Expr e) {
	e = unwrapMetaExpr(e);
	return e instanceof NumberExpr ne && ne.n.intValue() == 0;
}

private static Expr constantFoldFirstLazySeqArg(Expr arg) {
	arg = unwrapMetaExpr(arg);
	// After macroexpand: (new clojure.lang.LazySeq (fn* [] body)). Before: (lazy-seq body).
	Expr body = null;
	if (arg instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && lazySeqVar.equals(ve.var)
			&& ie.args.count() == 1) {
		body = (Expr) ie.args.nth(0);
	} else if (arg instanceof NewExpr ne && ne.c == LazySeq.class && ne.args.count() == 1) {
		Expr fnArg = (Expr) ne.args.nth(0);
		if (fnArg instanceof FnExpr fe && RT.count(fe.methods) == 1) {
			FnMethod fm = (FnMethod) RT.seq(fe.methods).first();
			body = fm.body;
		}
	}
	if (body == null) {
		return null;
	}
	body = unwrapMetaExpr(body);
	if (body instanceof BodyExpr be && be.exprs.count() == 1) {
		body = (Expr) be.exprs.nth(0);
	}
	Object coll = literalValueForFold(body);
	if (coll == null) {
		coll = collectionLiteralForFold(body);
	}
	if (!(coll instanceof IPersistentCollection ipc) || ipc.count() == 0) {
		return null;
	}
	return new ConstantExpr(RT.first(coll));
}

/** Constant-fold {@code (str lit lit…)} — disabled so {@code with-redefs} on #'str is observed
 *  (analyze-time fold would erase the call before bindRoot). Runtime {@code CoreStr*} ops remain. */
private static Expr tryConstantFoldStrLiterals(Expr fexpr, IPersistentVector argExprs) {
	return null;
}


/**
 * Constant-fold {@code (map <pure f> <literal vector of maps ≤8>)} — disabled: folding into a
 * {@link PersistentTuple}/{@link PersistentVector} breaks {@code realized?} ({@code ^IPending} cast)
 * vs stock LazySeq. Runtime {@link EphemeralVectorSeq} covers the PEA path instead.
 */
private static Expr tryConstantFoldMapPureOnLiteralVector(Expr fexpr, IPersistentVector argExprs) {
	return null;
}

private static IPersistentVector vectorSourceForMapPureFold(Expr collExpr) {
	collExpr = unwrapMetaExpr(collExpr);
	if (collExpr instanceof StaticMethodExpr sme
			&& isStaticMethod(sme, FEVS_CREATE)) {
		IPersistentVector inner = vectorSourceForMapPureFoldInner((Expr) sme.args.nth(1));
		if (inner != null) {
			return filterLiteralVectorAtAnalyze((Expr) sme.args.nth(0), inner);
		}
		return null;
	}
	if (collExpr instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && filterVar.equals(ve.var)
			&& ie.args.count() == 2) {
		IPersistentVector inner = vectorSourceForMapPureFoldInner((Expr) ie.args.nth(1));
		if (inner != null) {
			return filterLiteralVectorAtAnalyze((Expr) ie.args.nth(0), inner);
		}
		return null;
	}
	return vectorSourceForMapPureFoldInner(collExpr);
}

private static IPersistentVector vectorSourceForMapPureFoldInner(Expr collExpr) {
	collExpr = unwrapMetaExpr(collExpr);
	IPersistentVector v = vectorLiteralForFold(collExpr);
	if (v != null) {
		return v;
	}
	if (collExpr instanceof LocalBindingExpr lbe && lbe.b.init != null) {
		return vectorLiteralForFold(lbe.b.init);
	}
	return null;
}

private static IFn fnForLiteralFilterFold(Expr predExpr) {
	try {
		predExpr = unwrapMetaExpr(predExpr);
		if (predExpr instanceof FnExpr fn && fn.compiledClass == null) {
			fn.compile(fn.isVariadic() ? "clojure/lang/RestFn" : "clojure/lang/AFunction",
					null, fn.onceOnly);
		}
		Object predVal = predExpr.eval();
		return predVal instanceof IFn pred ? pred : null;
	} catch (Throwable t) {
		return null;
	}
}

private static IPersistentVector filterLiteralVectorAtAnalyze(Expr predExpr, IPersistentVector source) {
	if (source == null || source.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	try {
		IFn pred = fnForLiteralFilterFold(predExpr);
		if (pred == null) {
			return null;
		}
		IPersistentVector acc = PersistentVector.EMPTY;
		for (int i = 0; i < source.count(); i++) {
			Object elt = source.nth(i);
			if (RT.booleanCast(pred.invoke(elt))) {
				acc = (IPersistentVector) acc.cons(elt);
			}
		}
		return acc;
	} catch (Throwable t) {
		return null;
	}
}

private static Expr unwrapMetaExpr(Expr e) {
	while (e instanceof MetaExpr me) {
		e = me.expr;
	}
	return e;
}

private static IPersistentVector mapPureFoldedVector(Expr fnExpr, IPersistentVector source) {
	if (source == null || source.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	fnExpr = unwrapMetaExpr(fnExpr);
	if (!(fnExpr instanceof KeywordExpr ke)) {
		return null;
	}
	Keyword kw = ke.k;
	IPersistentVector acc = PersistentVector.EMPTY;
	for (int i = 0; i < source.count(); i++) {
		Object elt = source.nth(i);
		if (!(elt instanceof IPersistentMap map)) {
			return null;
		}
		if (!map.containsKey(kw)) {
			return null;
		}
		acc = (IPersistentVector) acc.cons(map.valAt(kw));
	}
	return acc;
}

private static IPersistentVector vectorLiteralForIntoFrom(Expr fromExpr) {
	IPersistentVector vec = vectorLiteralForFold(fromExpr);
	if (vec != null) {
		return vec;
	}
	fromExpr = unwrapMetaExpr(fromExpr);
	if (fromExpr instanceof LocalBindingExpr lbe && lbe.b.init != null) {
		vec = vectorLiteralForFold(lbe.b.init);
		if (vec != null) {
			return vec;
		}
	}
	if (fromExpr instanceof InvokeExpr ie
			&& ie.fexpr instanceof VarExpr mapVe
			&& mapVar.equals(mapVe.var)
			&& ie.args.count() == 2) {
		return mapPureFoldedVector((Expr) ie.args.nth(0),
				vectorSourceForMapPureFold((Expr) ie.args.nth(1)));
	}
	return null;
}

private static CompFilterMapKeyword parseCompFilterMapKeyword(Expr xformExpr) {
	xformExpr = unwrapMetaExpr(xformExpr);
	if (!(xformExpr instanceof InvokeExpr ie) || !(ie.fexpr instanceof VarExpr ve)) {
		return null;
	}
	if (!compVar.equals(ve.var) || ie.args.count() != 2) {
		return null;
	}
	Expr outer = unwrapMetaExpr((Expr) ie.args.nth(0));
	Expr inner = unwrapMetaExpr((Expr) ie.args.nth(1));
	Expr filterOuter = filterPredExpr(outer);
	Expr mapOuter = mapKeywordFnExpr(outer);
	Expr filterInner = filterPredExpr(inner);
	Expr mapInner = mapKeywordFnExpr(inner);
	if (filterOuter != null && mapInner != null) {
		return new CompFilterMapKeyword(filterOuter, mapInner, true);
	}
	if (mapOuter != null && filterInner != null) {
		return new CompFilterMapKeyword(filterInner, mapOuter, false);
	}
	return null;
}

private static Expr filterPredExpr(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && filterVar.equals(ve.var)) {
		if (ie.args.count() == 2) {
			return (Expr) ie.args.nth(0);
		}
		if (ie.args.count() == 1) {
			return (Expr) ie.args.nth(0);
		}
	}
	return null;
}

private static Expr mapKeywordFnExpr(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && mapVar.equals(ve.var)) {
		if (ie.args.count() == 2 && isPureFnExprForMap((Expr) ie.args.nth(0))) {
			return (Expr) ie.args.nth(0);
		}
		if (ie.args.count() == 1 && isPureFnExprForMap((Expr) ie.args.nth(0))) {
			return (Expr) ie.args.nth(0);
		}
	}
	return null;
}

private static IPersistentVector materializeCompFilterMapFold(CompFilterMapKeyword c, IPersistentVector source) {
	if (source == null || source.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	try {
		IFn pred = fnForLiteralFilterFold(c.predExpr);
		if (pred == null) {
			return null;
		}
		Expr mapFnExpr = unwrapMetaExpr(c.mapFnExpr);
		if (!(mapFnExpr instanceof KeywordExpr ke)) {
			return null;
		}
		Keyword mapKw = ke.k;
		if (!c.mapBeforeFilter) {
			IPersistentVector filtered = filterLiteralVectorAtAnalyze(c.predExpr, source);
			if (filtered == null) {
				return null;
			}
			return mapPureFoldedVector(c.mapFnExpr, filtered);
		}
		IPersistentVector acc = PersistentVector.EMPTY;
		for (int i = 0; i < source.count(); i++) {
			Object elt = source.nth(i);
			if (!(elt instanceof IPersistentMap map) || !map.containsKey(mapKw)) {
				return null;
			}
			Object mapped = map.valAt(mapKw);
			if (RT.booleanCast(pred.invoke(mapped))) {
				acc = (IPersistentVector) acc.cons(mapped);
			}
		}
		return acc;
	} catch (Throwable t) {
		return null;
	}
}

private static IPersistentVector vectorLiteralForInto3ArgFrom(Expr xformExpr, Expr fromExpr) {
	CompFilterMapKeyword c = parseCompFilterMapKeyword(xformExpr);
	if (c == null) {
		return null;
	}
	IPersistentVector source = vectorSourceForMapPureFoldInner(fromExpr);
	if (source == null && fromExpr instanceof LocalBindingExpr lbe && lbe.b.init != null) {
		source = vectorSourceForMapPureFoldInner(lbe.b.init);
	}
	return materializeCompFilterMapFold(c, source);
}

private static Expr tryConstantFoldInto3ArgTransducer(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 3 || !(fexpr instanceof VarExpr intoVe)) {
		return null;
	}
	if (!lockedEquals(intoVar, intoVe.var)) {
		return null;
	}
	if (!isEmptyVectorLiteral((Expr) argExprs.nth(0))) {
		return null;
	}
	IPersistentVector vec = vectorLiteralForInto3ArgFrom((Expr) argExprs.nth(1), (Expr) argExprs.nth(2));
	if (vec == null) {
		return null;
	}
	return new ConstantVectorExpr(PersistentVector.EMPTY, vec);
}

private static Expr tryRewriteInto3ArgFilterMapMaterialize(Expr fexpr, IPersistentVector argExprs, Symbol tag,
		boolean tailPosition) {
	if (argExprs.count() != 3 || !(fexpr instanceof VarExpr intoVe)) {
		return null;
	}
	if (!lockedEquals(intoVar, intoVe.var)) {
		return null;
	}
	if (!isEmptyVectorLiteral((Expr) argExprs.nth(0))) {
		return null;
	}
	CompFilterMapKeyword c = parseCompFilterMapKeyword((Expr) argExprs.nth(1));
	if (c == null) {
		return null;
	}
	Expr fromExpr = unwrapSeqOnVectorishColl((Expr) argExprs.nth(2));
	if (materializeCompFilterMapFold(c, vectorSourceForMapPureFoldInner(fromExpr)) != null) {
		return null;
	}
	if (!isVectorishCollForMap(fromExpr)) {
		return null;
	}
	java.lang.reflect.Method method = c.mapBeforeFilter ? FEVS_MAT_MAP_THEN_FILTER : FEVS_MAT_FILTER_THEN_MAP;
	return staticCall(tag, method, RT.vector(c.mapFnExpr, c.predExpr, fromExpr), tailPosition);
}

/**
 * When {@code f} is structurally pure at analyze time and {@code coll} is vector-shaped,
 * {@code (map f coll)} becomes {@code EphemeralVectorSeq/create} (same fast path as
 * {@code core/map} for {@link EphemeralVectorSeq#isPure}). {@code identity} is excluded here
 * (literal identity maps constant-fold; non-literal uses runtime {@code core/map}).
 */
static Expr tryRewriteMapEphemeralVectorSeqPureVar(Var v, Expr fexpr, IPersistentVector argExprs,
		Object tag, boolean tailPosition) {
	Symbol symTag = tag instanceof Symbol s ? s : null;
	if (!lockedEquals(mapVar, v)) {
		return null;
	}
	if (fexpr != null && (!(fexpr instanceof VarExpr mapVe) || !mapVar.equals(mapVe.var))) {
		return null;
	}
	return tryRewriteMapEphemeralVectorSeqPureBody(argExprs, symTag, tailPosition);
}

private static Expr tryRewriteMapEphemeralVectorSeqPure(Expr fexpr, IPersistentVector argExprs,
		Symbol tag, boolean tailPosition) {
	if (argExprs.count() != 2 || !(fexpr instanceof VarExpr mapVe)) {
		return null;
	}
	return tryRewriteMapEphemeralVectorSeqPureVar(mapVe.var, fexpr, argExprs, tag, tailPosition);
}

private static Expr tryRewriteMapEphemeralVectorSeqPureBody(IPersistentVector argExprs,
		Symbol tag, boolean tailPosition) {
	if (argExprs.count() != 2) {
		return null;
	}
	Expr fnExpr = (Expr) argExprs.nth(0);
	if (!isPureFnExprForMap(fnExpr)) {
		return null;
	}
	Expr collExpr = unwrapSeqOnVectorishColl((Expr) argExprs.nth(1));
	Expr mappedOnFilter = tryRewriteMapOnFilteredVectorPure(fnExpr, collExpr, tag, tailPosition);
	if (mappedOnFilter != null) {
		return mappedOnFilter;
	}
	// Do not constant-fold into PersistentTuple/Vector here: callers may use realized?
	// (^IPending / IPending). Emit EphemeralVectorSeq instead (PEA; isRealized true).
	if (isFilterOnVectorishColl(collExpr)) {
		return null;
	}
	if (!isVectorishCollForMap(collExpr)) {
		return null;
	}
	Expr zero = new NumberExpr(0);
	Expr fnUnwrapped = unwrapMetaExpr(fnExpr);
	if (fnUnwrapped instanceof KeywordExpr ke) {
		return new EphemeralVectorSeqKeywordCreateExpr(
				(String) SOURCE.deref(), lineDeref(), columnDeref(), tag,
				ke.k, collExpr, zero);
	}
	return staticCall(tag, EVS_CREATE_IFN_OBJ_INT, RT.vector(fnExpr, collExpr, zero), tailPosition);
}

/**
 * {@code (map pure-f (filter pred vector-shaped-coll))} without lazy-seq when coll stays on vector.
 */
private static Expr tryRewriteMapOnFilteredVectorPure(Expr fnExpr, Expr collExpr, Symbol tag,
		boolean tailPosition) {
	collExpr = unwrapMetaExpr(collExpr);
	Expr predExpr;
	Expr vectorExpr;
	if (collExpr instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve
			&& filterVar.equals(ve.var) && ie.args.count() == 2) {
		predExpr = (Expr) ie.args.nth(0);
		vectorExpr = (Expr) ie.args.nth(1);
	} else if (collExpr instanceof StaticMethodExpr sme
			&& isStaticMethod(sme, FEVS_CREATE)) {
		predExpr = (Expr) sme.args.nth(0);
		vectorExpr = (Expr) sme.args.nth(1);
	} else {
		return null;
	}
	if (!isVectorishCollForMap(vectorExpr)) {
		return null;
	}
	vectorExpr = unwrapSeqOnVectorishColl(vectorExpr);
	// Same as map rewrite: stay on FilteredEphemeralVectorSeq, not ConstantVectorExpr.
	Expr zero = new NumberExpr(0);
	return staticCall(tag, FEVS_CREATE_MAPPED,
			RT.vector(fnExpr, predExpr, vectorExpr, zero), tailPosition);
}

/**
 * {@code (filter pred vector-shaped-coll)} as indexed walk on the vector (no {@code lazy-seq}).
 */
static Expr tryRewriteFilterEphemeralVectorPureVar(Var v, Expr fexpr, IPersistentVector argExprs,
		Object tag, boolean tailPosition) {
	Symbol symTag = tag instanceof Symbol s ? s : null;
	if (!lockedEquals(filterVar, v)) {
		return null;
	}
	if (fexpr != null && (!(fexpr instanceof VarExpr filterVe) || !filterVar.equals(filterVe.var))) {
		return null;
	}
	return tryRewriteFilterEphemeralVectorPureBody(argExprs, symTag, tailPosition);
}

private static Expr tryRewriteFilterEphemeralVectorPure(Expr fexpr, IPersistentVector argExprs, Symbol tag,
		boolean tailPosition) {
	if (argExprs.count() != 2 || !(fexpr instanceof VarExpr filterVe)) {
		return null;
	}
	return tryRewriteFilterEphemeralVectorPureVar(filterVe.var, fexpr, argExprs, tag, tailPosition);
}

private static Expr tryRewriteFilterEphemeralVectorPureBody(IPersistentVector argExprs, Symbol tag,
		boolean tailPosition) {
	if (argExprs.count() != 2) {
		return null;
	}
	Expr predExpr = (Expr) argExprs.nth(0);
	Expr collExpr = unwrapSeqOnVectorishColl((Expr) argExprs.nth(1));
	Expr mapFnExpr = null;
	Expr vectorExpr = collExpr;
	if (collExpr instanceof InvokeExpr mapIe && mapIe.fexpr instanceof VarExpr mapVe
			&& mapVar.equals(mapVe.var) && mapIe.args.count() == 2) {
		mapFnExpr = (Expr) mapIe.args.nth(0);
		vectorExpr = (Expr) mapIe.args.nth(1);
	} else if (collExpr instanceof StaticMethodExpr mapSme
			&& isStaticMethod(mapSme, EVS_CREATE_IFN_OBJ_INT)) {
		mapFnExpr = (Expr) mapSme.args.nth(0);
		vectorExpr = (Expr) mapSme.args.nth(1);
	} else if (collExpr instanceof EphemeralVectorSeqKeywordCreateExpr evs) {
		mapFnExpr = new KeywordExpr(evs.keyword);
		vectorExpr = evs.coll;
	} else if (collExpr instanceof StaticInvokeExpr mapSie
			&& mapVar.equals(mapSie.var)
			&& mapSie.args.count() == 2) {
		mapFnExpr = (Expr) mapSie.args.nth(0);
		vectorExpr = (Expr) mapSie.args.nth(1);
	}
	if (mapFnExpr != null && isIdentityFnExprForMap(mapFnExpr)) {
		// (filter p (map identity c)) ≡ (filter p c)
		mapFnExpr = null;
		collExpr = vectorExpr;
	}
	if (mapFnExpr != null) {
		vectorExpr = unwrapSeqOnVectorishColl(vectorExpr);
		if (!isPureFnExprForMap(mapFnExpr) || !isVectorishCollForMap(vectorExpr)) {
			return null;
		}
	} else if (!isVectorishCollForMap(collExpr)) {
		return null;
	}
	IPersistentVector source = vectorSourceForMapPureFoldInner(vectorExpr);
	if (mapFnExpr != null) {
		CompFilterMapKeyword c = new CompFilterMapKeyword(predExpr, mapFnExpr, true);
		IPersistentVector folded = materializeCompFilterMapFold(c, source);
		if (folded != null) {
			return new ConstantVectorExpr(PersistentVector.EMPTY, folded);
		}
		return staticCall(tag, FEVS_MAT_MAP_THEN_FILTER,
				RT.vector(mapFnExpr, predExpr, vectorExpr), tailPosition);
	}
	IPersistentVector folded = filterLiteralVectorAtAnalyze(predExpr, source);
	if (folded != null) {
		return new ConstantVectorExpr(PersistentVector.EMPTY, folded);
	}
	Expr zero = new NumberExpr(0);
	return staticCall(tag, FEVS_CREATE, RT.vector(predExpr, collExpr, zero), tailPosition);
}

/** Mirrors {@link EphemeralVectorSeq#isPure} at analyze time (not {@code identity}). */
private static boolean isPureFnExprForMap(Expr fnExpr) {
	if (fnExpr instanceof KeywordExpr) {
		return true;
	}
	Object val = literalValueForFold(fnExpr);
	if (val instanceof IPersistentSet || val instanceof IPersistentMap) {
		return true;
	}
	if (fnExpr instanceof MetaExpr me) {
		return isPureFnExprForMap(me.expr);
	}
	return false;
}

private static boolean isIdentityFnExprForMap(Expr fnExpr) {
	fnExpr = unwrapMetaExpr(fnExpr);
	return fnExpr instanceof VarExpr ve && identityVar.equals(ve.var);
}

private static boolean isFilterOnVectorishColl(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && filterVar.equals(ve.var)
			&& ie.args.count() == 2 && isVectorishCollForMap((Expr) ie.args.nth(1))) {
		return true;
	}
	return e instanceof StaticMethodExpr sme
			&& isStaticMethod(sme, FEVS_CREATE)
			&& isVectorishCollForMap((Expr) sme.args.nth(1));
}

private static boolean isVectorishCollForMap(Expr e) {
	Expr withoutSeq = unwrapSeqOnVectorishColl(e);
	if (withoutSeq != unwrapMetaExpr(e)) {
		return true;
	}
	e = unwrapMetaExpr(e);
	if (e instanceof VectorLikeExpr) {
		return true;
	}
	if (e instanceof EmptyExpr ee && ee.coll instanceof IPersistentVector) {
		return true;
	}
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve
			&& (vectorVar.equals(ve.var) || vecVar.equals(ve.var))) {
		return true;
	}
	if (e instanceof StaticMethodExpr sme && sme.c == FilteredEphemeralVectorSeq.class) {
		return true;
	}
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && filterVar.equals(ve.var)
			&& ie.args.count() == 2 && isVectorishCollForMap((Expr) ie.args.nth(1))) {
		return true;
	}
	if (e instanceof LocalBindingExpr lbe) {
		if (lbe.tag != null) {
			Class c = tagClass(lbe.tag);
			if (c != null && IPersistentVector.class.isAssignableFrom(c)) {
				return true;
			}
		}
		if (lbe.b.init != null && isVectorishCollForMap(lbe.b.init)) {
			return true;
		}
	}
	return false;
}

private static Expr unwrapSeqOnVectorishColl(Expr e) {
	Expr unwrapped = unwrapMetaExpr(e);
	Expr inner = null;
	if (unwrapped instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve
			&& seqVar.equals(ve.var) && ie.args.count() == 1) {
		inner = (Expr) ie.args.nth(0);
	} else if (unwrapped instanceof StaticMethodExpr sme && isStaticMethod(sme, RT_SEQ_METHOD)) {
		inner = (Expr) sme.args.nth(0);
	}
	if (inner != null && isVectorishCollForMap(inner)) {
		return unwrapMetaExpr(inner);
	}
	return unwrapped;
}

private static IPersistentVector vectorLiteralForFold(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof ConstantVectorExpr cve) {
		return cve.val.count() <= FOLD_MAX_SMALL_VECTOR ? cve.val : null;
	}
	Object v = literalValueForFold(e);
	if (v instanceof IPersistentVector vec && vec.count() <= FOLD_MAX_SMALL_VECTOR) {
		return vec;
	}
	IPersistentCollection coll = collectionLiteralForFold(e);
	if (coll instanceof IPersistentVector vec && vec.count() <= FOLD_MAX_SMALL_VECTOR) {
		return vec;
	}
	IPersistentVector fromVecCall = vectorLiteralFromVecQuotedCall(e);
	if (fromVecCall != null) {
		return fromVecCall;
	}
	IPersistentVector fromVectorCall = vectorLiteralFromVectorCall(e);
	if (fromVectorCall != null) {
		return fromVectorCall;
	}
	return null;
}

private static IPersistentVector vectorLiteralFromVecQuotedCall(Expr e) {
	e = unwrapMetaExpr(e);
	Expr argExpr = null;
	if (e instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && vecVar.equals(ve.var)
			&& ie.args.count() == 1) {
		argExpr = (Expr) ie.args.nth(0);
	} else if (e instanceof StaticInvokeExpr sie && vecVar.equals(sie.var) && sie.args.count() == 1) {
		argExpr = (Expr) sie.args.nth(0);
	}
	if (argExpr == null) {
		return null;
	}
	IPersistentCollection fromColl = collectionLiteralForVecSource(argExpr);
	if (fromColl != null && fromColl.count() <= FOLD_MAX_SMALL_VECTOR) {
		return LazilyPersistentVector.create(fromColl);
	}
	return null;
}

/** Peel {@code (vector lit…)} when every arg is a foldable literal (≤ small-vector max). */
private static IPersistentVector vectorLiteralFromVectorCall(Expr e) {
	e = unwrapMetaExpr(e);
	if (!(e instanceof InvokeExpr ie) || !(ie.fexpr instanceof VarExpr ve) || !vectorVar.equals(ve.var)) {
		return null;
	}
	if (ie.args.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	ITransientCollection tv = PersistentVector.EMPTY.asTransient();
	for (int i = 0; i < ie.args.count(); i++) {
		Expr arg = (Expr) ie.args.nth(i);
		Object lit = literalValueForFold(arg);
		if (lit == null && !(arg instanceof NilExpr)) {
			return null;
		}
		tv = tv.conj(lit);
	}
	return (IPersistentVector) tv.persistent();
}


private static IPersistentCollection collectionLiteralForVecSource(Expr argExpr) {
	argExpr = unwrapMetaExpr(argExpr);
	IPersistentCollection fromColl = collectionLiteralForFold(argExpr);
	if (fromColl != null) {
		return fromColl;
	}
	if (argExpr instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && listVar.equals(ve.var)) {
		return collectionLiteralFromListInvoke(ie.args);
	}
	if (argExpr instanceof StaticInvokeExpr sie && listVar.equals(sie.var)) {
		return collectionLiteralFromListInvoke(sie.args);
	}
	if (argExpr instanceof LocalBindingExpr lbe && lbe.b.init != null) {
		Expr init = unwrapMetaExpr(lbe.b.init);
		if (init instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && listVar.equals(ve.var)) {
			return collectionLiteralFromListInvoke(ie.args);
		}
		if (init instanceof StaticInvokeExpr sie && listVar.equals(sie.var)) {
			return collectionLiteralFromListInvoke(sie.args);
		}
	}
	return null;
}

private static IPersistentCollection collectionLiteralFromListInvoke(IPersistentVector args) {
	if (args == null || args.count() == 0 || args.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	Object[] arr = new Object[args.count()];
	for (int i = 0; i < args.count(); i++) {
		Object x = literalValueForFold((Expr) args.nth(i));
		if (x == null) {
			return null;
		}
		arr[i] = x;
	}
	return PersistentList.createListFromArray(arr);
}

static Expr tryConstantFoldStaticInvoke(Var v, IPersistentVector argv) {
	if (lockedEquals(mapVar, v)) {
		if (argv.count() == 2) {
			Expr folded = tryConstantFoldMapPureOnLiteralVector(
					new VarExpr(mapVar, null, 0, 0), argv);
			if (folded != null) {
				return folded;
			}
		}
	}
	if (lockedEquals(intoVar, v) && argv.count() == 2) {
		Expr folded = constantFoldIntoEmptyFrom((Expr) argv.nth(0), (Expr) argv.nth(1));
		if (folded != null) {
			return folded;
		}
	}
	return null;
}

private static Expr tryConstantFoldVecQuotedLiteral(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 1 || !(fexpr instanceof VarExpr ve) || !lockedEquals(vecVar, ve.var)) {
		return null;
	}
	return constantFoldVecQuotedLiteralFromAnalyzedArgs(argExprs);
}

static Expr constantFoldVecQuotedLiteralFromAnalyzedArgs(IPersistentVector vecArgs) {
	if (vecArgs.count() != 1) {
		return null;
	}
	IPersistentCollection fromColl = collectionLiteralForFold((Expr) vecArgs.nth(0));
	if (fromColl == null || fromColl.count() > FOLD_MAX_SMALL_VECTOR) {
		return null;
	}
	return new ConstantVectorExpr(PersistentVector.EMPTY, LazilyPersistentVector.create(fromColl));
}

/**
 * Constant-fold {@code (into [] <literal vector ≤8>)} to the source vector (empty into is a copy of from).
 */
private static Expr tryConstantFoldIntoEmptyVector(Expr fexpr, IPersistentVector argExprs) {
	if (argExprs.count() != 2 || !(fexpr instanceof VarExpr intoVe) || !lockedEquals(intoVar, intoVe.var)) {
		return null;
	}
	return constantFoldIntoEmptyFrom((Expr) argExprs.nth(0), (Expr) argExprs.nth(1));
}

static boolean isRtIntoHostStaticMethod(StaticMethodExpr sm) {
	return isStaticMethod(sm, RT_INTO_HOST_METHOD);
}

static Expr tryConstantFoldRtIntoStaticMethod(StaticMethodExpr sm) {
	if (!lockedCallSiteRewritesEnabled() || !isRtIntoHostStaticMethod(sm)) {
		return null;
	}
	return constantFoldIntoEmptyFrom((Expr) sm.args.nth(0), (Expr) sm.args.nth(1));
}

private static Expr constantFoldIntoEmptyFrom(Expr toExpr, Expr fromExpr) {
	if (!isEmptyVectorLiteral(toExpr)) {
		return null;
	}
	IPersistentVector vec = vectorLiteralForIntoFrom(fromExpr);
	if (vec == null) {
		return null;
	}
	IPersistentVector argFormExprs = fromExpr instanceof ConstantVectorExpr cve ? cve.args
			: PersistentVector.EMPTY;
	return new ConstantVectorExpr(argFormExprs, vec);
}

private static boolean isEmptyVectorLiteral(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof EmptyExpr ee && ee.coll instanceof IPersistentVector) {
		return true;
	}
	if (e instanceof ConstantVectorExpr cve && cve.val.count() == 0) {
		return true;
	}
	if (e instanceof VectorLikeExpr vle && vle.args().count() == 0) {
		return true;
	}
	Object lit = literalValueForFold(e);
	return lit instanceof IPersistentVector v && v.count() == 0;
}

/**
 * Constant-fold when {@code #'conj}'s {@code :cloffle/op {2 :TupleConj}} applies and operands are
 * literal (including nested conj from {@code []} via {@link EmptyExpr}).
 */
/**
 * Constant-fold {@code (conj coll x)} for literals — disabled so {@code with-redefs} on #'conj
 * is observed (fold runs at analyze time, before with-redefs bindRoot). Runtime {@code TupleConj}
 * still applies under {@code :direct-linking} (stock direct-link contract).
 */
private static Expr tryConstantFoldTupleConj(Expr fexpr, IPersistentVector argExprs) {
	return null;
}

private static Object literalValueForFold(Expr e) {
	if (e instanceof LiteralExpr) {
		return ((LiteralExpr) e).val();
	}
	return null;
}

private static IPersistentCollection collectionLiteralForFold(Expr e) {
	e = unwrapMetaExpr(e);
	if (e instanceof EmptyExpr ee && ee.coll instanceof IPersistentCollection) {
		return (IPersistentCollection) ee.coll;
	}
	Object v = literalValueForFold(e);
	if (v instanceof IPersistentCollection) {
		return (IPersistentCollection) v;
	}
	if (e instanceof InvokeExpr ie
			&& ie.fexpr instanceof VarExpr ve
			&& CLOFFLE_OP_TUPLE_CONJ.equals(Var.cloffleOpForArity(ve.var, 2))
			&& ie.args.count() == 2) {
		Object x = literalValueForFold((Expr) ie.args.nth(1));
		IPersistentCollection coll = collectionLiteralForFold((Expr) ie.args.nth(0));
		if (x != null && coll != null) {
			return RT.conj(coll, x);
		}
	}
	return null;
}

/** Exposed for {@link LetExpr} binding inits such as {@code (vec 'literal)}. */
static IPersistentVector smallVectorLiteralForLetInit(Expr init) {
	return vectorLiteralForFold(init);
}

/** {@code (list literal …)} in {@code let*} inits only — not used from general {@link #collectionLiteralForFold}. */
static IPersistentCollection collectionLiteralListForLetInit(Expr init) {
	init = unwrapMetaExpr(init);
	if (init instanceof InvokeExpr ie && ie.fexpr instanceof VarExpr ve && listVar.equals(ve.var)) {
		return collectionLiteralFromListInvoke(ie.args);
	}
	if (init instanceof StaticInvokeExpr sie && listVar.equals(sie.var)) {
		return collectionLiteralFromListInvoke(sie.args);
	}
	return null;
}


}
