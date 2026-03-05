package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IFn;
import clojure.lang.IPersistentList;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.FnNode;

/**
 * Handles conversion at the Truffle polyglot boundary only.
 * <p>
 * Within the AST, values flow as native Clojure types (Keyword, PersistentVector, etc.)
 * with no wrapping. Wrapping into TruffleObject is only done at the polyglot exit point
 * ({@link net.javacrumbs.cloffle.nodes.ClojureRootNode}), and unwrapping is done
 * at re-entry points (InvokeNode, FnNode.toIFn, etc.) that receive values from
 * ClojureRootNode calls.
 */
public final class ClojureInterop {

    private ClojureInterop() {}

    /**
     * Wraps a native Clojure value into a TruffleObject for crossing
     * the polyglot boundary. Primitives and Strings pass through unchanged.
     */
    public static Object wrapForPolyglot(Object value) {
        if (value == null) {
            return NilNode.NIL;
        }
        if (value instanceof Boolean
                || value instanceof Long
                || value instanceof Double
                || value instanceof Integer
                || value instanceof Float
                || value instanceof Short
                || value instanceof Byte
                || value instanceof Character
                || value instanceof String) {
            return value;
        }
        if (value instanceof Keyword kw) {
            return new ClojureKeyword(kw);
        }
        if (value instanceof Symbol sym) {
            return new ClojureSymbol(sym);
        }
        if (value instanceof IPersistentVector vec) {
            return new ClojureVector(vec);
        }
        if (value instanceof IPersistentMap map) {
            return new ClojureMap(map);
        }
        if (value instanceof IPersistentList list) {
            return new ClojureList(list);
        }
        if (value instanceof IPersistentSet set) {
            return new ClojureSet(set);
        }
        if (value instanceof ISeq seq) {
            java.util.ArrayList<Object> items = new java.util.ArrayList<>();
            for (ISeq s = seq; s != null; s = s.next()) {
                items.add(s.first());
            }
            return new ClojureList(clojure.lang.PersistentList.create(items));
        }
        if (value instanceof FnNode fnNode) {
            return new ClojureFunction(fnNode.toIFn());
        }
        if (value instanceof IFn fn) {
            return new ClojureFunction(fn);
        }
        return value;
    }

    /**
     * Unwraps a TruffleObject wrapper (from a polyglot boundary crossing)
     * back to a native Clojure value.
     */
    public static Object unwrapFromPolyglot(Object value) {
        if (value instanceof ClojureKeyword kw) {
            return kw.getKeyword();
        }
        if (value instanceof ClojureSymbol sym) {
            return sym.getSymbol();
        }
        if (value instanceof ClojureVector vec) {
            return vec.getVector();
        }
        if (value instanceof ClojureMap map) {
            return map.getMap();
        }
        if (value instanceof ClojureList list) {
            return list.getList();
        }
        if (value instanceof ClojureSet set) {
            return set.getSet();
        }
        if (value instanceof ClojureFunction fn) {
            return fn.getFn();
        }
        if (value instanceof NilNode.Nil) {
            return null;
        }
        if (value instanceof FnNode fnNode) {
            return fnNode.toIFn();
        }
        return value;
    }
}
