package net.javacrumbs.cloffle.nodes;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClojureException extends AbstractTruffleException implements IExceptionInfo {

    private static final Keyword PHASE_KEY = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE_KEY = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE_KEY = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN_KEY = Keyword.intern("clojure.error", "column");
    private static final Keyword LENGTH_KEY = Keyword.intern("clojure.error", "length");
    private static final Keyword END_LINE_KEY = Keyword.intern("clojure.error", "end-line");
    private static final Keyword END_COLUMN_KEY = Keyword.intern("clojure.error", "end-column");
    private static final Keyword SYMBOL_KEY = Keyword.intern("clojure.error", "symbol");
    private static final Keyword CLASS_KEY = Keyword.intern("clojure.error", "class");
    private static final Keyword CAUSE_KEY = Keyword.intern("clojure.error", "cause");

    public record CallFrame(String sourceName, int line, int column, int length,
                            String snippet, String fnName) {}

    private List<CallFrame> enrichedFrames;
    private Keyword phase;

    public ClojureException(String message, Node location) {
        super(message, location);
    }

    public ClojureException(String message, Throwable cause, Node location) {
        super(message, cause, UNLIMITED_STACK_TRACE, location);
    }

    public ClojureException(String message, Node location, Keyword phase) {
        super(message, location);
        this.phase = phase;
    }

    public ClojureException(String message, Throwable cause, Node location, Keyword phase) {
        super(message, cause, UNLIMITED_STACK_TRACE, location);
        this.phase = phase;
    }

    public void setPhase(Keyword phase) {
        this.phase = phase;
    }

    public Keyword getPhase() {
        return phase;
    }

    /**
     * Wraps a reflection exception for Truffle's beginTryCatch. Unwraps
     * InvocationTargetException so the real cause is visible to catch clauses
     * via {@link CheckCatch}/{@link UnwrapException}.
     */
    @CompilerDirectives.TruffleBoundary
    public static ClojureException wrapReflective(Exception e) {
        Throwable cause = e;
        if (e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
            cause = ite.getCause();
        }
        ClojureException ce =
                new ClojureException(ErrorMessages.formatException(cause), cause, null);
        ce.phase = Keyword.intern(null, "execution");
        return ce;
    }

    /**
     * Copies {@code ex} with a concrete {@link Node} location so Polyglot / stack frames see the
     * bytecode instruction's {@link SourceSection} (operations throw with {@code null} location).
     */
    public static ClojureException withBytecodeSourceSection(ClojureException ex, SourceSection ss) {
        if (ex == null || ss == null || !ss.isAvailable()) {
            return ex;
        }
        if (ex.getLocation() != null) {
            return ex;
        }
        ClojureException n =
                new ClojureException(ex.getMessage(), ex.getCause(), new SourceSectionLocationNode(ss), ex.getPhase());
        if (ex.enrichedFrames != null) {
            n.enrichedFrames = new ArrayList<>(ex.enrichedFrames);
        }
        return n;
    }

    /** Same as {@link #withBytecodeSourceSection} but uses a real AST/bytecode {@link Node} (e.g. {@link com.oracle.truffle.api.bytecode.BytecodeNode}). */
    public static ClojureException withLocationNode(ClojureException ex, Node node) {
        if (ex == null || node == null || ex.getLocation() != null) {
            return ex;
        }
        ClojureException n = new ClojureException(ex.getMessage(), ex.getCause(), node, ex.getPhase());
        if (ex.enrichedFrames != null) {
            n.enrichedFrames = new ArrayList<>(ex.enrichedFrames);
        }
        return n;
    }

    public static ClojureException wrap(Throwable t, Node location) {
        ClojureException ce = new ClojureException(ErrorMessages.formatException(t), t, location);
        ce.phase = Keyword.intern(null, "execution");
        return ce;
    }

    @Override
    @CompilerDirectives.TruffleBoundary
    public IPersistentMap getData() {
        Object[] kvs = buildExData();
        if (kvs.length == 0) {
            return PersistentArrayMap.EMPTY;
        }
        return PersistentArrayMap.createAsIfByAssoc(kvs);
    }

    @CompilerDirectives.TruffleBoundary
    private Object[] buildExData() {
        List<Object> pairs = new ArrayList<>(14);

        if (phase != null) {
            pairs.add(PHASE_KEY);
            pairs.add(phase);
        }

        SourceSection ss = resolveSourceSection();
        if (ss != null && ss.isAvailable()) {
            pairs.add(SOURCE_KEY);
            pairs.add(ss.getSource().getName());
            if (ss.hasLines()) {
                pairs.add(LINE_KEY);
                pairs.add((long) ss.getStartLine());
                if (ss.hasColumns()) {
                    pairs.add(COLUMN_KEY);
                    pairs.add((long) ss.getStartColumn());
                }
                int charLen = ss.getCharLength();
                if (charLen > 0) {
                    pairs.add(LENGTH_KEY);
                    pairs.add((long) charLen);
                }
                pairs.add(END_LINE_KEY);
                pairs.add((long) ss.getEndLine());
                if (ss.hasColumns()) {
                    pairs.add(END_COLUMN_KEY);
                    pairs.add((long) ss.getEndColumn());
                }
            }
        }

        Throwable cause = getCause();
        if (cause != null) {
            pairs.add(CLASS_KEY);
            pairs.add(clojure.lang.Symbol.intern(cause.getClass().getName()));
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                pairs.add(CAUSE_KEY);
                pairs.add(causeMsg);
            }
        }

        return pairs.toArray();
    }

    private SourceSection resolveSourceSection() {
        Node loc = getLocation();
        if (loc != null) {
            SourceSection ss = loc.getSourceSection();
            if (ss != null) return ss;
            ss = loc.getEncapsulatingSourceSection();
            if (ss != null) return ss;
        }
        return null;
    }

    @CompilerDirectives.TruffleBoundary
    public void addFrame(Node callSite) {
        if (callSite == null) return;
        SourceSection ss = callSite.getSourceSection();
        if (ss == null) {
            ss = callSite.getEncapsulatingSourceSection();
        }
        if (ss == null || !ss.isAvailable() || !ss.hasLines()) return;

        String fnName = null;
        RootNode root = callSite.getRootNode();
        if (root != null) {
            fnName = root.getName();
        }

        addFrame(ss, fnName);
    }

    @CompilerDirectives.TruffleBoundary
    public void addFrame(SourceSection ss, String fnName) {
        if (ss == null || !ss.isAvailable() || !ss.hasLines()) return;

        String snippet = null;
        try {
            snippet = ss.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {}

        if (enrichedFrames == null) {
            enrichedFrames = new ArrayList<>(4);
        }
        enrichedFrames.add(new CallFrame(
                ss.getSource().getName(), ss.getStartLine(), ss.getStartColumn(),
                Math.max(1, ss.getCharLength()), snippet, fnName));
    }

    public List<CallFrame> getEnrichedFrames() {
        return enrichedFrames != null ? enrichedFrames : Collections.emptyList();
    }

    /**
     * Walks {@code t}, {@link Throwable#getCause()}, and {@link Throwable#getSuppressed()} (depth-limited)
     * and returns the first {@link ClojureException}.
     */
    @CompilerDirectives.TruffleBoundary
    public static ClojureException findFirstInChain(Throwable t) {
        return findFirstInChain(t, 0, 32);
    }

    private static ClojureException findFirstInChain(Throwable t, int depth, int maxDepth) {
        if (t == null || depth > maxDepth) {
            return null;
        }
        if (t instanceof ClojureException ce) {
            return ce;
        }
        ClojureException r = findFirstInChain(t.getCause(), depth + 1, maxDepth);
        if (r != null) {
            return r;
        }
        for (Throwable s : t.getSuppressed()) {
            r = findFirstInChain(s, depth + 1, maxDepth);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * Polyglot may expose the thrown {@link Throwable} as a guest {@link Value} that is not
     * {@link Value#isHostObject() a host object}; {@link Value#asHostObject()} is then unusable, but
     * {@link Value#as(Class)} still maps to the host {@link Throwable}.
     */
    @CompilerDirectives.TruffleBoundary
    private static Throwable unwrapGuestThrowable(Value go) {
        if (go == null || go.isNull()) {
            return null;
        }
        try {
            if (go.isHostObject()) {
                Object ho = go.asHostObject();
                return ho instanceof Throwable t ? t : null;
            }
            return go.as(Throwable.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves the guest {@link ClojureException} for a polyglot error: cause chain, Polyglot guest object,
     * and host exception unwrap.
     */
    @CompilerDirectives.TruffleBoundary
    public static ClojureException findForPolyglot(PolyglotException e) {
        if (e == null) {
            return null;
        }
        ClojureException ce = findFirstInChain(e);
        if (ce != null) {
            return ce;
        }
        try {
            Throwable gt = unwrapGuestThrowable(e.getGuestObject());
            if (gt != null) {
                if (gt instanceof ClojureException cex) {
                    return cex;
                }
                return findFirstInChain(gt);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (e.isHostException()) {
                Throwable h = e.asHostException();
                return findFirstInChain(h);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Like {@link #findForPolyglot(PolyglotException)} but returns the first {@link ClojureException} whose
     * {@link #getEnrichedFrames()} is non-empty (bytecode may wrap or chain multiple guests).
     */
    @CompilerDirectives.TruffleBoundary
    public static ClojureException findForPolyglotWithEnrichedFrames(PolyglotException e) {
        if (e == null) {
            return null;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ClojureException ce && !ce.getEnrichedFrames().isEmpty()) {
                return ce;
            }
        }
        try {
            Throwable gt = unwrapGuestThrowable(e.getGuestObject());
            if (gt != null) {
                ClojureException c = findFirstInChainWithEnrichedFrames(gt);
                if (c != null) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (e.isHostException()) {
                return findFirstInChainWithEnrichedFrames(e.asHostException());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ClojureException findFirstInChainWithEnrichedFrames(Throwable t) {
        int guard = 0;
        while (t != null && guard++ < 32) {
            if (t instanceof ClojureException ce && !ce.getEnrichedFrames().isEmpty()) {
                return ce;
            }
            t = t.getCause();
        }
        return null;
    }

    /**
     * First {@link ClojureException} on {@code t}'s {@linkplain Throwable#getCause() cause} chain whose
     * {@link #getEnrichedFrames()} is non-empty (for guest-side error display without a {@link PolyglotException}).
     */
    @CompilerDirectives.TruffleBoundary
    public static ClojureException findThrowableWithEnrichedFrames(Throwable t) {
        return findFirstInChainWithEnrichedFrames(t);
    }

    @Override
    @CompilerDirectives.TruffleBoundary
    public StackTraceElement[] getStackTrace() {
        return filterInternalFrames(super.getStackTrace());
    }

    /**
     * Filters out internal Truffle/GraalVM frames from a JVM stack trace,
     * keeping only frames relevant to the user (clojure.*, user code, etc.).
     */
    public static StackTraceElement[] filterInternalFrames(StackTraceElement[] frames) {
        List<StackTraceElement> filtered = new ArrayList<>();
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (isInternalFrame(className)) continue;
            filtered.add(frame);
        }
        return filtered.toArray(new StackTraceElement[0]);
    }

    private static boolean isInternalFrame(String className) {
        return className.startsWith("com.oracle.truffle.")
                || className.startsWith("org.graalvm.")
                || className.startsWith("jdk.graal.")
                || className.startsWith("com.oracle.graal.")
                || className.contains("$CallTarget")
                || className.contains("$FrameWithoutBoxing")
                || className.startsWith("sun.reflect.")
                || className.startsWith("java.lang.reflect.")
                || className.startsWith("jdk.internal.reflect.");
    }

    /** Minimal {@link Node} so {@link AbstractTruffleException} carries a bytecode {@link SourceSection}. */
    private static final class SourceSectionLocationNode extends Node {
        @CompilationFinal private final SourceSection section;

        SourceSectionLocationNode(SourceSection section) {
            this.section = section;
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
        }
    }
}
