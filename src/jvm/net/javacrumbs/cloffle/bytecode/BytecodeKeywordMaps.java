package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Associative;
import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;

final class BytecodeKeywordMaps {
    private BytecodeKeywordMaps() {
    }

    static boolean isILookup(Object obj) {
        return obj instanceof ILookup;
    }

    static boolean isAssociative(Object obj) {
        return obj instanceof Associative;
    }

    static boolean isPersistentMap(Object obj) {
        return obj instanceof IPersistentMap;
    }

    static PersistentShapeMap.LookupTransition createLookupTransition(PersistentShapeMap target, Keyword keyword) {
        return PersistentShapeMap.lookupTransition(target, keyword);
    }

    static PersistentShapeMap.AssocTransition createAssocTransition(PersistentShapeMap target, Keyword keyword) {
        return PersistentShapeMap.assocTransition(target, keyword);
    }

    static PersistentShapeMap.DissocTransition createDissocTransition(PersistentShapeMap target, Keyword keyword) {
        return PersistentShapeMap.dissocTransition(target, keyword);
    }

    static PersistentShapeMap16.Dissoc16Transition createDissocTransition16(PersistentShapeMap16 target, Keyword keyword) {
        return PersistentShapeMap16.dissocTransition(target, keyword);
    }

    static Object assocNull(Object key, Object val) {
        if (key instanceof Keyword kw) {
            return PersistentShapeMap.create(kw, val);
        }
        return RT.map(key, val);
    }

    static Object keywordAssocNull(Keyword keyword, Object val) {
        return PersistentShapeMap.create(keyword, val);
    }

    static Object lookupGeneric(Keyword keyword, Object target) {
        return RT.get(target, keyword);
    }

    static Object lookupGeneric(Keyword keyword, Object target, Object notFound) {
        return RT.get(target, keyword, notFound);
    }

    static Object assocGeneric(Object target, Object key, Object val) {
        return RT.assoc(target, key, val);
    }

    static Object dissocGeneric(Object target, Object key) {
        return RT.dissoc(target, key);
    }
}
