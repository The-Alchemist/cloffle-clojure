package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClojureException extends AbstractTruffleException {

    public record CallFrame(String sourceName, int line, int column, int length,
                            String snippet, String fnName) {}

    private static final ThreadLocal<List<CallFrame>> LAST_ENRICHED_FRAMES = new ThreadLocal<>();

    private List<CallFrame> enrichedFrames;

    public ClojureException(String message, Node location) {
        super(message, location);
    }

    public ClojureException(String message, Throwable cause, Node location) {
        super(message, cause, UNLIMITED_STACK_TRACE, location);
    }

    public static ClojureException wrap(Throwable t, Node location) {
        return new ClojureException(ErrorMessages.formatException(t), t, location);
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
    }

    public static List<CallFrame> consumeEnrichedFrames() {
        List<CallFrame> frames = LAST_ENRICHED_FRAMES.get();
        LAST_ENRICHED_FRAMES.remove();
        return frames != null ? frames : Collections.emptyList();
    }
}
