package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentList;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;
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

    /**
     * Var metadata declaring which bytecode operation may replace a call to that Var, keyed by arity:
     * {@code ^{:cloffle/op {3 :KeywordAssoc}}}. Core-only and undocumented — user code opting in would
     * widen the set of non-redefinable-looking Vars, which {@code COMPATIBILITY_RISK_AUDIT.md} §8 warns
     * against. The lowering itself stays honest about redefinition: every generated operation guards on
     * {@link Var#getRootAssumption()}, which upstream {@code :inline} does not.
     */
    private static final Keyword CLOFFLE_OP = Keyword.intern("cloffle", "op");
    private static final Keyword OP_KEYWORD_ASSOC = Keyword.intern("KeywordAssoc");
    private static final Keyword OP_KEYWORD_LOOKUP = Keyword.intern("KeywordLookup");
    private static final Keyword OP_KEYWORD_LOOKUP_DEFAULT = Keyword.intern("KeywordLookupDefault");
    private static final Keyword OP_KEYWORD_DISSOC = Keyword.intern("KeywordDissoc");
    private static final Keyword OP_NUMBERS_ADD = Keyword.intern("NumbersAdd");
    private static final Keyword OP_NUMBERS_MULTIPLY = Keyword.intern("NumbersMultiply");
    private static final Keyword OP_NUMBERS_MINUS = Keyword.intern("NumbersMinus");
    private static final Keyword OP_NUMBERS_DIVIDE = Keyword.intern("NumbersDivide");
    private static final Keyword OP_NUMBERS_LT = Keyword.intern("NumbersLt");
    private static final Keyword OP_NUMBERS_LTE = Keyword.intern("NumbersLte");
    private static final Keyword OP_NUMBERS_GT = Keyword.intern("NumbersGt");
    private static final Keyword OP_NUMBERS_GTE = Keyword.intern("NumbersGte");
    private static final Keyword OP_NUMBERS_EQUIV = Keyword.intern("NumbersEquiv");
    private static final Keyword OP_NUMBERS_INC = Keyword.intern("NumbersInc");
    private static final Keyword OP_NUMBERS_DEC = Keyword.intern("NumbersDec");
    private static final Keyword OP_NUMBERS_NEGATE = Keyword.intern("NumbersNegate");
    private static final Keyword OP_NUMBERS_NTH = Keyword.intern("NumbersNth");
    private static final Keyword OP_NUMBERS_COUNT = Keyword.intern("NumbersCount");
    private static final Keyword OP_RT_ASET = Keyword.intern("RtAset");
    private static final Keyword OP_RT_AGET = Keyword.intern("RtAget");

    /** The operation {@code var}'s {@code :cloffle/op} table names for this arity, or null. */
    private static Keyword loweringOp(Var var, int arity) {
        IPersistentMap meta = var.meta();
        if (meta == null) {
            return null;
        }
        Object table = meta.valAt(CLOFFLE_OP);
        if (!(table instanceof IPersistentMap ops)) {
            return null;
        }
        return ops.valAt(Long.valueOf(arity)) instanceof Keyword op ? op : null;
    }

    private static boolean uncheckedMathActive() {
        return RT.booleanCast(RT.UNCHECKED_MATH.deref());
    }

    private static boolean isKeywordKeyedOp(Keyword op) {
        return op == OP_KEYWORD_ASSOC || op == OP_KEYWORD_DISSOC
                || op == OP_KEYWORD_LOOKUP || op == OP_KEYWORD_LOOKUP_DEFAULT;
    }



    private final Clojure language;
    private final Source source;
    private final boolean clearDeadLocals;
    private final Map<LocalBinding, BytecodeLocal> localSlots = new HashMap<>();
    private final Map<LocalBinding, Integer> clearOnLastUse = new HashMap<>();

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
     * <b>Primitive {@code recur}:</b> loop/fn locals use built-in {@link BytecodeLocal}
     * {@code StoreLocal}/{@code LoadLocal}. When the stored value is a primitive {@code long}/
     * {@code double}/{@code int} (from {@code ConstLong}/typed static calls/unboxed params),
     * boxing elimination specializes the slot automatically — no direct {@code VirtualFrame} access.
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

    /**
     * @param clearDeadLocals drop bindings the body cannot read, so they neither pin objects on the
     *                        heap nor cost anything to maintain: {@code ClearLocal} for {@code let*}
     *                        bindings (see {@link #clearBindingsDeadInBody}), and the self reference
     *                        of a named {@code fn} whose name is never read (see
     *                        {@link #selfNameIsRead}). Both are invisible to the program and visible
     *                        to a debugger, which is why they share one switch. Callers that parse
     *                        for a Polyglot context pass
     *                        {@link net.javacrumbs.cloffle.CloffleContext#clearDeadLocals()};
     *                        host and build-time callers, which have no debugger to serve, pass
     *                        {@code true}. There is deliberately no default: the answer differs per
     *                        entry point and a new call site should have to state it.
     */
    public ExprToBytecode(Clojure language, Source source, boolean clearDeadLocals) {
        this.language = language;
        this.source = source;
        this.clearDeadLocals = clearDeadLocals;
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
            int rootLocals = ExprToBytecodeLocals.countExprLocals(rootExpr) * 4;
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
            b.beginRecordGuestNamespaceResult();
            try {
                convert(rootExpr, b);
            } finally {
                skipNextStatementTag = false;
                if (inhibitAllStatementTags) {
                    statementTagInhibitDepth--;
                }
            }
            b.endRecordGuestNamespaceResult();
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
            b.beginDefVar(de.initProvided, de.isDynamic, de.line,
                    symbolColumnForDef(de), uriForTrace());
            b.emitLoadConstant(de.var);
            if (de.initProvided) {
                convert(de.init, b);
            } else {
                b.emitLoadNull();
            }
            if (de.meta != null) {
                statementTagInhibitDepth++;
                try {
                    convert(de.meta, b);
                } finally {
                    statementTagInhibitDepth--;
                }
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

    /** Column of the defined symbol: opening {@code (} column plus {@code "(def "}. */
    private static int symbolColumnForDef(DefExpr de) {
        if (de.column < 1) {
            return 0;
        }
        // "(def " is 5 chars; fixture expects column 6 for `(def x 10)` with column 1 on `(`.
        return de.column + 5;
    }

    private String uriForTrace() {
        String uri = net.javacrumbs.cloffle.trace.CloffleTracer.uriOf(source);
        return uri != null ? uri : "";
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
     * The pool size is determined by {@link ExprToBytecodeLocals#countLocalsNeeded} (which walks the fn's AST
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

    BytecodeLocal createTrackedLocal(CloffleBytecodeRootNodeGen.Builder b) {
        // Allocate fresh locals. (Compile-time pooling is unsafe once boxingEliminationTypes
        // is enabled: sticky long/double tags on reused slots break later Object stores.)
        BytecodeLocal local = b.createLocal();
        localDepth.put(local, rootDepth);
        return local;
    }

    /**
     * Clears the locals of {@code let*} bindings that {@code body} cannot read, right before the
     * body is emitted.
     * <p>
     * A value left in a frame slot stays part of the interpreter state that
     * {@code LoopExplosionKind.MERGE_EXPLODE} compares when it merges two dispatch-loop iterations,
     * so any branch merge in the body turns such a value into a loop phi and forces partial escape
     * analysis to materialize it. Destructuring is the common case: {@code (let [[a b] v] …)}
     * expands to a temp that only the {@code nth} inits read, yet the temp outlives them and pins
     * the vector on the heap.
     * <p>
     * The trade-off is that a debugger stopped in the body reads those bindings as nil, which is why
     * {@link net.javacrumbs.cloffle.Clojure#CLEAR_DEAD_LOCALS} turns this off for REPL and debugger
     * contexts.
     */
    private void clearBindingsDeadInBody(CloffleBytecodeRootNodeGen.Builder b, Expr body,
                                         java.util.List<LocalBinding> bindings,
                                         java.util.List<BytecodeLocal> locals) {
        if (!clearDeadLocals) {
            return;
        }
        java.util.Set<LocalBinding> read = new java.util.HashSet<>();
        if (!ExprToBytecodeLocals.collectReadBindings(body, read)) {
            return;
        }
        for (int i = 0; i < bindings.size(); i++) {
            LocalBinding lb = bindings.get(i);
            if (lb.canBeCleared && !read.contains(lb)) {
                b.emitClearLocal(locals.get(i));
            }
        }
    }

    /**
     * Activate last-use clearing for this non-loop {@code let*}'s bindings, including uses in later
     * initializers (destructuring temps). Captured bindings stay live for {@code LoadLocalMaterialized}.
     */
    private void withLastUseCandidates(LetExpr le, Runnable emit) {
        if (!clearDeadLocals || le.isLoop) {
            emit.run();
            return;
        }
        java.util.Set<LocalBinding> captured = new java.util.HashSet<>();
        if (!ExprToBytecodeLocals.collectCapturedBindings(le, captured)) {
            emit.run();
            return;
        }
        java.util.List<LocalBinding> added = new java.util.ArrayList<>();
        for (int i = 0; i < le.bindingInits.count(); i++) {
            LocalBinding binding = ((BindingInit) le.bindingInits.nth(i)).binding();
            if (binding.isArg || !binding.canBeCleared || binding.getPrimitiveType() != null) {
                continue;
            }
            if (captured.contains(binding)) {
                continue;
            }
            if (clearOnLastUse.putIfAbsent(binding, rootDepth) == null) {
                added.add(binding);
            }
        }
        try {
            emit.run();
        } finally {
            for (LocalBinding binding : added) {
                clearOnLastUse.remove(binding);
            }
        }
    }

    private boolean shouldLoadAndClear(LocalBindingExpr lbe) {
        return clearDeadLocals
                && lbe.shouldClear
                && lbe.b.canBeCleared
                && lbe.b.getPrimitiveType() == null
                && java.util.Objects.equals(clearOnLastUse.get(lbe.b), rootDepth);
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
    void convertCalleeOrArgForInvoke(Expr expr, CloffleBytecodeRootNodeGen.Builder b) {
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
                ExprToBytecodeLiterals.emitConstantValue(ce.v, b);
            }
        } else if (expr instanceof ConstantVectorExpr cve) {
            if (ExprToBytecodeLiterals.isSmallConstantVector(cve)) {
                emitWithExprSection(b, cve, () -> {
                    ExprToBytecodeLiterals.emitCreateVector(cve.args, b, this::convert);
                });
            } else {
                ExprToBytecodeLiterals.emitConstantValue(cve.val, b);
            }
        } else if (expr instanceof ConstantMapExpr cme) {
            if (ExprToBytecodeLiterals.isSmallKeywordMap(cme)) {
                emitWithExprSection(b, cme, () -> {
                    ExprToBytecodeLiterals.emitCreateMap(cme, b, this::convert);
                });
            } else {
                ExprToBytecodeLiterals.emitConstantValue(cme.val, b);
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
            } else if (ee.coll instanceof clojure.lang.IPersistentMap) {
                b.emitCreateMap0();
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
            emitNumberExpr(ne, b);
        } else if (expr instanceof LocalBindingExpr lbe) {
            int[] loc = ExprSourceSpans.localBindingReferenceLineColumn(source, lbe)
                    .orElseGet(() -> ExprSourceSpans.extractLineColumn(lbe));
            emitWithLineColumnSection(b, loc[0], loc[1], BC_TAG_READ_VAR, () -> {
                BytecodeLocal local = localSlots.get(lbe.b);
                if (local != null) {
                    // Hinted locals stay Object in the frame (EnsureObject / no BE yet) but must
                    // re-enter the operand stack as long/double/int for StaticMethod specializations.
                    emitUnboxIfPrimitive(b, lbe.b.getPrimitiveType(), () -> {
                        try {
                            if (shouldLoadAndClear(lbe)) {
                                b.emitLoadAndClearLocal(local);
                            } else {
                                b.emitLoadLocal(local);
                            }
                        } catch (IllegalArgumentException e) {
                            emitOuterLocalLoad(b, local);
                        }
                    });
                } else {
                    if (lbe.b.isArg && currentFnMethod != null) {
                        int reqCount = currentFnMethod.reqParms().count();
                        boolean emitted = false;
                        for (int i = 0; i < reqCount; i++) {
                            if (currentFnMethod.reqParms().nth(i) == lbe.b) {
                                final int argIndex = i + 1; // +1: captured frame is arg 0
                                emitUnboxIfPrimitive(b, lbe.b.getPrimitiveType(), () -> b.emitLoadArgument(argIndex));
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
                b.emitReadVarConst(ve.var);
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
                        b.beginEnsureObject();
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
                        b.endEnsureObject();
                        b.endStoreLocal();

                        localSlots.put(bi.binding(), local);
                        letLocals.add(local);
                    }

                    if (le.isLoop) {
                        emitRecurWhileBody(b, letLocals, le.body);
                    } else {
                        clearBindingsDeadInBody(b, le.body, letBindingKeys, letLocals);
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
                withLastUseCandidates(le, letBody);
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
                        storeLocalEnsured(b, local, () -> convert(bi.init(), b));
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
                ExprToBytecodeLiterals.emitCreateList(le.args, b, this::convert);
            });
        } else if (expr instanceof VectorExpr ve) {
            emitWithExprSection(b, ve, () -> {
                ExprToBytecodeLiterals.emitCreateVector(ve.args, b, this::convert);
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
                ExprToBytecodeLiterals.emitCreateMap(me, b, this::convert);
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
            if (lt != null && ExprToBytecodeLocals.containsRecur(ie)) {
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
            emitWithExprSection(b, sme, BC_TAG_CALL, () -> {
                Object resolvedMethod = sme.method != null ? sme.method : Boolean.FALSE;
                emitStaticMethod(b, sme.c, sme.methodName, resolvedMethod, sme.args);
            });
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
                if (ne.c == clojure.lang.LazySeq.class && ne.args.count() == 1) {
                    b.beginNewLazySeq();
                    convert((Expr) ne.args.nth(0), b);
                    b.endNewLazySeq();
                } else {
                    b.beginNewObject(ne.c);
                    for (int i = 0; i < ne.args.count(); i++) {
                        convert((Expr) ne.args.nth(i), b);
                    }
                    b.endNewObject();
                }
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
            emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
                if (!sie.var.isDynamic()) {
                    ExprToBytecodeInvoke.emitInvokeVar(sie.var, sie.args, b, arg -> convert(arg, b));
                } else {
                    ExprToBytecodeInvoke.emitInvoke(
                            () -> {
                                b.beginReadVar();
                                b.emitLoadConstant(sie.var);
                                b.endReadVar();
                            },
                            sie.args,
                            b,
                            arg -> convert(arg, b)
                    );
                }
            });
        } else if (expr instanceof InvokeExpr ie) {
            if (ie.isProtocol && ie.onMethod != null && ie.fexpr instanceof VarExpr ve) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    b.beginInvokeProtocol(ve.var, ie.onMethod);
                    for (int i = 0; i < ie.args.count(); i++) {
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(i), b);
                    }
                    b.endInvokeProtocol();
                });
            } else if (ie.fexpr instanceof VarExpr ve && !ve.var.isDynamic()
                    && loweringOp(ve.var, ie.args.count()) != null
                    && (!isKeywordKeyedOp(loweringOp(ve.var, ie.args.count()))
                        || (ie.args.count() >= 2 && ie.args.nth(1) instanceof KeywordExpr))) {
                Keyword op = loweringOp(ve.var, ie.args.count());
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    if (op == OP_KEYWORD_ASSOC) {
                        KeywordExpr keyExpr = (KeywordExpr) ie.args.nth(1);
                        b.beginKeywordAssoc(ve.var, keyExpr.k);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(2), b);
                        b.endKeywordAssoc();
                    } else if (op == OP_KEYWORD_DISSOC) {
                        KeywordExpr keyExpr = (KeywordExpr) ie.args.nth(1);
                        b.beginKeywordDissoc(ve.var, keyExpr.k);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        b.endKeywordDissoc();
                    } else if (op == OP_KEYWORD_LOOKUP) {
                        KeywordExpr keyExpr = (KeywordExpr) ie.args.nth(1);
                        b.beginKeywordLookup(keyExpr.k);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        b.endKeywordLookup();
                    } else if (op == OP_KEYWORD_LOOKUP_DEFAULT) {
                        KeywordExpr keyExpr = (KeywordExpr) ie.args.nth(1);
                        b.beginKeywordLookupDefault(keyExpr.k);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(2), b);
                        b.endKeywordLookupDefault();
                    } else if (op == OP_NUMBERS_ADD) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedAdd(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersUncheckedAdd();
                        } else {
                            b.beginNumbersAdd(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersAdd();
                        }
                    } else if (op == OP_NUMBERS_MULTIPLY) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedMultiply(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersUncheckedMultiply();
                        } else {
                            b.beginNumbersMultiply(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersMultiply();
                        }
                    } else if (op == OP_NUMBERS_MINUS && ie.args.count() == 2) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedMinus(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersUncheckedMinus();
                        } else {
                            b.beginNumbersMinus(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                            b.endNumbersMinus();
                        }
                    } else if (op == OP_NUMBERS_NEGATE || (op == OP_NUMBERS_MINUS && ie.args.count() == 1)) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedNegate(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersUncheckedNegate();
                        } else {
                            b.beginNumbersNegate(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersNegate();
                        }
                    } else if (op == OP_NUMBERS_DIVIDE) {
                        b.beginNumbersDivide(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersDivide();
                    } else if (op == OP_NUMBERS_LT) {
                        b.beginNumbersLt(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersLt();
                    } else if (op == OP_NUMBERS_LTE) {
                        b.beginNumbersLte(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersLte();
                    } else if (op == OP_NUMBERS_GT) {
                        b.beginNumbersGt(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersGt();
                    } else if (op == OP_NUMBERS_GTE) {
                        b.beginNumbersGte(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersGte();
                    } else if (op == OP_NUMBERS_EQUIV) {
                        b.beginNumbersEquiv(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersEquiv();
                    } else if (op == OP_NUMBERS_INC) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedInc(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersUncheckedInc();
                        } else {
                            b.beginNumbersInc(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersInc();
                        }
                    } else if (op == OP_NUMBERS_DEC) {
                        if (uncheckedMathActive()) {
                            b.beginNumbersUncheckedDec(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersUncheckedDec();
                        } else {
                            b.beginNumbersDec(ve.var);
                            convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                            b.endNumbersDec();
                        }
                    } else if (op == OP_NUMBERS_NTH) {
                        b.beginNumbersNth(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endNumbersNth();
                    } else if (op == OP_NUMBERS_COUNT) {
                        b.beginNumbersCount(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        b.endNumbersCount();
                    } else if (op == OP_RT_ASET) {
                        b.beginRtAset(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(2), b);
                        b.endRtAset();
                    } else if (op == OP_RT_AGET) {
                        b.beginRtAget(ve.var);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(0), b);
                        convertCalleeOrArgForInvoke((Expr) ie.args.nth(1), b);
                        b.endRtAget();
                    } else {
                        throw new IllegalStateException("Unknown :cloffle/op " + op + " on " + ve.var);
                    }
                });
            } else if (ie.fexpr instanceof VarExpr ve && !ve.var.isDynamic()) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    ExprToBytecodeInvoke.emitInvokeVar(ve.var, ie.args, b, arg -> convertCalleeOrArgForInvoke(arg, b));
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
                    ExprToBytecodeInvoke.emitInvoke(
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
        } else if (expr instanceof CaseExpr ce && ExprToBytecodeLocals.containsRecur(ce)) {
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
            withLastUseCandidates(le, () -> {
                b.beginBlock();
                java.util.List<LocalBinding> letBindingKeys = new java.util.ArrayList<>(numBindings);
                java.util.List<BytecodeLocal> letLocals = new java.util.ArrayList<>(numBindings);
                for (int i = 0; i < numBindings; i++) {
                    BindingInit bi = (BindingInit) le.bindingInits.nth(i);
                    letBindingKeys.add(bi.binding());
                    BytecodeLocal local = createTrackedLocal(b);
                    registerSlotDebugName(local, bi.binding());
                    b.beginStoreLocal(local);
                    b.beginEnsureObject();
                    Class<?> fiClass = maybeFIBindingClass(bi.binding());
                    if (fiClass != null) {
                        b.beginAdaptFI(fiClass);
                    }
                    convert(bi.init(), b);
                    if (fiClass != null) {
                        b.endAdaptFI();
                    }
                    b.endEnsureObject();
                    b.endStoreLocal();
                    localSlots.put(bi.binding(), local);
                    letLocals.add(local);
                }
                clearBindingsDeadInBody(b, le.body, letBindingKeys, letLocals);
                convertLoopBody(le.body, b);
                b.endBlock();
                for (LocalBinding lb : letBindingKeys) {
                    localSlots.remove(lb);
                }
            });
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
        } else if (branch instanceof CaseExpr ce && ExprToBytecodeLocals.containsRecur(ce)) {
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
                b.beginEnsureObject();
                convert((Expr) re.args.nth(i), b);
                b.endEnsureObject();
                b.endStoreLocal();
            }
            for (int i = 0; i < n; i++) {
                b.beginStoreLocal(lt.locals().get(i));
                b.emitLoadLocal(temps[i]);
                b.endStoreLocal();
            }
        } else if (n == 1) {
            storeLocalEnsured(b, lt.locals().get(0), () -> convert((Expr) re.args.nth(0), b));
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

    /**
     * Whether any arity of {@code fnExpr} can read the fn's own name.
     *
     * <p>Recursion through the name, {@code (fn fact [n] ... (fact ...))}, is a read and keeps the
     * self reference. So is a name captured by an inner {@code fn*} or {@code reify}, which
     * {@link ExprToBytecodeLocals#collectReadBindings} covers through {@code closes()}.
     *
     * <p>An unrecognized expression type answers {@code true}: the analysis is then incomplete, and
     * dropping a self reference that is in fact read would break the fn rather than slow it down.
     */
    private static boolean selfNameIsRead(FnExpr fnExpr,
                                          java.util.List<clojure.lang.Compiler.LocalBinding> selfBindings) {
        java.util.Set<clojure.lang.Compiler.LocalBinding> read = new java.util.HashSet<>();
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(fnExpr.methods()); s != null; s = s.next()) {
            clojure.lang.Compiler.FnMethod fm = (clojure.lang.Compiler.FnMethod) s.first();
            if (!ExprToBytecodeLocals.collectReadBindings(fm.body(), read)) {
                return true;
            }
        }
        for (clojure.lang.Compiler.LocalBinding lb : selfBindings) {
            if (read.contains(lb)) {
                return true;
            }
        }
        return false;
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

        // A name alone does not need a self reference. Compiler.FnMethod.parse registers a
        // LocalBinding for the fn's own name unconditionally, so without this check every
        // named fn takes the capturing-closure path below: it is created with a materialized
        // parent frame instead of null, and its root then begins with a LoadLocalMaterialized
        // + StoreLocal that re-reads the closure out of that frame on every single call. For
        // a fn that never mentions its own name that is pure overhead, and it is not small --
        // the tuple-destructure snippet measures 176M ops/s anonymous against 80M self-named.
        //
        // Gated on clearDeadLocals for the same reason clearBindingsDeadInBody is: the slot
        // carries a debug name, so dropping it takes the fn's own name out of debugger scopes.
        // A context that asked to keep unreadable bindings visible keeps this one too, and
        // pays the capture cost.
        if (thisBinding != null && clearDeadLocals && !selfNameIsRead(fnExpr, allThisBindings)) {
            thisBinding = null;
            allThisBindings.clear();
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
        int neededCount = ExprToBytecodeLocals.countLocalsNeeded(fnExpr);
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

        IPersistentMap closureMeta = buildFnArglists(fnExpr);

        boolean capturesOuterLocals = (fnExpr.closes() != null && fnExpr.closes().count() > 0);
        if (thisLocal != null) {
            // Write closure to thisLocal on the live frame before materializing
            // the captured environment; otherwise emitClosureCopies reads a stale snapshot (uninit self).
            b.beginBlock();
            b.beginStoreLocal(thisLocal);
            b.beginCreateClosurePendingCapture(closureReqArity, closureVariadic, closureMeta);
            b.emitLoadConstant(innerNode);
            b.endCreateClosurePendingCapture();
            b.endStoreLocal();
            b.beginFinalizeClosureCapture();
            b.emitLoadLocal(thisLocal);
            b.emitGetOuterFrame();
            b.endFinalizeClosureCapture();
            b.endBlock();
        } else if (capturesOuterLocals) {
            b.beginCreateClosure(closureReqArity, closureVariadic, closureMeta);
            b.emitLoadConstant(innerNode);
            b.emitGetOuterFrame();
            b.endCreateClosure();
        } else {
            b.beginCreateClosure(closureReqArity, closureVariadic, closureMeta);
            b.emitLoadConstant(innerNode);
            b.emitLoadNull();
            b.endCreateClosure();
        }
    }

    private static IPersistentMap buildFnArglists(FnExpr fnExpr) {
        clojure.lang.IPersistentCollection methods = fnExpr.methods();
        int methodCount = methods != null ? methods.count() : 0;
        java.util.List<FnMethod> methodList = new java.util.ArrayList<>(methodCount);
        for (clojure.lang.ISeq ms = clojure.lang.RT.seq(methods); ms != null; ms = ms.next()) {
            methodList.add((FnMethod) ms.first());
        }
        methodList.sort((m1, m2) -> {
            boolean v1 = m1.restParm() != null;
            boolean v2 = m2.restParm() != null;
            if (v1 && !v2) return 1;
            if (!v1 && v2) return -1;
            return Integer.compare(m1.reqParms().count(), m2.reqParms().count());
        });

        java.util.List<IPersistentVector> arglists = new java.util.ArrayList<>(methodList.size());
        for (FnMethod m : methodList) {
            int reqCount = m.reqParms().count();
            boolean variadic = m.restParm() != null;
            Object[] pvec = new Object[reqCount + (variadic ? 2 : 0)];
            for (int i = 0; i < reqCount; i++) {
                LocalBinding lb = (LocalBinding) m.reqParms().nth(i);
                pvec[i] = lb.sym;
            }
            if (variadic) {
                pvec[reqCount] = Symbol.intern("&");
                pvec[reqCount + 1] = m.restParm().sym;
            }
            arglists.add(RT.vector(pvec));
        }
        return (IPersistentMap) RT.map(
                Keyword.intern(null, "arglists"),
                PersistentList.create(arglists));
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
                    final int argIndex = i + 1;
                    emitUnboxIfPrimitive(b, lb.getPrimitiveType(), () -> b.emitLoadArgument(argIndex));
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
                b.beginStaticMethod3(CaseExprRuntime.class, "intDispatchKey", Boolean.FALSE);
                b.emitLoadLocal(discLocal);
                b.emitLoadConstant(ce.shift);
                b.emitLoadConstant(ce.mask);
                b.endStaticMethod3();
            } else {
                b.beginStaticMethod3(CaseExprRuntime.class, "hashDispatchKey", Boolean.FALSE);
                b.emitLoadLocal(discLocal);
                b.emitLoadConstant(ce.shift);
                b.emitLoadConstant(ce.mask);
                b.endStaticMethod3();
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
        b.beginStaticMethod2(CaseExprRuntime.class, "intEq", Boolean.FALSE);
        b.emitLoadLocal(keyLocal);
        b.emitLoadConstant(k);
        b.endStaticMethod2();
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
            b.beginStaticMethod2(clojure.lang.Util.class, "equiv", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod2();
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
            b.beginStaticMethod2(CaseExprRuntime.class, "identical", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod2();
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
            b.beginStaticMethod3(CaseExprRuntime.class, "intDispatchKey", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            b.emitLoadConstant(ce.shift);
            b.emitLoadConstant(ce.mask);
            b.endStaticMethod3();
        } else {
            b.beginStaticMethod3(CaseExprRuntime.class, "hashDispatchKey", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            b.emitLoadConstant(ce.shift);
            b.emitLoadConstant(ce.mask);
            b.endStaticMethod3();
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
        b.beginStaticMethod2(CaseExprRuntime.class, "intEq", Boolean.FALSE);
        b.emitLoadLocal(keyLocal);
        b.emitLoadConstant(k);
        b.endStaticMethod2();
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
            b.beginStaticMethod2(clojure.lang.Util.class, "equiv", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod2();
            b.endTruthiness();
            convert(ce.thens.get(k), b);
            convert(ce.defaultExpr, b);
            b.endConditional();
        } else if (ce.testType.equals(CASE_HASH_IDENTITY)) {
            b.beginConditional();
            b.beginTruthiness();
            b.beginStaticMethod2(CaseExprRuntime.class, "identical", Boolean.FALSE);
            b.emitLoadLocal(discLocal);
            convert(ce.tests.get(k), b);
            b.endStaticMethod2();
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


    private void emitNumberExpr(NumberExpr ne, CloffleBytecodeRootNodeGen.Builder b) {
        Class<?> jc = ne.getJavaClass();
        Number n = ne.n;
        // Const* keeps primitives on the operand stack for StaticMethod specializations.
        // EnsureObject before any StoreLocal is applied at store sites (let/recur); numbers
        // used only as call args stay unboxed through the stack.
        if (jc == long.class) {
            b.emitConstLong(n.longValue());
        } else if (jc == double.class) {
            b.emitConstDouble(n.doubleValue());
        } else {
            b.emitLoadConstant(ne.val());
        }
    }

    private void storeLocalEnsured(CloffleBytecodeRootNodeGen.Builder b, BytecodeLocal local, Runnable value) {
        b.beginStoreLocal(local);
        b.beginEnsureObject();
        value.run();
        b.endEnsureObject();
        b.endStoreLocal();
    }

    /** Wrap {@code valueEmitter} in UnboxLong/Double/Int when {@code prim} is a BE primitive. */
    private static void emitUnboxIfPrimitive(
            CloffleBytecodeRootNodeGen.Builder b, Class<?> prim, Runnable valueEmitter) {
        if (prim == long.class) {
            b.beginUnboxLong();
            valueEmitter.run();
            b.endUnboxLong();
        } else if (prim == double.class) {
            b.beginUnboxDouble();
            valueEmitter.run();
            b.endUnboxDouble();
        } else if (prim == int.class) {
            b.beginUnboxInt();
            valueEmitter.run();
            b.endUnboxInt();
        } else {
            valueEmitter.run();
        }
    }

    private void emitStaticMethod(
            CloffleBytecodeRootNodeGen.Builder b,
            Class<?> targetClass,
            String methodName,
            Object resolvedMethod,
            IPersistentVector args) {
        int count = args != null ? args.count() : 0;
        if (count == 0) {
            b.emitStaticMethod0(targetClass, methodName, resolvedMethod);
        } else if (count == 1) {
            b.beginStaticMethod1(targetClass, methodName, resolvedMethod);
            convert((Expr) args.nth(0), b);
            b.endStaticMethod1();
        } else if (count == 2) {
            b.beginStaticMethod2(targetClass, methodName, resolvedMethod);
            convert((Expr) args.nth(0), b);
            convert((Expr) args.nth(1), b);
            b.endStaticMethod2();
        } else if (count == 3) {
            b.beginStaticMethod3(targetClass, methodName, resolvedMethod);
            convert((Expr) args.nth(0), b);
            convert((Expr) args.nth(1), b);
            convert((Expr) args.nth(2), b);
            b.endStaticMethod3();
        } else if (count == 4) {
            b.beginStaticMethod4(targetClass, methodName, resolvedMethod);
            convert((Expr) args.nth(0), b);
            convert((Expr) args.nth(1), b);
            convert((Expr) args.nth(2), b);
            convert((Expr) args.nth(3), b);
            b.endStaticMethod4();
        } else {
            b.beginStaticMethodN(targetClass, methodName, resolvedMethod);
            for (int i = 0; i < count; i++) {
                convert((Expr) args.nth(i), b);
            }
            b.endStaticMethodN();
        }
    }
}
