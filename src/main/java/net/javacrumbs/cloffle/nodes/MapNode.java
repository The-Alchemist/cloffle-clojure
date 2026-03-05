package net.javacrumbs.cloffle.nodes;

import clojure.lang.PersistentArrayMap;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.ClojureMap;

public class MapNode extends ClojureNode {

    @Children
    private final ClojureNode[] keys;

    @Children
    private final ClojureNode[] vals;

    public MapNode(ClojureNode[] keys, ClojureNode[] vals) {
        this.keys = keys;
        this.vals = vals;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] kvs = new Object[keys.length * 2];
        for (int i = 0; i < keys.length; i++) {
            kvs[i * 2] = ClojureInterop.unwrap(keys[i].executeGeneric(virtualFrame));
            kvs[i * 2 + 1] = ClojureInterop.unwrap(vals[i].executeGeneric(virtualFrame));
        }
        return PersistentArrayMap.createAsIfByAssoc(kvs);
    }
}
