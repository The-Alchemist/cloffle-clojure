package net.javacrumbs.cloffle;

import com.oracle.truffle.api.frame.FrameDescriptor;
import net.javacrumbs.cloffle.nodes.ClojureNode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language context that persists across evaluations within a single
 * Polyglot Context. Holds top-level def/defn bindings so they can
 * be referenced across separate eval() calls.
 */
public class CloffleContext {

    public record DefEntry(ClojureNode node, FrameDescriptor frameDescriptor) {}

    private final ConcurrentHashMap<Object, DefEntry> globalDefs = new ConcurrentHashMap<>();
    private com.oracle.truffle.api.TruffleLanguage<?> language;

    public void setLanguage(com.oracle.truffle.api.TruffleLanguage<?> language) {
        this.language = language;
    }

    public com.oracle.truffle.api.TruffleLanguage<?> language() {
        return language;
    }

    public void putDef(Object key, ClojureNode node, FrameDescriptor frameDescriptor) {
        globalDefs.put(key, new DefEntry(node, frameDescriptor));
    }

    public DefEntry getDef(Object key) {
        return globalDefs.get(key);
    }

    public Set<Map.Entry<Object, DefEntry>> getAllDefs() {
        return globalDefs.entrySet();
    }
}
