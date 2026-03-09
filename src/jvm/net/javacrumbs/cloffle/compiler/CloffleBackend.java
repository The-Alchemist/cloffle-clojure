package net.javacrumbs.cloffle.compiler;

import java.io.IOException;
import java.io.Reader;
import clojure.lang.RT;
import clojure.lang.Compiler;
import clojure.lang.Compiler.C;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;

import clojure.lang.Symbol;
import java.util.IdentityHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.PersistentHashMap;
import clojure.lang.Var;

public class CloffleBackend {
    private static final Object EOF = new Object();

    public static Object compile(Reader rdr, String sourcePath, String sourceName) throws IOException {
        LineNumberingPushbackReader pushbackReader =
            (rdr instanceof LineNumberingPushbackReader) ? (LineNumberingPushbackReader) rdr :
            new LineNumberingPushbackReader(rdr);

        Object ret = null;
        Object readerOpts = RT.map(RT.READEVAL, RT.T); // Basic opts

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
                   Compiler.CONSTANT_IDS, new IdentityHashMap(),
                   Compiler.KEYWORD_CALLSITES, null,
                   Compiler.PROTOCOL_CALLSITES, null,
                   Compiler.KEYWORDS, PersistentHashMap.EMPTY,
                   Compiler.VARS, PersistentHashMap.EMPTY
                   ,RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref()
                   ,Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*")), Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*")).deref()
                   ,RT.DATA_READERS, RT.DATA_READERS.deref()
                   ,Compiler.LOADER, RT.makeClassLoader()
            ));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);

        try {
            for (Object r = LispReader.read(pushbackReader, false, EOF, false, readerOpts); r != EOF;
                 r = LispReader.read(pushbackReader, false, EOF, false, readerOpts)) {
                
                 Compiler.LINE_AFTER.set(pushbackReader.getLineNumber());
                 Compiler.COLUMN_AFTER.set(pushbackReader.getColumnNumber());

                 if (Clojure.isHostEvalForm(r)) {
                     Clojure.hostEval(r);
                     Compiler.LINE_BEFORE.set(pushbackReader.getLineNumber());
                     Compiler.COLUMN_BEFORE.set(pushbackReader.getColumnNumber());
                     continue;
                 }
                 
                 Object form = Clojure.eagerHostEvalInDo(r);
                 if (form == null) {
                     Compiler.LINE_BEFORE.set(pushbackReader.getLineNumber());
                     Compiler.COLUMN_BEFORE.set(pushbackReader.getColumnNumber());
                     continue;
                 }

                 // Analyze
                 Compiler.Expr expr = Compiler.analyze(C.EVAL, form);
                 
                 // ... rest of the loop ...
                 Source source = Source.newBuilder("cloffle", "NO_SOURCE", "NO_SOURCE").build();
                 ExprToNode converter = new ExprToNode(null, source);
                 ClojureNode node = converter.convert(expr);
                 
                 // System.out.println("CloffleBackend: Converted form to " + node.getClass().getSimpleName());
                 
                 FrameDescriptor fd = converter.buildFrameDescriptor();
                 ClojureRootNode root = ClojureRootNode.create(node, fd, null);
                 
                 try {
                    ret = root.getCallTarget().call();
                    // System.out.println("CloffleBackend: Executed result: " + ret);
                 } catch (Exception e) {
                     throw e; // Rethrow to fail the test
                 }
                 
                 Compiler.LINE_BEFORE.set(pushbackReader.getLineNumber());
                 Compiler.COLUMN_BEFORE.set(pushbackReader.getColumnNumber());
            }
        } catch (Exception e) {
            throw new IOException("Compilation failed", e);
        } finally {
            Var.popThreadBindings();
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
        
        return ret;
    }
}
