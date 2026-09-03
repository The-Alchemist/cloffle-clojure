package net.javacrumbs.cloffle;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Editor-oriented compile check: parse source in a {@link Context} without {@link Context#eval},
 * map failures to stable {@link Diagnostic} records.
 *
 * <p>Ranges use <strong>1-based</strong> line and column (same as {@code :clojure.error/line},
 * Graal {@link SourceSection}, and typical Clojure tooling). LSP clients should subtract 1 from
 * line and use UTF-16/code-unit rules for character offsets.
 */
public final class CloffleDiagnostics {

    /**
     * Severity for UI mapping (LSP: 1 = Error, 2 = Warning).
     */
    public enum Severity {
        ERROR(1),
        WARNING(2);

        private final int lspCode;

        Severity(int lspCode) {
            this.lspCode = lspCode;
        }

        public int lspCode() {
            return lspCode;
        }
    }

    /**
     * One problem in a source file.
     *
     * @param severity   usually {@link Severity#ERROR} for parse/analyze failures
     * @param message    human-readable; use {@link PolyglotErrorTriage#formatMessage} for parity with REPL
     * @param sourceName logical name from {@link Source#getName()} (not necessarily a filesystem path)
     * @param startLine  1-based inclusive
     * @param startColumn 1-based inclusive when known; use 1 if unknown
     * @param endLine    1-based inclusive; equals start when unknown
     * @param endColumn  1-based inclusive; may equal startColumn for a point range
     * @param phase      {@code :clojure.error/phase} name (e.g. {@code read-source}), or null
     */
    public record Diagnostic(
            Severity severity,
            String message,
            String sourceName,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            String phase) {

        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(sourceName, "sourceName");
        }
    }

    private CloffleDiagnostics() {}

    /**
     * Parses Cloffle source (same work as load/compile up to building roots; may run macro expansion
     * and eager top-level forms such as {@code ns} / {@code defmacro}).
     *
     * @return empty list on success, or a singleton list with the first failure
     */
    public static List<Diagnostic> checkParse(Context context, Source source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        try {
            context.parse(source);
            return List.of();
        } catch (PolyglotException e) {
            return List.of(diagnosticFromException(source.getName(), e));
        }
    }

    /**
     * Build a diagnostic from any {@link PolyglotException} (e.g. from {@code eval}).
     */
    public static Diagnostic diagnosticFromException(String defaultSourceName, PolyglotException e) {
        IPersistentMap triage = PolyglotErrorTriage.triage(e);
        String message = PolyglotErrorTriage.formatMessage(triage).trim();
        if (message.isEmpty()) {
            message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        Keyword ph = triage != null && triage.count() > 0
                ? (triage.valAt(Keyword.intern("clojure.error", "phase")) instanceof Keyword k ? k : null)
                : null;
        String phaseStr = ph != null ? ph.getName() : null;

        SourceSection sl = e.getSourceLocation();
        String srcName = defaultSourceName;
        int startLine = 1;
        int startCol = 1;
        int endLine = 1;
        int endCol = 1;

        // Truffle 25.1+ may report internal frames (e.g. TruffleStackTrace.java) as the
        // polyglot source location for reader/parse errors — keep the caller's source name.
        if (sl != null && sl.isAvailable() && sl.hasLines()
                && PolyglotErrorLocations.isGuestLanguageSource(sl)) {
            srcName = sl.getSource().getName();
            startLine = sl.getStartLine();
            endLine = sl.hasLines() ? sl.getEndLine() : startLine;
            if (sl.hasColumns()) {
                startCol = sl.getStartColumn();
                endCol = sl.getEndColumn();
            }
        } else if (triage != null && triage.count() > 0) {
            Object s = triage.valAt(Keyword.intern("clojure.error", "source"));
            if (s != null) {
                srcName = String.valueOf(s);
            }
            Object line = triage.valAt(Keyword.intern("clojure.error", "line"));
            Object col = triage.valAt(Keyword.intern("clojure.error", "column"));
            if (line instanceof Number n) {
                startLine = endLine = n.intValue();
            }
            if (col instanceof Number n) {
                startCol = endCol = n.intValue();
            }
        }

        return new Diagnostic(Severity.ERROR, message, srcName, startLine, startCol, endLine, endCol, phaseStr);
    }

    /**
     * Convenience: check parse and return at most one diagnostic, or empty optional-style list.
     */
    public static List<Diagnostic> checkParseOrEmpty(Context context, Source source) {
        List<Diagnostic> d = checkParse(context, source);
        return d.isEmpty() ? Collections.emptyList() : d;
    }
}
