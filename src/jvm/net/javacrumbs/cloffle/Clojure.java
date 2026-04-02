/**
 * Copyright 2009-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.cloffle;

import clojure.lang.Compiler;
import clojure.lang.Compiler.C;
import clojure.lang.IMeta;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.ast.ExprSourceSpans;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.PolyglotNilSafeRootNode;
import net.javacrumbs.cloffle.nodes.SequentialFormNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.NilNode;
import net.javacrumbs.cloffle.nodes.value.ObjectNode;

import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Language is registered via {@link CloffleLanguageProvider} (ServiceLoader) only.
 * Do not add @TruffleLanguage.Registration here—it would duplicate the "cloffle" id
 * and cause "Duplicate language id cloffle" when both annotation and provider are present.
 */
@ProvidedTags({
    StandardTags.StatementTag.class,
    StandardTags.ExpressionTag.class,
    StandardTags.CallTag.class,
    StandardTags.RootBodyTag.class,
    StandardTags.RootTag.class,
    StandardTags.ReadVariableTag.class,
    StandardTags.WriteVariableTag.class
})
public class Clojure extends TruffleLanguage<CloffleContext> {

    private static final Object EOF_SENTINEL = new Object();
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    @Override
    protected CloffleContext createContext(Env env) {
        RT.init();
        CloffleContext ctx = new CloffleContext();
        ctx.setLanguage(this);
        return ctx;
    }

    @Override
    protected void initializeThread(CloffleContext context, Thread thread) {
        ensureThreadBindings();
    }

    @Override
    protected void finalizeThread(CloffleContext context, Thread thread) {
        try {
            Var.popThreadBindings();
        } catch (IllegalStateException ex) {
            if ("Pop without matching push".equals(ex.getMessage())) {
                throw new IllegalStateException(
                        "Cloffle: Var thread binding stack imbalance on thread \""
                                + Thread.currentThread().getName()
                                + "\". Each thread that evaluates Clojure must enter through the Truffle language "
                                + "context (so initializeThread runs before finalizeThread). Do not use the same "
                                + "Polyglot Context from threads that were never initialized for this language, "
                                + "and avoid closing the Context from a different thread than the one currently "
                                + "executing guest code unless all guest threads have finished.",
                        ex);
            }
            throw ex;
        }
    }

    /**
     * Push the same default dynamic var bindings as {@link #initializeThread(CloffleContext, Thread)}
     * ({@code *ns*}, {@code *warn-on-reflection*}, …). Pair with {@link Var#popThreadBindings()} in {@code finally}
     * when evaluating outside Truffle’s {@code finalizeThread} (e.g. JUnit bytecode DSL helpers).
     *
     * @see #initializeThread(CloffleContext, Thread)
     */
    public static void pushEvalThreadBindings() {
        RT.pushThreadBindingsForEval();
    }

    /**
     * Push thread-local bindings for dynamic vars that Clojure's runtime
     * expects to be thread-bound. This mimics what RT.doInit() and
     * Compiler.load() do before evaluating any Clojure code.
     *
     * Without these, calls like (in-ns 'foo) fail because CURRENT_NS.set()
     * requires a thread-local binding (Var.set refuses to modify root bindings).
     */
    private static void ensureThreadBindings() {
        RT.pushThreadBindingsForEval();
    }

    public static CloffleContext getContext() {
        return getCurrentContext(Clojure.class);
    }

    @Override
    protected Object getScope(CloffleContext context) {
        return new net.javacrumbs.cloffle.nodes.ClojureTopScope();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws IOException {
        Source truffleSource = request.getSource();
        String sourceText = truffleSource.getCharacters().toString();
        clojure.lang.LineNumberingPushbackReader reader =
            new clojure.lang.LineNumberingPushbackReader(new StringReader(sourceText));

        List<CallTarget> forms = new ArrayList<>();

        pushCompilerBindings(truffleSource.getName());
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
        try {
            while (true) {
                Object form;
                try {
                    form = clojure.lang.LispReader.read(reader, false, EOF_SENTINEL, false);
                } catch (clojure.lang.LispReader.ReaderException e) {
                    if (e.getCause() instanceof RuntimeException re
                            && re.getMessage() != null
                            && re.getMessage().startsWith("Unmatched delimiter")
                            && !forms.isEmpty()) {
                        break;
                    }
                    throw makeReaderException(e, truffleSource);
                }
                if (form == EOF_SENTINEL) {
                    break;
                }
                try {
                    collectForm(form, truffleSource, forms);
                } catch (net.javacrumbs.cloffle.nodes.ClojureParseError pe) {
                    throw pe;
                } catch (Exception e) {
                    throw makeAnalyzerException(e, truffleSource, reader);
                }
            }
        } finally {
            Var.popThreadBindings();
            Thread.currentThread().setContextClassLoader(oldLoader);
        }

        if (forms.isEmpty()) {
            ExprToNode converter = new ExprToNode(this, truffleSource);
            ClojureNode node = new NilNode();
            ClojureRootNode rootNode = ClojureRootNode.create(node, converter.buildFrameDescriptor(), this);
            rootNode.setSourceSection(truffleSource.createSection(0, sourceText.length()));
            return rootNode.getCallTarget();
        }

        if (forms.size() == 1) {
            return forms.get(0);
        }

        CallTarget[] targets = forms.toArray(new CallTarget[0]);
        ClojureNode seqNode = new SequentialFormNode(targets);
        ExprToNode wrapperConverter = new ExprToNode(this, truffleSource);
        ClojureRootNode rootNode = ClojureRootNode.create(seqNode, wrapperConverter.buildFrameDescriptor(), this);
        rootNode.setSourceSection(truffleSource.createSection(0, sourceText.length()));
        return rootNode.getCallTarget();
    }

    /**
     * Analyze a form and add its CallTarget to the form list.
     * For forms that need eager execution (defmacro, ns, import, etc.),
     * execute via Truffle immediately so side effects are visible to
     * subsequent forms during analysis.
     *
     * <p>{@code do} blocks are split into individual subforms so that
     * a defmacro takes effect before later forms in the same block.
     */
    private void collectForm(Object form, Source source, List<CallTarget> forms) {
        int formLine = extractFormLine(form, 0);
        int formCol = extractFormColumn(form, 0);
        if (formLine > 0 || formCol > 0) {
            Var.pushThreadBindings(RT.mapUniqueKeys(
                    Compiler.LINE, formLine > 0 ? formLine : Compiler.LINE.deref(),
                    Compiler.COLUMN, formCol > 0 ? formCol : Compiler.COLUMN.deref()));
        }
        try {
            collectFormInner(form, source, forms);
        } finally {
            if (formLine > 0 || formCol > 0) {
                Var.popThreadBindings();
            }
        }
    }

    private void collectFormInner(Object form, Source source, List<CallTarget> forms) {
        if (needsEagerExec(form)) {
            Object result = truffleEval(form, source);
            ClojureNode node = new ObjectNode(result);
            ClojureRootNode rootNode = ClojureRootNode.create(node, new FrameDescriptor(), this);
            if (source != null) {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
            }
            forms.add(rootNode.getCallTarget());
            return;
        }

        Object expanded = Compiler.macroexpand(form);
        if (expanded instanceof ISeq seq && isDoSym(seq.first())) {
            for (ISeq s = seq.next(); s != null; s = s.next()) {
                collectForm(s.first(), source, forms);
            }
            return;
        }

        expanded = transferLineColumnMeta(form, expanded);

        Compiler.Expr expr = Compiler.analyze(C.EVAL, expanded);

        if (CloffleCompiler.useBytecodeExecution()) {
            ExprToBytecode converter = new ExprToBytecode(this, source);
            String name = "parse";
            if (expanded instanceof ISeq seq && seq.first() instanceof Symbol sym) {
                name = sym.getName();
            }
            BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, name);
            CloffleBytecodeRootNode inner = nodes.getNode(0);
            PolyglotNilSafeRootNode wrapped =
                    new PolyglotNilSafeRootNode(this, inner.getFrameDescriptor(), inner.getCallTarget());
            SourceSection formSection = bytecodeRootFormSourceSection(source, expr);
            if (formSection != null && formSection.isAvailable()) {
                wrapped.setSourceSection(formSection);
            }
            forms.add(wrapped.getCallTarget());
        } else {
            ExprToNode converter = new ExprToNode(this, source);
            ClojureNode node = converter.convert(expr);
            ClojureRootNode rootNode = ClojureRootNode.create(node, converter.buildFrameDescriptor(), this);
            if (source != null) {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
                com.oracle.truffle.api.source.SourceSection formSection = node.getSourceSection();
                if (formSection != null && formSection.isAvailable()) {
                    rootNode.setSourceSection(formSection);
                }
            }
            forms.add(rootNode.getCallTarget());
        }
    }

    /**
     * Execute a form entirely through the Truffle pipeline:
     * macroexpand -> split do blocks -> analyze -> convert (AST or bytecode) -> call().
     * Mirrors CloffleCompiler.executeForm() to handle nested do blocks
     * from macro expansions (e.g., ns expands to a do block).
     */
    private Object truffleEval(Object form, Source source) {
        Object expanded = Compiler.macroexpand(form);
        if (expanded instanceof ISeq seq && isDoSym(seq.first())) {
            Object ret = null;
            for (ISeq s = seq.next(); s != null; s = s.next()) {
                Object subForm = s.first();
                int subLine = extractFormLine(subForm, 0);
                int subCol = extractFormColumn(subForm, 0);
                if (subLine > 0 || subCol > 0) {
                    Var.pushThreadBindings(RT.mapUniqueKeys(
                            Compiler.LINE, subLine > 0 ? subLine : Compiler.LINE.deref(),
                            Compiler.COLUMN, subCol > 0 ? subCol : Compiler.COLUMN.deref()));
                    try {
                        ret = truffleEval(subForm, source);
                    } finally {
                        Var.popThreadBindings();
                    }
                } else {
                    ret = truffleEval(subForm, source);
                }
            }
            return ret;
        }

        expanded = transferLineColumnMeta(form, expanded);

        Compiler.Expr expr = Compiler.analyze(C.EVAL, expanded);

        if (CloffleCompiler.useBytecodeExecution()) {
            String text = RT.printString(expanded);
            Source formSource = Source.newBuilder("cloffle", text,
                    source != null ? source.getName() : "NO_SOURCE").build();
            ExprToBytecode converter = new ExprToBytecode(this, formSource);
            String name = "eval";
            if (expanded instanceof ISeq seq && seq.first() instanceof Symbol sym) {
                name = sym.getName();
            }
            BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, name);
            return ClojureInterop.wrapForPolyglot(nodes.getNode(0).getCallTarget().call());
        }

        ExprToNode converter = new ExprToNode(this, source);
        ClojureNode node = converter.convert(expr);
        FrameDescriptor fd = converter.buildFrameDescriptor();
        ClojureRootNode root = ClojureRootNode.create(node, fd, this);
        if (source != null) {
            root.setSourceSection(source.createSection(0, source.getLength()));
            com.oracle.truffle.api.source.SourceSection formSection = node.getSourceSection();
            if (formSection != null && formSection.isAvailable()) {
                root.setSourceSection(formSection);
            }
        }
        if (expanded instanceof ISeq seq && seq.first() instanceof Symbol sym) {
            root.setName(sym.getName());
        }
        return root.getCallTarget().call();
    }

    private static final Set<String> EAGER_EVAL_FORMS = Set.of(
        "ns", "require", "use", "import", "refer",
        "defmacro", "definline", "in-ns",
        "defprotocol", "defmulti", "defmethod",
        "extend-protocol", "extend-type", "extend", "load"
    );

    private static boolean needsEagerExec(Object form) {
        if (!(form instanceof ISeq seq) || !(seq.first() instanceof Symbol sym)) {
            return false;
        }
        String ns = sym.getNamespace();
        if (ns != null && !"clojure.core".equals(ns)) {
            return false;
        }
        return EAGER_EVAL_FORMS.contains(sym.getName());
    }

    private static boolean isDoSym(Object obj) {
        return obj instanceof Symbol sym
                && "do".equals(sym.getName())
                && sym.getNamespace() == null;
    }

    private static void pushCompilerBindings(String sourceName) {
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        Var uncheckedMath = Var.find(Symbol.intern("clojure.core", "*unchecked-math*"));
        Var dataReaders = Var.find(Symbol.intern("clojure.core", "*data-readers*"));

        String srcPath = sourceName != null ? sourceName : "NO_SOURCE_PATH";
        String srcFile = sourceName != null ? sourceName : "NO_SOURCE_FILE";

        Var.pushThreadBindings(RT.mapUniqueKeys(
                Compiler.LOADER, RT.makeClassLoader(),
                Compiler.SOURCE_PATH, srcPath,
                Compiler.SOURCE, srcFile,
                Compiler.METHOD, null,
                Compiler.LOCAL_ENV, null,
                Compiler.LOOP_LOCALS, null,
                Compiler.NEXT_LOCAL_NUM, 0,
                RT.READEVAL, RT.T,
                RT.CURRENT_NS, RT.CURRENT_NS.deref(),
                Compiler.LINE, 1,
                Compiler.COLUMN, 1,
                Compiler.LINE_BEFORE, 1,
                Compiler.COLUMN_BEFORE, 1,
                Compiler.LINE_AFTER, 1,
                Compiler.COLUMN_AFTER, 1,
                Compiler.KEYWORD_CALLSITES, clojure.lang.PersistentVector.EMPTY,
                Compiler.PROTOCOL_CALLSITES, clojure.lang.PersistentVector.EMPTY,
                uncheckedMath, uncheckedMath.deref(),
                warnOnReflection, warnOnReflection.deref(),
                dataReaders, dataReaders.deref()
        ));
    }

    /**
     * Balanced s-expression span for the analyzed form on the Polyglot parse path. The inner
     * {@link CloffleBytecodeRootNode} keeps a full-source root section for bytecode tests; this
     * section is set on {@link PolyglotNilSafeRootNode} so guest stack frames can report the
     * current form (mirrors {@link ClojureRootNode#setSourceSection} narrowing for the AST path).
     */
    private static SourceSection bytecodeRootFormSourceSection(Source source, Compiler.Expr expr) {
        if (source == null || expr == null) {
            return null;
        }
        Compiler.Expr spanExpr = expr;
        if (expr instanceof Compiler.BodyExpr be && be.exprs().count() > 0) {
            spanExpr = (Compiler.Expr) be.exprs().nth(be.exprs().count() - 1);
        }
        int[] loc = ExprSourceSpans.extractLineColumn(spanExpr);
        if (loc[0] < 1 || loc[1] < 1) {
            return null;
        }
        return ExprSourceSpans.computeCharSpanFromLineColumn(source, loc[0], loc[1])
                .map(cs -> source.createSection(cs.start(), cs.length()))
                .orElse(null);
    }

    private static Object transferLineColumnMeta(Object originalForm, Object expanded) {
        if (originalForm instanceof IMeta origMeta && expanded instanceof IObj expandedObj) {
            IPersistentMap meta = origMeta.meta();
            if (meta != null && (meta.containsKey(LINE_KEY) || meta.containsKey(COLUMN_KEY))) {
                IPersistentMap eMeta = RT.meta(expanded);
                if (eMeta == null || !eMeta.containsKey(LINE_KEY)) {
                    IPersistentMap newMeta = eMeta != null ? eMeta : PersistentArrayMap.EMPTY;
                    Object line = meta.valAt(LINE_KEY);
                    Object col = meta.valAt(COLUMN_KEY);
                    if (line != null) newMeta = newMeta.assoc(LINE_KEY, line);
                    if (col != null) newMeta = newMeta.assoc(COLUMN_KEY, col);
                    return expandedObj.withMeta(newMeta);
                }
            }
        }
        return expanded;
    }

    private static int extractFormLine(Object form, int fallback) {
        if (form instanceof IMeta m) {
            IPersistentMap meta = m.meta();
            if (meta != null) {
                Object line = meta.valAt(LINE_KEY);
                if (line instanceof Number n && n.intValue() > 0) {
                    return n.intValue();
                }
            }
        }
        return fallback;
    }

    private static int extractFormColumn(Object form, int fallback) {
        if (form instanceof IMeta m) {
            IPersistentMap meta = m.meta();
            if (meta != null) {
                Object col = meta.valAt(COLUMN_KEY);
                if (col instanceof Number n && n.intValue() > 0) {
                    return n.intValue();
                }
            }
        }
        return fallback;
    }

    private static net.javacrumbs.cloffle.nodes.ClojureParseError makeReaderException(
            clojure.lang.LispReader.ReaderException e, Source source) {
        Throwable cause = e.getCause();
        String msg = "Reader error";
        if (cause != null && cause.getMessage() != null) {
            msg = cause.getMessage();
        } else if (e.getMessage() != null) {
            msg = e.getMessage();
        }

        int line = Math.max(1, e.line);
        int errorCol = Math.max(1, e.column);

        int startCol = 1;
        int length = Math.max(1, errorCol - startCol + 1);

        return new net.javacrumbs.cloffle.nodes.ClojureParseError(
                source, line, startCol, length, false, msg, cause != null ? cause : e);
    }

    private static net.javacrumbs.cloffle.nodes.ClojureParseError makeAnalyzerException(
            Exception e, Source source, clojure.lang.LineNumberingPushbackReader reader) {
        String msg = buildFullMessage(e);

        int line = -1;
        int column = 1;

        if (e instanceof Compiler.CompilerException ce) {
            IPersistentMap data = ce.getData();
            if (data != null) {
                Object ceLineObj = data.valAt(Compiler.CompilerException.ERR_LINE);
                Object ceColObj = data.valAt(Compiler.CompilerException.ERR_COLUMN);
                if (ceLineObj instanceof Number n && n.intValue() > 0) {
                    line = n.intValue();
                }
                if (ceColObj instanceof Number n && n.intValue() > 0) {
                    column = n.intValue();
                }
            }
        }

        if (line < 1) {
            line = Math.min(reader.getLineNumber(), source.getLineCount());
            line = Math.max(1, line);
            column = 1;
        }

        int length = 1;
        try {
            length = Math.max(1, source.getLineLength(line));
        } catch (Exception ignored) {}

        return new net.javacrumbs.cloffle.nodes.ClojureParseError(
                source, line, column, length, false, msg, e);
    }

    /**
     * Walk the cause chain and compose a message that includes the root cause,
     * avoiding duplicates. For macro expansion errors this surfaces the actual
     * failure message (e.g. "Divide by zero") alongside the compiler context.
     */
    private static String buildFullMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();

        StringBuilder sb = new StringBuilder(msg);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        seen.add(msg);

        Throwable current = e.getCause();
        int depth = 0;
        while (current != null && depth < 5) {
            String causeMsg = current.getMessage();
            if (causeMsg != null && !seen.contains(causeMsg) && !msg.contains(causeMsg)) {
                sb.append("\n").append(causeMsg);
                seen.add(causeMsg);
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

}
