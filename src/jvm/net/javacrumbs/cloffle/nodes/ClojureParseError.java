package net.javacrumbs.cloffle.nodes;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

@ExportLibrary(InteropLibrary.class)
public class ClojureParseError extends AbstractTruffleException implements IExceptionInfo {

    private static final Keyword PHASE_KEY = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE_KEY = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE_KEY = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN_KEY = Keyword.intern("clojure.error", "column");
    private static final Keyword CAUSE_KEY = Keyword.intern("clojure.error", "cause");

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
        Object[] kvs = new Object[]{
            PHASE_KEY, phase != null ? phase : Keyword.intern(null, "read-source"),
            SOURCE_KEY, source != null ? source.getName() : "UNKNOWN",
            LINE_KEY, (long) line,
            COLUMN_KEY, (long) column,
            CAUSE_KEY, getMessage()
        };
        return PersistentArrayMap.createAsIfByAssoc(kvs);
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
