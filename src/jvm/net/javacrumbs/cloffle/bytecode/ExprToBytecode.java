package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import clojure.lang.Keyword;
import clojure.lang.RT;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayDeque;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeLabel;

public class ExprToBytecode {

    /** Enables {@code beginSource} / {@code beginSourceSection} so nodes expose {@link com.oracle.truffle.api.source.SourceSection}s. */
    public static final BytecodeConfig BYTECODE_CONFIG = BytecodeConfig.WITH_SOURCE;

    private final Clojure language;
    private final Source source;
    private final Map<LocalBinding, BytecodeLocal> localSlots = new HashMap<>();
    
    /**
     * Recur target for {@code loop*} or {@code fn*}: locals to rebind on {@code recur}, a continue flag
     * ({@link RT#T}/{@link RT#F}) for {@link CloffleBytecodeRootNodeGen.Builder#beginWhile()}, and a slot for
     * the value on normal exit. Matches {@code Compiler}’s loop label + {@code RecurExpr.emit}; Truffle uses
     * {@code While} because {@link CloffleBytecodeRootNodeGen.Builder#emitBranch} forbids backward jumps.
     * <p>
     * <b>Primitive {@code recur}:</b> locals use Truffle {@link BytecodeLocal} / object slots; unboxed recur
     * targets are out of scope until real {@code core.clj} loads show {@link com.oracle.truffle.api.frame.FrameSlotTypeException}
     * or bad numerics (see project {@code CLOFFLE_TRUFFLE_BYTECODE.md}, Pending → follow-on polish).
     */
    private record LoopTarget(List<BytecodeLocal> locals, BytecodeLocal continueLocal, BytecodeLocal resultLocal) {}

    private final ArrayDeque<LoopTarget> loopStack = new ArrayDeque<>();

    public ExprToBytecode(Clojure language, Source source) {
        this.language = language;
        this.source = source;
    }

    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name) {
        BytecodeParser<CloffleBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginSource(source);
            b.beginSourceSection(0, source.getLength());
            b.beginRoot();
            b.beginReturn();
            convert(rootExpr, b);
            b.endReturn();
            CloffleBytecodeRootNode rootNode = b.endRoot();
            rootNode.setName(name);
            b.endSourceSection();
            b.endSource();
        };
        return CloffleBytecodeRootNodeGen.create(language, BYTECODE_CONFIG, parser);
    }

    public void convert(Expr expr, CloffleBytecodeRootNodeGen.Builder b) {
        if (expr instanceof ConstantExpr ce) {
            b.emitLoadConstant(ce.v);
        } else if (expr instanceof NilExpr) {
            b.emitLoadNull();
        } else if (expr instanceof EmptyExpr ee) {
            b.emitLoadConstant(ee.coll);
        } else if (expr instanceof KeywordExpr ke) {
            b.emitLoadConstant(ke.k);
        } else if (expr instanceof StringExpr se) {
            b.emitLoadConstant(se.str);
        } else if (expr instanceof BooleanExpr be) {
            b.emitLoadConstant(be.val ? clojure.lang.RT.T : clojure.lang.RT.F);
        } else if (expr instanceof NumberExpr ne) {
            b.emitLoadConstant(ne.val());
        } else if (expr instanceof LocalBindingExpr lbe) {
            BytecodeLocal local = localSlots.get(lbe.b);
            if (local != null) {
                // If the local was created in this RootNode, we can just emitLoadLocal.
                // However, we don't know the root node of the local directly without reflection.
                // But Truffle Bytecode DSL throws IllegalArgumentException if we use emitLoadLocal 
                // on a local from an outer root node.
                try {
                    b.emitLoadLocal(local);
                } catch (IllegalArgumentException e) {
                    // It's from an outer scope. Load the captured frame and use LoadLocalMaterialized.
                    // The captured frame is always argument 0 in our closures.
                    b.beginLoadLocalMaterialized(local);
                    b.emitLoadArgument(0);
                    b.endLoadLocalMaterialized();
                }
            } else {
                if (lbe.b.isArg) {
                    b.emitLoadArgument(lbe.b.idx + 1); // +1 because closure frame might be arg 0?
                } else {
                    System.out.println("WARNING: LocalBinding not found in localSlots: " + lbe.b.sym);
                    b.emitLoadNull(); // Fallback
                }
            }
        } else if (expr instanceof VarExpr ve) {
            b.beginReadVar();
            b.emitLoadConstant(ve.var);
            b.endReadVar();
        } else if (expr instanceof TheVarExpr tve) {
            b.emitLoadConstant(tve.var);
        } else if (expr instanceof DefExpr de) {
            b.beginDefVar(de.initProvided, de.isDynamic);
            b.emitLoadConstant(de.var);
            if (de.initProvided) {
                convert(de.init, b);
            } else {
                b.emitLoadNull();
            }
            if (de.meta != null) {
                convert(de.meta, b);
            } else {
                b.emitLoadNull();
            }
            b.endDefVar();
        } else if (expr instanceof ImportExpr ie) {
            b.emitImportClass(ie.c);
        } else if (expr instanceof AssignExpr ae) {
            if (ae.target instanceof VarExpr ve) {
                b.beginWriteVar();
                b.emitLoadConstant(ve.var);
                convert(ae.val, b);
                b.endWriteVar();
            } else if (ae.target instanceof StaticFieldExpr sfe) {
                b.beginSetStaticField(sfe.c, sfe.fieldName);
                convert(ae.val, b);
                b.endSetStaticField();
            } else if (ae.target instanceof InstanceFieldExpr ife) {
                b.beginSetInstanceField(ife.fieldName);
                convert(ife.target, b);
                convert(ae.val, b);
                b.endSetInstanceField();
            } else if (ae.target instanceof LocalBindingExpr lbe) {
                BytecodeLocal local = localSlots.get(lbe.b);
                if (local != null) {
                    b.beginBlock();
                    b.beginStoreLocal(local);
                    convert(ae.val, b);
                    b.endStoreLocal();
                    b.emitLoadLocal(local);
                    b.endBlock();
                } else {
                    System.out.println("WARNING: AssignExpr LocalBinding not in localSlots: " + lbe.b.sym);
                    b.emitLoadNull();
                }
            } else {
                System.out.println("WARNING: Unimplemented AssignExpr target " + ae.target.getClass().getName());
                b.emitLoadNull();
            }
        } else if (expr instanceof RecurExpr recurExpr) {
            LoopTarget target = loopStack.peek();
            if (target != null) {
                emitLoopRecur(recurExpr, b, target);
            } else {
                b.emitLoadNull();
            }
        } else if (expr instanceof LetExpr le) {
            int numBindings = le.bindingInits.count();
            if (numBindings > 0) {
                b.beginBlock();
                java.util.List<BytecodeLocal> letLocals = new java.util.ArrayList<>();
                for (int i = 0; i < numBindings; i++) {
                    BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                    BytecodeLocal local = b.createLocal();

                    b.beginStoreLocal(local);
                    convert(bi.init(), b);
                    b.endStoreLocal();

                    localSlots.put(bi.binding(), local);
                    letLocals.add(local);
                }

                if (le.isLoop) {
                    emitRecurWhileBody(b, letLocals, le.body);
                } else {
                    convert(le.body, b);
                }

                b.endBlock();
            } else {
                if (le.isLoop) {
                    b.beginBlock();
                    emitRecurWhileBody(b, java.util.List.of(), le.body);
                    b.endBlock();
                } else {
                    convert(le.body, b);
                }
            }
        } else if (expr instanceof LetFnExpr lfe) {
            int n = lfe.bindingInits.count();
            if (n == 0) {
                convert(lfe.body, b);
            } else {
                b.beginBlock();
                java.util.List<BytecodeLocal> letFnLocals = new java.util.ArrayList<>(n);
                // Register every binding local before emitting any init (matches Compiler: pre-seed env) so each
                // fn* body resolves sibling LocalBindingExprs.
                for (int i = 0; i < n; i++) {
                    BindingInit bi = (BindingInit) lfe.bindingInits.nth(i);
                    BytecodeLocal local = b.createLocal();
                    localSlots.put(bi.binding(), local);
                    letFnLocals.add(local);
                }
                for (int i = 0; i < n; i++) {
                    BindingInit bi = (BindingInit) lfe.bindingInits.nth(i);
                    BytecodeLocal local = letFnLocals.get(i);
                    b.beginStoreLocal(local);
                    convert(bi.init(), b);
                    b.endStoreLocal();
                }
                b.beginWireLetFnClosures();
                for (BytecodeLocal loc : letFnLocals) {
                    b.emitLoadLocal(loc);
                }
                b.endWireLetFnClosures();
                convert(lfe.body, b);
                b.endBlock();
            }
        } else if (expr instanceof BodyExpr be) {
            int count = be.exprs().count();
            if (count == 0) {
                b.emitLoadNull();
            } else {
                if (count > 1) {
                    b.beginBlock();
                    for (int i = 0; i < count; i++) {
                        convert((Expr) be.exprs().nth(i), b);
                    }
                    b.endBlock();
                } else {
                    convert((Expr) be.exprs().nth(0), b);
                }
            }
        } else if (expr instanceof ListExpr le) {
            b.beginCreateList();
            for (int i = 0; i < le.args.count(); i++) {
                convert((Expr) le.args.nth(i), b);
            }
            b.endCreateList();
        } else if (expr instanceof VectorExpr ve) {
            b.beginCreateVector();
            for (int i = 0; i < ve.args.count(); i++) {
                convert((Expr) ve.args.nth(i), b);
            }
            b.endCreateVector();
        } else if (expr instanceof SetExpr se) {
            b.beginCreateSet();
            for (int i = 0; i < se.keys.count(); i++) {
                convert((Expr) se.keys.nth(i), b);
            }
            b.endCreateSet();
        } else if (expr instanceof MapExpr me) {
            b.beginCreateMap();
            for (int i = 0; i < me.keyvals.count(); i += 2) {
                convert((Expr) me.keyvals.nth(i), b);
                convert((Expr) me.keyvals.nth(i + 1), b);
            }
            b.endCreateMap();
        } else if (expr instanceof MetaExpr me) {
            b.beginWithMeta();
            convert(me.expr, b);
            convert(me.meta, b);
            b.endWithMeta();
        } else if (expr instanceof KeywordInvokeExpr kie) {
            // (:k target) — Keyword implements IFn (lookup on map / ILookup)
            b.beginBlock();
            BytecodeLocal targetLocal = b.createLocal();
            b.beginStoreLocal(targetLocal);
            convert(kie.target, b);
            b.endStoreLocal();
            b.beginInvoke();
            b.emitLoadConstant(kie.kw.k);
            b.emitLoadLocal(targetLocal);
            b.endInvoke();
            b.endBlock();
        } else if (expr instanceof TryExpr tryExpr) {
            b.beginBlock();
            BytecodeLocal resultLocal = b.createLocal();
            
            if (tryExpr.finallyExpr != null) {
                b.beginTryFinally(() -> {
                    b.beginBlock();
                    convert(tryExpr.finallyExpr, b);
                    b.endBlock();
                });
            }

            if (tryExpr.catchExprs.count() > 0) {
                b.beginTryCatch();
                
                b.beginStoreLocal(resultLocal);
                convert(tryExpr.tryExpr, b);
                b.endStoreLocal();
                
                b.beginBlock(); // catch handler block
                BytecodeLocal excLocal = b.createLocal();
                b.beginStoreLocal(excLocal);
                b.emitLoadException();
                b.endStoreLocal();
                
                BytecodeLabel endCatchLabel = b.createLabel();
                
                for (int i = 0; i < tryExpr.catchExprs.count(); i++) {
                    TryExpr.CatchClause cc = (TryExpr.CatchClause) tryExpr.catchExprs.nth(i);
                    b.beginIfThen();
                    b.beginCheckCatch(cc.c);
                    b.emitLoadLocal(excLocal);
                    b.endCheckCatch();
                    
                    b.beginBlock();
                    BytecodeLocal handlerLocal = b.createLocal();
                    localSlots.put(cc.lb, handlerLocal);
                    b.beginStoreLocal(handlerLocal);
                    b.beginUnwrapException();
                    b.emitLoadLocal(excLocal);
                    b.endUnwrapException();
                    b.endStoreLocal();
                    
                    b.beginStoreLocal(resultLocal);
                    convert(cc.handler, b);
                    b.endStoreLocal();
                    b.emitBranch(endCatchLabel);
                    b.endBlock(); // end handler block
                    
                    b.endIfThen();
                }
                
                // If we get here, no catch clause matched, rethrow
                b.beginThrowException();
                b.emitLoadLocal(excLocal);
                b.endThrowException();
                
                b.emitLabel(endCatchLabel);
                b.endBlock(); // end try-catch exception block
                
                b.endTryCatch();
            } else {
                b.beginStoreLocal(resultLocal);
                convert(tryExpr.tryExpr, b);
                b.endStoreLocal();
            }

            if (tryExpr.finallyExpr != null) {
                b.endTryFinally();
            }
            
            b.emitLoadLocal(resultLocal);
            b.endBlock();
        } else if (expr instanceof ThrowExpr throwExpr) {
            b.beginThrowException();
            convert(throwExpr.excExpr, b);
            b.endThrowException();
        } else if (expr instanceof IfExpr ie) {
            b.beginConditional();
            b.beginTruthiness();
            convert(ie.testExpr, b);
            b.endTruthiness();
            convert(ie.thenExpr, b);
            convert(ie.elseExpr, b);
            b.endConditional();
        } else if (expr instanceof FnExpr fnExpr) {
            // Multi-arity fn* registers each method's parameter LocalBindings in localSlots. Leaving those
            // entries mapped after we finish can make later emits (e.g. outer CreateClosure under Invoke)
            // resolve the wrong BytecodeLocal — e.g. direct ((fn* ([] 10) ...)) saw Long 10 as Invoke's fn.
            Map<LocalBinding, BytecodeLocal> savedLocals = new HashMap<>(localSlots);
            try {
                convertFnExpr(fnExpr, b);
            } finally {
                localSlots.clear();
                localSlots.putAll(savedLocals);
            }
        } else if (expr instanceof StaticMethodExpr sme) {
            b.beginStaticMethod(sme.c, sme.methodName);
            for (int i = 0; i < sme.args.count(); i++) {
                convert((Expr) sme.args.nth(i), b);
            }
            b.endStaticMethod();
        } else if (expr instanceof InstanceMethodExpr ime) {
            b.beginInstanceMethod(ime.methodName);
            convert(ime.target, b);
            for (int i = 0; i < ime.args.count(); i++) {
                convert((Expr) ime.args.nth(i), b);
            }
            b.endInstanceMethod();
        } else if (expr instanceof NewExpr ne) {
            b.beginNewObject(ne.c);
            for (int i = 0; i < ne.args.count(); i++) {
                convert((Expr) ne.args.nth(i), b);
            }
            b.endNewObject();
        } else if (expr instanceof StaticFieldExpr sfe) {
            b.emitStaticField(sfe.c, sfe.fieldName);
        } else if (expr instanceof InstanceFieldExpr ife) {
            b.beginInstanceField(ife.fieldName, ife.requireField);
            convert(ife.target, b);
            b.endInstanceField();
        } else if (expr instanceof InstanceOfExpr ioe) {
            b.beginInstanceOf(ioe.c);
            convert(ioe.expr, b);
            b.endInstanceOf();
        } else if (expr instanceof MonitorEnterExpr mee) {
            b.beginMonitorEnter();
            convert(mee.target, b);
            b.endMonitorEnter();
        } else if (expr instanceof MonitorExitExpr mee) {
            b.beginMonitorExit();
            convert(mee.target, b);
            b.endMonitorExit();
        } else if (expr instanceof StaticInvokeExpr sie) {
            b.beginInvoke();
            b.beginReadVar();
            b.emitLoadConstant(sie.var);
            b.endReadVar();
            for (int i = 0; i < sie.args.count(); i++) {
                convert((Expr) sie.args.nth(i), b);
            }
            b.endInvoke();
        } else if (expr instanceof InvokeExpr ie) {
            // Materialize callee in a local, then Invoke(loadLocal, args...). Block scopes the temp local.
            b.beginBlock();
            BytecodeLocal fnLocal = b.createLocal();
            b.beginStoreLocal(fnLocal);
            convert(ie.fexpr, b);
            b.endStoreLocal();
            b.beginInvoke();
            b.emitLoadLocal(fnLocal);
            for (int i = 0; i < ie.args.count(); i++) {
                convert((Expr) ie.args.nth(i), b);
            }
            b.endInvoke();
            b.endBlock();
        } else if (expr instanceof QualifiedMethodExpr qme) {
            if (qme.preferOverloadedField()) {
                convert(qme.fieldOverload, b);
            } else {
                convert(QualifiedMethodExpr.buildThunkFnStar(C.EVAL, qme), b);
            }
        } else if (expr instanceof CaseExpr ce) {
            convertCaseExpr(ce, b);
        } else {
            System.out.println("WARNING: Unimplemented expression fallback for " + expr.getClass().getName());
            // Fallback for unimplemented expressions
            b.emitLoadNull();
        }
    }

    /**
     * Shared lowering for {@code loop*} and {@code fn*} method bodies: {@code While} + continue flag; tail
     * {@code recur} rebinds {@code locals} (loop bindings or fn params in order, including rest arg).
     * <p>
     * <b>{@code RT/conj} + {@code recur}:</b> {@code recur} args are arbitrary expressions; accumulators built with
     * {@link RT#conj} are covered by {@code clojure.lang.ExprToBytecodeTest.BindingsLoopsAndFunctions#loopStarRecurWithRtConjAccumulator}.
     * Failures in that shape usually indicate collection / {@code conj} semantics, not this loop scaffold.
     */
    private void emitRecurWhileBody(
            CloffleBytecodeRootNodeGen.Builder b, java.util.List<BytecodeLocal> recurLocals, Expr body) {
        BytecodeLocal continueLocal = b.createLocal();
        BytecodeLocal resultLocal = b.createLocal();

        loopStack.push(new LoopTarget(recurLocals, continueLocal, resultLocal));
        try {
            // One Block so the last op (load result) is the value for an enclosing Return / outer Block.
            b.beginBlock();
            b.beginStoreLocal(continueLocal);
            b.emitLoadConstant(RT.T);
            b.endStoreLocal();

            b.beginWhile();
            b.beginTruthiness();
            b.emitLoadLocal(continueLocal);
            b.endTruthiness();
            b.beginBlock();
            b.beginStoreLocal(continueLocal);
            b.emitLoadConstant(RT.F);
            b.endStoreLocal();
            convertLoopBody(body, b);
            b.endBlock();
            b.endWhile();
            b.emitLoadLocal(resultLocal);
            b.endBlock();
        } finally {
            loopStack.pop();
        }
    }

    private void convertLoopBody(Expr body, CloffleBytecodeRootNodeGen.Builder b) {
        LoopTarget lt = loopStack.peek();
        if (body instanceof BodyExpr be) {
            int n = be.exprs().count();
            if (n == 0) {
                b.beginStoreLocal(lt.resultLocal());
                b.emitLoadNull();
                b.endStoreLocal();
            } else if (n == 1) {
                convertLoopTail((Expr) be.exprs().nth(0), b, lt);
            } else {
                b.beginBlock();
                for (int i = 0; i < n - 1; i++) {
                    convert((Expr) be.exprs().nth(i), b);
                }
                convertLoopTail((Expr) be.exprs().nth(n - 1), b, lt);
                b.endBlock();
            }
        } else {
            convertLoopTail(body, b, lt);
        }
    }

    private void convertLoopTail(Expr expr, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        if (expr instanceof RecurExpr re) {
            emitLoopRecur(re, b, lt);
        } else if (expr instanceof IfExpr ie) {
            emitLoopIfExpr(ie, b, lt);
        } else if (expr instanceof BodyExpr) {
            convertLoopBody(expr, b);
        } else if (expr instanceof LetExpr le) {
            if (le.isLoop) {
                // Nested loop*: inner emitRecurWhileBody produces a value; store it in this recur region's result.
                b.beginStoreLocal(lt.resultLocal());
                convert(le, b);
                b.endStoreLocal();
            } else {
                emitLetExprAsLoopTail(le, b);
            }
        } else {
            b.beginStoreLocal(lt.resultLocal());
            convert(expr, b);
            b.endStoreLocal();
        }
    }

    /**
     * Non-{@code loop*} {@code let*} at tail of a {@code loop*}/{@code fn*} recur region: bindings then
     * {@link #convertLoopBody} (tail may be {@code recur}); do not wrap with {@link #convert} (which would emit
     * value {@code Conditional} for {@code if}). {@code loop*} at tail is handled in {@link #convertLoopTail}.
     */
    private void emitLetExprAsLoopTail(LetExpr le, CloffleBytecodeRootNodeGen.Builder b) {
        int numBindings = le.bindingInits.count();
        if (numBindings > 0) {
            b.beginBlock();
            for (int i = 0; i < numBindings; i++) {
                BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                BytecodeLocal local = b.createLocal();
                b.beginStoreLocal(local);
                convert(bi.init(), b);
                b.endStoreLocal();
                localSlots.put(bi.binding(), local);
            }
            convertLoopBody(le.body, b);
            b.endBlock();
        } else {
            convertLoopBody(le.body, b);
        }
    }

    /**
     * Tail {@code if} inside a {@code loop*} or {@code fn*} recur region: void branches ({@link CloffleBytecodeRootNodeGen.Builder#beginIfThenElse})
     * so a tail {@code recur} does not need to fake a value for {@link CloffleBytecodeRootNodeGen.Builder#beginConditional}.
     */
    private void emitLoopIfExpr(IfExpr ie, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        b.beginIfThenElse();
        b.beginTruthiness();
        convert(ie.testExpr, b);
        b.endTruthiness();
        b.beginBlock();
        emitLoopBranchExpr(ie.thenExpr, b, lt);
        b.endBlock();
        b.beginBlock();
        emitLoopBranchExpr(ie.elseExpr, b, lt);
        b.endBlock();
        b.endIfThenElse();
    }

    private void emitLoopBranchExpr(Expr branch, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        if (branch instanceof RecurExpr re) {
            emitLoopRecur(re, b, lt);
        } else if (branch instanceof IfExpr inner) {
            emitLoopIfExpr(inner, b, lt);
        } else {
            b.beginStoreLocal(lt.resultLocal());
            convert(branch, b);
            b.endStoreLocal();
        }
    }

    private void emitLoopRecur(RecurExpr re, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        if (re.args.count() != lt.locals().size()) {
            throw new IllegalStateException(
                    "recur: expected " + lt.locals().size() + " args, got " + re.args.count());
        }
        b.beginBlock();
        for (int i = 0; i < re.args.count(); i++) {
            b.beginStoreLocal(lt.locals().get(i));
            convert((Expr) re.args.nth(i), b);
            b.endStoreLocal();
        }
        b.beginStoreLocal(lt.continueLocal());
        b.emitLoadConstant(RT.T);
        b.endStoreLocal();
        b.endBlock();
    }

    private void convertFnExpr(FnExpr fnExpr, CloffleBytecodeRootNodeGen.Builder b) {
        String thisName = fnExpr.thisName();
        clojure.lang.Compiler.LocalBinding thisBinding = null;
        if (thisName != null) {
            clojure.lang.IPersistentCollection methods = fnExpr.methods();
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(methods); s != null && thisBinding == null; s = s.next()) {
                clojure.lang.Compiler.FnMethod fm = (clojure.lang.Compiler.FnMethod) s.first();
                clojure.lang.IPersistentMap locals = fm.locals();
                if (locals != null) {
                    for (clojure.lang.ISeq ls = clojure.lang.RT.seq(locals); ls != null; ls = ls.next()) {
                        java.util.Map.Entry entry = (java.util.Map.Entry) ls.first();
                        clojure.lang.Compiler.LocalBinding lb = (clojure.lang.Compiler.LocalBinding) entry.getValue();
                        if (!lb.isArg && (thisName.equals(lb.name) || thisName.equals(lb.sym.getName()))) {
                            thisBinding = lb;
                            break;
                        }
                    }
                }
            }
        }

        BytecodeLocal thisLocal = null;
        if (thisBinding != null) {
            thisLocal = b.createLocal();
            localSlots.put(thisBinding, thisLocal);
        }

        b.beginRoot();

        if (thisLocal != null) {
            // Self-reference: closure not wired yet (see Truffle FnNode / this slot).
        }
        b.beginReturn();

        clojure.lang.IPersistentCollection methods = fnExpr.methods();
        int methodCount = methods.count();

        if (methodCount == 1) {
            FnMethod fm = (FnMethod) clojure.lang.RT.seq(methods).first();
            convertFnMethod(fm, b);
        } else {
            // Allocate argCount outside the Block so endBlock's CLEAR_LOCAL does not clear the same slot
            // index reused elsewhere (nested inner roots under let* + StoreLocal showed Long body literals in
            // the binding slot).
            BytecodeLocal argCountLocal = b.createLocal();
            b.beginBlock();
            b.beginStoreLocal(argCountLocal);
            b.emitGetArgCount();
            b.endStoreLocal();

            java.util.List<FnMethod> methodList = new java.util.ArrayList<>();
            for (int i = 0; i < methodCount; i++) {
                methodList.add((FnMethod) clojure.lang.RT.nth(methods, i));
            }

            methodList.sort((m1, m2) -> {
                boolean v1 = m1.restParm() != null;
                boolean v2 = m2.restParm() != null;
                if (v1 && !v2) return 1;
                if (!v1 && v2) return -1;
                return Integer.compare(m1.reqParms().count(), m2.reqParms().count());
            });

            emitFnArityDispatch(b, methodList, 0, argCountLocal, fnExpr.thisName());

            b.endBlock();
        }

        b.endReturn();
        CloffleBytecodeRootNode innerNode = b.endRoot();
        innerNode.setName(fnExpr.thisName() != null ? fnExpr.thisName() : "fn");

        if (thisLocal != null) {
            b.beginBlock();
            b.beginStoreLocal(thisLocal);
            b.beginCreateClosure();
            b.emitLoadConstant(innerNode);
            b.emitGetOuterFrame();
            b.endCreateClosure();
            b.endStoreLocal();
            b.emitLoadLocal(thisLocal);
            b.endBlock();
        } else {
            b.beginCreateClosure();
            b.emitLoadConstant(innerNode);
            b.emitGetOuterFrame();
            b.endCreateClosure();
        }
    }

    private void convertFnMethod(FnMethod fm, CloffleBytecodeRootNodeGen.Builder b) {
        int bindings = fm.reqParms().count() + (fm.restParm() != null ? 1 : 0);
        java.util.ArrayList<BytecodeLocal> paramLocals = new java.util.ArrayList<>(bindings);
        if (bindings > 0) {
            b.beginBlock(); // block for evaluating parameters and body

            for (int i = 0; i < fm.reqParms().count(); i++) {
                LocalBinding lb = (LocalBinding) fm.reqParms().nth(i);
                BytecodeLocal local = b.createLocal();
                localSlots.put(lb, local);
                paramLocals.add(local);

                b.beginStoreLocal(local);
                b.emitLoadArgument(i + 1); // +1 because closure frame might be arg 0?
                b.endStoreLocal();

                // Discard the result of storeLocal so it doesn't leak into the block
                b.beginBlock();
                b.endBlock();
            }

            if (fm.restParm() != null) {
                LocalBinding lb = fm.restParm();
                BytecodeLocal local = b.createLocal();
                localSlots.put(lb, local);
                paramLocals.add(local);

                b.beginStoreLocal(local);
                b.emitGetRestArgs(fm.reqParms().count());
                b.endStoreLocal();

                // Discard the result of storeLocal so it doesn't leak into the block
                b.beginBlock();
                b.endBlock();
            }

            emitRecurWhileBody(b, paramLocals, fm.body());

            b.endBlock(); // end parameter-eval-body block
        } else {
            emitRecurWhileBody(b, java.util.List.of(), fm.body());
        }
    }

    /**
     * Nested {@code Conditional}s for multi-arity {@code fn*} dispatch. Each conditional is
     * {@code (if (checkArity ...) body else nextOrThrow)}.
     */
    private void emitFnArityDispatch(
            CloffleBytecodeRootNodeGen.Builder b,
            java.util.List<FnMethod> methodList,
            int index,
            BytecodeLocal argCountLocal,
            String fnName) {
        FnMethod fm = methodList.get(index);
        boolean last = index == methodList.size() - 1;

        b.beginConditional();
        b.beginCheckArity(fm.reqParms().count(), fm.restParm() != null);
        b.emitLoadLocal(argCountLocal);
        b.endCheckArity();

        convertFnMethod(fm, b);

        if (last) {
            b.beginThrowArity();
            b.emitLoadLocal(argCountLocal);
            b.emitLoadConstant(fnName != null ? fnName : "fn");
            b.endThrowArity();
        } else {
            emitFnArityDispatch(b, methodList, index + 1, argCountLocal, fnName);
        }
        b.endConditional();
    }

    private static final Keyword CASE_INT = Keyword.intern(null, "int");
    private static final Keyword CASE_HASH_EQUIV = Keyword.intern(null, "hash-equiv");
    private static final Keyword CASE_HASH_IDENTITY = Keyword.intern(null, "hash-identity");

    private void convertCaseExpr(CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b) {
        b.beginBlock();
        BytecodeLocal discLocal = b.createLocal();
        b.beginStoreLocal(discLocal);
        convert(ce.expr, b);
        b.endStoreLocal();

        BytecodeLocal keyLocal = b.createLocal();
        b.beginStoreLocal(keyLocal);
        if (ce.testType.equals(CASE_INT)) {
            b.beginStaticMethod(CaseExprRuntime.class, "intDispatchKey");
            b.emitLoadLocal(discLocal);
            b.emitLoadConstant(ce.shift);
            b.emitLoadConstant(ce.mask);
            b.endStaticMethod();
        } else {
            b.beginStaticMethod(CaseExprRuntime.class, "hashDispatchKey");
            b.emitLoadLocal(discLocal);
            b.emitLoadConstant(ce.shift);
            b.emitLoadConstant(ce.mask);
            b.endStaticMethod();
        }
        b.endStoreLocal();

        if (ce.tests.isEmpty()) {
            convert(ce.defaultExpr, b);
            b.endBlock();
            return;
        }

        java.util.ArrayList<Integer> keys = new java.util.ArrayList<>(ce.tests.keySet());
        emitCaseKeyChain(ce, b, discLocal, keyLocal, keys, 0);
        b.endBlock();
    }

    private void emitCaseKeyChain(
            CaseExpr ce,
            CloffleBytecodeRootNodeGen.Builder b,
            BytecodeLocal discLocal,
            BytecodeLocal keyLocal,
            java.util.ArrayList<Integer> keys,
            int idx) {
        if (idx >= keys.size()) {
            convert(ce.defaultExpr, b);
            return;
        }
        Integer k = keys.get(idx);
        b.beginConditional();
        b.beginTruthiness();
        b.beginStaticMethod(CaseExprRuntime.class, "intEq");
        b.emitLoadLocal(keyLocal);
        b.emitLoadConstant(k);
        b.endStaticMethod();
        b.endTruthiness();
        emitCaseBucket(ce, b, discLocal, k);
        emitCaseKeyChain(ce, b, discLocal, keyLocal, keys, idx + 1);
        b.endConditional();
    }

    private void emitCaseBucket(CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b, BytecodeLocal discLocal, Integer k) {
        if (skipCheckContains(ce, k)) {
            convert(ce.thens.get(k), b);
            return;
        }
        if (ce.testType.equals(CASE_INT) || ce.testType.equals(CASE_HASH_EQUIV)) {
            b.beginConditional();
            b.beginTruthiness();
            b.beginStaticMethod(clojure.lang.Util.class, "equiv");
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod();
            b.endTruthiness();
            convert(ce.thens.get(k), b);
            convert(ce.defaultExpr, b);
            b.endConditional();
        } else if (ce.testType.equals(CASE_HASH_IDENTITY)) {
            b.beginConditional();
            b.beginTruthiness();
            b.beginStaticMethod(CaseExprRuntime.class, "identical");
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod();
            b.endTruthiness();
            convert(ce.thens.get(k), b);
            convert(ce.defaultExpr, b);
            b.endConditional();
        } else {
            b.emitLoadNull();
        }
    }

    private static boolean skipCheckContains(CaseExpr ce, Integer k) {
        if (ce.skipCheck == null) {
            return false;
        }
        return RT.booleanCast(RT.contains(ce.skipCheck, k));
    }
}
