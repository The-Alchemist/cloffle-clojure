package net.javacrumbs.cloffle.compiler;

import java.io.IOException;
import java.io.Reader;
import java.util.IdentityHashMap;

import clojure.lang.Compiler;
import clojure.lang.Compiler.C;
import clojure.lang.ISeq;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;

public final class CloffleCompiler {
    private static final Object EOF = new Object();

    private CloffleCompiler() {
    }

    public static Object compile(Reader rdr, String sourcePath, String sourceName) throws IOException {
        LineNumberingPushbackReader pushbackReader =
                (rdr instanceof LineNumberingPushbackReader) ? (LineNumberingPushbackReader) rdr
                        : new LineNumberingPushbackReader(rdr);

        Object ret = null;
        Object readerOpts = RT.map(RT.READEVAL, RT.T);

        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));

        Var.pushThreadBindings(
                RT.mapUniqueKeys(Compiler.SOURCE_PATH, sourcePath,
                        Compiler.SOURCE, sourceName,
                        Compiler.METHOD, null,
                        Compiler.LOCAL_ENV, null,
                        Compiler.LOOP_LOCALS, null,
                        Compiler.NEXT_LOCAL_NUM, 0,
                        RT.READEVAL, RT.T,
                        RT.CURRENT_NS, RT.CURRENT_NS.deref(),
                        Compiler.LINE_BEFORE, pushbackReader.getLineNumber(),
                        Compiler.COLUMN_BEFORE, pushbackReader.getColumnNumber(),
                        Compiler.LINE_AFTER, pushbackReader.getLineNumber(),
                        Compiler.COLUMN_AFTER, pushbackReader.getColumnNumber(),
                        Compiler.CONSTANTS, PersistentVector.EMPTY,
                        Compiler.CONSTANT_IDS, new IdentityHashMap<>(),
                        Compiler.KEYWORD_CALLSITES, PersistentVector.EMPTY,
                        Compiler.PROTOCOL_CALLSITES, PersistentVector.EMPTY,
                        Compiler.KEYWORDS, PersistentHashMap.EMPTY,
                        Compiler.VARS, PersistentHashMap.EMPTY,
                        RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref(),
                        warnOnReflection, warnOnReflection.deref(),
                        RT.DATA_READERS, RT.DATA_READERS.deref(),
                        Compiler.LOADER, RT.makeClassLoader()));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);

        try {
            for (Object r = LispReader.read(pushbackReader, false, EOF, false, readerOpts); r != EOF;
                 r = LispReader.read(pushbackReader, false, EOF, false, readerOpts)) {

                Compiler.LINE_AFTER.set(pushbackReader.getLineNumber());
                Compiler.COLUMN_AFTER.set(pushbackReader.getColumnNumber());

                ret = executeForm(r);

                Compiler.LINE_BEFORE.set(pushbackReader.getLineNumber());
                Compiler.COLUMN_BEFORE.set(pushbackReader.getColumnNumber());
            }
        } finally {
            Var.popThreadBindings();
            Thread.currentThread().setContextClassLoader(oldLoader);
        }

        return ret;
    }

    /**
     * Analyze, convert, and execute a single form via Truffle.
     * {@code do} blocks are split so that each subform is fully
     * executed (side effects visible) before the next is analyzed.
     */
    public static Object executeForm(Object form) throws IOException {
        Object expanded = Compiler.macroexpand(form);
        if (expanded instanceof ISeq seq) {
            Object first = seq.first();
            if (first instanceof Symbol sym
                    && "do".equals(sym.getName())
                    && sym.getNamespace() == null) {
                Object ret = null;
                for (ISeq s = seq.next(); s != null; s = s.next()) {
                    ret = executeForm(s.first());
                }
                return ret;
            }
        }

        Compiler.Expr expr = Compiler.analyze(C.EVAL, expanded);
        Source source = Source.newBuilder("cloffle", "NO_SOURCE", "NO_SOURCE").build();
        ExprToNode converter = new ExprToNode(null, source);
        ClojureNode node = converter.convert(expr);
        FrameDescriptor fd = converter.buildFrameDescriptor();
        ClojureRootNode root = ClojureRootNode.create(node, fd, null);
        Object result = root.getCallTarget().call();
        return result instanceof NilNode.Nil ? null : result;
    }
}
