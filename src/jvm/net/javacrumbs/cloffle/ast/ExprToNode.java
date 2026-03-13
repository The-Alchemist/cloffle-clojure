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
    private int tryDepth;

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
        return slotByName.computeIfAbsent(name,
                n -> frameDescriptorBuilder.addSlot(kind, n, null));
    }

    private static FrameSlotKind slotKindForClass(Class<?> c) {
        if (c == null) return FrameSlotKind.Object;
        if (c == long.class || c == int.class || c == short.class ||
            c == byte.class || c == char.class) return FrameSlotKind.Long;
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
        if (node == null || expr == null) return;
        int[] loc = extractLineColumn(expr);
        int line = loc[0];
        int column = loc[1];
        if (line < 1 || column < 1) return;

        int charIndex = sourceCharIndex(line, column);
        if (charIndex < 0) {
            node.setSourceSectionByLine(line, column, 1);
            return;
        }

        int len = balancedFormLength(charIndex);
        if (len > 0) {
            node.setSourceSection(charIndex, len);
        } else {
            node.setSourceSectionByLine(line, column, 1);
        }
    }

    private static int[] extractLineColumn(Compiler.Expr expr) {
        // Invocation
        if (expr instanceof InvokeExpr e) return new int[]{e.line, e.column};
        if (expr instanceof KeywordInvokeExpr e) return new int[]{e.line, e.column};

        // Control flow
        if (expr instanceof IfExpr e) return new int[]{e.line, e.column};
        if (expr instanceof CaseExpr e) return new int[]{e.line, e.column};

        // Definitions
        if (expr instanceof DefExpr e) return new int[]{e.line, e.column};
        if (expr instanceof FnExpr e) return new int[]{e.line(), e.column()};

        // Vars and locals
        if (expr instanceof VarExpr e) return new int[]{e.line, e.column};
        if (expr instanceof LocalBindingExpr e) return new int[]{e.line, e.column};

        // Bindings
        if (expr instanceof LetExpr e) return new int[]{e.line, e.column};
        if (expr instanceof LetFnExpr e) return new int[]{e.line, e.column};
        if (expr instanceof RecurExpr e) return new int[]{e.line, e.column};

        // Java interop
        if (expr instanceof StaticMethodExpr e) return new int[]{e.line, e.column};
        if (expr instanceof InstanceMethodExpr e) return new int[]{e.line, e.column};
        if (expr instanceof InstanceFieldExpr e) return new int[]{e.line, e.column};
        if (expr instanceof StaticFieldExpr e) return new int[]{e.line, e.column};
        if (expr instanceof NewExpr e) return new int[]{e.line, e.column};

        // Exception handling
        if (expr instanceof TryExpr e) return new int[]{e.line, e.column};
        if (expr instanceof ThrowExpr e) return new int[]{e.line, e.column};

        // Collections
        if (expr instanceof MapExpr e) return new int[]{e.line, e.column};
        if (expr instanceof VectorExpr e) return new int[]{e.line, e.column};
        if (expr instanceof SetExpr e) return new int[]{e.line, e.column};
        if (expr instanceof ListExpr e) return new int[]{e.line, e.column};

        // Assignment / import
        if (expr instanceof AssignExpr e) return new int[]{e.line, e.column};
        if (expr instanceof ImportExpr e) return new int[]{e.line, e.column};

        return new int[]{-1, -1};
    }

    private int sourceCharIndex(int line, int column) {
        if (source == null) return -1;
        try {
            int lineStart = source.getLineStartOffset(line);
            return lineStart + column - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Scans source text from the given char index to find the end of a
     * balanced s-expression (matching parens/brackets), respecting strings
     * and character literals. Returns the length including the closing
     * delimiter, or -1 if the form is not a paren/bracket form.
     */
    private int balancedFormLength(int start) {
        CharSequence text = source.getCharacters();
        if (start >= text.length()) return -1;
        char open = text.charAt(start);
        char close;
        if (open == '(') close = ')';
        else if (open == '[') close = ']';
        else if (open == '{') close = '}';
        else return -1;

        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                i++;
            } else if (c == ';') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') i++;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i - start + 1;
                }
            }
        }
        return -1;
    }

    private ClojureNode dispatch(Compiler.Expr expr) {
        // Literals
        if (expr instanceof NilExpr) return new NilNode();
        if (expr instanceof BooleanExpr e) return new BooleanNode(e.val);
        if (expr instanceof NumberExpr e) return convertNumber(e);
        if (expr instanceof StringExpr e) return new ObjectNode(e.str);
        if (expr instanceof KeywordExpr e) return new ObjectNode(e.k);
        if (expr instanceof ConstantExpr e) {
            if (e.v instanceof net.javacrumbs.cloffle.Clojure.HostEvalResult hostEvalResult) {
                return new ObjectNode(hostEvalResult.value());
            }
            return new ObjectNode(e.v);
        }
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
        if (expr instanceof QualifiedMethodExpr e) return convertQualifiedMethod(e);

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
        throw new UnsupportedOperationException("Unknown Expr type: " + expr.getClass().getName());
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
            bindings[i] = BindingNodeGen.create(lb.sym, init, slot);
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
        IPersistentCollection methods = fnExpr.methods();
        List<FnMethodNode> methodNodes = new ArrayList<>();

        for (ISeq s = RT.seq(methods); s != null; s = s.next()) {
            FnMethod fm = (FnMethod) s.first();
            methodNodes.add(convertFnMethod(fm));
        }

        FnNode fnNode = new FnNode(methodNodes.toArray(new FnMethodNode[0]));
        fnNode.setFrameDescriptorSupplier(this::buildFrameDescriptor);
        fnNode.setSource(source);
        String name = fnExpr.thisName();
        if (name == null) {
            name = extractFnName(fnExpr.compiledName());
        }
        if (name != null) {
            fnNode.setFnName(name);
        }
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
            FrameSlotKind kind = slotKindForClass(lb.getPrimitiveType());
            int slot = findOrAddSlot(lb, kind);
            ClojureNode init = new ArgInitNode((long) i);
            params[i] = BindingNodeGen.create(lb.sym, init, slot);
        }
        if (isVariadic) {
            LocalBinding lb = (LocalBinding) argLocals.nth(fixedCount);
            FrameSlotKind kind = slotKindForClass(lb.getPrimitiveType());
            int slot = findOrAddSlot(lb, kind);
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
        try {
            Class<?> c = Class.forName(e.target.getClassName());
            return new GenericStaticCallNode(c, "invokeStatic", args);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException("Cannot resolve class for StaticInvokeExpr: " + e.target, ex);
        }
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
        for (int i = 0; i < e.args.count(); i++) {
            args[i] = convert((Compiler.Expr) e.args.nth(i));
        }
        return new NewNode(e.c, args);
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
            target = new InstanceFieldNode(ife.fieldName, convert(ife.target));
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
        boolean isDeftype = e.hintedFields != null && e.hintedFields.count() > 0;
        if (isDeftype) {
            return new NilNode();
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
    static String extractFnName(String compiledName) {
        if (compiledName == null) return null;
        int dollarIdx = compiledName.lastIndexOf('$');
        String tail = dollarIdx >= 0 ? compiledName.substring(dollarIdx + 1) : compiledName;
        int suffixIdx = tail.indexOf("__");
        if (suffixIdx >= 0) {
            tail = tail.substring(0, suffixIdx);
        }
        if (tail.isEmpty() || tail.startsWith("fn") || tail.startsWith("eval")) {
            return null;
        }
        return Compiler.demunge(tail);
    }

    /**
     * Fallback for Expr types that are too complex to convert directly:
     * evaluate via the Compiler's own eval() and wrap the result.
     */
    private ClojureNode convertHostEval(Compiler.Expr expr) {
        return new ObjectNode(expr.eval());
    }
}
