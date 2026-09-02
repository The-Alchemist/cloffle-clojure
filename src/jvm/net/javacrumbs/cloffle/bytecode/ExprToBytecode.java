package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import clojure.lang.IPersistentVector;
import clojure.lang.PersistentVector;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.ast.ExprSourceSpans;

import java.util.HashMap;
import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.instrumentation.StandardTags;

public class ExprToBytecode {

    /** Enables {@code beginSource} / {@code beginSourceSection} so nodes expose {@link com.oracle.truffle.api.source.SourceSection}s. */
    public static final BytecodeConfig BYTECODE_CONFIG = BytecodeConfig.WITH_SOURCE;

    /** Optional extras for {@link #emitWithLineColumnSection}; always nests {@link StandardTags.StatementTag} and {@link StandardTags.ExpressionTag} when a section applies. */
    private static final int BC_TAG_CALL = 1;
    private static final int BC_TAG_WRITE_VAR = 2;
    private static final int BC_TAG_READ_VAR = 4;

    private final Clojure language;
    private final Source source;
    private final Map<LocalBinding, BytecodeLocal> localSlots = new HashMap<>();

    /**
     * Innermost {@code fn*} method being emitted; used to load params when a {@link LocalBindingExpr} has
     * {@link LocalBinding#isArg} but no {@link #localSlots} entry. Do not use {@link LocalBinding#idx} for
     * {@link CloffleBytecodeRootNodeGen.Builder#emitLoadArgument} — idx is a JVM local slot from
     * {@code getAndIncLocalNum()}, not the positional index of the parameter.
     */
    private FnMethod currentFnMethod;

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

    /**
     * Tracks fn root nesting depth (0 = top-level convertRoot, 1 = first inner fn, etc.)
     * and the captured-frame locals at each depth, so that deeply nested closures can chain
     * through parent frames to reach grandparent (or higher) locals.
     */
    private int rootDepth = 0;
    private final Map<BytecodeLocal, Integer> localDepth = new HashMap<>();

    /** Per {@code beginRoot}/{@code endRoot}: frame-slot debugger names for {@link CloffleBytecodeRootNode}. */
    private final ArrayDeque<List<SlotDebug>> slotDebugByRoot = new ArrayDeque<>();

    private static final class SlotDebug {
        final BytecodeLocal local;
        final String name;

        SlotDebug(BytecodeLocal local, String name) {
            this.local = local;
            this.name = name;
        }
    }

    public ExprToBytecode(Clojure language, Source source) {
        this.language = language;
        this.source = source;
    }

    /**
     * @param narrowRootSourceSection when true, root {@link com.oracle.truffle.api.source.SourceSection} is the
     *                                  balanced span of {@code rootExpr} (later top-level forms in a multi-form
     *                                  Polyglot script). When false, spans all of {@link #source} (line-1 forms and
     *                                  standalone snippets — {@code ExprToBytecodeSourceLocationTest}).
     */
    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name, boolean narrowRootSourceSection) {
        return convertRoot(rootExpr, name, narrowRootSourceSection, false);
    }

    /**
     * @param inhibitRootStatementTag when true, the root-level expression's outermost
     *        {@link StandardTags.StatementTag} is suppressed (via {@link #skipNextStatementTag}).
     *        Used when the bytecode root is wrapped by a
     *        {@link net.javacrumbs.cloffle.nodes.SequentialFormNode} whose
     *        {@code TopLevelEvalNode} already provides the {@code StatementTag} for the line,
     *        preventing duplicate breakpoint halts. See {@link BytecodeTagPolicy}.
     */
    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name,
            boolean narrowRootSourceSection, boolean inhibitRootStatementTag) {
        return convertRoot(rootExpr, name, narrowRootSourceSection, inhibitRootStatementTag, false);
    }

    /**
     * @param inhibitAllStatementTags when true, suppresses all emitted {@link StandardTags.StatementTag}
     *        entries for this root. Used for internal eager-eval setup forms so DAP suspend-on-start
     *        does not halt in synthetic macroexpanded sources before reaching user code.
     */
    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name,
            boolean narrowRootSourceSection, boolean inhibitRootStatementTag, boolean inhibitAllStatementTags) {
        BytecodeParser<CloffleBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginSource(source);
            beginRootSourceSection(b, rootExpr, narrowRootSourceSection);
            b.beginRoot();
            pushRootSlotDebug();
            int rootLocals = countExprLocals(rootExpr) * 4;
            if (rootLocals > 0) {
                fillRootLocalPool(b, rootLocals);
            }
            if (inhibitRootStatementTag) {
                skipNextStatementTag = true;
            }
            if (inhibitAllStatementTags) {
                statementTagInhibitDepth++;
            }
            b.beginReturn();
            try {
                convert(rootExpr, b);
            } finally {
                skipNextStatementTag = false;
                if (inhibitAllStatementTags) {
                    statementTagInhibitDepth--;
                }
            }
            b.endReturn();
            if (rootLocals > 0) {
                discardRootLocalPool();
            }
            CloffleBytecodeRootNode rootNode = b.endRoot();
            applySlotDebugNames(rootNode, slotDebugByRoot.pop());
            rootNode.setName(name);
            b.endSourceSection();
            b.endSource();
        };
        return CloffleBytecodeRootNodeGen.create(language, BYTECODE_CONFIG, parser);
    }

    private void beginRootSourceSection(CloffleBytecodeRootNodeGen.Builder b, Expr rootExpr, boolean narrow) {
        if (!narrow || source == null) {
            b.beginSourceSection(0, source != null ? source.getLength() : 0);
            return;
        }
        if (rootExpr instanceof DefExpr de) {
            Optional<SourceSection> head = ExprSourceSpans.defFormHeadSourceSection(source, de);
            if (head.isPresent()) {
                SourceSection ss = head.get();
                b.beginSourceSection(ss.getCharIndex(), ss.getCharLength());
                return;
            }
        }
        int[] loc = ExprSourceSpans.extractLineColumn(rootExpr);
        if (loc[0] < 1 || loc[1] < 1) {
            b.beginSourceSection(0, source.getLength());
            return;
        }
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(source, loc[0], loc[1]);
        if (span.isEmpty()) {
            b.beginSourceSection(0, source.getLength());
            return;
        }
        ExprSourceSpans.CharSpan cs = span.get();
        if (cs.start() < 0 || cs.length() <= 0) {
            b.beginSourceSection(0, source.getLength());
            return;
        }
        b.beginSourceSection(cs.start(), cs.length());
    }

    /**
     * Narrow bytecode operations to a source span so guest stack frames are not all attributed to line 1
     * (whole-file root section). Uses 1-based line/column from the Clojure analyzer and the same balanced
     * s-expression span rules aligned with the legacy AST converter (removed).
     * <p>
     * Applies inside nested {@code fn*} bodies too: each {@link CloffleBytecodeRootNode} still exposes a
     * full-span root {@link com.oracle.truffle.api.source.SourceSection} (see
     * {@link clojure.lang.ExprToBytecodeSourceLocationTest}), while bytecode instructions nest narrower
     * sections for accurate throws and stack frames.
     */
    private void emitWithLineColumnSection(CloffleBytecodeRootNodeGen.Builder b, int line, int column, Runnable body) {
        emitWithLineColumnSection(b, line, column, 0, body);
    }

    /**
     * Nests source + Truffle debugger tags ({@link StandardTags}) for breakpoints / stepping / scopes.
     * Tags are omitted when {@link #statementTagInhibitDepth} &gt; 0; see {@link BytecodeTagPolicy}
     * for the policy decisions that control inhibition.
     */
    private void emitWithLineColumnSection(CloffleBytecodeRootNodeGen.Builder b, int line, int column, int tagFlags, Runnable body) {
        if (source == null || line < 1 || column < 1) {
            body.run();
            return;
        }
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(source, line, column);
        if (span.isEmpty()) {
            body.run();
            return;
        }
        ExprSourceSpans.CharSpan cs = span.get();
        b.beginSourceSection(cs.start(), cs.length());
        try {
            boolean tag = statementTagInhibitDepth == 0;
            boolean omitStatement = tag && skipNextStatementTag;
            if (omitStatement) {
                skipNextStatementTag = false;
            }
            if (tag) {
                if (!omitStatement) {
                    b.beginTag(StandardTags.StatementTag.class);
                }
                b.beginTag(StandardTags.ExpressionTag.class);
                if ((tagFlags & BC_TAG_CALL) != 0) {
                    b.beginTag(StandardTags.CallTag.class);
                }
                if ((tagFlags & BC_TAG_WRITE_VAR) != 0) {
                    b.beginTag(StandardTags.WriteVariableTag.class);
                }
                if ((tagFlags & BC_TAG_READ_VAR) != 0) {
                    b.beginTag(StandardTags.ReadVariableTag.class);
                }
            }
            try {
                body.run();
            } finally {
                if (tag) {
                    if ((tagFlags & BC_TAG_READ_VAR) != 0) {
                        b.endTag(StandardTags.ReadVariableTag.class);
                    }
                    if ((tagFlags & BC_TAG_WRITE_VAR) != 0) {
                        b.endTag(StandardTags.WriteVariableTag.class);
                    }
                    if ((tagFlags & BC_TAG_CALL) != 0) {
                        b.endTag(StandardTags.CallTag.class);
                    }
                    b.endTag(StandardTags.ExpressionTag.class);
                    if (!omitStatement) {
                        b.endTag(StandardTags.StatementTag.class);
                    }
                }
            }
        } finally {
            b.endSourceSection();
        }
    }

    /**
     * Nests a source section for the whole {@link Expr} using {@link ExprSourceSpans#extractLineColumn}
     * and the same rules as {@link #emitWithLineColumnSection}.
     */
    private void emitWithExprSection(CloffleBytecodeRootNodeGen.Builder b, Expr expr, Runnable body) {
        emitWithExprSection(b, expr, 0, body);
    }

    private void emitWithExprSection(CloffleBytecodeRootNodeGen.Builder b, Expr expr, int tagFlags, Runnable body) {
        int[] loc = ExprSourceSpans.extractLineColumn(expr);
        emitWithLineColumnSection(b, loc[0], loc[1], tagFlags, body);
    }

    /**
     * {@code def} / {@code defn}: tag the first source line only (not the full balanced form) so
     * line breakpoints on later lines (e.g. fn body) resolve to inner expressions.
     *
     * <p>{@link BytecodeTagPolicy#defHeadIsStatement} decides whether the head section carries
     * {@code StatementTag}: simple defs ({@code (def x 10)}) are steppable statements;
     * function definitions ({@code (defn f [x] ...)}) are not, matching Java/Python/JS UX.
     */
    private void emitDefExpr(CloffleBytecodeRootNodeGen.Builder b, DefExpr de) {
        Runnable defBody = () -> {
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
        };
        Optional<SourceSection> headOpt = ExprSourceSpans.defFormHeadSourceSection(source, de);
        if (headOpt.isPresent()) {
            SourceSection ss = headOpt.get();
            boolean isStatement = BytecodeTagPolicy.defHeadIsStatement(de) && !skipNextStatementTag;
            skipNextStatementTag = false;
            try {
                b.beginSourceSection(ss.getCharIndex(), ss.getCharLength());
                try {
                    if (isStatement) {
                        b.beginTag(StandardTags.StatementTag.class);
                    }
                    b.beginTag(StandardTags.ExpressionTag.class);
                    b.beginTag(StandardTags.WriteVariableTag.class);
                    try {
                        boolean inhibit = BytecodeTagPolicy.inhibitDefInitTags(de);
                        if (inhibit) {
                            statementTagInhibitDepth++;
                        }
                        try {
                            defBody.run();
                        } finally {
                            if (inhibit) {
                                statementTagInhibitDepth--;
                            }
                        }
                    } finally {
                        b.endTag(StandardTags.WriteVariableTag.class);
                        b.endTag(StandardTags.ExpressionTag.class);
                        if (isStatement) {
                            b.endTag(StandardTags.StatementTag.class);
                        }
                    }
                } finally {
                    b.endSourceSection();
                }
            } catch (Exception e) {
                defBody.run();
            }
        } else {
            defBody.run();
        }
    }

    /**
     * Locals that must survive the lifetime of the enclosing root's frame (because
     * inner closures may read them from a captured {@code MaterializedFrame} long after
     * the creating {@code Block} has ended) are pre-allocated at root scope.  The pool
     * is filled in {@link #fillRootLocalPool} right after {@code beginRoot()}, before any
     * {@code beginBlock()}, so the Bytecode DSL assigns them to the root rather than a
     * block and will NOT emit {@code CLEAR_LOCAL} when a block ends.
     */
    private final ArrayDeque<ArrayDeque<BytecodeLocal>> rootLocalPoolStack = new ArrayDeque<>();

    /**
     * When &gt; 0, nested {@link #emitWithLineColumnSection} calls omit Truffle statement/call/read/write
     * tags (source sections still apply). Incremented/decremented by sites whose policy is defined
     * in {@link BytecodeTagPolicy}: simple-def init inhibition
     * ({@link BytecodeTagPolicy#inhibitDefInitTags}) and invoke callee/arg dedup
     * ({@link BytecodeTagPolicy#inhibitCalleeArgTags}).
     */
    private int statementTagInhibitDepth;

    /**
     * When true, the <em>next</em> {@link #emitWithLineColumnSection} or {@link #emitDefExpr}
     * call suppresses its {@code StatementTag} and resets this flag. This prevents a duplicate
     * breakpoint halt when the bytecode root is wrapped by a {@code TopLevelEvalNode} that
     * already provides the line's {@code StatementTag}. See {@link BytecodeTagPolicy}.
     */
    private boolean skipNextStatementTag;

    /**
     * Pre-allocate root-scoped locals for the current fn root. Called right after
     * {@code beginRoot()}, before any {@code beginBlock()}, so every local in the pool
     * belongs to the Root scope and will never be cleared by the Bytecode DSL's
     * {@code CLEAR_LOCAL} at {@code endBlock()}.
     * <p>
     * The pool size is determined by {@link #countLocalsNeeded} (which walks the fn's AST
     * to estimate local allocations) multiplied by a safety factor. The multiplier is needed
     * because the Truffle builder may invoke the {@code beginTryFinally} handler lambda
     * multiple times (once per exit point), each invocation creating locals that the AST
     * pre-scan counts only once. Extra unused root-scoped slots are harmless (a few extra
     * frame slots per fn).
     */
    private void fillRootLocalPool(CloffleBytecodeRootNodeGen.Builder b, int size) {
        ArrayDeque<BytecodeLocal> pool = new ArrayDeque<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(b.createLocal());
        }
        rootLocalPoolStack.push(pool);
    }

    private void discardRootLocalPool() {
        rootLocalPoolStack.pop();
    }

    private void pushRootSlotDebug() {
        slotDebugByRoot.push(new ArrayList<>());
    }

    private void registerSlotDebugName(BytecodeLocal local, LocalBinding lb) {
        if (slotDebugByRoot.isEmpty() || lb == null || lb.sym == null) {
            return;
        }
        String n = lb.sym.getName();
        if (n == null) {
            return;
        }
        slotDebugByRoot.peek().add(new SlotDebug(local, n));
    }

    private static void applySlotDebugNames(CloffleBytecodeRootNode node, List<SlotDebug> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<Integer, String> map = new HashMap<>();
        for (SlotDebug e : entries) {
            try {
                map.put(e.local.getLocalOffset(), e.name);
            } catch (IllegalStateException ignored) {
                // Synthetic locals (e.g. serialization placeholders) that structurally
                // cannot expose an offset. Safe to skip — real params/let* bindings
                // always have valid offsets after endRoot().
            }
        }
        if (!map.isEmpty()) {
            node.setBytecodeLocalOffsetDebugNames(map);
        }
    }

    private BytecodeLocal createTrackedLocal(CloffleBytecodeRootNodeGen.Builder b) {
        ArrayDeque<BytecodeLocal> pool = rootLocalPoolStack.peek();
        BytecodeLocal local;
        if (pool != null && !pool.isEmpty()) {
            local = pool.poll();
        } else {
            local = b.createLocal();
        }
        localDepth.put(local, rootDepth);
        return local;
    }

    // ---- AST pre-scan: count locals needed for a fn root ----

    /**
     * Estimates how many {@link BytecodeLocal}s will be allocated by {@link #createTrackedLocal}
     * during emission of a fn body. The count includes closure copies, parameter locals,
     * recur infrastructure, and all temporaries from the body expression tree.
     * <p>
     * This is a best-effort estimate. The Truffle builder may allocate more locals than
     * counted here (e.g. {@code beginTryFinally}'s handler lambda is invoked once per exit
     * point, each call re-running {@code convert} and creating locals). A safety multiplier
     * is applied by the caller ({@link #convertFnExpr}) to compensate.
     */
    private static int countLocalsNeeded(FnExpr fnExpr) {
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
    private static int countExprLocals(Expr expr) {
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
            if (isGetInCall(ie.fexpr, ie.args)) {
                int c = countExprLocals((Expr) ie.args.nth(0)) + countExprLocals((Expr) ie.args.nth(1));
                if (ie.args.count() == 3) c += countExprLocals((Expr) ie.args.nth(2));
                return c;
            }
            if (isAssocInCall(ie.fexpr, ie.args)) {
                VectorExpr ve = (VectorExpr) ie.args.nth(1);
                int c = ve.args.count();
                c += countExprLocals((Expr) ie.args.nth(0));
                c += countExprLocals(ve);
                c += countExprLocals((Expr) ie.args.nth(2));
                return c;
            }
            if (isAssocCall(ie.fexpr, ie.args)) {
                int c = 0;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            if (isUpdateInCall(ie.fexpr, ie.args)) {
                VectorExpr ve = (VectorExpr) ie.args.nth(1);
                int c = ve.args.count() + 2;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            if (isUpdateCall(ie.fexpr, ie.args)) {
                int c = 2;
                for (int i = 0; i < ie.args.count(); i++) {
                    c += countExprLocals((Expr) ie.args.nth(i));
                }
                return c;
            }
            if (isMergeWithMapLiteral(ie.fexpr, ie.args)) {
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
            if (isFirstCall(ie.fexpr, ie.args) || isRestCall(ie.fexpr, ie.args)) {
                return countExprLocals((Expr) ie.args.nth(0));
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
            if (isRtAssocMethod(sme)) {
                int c = 0;
                for (int i = 0; i < sme.args.count(); i++) {
                    c += countExprLocals((Expr) sme.args.nth(i));
                }
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
        if (expr instanceof VectorExpr ve) {
            int c = 0;
            for (int i = 0; i < ve.args.count(); i++) c += countExprLocals((Expr) ve.args.nth(i));
            return c;
        }
        if (expr instanceof SetExpr se) {
            int c = 0;
            for (int i = 0; i < se.keys.count(); i++) c += countExprLocals((Expr) se.keys.nth(i));
            return c;
        }
        if (expr instanceof MapExpr me) {
            int c = 0;
            for (int i = 0; i < me.keyvals.count(); i++) c += countExprLocals((Expr) me.keyvals.nth(i));
            return c;
        }
        if (expr instanceof StaticInvokeExpr sie) {
            if (isGetInStatic(sie)) {
                int c = countExprLocals((Expr) sie.args.nth(0)) + countExprLocals((Expr) sie.args.nth(1));
                if (sie.args.count() == 3) c += countExprLocals((Expr) sie.args.nth(2));
                return c;
            }
            if (isAssocInStatic(sie)) {
                VectorExpr ve = (VectorExpr) sie.args.nth(1);
                int c = ve.args.count();
                c += countExprLocals((Expr) sie.args.nth(0));
                c += countExprLocals(ve);
                c += countExprLocals((Expr) sie.args.nth(2));
                return c;
            }
            if (isAssocStatic(sie)) {
                int c = 0;
                for (int i = 0; i < sie.args.count(); i++) {
                    c += countExprLocals((Expr) sie.args.nth(i));
                }
                return c;
            }
            if (isUpdateInStatic(sie)) {
                VectorExpr ve = (VectorExpr) sie.args.nth(1);
                int c = ve.args.count() + 2;
                for (int i = 0; i < sie.args.count(); i++) {
                    c += countExprLocals((Expr) sie.args.nth(i));
                }
                return c;
            }
            if (isUpdateStatic(sie)) {
                int c = 2;
                for (int i = 0; i < sie.args.count(); i++) {
                    c += countExprLocals((Expr) sie.args.nth(i));
                }
                return c;
            }
            if (isMergeStaticWithMapLiteral(sie)) {
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
            if (isFirstStatic(sie) || isRestStatic(sie)) {
                return countExprLocals((Expr) sie.args.nth(0));
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
     * Emit bytecode to load a local from the immediate parent fn's frame.
     * By the time this is called, all ancestor values have been copied into the
     * immediate parent's frame at fn entry (see {@link #emitClosureCopies}).
     * So a single {@code LoadLocalMaterialized(local, LoadArgument(0))} suffices.
     */
    private void emitOuterLocalLoad(CloffleBytecodeRootNodeGen.Builder b, BytecodeLocal targetLocal) {
        b.beginLoadLocalMaterialized(targetLocal);
        b.emitLoadArgument(0);
        b.endLoadLocalMaterialized();
    }

    /**
     * For each binding in {@code fnExpr.closes()}, if its existing {@code localSlots} entry
     * is from a grandparent root or deeper, copy the value from the captured frame
     * (argument 0 = parent frame) into a new local in the current root.
     * <p>
     * By induction the parent fn already copied ancestor values into its own frame,
     * so reading them via {@code LoadLocalMaterialized(parentLocal, LoadArgument(0))}
     * always works.  After copying, {@code localSlots} is updated to point at the
     * current-root local so that nested closures can find it one level up.
     */
    /**
     * Saved localSlots entries overridden by closure copies, restored after endRoot.
     */
    private final ArrayDeque<Map<LocalBinding, BytecodeLocal>> closureCopySaves = new ArrayDeque<>();

    private void emitClosureCopies(FnExpr fnExpr, LocalBinding thisBinding,
                                   CloffleBytecodeRootNodeGen.Builder b) {
        Map<LocalBinding, BytecodeLocal> saved = new HashMap<>();

        java.util.List<LocalBinding> toCopy = new java.util.ArrayList<>();
        clojure.lang.IPersistentMap closes = fnExpr.closes();
        if (closes != null && closes.count() > 0) {
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(closes); s != null; s = s.next()) {
                java.util.Map.Entry entry = (java.util.Map.Entry) s.first();
                toCopy.add((LocalBinding) entry.getKey());
            }
        }
        if (thisBinding != null && !toCopy.contains(thisBinding)) {
            toCopy.add(thisBinding);
        }

        for (LocalBinding lb : toCopy) {
            BytecodeLocal outerLocal = localSlots.get(lb);
            if (outerLocal == null) continue;
            Integer outerDepth = localDepth.get(outerLocal);
            if (outerDepth == null) outerDepth = 0;

            if (outerDepth < rootDepth) {
                BytecodeLocal copy = createTrackedLocal(b);
                registerSlotDebugName(copy, lb);
                b.beginStoreLocal(copy);
                b.beginLoadLocalMaterialized(outerLocal);
                b.emitLoadArgument(0);
                b.endLoadLocalMaterialized();
                b.endStoreLocal();
                saved.put(lb, outerLocal);
                localSlots.put(lb, copy);
                for (var e : new java.util.ArrayList<>(localSlots.entrySet())) {
                    if (e.getValue() == outerLocal && e.getKey() != lb) {
                        saved.putIfAbsent(e.getKey(), outerLocal);
                        localSlots.put(e.getKey(), copy);
                    }
                }
            }
        }
        closureCopySaves.push(saved);
    }

    private void restoreClosureCopies() {
        Map<LocalBinding, BytecodeLocal> saved = closureCopySaves.pop();
        for (var entry : saved.entrySet()) {
            localSlots.put(entry.getKey(), entry.getValue());
        }
    }

    public BytecodeRootNodes<CloffleBytecodeRootNode> convertRoot(Expr rootExpr, String name) {
        return convertRoot(rootExpr, name, false);
    }

    /**
     * Emit a callee or argument of an {@link InvokeExpr}, suppressing nested
     * {@link StandardTags.StatementTag} when {@link BytecodeTagPolicy#inhibitCalleeArgTags}
     * says so (e.g. {@link VarExpr} / {@link TheVarExpr} that share a source line with the
     * outer invoke and would otherwise cause duplicate breakpoint halts).
     */
    private void convertCalleeOrArgForInvoke(Expr expr, CloffleBytecodeRootNodeGen.Builder b) {
        if (BytecodeTagPolicy.inhibitCalleeArgTags(expr)) {
            statementTagInhibitDepth++;
            try {
                convert(expr, b);
            } finally {
                statementTagInhibitDepth--;
            }
        } else {
            convert(expr, b);
        }
    }

    public void convert(Expr expr, CloffleBytecodeRootNodeGen.Builder b) {
        if (expr instanceof ConstantExpr ce) {
            if (ce.v == null) {
                b.emitLoadNull();
            } else {
                emitConstantValue(ce.v, b);
            }
        } else if (expr instanceof NilExpr) {
            b.emitLoadNull();
        } else if (expr instanceof EmptyExpr ee) {
            // Mirror JVM emit: load via static field access so Truffle's equals-based
            // constant pool doesn't merge structurally-equal but type-distinct empties
            // (e.g. PersistentVector.EMPTY.equals(PersistentList.EMPTY) is true).
            if (ee.coll instanceof clojure.lang.IPersistentList) {
                b.emitStaticField(clojure.lang.PersistentList.class, "EMPTY");
            } else if (ee.coll instanceof clojure.lang.IPersistentVector) {
                b.emitStaticField(clojure.lang.PersistentVector.class, "EMPTY");
            } else if (ee.coll instanceof clojure.lang.PersistentShapeMap) {
                b.emitStaticField(clojure.lang.PersistentShapeMap.class, "EMPTY");
            } else if (ee.coll instanceof clojure.lang.IPersistentMap) {
                b.emitStaticField(clojure.lang.PersistentArrayMap.class, "EMPTY");
            } else if (ee.coll instanceof clojure.lang.IPersistentSet) {
                b.emitStaticField(clojure.lang.PersistentHashSet.class, "EMPTY");
            } else {
                b.emitLoadConstant(ee.coll);
            }
        } else if (expr instanceof KeywordExpr ke) {
            b.emitLoadConstant(ke.k);
        } else if (expr instanceof StringExpr se) {
            b.emitLoadConstant(se.str);
        } else if (expr instanceof BooleanExpr be) {
            b.emitLoadConstant(be.val ? clojure.lang.RT.T : clojure.lang.RT.F);
        } else if (expr instanceof NumberExpr ne) {
            b.emitLoadConstant(ne.val());
        } else if (expr instanceof LocalBindingExpr lbe) {
            int[] loc = ExprSourceSpans.localBindingReferenceLineColumn(source, lbe)
                    .orElseGet(() -> ExprSourceSpans.extractLineColumn(lbe));
            emitWithLineColumnSection(b, loc[0], loc[1], BC_TAG_READ_VAR, () -> {
                BytecodeLocal local = localSlots.get(lbe.b);
                if (local != null) {
                    try {
                        b.emitLoadLocal(local);
                    } catch (IllegalArgumentException e) {
                        emitOuterLocalLoad(b, local);
                    }
                } else {
                    if (lbe.b.isArg && currentFnMethod != null) {
                        int reqCount = currentFnMethod.reqParms().count();
                        boolean emitted = false;
                        for (int i = 0; i < reqCount; i++) {
                            if (currentFnMethod.reqParms().nth(i) == lbe.b) {
                                b.emitLoadArgument(i + 1); // +1: captured frame is arg 0
                                emitted = true;
                                break;
                            }
                        }
                        if (!emitted && currentFnMethod.restParm() != null && currentFnMethod.restParm() == lbe.b) {
                            b.emitGetRestArgs(reqCount);
                            emitted = true;
                        }
                        if (!emitted) {
                            System.out.println("WARNING: fn arg LocalBinding not in reqParms/restParm: " + lbe.b.sym);
                            b.emitLoadNull();
                        }
                    } else {
                        System.out.println("WARNING: LocalBinding not found in localSlots: " + lbe.b.sym);
                        b.emitLoadNull(); // Fallback
                    }
                }
            });
        } else if (expr instanceof VarExpr ve) {
            emitWithExprSection(b, ve, BC_TAG_READ_VAR, () -> {
                b.beginReadVar();
                b.emitLoadConstant(ve.var);
                b.endReadVar();
            });
        } else if (expr instanceof TheVarExpr tve) {
            emitWithExprSection(b, tve, () -> b.emitLoadConstant(tve.var));
        } else if (expr instanceof DefExpr de) {
            emitDefExpr(b, de);
        } else if (expr instanceof ImportExpr ie) {
            emitWithExprSection(b, ie, () -> b.emitImportClass(ie.c));
        } else if (expr instanceof AssignExpr ae) {
            emitWithExprSection(b, ae, BC_TAG_WRITE_VAR, () -> {
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
            });
        } else if (expr instanceof RecurExpr recurExpr) {
            LoopTarget target = loopStack.peek();
            if (target != null) {
                emitWithExprSection(b, recurExpr, () -> emitLoopRecur(recurExpr, b, target));
            } else {
                b.emitLoadNull();
            }
        } else if (expr instanceof LetExpr le) {
            // Non-loop: do not wrap the whole `(let* …)` in one source section — balanced spans cover the
            // body and steal line breakpoints to the `(let` line. Bindings and body use their own sections.
            Runnable letBody = () -> {
                int numBindings = le.bindingInits.count();
                if (numBindings > 0) {
                    b.beginBlock();
                    java.util.List<LocalBinding> letBindingKeys = new java.util.ArrayList<>(numBindings);
                    java.util.List<BytecodeLocal> letLocals = new java.util.ArrayList<>();
                    for (int i = 0; i < numBindings; i++) {
                        BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                        letBindingKeys.add(bi.binding());
                        BytecodeLocal local = createTrackedLocal(b);
                        registerSlotDebugName(local, bi.binding());

                        b.beginStoreLocal(local);
                        Class<?> fiClass = maybeFIBindingClass(bi.binding());
                        Expr initExpr = bi.init();
                        emitWithExprSection(b, initExpr, () -> {
                            if (fiClass != null) {
                                b.beginAdaptFI(fiClass);
                            }
                            convert(initExpr, b);
                            if (fiClass != null) {
                                b.endAdaptFI();
                            }
                        });
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
                    for (LocalBinding lb : letBindingKeys) {
                        localSlots.remove(lb);
                    }
                } else {
                    if (le.isLoop) {
                        b.beginBlock();
                        emitRecurWhileBody(b, java.util.List.of(), le.body);
                        b.endBlock();
                    } else {
                        convert(le.body, b);
                    }
                }
            };
            if (le.isLoop) {
                emitWithExprSection(b, le, letBody);
            } else {
                letBody.run();
            }
        } else if (expr instanceof LetFnExpr lfe) {
            emitWithExprSection(b, lfe, () -> {
                int n = lfe.bindingInits.count();
                if (n == 0) {
                    convert(lfe.body, b);
                } else {
                    b.beginBlock();
                    java.util.List<LocalBinding> letFnBindingKeys = new java.util.ArrayList<>(n);
                    java.util.List<BytecodeLocal> letFnLocals = new java.util.ArrayList<>(n);
                    // Register every binding local before emitting any init (matches Compiler: pre-seed env) so each
                    // fn* body resolves sibling LocalBindingExprs.
                    for (int i = 0; i < n; i++) {
                        BindingInit bi = (BindingInit) lfe.bindingInits.nth(i);
                        letFnBindingKeys.add(bi.binding());
                        BytecodeLocal local = createTrackedLocal(b);
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
                    for (LocalBinding lb : letFnBindingKeys) {
                        localSlots.remove(lb);
                    }
                }
            });
        } else if (expr instanceof BodyExpr be) {
            int count = be.exprs().count();
            if (count == 1) {
                // Single-expression body: avoid an extra BodyExpr wrapper section (compiler line metadata
                // for the implicit body often matches the enclosing `(let` line and steals breakpoints).
                convert((Expr) be.exprs().nth(0), b);
            } else {
                emitWithExprSection(b, be, () -> {
                    if (count == 0) {
                        b.emitLoadNull();
                    } else {
                        // Each non-final form must be a void statement: value-producing ops (e.g. Conditional
                        // from `if`) cannot stack multiple values inside one Block without discarding.
                        b.beginBlock();
                        for (int i = 0; i < count - 1; i++) {
                            b.beginBlock();
                            convert((Expr) be.exprs().nth(i), b);
                            b.endBlock();
                        }
                        convert((Expr) be.exprs().nth(count - 1), b);
                        b.endBlock();
                    }
                });
            }
        } else if (expr instanceof ListExpr le) {
            emitWithExprSection(b, le, () -> {
                b.beginCreateList();
                for (int i = 0; i < le.args.count(); i++) {
                    convert((Expr) le.args.nth(i), b);
                }
                b.endCreateList();
            });
        } else if (expr instanceof VectorExpr ve) {
            emitWithExprSection(b, ve, () -> {
                int count = ve.args == null ? 0 : ve.args.count();
                switch (count) {
                    case 0 -> {
                        b.emitCreateVector0();
                    }
                    case 1 -> {
                        b.beginCreateVector1();
                        convert((Expr) ve.args.nth(0), b);
                        b.endCreateVector1();
                    }
                    case 2 -> {
                        b.beginCreateVector2();
                        convert((Expr) ve.args.nth(0), b);
                        convert((Expr) ve.args.nth(1), b);
                        b.endCreateVector2();
                    }
                    case 3 -> {
                        b.beginCreateVector3();
                        convert((Expr) ve.args.nth(0), b);
                        convert((Expr) ve.args.nth(1), b);
                        convert((Expr) ve.args.nth(2), b);
                        b.endCreateVector3();
                    }
                    case 4 -> {
                        b.beginCreateVector4();
                        convert((Expr) ve.args.nth(0), b);
                        convert((Expr) ve.args.nth(1), b);
                        convert((Expr) ve.args.nth(2), b);
                        convert((Expr) ve.args.nth(3), b);
                        b.endCreateVector4();
                    }
                    default -> {
                        b.beginCreateVectorN();
                        for (int i = 0; i < count; i++) {
                            convert((Expr) ve.args.nth(i), b);
                        }
                        b.endCreateVectorN();
                    }
                }
            });
        } else if (expr instanceof SetExpr se) {
            emitWithExprSection(b, se, () -> {
                b.beginCreateSet();
                for (int i = 0; i < se.keys.count(); i++) {
                    convert((Expr) se.keys.nth(i), b);
                }
                b.endCreateSet();
            });
        } else if (expr instanceof MapExpr me) {
            emitWithExprSection(b, me, () -> {
                int pairCount = me.keyvals == null ? 0 : (me.keyvals.count() / 2);
                switch (pairCount) {
                    case 0 -> {
                        b.emitCreateMap0();
                    }
                    case 1 -> {
                        b.beginCreateMap1();
                        convert((Expr) me.keyvals.nth(0), b);
                        convert((Expr) me.keyvals.nth(1), b);
                        b.endCreateMap1();
                    }
                    case 2 -> {
                        b.beginCreateMap2();
                        convert((Expr) me.keyvals.nth(0), b);
                        convert((Expr) me.keyvals.nth(1), b);
                        convert((Expr) me.keyvals.nth(2), b);
                        convert((Expr) me.keyvals.nth(3), b);
                        b.endCreateMap2();
                    }
                    case 3 -> {
                        b.beginCreateMap3();
                        convert((Expr) me.keyvals.nth(0), b);
                        convert((Expr) me.keyvals.nth(1), b);
                        convert((Expr) me.keyvals.nth(2), b);
                        convert((Expr) me.keyvals.nth(3), b);
                        convert((Expr) me.keyvals.nth(4), b);
                        convert((Expr) me.keyvals.nth(5), b);
                        b.endCreateMap3();
                    }
                    case 4 -> {
                        b.beginCreateMap4();
                        convert((Expr) me.keyvals.nth(0), b);
                        convert((Expr) me.keyvals.nth(1), b);
                        convert((Expr) me.keyvals.nth(2), b);
                        convert((Expr) me.keyvals.nth(3), b);
                        convert((Expr) me.keyvals.nth(4), b);
                        convert((Expr) me.keyvals.nth(5), b);
                        convert((Expr) me.keyvals.nth(6), b);
                        convert((Expr) me.keyvals.nth(7), b);
                        b.endCreateMap4();
                    }
                    default -> {
                        b.beginCreateMapN();
                        for (int i = 0; i < me.keyvals.count(); i += 2) {
                            convert((Expr) me.keyvals.nth(i), b);
                            convert((Expr) me.keyvals.nth(i + 1), b);
                        }
                        b.endCreateMapN();
                    }
                }
            });
        } else if (expr instanceof MetaExpr me) {
            emitWithExprSection(b, me, () -> {
                b.beginWithMeta();
                convert(me.expr, b);
                convert(me.meta, b);
                b.endWithMeta();
            });
        } else if (expr instanceof KeywordInvokeExpr kie) {
            emitWithExprSection(b, kie, BC_TAG_CALL, () -> {
                // (:k target) — Specialized keyword lookup
                b.beginKeywordLookup(kie.kw.k);
                convert(kie.target, b);
                b.endKeywordLookup();
            });
        } else if (expr instanceof TryExpr tryExpr) {
            emitWithExprSection(b, tryExpr, () -> {
                b.beginBlock();
                BytecodeLocal resultLocal = createTrackedLocal(b);

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
                    BytecodeLocal excLocal = createTrackedLocal(b);
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
                        BytecodeLocal handlerLocal = createTrackedLocal(b);
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
                        localSlots.remove(cc.lb);

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
            });
        } else if (expr instanceof ThrowExpr throwExpr) {
            emitWithExprSection(b, throwExpr, () -> {
                b.beginThrowException();
                convert(throwExpr.excExpr, b);
                b.endThrowException();
            });
        } else if (expr instanceof IfExpr ie) {
            LoopTarget lt = loopStack.peek();
            if (lt != null && containsRecur(ie)) {
                // emitLoopIfExpr already applies emitWithExprSection (also used from convertLoopTail /
                // emitLoopBranchExpr without this convert() wrapper).
                emitLoopIfExpr(ie, b, lt);
            } else {
                emitWithExprSection(b, ie, () -> {
                    b.beginConditional();
                    b.beginTruthiness();
                    convert(ie.testExpr, b);
                    b.endTruthiness();
                    convert(ie.thenExpr, b);
                    convert(ie.elseExpr, b);
                    b.endConditional();
                });
            }
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
        } else if (expr instanceof NewInstanceExpr nie) {
            emitWithExprSection(b, nie, BC_TAG_CALL, () -> {
                // deftype* / reify* (Compiler.NewInstanceExpr). MVP: deftype value is null;
                // reify instantiates the generated class with closed-over locals (same ctor args as JVM emit).
                convertNewInstanceExpr(nie, b);
            });
        } else if (expr instanceof StaticMethodExpr sme) {
            if (isRtGetKeywordMethod(sme)) {
                emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                    Keyword kw = ((KeywordExpr) sme.args.nth(1)).k;
                    if (sme.args.count() == 2) {
                        b.beginKeywordLookup(kw);
                        convert((Expr) sme.args.nth(0), b);
                        b.endKeywordLookup();
                    } else {
                        b.beginKeywordLookupDefault(kw);
                        convert((Expr) sme.args.nth(0), b);
                        convert((Expr) sme.args.nth(2), b);
                        b.endKeywordLookupDefault();
                    }
                });
            } else if (isRtAssocMethod(sme)) {
                emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                    emitUnrolledAssoc((Expr) sme.args.nth(0), sme.args, b);
                });
            } else if (isRtNthMethod(sme)) {
                emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                    if (sme.args.count() == 2) {
                        b.beginVectorNth2();
                        convert((Expr) sme.args.nth(0), b);
                        convert((Expr) sme.args.nth(1), b);
                        b.endVectorNth2();
                    } else {
                        b.beginVectorNth3();
                        convert((Expr) sme.args.nth(0), b);
                        convert((Expr) sme.args.nth(1), b);
                        convert((Expr) sme.args.nth(2), b);
                        b.endVectorNth3();
                    }
                });
            } else if (isRtFirstMethod(sme)) {
                emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                    b.beginVectorFirst();
                    convert((Expr) sme.args.nth(0), b);
                    b.endVectorFirst();
                });
            } else {
                emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                    Object resolvedMethod = sme.method != null ? sme.method : Boolean.FALSE;
                    b.beginStaticMethod(sme.c, sme.methodName, resolvedMethod);
                    for (int i = 0; i < sme.args.count(); i++) {
                        convert((Expr) sme.args.nth(i), b);
                    }
                    b.endStaticMethod();
                });
            }
        } else if (expr instanceof InstanceMethodExpr ime) {
            emitWithExprSection(b, ime, BC_TAG_CALL, () -> {
                Object resolvedMethod = ime.method != null ? ime.method : Boolean.FALSE;
                b.beginInstanceMethod(ime.methodName, resolvedMethod);
                convert(ime.target, b);
                for (int i = 0; i < ime.args.count(); i++) {
                    convert((Expr) ime.args.nth(i), b);
                }
                b.endInstanceMethod();
            });
        } else if (expr instanceof NewExpr ne) {
            emitWithExprSection(b, ne, BC_TAG_CALL, () -> {
                b.beginNewObject(ne.c);
                for (int i = 0; i < ne.args.count(); i++) {
                    convert((Expr) ne.args.nth(i), b);
                }
                b.endNewObject();
            });
        } else if (expr instanceof StaticFieldExpr sfe) {
            emitWithExprSection(b, sfe, () -> b.emitStaticField(sfe.c, sfe.fieldName));
        } else if (expr instanceof InstanceFieldExpr ife) {
            emitWithExprSection(b, ife, () -> {
                b.beginInstanceField(ife.fieldName, ife.requireField);
                convert(ife.target, b);
                b.endInstanceField();
            });
        } else if (expr instanceof InstanceOfExpr ioe) {
            emitWithExprSection(b, ioe, () -> {
                b.beginInstanceOf(ioe.c);
                convert(ioe.expr, b);
                b.endInstanceOf();
            });
        } else if (expr instanceof MonitorEnterExpr mee) {
            emitWithExprSection(b, mee, () -> {
                b.beginMonitorEnter();
                convert(mee.target, b);
                b.endMonitorEnter();
            });
        } else if (expr instanceof MonitorExitExpr mee) {
            emitWithExprSection(b, mee, () -> {
                b.beginMonitorExit();
                convert(mee.target, b);
                b.endMonitorExit();
            });
        } else if (expr instanceof StaticInvokeExpr sie) {
            if (isGetInStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    Expr notFound = sie.args.count() == 3 ? (Expr) sie.args.nth(2) : null;
                    emitUnrolledGetIn((Expr) sie.args.nth(0), (VectorExpr) sie.args.nth(1), notFound, b);
                });
            } else if (isAssocInStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitUnrolledAssocIn((Expr) sie.args.nth(0), (VectorExpr) sie.args.nth(1), (Expr) sie.args.nth(2), b);
                });
            } else if (isAssocStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitUnrolledAssoc((Expr) sie.args.nth(0), sie.args, b);
                });
            } else if (isUpdateInStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitUnrolledUpdateIn((Expr) sie.args.nth(0), (VectorExpr) sie.args.nth(1), (Expr) sie.args.nth(2), getExtraArgs(sie.args, 3), b);
                });
            } else if (isUpdateStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitUnrolledUpdate((Expr) sie.args.nth(0), (Expr) sie.args.nth(1), (Expr) sie.args.nth(2), getExtraArgs(sie.args, 3), b);
                });
            } else if (isMergeStaticWithMapLiteral(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitUnrolledMergeMapLiteral((Expr) sie.args.nth(0), (MapExpr) sie.args.nth(1), b);
                });
            } else if (isNthStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    if (sie.args.count() == 2) {
                        b.beginVectorNth2();
                        convert((Expr) sie.args.nth(0), b);
                        convert((Expr) sie.args.nth(1), b);
                        b.endVectorNth2();
                    } else {
                        b.beginVectorNth3();
                        convert((Expr) sie.args.nth(0), b);
                        convert((Expr) sie.args.nth(1), b);
                        convert((Expr) sie.args.nth(2), b);
                        b.endVectorNth3();
                    }
                });
            } else if (isFirstStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    b.beginVectorFirst();
                    convert((Expr) sie.args.nth(0), b);
                    b.endVectorFirst();
                });
            } else if (isRestStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    b.beginVectorRest();
                    convert((Expr) sie.args.nth(0), b);
                    b.endVectorRest();
                });
            } else if (isGetKeywordStatic(sie)) {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    Keyword kw = ((KeywordExpr) sie.args.nth(1)).k;
                    if (sie.args.count() == 2) {
                        b.beginKeywordLookup(kw);
                        convert((Expr) sie.args.nth(0), b);
                        b.endKeywordLookup();
                    } else {
                        b.beginKeywordLookupDefault(kw);
                        convert((Expr) sie.args.nth(0), b);
                        convert((Expr) sie.args.nth(2), b);
                        b.endKeywordLookupDefault();
                    }
                });
            } else {
                emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                    emitInvokeHelper(
                            () -> {
                                b.beginReadVar();
                                b.emitLoadConstant(sie.var);
                                b.endReadVar();
                            },
                            sie.args,
                            b,
                            arg -> convert(arg, b)
                    );
                });
            }
        } else if (expr instanceof InvokeExpr ie) {
            if (isGetInCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    Expr notFound = ie.args.count() == 3 ? (Expr) ie.args.nth(2) : null;
                    emitUnrolledGetIn((Expr) ie.args.nth(0), (VectorExpr) ie.args.nth(1), notFound, b);
                });
            } else if (isAssocInCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    emitUnrolledAssocIn((Expr) ie.args.nth(0), (VectorExpr) ie.args.nth(1), (Expr) ie.args.nth(2), b);
                });
            } else if (isAssocCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    emitUnrolledAssoc((Expr) ie.args.nth(0), ie.args, b);
                });
            } else if (isUpdateInCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    emitUnrolledUpdateIn((Expr) ie.args.nth(0), (VectorExpr) ie.args.nth(1), (Expr) ie.args.nth(2), getExtraArgs(ie.args, 3), b);
                });
            } else if (isUpdateCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    emitUnrolledUpdate((Expr) ie.args.nth(0), (Expr) ie.args.nth(1), (Expr) ie.args.nth(2), getExtraArgs(ie.args, 3), b);
                });
            } else if (isMergeWithMapLiteral(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    emitUnrolledMergeMapLiteral((Expr) ie.args.nth(0), (MapExpr) ie.args.nth(1), b);
                });
            } else if (isNthCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    if (ie.args.count() == 2) {
                        b.beginVectorNth2();
                        convert((Expr) ie.args.nth(0), b);
                        convert((Expr) ie.args.nth(1), b);
                        b.endVectorNth2();
                    } else {
                        b.beginVectorNth3();
                        convert((Expr) ie.args.nth(0), b);
                        convert((Expr) ie.args.nth(1), b);
                        convert((Expr) ie.args.nth(2), b);
                        b.endVectorNth3();
                    }
                });
            } else if (isFirstCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    b.beginVectorFirst();
                    convert((Expr) ie.args.nth(0), b);
                    b.endVectorFirst();
                });
            } else if (isRestCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    b.beginVectorRest();
                    convert((Expr) ie.args.nth(0), b);
                    b.endVectorRest();
                });
            } else if (isKeywordInvoke(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    Keyword kw = ((KeywordExpr) ie.fexpr).k;
                    if (ie.args.count() == 1) {
                        b.beginKeywordLookup(kw);
                        convert((Expr) ie.args.nth(0), b);
                        b.endKeywordLookup();
                    } else {
                        b.beginKeywordLookupDefault(kw);
                        convert((Expr) ie.args.nth(0), b);
                        convert((Expr) ie.args.nth(1), b);
                        b.endKeywordLookupDefault();
                    }
                });
            } else if (isGetKeywordCall(ie.fexpr, ie.args)) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    Keyword kw = ((KeywordExpr) ie.args.nth(1)).k;
                    if (ie.args.count() == 2) {
                        b.beginKeywordLookup(kw);
                        convert((Expr) ie.args.nth(0), b);
                        b.endKeywordLookup();
                    } else {
                        b.beginKeywordLookupDefault(kw);
                        convert((Expr) ie.args.nth(0), b);
                        convert((Expr) ie.args.nth(2), b);
                        b.endKeywordLookupDefault();
                    }
                });
            } else {
                // Materialize callee in a local, then Invoke(loadLocal, args...). Block scopes the temp local.
                // Do not narrow `((fn* ...))`-style invokes at top level: outer root must keep a full-span section
                // (see ExprToBytecodeSourceLocationTest).
                // Inhibit StatementTag on callee/arg VarExpr: the outer emitWithExprSection(ie) already tags the
                // whole call; otherwise a line breakpoint matches the var load + invoke + TopLevelEvalNode (3×).
                Runnable invokeBlock = () -> {
                    b.beginBlock();
                    BytecodeLocal fnLocal = createTrackedLocal(b);
                    b.beginStoreLocal(fnLocal);
                    convertCalleeOrArgForInvoke(ie.fexpr, b);
                    b.endStoreLocal();
                    emitInvokeHelper(
                            () -> b.emitLoadLocal(fnLocal),
                            ie.args,
                            b,
                            arg -> convertCalleeOrArgForInvoke(arg, b)
                    );
                    b.endBlock();
                };
                if (rootDepth == 0 && ie.fexpr instanceof FnExpr) {
                    invokeBlock.run();
                } else {
                    emitWithExprSection(b, ie, BC_TAG_CALL, invokeBlock);
                }
            }
        } else if (expr instanceof QualifiedMethodExpr qme) {
            emitWithExprSection(b, qme, () -> {
                if (qme.preferOverloadedField()) {
                    convert(qme.fieldOverload, b);
                } else {
                    convert(QualifiedMethodExpr.buildThunkFnStar(C.EVAL, qme), b);
                }
            });
        } else if (expr instanceof CaseExpr ce) {
            emitWithExprSection(b, ce, () -> convertCaseExpr(ce, b));
        } else if (expr instanceof UnresolvedVarExpr) {
            throw new IllegalArgumentException("UnresolvedVarExpr cannot be evalled");
        } else {
            System.out.println("WARNING: Unimplemented expression fallback for " + expr.getClass().getName());
            // Fallback for unimplemented expressions
            b.emitLoadNull();
        }
    }

    private void emitInvokeHelper(Runnable emitCallee, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, java.util.function.Consumer<Expr> argConverter) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> {
                b.beginInvoke0();
                emitCallee.run();
                b.endInvoke0();
            }
            case 1 -> {
                b.beginInvoke1();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                b.endInvoke1();
            }
            case 2 -> {
                b.beginInvoke2();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                b.endInvoke2();
            }
            case 3 -> {
                b.beginInvoke3();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                b.endInvoke3();
            }
            case 4 -> {
                b.beginInvoke4();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                argConverter.accept((Expr) args.nth(3));
                b.endInvoke4();
            }
            default -> {
                b.beginInvokeN();
                emitCallee.run();
                for (int i = 0; i < count; i++) {
                    argConverter.accept((Expr) args.nth(i));
                }
                b.endInvokeN();
            }
        }
    }

    private static boolean isCoreVar(Var var, String name) {
        return var != null
                && var.ns != null
                && "clojure.core".equals(var.ns.name.getName())
                && name.equals(var.sym.getName());
    }

    private static boolean isKeywordInvoke(Expr fexpr, IPersistentVector args) {
        return fexpr instanceof KeywordExpr && (args.count() == 1 || args.count() == 2);
    }

    private static boolean isGetKeywordCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "get")) {
            return (args.count() == 2 || args.count() == 3) && args.nth(1) instanceof KeywordExpr;
        }
        return false;
    }

    private static boolean isRtGetKeywordMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "get".equals(sme.methodName)
                && (sme.args.count() == 2 || sme.args.count() == 3)
                && sme.args.nth(1) instanceof KeywordExpr;
    }

    private static boolean isGetKeywordStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "get")
                && (sie.args.count() == 2 || sie.args.count() == 3)
                && sie.args.nth(1) instanceof KeywordExpr;
    }

    private static boolean isGetInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "get-in")) {
            return (args.count() == 2 || args.count() == 3) && args.nth(1) instanceof VectorExpr;
        }
        return false;
    }

    private static boolean isAssocInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "assoc-in")) {
            return args.count() == 3 && args.nth(1) instanceof VectorExpr;
        }
        return false;
    }

    private static boolean isAssocCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "assoc")) {
            return args.count() >= 3 && ((args.count() - 1) % 2 == 0);
        }
        return false;
    }

    private static boolean isGetInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "get-in") && (sie.args.count() == 2 || sie.args.count() == 3) && sie.args.nth(1) instanceof VectorExpr;
    }

    private static boolean isAssocInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "assoc-in") && sie.args.count() == 3 && sie.args.nth(1) instanceof VectorExpr;
    }

    private static boolean isAssocStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "assoc") && sie.args.count() >= 3 && ((sie.args.count() - 1) % 2 == 0);
    }

    private static boolean isUpdateInCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "update-in")) {
            return args.count() >= 3 && args.nth(1) instanceof VectorExpr;
        }
        return false;
    }

    private static boolean isUpdateCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "update")) {
            return args.count() >= 3;
        }
        return false;
    }

    private static boolean isUpdateInStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "update-in") && sie.args.count() >= 3 && sie.args.nth(1) instanceof VectorExpr;
    }

    private static boolean isUpdateStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "update") && sie.args.count() >= 3;
    }

    private static boolean isMergeWithMapLiteral(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "merge")) {
            return args.count() == 2 && args.nth(1) instanceof MapExpr;
        }
        return false;
    }

    private static boolean isMergeStaticWithMapLiteral(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "merge") && sie.args.count() == 2 && sie.args.nth(1) instanceof MapExpr;
    }

    private static boolean isNthCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "nth")) {
            return args.count() == 2 || args.count() == 3;
        }
        return false;
    }

    private static boolean isNthStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "nth") && (sie.args.count() == 2 || sie.args.count() == 3);
    }

    private static boolean isRtNthMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "nth".equals(sme.methodName) && (sme.args.count() == 2 || sme.args.count() == 3);
    }

    private static boolean isFirstCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "first")) {
            return args.count() == 1;
        }
        return false;
    }

    private static boolean isFirstStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "first") && sie.args.count() == 1;
    }

    private static boolean isRtFirstMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "first".equals(sme.methodName) && sme.args.count() == 1;
    }

    private static boolean isRestCall(Expr fexpr, IPersistentVector args) {
        if (fexpr instanceof VarExpr ve && isCoreVar(ve.var, "rest")) {
            return args.count() == 1;
        }
        return false;
    }

    private static boolean isRestStatic(StaticInvokeExpr sie) {
        return isCoreVar(sie.var, "rest") && sie.args.count() == 1;
    }

    private static IPersistentVector getExtraArgs(IPersistentVector args, int startIndex) {
        if (args == null || args.count() <= startIndex) {
            return PersistentVector.EMPTY;
        }
        IPersistentVector extra = PersistentVector.EMPTY;
        for (int i = startIndex; i < args.count(); i++) {
            extra = (IPersistentVector) extra.cons(args.nth(i));
        }
        return extra;
    }

    private void emitUnrolledUpdate(Expr mExpr, Expr keyExpr, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        b.beginBlock();
        BytecodeLocal mLocal = createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        convert(mExpr, b);
        b.endStoreLocal();

        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(mLocal);

            emitInvokeWithFirstArg(
                    () -> {
                        b.beginKeywordLookup(ke.k);
                        b.emitLoadLocal(mLocal);
                        b.endKeywordLookup();
                    },
                    fnExpr,
                    extraArgs,
                    b
            );

            b.endKeywordAssoc();
        } else {
            BytecodeLocal keyLocal = createTrackedLocal(b);
            b.beginStoreLocal(keyLocal);
            convert(keyExpr, b);
            b.endStoreLocal();

            b.beginMapAssoc();
            b.emitLoadLocal(mLocal);
            b.emitLoadLocal(keyLocal);

            emitInvokeWithFirstArg(
                    () -> {
                        b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                        b.emitLoadLocal(mLocal);
                        b.emitLoadLocal(keyLocal);
                        b.endStaticMethod();
                    },
                    fnExpr,
                    extraArgs,
                    b
            );

            b.endMapAssoc();
        }
        b.endBlock();
    }

    private void emitInvokeWithFirstArg(Runnable emitFirstArg, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        int extraCount = extraArgs == null ? 0 : extraArgs.count();
        int totalArity = 1 + extraCount;
        switch (totalArity) {
            case 1 -> {
                b.beginInvoke1();
                convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                b.endInvoke1();
            }
            case 2 -> {
                b.beginInvoke2();
                convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                b.endInvoke2();
            }
            case 3 -> {
                b.beginInvoke3();
                convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(1), b);
                b.endInvoke3();
            }
            case 4 -> {
                b.beginInvoke4();
                convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(1), b);
                convertCalleeOrArgForInvoke((Expr) extraArgs.nth(2), b);
                b.endInvoke4();
            }
            default -> {
                b.beginInvokeN();
                convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                for (int i = 0; i < extraCount; i++) {
                    convertCalleeOrArgForInvoke((Expr) extraArgs.nth(i), b);
                }
                b.endInvokeN();
            }
        }
    }

    private void emitUnrolledUpdateIn(Expr mExpr, VectorExpr pathExpr, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args;
        int n = keys.count();
        if (n == 0) {
            emitInvokeWithFirstArg(() -> convert(mExpr, b), fnExpr, extraArgs, b);
            return;
        }
        if (n == 1) {
            emitUnrolledUpdate(mExpr, (Expr) keys.nth(0), fnExpr, extraArgs, b);
            return;
        }
        b.beginBlock();
        BytecodeLocal mLocal = createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        convert(mExpr, b);
        b.endStoreLocal();

        emitUpdateInStep(mLocal, keys, 0, fnExpr, extraArgs, b);

        b.endBlock();
    }

    private void emitUpdateInStep(BytecodeLocal currMapLocal, IPersistentVector keys, int index, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keys.nth(index);
        if (index == keys.count() - 1) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                b.emitLoadLocal(currMapLocal);
                emitInvokeWithFirstArg(
                        () -> {
                            b.beginKeywordLookup(ke.k);
                            b.emitLoadLocal(currMapLocal);
                            b.endKeywordLookup();
                        },
                        fnExpr,
                        extraArgs,
                        b
                );
                b.endKeywordAssoc();
            } else {
                BytecodeLocal keyLocal = createTrackedLocal(b);
                b.beginStoreLocal(keyLocal);
                convert(keyExpr, b);
                b.endStoreLocal();

                b.beginMapAssoc();
                b.emitLoadLocal(currMapLocal);
                b.emitLoadLocal(keyLocal);
                emitInvokeWithFirstArg(
                        () -> {
                            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                            b.emitLoadLocal(currMapLocal);
                            b.emitLoadLocal(keyLocal);
                            b.endStaticMethod();
                        },
                        fnExpr,
                        extraArgs,
                        b
                );
                b.endMapAssoc();
            }
            return;
        }
        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(currMapLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginKeywordLookup(ke.k);
            b.emitLoadLocal(currMapLocal);
            b.endKeywordLookup();
            b.endStoreLocal();

            emitUpdateInStep(nextMapLocal, keys, index + 1, fnExpr, extraArgs, b);

            b.endBlock();
            b.endKeywordAssoc();
        } else {
            BytecodeLocal keyLocal = createTrackedLocal(b);
            b.beginStoreLocal(keyLocal);
            convert(keyExpr, b);
            b.endStoreLocal();

            b.beginMapAssoc();
            b.emitLoadLocal(currMapLocal);
            b.emitLoadLocal(keyLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
            b.emitLoadLocal(currMapLocal);
            b.emitLoadLocal(keyLocal);
            b.endStaticMethod();
            b.endStoreLocal();

            emitUpdateInStep(nextMapLocal, keys, index + 1, fnExpr, extraArgs, b);

            b.endBlock();
            b.endMapAssoc();
        }
    }

    private void emitUnrolledMergeMapLiteral(Expr mExpr, MapExpr mapLiteral, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keyvals = mapLiteral.keyvals;
        if (keyvals == null || keyvals.count() == 0) {
            convert(mExpr, b);
            return;
        }
        int numPairs = keyvals.count() / 2;
        emitMergeStep(mExpr, keyvals, numPairs - 1, b);
    }

    private void emitMergeStep(Expr mExpr, IPersistentVector keyvals, int pairIndex, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keyvals.nth(2 * pairIndex);
        Expr valExpr = (Expr) keyvals.nth(2 * pairIndex + 1);
        if (pairIndex == 0) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                convert(mExpr, b);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                convert(mExpr, b);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
        } else {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                emitMergeStep(mExpr, keyvals, pairIndex - 1, b);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                emitMergeStep(mExpr, keyvals, pairIndex - 1, b);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
        }
    }

    private static boolean isRtAssocMethod(StaticMethodExpr sme) {
        return sme.c == RT.class && "assoc".equals(sme.methodName) && sme.args.count() == 3;
    }

    private void emitUnrolledAssoc(Expr mExpr, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b) {
        int numPairs = (args.count() - 1) / 2;
        emitAssocStep(mExpr, args, numPairs - 1, b);
    }

    private void emitAssocStep(Expr mExpr, IPersistentVector args, int pairIndex, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) args.nth(1 + 2 * pairIndex);
        Expr valExpr = (Expr) args.nth(2 + 2 * pairIndex);
        if (pairIndex == 0) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                convert(mExpr, b);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                convert(mExpr, b);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
        } else {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                emitAssocStep(mExpr, args, pairIndex - 1, b);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                emitAssocStep(mExpr, args, pairIndex - 1, b);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
        }
    }

    private void emitUnrolledGetIn(Expr mExpr, VectorExpr pathExpr, Expr notFoundExpr, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args;
        int n = keys.count();
        if (n == 0) {
            convert(mExpr, b);
            return;
        }
        emitGetChain(mExpr, keys, 0, notFoundExpr, b);
    }

    private void emitGetChain(Expr mExpr, IPersistentVector keys, int index, Expr notFoundExpr, CloffleBytecodeRootNodeGen.Builder b) {
        if (index == keys.count()) {
            convert(mExpr, b);
            return;
        }
        Expr keyExpr = (Expr) keys.nth(keys.count() - 1 - index);
        boolean isLast = (index == 0);
        if (keyExpr instanceof KeywordExpr ke) {
            if (isLast && notFoundExpr != null) {
                b.beginKeywordLookupDefault(ke.k);
                emitGetChain(mExpr, keys, index + 1, notFoundExpr, b);
                convert(notFoundExpr, b);
                b.endKeywordLookupDefault();
            } else {
                b.beginKeywordLookup(ke.k);
                emitGetChain(mExpr, keys, index + 1, notFoundExpr, b);
                b.endKeywordLookup();
            }
        } else {
            if (isLast && notFoundExpr != null) {
                b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                emitGetChain(mExpr, keys, index + 1, notFoundExpr, b);
                convert(keyExpr, b);
                convert(notFoundExpr, b);
                b.endStaticMethod();
            } else {
                b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                emitGetChain(mExpr, keys, index + 1, notFoundExpr, b);
                convert(keyExpr, b);
                b.endStaticMethod();
            }
        }
    }

    private void emitUnrolledAssocIn(Expr mExpr, VectorExpr pathExpr, Expr valExpr, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args;
        int n = keys.count();
        if (n == 0) {
            convert(valExpr, b);
            return;
        }
        if (n == 1) {
            Expr keyExpr = (Expr) keys.nth(0);
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                convert(mExpr, b);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                convert(mExpr, b);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
            return;
        }
        b.beginBlock();
        BytecodeLocal mLocal = createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        convert(mExpr, b);
        b.endStoreLocal();

        emitAssocInStep(mLocal, keys, 0, valExpr, b);

        b.endBlock();
    }

    private void emitAssocInStep(BytecodeLocal currMapLocal, IPersistentVector keys, int index, Expr valExpr, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keys.nth(index);
        if (index == keys.count() - 1) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                b.emitLoadLocal(currMapLocal);
                convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                b.emitLoadLocal(currMapLocal);
                convert(keyExpr, b);
                convert(valExpr, b);
                b.endMapAssoc();
            }
            return;
        }
        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(currMapLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginKeywordLookup(ke.k);
            b.emitLoadLocal(currMapLocal);
            b.endKeywordLookup();
            b.endStoreLocal();

            emitAssocInStep(nextMapLocal, keys, index + 1, valExpr, b);

            b.endBlock();

            b.endKeywordAssoc();
        } else {
            b.beginMapAssoc();
            b.emitLoadLocal(currMapLocal);
            convert(keyExpr, b);

            b.beginBlock();
            BytecodeLocal nextMapLocal = createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
            b.emitLoadLocal(currMapLocal);
            convert(keyExpr, b);
            b.endStaticMethod();
            b.endStoreLocal();

            emitAssocInStep(nextMapLocal, keys, index + 1, valExpr, b);

            b.endBlock();

            b.endMapAssoc();
        }
    }

    /**
     * MVP for {@code deftype*} / {@code reify*}: not full Clojure JVM parity — enough to instantiate
     * {@link NewInstanceExpr} (deftype vs reify).
     */
    private void convertNewInstanceExpr(NewInstanceExpr nie, CloffleBytecodeRootNodeGen.Builder b) {
        if (nie.isDeftype()) {
            b.emitLoadNull();
            return;
        }
        Class<?> c = nie.getCompiledClass();
        b.beginNewObject(c);
        for (int i = 0; i < nie.closesExprs.count(); i++) {
            convert((Expr) nie.closesExprs.nth(i), b);
        }
        b.endNewObject();
    }

    /**
     * Shared lowering for {@code loop*} and {@code fn*} method bodies: {@code While} + continue flag; tail
     * {@code recur} rebinds {@code locals} (loop bindings or fn params in order, including rest arg).
     * <p>
     * <b>{@code RT/conj} + {@code recur}:</b> {@code recur} args are arbitrary expressions; accumulators built with
     * {@link RT#conj} are covered by {@code clojure.lang.BytecodeBindingsAndLoopsTest#loopStarRecurWithRtConjAccumulator}.
     * Failures in that shape usually indicate collection / {@code conj} semantics, not this loop scaffold.
     */
    private void emitRecurWhileBody(
            CloffleBytecodeRootNodeGen.Builder b, java.util.List<BytecodeLocal> recurLocals, Expr body) {
        BytecodeLocal continueLocal = createTrackedLocal(b);
        BytecodeLocal resultLocal = createTrackedLocal(b);

        loopStack.push(new LoopTarget(recurLocals, continueLocal, resultLocal));
        try {
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
                    b.beginBlock();
                    convert((Expr) be.exprs().nth(i), b);
                    b.endBlock();
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
            emitWithExprSection(b, re, () -> emitLoopRecur(re, b, lt));
        } else if (expr instanceof IfExpr ie) {
            emitLoopIfExpr(ie, b, lt);
        } else if (expr instanceof CaseExpr ce && containsRecur(ce)) {
            emitLoopCaseExpr(ce, b, lt);
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
            java.util.List<LocalBinding> letBindingKeys = new java.util.ArrayList<>(numBindings);
            for (int i = 0; i < numBindings; i++) {
                BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                letBindingKeys.add(bi.binding());
                BytecodeLocal local = createTrackedLocal(b);
                registerSlotDebugName(local, bi.binding());
                b.beginStoreLocal(local);
                Class<?> fiClass = maybeFIBindingClass(bi.binding());
                if (fiClass != null) {
                    b.beginAdaptFI(fiClass);
                }
                convert(bi.init(), b);
                if (fiClass != null) {
                    b.endAdaptFI();
                }
                b.endStoreLocal();
                localSlots.put(bi.binding(), local);
            }
            convertLoopBody(le.body, b);
            b.endBlock();
            for (LocalBinding lb : letBindingKeys) {
                localSlots.remove(lb);
            }
        } else {
            convertLoopBody(le.body, b);
        }
    }

    /**
     * Tail {@code if} inside a {@code loop*} or {@code fn*} recur region: void branches ({@link CloffleBytecodeRootNodeGen.Builder#beginIfThenElse})
     * so a tail {@code recur} does not need to fake a value for {@link CloffleBytecodeRootNodeGen.Builder#beginConditional}.
     */
    private void emitLoopIfExpr(IfExpr ie, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        emitWithExprSection(b, ie, () -> {
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
        });
    }

    private void emitLoopBranchExpr(Expr branch, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        if (branch instanceof RecurExpr re) {
            emitWithExprSection(b, re, () -> emitLoopRecur(re, b, lt));
        } else if (branch instanceof IfExpr inner) {
            emitLoopIfExpr(inner, b, lt);
        } else if (branch instanceof CaseExpr ce && containsRecur(ce)) {
            emitLoopCaseExpr(ce, b, lt);
        } else if (branch instanceof LetExpr le) {
            if (le.isLoop) {
                b.beginStoreLocal(lt.resultLocal());
                convert(le, b);
                b.endStoreLocal();
            } else {
                emitLetExprAsLoopTail(le, b);
            }
        } else if (branch instanceof BodyExpr be) {
            convertLoopBody(be, b);
        } else {
            b.beginStoreLocal(lt.resultLocal());
            convert(branch, b);
            b.endStoreLocal();
        }
    }

    /**
     * True if {@code recur} appears inside this expression targeting the <em>current</em> loop/fn
     * recur point.  Traverses {@code if}, {@code do}, and non-loop {@code let*} but stops at
     * {@code loop*} boundaries ({@code LetExpr.isLoop}) because a nested loop establishes its
     * own recur target — any {@code recur} inside belongs to the inner loop, not the outer one.
     */

    /**
     * Emit a constant value, handling the case where the value is an {@link clojure.lang.IObj}
     * with non-null metadata. Truffle's {@code ConstantsBuffer} deduplicates constants using
     * {@code Object.equals()}, but Clojure's {@code Symbol.equals()} (and similar) ignores
     * metadata. Two symbols with the same name but different metadata would be collapsed to
     * whichever was added first, losing the metadata of the second. To prevent this, we strip
     * the metadata, emit the bare value as the constant, and re-apply the metadata at runtime
     * via {@code WithMeta}.
     */
    private void emitConstantValue(Object v, CloffleBytecodeRootNodeGen.Builder b) {
        if (v instanceof clojure.lang.IObj iobj) {
            clojure.lang.IPersistentMap meta = iobj.meta();
            if (meta != null) {
                b.beginWithMeta();
                emitConstantNoMeta(iobj.withMeta(null), b);
                emitConstantNoMeta(meta, b);
                b.endWithMeta();
                return;
            }
        }
        emitConstantNoMeta(v, b);
    }

    private static boolean safeForConstantPool(Object v) {
        return v == null
            || v instanceof String
            || v instanceof Number
            || v instanceof Boolean
            || v instanceof Character
            || v instanceof Class
            || v instanceof clojure.lang.Keyword
            || v instanceof clojure.lang.Symbol;
    }

    private static void emitConstantNoMeta(Object v, CloffleBytecodeRootNodeGen.Builder b) {
        if (!safeForConstantPool(v)) {
            b.emitLoadIdentityConstant(new CloffleBytecodeRootNode.IdentityConstant(v));
        } else {
            b.emitLoadConstant(v);
        }
    }

    private static boolean containsRecur(Expr e) {
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

    private void emitLoopRecur(RecurExpr re, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        if (re.args.count() != lt.locals().size()) {
            throw new IllegalStateException(
                    "recur: expected " + lt.locals().size() + " args, got " + re.args.count());
        }
        int n = re.args.count();
        b.beginBlock();
        if (n > 1) {
            // Evaluate all recur args into temporaries before storing any — otherwise left-to-right
            // stores let later args see partially-updated locals (e.g. (recur (next p) (cons (first p) d))
            // would read the already-advanced p for the second arg).
            BytecodeLocal[] temps = new BytecodeLocal[n];
            for (int i = 0; i < n; i++) {
                temps[i] = createTrackedLocal(b);
                b.beginStoreLocal(temps[i]);
                convert((Expr) re.args.nth(i), b);
                b.endStoreLocal();
            }
            for (int i = 0; i < n; i++) {
                b.beginStoreLocal(lt.locals().get(i));
                b.emitLoadLocal(temps[i]);
                b.endStoreLocal();
            }
        } else if (n == 1) {
            b.beginStoreLocal(lt.locals().get(0));
            convert((Expr) re.args.nth(0), b);
            b.endStoreLocal();
        }
        b.beginStoreLocal(lt.continueLocal());
        b.emitLoadConstant(RT.T);
        b.endStoreLocal();
        b.endBlock();
    }

    private static Class<?> maybeFIBindingClass(clojure.lang.Compiler.LocalBinding binding) {
        if (binding.tag == null) return null;
        Class<?> c = clojure.lang.Compiler.HostExpr.maybeClass(binding.tag, true);
        if (c != null && clojure.lang.Compiler.FISupport.maybeFIMethod(c) != null) return c;
        return null;
    }

    private static String fnArityName(FnExpr fnExpr) {
        String compiled = fnExpr.compiledName();
        if (compiled != null) return clojure.lang.Compiler.demunge(compiled);
        String tn = fnExpr.thisName();
        return tn != null ? tn : "fn";
    }

    private void convertFnExpr(FnExpr fnExpr, CloffleBytecodeRootNodeGen.Builder b) {
        String thisName = fnExpr.thisName();
        clojure.lang.Compiler.LocalBinding thisBinding = null;
        java.util.List<clojure.lang.Compiler.LocalBinding> allThisBindings = new java.util.ArrayList<>();
        if (thisName != null) {
            clojure.lang.IPersistentCollection methods = fnExpr.methods();
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(methods); s != null; s = s.next()) {
                clojure.lang.Compiler.FnMethod fm = (clojure.lang.Compiler.FnMethod) s.first();
                clojure.lang.IPersistentMap locals = fm.locals();
                if (locals != null) {
                    for (clojure.lang.ISeq ls = clojure.lang.RT.seq(locals); ls != null; ls = ls.next()) {
                        java.util.Map.Entry entry = (java.util.Map.Entry) ls.first();
                        clojure.lang.Compiler.LocalBinding lb = (clojure.lang.Compiler.LocalBinding) entry.getValue();
                        if (!lb.isArg && (thisName.equals(lb.name) || thisName.equals(lb.sym.getName()))) {
                            if (thisBinding == null) thisBinding = lb;
                            allThisBindings.add(lb);
                            break;
                        }
                    }
                }
            }
        }

        BytecodeLocal thisLocal = null;
        if (thisBinding != null) {
            thisLocal = createTrackedLocal(b);
            for (clojure.lang.Compiler.LocalBinding tb : allThisBindings) {
                localSlots.put(tb, thisLocal);
            }
        }

        b.beginRoot();
        rootDepth++;
        pushRootSlotDebug();
        int neededCount = countLocalsNeeded(fnExpr);
        // Safety margin: the count may underestimate due to Truffle-internal patterns
        // (e.g. finally handler lambda invoked multiple times, future expression types).
        // Extra unused root-scoped slots are harmless.
        fillRootLocalPool(b, neededCount * 4);

        emitClosureCopies(fnExpr, thisBinding, b);

        clojure.lang.IPersistentCollection methods = fnExpr.methods();
        int methodCount = methods.count();

        b.beginReturn();

        if (methodCount == 1) {
            FnMethod fm = (FnMethod) clojure.lang.RT.seq(methods).first();
            int reqCount = fm.reqParms().count();
            boolean variadic = fm.restParm() != null;
            BytecodeLocal argCountLocal = createTrackedLocal(b);
            b.beginBlock();
            b.beginStoreLocal(argCountLocal);
            b.emitGetArgCount();
            b.endStoreLocal();
            b.beginConditional();
            b.beginCheckArity(reqCount, variadic);
            b.emitLoadLocal(argCountLocal);
            b.endCheckArity();
            convertFnMethod(fnExpr, fm, b);
            b.beginThrowArity();
            b.emitLoadLocal(argCountLocal);
            b.emitLoadConstant(fnArityName(fnExpr));
            b.endThrowArity();
            b.endConditional();
            b.endBlock();
        } else {
            BytecodeLocal argCountLocal = createTrackedLocal(b);
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

            emitFnArityDispatch(b, fnExpr, methodList, 0, argCountLocal, fnArityName(fnExpr));
            b.endBlock();
        }

        b.endReturn();
        rootDepth--;
        discardRootLocalPool();
        CloffleBytecodeRootNode innerNode = b.endRoot();
        applySlotDebugNames(innerNode, slotDebugByRoot.pop());
        restoreClosureCopies();
        innerNode.setName(fnArityName(fnExpr));

        int closureReqArity = 0;
        boolean closureVariadic = false;
        for (clojure.lang.ISeq ms = clojure.lang.RT.seq(methods); ms != null; ms = ms.next()) {
            FnMethod m = (FnMethod) ms.first();
            if (m.restParm() != null) {
                closureVariadic = true;
                closureReqArity = m.reqParms().count();
            }
        }
        if (!closureVariadic && methodCount == 1) {
            FnMethod m = (FnMethod) clojure.lang.RT.seq(methods).first();
            closureReqArity = m.reqParms().count();
        }

        boolean capturesOuterLocals = (fnExpr.closes() != null && fnExpr.closes().count() > 0);
        if (thisLocal != null) {
            // Write closure to thisLocal on the live frame before materializing
            // the captured environment; otherwise emitClosureCopies reads a stale snapshot (uninit self).
            b.beginBlock();
            b.beginStoreLocal(thisLocal);
            b.beginCreateClosurePendingCapture(closureReqArity, closureVariadic);
            b.emitLoadConstant(innerNode);
            b.endCreateClosurePendingCapture();
            b.endStoreLocal();
            b.beginFinalizeClosureCapture();
            b.emitLoadLocal(thisLocal);
            b.emitGetOuterFrame();
            b.endFinalizeClosureCapture();
            b.endBlock();
        } else if (capturesOuterLocals) {
            b.beginCreateClosure(closureReqArity, closureVariadic);
            b.emitLoadConstant(innerNode);
            b.emitGetOuterFrame();
            b.endCreateClosure();
        } else {
            b.beginCreateClosure(closureReqArity, closureVariadic);
            b.emitLoadConstant(innerNode);
            b.emitLoadNull();
            b.endCreateClosure();
        }
    }

    /**
     * Multi-arity {@code fn*} shares one {@link #localSlots} map while each arity's params live in a
     * block-scoped {@link BytecodeLocal}. After {@code endBlock()} those locals are cleared; if
     * {@code localSlots} still maps another method's {@link LocalBinding} to the same pooled local,
     * a later arity's body can emit a load to an illegal slot (e.g. concat's {@code cat} colliding
     * with a rest-arg slot). Drop param entries for all arities other than {@code current}.
     */
    private void removeOtherArityParamsFromLocalSlots(FnExpr owner, FnMethod current) {
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(owner.methods()); s != null; s = s.next()) {
            FnMethod om = (FnMethod) s.first();
            if (om == current) {
                continue;
            }
            for (int i = 0; i < om.reqParms().count(); i++) {
                localSlots.remove((LocalBinding) om.reqParms().nth(i));
            }
            if (om.restParm() != null) {
                localSlots.remove(om.restParm());
            }
        }
    }

    private void convertFnMethod(FnExpr owner, FnMethod fm, CloffleBytecodeRootNodeGen.Builder b) {
        removeOtherArityParamsFromLocalSlots(owner, fm);
        FnMethod prev = currentFnMethod;
        currentFnMethod = fm;
        try {
            int bindings = fm.reqParms().count() + (fm.restParm() != null ? 1 : 0);
            java.util.ArrayList<BytecodeLocal> paramLocals = new java.util.ArrayList<>(bindings);
            if (bindings > 0) {
                b.beginBlock();

                for (int i = 0; i < fm.reqParms().count(); i++) {
                    LocalBinding lb = (LocalBinding) fm.reqParms().nth(i);
                    BytecodeLocal local = createTrackedLocal(b);
                    registerSlotDebugName(local, lb);
                    localSlots.put(lb, local);
                    paramLocals.add(local);
                    b.beginStoreLocal(local);
                    b.emitLoadArgument(i + 1);
                    b.endStoreLocal();
                }

                if (fm.restParm() != null) {
                    LocalBinding lb = fm.restParm();
                    BytecodeLocal local = createTrackedLocal(b);
                    registerSlotDebugName(local, lb);
                    localSlots.put(lb, local);
                    paramLocals.add(local);

                    b.beginStoreLocal(local);
                    b.emitGetRestArgs(fm.reqParms().count());
                    b.endStoreLocal();
                }

                emitRecurWhileBody(b, paramLocals, fm.body());

                b.endBlock();
            } else {
                emitRecurWhileBody(b, java.util.List.of(), fm.body());
            }
        } finally {
            currentFnMethod = prev;
        }
    }

    /**
     * Nested {@code Conditional}s for multi-arity {@code fn*} dispatch. Each conditional is
     * {@code (if (checkArity ...) body else nextOrThrow)}.
     */
    private void emitFnArityDispatch(
            CloffleBytecodeRootNodeGen.Builder b,
            FnExpr owner,
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

        convertFnMethod(owner, fm, b);

        if (last) {
            b.beginThrowArity();
            b.emitLoadLocal(argCountLocal);
            b.emitLoadConstant(fnName != null ? fnName : "fn");
            b.endThrowArity();
        } else {
            emitFnArityDispatch(b, owner, methodList, index + 1, argCountLocal, fnName);
        }
        b.endConditional();
    }

    private static final Keyword CASE_INT = Keyword.intern(null, "int");
    private static final Keyword CASE_HASH_EQUIV = Keyword.intern(null, "hash-equiv");
    private static final Keyword CASE_HASH_IDENTITY = Keyword.intern(null, "hash-identity");

    /**
     * {@code case} at the tail of a {@code loop*}/{@code fn*} recur region: uses void
     * {@code beginIfThenElse} instead of value-producing {@code beginConditional} so
     * {@code recur} branches (which are void jumps) don't violate the builder's
     * value-producing requirement.
     */
    private void emitLoopCaseExpr(CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt) {
        emitWithExprSection(b, ce, () -> {
            b.beginBlock();
            BytecodeLocal discLocal = createTrackedLocal(b);
            b.beginStoreLocal(discLocal);
            convert(ce.expr, b);
            b.endStoreLocal();

            BytecodeLocal keyLocal = createTrackedLocal(b);
            b.beginStoreLocal(keyLocal);
            if (ce.testType.equals(CASE_INT)) {
                b.beginStaticMethod(CaseExprRuntime.class, "intDispatchKey", Boolean.FALSE);
                b.emitLoadLocal(discLocal);
                b.emitLoadConstant(ce.shift);
                b.emitLoadConstant(ce.mask);
                b.endStaticMethod();
            } else {
                b.beginStaticMethod(CaseExprRuntime.class, "hashDispatchKey", Boolean.FALSE);
                b.emitLoadLocal(discLocal);
                b.emitLoadConstant(ce.shift);
                b.emitLoadConstant(ce.mask);
                b.endStaticMethod();
            }
            b.endStoreLocal();

            if (ce.tests.isEmpty()) {
                emitLoopBranchExpr(ce.defaultExpr, b, lt);
                b.endBlock();
                return;
            }

            java.util.ArrayList<Integer> keys = new java.util.ArrayList<>(ce.tests.keySet());
            emitLoopCaseKeyChain(ce, b, lt, discLocal, keyLocal, keys, 0);
            b.endBlock();
        });
    }

    private void emitLoopCaseKeyChain(
            CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt,
            BytecodeLocal discLocal, BytecodeLocal keyLocal,
            java.util.ArrayList<Integer> keys, int idx) {
        if (idx >= keys.size()) {
            emitLoopBranchExpr(ce.defaultExpr, b, lt);
            return;
        }
        Integer k = keys.get(idx);
        b.beginIfThenElse();
        b.beginTruthiness();
        b.beginStaticMethod(CaseExprRuntime.class, "intEq", Boolean.FALSE);
        b.emitLoadLocal(keyLocal);
        b.emitLoadConstant(k);
        b.endStaticMethod();
        b.endTruthiness();
        b.beginBlock();
        emitLoopCaseBucket(ce, b, lt, discLocal, k);
        b.endBlock();
        b.beginBlock();
        emitLoopCaseKeyChain(ce, b, lt, discLocal, keyLocal, keys, idx + 1);
        b.endBlock();
        b.endIfThenElse();
    }

    private void emitLoopCaseBucket(CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b, LoopTarget lt, BytecodeLocal discLocal, Integer k) {
        if (skipCheckContains(ce, k)) {
            emitLoopBranchExpr(ce.thens.get(k), b, lt);
            return;
        }
        if (ce.testType.equals(CASE_INT) || ce.testType.equals(CASE_HASH_EQUIV)) {
            b.beginIfThenElse();
            b.beginTruthiness();
            b.beginStaticMethod(clojure.lang.Util.class, "equiv", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod();
            b.endTruthiness();
            b.beginBlock();
            emitLoopBranchExpr(ce.thens.get(k), b, lt);
            b.endBlock();
            b.beginBlock();
            emitLoopBranchExpr(ce.defaultExpr, b, lt);
            b.endBlock();
            b.endIfThenElse();
        } else if (ce.testType.equals(CASE_HASH_IDENTITY)) {
            b.beginIfThenElse();
            b.beginTruthiness();
            b.beginStaticMethod(CaseExprRuntime.class, "identical", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod();
            b.endTruthiness();
            b.beginBlock();
            emitLoopBranchExpr(ce.thens.get(k), b, lt);
            b.endBlock();
            b.beginBlock();
            emitLoopBranchExpr(ce.defaultExpr, b, lt);
            b.endBlock();
            b.endIfThenElse();
        } else {
            b.beginStoreLocal(lt.resultLocal());
            b.emitLoadNull();
            b.endStoreLocal();
        }
    }

    private void convertCaseExpr(CaseExpr ce, CloffleBytecodeRootNodeGen.Builder b) {
        b.beginBlock();
        BytecodeLocal discLocal = createTrackedLocal(b);
        b.beginStoreLocal(discLocal);
        convert(ce.expr, b);
        b.endStoreLocal();

        BytecodeLocal keyLocal = createTrackedLocal(b);
        b.beginStoreLocal(keyLocal);
        if (ce.testType.equals(CASE_INT)) {
            b.beginStaticMethod(CaseExprRuntime.class, "intDispatchKey", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            b.emitLoadConstant(ce.shift);
            b.emitLoadConstant(ce.mask);
            b.endStaticMethod();
        } else {
            b.beginStaticMethod(CaseExprRuntime.class, "hashDispatchKey", Boolean.FALSE);
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
        b.beginStaticMethod(CaseExprRuntime.class, "intEq", Boolean.FALSE);
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
            b.beginStaticMethod(clojure.lang.Util.class, "equiv", Boolean.FALSE);
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
            b.beginStaticMethod(CaseExprRuntime.class, "identical", Boolean.FALSE);
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
