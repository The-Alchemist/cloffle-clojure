package net.javacrumbs.cloffle.nodes;

import clojure.lang.Compiler;
import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.Symbol;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;
import java.util.List;

@ExportLibrary(InteropLibrary.class)
public class ClojureParseError extends AbstractTruffleException implements IExceptionInfo {

    private static final Keyword PHASE_KEY = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE_KEY = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE_KEY = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN_KEY = Keyword.intern("clojure.error", "column");
    private static final Keyword CAUSE_KEY = Keyword.intern("clojure.error", "cause");
    private static final Keyword SYMBOL_KEY = Keyword.intern("clojure.error", "symbol");
    private static final Keyword SPEC_KEY = Keyword.intern("clojure.error", "spec");
    private static final Keyword CLASS_KEY = Keyword.intern("clojure.error", "class");
    private static final Keyword MACRO_STACK_KEY = Keyword.intern("clojure.error", "macro-stack");

    private final Source source;
    private final int line;
    private final int column;
    private final int length;
    private final boolean incompleteSource;
    private final Keyword phase;

    public ClojureParseError(Source source, int line, int column, int length,
                             boolean incompleteSource, String message) {
        this(source, line, column, length, incompleteSource, message,
             Keyword.intern(null, "read-source"));
    }

    public ClojureParseError(Source source, int line, int column, int length,
                             boolean incompleteSource, String message, Throwable cause) {
        this(source, line, column, length, incompleteSource, message, cause,
             Keyword.intern(null, "read-source"));
    }

    public ClojureParseError(Source source, int line, int column, int length,
                             boolean incompleteSource, String message, Keyword phase) {
        super(message);
        this.source = source;
        this.line = line;
        this.column = column;
        this.length = length;
        this.incompleteSource = incompleteSource;
        this.phase = phase;
    }

    public ClojureParseError(Source source, int line, int column, int length,
                             boolean incompleteSource, String message, Throwable cause,
                             Keyword phase) {
        super(message, cause, UNLIMITED_STACK_TRACE, null);
        this.source = source;
        this.line = line;
        this.column = column;
        this.length = length;
        this.incompleteSource = incompleteSource;
        this.phase = phase;
    }

    @Override
    @TruffleBoundary
    public IPersistentMap getData() {
        List<Object> p = new ArrayList<>(24);
        Keyword effPhase = phase != null ? phase : Keyword.intern(null, "read-source");
        Compiler.CompilerException innermostCe = null;
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof Compiler.CompilerException ce) {
                innermostCe = ce;
            }
        }
        if (innermostCe != null) {
            IPersistentMap d = innermostCe.getData();
            if (d != null) {
                Object ph = d.valAt(Compiler.CompilerException.ERR_PHASE);
                if (ph instanceof Keyword k) {
                    effPhase = k;
                }
            }
        }

        p.add(PHASE_KEY);
        p.add(effPhase);
        p.add(SOURCE_KEY);
        p.add(source != null ? source.getName() : "UNKNOWN");
        p.add(LINE_KEY);
        p.add((long) line);
        p.add(COLUMN_KEY);
        p.add((long) column);
        p.add(CAUSE_KEY);
        p.add(getMessage());

        if (innermostCe != null) {
            IPersistentMap d = innermostCe.getData();
            if (d != null) {
                Object sym = d.valAt(Compiler.CompilerException.ERR_SYMBOL);
                if (sym instanceof Symbol) {
                    p.add(SYMBOL_KEY);
                    p.add(sym);
                }
            }
        }

        IPersistentMap specMap = null;
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof IExceptionInfo ei) {
                IPersistentMap dm = ei.getData();
                if (dm != null && dm.valAt(Compiler.CompilerException.SPEC_PROBLEMS) != null) {
                    specMap = dm;
                    break;
                }
            }
        }
        if (specMap != null) {
            p.add(SPEC_KEY);
            p.add(specMap);
        }

        Throwable leaf = getCause();
        while (leaf != null && leaf.getCause() != null) {
            leaf = leaf.getCause();
        }
        if (leaf != null && !(leaf instanceof Compiler.CompilerException)) {
            p.add(CLASS_KEY);
            p.add(Symbol.intern(leaf.getClass().getName()));
        }

        List<Symbol> macroSyms = new ArrayList<>();
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof Compiler.CompilerException ce) {
                IPersistentMap d = ce.getData();
                if (d != null) {
                    Object sym = d.valAt(Compiler.CompilerException.ERR_SYMBOL);
                    if (sym instanceof Symbol s) {
                        macroSyms.add(s);
                    }
                }
            }
        }
        if (!macroSyms.isEmpty()) {
            p.add(MACRO_STACK_KEY);
            p.add(PersistentVector.create(macroSyms));
        }

        return PersistentArrayMap.createAsIfByAssoc(p.toArray());
    }

    @ExportMessage
    ExceptionType getExceptionType() {
        return ExceptionType.PARSE_ERROR;
    }

    @ExportMessage
    boolean isExceptionIncompleteSource() {
        return incompleteSource;
    }

    @ExportMessage
    boolean hasSourceLocation() {
        return source != null;
    }

    @ExportMessage(name = "getSourceLocation")
    @TruffleBoundary
    SourceSection getSourceSection() throws UnsupportedMessageException {
        if (source == null) {
            throw UnsupportedMessageException.create();
        }
        return source.createSection(line, column, length);
    }
}
