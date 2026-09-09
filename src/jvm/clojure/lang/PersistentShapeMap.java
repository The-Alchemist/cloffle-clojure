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
 * direct object fields and canonical Keyword.id ordering.
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
     * Legacy 18-argument constructor. Builds a MapShape from the loose keywords
     * and delegates. Retained for compatibility with callers that have not yet
     * been migrated (PersistentShapeMap16 promote/demote, tests, etc.).
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
        this.shape = MapShape.fromSorted(count, k0, k1, k2, k3, k4, k5, k6, k7);
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
        if (k0.id > k1.id) {
            Keyword tk = k0; k0 = k1; k1 = tk;
            Object tv = v0; v0 = v1; v1 = tv;
        }
        return new PersistentShapeMap(null, 2,
                k0, v0, k1, v1, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2) {
        if (k0 == null || k1 == null || k2 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        if (k0 == k1 || k0 == k2 || k1 == k2) throw new IllegalArgumentException("Duplicate key");
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; Object tv = v0; v0 = v1; v1 = tv; }
        if (k1.id > k2.id) { Keyword tk = k1; k1 = k2; k2 = tk; Object tv = v1; v1 = v2; v2 = tv; }
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; Object tv = v0; v0 = v1; v1 = tv; }
        return new PersistentShapeMap(null, 3,
                k0, v0, k1, v1, k2, v2, null, null, null, null, null, null, null, null, null, null);
    }

    public static PersistentShapeMap create(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3) {
        if (k0 == null || k1 == null || k2 == null || k3 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
        if (k0 == k1 || k0 == k2 || k0 == k3 || k1 == k2 || k1 == k3 || k2 == k3) throw new IllegalArgumentException("Duplicate key");
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; Object tv = v0; v0 = v1; v1 = tv; }
        if (k2.id > k3.id) { Keyword tk = k2; k2 = k3; k3 = tk; Object tv = v2; v2 = v3; v3 = tv; }
        if (k0.id > k2.id) { Keyword tk = k0; k0 = k2; k2 = tk; Object tv = v0; v0 = v2; v2 = tv; }
        if (k1.id > k3.id) { Keyword tk = k1; k1 = k3; k3 = tk; Object tv = v1; v1 = v3; v3 = tv; }
        if (k1.id > k2.id) { Keyword tk = k1; k1 = k2; k2 = tk; Object tv = v1; v1 = v2; v2 = tv; }
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

        public Shape1(Keyword k0) {
            if (k0 == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            this.k0 = k0;
        }

        public PersistentShapeMap create(Object v0) {
            return new PersistentShapeMap(null, 1,
                    k0, v0, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Cached 2-key shape: canonical Keyword.id order and whether input values must swap.
     */
    @ValueType
    public static final class Shape2 {
        public final Keyword k0, k1;
        public final boolean swapped;

        public Shape2(Keyword a, Keyword b) {
            if (a == null || b == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b) throw new IllegalArgumentException("Duplicate key: " + a);
            this.swapped = a.id > b.id;
            this.k0 = swapped ? b : a;
            this.k1 = swapped ? a : b;
        }

        public PersistentShapeMap create(Object v0, Object v1) {
            Object sv0 = swapped ? v1 : v0;
            Object sv1 = swapped ? v0 : v1;
            return new PersistentShapeMap(null, 2,
                    k0, sv0, k1, sv1, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Cached 3-key shape. {@code p0..p2} are original input indices for sorted slots 0..2.
     */
    @ValueType
    public static final class Shape3 {
        public final Keyword k0, k1, k2;
        public final byte p0, p1, p2;

        public Shape3(Keyword a, Keyword b, Keyword c) {
            if (a == null || b == null || c == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || b == c) throw new IllegalArgumentException("Duplicate key");
            Keyword sk0 = a, sk1 = b, sk2 = c;
            byte i0 = 0, i1 = 1, i2 = 2;
            if (sk0.id > sk1.id) { Keyword tk = sk0; sk0 = sk1; sk1 = tk; byte ti = i0; i0 = i1; i1 = ti; }
            if (sk1.id > sk2.id) { Keyword tk = sk1; sk1 = sk2; sk2 = tk; byte ti = i1; i1 = i2; i2 = ti; }
            if (sk0.id > sk1.id) { Keyword tk = sk0; sk0 = sk1; sk1 = tk; byte ti = i0; i0 = i1; i1 = ti; }
            this.k0 = sk0;
            this.k1 = sk1;
            this.k2 = sk2;
            this.p0 = i0;
            this.p1 = i1;
            this.p2 = i2;
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2) {
            return new PersistentShapeMap(null, 3,
                    k0, pick3(p0, v0, v1, v2),
                    k1, pick3(p1, v0, v1, v2),
                    k2, pick3(p2, v0, v1, v2),
                    null, null, null, null, null, null, null, null, null, null);
        }

        private static Object pick3(byte p, Object v0, Object v1, Object v2) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                default -> v2;
            };
        }
    }

    /**
     * Cached 4-key shape. {@code p0..p3} are original input indices for sorted slots 0..3.
     */
    @ValueType
    public static final class Shape4 {
        public final Keyword k0, k1, k2, k3;
        public final byte p0, p1, p2, p3;

        public Shape4(Keyword a, Keyword b, Keyword c, Keyword d) {
            if (a == null || b == null || c == null || d == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || b == c || b == d || c == d) throw new IllegalArgumentException("Duplicate key");
            Keyword sk0 = a, sk1 = b, sk2 = c, sk3 = d;
            byte i0 = 0, i1 = 1, i2 = 2, i3 = 3;
            if (sk0.id > sk1.id) { Keyword tk = sk0; sk0 = sk1; sk1 = tk; byte ti = i0; i0 = i1; i1 = ti; }
            if (sk2.id > sk3.id) { Keyword tk = sk2; sk2 = sk3; sk3 = tk; byte ti = i2; i2 = i3; i3 = ti; }
            if (sk0.id > sk2.id) { Keyword tk = sk0; sk0 = sk2; sk2 = tk; byte ti = i0; i0 = i2; i2 = ti; }
            if (sk1.id > sk3.id) { Keyword tk = sk1; sk1 = sk3; sk3 = tk; byte ti = i1; i1 = i3; i3 = ti; }
            if (sk1.id > sk2.id) { Keyword tk = sk1; sk1 = sk2; sk2 = tk; byte ti = i1; i1 = i2; i2 = ti; }
            this.k0 = sk0;
            this.k1 = sk1;
            this.k2 = sk2;
            this.k3 = sk3;
            this.p0 = i0;
            this.p1 = i1;
            this.p2 = i2;
            this.p3 = i3;
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3) {
            return new PersistentShapeMap(null, 4,
                    k0, pick4(p0, v0, v1, v2, v3),
                    k1, pick4(p1, v0, v1, v2, v3),
                    k2, pick4(p2, v0, v1, v2, v3),
                    k3, pick4(p3, v0, v1, v2, v3),
                    null, null, null, null, null, null, null, null);
        }

        private static Object pick4(byte p, Object v0, Object v1, Object v2, Object v3) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                default -> v3;
            };
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
     * Cached 5-key shape. {@code p0..p4} are original input indices for sorted slots 0..4.
     */
    @ValueType
    public static final class Shape5 {
        public final Keyword k0, k1, k2, k3, k4;
        public final byte p0, p1, p2, p3, p4;

        public Shape5(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e) {
            if (a == null || b == null || c == null || d == null || e == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || b == c || b == d || b == e || c == d || c == e || d == e) throw new IllegalArgumentException("Duplicate key");
            Keyword[] ks = new Keyword[]{a, b, c, d, e};
            byte[] idx = new byte[]{0, 1, 2, 3, 4};
            for (int i = 1; i < 5; i++) {
                Keyword key = ks[i];
                byte id = idx[i];
                int j = i - 1;
                while (j >= 0 && ks[j].id > key.id) {
                    ks[j + 1] = ks[j];
                    idx[j + 1] = idx[j];
                    j--;
                }
                ks[j + 1] = key;
                idx[j + 1] = id;
            }
            this.k0 = ks[0];
            this.k1 = ks[1];
            this.k2 = ks[2];
            this.k3 = ks[3];
            this.k4 = ks[4];
            this.p0 = idx[0];
            this.p1 = idx[1];
            this.p2 = idx[2];
            this.p3 = idx[3];
            this.p4 = idx[4];
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4) {
            return new PersistentShapeMap(null, 5,
                    k0, pick5(p0, v0, v1, v2, v3, v4), k1, pick5(p1, v0, v1, v2, v3, v4), k2, pick5(p2, v0, v1, v2, v3, v4), k3, pick5(p3, v0, v1, v2, v3, v4), k4, pick5(p4, v0, v1, v2, v3, v4), null, null, null, null, null, null);
        }

        private static Object pick5(byte p, Object v0, Object v1, Object v2, Object v3, Object v4) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> v4;
            };
        }
    }

    /**
     * Cached 6-key shape. {@code p0..p5} are original input indices for sorted slots 0..5.
     */
    @ValueType
    public static final class Shape6 {
        public final Keyword k0, k1, k2, k3, k4, k5;
        public final byte p0, p1, p2, p3, p4, p5;

        public Shape6(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f) {
            if (a == null || b == null || c == null || d == null || e == null || f == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || b == c || b == d || b == e || b == f || c == d || c == e || c == f || d == e || d == f || e == f) throw new IllegalArgumentException("Duplicate key");
            Keyword[] ks = new Keyword[]{a, b, c, d, e, f};
            byte[] idx = new byte[]{0, 1, 2, 3, 4, 5};
            for (int i = 1; i < 6; i++) {
                Keyword key = ks[i];
                byte id = idx[i];
                int j = i - 1;
                while (j >= 0 && ks[j].id > key.id) {
                    ks[j + 1] = ks[j];
                    idx[j + 1] = idx[j];
                    j--;
                }
                ks[j + 1] = key;
                idx[j + 1] = id;
            }
            this.k0 = ks[0];
            this.k1 = ks[1];
            this.k2 = ks[2];
            this.k3 = ks[3];
            this.k4 = ks[4];
            this.k5 = ks[5];
            this.p0 = idx[0];
            this.p1 = idx[1];
            this.p2 = idx[2];
            this.p3 = idx[3];
            this.p4 = idx[4];
            this.p5 = idx[5];
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            return new PersistentShapeMap(null, 6,
                    k0, pick6(p0, v0, v1, v2, v3, v4, v5), k1, pick6(p1, v0, v1, v2, v3, v4, v5), k2, pick6(p2, v0, v1, v2, v3, v4, v5), k3, pick6(p3, v0, v1, v2, v3, v4, v5), k4, pick6(p4, v0, v1, v2, v3, v4, v5), k5, pick6(p5, v0, v1, v2, v3, v4, v5), null, null, null, null);
        }

        private static Object pick6(byte p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                case 4 -> v4;
                default -> v5;
            };
        }
    }

    /**
     * Cached 7-key shape. {@code p0..p6} are original input indices for sorted slots 0..6.
     */
    @ValueType
    public static final class Shape7 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6;
        public final byte p0, p1, p2, p3, p4, p5, p6;

        public Shape7(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g) {
            if (a == null || b == null || c == null || d == null || e == null || f == null || g == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || a == g || b == c || b == d || b == e || b == f || b == g || c == d || c == e || c == f || c == g || d == e || d == f || d == g || e == f || e == g || f == g) throw new IllegalArgumentException("Duplicate key");
            Keyword[] ks = new Keyword[]{a, b, c, d, e, f, g};
            byte[] idx = new byte[]{0, 1, 2, 3, 4, 5, 6};
            for (int i = 1; i < 7; i++) {
                Keyword key = ks[i];
                byte id = idx[i];
                int j = i - 1;
                while (j >= 0 && ks[j].id > key.id) {
                    ks[j + 1] = ks[j];
                    idx[j + 1] = idx[j];
                    j--;
                }
                ks[j + 1] = key;
                idx[j + 1] = id;
            }
            this.k0 = ks[0];
            this.k1 = ks[1];
            this.k2 = ks[2];
            this.k3 = ks[3];
            this.k4 = ks[4];
            this.k5 = ks[5];
            this.k6 = ks[6];
            this.p0 = idx[0];
            this.p1 = idx[1];
            this.p2 = idx[2];
            this.p3 = idx[3];
            this.p4 = idx[4];
            this.p5 = idx[5];
            this.p6 = idx[6];
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            return new PersistentShapeMap(null, 7,
                    k0, pick7(p0, v0, v1, v2, v3, v4, v5, v6), k1, pick7(p1, v0, v1, v2, v3, v4, v5, v6), k2, pick7(p2, v0, v1, v2, v3, v4, v5, v6), k3, pick7(p3, v0, v1, v2, v3, v4, v5, v6), k4, pick7(p4, v0, v1, v2, v3, v4, v5, v6), k5, pick7(p5, v0, v1, v2, v3, v4, v5, v6), k6, pick7(p6, v0, v1, v2, v3, v4, v5, v6), null, null);
        }

        private static Object pick7(byte p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                case 4 -> v4;
                case 5 -> v5;
                default -> v6;
            };
        }
    }

    /**
     * Cached 8-key shape. {@code p0..p7} are original input indices for sorted slots 0..7.
     */
    @ValueType
    public static final class Shape8 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
        public final byte p0, p1, p2, p3, p4, p5, p6, p7;

        public Shape8(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g, Keyword h) {
            if (a == null || b == null || c == null || d == null || e == null || f == null || g == null || h == null) throw new IllegalArgumentException("Key cannot be null in ShapeMap");
            if (a == b || a == c || a == d || a == e || a == f || a == g || a == h || b == c || b == d || b == e || b == f || b == g || b == h || c == d || c == e || c == f || c == g || c == h || d == e || d == f || d == g || d == h || e == f || e == g || e == h || f == g || f == h || g == h) throw new IllegalArgumentException("Duplicate key");
            Keyword[] ks = new Keyword[]{a, b, c, d, e, f, g, h};
            byte[] idx = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
            for (int i = 1; i < 8; i++) {
                Keyword key = ks[i];
                byte id = idx[i];
                int j = i - 1;
                while (j >= 0 && ks[j].id > key.id) {
                    ks[j + 1] = ks[j];
                    idx[j + 1] = idx[j];
                    j--;
                }
                ks[j + 1] = key;
                idx[j + 1] = id;
            }
            this.k0 = ks[0];
            this.k1 = ks[1];
            this.k2 = ks[2];
            this.k3 = ks[3];
            this.k4 = ks[4];
            this.k5 = ks[5];
            this.k6 = ks[6];
            this.k7 = ks[7];
            this.p0 = idx[0];
            this.p1 = idx[1];
            this.p2 = idx[2];
            this.p3 = idx[3];
            this.p4 = idx[4];
            this.p5 = idx[5];
            this.p6 = idx[6];
            this.p7 = idx[7];
        }

        public PersistentShapeMap create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            return new PersistentShapeMap(null, 8,
                    k0, pick8(p0, v0, v1, v2, v3, v4, v5, v6, v7), k1, pick8(p1, v0, v1, v2, v3, v4, v5, v6, v7), k2, pick8(p2, v0, v1, v2, v3, v4, v5, v6, v7), k3, pick8(p3, v0, v1, v2, v3, v4, v5, v6, v7), k4, pick8(p4, v0, v1, v2, v3, v4, v5, v6, v7), k5, pick8(p5, v0, v1, v2, v3, v4, v5, v6, v7), k6, pick8(p6, v0, v1, v2, v3, v4, v5, v6, v7), k7, pick8(p7, v0, v1, v2, v3, v4, v5, v6, v7));
        }

        private static Object pick8(byte p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            return switch (p) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                case 4 -> v4;
                case 5 -> v5;
                case 6 -> v6;
                default -> v7;
            };
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
                if (keys[i].id > keys[j].id) {
                    Keyword tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
                    Object tv = vals[i]; vals[i] = vals[j]; vals[j] = tv;
                }
            }
        }
        return createFromSorted(null, pairCount, keys, vals);
    }

    public static PersistentShapeMap createFromSorted(IPersistentMap meta, int pairCount, Keyword[] keys, Object[] vals) {
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

        /** Two pointer compares: keyword identity + shape identity. */
        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword && map.shape == fromShape;
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
                case 0 -> { nv7 = nv6; nv6 = nv5; nv5 = nv4; nv4 = nv3; nv3 = nv2; nv2 = nv1; nv1 = nv0; nv0 = val; }
                case 1 -> { nv7 = nv6; nv6 = nv5; nv5 = nv4; nv4 = nv3; nv3 = nv2; nv2 = nv1; nv1 = val; }
                case 2 -> { nv7 = nv6; nv6 = nv5; nv5 = nv4; nv4 = nv3; nv3 = nv2; nv2 = val; }
                case 3 -> { nv7 = nv6; nv6 = nv5; nv5 = nv4; nv4 = nv3; nv3 = val; }
                case 4 -> { nv7 = nv6; nv6 = nv5; nv5 = nv4; nv4 = val; }
                case 5 -> { nv7 = nv6; nv6 = nv5; nv5 = val; }
                case 6 -> { nv7 = nv6; nv6 = val; }
                case 7 -> { nv7 = val; }
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
            return switch (slot) {
                case 0 -> shape16(map, keyword, val, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 1 -> shape16(map, s.k0, map.v0, keyword, val, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 2 -> shape16(map, s.k0, map.v0, s.k1, map.v1, keyword, val, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 3 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, keyword, val, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 4 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, keyword, val, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 5 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, keyword, val, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7);
                case 6 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, keyword, val, s.k6, map.v6, s.k7, map.v7);
                case 7 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, keyword, val, s.k7, map.v7);
                case 8 -> shape16(map, s.k0, map.v0, s.k1, map.v1, s.k2, map.v2, s.k3, map.v3, s.k4, map.v4, s.k5, map.v5, s.k6, map.v6, s.k7, map.v7, keyword, val);
                default -> throw new AssertionError("Invalid ShapeMap promotion slot: " + slot);
            };
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

        /** Two pointer compares: keyword identity + shape identity. */
        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword && map.shape == fromShape;
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
            case 0 -> {
                nv7 = v6; nv6 = v5; nv5 = v4; nv4 = v3; nv3 = v2; nv2 = v1; nv1 = v0; nv0 = val;
            }
            case 1 -> {
                nv7 = v6; nv6 = v5; nv5 = v4; nv4 = v3; nv3 = v2; nv2 = v1; nv1 = val;
            }
            case 2 -> {
                nv7 = v6; nv6 = v5; nv5 = v4; nv4 = v3; nv3 = v2; nv2 = val;
            }
            case 3 -> {
                nv7 = v6; nv6 = v5; nv5 = v4; nv4 = v3; nv3 = val;
            }
            case 4 -> {
                nv7 = v6; nv6 = v5; nv5 = v4; nv4 = val;
            }
            case 5 -> {
                nv7 = v6; nv6 = v5; nv5 = val;
            }
            case 6 -> {
                nv7 = v6; nv6 = val;
            }
            case 7 -> {
                nv7 = val;
            }
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
        Keyword pk0 = shape.k0, pk1 = shape.k1, pk2 = shape.k2, pk3 = shape.k3;
        Keyword pk4 = shape.k4, pk5 = shape.k5, pk6 = shape.k6, pk7 = shape.k7, pk8;
        Object pv0 = v0, pv1 = v1, pv2 = v2, pv3 = v3, pv4 = v4, pv5 = v5, pv6 = v6, pv7 = v7, pv8;
        switch (ins) {
            case 0 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = pk4; pv5 = pv4;
                pk4 = pk3; pv4 = pv3;
                pk3 = pk2; pv3 = pv2;
                pk2 = pk1; pv2 = pv1;
                pk1 = pk0; pv1 = pv0;
                pk0 = kw; pv0 = val;
            }
            case 1 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = pk4; pv5 = pv4;
                pk4 = pk3; pv4 = pv3;
                pk3 = pk2; pv3 = pv2;
                pk2 = pk1; pv2 = pv1;
                pk1 = kw; pv1 = val;
            }
            case 2 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = pk4; pv5 = pv4;
                pk4 = pk3; pv4 = pv3;
                pk3 = pk2; pv3 = pv2;
                pk2 = kw; pv2 = val;
            }
            case 3 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = pk4; pv5 = pv4;
                pk4 = pk3; pv4 = pv3;
                pk3 = kw; pv3 = val;
            }
            case 4 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = pk4; pv5 = pv4;
                pk4 = kw; pv4 = val;
            }
            case 5 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = pk5; pv6 = pv5;
                pk5 = kw; pv5 = val;
            }
            case 6 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = pk6; pv7 = pv6;
                pk6 = kw; pv6 = val;
            }
            case 7 -> {
                pk8 = pk7; pv8 = pv7;
                pk7 = kw; pv7 = val;
            }
            default -> {
                pk8 = kw; pv8 = val;
            }
        }
        return new PersistentShapeMap16(meta(), 9,
                pk0, pv0, pk1, pv1, pk2, pv2, pk3, pv3, pk4, pv4, pk5, pv5, pk6, pv6, pk7, pv7, pk8, pv8,
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
                return target instanceof PersistentShapeMap sm && sm.shape == cachedShape ? sm.getVal(targetSlot) : this;
            }
        };
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }
}
