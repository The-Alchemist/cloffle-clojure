package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;

/**
 * One parsed top-level form: call target plus source metadata for debugger stepping and JSONL tracing.
 *
 * @param isRuntimeStatement whether a line breakpoint / step-over should halt on this form.
 *                           {@code false} for function definitions ({@code defn}/{@code defmacro}),
 *                           so the debugger skips them at load time — matching Java/Python/JS UX.
 * @param uri                filesystem path or source name for TraceRecord events (may be null)
 * @param sourceColumn       1-based column of the form start (0 if unknown)
 * @param formText           reader form text for {@code formEnter}/{@code formExit} (may be null)
 */
public record TopLevelFormEntry(
        CallTarget target,
        int sourceLine,
        boolean isRuntimeStatement,
        String uri,
        int sourceColumn,
        String formText) {

    /** Convenience when trace metadata is unavailable. */
    public TopLevelFormEntry(CallTarget target, int sourceLine, boolean isRuntimeStatement) {
        this(target, sourceLine, isRuntimeStatement, null, 0, null);
    }
}
