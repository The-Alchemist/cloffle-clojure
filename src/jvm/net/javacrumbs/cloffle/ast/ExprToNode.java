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
    /**
     * {@link Compiler#NEXT_LOCAL_NUM} resets per {@link FnMethod}, so (idx, munged name, isArg) can
     * repeat across different {@link FnExpr}s (e.g. defn body vs {@code :inline} fn). Scope by
     * enclosing {@link FnExpr} so all arities of one {@code fn*} share slots; separate fns do not.
     */
    private final Map<LocalBindingKey, Integer> localSlots = new HashMap<>();
    /** Non-fn top-level / host-eval locals (rare); distinct from any {@link FnExpr}. */
    private static final Object GLOBAL_FN_SCOPE = new Object();
    private final ArrayDeque<Object> fnExprStack = new ArrayDeque<>();

    /** Same-package tests: emulate enclosing {@link FnExpr} without full analysis. */
    void pushTestFnExprScope(Object token) {
        fnExprStack.push(token);
    }

    void popTestFnExprScope() {
        fnExprStack.pop();
    }
    private FrameDescriptor frameDescriptor;
    private int tryDepth;

    private record LocalBindingKey(Object fnExprScope, int idx, String name, boolean isArg) {}

    public ExprToNode(TruffleLanguage<?> language, Source source) {
        this.language = language;
        this.source = source;
        this.frameDescriptorBuilder = FrameDescriptor.newBuilder().defaultValue(null);
    }

    public int findOrAddSlot(Object name) {
        return findOrAddSlot(name, FrameSlotKind.Illegal);
    }

    /**
     * Find or add a frame slot, using the given kind for new slots.
     * When kind is Illegal, new slots are created as Illegal (type inferred at first write).
     * When kind is Long/Double/Boolean/Object, new slots start with that type, avoiding
     * transferToInterpreterAndInvalidate on first write for primitives.
     */
    public int findOrAddSlot(Object name, FrameSlotKind kind) {
        if (name instanceof LocalBinding lb) {
            Integer cached = slotByName.get(lb);
            if (cached != null) {
                return cached;
            }
            Object scope = fnExprStack.isEmpty() ? GLOBAL_FN_SCOPE : fnExprStack.peek();
            // Only merge by (scope, idx, name, isArg) for parameters. NEXT_LOCAL_NUM resets per
            // FnMethod, so two non-arg locals in different arities (e.g. concat's let [cat ...] vs
            // another method's temp) can share idx/name/isArg and must not share a frame slot.
            if (lb.isArg) {
                LocalBindingKey key = new LocalBindingKey(scope, lb.idx, lb.name, true);
                Integer existing = localSlots.get(key);
                if (existing != null) {
                    slotByName.put(lb, existing);
                    return existing;
                }
                int slot = frameDescriptorBuilder.addSlot(kind, lb, null);
                localSlots.put(key, slot);
                slotByName.put(lb, slot);
                return slot;
            }
            int slot = frameDescriptorBuilder.addSlot(kind, lb, null);
            slotByName.put(lb, slot);
            return slot;
        }
        return slotByName.computeIfAbsent(name,
                n -> frameDescriptorBuilder.addSlot(kind, n, null));
    }

    private static FrameSlotKind slotKindForClass(Class<?> c) {
        if (c == null) return FrameSlotKind.Object;
        if (c == long.class || c == int.class || c == short.class ||
            c == byte.class) return FrameSlotKind.Long;
        if (c == double.class || c == float.class) return FrameSlotKind.Double;
        if (c == boolean.class) return FrameSlotKind.Boolean;
        return FrameSlotKind.Object;
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
        applySourceFromExpr(result, expr);
        return result;
    }

    /**
     * Applies source location from compiler Expr to the node. Uses the Source
     * text to compute the full span of the form (balanced parens) when possible,
     * falling back to a single-character span.
     */
    private void applySourceFromExpr(ClojureNode node, Compiler.Expr expr) {
        if (node == null || expr == null) {
            return;
        }
        int[] loc = ExprSourceSpans.extractLineColumn(expr);
        int line = loc[0];
        int column = loc[1];
        if (line < 1 || column < 1) {
            return;
        }
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(source, line, column);
        span.ifPresent(cs -> node.setSourceSection(cs.start(), cs.length()));
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
        if (expr instanceof InstanceFieldExpr e) return new InstanceFieldNode(e.fieldName, convert(e.target), e.requireField);
        if (expr instanceof NewExpr e) return convertNew(e);
        if (expr instanceof InstanceOfExpr e) return new InstanceCheckNode(e.c, convert(e.expr));
        if (expr instanceof QualifiedMethodExpr e) return convertQualifiedMethod(e);

        // Collections
        if (expr instanceof MapExpr e) return convertMap(e);
        if (expr instanceof VectorExpr e) return convertVector(e);
        if (expr instanceof SetExpr e) return convertSet(e);
        if (expr instanceof ListExpr e) return convertList(e);

        // Meta
        if (expr instanceof MetaExpr e) return new WithMetaNode(convert(e.expr), convert(e.meta));

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

        // Last-resort compatibility fallback for Expr variants not yet modeled as Truffle nodes.
        return convertHostEval(expr);
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
        List<Boolean> skipChecks = new ArrayList<>();
        for (Map.Entry<Integer, Compiler.Expr> entry : e.tests.entrySet()) {
            Integer key = entry.getKey();
            testNodes.add(convert(entry.getValue()));
            thenNodes.add(convert(e.thens.get(key)));
            skipChecks.add(e.skipCheck != null && e.skipCheck.contains(key));
        }

        boolean[] skipCheckArray = new boolean[skipChecks.size()];
        for (int i = 0; i < skipChecks.size(); i++) {
            skipCheckArray[i] = skipChecks.get(i);
        }

        ClojureNode defaultNode = e.defaultExpr != null ? convert(e.defaultExpr) : null;
        return new CaseNode(test,
                testNodes.toArray(new ClojureNode[0]),
                thenNodes.toArray(new ClojureNode[0]),
                skipCheckArray,
                defaultNode);
    }

    // ---- Vars and locals ----

    private ClojureNode convertVar(VarExpr e) {
        // Truffle wants context-specific lookup if we want to be fully compliant,
        // but for now, direct VarNode is fine.
        // Wait, e.var might be unbound if it's being defined.
        int slot = findOrAddSlot(e.var);
        return new VarNode(slot, e.var);
    }

    private ClojureNode convertLocal(LocalBindingExpr e) {
        int slot = findOrAddSlot(e.b);
        return new LocalNode(slot);
    }

    private ClojureNode convertDef(DefExpr e) {
        ClojureNode init;
        if (e.initProvided) {
            init = convert(e.init);
            String qualifiedName = e.var.ns.name.getName() + "/" + e.var.sym.getName();
            FnNode fnNode = extractFnNode(init);
            if (fnNode != null) {
                fnNode.setFnName(qualifiedName);
            }
        } else {
            init = new NilNode();
        }

        ClojureNode meta = null;
        if (e.meta != null) {
            meta = convert(e.meta);
        }

        return new DefNode(init, e.var, e.initProvided, meta, e.isDynamic);
    }

    // ---- Bindings ----

    private ClojureNode convertLet(LetExpr e) {
        BindingNode[] bindings = convertBindings(e.bindingInits);
        ClojureNode body = convert(e.body);
        return e.isLoop ? new LoopNode(bindings, body) : new LetNode(bindings, body);
    }

    private ClojureNode convertLetFn(LetFnExpr e) {
        BindingNode[] bindings = convertBindings(e.bindingInits);
        ClojureNode body = convert(e.body);
        return new LetFnNode(bindings, body);
    }

    private BindingNode[] convertBindings(PersistentVector bindingInits) {
        BindingNode[] bindings = new BindingNode[bindingInits.count()];
        for (int i = 0; i < bindingInits.count(); i++) {
            BindingInit bi = (BindingInit) bindingInits.nth(i);
            LocalBinding lb = bi.binding();
            FrameSlotKind kind = slotKindForClass(lb.getPrimitiveType());
            int slot = findOrAddSlot(lb, kind);
            ClojureNode init = convert(bi.init());
            init = maybeFIAdapt(init, lb.tag);
            BindingNode binding = BindingNodeGen.create(lb.sym, init, slot);
            applySourceFromExpr(binding, bi.init());
            bindings[i] = binding;
        }
        return bindings;
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
        fnExprStack.push(fnExpr);
        try {
            String thisName = fnExpr.thisName();

            int thisSlot = -1;
            if (thisName != null) {
                // One LocalBinding per FnMethod for the self name; FnNode writes closure to a single slot
                // before snapshot — every method's LocalBindingExpr for thisName must resolve to that slot.
                List<LocalBinding> allThisBindings = new ArrayList<>();
                for (ISeq s = RT.seq(fnExpr.methods()); s != null; s = s.next()) {
                    FnMethod fm = (FnMethod) s.first();
                    IPersistentMap locals = fm.locals();
                    if (locals != null) {
                        for (ISeq ls = RT.seq(locals); ls != null; ls = ls.next()) {
                            java.util.Map.Entry entry = (java.util.Map.Entry) ls.first();
                            LocalBinding lb = (LocalBinding) entry.getKey();
                            if (!lb.isArg && (thisName.equals(lb.name) || thisName.equals(lb.sym.getName()))) {
                                allThisBindings.add(lb);
                                break;
                            }
                        }
                    }
                }
                if (!allThisBindings.isEmpty()) {
                    thisSlot = findOrAddSlot(allThisBindings.get(0));
                    for (int i = 1; i < allThisBindings.size(); i++) {
                        slotByName.put(allThisBindings.get(i), thisSlot);
                    }
                }
            }
            IPersistentCollection methods = fnExpr.methods();
            List<FnMethodNode> methodNodes = new ArrayList<>();

            for (ISeq s = RT.seq(methods); s != null; s = s.next()) {
                FnMethod fm = (FnMethod) s.first();
                methodNodes.add(convertFnMethod(fm));
            }

            FnNode fnNode = new FnNode(methodNodes.toArray(new FnMethodNode[0]));
            fnNode.setFrameDescriptorSupplier(this::buildFrameDescriptor);
            fnNode.setSource(source);
            fnNode.setLanguage(language);
            if (thisSlot >= 0) {
                fnNode.setThisSlot(thisSlot);
            }
            if (thisName != null) {
                fnNode.setFnName(thisName);
            }
            return fnNode;
        } finally {
            fnExprStack.pop();
        }
    }

    private FnMethodNode convertFnMethod(FnMethod fm) {
        PersistentVector argLocals = fm.argLocals();
        boolean isVariadic = fm.restParm != null;

        int fixedCount = fm.reqParms.count();
        int totalParams = fixedCount + (isVariadic ? 1 : 0);
        int methodLine = fm.sourceLine();
        int methodCol = fm.sourceColumn();

        BindingNode[] params = new BindingNode[totalParams];
        for (int i = 0; i < fixedCount; i++) {
            LocalBinding lb = (LocalBinding) argLocals.nth(i);
            FrameSlotKind kind = slotKindForClass(lb.getPrimitiveType());
            int slot = findOrAddSlot(lb, kind);
            ClojureNode init = new ArgInitNode((long) i);
            if (methodLine > 0 && methodCol > 0) {
                init.setSourceSectionByLine(methodLine, methodCol, 1);
            }
            BindingNode param = BindingNodeGen.create(lb.sym, init, slot);
            if (methodLine > 0 && methodCol > 0) {
                param.setSourceSectionByLine(methodLine, methodCol, 1);
            }
            params[i] = param;
        }
        if (isVariadic) {
            LocalBinding lb = (LocalBinding) argLocals.nth(fixedCount);
            FrameSlotKind kind = slotKindForClass(lb.getPrimitiveType());
            int slot = findOrAddSlot(lb, kind);
            ClojureNode init = new VariadicArgInitNode(fixedCount);
            if (methodLine > 0 && methodCol > 0) {
                init.setSourceSectionByLine(methodLine, methodCol, 1);
            }
            BindingNode param = BindingNodeGen.create(lb.sym, init, slot);
            if (methodLine > 0 && methodCol > 0) {
                param.setSourceSectionByLine(methodLine, methodCol, 1);
            }
            params[fixedCount] = param;
        }

        ClojureNode body = convert(fm.body());
        FnMethodNode node = new FnMethodNode(params, body, fixedCount, isVariadic);
        if (methodLine > 0 && methodCol > 0) {
            try {
                int len = source != null ? Math.max(1, source.getLineLength(methodLine)) : 1;
                node.setSourceSectionByLine(methodLine, methodCol, len);
            } catch (Exception ignored) {
                node.setSourceSectionByLine(methodLine, methodCol, 1);
            }
        }
        return node;
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
                    args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new ClojureNode[0],
                    e.protocolOn,
                    e.onMethod);
        }

        return new InvokeNode(fn, this::buildFrameDescriptor, source, language, args,
                e.tailPosition && tryDepth == 0);
    }

    private ClojureNode convertKeywordInvoke(KeywordInvokeExpr e) {
        return new KeywordInvokeNode(e.kw.k, convert(e.target));
    }

    private ClojureNode convertStaticInvoke(StaticInvokeExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        int slot = findOrAddSlot(e.var);
        ClojureNode fn = new VarNode(slot, e.var);
        return new InvokeNode(fn, this::buildFrameDescriptor, source, language, args,
                e.tailPosition && tryDepth == 0);
    }

    // ---- Java interop ----

    private ClojureNode convertStaticMethod(StaticMethodExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        Class<?>[] paramTypes = e.method != null ? e.method.getParameterTypes() : null;
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
            if (paramTypes != null && i < paramTypes.length) {
                args[i] = maybeFIAdapt(args[i], paramTypes[i]);
            }
        }
        return new GenericStaticCallNode(e.c, e.methodName, args, e.method);
    }

    private ClojureNode convertInstanceMethod(InstanceMethodExpr e) {
        ClojureNode instance = convert(e.target);
        ClojureNode[] args = new ClojureNode[e.args.count()];
        Class<?>[] paramTypes = e.method != null ? e.method.getParameterTypes() : null;
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
            if (paramTypes != null && i < paramTypes.length) {
                args[i] = maybeFIAdapt(args[i], paramTypes[i]);
            }
        }
        return new InstanceCallNode(instance, e.methodName, e.method, args);
    }

    private ClojureNode convertQualifiedMethod(QualifiedMethodExpr e) {
        if (e.preferOverloadedField()) {
             return convert(e.fieldOverload);
        } else {
             FnExpr thunk = QualifiedMethodExpr.buildThunk(C.EVAL, e);
             return convert(thunk);
        }
    }

    private ClojureNode convertNew(NewExpr e) {
        ClojureNode[] args = new ClojureNode[e.args.count()];
        Class<?>[] paramTypes = e.ctor != null ? e.ctor.getParameterTypes() : null;
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
            if (paramTypes != null && i < paramTypes.length) {
                args[i] = maybeFIAdapt(args[i], paramTypes[i]);
            }
        }
        return new NewNode(e.c, args, e.ctor);
    }

    // ---- Collections ----

    private ClojureNode convertList(ListExpr e) {
        ClojureNode[] items = new ClojureNode[e.args.count()];
        for (int i = 0; i < e.args.count(); i++) {
            items[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new ListNode(items);
    }

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

    // ---- Exception handling ----

    private ClojureNode convertTry(TryExpr e) {
        tryDepth++;
        ClojureNode body;
        try {
            body = convert(e.tryExpr);
        } finally {
            tryDepth--;
        }

        CatchNode[] catchNodes = new CatchNode[e.catchExprs.count()];
        for (int i = 0; i < e.catchExprs.count(); i++) {
            TryExpr.CatchClause cc = (TryExpr.CatchClause) e.catchExprs.nth(i);
            int slot = findOrAddSlot(cc.lb);
            tryDepth++;
            ClojureNode handler;
            try {
                handler = convert(cc.handler);
            } finally {
                tryDepth--;
            }
            catchNodes[i] = new CatchNode(cc.c, slot, handler);
        }

        ClojureNode finallyNode = null;
        if (e.finallyExpr != null) {
            tryDepth++;
            try {
                finallyNode = convert(e.finallyExpr);
            } finally {
                tryDepth--;
            }
        }
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
            target = new InstanceFieldNode(ife.fieldName, convert(ife.target), ife.requireField);
        } else if (e.target instanceof LocalBindingExpr lbe) {
            int slot = findOrAddSlot(lbe.b);
            target = new LocalNode(slot);
        } else {
            return new ObjectNode(e.eval());
        }
        ClojureNode val = convert(e.val);
        return new SetBangNode(target, val);
    }

    // ---- deftype / reify ----

    private ClojureNode convertNewInstance(NewInstanceExpr e) {
        Class<?> compiledClass = e.compiledClass();
        if (compiledClass == null) {
            throw new RuntimeException("NewInstanceExpr has no compiled class — "
                    + "Compiler.analyze() should always generate one for deftype/reify");
        }
        if (e.isDeftype()) {
            return convertHostEval(e);
        }
        ClojureNode[] ctorArgs = new ClojureNode[e.closesExprs.count()];
        for (int i = 0; i < e.closesExprs.count(); i++) {
            ctorArgs[i] = convert((Compiler.Expr) e.closesExprs.nth(i));
        }
        return new NewNode(compiledClass, ctorArgs);
    }

    /**
     * Extracts a human-readable function name from the compiler's internal name.
     * Internal names look like "user$boom__123" or "user$fn__456".
     * Returns null for anonymous functions.
     */
    /**
     * Fallback for Expr types that are too complex to convert directly:
     * evaluate via the Compiler's own eval() and wrap the result.
     */
    private static FnNode extractFnNode(ClojureNode node) {
        if (node instanceof FnNode fn) return fn;
        if (node instanceof WithMetaNode wm) return extractFnNode(wm.getInnerExpr());
        return null;
    }

    /**
     * If tag resolves to a @FunctionalInterface, wrap node in an FIAdapterNode.
     */
    private static ClojureNode maybeFIAdapt(ClojureNode node, Symbol tag) {
        if (tag == null) return node;
        Class<?> targetClass = HostExpr.maybeClass(tag, true);
        return maybeFIAdapt(node, targetClass);
    }

    private static ClojureNode maybeFIAdapt(ClojureNode node, Class<?> targetClass) {
        if (targetClass == null) return node;
        java.lang.reflect.Method fiMethod = Compiler.FISupport.maybeFIMethod(targetClass);
        if (fiMethod == null) return node;
        return new FIAdapterNode(node, targetClass, fiMethod);
    }

    private ClojureNode convertHostEval(Compiler.Expr expr) {
        return new ObjectNode(expr.eval());
    }
}
