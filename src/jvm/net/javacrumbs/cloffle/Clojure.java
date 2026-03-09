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
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ParsingRequest;

import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.SequentialFormNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import clojure.lang.ISeq;

@TruffleLanguage.Registration(id = "cloffle", name = "Cloffle")
public class Clojure extends TruffleLanguage<CloffleContext> {

    private static final Object EOF_SENTINEL = new Object();

    static {
        RT.init();
    }

    @Override
    protected CloffleContext createContext(Env env) {
        ensureThreadBindings();
        CloffleContext ctx = new CloffleContext();
        ctx.setLanguage(this);
        return ctx;
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
                if (isHostEvalForm(form)) {
                    hostEval(form);
                    continue;
                }
                // Eagerly evaluate defmacro (and other host-eval forms)
                // nested inside do blocks so that macros are defined before
                // subsequent forms in the same block are analyzed.
                form = eagerHostEvalInDo(form);
                if (form == null) {
                    continue;
                }
                try {
                    ExprToNode converter = new ExprToNode(this, truffleSource);
                    Compiler.Expr expr = Compiler.analyze(C.EVAL, form);
                    ClojureNode node = converter.convert(expr);
                    forms.add(new FormEntry(node, converter.buildFrameDescriptor()));
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
                uncheckedMath, uncheckedMath.deref(),
                warnOnReflection, warnOnReflection.deref(),
                dataReaders, dataReaders.deref()
        ));
    }

    public record FormEntry(ClojureNode node, com.oracle.truffle.api.frame.FrameDescriptor frameDescriptor) {}

    public static final Set<Symbol> HOST_EVAL_FORMS = Set.of(
        Symbol.intern("ns"),
        Symbol.intern("require"),
        Symbol.intern("use"),
        Symbol.intern("import"),
        Symbol.intern("refer"),
        Symbol.intern("defmacro"),
        Symbol.intern("definline"),
        Symbol.intern("in-ns"),
        Symbol.intern("defprotocol"),
        Symbol.intern("defmulti"),
        Symbol.intern("defmethod"),
        Symbol.intern("extend-protocol"),
        Symbol.intern("extend-type"),
        Symbol.intern("extend"),
        Symbol.intern("load")
    );

    public static boolean isHostEvalForm(Object form) {
        if (form instanceof ISeq seq) {
            Object first = seq.first();
            return first instanceof Symbol && HOST_EVAL_FORMS.contains(first);
        }
        return false;
    }

    private static final Symbol DO = Symbol.intern("do");

    /**
     * Walk a form and eagerly host-eval any {@code defmacro} (or other
     * HOST_EVAL_FORMS) nested inside {@code do} blocks. Returns the form
     * with those subforms removed, or {@code null} if the entire form was
     * consumed by host-eval.
     *
     * <p>This mirrors what {@code Compiler.eval} does for {@code do}: it
     * evaluates each subform sequentially so that a {@code defmacro} takes
     * effect before later forms in the same block are analyzed.
     */
    public static Object eagerHostEvalInDo(Object form) {
        if (!(form instanceof ISeq seq)) {
            return form;
        }
        Object first = seq.first();
        if (!(first instanceof Symbol sym) || !sym.equals(DO)) {
            return form;
        }

        // Walk subforms: host-eval the ones that need it, keep the rest
        List<Object> kept = new ArrayList<>();
        for (ISeq s = seq.next(); s != null; s = s.next()) {
            Object sub = s.first();
            if (isHostEvalForm(sub)) {
                hostEval(sub);
            } else {
                Object processed = eagerHostEvalInDo(sub);
                if (processed != null) {
                    kept.add(processed);
                }
            }
        }

        if (kept.isEmpty()) {
            return null;
        }
        if (kept.size() == 1) {
            return kept.get(0);
        }

        // Rebuild (do kept-form-1 kept-form-2 ...)
        ISeq result = null;
        for (int i = kept.size() - 1; i >= 0; i--) {
            result = RT.cons(kept.get(i), result);
        }
        return RT.cons(DO, result);
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

        // The reader reports the column where the cursor was when the error
        // occurred, not where the form started.  Span from column 1 to the
        // error column so the squiggle covers the whole problematic region.
        int startCol = 1;
        int length = Math.max(1, errorCol - startCol + 1);

        return new net.javacrumbs.cloffle.nodes.ClojureParseError(
                source, line, startCol, length, false, msg, cause != null ? cause : e);
    }

    private static net.javacrumbs.cloffle.nodes.ClojureParseError makeAnalyzerException(
            Exception e, Source source, clojure.lang.LineNumberingPushbackReader reader) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();

        int line = Math.min(reader.getLineNumber(), source.getLineCount());
        line = Math.max(1, line);
        int column = 1;
        int length = 1;
        try {
            length = Math.max(1, source.getLineLength(line));
        } catch (Exception ignored) {}

        return new net.javacrumbs.cloffle.nodes.ClojureParseError(
                source, line, column, length, false, msg, e);
    }

    public static void hostEval(Object form) {
        clojure.lang.IFn evalFn = (clojure.lang.IFn) RT.var("clojure.core", "eval").deref();
        evalFn.invoke(form);
    }
}
