package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.nodes.ClojureException;

/**
 * Exception interception for {@link CloffleBytecodeRootNode}: wraps host exceptions so guest
 * {@code catch} clauses can see them, and enriches {@link ClojureException}s with the throwing
 * instruction's {@link SourceSection} plus a stack frame entry.
 */
final class BytecodeExceptionBoundary {

    private BytecodeExceptionBoundary() {
    }

    /**
     * Operations that call {@code clojure.lang} directly (e.g. {@code KeywordLookup}, {@code StaticMethod2}) throw
     * plain host exceptions, which would otherwise unwind past every Clojure {@code catch} clause, since guest
     * {@code try}/{@code catch} handlers only run for {@link AbstractTruffleException}s. Wrap them the same way the
     * {@code Reflector}-based operations do so {@code CheckCatch} can match on the cause.
     */
    @CompilerDirectives.TruffleBoundary
    static Throwable wrapInternalException(Exception e) {
        return ClojureException.wrapReflective(e);
    }

    /**
     * Bytecode operations throw {@link ClojureException} with {@code null} {@link Node} location; attach the current
     * instruction's {@link SourceSection} so Polyglot and guest stack frames report line/column. {@code self} supplies
     * the root name used for the enriched frame entry.
     */
    @CompilerDirectives.TruffleBoundary
    static AbstractTruffleException interceptTruffleException(
            CloffleBytecodeRootNode self,
            AbstractTruffleException ex,
            BytecodeNode bytecodeNode,
            int bytecodeIndex) {
        if (ex instanceof ClojureException ce
                && bytecodeNode != null) {
            SourceSection instrSS = resolveBytecodeSourceSection(bytecodeNode, bytecodeIndex);

            if (!hasPolyglotUsableExceptionLocation(ce)) {
                try {
                    if (instrSS != null && instrSS.isAvailable()) {
                        ce = ClojureException.withBytecodeSourceSection(ce, instrSS);
                    } else {
                        Node loc = bytecodeNode.getRootNode();
                        if (loc == null) loc = bytecodeNode;
                        ce = ClojureException.withLocationNode(ce, loc);
                    }
                } catch (Throwable ignored) {
                    Node loc = bytecodeNode.getRootNode();
                    if (loc == null) loc = bytecodeNode;
                    ce = ClojureException.withLocationNode(ce, loc);
                }
            }

            // Enriched frame tracking: add call-site source info so deep stacks show
            // intermediate frames at Truffle call sites.
            CompilerDirectives.transferToInterpreter();
            if (instrSS != null && instrSS.isAvailable() && instrSS.hasLines() && instrSS.getStartLine() > 0) {
                ce.addFrame(instrSS, self.getName());
            }

            return ce;
        }
        return ex;
    }

    private static boolean hasPolyglotUsableExceptionLocation(ClojureException ce) {
        Node loc = ce.getLocation();
        if (loc == null) {
            return false;
        }
        SourceSection ss = loc.getSourceSection();
        if (ss == null || !ss.isAvailable() || !ss.hasLines() || ss.getStartLine() <= 0) {
            ss = loc.getEncapsulatingSourceSection();
        }
        return ss != null && ss.isAvailable() && ss.hasLines() && ss.getStartLine() > 0;
    }

    private static SourceSection resolveBytecodeSourceSection(BytecodeNode bytecodeNode, int bytecodeIndex) {
        try {
            if (bytecodeIndex >= 0) {
                bytecodeNode.ensureSourceInformation();
                SourceSection ss = bytecodeNode.getSourceLocation(bytecodeIndex);
                if (ss == null || !ss.isAvailable()) {
                    BytecodeLocation loc = BytecodeLocation.get(bytecodeNode, bytecodeIndex);
                    if (loc != null) {
                        loc = loc.ensureSourceInformation();
                        ss = loc.getSourceLocation();
                    }
                }
                if (ss != null && ss.isAvailable()) {
                    return ss;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
