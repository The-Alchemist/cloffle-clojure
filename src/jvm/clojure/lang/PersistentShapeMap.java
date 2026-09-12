/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import java.io.Serializable;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * Shape-based immutable persistent map for small keyword-only maps (<= 8 keys).
 * Enables GraalVM Partial Escape Analysis (PEA) and scalar replacement by using
 * direct object fields.  Slots follow construction / insertion order.
 */
@ValueType
public class PersistentShapeMap extends APersistentMap implements IObj, IEditableCollection, IMapIterable, IKVReduce, IDrop, IKeywordLookup, IReduce {

    private static final long serialVersionUID = 7712849182371928374L;

    public static final PersistentShapeMap EMPTY = new PersistentShapeMap();
    public static final int MAX_SHAPE_KEYS = 8;

    public final MapShape shape;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7;
    private final IPersistentMap _meta;

    // Accessors bridging shape fields for code that reads k0..k7 / count directly.
    public int getCount() { return shape.count; }
    public Keyword getK0() { return shape.k0; }
    public Keyword getK1() { return shape.k1; }
    public Keyword getK2() { return shape.k2; }
    public Keyword getK3() { return shape.k3; }
    public Keyword getK4() { return shape.k4; }
    public Keyword getK5() { return shape.k5; }
    public Keyword getK6() { return shape.k6; }
    public Keyword getK7() { return shape.k7; }

    public PersistentShapeMap() {
        this._meta = null;
        this.shape = MapShape.EMPTY;
        this.v0 = null; this.v1 = null; this.v2 = null; this.v3 = null;
        this.v4 = null; this.v5 = null; this.v6 = null; this.v7 = null;
    }

    /** Primary constructor: schema comes from a MapShape, values are flat fields. */
    public PersistentShapeMap(IPersistentMap meta, MapShape shape,
                              Object v0, Object v1, Object v2, Object v3,
                              Object v4, Object v5, Object v6, Object v7) {
        this._meta = meta;
        this.shape = shape;
        this.v0 = v0; this.v1 = v1; this.v2 = v2; this.v3 = v3;
        this.v4 = v4; this.v5 = v5; this.v6 = v6; this.v7 = v7;
    }

    /**
     * Legacy 18-argument constructor. Builds a MapShape from the keywords in
     * slot order and delegates. Retained for compatibility with callers that have
     * not yet been migrated (PersistentShapeMap16 promote/demote, tests, etc.).
     */
    public PersistentShapeMap(IPersistentMap meta, int count,
                              Keyword k0, Object v0,
                              Keyword k1, Object v1,
                              Keyword k2, Object v2,
                              Keyword k3, Object v3,
                              Keyword k4, Object v4,
                              Keyword k5, Object v5,
                              Keyword k6, Object v6,
                              Keyword k7, Object v7) {
        this._meta = meta;
        this.shape = MapShape.fromKeys(count, k0, k1, k2, k3, k4, k5, k6, k7);
        this.v0 = v0; this.v1 = v1; this.v2 = v2; this.v3 = v3;
        this.v4 = v4; this.v5 = v5; this.v6 = v6; this.v7 = v7;
    }

    public static boolean canBeShapeMap(Object[] init) {
        if (init == null || init.length > MAX_SHAPE_KEYS * 2 || (init.length & 1) != 0)
            return false;
        for (int i = 0; i < init.length; i += 2) {
            if (!(init[i] instanceof Keyword))
                return false;
        }
        return true;
    }

    public static PersistentShapeMap create(Keyword k0, Object v0) {
        if (k0 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        return new PersistentShapeMap(null, 1,
                k0, v0, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1) {
        if (k0 == null || k1 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        if (k0 == k1) throw new IllegalArgumentException("Duplicate key: " + k0);
        return new PersistentShapeMap(null, 2,
                k0, v0, k1, v1, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2) {
        if (k0 == null || k1 == null || k2 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        if (k0 == k1 || k0 == k2 || k1 == k2) throw new IllegalArgumentException("Duplicate key");
        return new PersistentShapeMap(null, 3,
                k0, v0, k1, v1, k2, v2, null, null, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3) {
        if (k0 == null || k1 == null || k2 == null || k3 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        if (k0 == k1 || k0 == k2 || k0 == k3 || k1 == k2 || k1 == k3 || k2 == k3) throw new IllegalArgumentException("Duplicate key");
        return new PersistentShapeMap(null, 4,
                k0, v0, k1, v1, k2, v2, k3, v3, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4) {
        return shape5(k0, k1, k2, k3, k4).create(v0, v1, v2, v3, v4);
    }
    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5) {
        return shape6(k0, k1, k2, k3, k4, k5).create(v0, v1, v2, v3, v4, v5);
    }
    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6) {
        return shape7(k0, k1, k2, k3, k4, k5, k6).create(v0, v1, v2, v3, v4, v5, v6);
    }
    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6, Keyword k7, Object v7) {
        return shape8(k0, k1, k2, k3, k4, k5, k6, k7).create(v0, v1, v2, v3, v4, v5, v6, v7);
    }

    /**
     * Cached 1-key shape: interned keyword plus precomputed masks.
     * Truffle {@code @Cached} instances are compilation-final.
     */
    @ValueType
    public static final class Shape1 {
        public final Keyword k0;
        public final MapShape shape;

        public Shape1(Keyword k0) {
            if (k0 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            this.k0 = k0;
            this.shape = MapShape.fromKeys(1, k0, null, null, null, null, null, null, null);
        }

        public PersistentShapeMap create(Object v0) {
            return new PersistentShapeMap(null, shape,
                    v0, null, null, null, null, null, null, null);
        }
    }

    /**
     * Cached 2-key shape in argument order.
     */
    @ValueType
    public static final class Shape2 {
        public final Keyword k0, k1;
        public final MapShape shape;

        public Shape2(Keyword a, Keyword b) {
            if (a == null || b == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b) throw new IllegalArgumentException("Duplicate key: " + a);
            this.k0 = a;
            this.k1 = b;
            this.shape = MapShape.fromKeys(2, k0, k1, null, null, null, null, null, null);
        }

        public PersistentShapeMap create(Object v0, Object v1) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, null, null, null, null, null, null);
        }
    }

    /**
     * Cached 3-key shape in argument order.
     */
    @ValueType
    public static final class Shape3 {
        public final Keyword k0, k1, k2;
        public final MapShape shape;

        public Shape3(Keyword a, Keyword b, Keyword c) {
            if (a == null || b == null || c == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || b == c) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.shape = MapShape.fromKeys(3, k0, k1, k2, null, null, null, null, null);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, null, null, null, null, null);
        }
    }

    /**
     * Cached 4-key shape in argument order.
     */
    @ValueType
    public static final class Shape4 {
        public final Keyword k0, k1, k2, k3;
        public final MapShape shape;

        public Shape4(Keyword a, Keyword b, Keyword c, Keyword d) {
            if (a == null || b == null || c == null || d == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || b == c || b == d || c == d) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.k3 = d;
            this.shape = MapShape.fromKeys(4, k0, k1, k2, k3, null, null, null, null);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, v3, null, null, null, null);
        }
    }

    public static Shape1 shape1(Keyword k0) {
        return new Shape1(k0);
    }

    public static Shape2 shape2(Keyword k0, Keyword k1) {
        return new Shape2(k0, k1);
    }

    public static Shape3 shape3(Keyword k0, Keyword k1, Keyword k2) {
        return new Shape3(k0, k1, k2);
    }

    public static Shape4 shape4(Keyword k0, Keyword k1, Keyword k2, Keyword k3) {
        return new Shape4(k0, k1, k2, k3);
    }

    public static Shape5 shape5(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4) {
        return new Shape5(k0, k1, k2, k3, k4);
    }
    public static Shape6 shape6(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5) {
        return new Shape6(k0, k1, k2, k3, k4, k5);
    }
    public static Shape7 shape7(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6) {
        return new Shape7(k0, k1, k2, k3, k4, k5, k6);
    }
    public static Shape8 shape8(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        return new Shape8(k0, k1, k2, k3, k4, k5, k6, k7);
    }
    /**
     * Cached 5-key shape in argument order.
     */
    @ValueType
    public static final class Shape5 {
        public final Keyword k0, k1, k2, k3, k4;
        public final MapShape shape;

        public Shape5(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e) {
            if (a == null || b == null || c == null || d == null || e == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || b == c || b == d || b == e || c == d || c == e || d == e) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.k3 = d;
            this.k4 = e;
            this.shape = MapShape.fromKeys(5, k0, k1, k2, k3, k4, null, null, null);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, v3, v4, null, null, null);
        }
    }

    /**
     * Cached 6-key shape in argument order.
     */
    @ValueType
    public static final class Shape6 {
        public final Keyword k0, k1, k2, k3, k4, k5;
        public final MapShape shape;

        public Shape6(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f) {
            if (a == null || b == null || c == null || d == null || e == null || f == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || b == c || b == d || b == e || b == f || c == d || c == e || c == f || d == e || d == f || e == f) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.k3 = d;
            this.k4 = e;
            this.k5 = f;
            this.shape = MapShape.fromKeys(6, k0, k1, k2, k3, k4, k5, null, null);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, v3, v4, v5, null, null);
        }
    }

    /**
     * Cached 7-key shape in argument order.
     */
    @ValueType
    public static final class Shape7 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6;
        public final MapShape shape;

        public Shape7(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g) {
            if (a == null || b == null || c == null || d == null || e == null || f == null || g == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || a == g || b == c || b == d || b == e || b == f || b == g || c == d || c == e || c == f || c == g || d == e || d == f || d == g || e == f || e == g || f == g) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.k3 = d;
            this.k4 = e;
            this.k5 = f;
            this.k6 = g;
            this.shape = MapShape.fromKeys(7, k0, k1, k2, k3, k4, k5, k6, null);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, v3, v4, v5, v6, null);
        }
    }

    /**
     * Cached 8-key shape in argument order.
     */
    @ValueType
    public static final class Shape8 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
        public final MapShape shape;

        public Shape8(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g, Keyword h) {
            if (a == null || b == null || c == null || d == null || e == null || f == null || g == null || h == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || a == g || a == h || b == c || b == d || b == e || b == f || b == g || b == h || c == d || c == e || c == f || c == g || c == h || d == e || d == f || d == g || d == h || e == f || e == g || e == h || f == g || f == h || g == h) throw new IllegalArgumentException("Duplicate key");
            this.k0 = a;
            this.k1 = b;
            this.k2 = c;
            this.k3 = d;
            this.k4 = e;
            this.k5 = f;
            this.k6 = g;
            this.k7 = h;
            this.shape = MapShape.fromKeys(8, k0, k1, k2, k3, k4, k5, k6, k7);
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            return new PersistentShapeMap(null, shape,
                    v0, v1, v2, v3, v4, v5, v6, v7);
        }
    }

    public static PersistentShapeMap createWithCheck(Object[] init) {
        int pairCount = init.length / 2;
        if (pairCount == 0) return EMPTY;

        Keyword[] keys = new Keyword[pairCount];
        Object[] vals = new Object[pairCount];
        for (int i = 0; i < pairCount; i++) {
            keys[i] = (Keyword) init[i * 2];
            vals[i] = init[i * 2 + 1];
        }
        for (int i = 0; i < pairCount; i++) {
            for (int j = i + 1; j < pairCount; j++) {
                if (keys[i] == keys[j]) {
                    throw new IllegalArgumentException("Duplicate key: " + keys[i]);
                }
            }
        }
        return createFromKeys(null, pairCount, keys, vals);
    }

    public static PersistentShapeMap createFromKeys(IPersistentMap meta, int pairCount, Keyword[] keys, Object[] vals) {
        Keyword pk0 = pairCount > 0 ? keys[0] : null; Object pv0 = pairCount > 0 ? vals[0] : null;
        Keyword pk1 = pairCount > 1 ? keys[1] : null; Object pv1 = pairCount > 1 ? vals[1] : null;
        Keyword pk2 = pairCount > 2 ? keys[2] : null; Object pv2 = pairCount > 2 ? vals[2] : null;
        Keyword pk3 = pairCount > 3 ? keys[3] : null; Object pv3 = pairCount > 3 ? vals[3] : null;
        Keyword pk4 = pairCount > 4 ? keys[4] : null; Object pv4 = pairCount > 4 ? vals[4] : null;
        Keyword pk5 = pairCount > 5 ? keys[5] : null; Object pv5 = pairCount > 5 ? vals[5] : null;
        Keyword pk6 = pairCount > 6 ? keys[6] : null; Object pv6 = pairCount > 6 ? vals[6] : null;
        Keyword pk7 = pairCount > 7 ? keys[7] : null; Object pv7 = pairCount > 7 ? vals[7] : null;
        return new PersistentShapeMap(meta, pairCount, pk0, pv0, pk1, pv1, pk2, pv2, pk3, pv3, pk4, pv4, pk5, pv5, pk6, pv6, pk7, pv7);
    }

    public Keyword getKey(int i) {
        return shape.getKey(i);
    }

    public Object getVal(int i) {
        return switch (i) {
            case 0 -> v0;
            case 1 -> v1;
            case 2 -> v2;
            case 3 -> v3;
            case 4 -> v4;
            case 5 -> v5;
            case 6 -> v6;
            case 7 -> v7;
            default -> null;
        };
    }

    /**
     * A bytecode-node-local, immutable assoc plan for one keyword and one incoming key layout.
     * The Truffle DSL caches these descriptors, so presence, slot, insertion position, and masks
     * are computed once instead of on every execution.
     */
    @ValueType
    public abstract static class AssocTransition {
        public final Keyword keyword;
        public final MapShape fromShape;

        private AssocTransition(PersistentShapeMap map, Keyword keyword) {
            this.keyword = keyword;
            this.fromShape = map.shape;
        }

        /** Keyword identity plus key-layout equality; shapes are not canonicalized. */
        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword && fromShape.sameKeys(map.shape);
        }

        public abstract IPersistentMap apply(PersistentShapeMap map, Object val);
    }

    private static final class UpdateTransition extends AssocTransition {
        private final byte slot;

        private UpdateTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;
        }

        @Override
        public PersistentShapeMap apply(PersistentShapeMap map, Object val) {
            Object nv0 = map.v0, nv1 = map.v1, nv2 = map.v2, nv3 = map.v3;
            Object nv4 = map.v4, nv5 = map.v5, nv6 = map.v6, nv7 = map.v7;
            switch (slot) {
                case 0 -> nv0 = val;
                case 1 -> nv1 = val;
                case 2 -> nv2 = val;
                case 3 -> nv3 = val;
                case 4 -> nv4 = val;
                case 5 -> nv5 = val;
                case 6 -> nv6 = val;
                case 7 -> nv7 = val;
                default -> throw new AssertionError("Invalid ShapeMap update slot: " + slot);
            }
            return new PersistentShapeMap(map.meta(), fromShape,
                    nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
        }
    }

    private static class InsertTransition extends AssocTransition {
        protected final byte slot;
        protected final MapShape toShape;

        private InsertTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            this(map, keyword, slot, true);
        }

        /** When computeToShape is false, toShape is null (used by Promote16Transition). */
        protected InsertTransition(PersistentShapeMap map, Keyword keyword, int slot, boolean computeToShape) {
            super(map, keyword);
            this.slot = (byte) slot;
            this.toShape = computeToShape ? fromShape.addKey(keyword, slot) : null;
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap map, Object val) {
            Object nv0 = map.v0, nv1 = map.v1, nv2 = map.v2, nv3 = map.v3;
            Object nv4 = map.v4, nv5 = map.v5, nv6 = map.v6, nv7 = map.v7;
            switch (slot) {
                case 0 -> nv0 = val;
                case 1 -> nv1 = val;
                case 2 -> nv2 = val;
                case 3 -> nv3 = val;
                case 4 -> nv4 = val;
                case 5 -> nv5 = val;
                case 6 -> nv6 = val;
                case 7 -> nv7 = val;
                default -> throw new AssertionError("Invalid ShapeMap insert slot: " + slot);
            }
            return new PersistentShapeMap(map.meta(), toShape,
                    nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
        }
    }

    private static final class Promote16Transition extends InsertTransition {
        private Promote16Transition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword, slot, false);
        }

        @Override
        public PersistentShapeMap16 apply(PersistentShapeMap map, Object val) {
            MapShape s = fromShape;
            return shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3,
                    s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7, keyword, val);
        }

        private PersistentShapeMap16 shape16(
                PersistentShapeMap map,
                Keyword nk0, Object nv0, Keyword nk1, Object nv1, Keyword nk2, Object nv2,
                Keyword nk3, Object nv3, Keyword nk4, Object nv4, Keyword nk5, Object nv5,
                Keyword nk6, Object nv6, Keyword nk7, Object nv7, Keyword nk8, Object nv8) {
            return new PersistentShapeMap16(map.meta(), 9,
                    nk0, nv0, nk1, nv1, nk2, nv2, nk3, nv3, nk4, nv4, nk5, nv5, nk6, nv6, nk7, nv7, nk8, nv8,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public static AssocTransition assocTransition(PersistentShapeMap map, Keyword keyword) {
        int existingSlot = map.shape.indexOf(keyword);
        if (existingSlot >= 0) {
            return new UpdateTransition(map, keyword, existingSlot);
        }

        int insertSlot = map.shape.insertSlot(keyword);
        return map.shape.count == MAX_SHAPE_KEYS
                ? new Promote16Transition(map, keyword, insertSlot)
                : new InsertTransition(map, keyword, insertSlot);
    }

    /**
     * A bytecode-node-local, immutable dissoc plan for one keyword and one incoming key layout.
     * The Truffle DSL caches these descriptors, avoiding key shuffling, bitmask recalculation,
     * and multi-case branching during compiled (dissoc m :k) operations.
     */
    @ValueType
    public abstract static class DissocTransition {
        public final Keyword keyword;
        public final MapShape fromShape;

        protected DissocTransition(PersistentShapeMap map, Keyword keyword) {
            this.keyword = keyword;
            this.fromShape = map.shape;
        }

        /** Keyword identity plus key-layout equality; shapes are not canonicalized. */
        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword && fromShape.sameKeys(map.shape);
        }

        public abstract IPersistentMap apply(PersistentShapeMap map);
    }

    private static final class NoOpDissocTransition extends DissocTransition {
        private NoOpDissocTransition(PersistentShapeMap map, Keyword keyword) {
            super(map, keyword);
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap map) {
            return map;
        }
    }

    private static final class EmptyDissocTransition extends DissocTransition {
        private EmptyDissocTransition(PersistentShapeMap map, Keyword keyword) {
            super(map, keyword);
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap map) {
            return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(map.meta());
        }
    }

    private static final class RemoveTransition extends DissocTransition {
        private final byte slot;
        private final MapShape toShape;

        private RemoveTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;
            this.toShape = fromShape.removeKey(slot);
        }

        @Override
        public PersistentShapeMap apply(PersistentShapeMap map) {
            Object nv0 = map.v0, nv1 = map.v1, nv2 = map.v2, nv3 = map.v3;
            Object nv4 = map.v4, nv5 = map.v5, nv6 = map.v6, nv7 = map.v7;
            switch (slot) {
                case 0 -> { nv0 = nv1; nv1 = nv2; nv2 = nv3; nv3 = nv4; nv4 = nv5; nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 1 -> { nv1 = nv2; nv2 = nv3; nv3 = nv4; nv4 = nv5; nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 2 -> { nv2 = nv3; nv3 = nv4; nv4 = nv5; nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 3 -> { nv3 = nv4; nv4 = nv5; nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 4 -> { nv4 = nv5; nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 5 -> { nv5 = nv6; nv6 = nv7; nv7 = null; }
                case 6 -> { nv6 = nv7; nv7 = null; }
                case 7 -> { nv7 = null; }
                default -> throw new AssertionError("Invalid ShapeMap remove slot: " + slot);
            }
            return new PersistentShapeMap(map.meta(), toShape,
                    nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
        }
    }

    public static DissocTransition dissocTransition(PersistentShapeMap map, Keyword keyword) {
        if (map.shape.count == 0) {
            return new NoOpDissocTransition(map, keyword);
        }
        int slot = map.shape.indexOf(keyword);

        if (slot < 0) {
            return new NoOpDissocTransition(map, keyword);
        }
        if (map.shape.count == 1) {
            return new EmptyDissocTransition(map, keyword);
        }
        return new RemoveTransition(map, keyword, slot);
    }

    @Override
    public int count() {
        return shape.count;
    }

    // Slots at or past count always hold a null key, and a Keyword argument is never null,
    // so identity compares alone cannot match an unused slot: no count guards needed.
    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Keyword kw) {
            return shape.indexOf(kw) >= 0;
        }
        return false;
    }

    @Override
    public IMapEntry entryAt(Object key) {
        if (key instanceof Keyword kw) {
            int slot = shape.indexOf(kw);
            if (slot >= 0) return (IMapEntry) MapEntry.create(shape.getKey(slot), getVal(slot));
        }
        return null;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (key instanceof Keyword kw) {
            int slot = shape.indexOf(kw);
            if (slot >= 0) return getVal(slot);
        }
        return notFound;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        if (!(key instanceof Keyword kw)) {
            return assocNonKeyword(key, val);
        }

        // Check if key already exists
        int existingSlot = shape.indexOf(kw);

        if (existingSlot >= 0) {
            Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
            switch (existingSlot) {
                case 0 -> nv0 = val;
                case 1 -> nv1 = val;
                case 2 -> nv2 = val;
                case 3 -> nv3 = val;
                case 4 -> nv4 = val;
                case 5 -> nv5 = val;
                case 6 -> nv6 = val;
                case 7 -> nv7 = val;
                default -> {
                    return this;
                }
            }
            return new PersistentShapeMap(meta(), shape,
                    nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
        }

        // Insertion: delegate slot computation to shape
        int ins = shape.insertSlot(kw);

        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, shape.count == MAX_SHAPE_KEYS)) {
            return assocPromote16(kw, val, ins);
        }

        MapShape newShape = shape.addKey(kw, ins);
        Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
        switch (ins) {
            case 0 -> nv0 = val;
            case 1 -> nv1 = val;
            case 2 -> nv2 = val;
            case 3 -> nv3 = val;
            case 4 -> nv4 = val;
            case 5 -> nv5 = val;
            case 6 -> nv6 = val;
            case 7 -> nv7 = val;
        }
        return new PersistentShapeMap(meta(), newShape,
                nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
    }

    @TruffleBoundary
    private IPersistentMap assocNonKeyword(Object key, Object val) {
        Object[] arr = toArray();
        return new PersistentArrayMap(meta(), arr).assoc(key, val);
    }

    private PersistentShapeMap16 assocPromote16(Keyword kw, Object val, int ins) {
        return new PersistentShapeMap16(meta(), 9,
                shape.k0, v0, shape.k1, v1, shape.k2, v2, shape.k3, v3,
                shape.k4, v4, shape.k5, v5, shape.k6, v6, shape.k7, v7,
                kw, val,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public IPersistentMap assocEx(Object key, Object val) {
        if (containsKey(key)) {
            throw Util.runtimeException("Key already present");
        }
        return assoc(key, val);
    }

    @Override
    public IPersistentMap without(Object key) {
        if (!(key instanceof Keyword kw)) {
            return this;
        }

        int matchIdx = shape.indexOf(kw);
        if (matchIdx < 0) return this;

        if (shape.count == 1) {
            return (IPersistentMap) EMPTY.withMeta(meta());
        }

        MapShape newShape = shape.removeKey(matchIdx);
        Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
        switch (matchIdx) {
            case 0 -> { nv0 = v1; nv1 = v2; nv2 = v3; nv3 = v4; nv4 = v5; nv5 = v6; nv6 = v7; nv7 = null; }
            case 1 -> { nv1 = v2; nv2 = v3; nv3 = v4; nv4 = v5; nv5 = v6; nv6 = v7; nv7 = null; }
            case 2 -> { nv2 = v3; nv3 = v4; nv4 = v5; nv5 = v6; nv6 = v7; nv7 = null; }
            case 3 -> { nv3 = v4; nv4 = v5; nv5 = v6; nv6 = v7; nv7 = null; }
            case 4 -> { nv4 = v5; nv5 = v6; nv6 = v7; nv7 = null; }
            case 5 -> { nv5 = v6; nv6 = v7; nv7 = null; }
            case 6 -> { nv6 = v7; nv7 = null; }
            case 7 -> { nv7 = null; }
            default -> { return this; }
        }
        return new PersistentShapeMap(meta(), newShape,
                nv0, nv1, nv2, nv3, nv4, nv5, nv6, nv7);
    }

    @Override
    public IPersistentMap empty() {
        return (IPersistentMap) EMPTY.withMeta(meta());
    }

    public Object[] toArray() {
        Object[] arr = new Object[shape.count * 2];
        for (int i = 0; i < shape.count; i++) {
            arr[i * 2] = getKey(i);
            arr[i * 2 + 1] = getVal(i);
        }
        return arr;
    }

    @Override
    public Iterator iterator() {
        return new ShapeMapIter(this, APersistentMap.MAKE_ENTRY);
    }

    public Iterator keyIterator() {
        return new ShapeMapIter(this, APersistentMap.MAKE_KEY);
    }

    public Iterator valIterator() {
        return new ShapeMapIter(this, APersistentMap.MAKE_VAL);
    }

    @Override
    public ISeq seq() {
        if (shape.count > 0) {
            return new ShapeMapSeq(this, 0);
        }
        return null;
    }

    @Override
    public Sequential drop(int n) {
        if (shape.count > 0) {
            return ((ShapeMapSeq) seq()).drop(n);
        }
        return null;
    }

    @Override
    public IPersistentMap meta() {
        return _meta;
    }

    @Override
    public PersistentShapeMap withMeta(IPersistentMap meta) {
        if (meta() == meta)
            return this;
        return new PersistentShapeMap(meta, shape,
                v0, v1, v2, v3, v4, v5, v6, v7);
    }

    @Override
    public Object kvreduce(IFn f, Object init) {
        Object acc = init;
        switch (shape.count) {
            case 0: return acc;
            case 1: {
                acc = f.invoke(acc, shape.k0, v0);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 2: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 3: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 4: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k3, v3);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 5: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k4, v4);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 6: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k5, v5);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 7: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k5, v5);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k6, v6);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 8: {
                acc = f.invoke(acc, shape.k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k5, v5);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k6, v6);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, shape.k7, v7);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            default:
                for (int i = 0; i < shape.count; i++) {
                    acc = f.invoke(acc, getKey(i), getVal(i));
                    if (RT.isReduced(acc))
                        return ((IDeref) acc).deref();
                }
                return acc;
        }
    }

    @Override
    public Object reduce(IFn f, Object start) {
        Object acc = start;
        switch (shape.count) {
            case 0: return acc;
            case 1: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 2: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 3: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 4: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k3, v3));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 5: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k4, v4));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 6: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k5, v5));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 7: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k5, v5));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k6, v6));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 8: {
                acc = f.invoke(acc, MapEntry.create(shape.k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k5, v5));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k6, v6));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(shape.k7, v7));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            default:
                for (int i = 0; i < shape.count; i++) {
                    acc = f.invoke(acc, MapEntry.create(getKey(i), getVal(i)));
                    if (RT.isReduced(acc))
                        return ((IDeref) acc).deref();
                }
                return acc;
        }
    }

    @Override
    public Object reduce(IFn f) {
        if (shape.count == 0) return f.invoke();
        Object acc = MapEntry.create(shape.k0, v0);
        for (int i = 1; i < shape.count; i++) {
            if (RT.isReduced(acc)) return ((IDeref) acc).deref();
            acc = f.invoke(acc, MapEntry.create(getKey(i), getVal(i)));
        }
        return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
    }

    @ValueType
    static final class ShapeMapSeq extends ASeq implements Counted, IReduce, IDrop {
        final PersistentShapeMap map;
        final int i;

        ShapeMapSeq(PersistentShapeMap map, int i) {
            this.map = map;
            this.i = i;
        }

        ShapeMapSeq(IPersistentMap meta, PersistentShapeMap map, int i) {
            super(meta);
            this.map = map;
            this.i = i;
        }

        @Override
        public Object first() {
            return MapEntry.create(map.getKey(i), map.getVal(i));
        }

        @Override
        public ISeq next() {
            if (i + 1 < map.shape.count)
                return new ShapeMapSeq(map, i + 1);
            return null;
        }

        @Override
        public int count() {
            return map.shape.count - i;
        }

        @Override
        public Sequential drop(int n) {
            if (n <= 0) return this;
            if (i + n < map.shape.count) {
                return new ShapeMapSeq(map, i + n);
            }
            return null;
        }

        @Override
        public Obj withMeta(IPersistentMap meta) {
            if (meta() == meta) return this;
            return new ShapeMapSeq(meta, map, i);
        }

        @Override
        public Object reduce(IFn f) {
            if (i < map.shape.count) {
                Object acc = MapEntry.create(map.getKey(i), map.getVal(i));
                for (int j = i + 1; j < map.shape.count; j++) {
                    acc = f.invoke(acc, MapEntry.create(map.getKey(j), map.getVal(j)));
                    if (RT.isReduced(acc))
                        return ((IDeref) acc).deref();
                }
                return acc;
            } else {
                return f.invoke();
            }
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object acc = start;
            for (int j = i; j < map.shape.count; j++) {
                acc = f.invoke(acc, MapEntry.create(map.getKey(j), map.getVal(j)));
                if (RT.isReduced(acc))
                    return ((IDeref) acc).deref();
            }
            return acc;
        }
    }

    static final class ShapeMapIter implements Iterator {
        final PersistentShapeMap map;
        final IFn f;
        int i = 0;

        ShapeMapIter(PersistentShapeMap map, IFn f) {
            this.map = map;
            this.f = f;
        }

        @Override
        public boolean hasNext() {
            return i < map.shape.count;
        }

        @Override
        public Object next() {
            if (i >= map.shape.count) throw new NoSuchElementException();
            Object ret = f.invoke(map.getKey(i), map.getVal(i));
            i++;
            return ret;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public ILookupThunk getLookupThunk(final Keyword k) {
        int slot = shape.indexOf(k);
        if (slot < 0) return null;
        final int targetSlot = slot;
        final MapShape cachedShape = shape;
        return new ILookupThunk() {
            @Override
            public Object get(Object target) {
                return target instanceof PersistentShapeMap sm && cachedShape.sameKeys(sm.shape) ? sm.getVal(targetSlot) : this;
            }
        };
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }
}
