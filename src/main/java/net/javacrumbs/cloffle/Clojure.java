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

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ParsingRequest;

import net.javacrumbs.cloffle.ast.AstBuilder;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.DoNode;
import net.javacrumbs.cloffle.nodes.SequentialFormNode;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@TruffleLanguage.Registration(id = "cloffle", name = "Cloffle")
public class Clojure extends TruffleLanguage<CloffleContext> {

    private static final Object EOF_SENTINEL = new Object();
    private static final clojure.lang.IFn ANALYZE_FN;

    static {
        mikera.cljutils.Clojure.require("clojure.tools.analyzer.jvm");
        ANALYZE_FN = (clojure.lang.IFn) mikera.cljutils.Clojure.eval("clojure.tools.analyzer.jvm/analyze");
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
        String source = request.getSource().getCharacters().toString();
        clojure.lang.LineNumberingPushbackReader reader =
            new clojure.lang.LineNumberingPushbackReader(new StringReader(source));

        List<FormEntry> forms = new ArrayList<>();

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
                throw e;
            }
            if (form == EOF_SENTINEL) {
                break;
            }
            if (isNsForm(form)) {
                handleNsForm(form);
                continue;
            }
            AstBuilder astBuilder = new AstBuilder(this);
            @SuppressWarnings("unchecked")
            Map<clojure.lang.Keyword, Object> analyzeResult = (Map<clojure.lang.Keyword, Object>) ANALYZE_FN.invoke(form);
            ClojureNode node = astBuilder.build(analyzeResult);
            forms.add(new FormEntry(node, astBuilder.getFrameDescriptor()));
        }

        if (forms.isEmpty()) {
            AstBuilder astBuilder = new AstBuilder(this);
            @SuppressWarnings("unchecked")
            Map<clojure.lang.Keyword, Object> analyzeResult = (Map<clojure.lang.Keyword, Object>) ANALYZE_FN.invoke(null);
            ClojureNode node = astBuilder.build(analyzeResult);
            return ClojureRootNode.create(node, astBuilder.getFrameDescriptor(), this).getCallTarget();
        }

        if (forms.size() == 1) {
            FormEntry f = forms.get(0);
            return ClojureRootNode.create(f.node, f.frameDescriptor, this).getCallTarget();
        }

        FormEntry[] formArray = forms.toArray(new FormEntry[0]);
        ClojureNode seqNode = new SequentialFormNode(formArray, this);
        AstBuilder wrapperBuilder = new AstBuilder(this);
        return ClojureRootNode.create(seqNode, wrapperBuilder.getFrameDescriptor(), this).getCallTarget();
    }

    public record FormEntry(ClojureNode node, com.oracle.truffle.api.frame.FrameDescriptor frameDescriptor) {}

    private static boolean isNsForm(Object form) {
        if (form instanceof clojure.lang.ISeq seq) {
            Object first = seq.first();
            return Symbol.intern("ns").equals(first);
        }
        return false;
    }

    private static void handleNsForm(Object form) {
        clojure.lang.ISeq seq = (clojure.lang.ISeq) form;
        Object nsName = seq.next().first();
        Symbol nsSym = (nsName instanceof Symbol s) ? s : Symbol.intern(nsName.toString());
        Namespace ns = Namespace.findOrCreate(nsSym);
        Var.find(Symbol.intern("clojure.core", "*ns*")).set(ns);
    }
}
