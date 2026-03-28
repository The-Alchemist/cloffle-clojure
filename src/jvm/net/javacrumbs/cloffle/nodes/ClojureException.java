package net.javacrumbs.cloffle.nodes;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClojureException extends AbstractTruffleException implements IExceptionInfo {

    private static final Keyword PHASE_KEY = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE_KEY = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE_KEY = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN_KEY = Keyword.intern("clojure.error", "column");
    private static final Keyword SYMBOL_KEY = Keyword.intern("clojure.error", "symbol");
    private static final Keyword CLASS_KEY = Keyword.intern("clojure.error", "class");
    private static final Keyword CAUSE_KEY = Keyword.intern("clojure.error", "cause");

    public record CallFrame(String sourceName, int line, int column, int length,
                            String snippet, String fnName) {}

    private static final ThreadLocal<List<CallFrame>> LAST_ENRICHED_FRAMES = new ThreadLocal<>();
    private static final ThreadLocal<Keyword> LAST_PHASE = new ThreadLocal<>();

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

        String snippet = null;
        try {
            snippet = ss.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {}

        String fnName = null;
        RootNode root = callSite.getRootNode();
        if (root != null) {
            fnName = root.getName();
        }

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

    public void publishFrames() {
        LAST_ENRICHED_FRAMES.set(enrichedFrames);
        LAST_PHASE.set(phase);
    }

    public static List<CallFrame> consumeEnrichedFrames() {
        List<CallFrame> frames = LAST_ENRICHED_FRAMES.get();
        LAST_ENRICHED_FRAMES.remove();
        return frames != null ? frames : Collections.emptyList();
    }

    public static Keyword consumePhase() {
        Keyword p = LAST_PHASE.get();
        LAST_PHASE.remove();
        return p;
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
    static StackTraceElement[] filterInternalFrames(StackTraceElement[] frames) {
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
}
