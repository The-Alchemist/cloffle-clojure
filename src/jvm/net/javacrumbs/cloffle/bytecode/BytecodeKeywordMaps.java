package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Associative;
import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;

/**
 * Implementation helpers for the literal-keyword map operations ({@code KeywordLookup},
 * {@code KeywordLookupDefault}, {@code KeywordAssoc}, {@code KeywordDissoc}).
 *
 * <p>The operations keep their {@code @Cached} shape/class caches and {@code castExact} bodies
 * inline, because those are what partial escape analysis reads; everything else (transition
 * factories, the uncached receiver work, and the generic {@link RT} fallbacks) lives here.
 */
final class BytecodeKeywordMaps {
    private BytecodeKeywordMaps() {
    }

    static boolean isILookup(Object obj) {
        return obj instanceof ILookup;
    }

    static boolean isAssociative(Object obj) {
        return obj instanceof Associative;
    }

    static boolean isMap(Object obj) {
        return obj instanceof IPersistentMap;
    }

    static Object lookupGeneric(Keyword keyword, Object target) {
        return RT.get(target, keyword);
    }

    static Object lookupGeneric(Keyword keyword, Object target, Object notFound) {
        return RT.get(target, keyword, notFound);
    }

    /** Value behind a shape slot resolved by the operation's {@code @Cached} slot index. */
    static Object slotValue(PersistentShapeMap target, int cachedSlot, Object notFound) {
        if (cachedSlot >= 0) {
            return target.getVal(cachedSlot);
        }
        return notFound;
    }

    static Object lookup(ILookup target, Keyword keyword) {
        return target.valAt(keyword);
    }

    static Object lookup(ILookup target, Keyword keyword, Object notFound) {
        return target.valAt(keyword, notFound);
    }

    static PersistentShapeMap16.Lookup16Transition lookup16Transition(
            PersistentShapeMap16 map, Keyword keyword) {
        return PersistentShapeMap16.lookupTransition(map, keyword);
    }

    static PersistentShapeMap.AssocTransition assocTransition(PersistentShapeMap map, Keyword keyword) {
        return PersistentShapeMap.assocTransition(map, keyword);
    }

    static PersistentShapeMap16.Assoc16Transition assoc16Transition(
            PersistentShapeMap16 map, Keyword keyword) {
        return PersistentShapeMap16.assocTransition(map, keyword);
    }

    static PersistentShapeMap.DissocTransition dissocTransition(
            PersistentShapeMap map, Keyword keyword) {
        return PersistentShapeMap.dissocTransition(map, keyword);
    }

    static PersistentShapeMap16.Dissoc16Transition dissoc16Transition(
            PersistentShapeMap16 map, Keyword keyword) {
        return PersistentShapeMap16.dissocTransition(map, keyword);
    }

    /** {@code (assoc nil :k v)} builds a one-entry shaped map, matching {@link RT#assoc}. */
    static Object assocNull(Keyword keyword, Object val) {
        return PersistentShapeMap.create(keyword, val);
    }

    static Object assoc(Associative target, Keyword keyword, Object val) {
        return target.assoc(keyword, val);
    }

    /** Non-{@link Associative}, non-null receiver: defer to {@link RT#assoc} so the cast error matches stock. */
    static Object assocGeneric(Object target, Keyword keyword, Object val) {
        return RT.assoc(target, keyword, val);
    }

    static Object without(IPersistentMap target, Keyword keyword) {
        return target.without(keyword);
    }

    /**
     * Non-{@link IPersistentMap}, non-null receiver: defer to {@link RT#dissoc} so the
     * {@code ClassCastException} matches stock exactly.
     */
    static Object dissocGeneric(Object target, Keyword keyword) {
        return RT.dissoc(target, keyword);
    }
}
