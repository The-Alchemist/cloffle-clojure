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
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.SequentialFormNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;
import net.javacrumbs.cloffle.nodes.value.ObjectNode;

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
public class Clojure extends TruffleLanguage<CloffleContext> {

    private static final Object EOF_SENTINEL = new Object();

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
        Var.popThreadBindings();
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
        Var currentNs = Var.find(Symbol.intern("clojure.core", "*ns*"));
        Var warnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        Var uncheckedMath = Var.find(Symbol.intern("clojure.core", "*unchecked-math*"));
        Var readEval = Var.find(Symbol.intern("clojure.core", "*read-eval*"));
        Var dataReaders = Var.find(Symbol.intern("clojure.core", "*data-readers*"));
        Var defaultDataReaderFn = Var.find(Symbol.intern("clojure.core", "*default-data-reader-fn*"));

        Var.pushThreadBindings(RT.mapUniqueKeys(
            currentNs, currentNs.deref(),
            warnReflection, warnReflection.deref(),
            uncheckedMath, uncheckedMath.deref(),
            readEval, readEval.deref(),
            dataReaders, dataReaders.deref(),
            defaultDataReaderFn, defaultDataReaderFn.deref()
        ));
    }

    public static CloffleContext getContext() {
        return getCurrentContext(Clojure.class);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws IOException {
        Source truffleSource = request.getSource();
        String sourceText = truffleSource.getCharacters().toString();
        clojure.lang.LineNumberingPushbackReader reader =
            new clojure.lang.LineNumberingPushbackReader(new StringReader(sourceText));

        List<FormEntry> forms = new ArrayList<>();

        pushCompilerBindings();
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
            FormEntry f = forms.get(0);
            ClojureRootNode rootNode = ClojureRootNode.create(f.node, f.frameDescriptor, this);
            rootNode.setSourceSection(truffleSource.createSection(0, sourceText.length()));
            return rootNode.getCallTarget();
        }

        FormEntry[] formArray = forms.toArray(new FormEntry[0]);
        ClojureNode seqNode = new SequentialFormNode(formArray, this, truffleSource);
        ExprToNode wrapperConverter = new ExprToNode(this, truffleSource);
        ClojureRootNode rootNode = ClojureRootNode.create(seqNode, wrapperConverter.buildFrameDescriptor(), this);
        rootNode.setSourceSection(truffleSource.createSection(0, sourceText.length()));
        return rootNode.getCallTarget();
    }

    /**
     * Analyze a form and add its Truffle node to the form list.
     * For forms that need eager execution (defmacro, ns, import, etc.),
     * execute via Truffle immediately so side effects are visible to
     * subsequent forms during analysis.
     *
     * <p>{@code do} blocks are split into individual subforms so that
     * a defmacro takes effect before later forms in the same block.
     */
    private void collectForm(Object form, Source source, List<FormEntry> forms) {
        if (needsEagerExec(form)) {
            Object result = truffleEval(form, source);
            forms.add(new FormEntry(new ObjectNode(result), new FrameDescriptor()));
            return;
        }

        net.javacrumbs.cloffle.compiler.MacroExpander.setCurrentSource(source);
        Object expanded;
        try {
            expanded = Compiler.macroexpand(form);
        } finally {
            net.javacrumbs.cloffle.compiler.MacroExpander.clearCurrentSource();
        }
        if (expanded instanceof ISeq seq && isDoSym(seq.first())) {
            for (ISeq s = seq.next(); s != null; s = s.next()) {
                collectForm(s.first(), source, forms);
            }
            return;
        }

        ExprToNode converter = new ExprToNode(this, source);
        Compiler.Expr expr = Compiler.analyze(C.EVAL, form);
        ClojureNode node = converter.convert(expr);
        forms.add(new FormEntry(node, converter.buildFrameDescriptor()));
    }

    /**
     * Execute a form entirely through the Truffle pipeline:
     * macroexpand -> split do blocks -> analyze -> ExprToNode -> call().
     * Mirrors CloffleCompiler.executeForm() to handle nested do blocks
     * from macro expansions (e.g., ns expands to a do block).
     */
    private Object truffleEval(Object form, Source source) {
        net.javacrumbs.cloffle.compiler.MacroExpander.setCurrentSource(source);
        Object expanded;
        try {
            expanded = Compiler.macroexpand(form);
        } finally {
            net.javacrumbs.cloffle.compiler.MacroExpander.clearCurrentSource();
        }
        if (expanded instanceof ISeq seq && isDoSym(seq.first())) {
            Object ret = null;
            for (ISeq s = seq.next(); s != null; s = s.next()) {
                ret = truffleEval(s.first(), source);
            }
            return ret;
        }

        ExprToNode converter = new ExprToNode(this, source);
        Compiler.Expr expr = Compiler.analyze(C.EVAL, expanded);
        ClojureNode node = converter.convert(expr);
        FrameDescriptor fd = converter.buildFrameDescriptor();
        ClojureRootNode root = ClojureRootNode.create(node, fd, this);
        root.setSourceSection(source.createSection(0, source.getLength()));
        if (form instanceof ISeq seq && seq.first() instanceof Symbol sym) {
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

    private static void pushCompilerBindings() {
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        Var uncheckedMath = Var.find(Symbol.intern("clojure.core", "*unchecked-math*"));
        Var dataReaders = Var.find(Symbol.intern("clojure.core", "*data-readers*"));

        Var.pushThreadBindings(RT.mapUniqueKeys(
                Compiler.LOADER, RT.makeClassLoader(),
                Compiler.SOURCE_PATH, "NO_SOURCE_PATH",
                Compiler.SOURCE, "NO_SOURCE_FILE",
                Compiler.METHOD, null,
                Compiler.LOCAL_ENV, null,
                Compiler.LOOP_LOCALS, null,
                Compiler.NEXT_LOCAL_NUM, 0,
                RT.READEVAL, RT.T,
                RT.CURRENT_NS, RT.CURRENT_NS.deref(),
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

    public record FormEntry(ClojureNode node, com.oracle.truffle.api.frame.FrameDescriptor frameDescriptor) {}

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
