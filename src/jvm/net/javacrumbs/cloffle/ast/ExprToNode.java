package net.javacrumbs.cloffle.ast;

import clojure.lang.*;
import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.nodes.*;
import net.javacrumbs.cloffle.nodes.binding.ArgInitNode;
import net.javacrumbs.cloffle.nodes.binding.BindingNodeGen;
import net.javacrumbs.cloffle.nodes.binding.VariadicArgInitNode;
import net.javacrumbs.cloffle.nodes.invoke.InvokeNode;
import net.javacrumbs.cloffle.nodes.staticcall.GenericStaticCallNode;
import net.javacrumbs.cloffle.nodes.value.*;
import net.javacrumbs.cloffle.nodes.vars.LocalNode;
import net.javacrumbs.cloffle.nodes.vars.VarNode;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;

import java.util.*;

/**
 * Converts Compiler.Expr trees (produced by Compiler.analyze()) into
 * Truffle ClojureNode trees, replacing tools.analyzer.jvm + AstBuilder.
 */
public class ExprToNode {

    private final TruffleLanguage<?> language;
    private final Source source;
    private final FrameDescriptor.Builder frameDescriptorBuilder;
    private final Map<Object, Integer> slotByName = new HashMap<>();
    private FrameDescriptor frameDescriptor;

    public ExprToNode(TruffleLanguage<?> language, Source source) {
        this.language = language;
        this.source = source;
        this.frameDescriptorBuilder = FrameDescriptor.newBuilder().defaultValue(null);
    }

    public int findOrAddSlot(Object name) {
        return slotByName.computeIfAbsent(name,
                n -> frameDescriptorBuilder.addSlot(FrameSlotKind.Illegal, n, null));
    }

    /**
     * Build and return the FrameDescriptor. Must only be called after all
     * conversion is complete, as calling build() freezes the slot count.
     */
    public FrameDescriptor buildFrameDescriptor() {
        if (frameDescriptor == null) {
            frameDescriptor = frameDescriptorBuilder.build();
        }
        return frameDescriptor;
    }

    public ClojureNode convert(Compiler.Expr expr) {
        if (expr == null) {
            return new NilNode();
        }

        ClojureNode result = dispatch(expr);
        return result;
    }

    private ClojureNode dispatch(Compiler.Expr expr) {
        // Literals
        if (expr instanceof NilExpr) return new NilNode();
        if (expr instanceof BooleanExpr e) return new BooleanNode(e.val);
        if (expr instanceof NumberExpr e) return convertNumber(e);
        if (expr instanceof StringExpr e) return new ObjectNode(e.str);
        if (expr instanceof KeywordExpr e) return new ObjectNode(e.k);
        if (expr instanceof ConstantExpr e) return new ObjectNode(e.v);
        if (expr instanceof EmptyExpr e) return new ObjectNode(e.coll);

        // Control flow
        if (expr instanceof IfExpr e) return convertIf(e);
        if (expr instanceof BodyExpr e) return convertBody(e);
        if (expr instanceof CaseExpr e) return convertCase(e);

        // Vars and locals
        if (expr instanceof VarExpr e) return convertVar(e);
        if (expr instanceof TheVarExpr e) return new ObjectNode(e.var);
        if (expr instanceof LocalBindingExpr e) return convertLocal(e);
        if (expr instanceof DefExpr e) return convertDef(e);

        // Bindings
        if (expr instanceof LetExpr e) return convertLet(e);
        if (expr instanceof LetFnExpr e) return convertLetFn(e);
        if (expr instanceof RecurExpr e) return convertRecur(e);

        // Functions
        if (expr instanceof FnExpr e) return convertFn(e);

        // Invocation
        if (expr instanceof InvokeExpr e) return convertInvoke(e);
        if (expr instanceof KeywordInvokeExpr e) return convertKeywordInvoke(e);
        if (expr instanceof StaticInvokeExpr e) return convertStaticInvoke(e);

        // Java interop
        if (expr instanceof StaticMethodExpr e) return convertStaticMethod(e);
        if (expr instanceof StaticFieldExpr e) return new StaticFieldNode(e.c, e.fieldName);
        if (expr instanceof InstanceMethodExpr e) return convertInstanceMethod(e);
        if (expr instanceof InstanceFieldExpr e) return new InstanceFieldNode(e.fieldName, convert(e.target));
        if (expr instanceof NewExpr e) return convertNew(e);
        if (expr instanceof InstanceOfExpr e) return new InstanceCheckNode(e.c, convert(e.expr));
        if (expr instanceof QualifiedMethodExpr) return convertHostEval(expr);

        // Collections
        if (expr instanceof MapExpr e) return convertMap(e);
        if (expr instanceof VectorExpr e) return convertVector(e);
        if (expr instanceof SetExpr e) return convertSet(e);
        if (expr instanceof ListExpr e) return convertList(e);

        // Meta
        if (expr instanceof MetaExpr e) return convert(e.expr);

        // Exception handling
        if (expr instanceof TryExpr e) return convertTry(e);
        if (expr instanceof ThrowExpr e) return new ThrowNode(convert(e.excExpr));

        // set!
        if (expr instanceof AssignExpr e) return convertAssign(e);

        // import
        if (expr instanceof ImportExpr e) return new ImportNode(e.c);

        // Monitors
        if (expr instanceof MonitorEnterExpr e) return new MonitorEnterNode(convert(e.target));
        if (expr instanceof MonitorExitExpr e) return new MonitorExitNode(convert(e.target));

        // deftype / reify
        if (expr instanceof NewInstanceExpr e) return convertNewInstance(e);

        // Fallback: use eval() to get the value
        return new ObjectNode(expr.eval());
    }

    // ---- Literal conversion ----

    private ClojureNode convertNumber(NumberExpr e) {
        Number n = e.n;
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return new LongNode(n.longValue());
        } else if (n instanceof Double || n instanceof Float) {
            return new DoubleNode(n.doubleValue());
        }
        return new ObjectNode(n);
    }

    // ---- Control flow ----

    private ClojureNode convertIf(IfExpr e) {
        return new IfNode(convert(e.testExpr), convert(e.thenExpr), convert(e.elseExpr));
    }

    private ClojureNode convertBody(BodyExpr e) {
        PersistentVector exprs = e.exprs();
        if (exprs.count() == 0) return new NilNode();
        if (exprs.count() == 1) return convert((Compiler.Expr) exprs.nth(0));

        ClojureNode[] statements = new ClojureNode[exprs.count() - 1];
        for (int i = 0; i < exprs.count() - 1; i++) {
            statements[i] = convert((Compiler.Expr) exprs.nth(i));
        }
        ClojureNode ret = convert((Compiler.Expr) exprs.nth(exprs.count() - 1));
        return new DoNode(statements, ret);
    }

    private ClojureNode convertCase(CaseExpr e) {
        ClojureNode test = convert(e.expr);

        List<ClojureNode> testNodes = new ArrayList<>();
        List<ClojureNode> thenNodes = new ArrayList<>();
        for (Map.Entry<Integer, Compiler.Expr> entry : e.tests.entrySet()) {
            Integer key = entry.getKey();
            testNodes.add(convert(entry.getValue()));
            thenNodes.add(convert(e.thens.get(key)));
        }

        ClojureNode defaultNode = e.defaultExpr != null ? convert(e.defaultExpr) : null;
        return new CaseNode(test,
                testNodes.toArray(new ClojureNode[0]),
                thenNodes.toArray(new ClojureNode[0]),
                defaultNode);
    }

    // ---- Vars and locals ----

    private ClojureNode convertVar(VarExpr e) {
        int slot = findOrAddSlot(e.var);
        return new VarNode(slot, e.var);
    }

    private ClojureNode convertLocal(LocalBindingExpr e) {
        int slot = findOrAddSlot(e.b);
        return new LocalNode(slot);
    }

    private ClojureNode convertDef(DefExpr e) {
        int slot = findOrAddSlot(e.var);
        ClojureNode init;
        if (e.initProvided) {
            init = convert(e.init);
        } else {
            init = new NilNode();
        }

        if (e.meta != null) {
            Object metaVal = e.meta.eval();
            if (metaVal instanceof IPersistentMap metaMap) {
                e.var.setMeta(metaMap);
            }
        }
        if (e.isDynamic) {
            e.var.setDynamic();
        }

        return new DefNode(slot, init, e.var);
    }

    // ---- Bindings ----

    private ClojureNode convertLet(LetExpr e) {
        PersistentVector bis = e.bindingInits;
        BindingNode[] bindings = new BindingNode[bis.count()];
        for (int i = 0; i < bis.count(); i++) {
            BindingInit bi = (BindingInit) bis.nth(i);
            LocalBinding lb = bi.binding();
            int slot = findOrAddSlot(lb);
            ClojureNode init = convert(bi.init());
            bindings[i] = BindingNodeGen.create(lb.sym, init, slot);
        }
        ClojureNode body = convert(e.body);

        if (e.isLoop) {
            return new LoopNode(bindings, body);
        } else {
            return new LetNode(bindings, body);
        }
    }

    private ClojureNode convertLetFn(LetFnExpr e) {
        PersistentVector bis = e.bindingInits;
        BindingNode[] bindings = new BindingNode[bis.count()];
        for (int i = 0; i < bis.count(); i++) {
            BindingInit bi = (BindingInit) bis.nth(i);
            LocalBinding lb = bi.binding();
            int slot = findOrAddSlot(lb);
            ClojureNode init = convert(bi.init());
            bindings[i] = BindingNodeGen.create(lb.sym, init, slot);
        }
        ClojureNode body = convert(e.body);
        return new LetNode(bindings, body);
    }

    private ClojureNode convertRecur(RecurExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new RecurNode(args);
    }

    // ---- Functions ----

    private ClojureNode convertFn(FnExpr fnExpr) {
        IPersistentCollection methods = fnExpr.methods();
        List<FnMethodNode> methodNodes = new ArrayList<>();

        for (ISeq s = RT.seq(methods); s != null; s = s.next()) {
            FnMethod fm = (FnMethod) s.first();
            methodNodes.add(convertFnMethod(fm));
        }

        FnNode fnNode = new FnNode(methodNodes.toArray(new FnMethodNode[0]));
        fnNode.setFrameDescriptorSupplier(this::buildFrameDescriptor);
        fnNode.setSource(source);
        return fnNode;
    }

    private FnMethodNode convertFnMethod(FnMethod fm) {
        PersistentVector argLocals = fm.argLocals();
        boolean isVariadic = fm.restParm != null;

        int fixedCount = fm.reqParms.count();
        int totalParams = fixedCount + (isVariadic ? 1 : 0);

        BindingNode[] params = new BindingNode[totalParams];
        for (int i = 0; i < fixedCount; i++) {
            LocalBinding lb = (LocalBinding) argLocals.nth(i);
            int slot = findOrAddSlot(lb);
            ClojureNode init = new ArgInitNode((long) i);
            params[i] = BindingNodeGen.create(lb.sym, init, slot);
        }
        if (isVariadic) {
            LocalBinding lb = (LocalBinding) argLocals.nth(fixedCount);
            int slot = findOrAddSlot(lb);
            ClojureNode init = new VariadicArgInitNode(fixedCount);
            params[fixedCount] = BindingNodeGen.create(lb.sym, init, slot);
        }

        ClojureNode body = convert(fm.body());
        return new FnMethodNode(params, body, fixedCount, isVariadic);
    }

    // ---- Invocation ----

    private ClojureNode convertInvoke(InvokeExpr e) {
        ClojureNode fn = convert(e.fexpr);
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }

        if (e.isProtocol) {
            return new ProtocolInvokeNode(fn,
                    args.length > 0 ? args[0] : new NilNode(),
                    args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new ClojureNode[0]);
        }

        return new InvokeNode(fn, this::buildFrameDescriptor, source, language, args);
    }

    private ClojureNode convertKeywordInvoke(KeywordInvokeExpr e) {
        return new KeywordInvokeNode(e.kw.k, convert(e.target));
    }

    private ClojureNode convertStaticInvoke(StaticInvokeExpr e) {
        return convertHostEval(e);
    }

    // ---- Java interop ----

    private ClojureNode convertStaticMethod(StaticMethodExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new GenericStaticCallNode(e.c, e.methodName, args);
    }

    private ClojureNode convertInstanceMethod(InstanceMethodExpr e) {
        ClojureNode instance = convert(e.target);
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new InstanceCallNode(instance, e.methodName, args);
    }

    private ClojureNode convertNew(NewExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new NewNode(e.c, args);
    }

    // ---- Collections ----

    private ClojureNode convertMap(MapExpr e) {
        int count = e.keyvals.count();
        ClojureNode[] keys = new ClojureNode[count / 2];
        ClojureNode[] vals = new ClojureNode[count / 2];
        for (int i = 0; i < count; i += 2) {
            keys[i / 2] = convert((Compiler.Expr) e.keyvals.nth(i));
            vals[i / 2] = convert((Compiler.Expr) e.keyvals.nth(i + 1));
        }
        return new MapNode(keys, vals);
    }

    private ClojureNode convertVector(VectorExpr e) {
        ClojureNode[] items = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            items[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new VectorNode(items);
    }

    private ClojureNode convertSet(SetExpr e) {
        ClojureNode[] items = new ClojureNode[e.keys.count()];
        for (int i = 0; i < e.keys.count(); i++) {
            items[i] = convert((Compiler.Expr) e.keys.nth(i));
        }
        return new SetNode(items);
    }

    private ClojureNode convertList(ListExpr e) {
        ClojureNode[] items = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            items[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new ObjectNode(e.eval());
    }

    // ---- Exception handling ----

    private ClojureNode convertTry(TryExpr e) {
        ClojureNode body = convert(e.tryExpr);

        CatchNode[] catchNodes = new CatchNode[e.catchExprs.count()];
        for (int i = 0; i < e.catchExprs.count(); i++) {
            TryExpr.CatchClause cc = (TryExpr.CatchClause) e.catchExprs.nth(i);
            int slot = findOrAddSlot(cc.lb);
            ClojureNode handler = convert(cc.handler);
            catchNodes[i] = new CatchNode(cc.c, slot, handler);
        }

        ClojureNode finallyNode = e.finallyExpr != null ? convert(e.finallyExpr) : null;
        return new TryNode(body, catchNodes, finallyNode);
    }

    // ---- Assignment ----

    private ClojureNode convertAssign(AssignExpr e) {
        ClojureNode target;
        if (e.target instanceof VarExpr ve) {
            int slot = findOrAddSlot(ve.var);
            target = new VarNode(slot, ve.var);
        } else if (e.target instanceof StaticFieldExpr sfe) {
            target = new StaticFieldNode(sfe.c, sfe.fieldName);
        } else if (e.target instanceof InstanceFieldExpr ife) {
            target = new InstanceFieldNode(ife.fieldName, convert(ife.target));
        } else {
            return new ObjectNode(e.eval());
        }
        ClojureNode val = convert(e.val);
        return new SetBangNode(target, val);
    }

    // ---- deftype / reify ----

    private ClojureNode convertNewInstance(NewInstanceExpr e) {
        IPersistentCollection methods = e.methods;
        IPersistentVector hintedFields = e.hintedFields;
        IPersistentMap fields = e.fields;

        List<Class<?>> interfaces = new ArrayList<>();
        if (e.compiledClass() != null) {
            for (Class<?> iface : e.compiledClass().getInterfaces()) {
                interfaces.add(iface);
            }
        }

        List<ReifyNode.ReifyMethodDef> methodDefs = new ArrayList<>();
        for (ISeq s = RT.seq(methods); s != null; s = s.next()) {
            NewInstanceMethod nim = (NewInstanceMethod) s.first();
            PersistentVector argLocals = nim.argLocals();
            int thisSlot = findOrAddSlot(Symbol.intern("this__cloffle"));
            int[] paramSlots = new int[argLocals.count()];
            for (int i = 0; i < argLocals.count(); i++) {
                LocalBinding lb = (LocalBinding) argLocals.nth(i);
                paramSlots[i] = findOrAddSlot(lb.sym);
            }
            ClojureNode body = convert(nim.body());
            methodDefs.add(new ReifyNode.ReifyMethodDef(
                    nim.name, thisSlot, paramSlots, body));
        }

        boolean isDeftype = hintedFields != null && hintedFields.count() > 0;
        if (isDeftype) {
            String[] fieldNames = new String[hintedFields.count()];
            int[] fieldSlots = new int[hintedFields.count()];
            for (int i = 0; i < hintedFields.count(); i++) {
                Symbol fieldSym = (Symbol) hintedFields.nth(i);
                fieldNames[i] = fieldSym.getName();
                fieldSlots[i] = findOrAddSlot(fieldSym);
            }
            return new DefTypeNode(
                    interfaces.toArray(new Class<?>[0]),
                    fieldNames, fieldSlots,
                    methodDefs.toArray(new ReifyNode.ReifyMethodDef[0]),
                    language);
        } else {
            return new ReifyNode(
                    interfaces.toArray(new Class<?>[0]),
                    methodDefs.toArray(new ReifyNode.ReifyMethodDef[0]),
                    language);
        }
    }

    /**
     * Fallback for Expr types that are too complex to convert directly:
     * evaluate via the Compiler's own eval() and wrap the result.
     */
    private ClojureNode convertHostEval(Compiler.Expr expr) {
        return new ObjectNode(expr.eval());
    }
}
