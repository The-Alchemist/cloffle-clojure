package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeLabel;

public class ExprToBytecode {

    private final Clojure language;
    private final Source source;
    private final Map<LocalBinding, BytecodeLocal> localSlots = new HashMap<>();
    
    private record LoopTarget(BytecodeLabel label, List<BytecodeLocal> locals) {}
    private final ArrayDeque<LoopTarget> loopStack = new ArrayDeque<>();

    public ExprToBytecode(Clojure language, Source source) {
        this.language = language;
        this.source = source;
    }

    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name) {
        BytecodeParser<CloffleBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            convert(rootExpr, b);
            b.endReturn();
            CloffleBytecodeRootNode rootNode = b.endRoot();
            rootNode.setName(name);
        };
        return CloffleBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, parser);
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
        } else if (expr instanceof RecurExpr recurExpr) {
            // Wait, we need the arguments to loop. Let's just create an array and throw TailCallException
            // if we are inside a function. But wait, Clojure loops are loop targets.
            // A LetExpr might be a loop. LetExpr has `isLoop`.
            LoopTarget target = loopStack.peek();
            if (target != null) {
                // Assign new values to locals
                b.beginBlock();
                for (int i = 0; i < recurExpr.args.count(); i++) {
                    b.beginStoreLocal(target.locals().get(i));
                    convert((Expr) recurExpr.args.nth(i), b);
                    b.endStoreLocal();
                }
                b.emitBranch(target.label());
                b.endBlock();
            } else {
                b.emitLoadNull();
            }
        } else if (expr instanceof LetExpr le) {
            // let binds variables and then evaluates body
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
                
                BytecodeLabel loopLabel = null;
                if (le.isLoop) {
                    loopLabel = b.createLabel();
                    loopStack.push(new LoopTarget(loopLabel, letLocals));
                    b.emitLabel(loopLabel);
                }
                
                convert(le.body, b);
                
                if (le.isLoop) {
                    loopStack.pop();
                }
                
                b.endBlock();
            } else {
                convert(le.body, b);
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
                
                // Note: we can't emit LoadArgument here, we just store it
                // in the closure's lexical scope so that it works.
                // Wait, no. A self-recursive function gets itself directly as part of execution.
                // The issue in Truffle AST is we set `fnNode.setThisSlot()`.
                // In bytecode, we should probably evaluate the function and then store it into a local
                // OR we can just write it. Wait! The closure object is not available INSIDE the function 
                // builder at this stage!
                // We should actually pass the closure ITSELF as an argument or we should 
                // let `createClosure` store it.
                // Actually, if we just remove thisLocal logic here, wait:
                
                localSlots.put(thisBinding, thisLocal);
            }

            // Nested RootNode for the function body
            b.beginRoot();
            
            if (thisLocal != null) {
                // If it's a self-reference, we don't have a direct way to get "this closure" 
                // inside Truffle Bytecode without passing it as an argument or accessing the current CallTarget.
                // However, the first arg to the RootNode is `capturedFrame`, so we can't just LoadArgument(0).
                // Actually, we'll need to figure out how clojure handles this in Truffle AST...
                // In Truffle AST we do `virtualFrame.setObject(thisSlot, closure);` in the `executeGeneric`
                // of the outer `FnNode`.
                // In Bytecode, we'll leave it null for now, which will just fail on invocation of self.
            }
            b.beginReturn();
            
            // Generate dispatch based on argument count
            clojure.lang.IPersistentCollection methods = fnExpr.methods();
            int methodCount = methods.count();
            
            if (methodCount == 1) {
                FnMethod fm = (FnMethod) clojure.lang.RT.seq(methods).first();
                convertFnMethod(fm, b);
            } else {
                b.beginBlock();
                BytecodeLocal argCountLocal = b.createLocal();
                b.beginStoreLocal(argCountLocal);
                b.emitGetArgCount();
                b.endStoreLocal();
                
                java.util.List<FnMethod> methodList = new java.util.ArrayList<>();
                for (int i = 0; i < methodCount; i++) {
                    methodList.add((FnMethod) clojure.lang.RT.nth(methods, i));
                }
                
                // Sort methods: exact arities first (ascending), variadic last
                methodList.sort((m1, m2) -> {
                    boolean v1 = m1.restParm() != null;
                    boolean v2 = m2.restParm() != null;
                    if (v1 && !v2) return 1;
                    if (!v1 && v2) return -1;
                    return Integer.compare(m1.reqParms().count(), m2.reqParms().count());
                });
                
                for (FnMethod fm : methodList) {
                    b.beginConditional();
                    b.beginCheckArity(fm.reqParms().count(), fm.restParm() != null);
                    b.emitLoadLocal(argCountLocal);
                    b.endCheckArity();
                    
                    convertFnMethod(fm, b);
                }
                
                b.beginThrowArity();
                b.emitLoadLocal(argCountLocal);
                b.emitLoadConstant(fnExpr.thisName() != null ? fnExpr.thisName() : "fn");
                b.endThrowArity();
                
                for (int i = 0; i < methodCount; i++) {
                    b.endConditional();
                }
                
                b.endBlock();
            }
            
            b.endReturn();
            CloffleBytecodeRootNode innerNode = b.endRoot();
            innerNode.setName(fnExpr.thisName() != null ? fnExpr.thisName() : "fn");
            
            if (thisLocal != null) {
                b.beginBlock(); // <--- This block causes the result to be the local, preventing "void" Return errors!
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
            b.beginInvoke();
            convert(ie.fexpr, b);
            for (int i = 0; i < ie.args.count(); i++) {
                Expr arg = (Expr) ie.args.nth(i);
                convert(arg, b);
            }
            b.endInvoke();
        } else {
            System.out.println("WARNING: Unimplemented expression fallback for " + expr.getClass().getName());
            // Fallback for unimplemented expressions
            b.emitLoadNull();
        }
    }

    private void convertFnMethod(FnMethod fm, CloffleBytecodeRootNodeGen.Builder b) {
        int bindings = fm.reqParms().count() + (fm.restParm() != null ? 1 : 0);
        if (bindings > 0) {
            b.beginBlock(); // block for evaluating parameters and body
            
            for (int i = 0; i < fm.reqParms().count(); i++) {
                LocalBinding lb = (LocalBinding) fm.reqParms().nth(i);
                BytecodeLocal local = b.createLocal();
                localSlots.put(lb, local);
                
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
                
                b.beginStoreLocal(local);
                b.emitGetRestArgs(fm.reqParms().count());
                b.endStoreLocal();
                
                // Discard the result of storeLocal so it doesn't leak into the block
                b.beginBlock();
                b.endBlock();
            }
            
            convert(fm.body(), b);
            
            b.endBlock(); // end parameter-eval-body block
        } else {
            convert(fm.body(), b);
        }
    }
}
