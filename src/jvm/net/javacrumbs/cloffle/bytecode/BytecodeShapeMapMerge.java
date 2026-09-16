package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IPersistentCollection;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;

/**
 * Helpers for the 2-arg {@code ShapeMapMerge} bytecode op ({@code (merge a b)} under
 * {@code :cloffle/op}). Transition factories stay here so the operation body can keep
 * {@code @Cached} guards PEA-friendly.
 */
final class BytecodeShapeMapMerge {
    private BytecodeShapeMapMerge() {
    }

    static PersistentShapeMap.MergeTransition mergeTransition(
            PersistentShapeMap left, PersistentShapeMap right) {
        return PersistentShapeMap.mergeTransition(left, right);
    }

    static PersistentShapeMap.Merge16RightTransition merge16RightTransition(
            PersistentShapeMap left, PersistentShapeMap16 right) {
        return PersistentShapeMap.mergeTransition(left, right);
    }

    static PersistentShapeMap16.Merge16Transition merge16Transition(
            PersistentShapeMap16 left, PersistentShapeMap right) {
        return PersistentShapeMap16.mergeTransition(left, right);
    }

    static PersistentShapeMap16.Merge16x16Transition merge16x16Transition(
            PersistentShapeMap16 left, PersistentShapeMap16 right) {
        return PersistentShapeMap16.mergeTransition(left, right);
    }

    /**
     * {@code clojure.core/merge} for exactly two arguments: {@code nil} if both are nil;
     * otherwise {@code (conj (or left {}) right)}.
     */
    static Object genericMerge2(Object left, Object right) {
        if (left == null && right == null) {
            return null;
        }
        Object base = left == null ? PersistentShapeMap.EMPTY : left;
        if (right == null) {
            return base;
        }
        if (base instanceof IPersistentCollection coll) {
            return coll.cons(right);
        }
        return RT.var("clojure.core", "merge").invoke(left, right);
    }

    /** {@code (merge nil right)} once {@code right} is known non-null. */
    static Object mergeNullLeft(Object right) {
        if (right == null) {
            return null;
        }
        if (right instanceof IPersistentMap) {
            return PersistentShapeMap.EMPTY.cons(right);
        }
        return genericMerge2(null, right);
    }
}
