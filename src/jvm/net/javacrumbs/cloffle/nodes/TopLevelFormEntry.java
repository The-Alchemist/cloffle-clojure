package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;

/**
 * One parsed top-level form: call target plus 1-based source line for debugger stepping.
 *
 * @param isRuntimeStatement whether a line breakpoint / step-over should halt on this form.
 *                           {@code false} for function definitions ({@code defn}/{@code defmacro}),
 *                           so the debugger skips them at load time — matching Java/Python/JS UX.
 */
public record TopLevelFormEntry(CallTarget target, int sourceLine, boolean isRuntimeStatement) {
}
