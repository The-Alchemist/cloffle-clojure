package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;

/**
 * One parsed top-level form: call target plus 1-based source line for debugger stepping.
 */
public record TopLevelFormEntry(CallTarget target, int sourceLine) {
}
