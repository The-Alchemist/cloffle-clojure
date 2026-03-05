package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IFn;
import clojure.lang.IPersistentList;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.nodes.FnNode;

/**
 * Converts between raw Clojure objects and TruffleObject wrappers.
 * Primitives (long, double, boolean) and String pass through unchanged
 * since the Polyglot boundary handles them natively.
 */
public final class ClojureInterop {

    private ClojureInterop() {}

    public static Object wrap(Object value) {
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
        if (value instanceof Var var) {
            return new VarValue(var);
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
     * Unwraps a TruffleObject wrapper back to the raw Clojure object,
     * for use as map keys, set membership checks, etc.
     */
    public static Object unwrap(Object value) {
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
        if (value instanceof VarValue var) {
            return var.getVar();
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
