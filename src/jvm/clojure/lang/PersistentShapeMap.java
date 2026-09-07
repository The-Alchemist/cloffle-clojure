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

    public final int count;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7;
    private final IPersistentMap _meta;

    public PersistentShapeMap() {
        this(null, 0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

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
        this.count = count;
        this.k0 = k0; this.v0 = v0;
        this.k1 = k1; this.v1 = v1;
        this.k2 = k2; this.v2 = v2;
        this.k3 = k3; this.v3 = v3;
        this.k4 = k4; this.v4 = v4;
        this.k5 = k5; this.v5 = v5;
        this.k6 = k6; this.v6 = v6;
        this.k7 = k7; this.v7 = v7;
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
        return switch (i) {
            case 0 -> k0;
            case 1 -> k1;
            case 2 -> k2;
            case 3 -> k3;
            case 4 -> k4;
            case 5 -> k5;
            case 6 -> k6;
            case 7 -> k7;
            default -> null;
        };
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
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;

        private AssocTransition(PersistentShapeMap map, Keyword keyword) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
        }

        // Unused slots are null on both sides once counts agree, so the key compares need
        // no count guards. Non-short-circuiting & keeps this a flat AND-tree rather than
        // eight branches; all eight compares run anyway on the cache-hit path.
        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7));
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
            return switch (slot) {
                case 0 -> new PersistentShapeMap(map.meta(), count, k0, val, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 1 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, val, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 2 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, val, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 3 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, map.v2, k3, val, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 4 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, val, k5, map.v5, k6, map.v6, k7, map.v7);
                case 5 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, val, k6, map.v6, k7, map.v7);
                case 6 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, val, k7, map.v7);
                case 7 -> new PersistentShapeMap(map.meta(), count, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, val);
                default -> throw new AssertionError("Invalid ShapeMap update slot: " + slot);
            };
        }
    }

    private static class InsertTransition extends AssocTransition {
        protected final byte slot;

        private InsertTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap map, Object val) {
            return switch (slot) {
                case 0 -> new PersistentShapeMap(map.meta(), count + 1, keyword, val, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6);
                case 1 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, keyword, val, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6);
                case 2 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, keyword, val, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6);
                case 3 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, k2, map.v2, keyword, val, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6);
                case 4 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, keyword, val, k4, map.v4, k5, map.v5, k6, map.v6);
                case 5 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, keyword, val, k5, map.v5, k6, map.v6);
                case 6 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, keyword, val, k6, map.v6);
                case 7 -> new PersistentShapeMap(map.meta(), count + 1, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, keyword, val);
                default -> throw new AssertionError("Invalid ShapeMap insert slot: " + slot);
            };
        }
    }

    private static final class Promote16Transition extends InsertTransition {
        private Promote16Transition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword, slot);
        }

        @Override
        public PersistentShapeMap16 apply(PersistentShapeMap map, Object val) {
            return switch (slot) {
                case 0 -> shape16(map, keyword, val, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 1 -> shape16(map, k0, map.v0, keyword, val, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 2 -> shape16(map, k0, map.v0, k1, map.v1, keyword, val, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 3 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, keyword, val, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 4 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, keyword, val, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7);
                case 5 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, keyword, val, k5, map.v5, k6, map.v6, k7, map.v7);
                case 6 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, keyword, val, k6, map.v6, k7, map.v7);
                case 7 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, keyword, val, k7, map.v7);
                case 8 -> shape16(map, k0, map.v0, k1, map.v1, k2, map.v2, k3, map.v3, k4, map.v4, k5, map.v5, k6, map.v6, k7, map.v7, keyword, val);
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
        int existingSlot = -1;
        if (map.k0 == keyword) existingSlot = 0;
        else if (map.k1 == keyword) existingSlot = 1;
        else if (map.k2 == keyword) existingSlot = 2;
        else if (map.k3 == keyword) existingSlot = 3;
        else if (map.k4 == keyword) existingSlot = 4;
        else if (map.k5 == keyword) existingSlot = 5;
        else if (map.k6 == keyword) existingSlot = 6;
        else if (map.k7 == keyword) existingSlot = 7;
        if (existingSlot >= 0) {
            return new UpdateTransition(map, keyword, existingSlot);
        }

        long want = keyword.id;
        int lt = ((map.count > 0 && want > map.k0.id) ? 1      : 0)
               | ((map.count > 1 && want > map.k1.id) ? 1 << 1 : 0)
               | ((map.count > 2 && want > map.k2.id) ? 1 << 2 : 0)
               | ((map.count > 3 && want > map.k3.id) ? 1 << 3 : 0)
               | ((map.count > 4 && want > map.k4.id) ? 1 << 4 : 0)
               | ((map.count > 5 && want > map.k5.id) ? 1 << 5 : 0)
               | ((map.count > 6 && want > map.k6.id) ? 1 << 6 : 0)
               | ((map.count > 7 && want > map.k7.id) ? 1 << 7 : 0);
        int insertSlot = Integer.bitCount(lt);
        return map.count == MAX_SHAPE_KEYS
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
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;

        protected DissocTransition(PersistentShapeMap map, Keyword keyword) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
        }

        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7));
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
        private final Keyword toK0, toK1, toK2, toK3, toK4, toK5, toK6;

        private RemoveTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;

            Keyword[] dest = new Keyword[7];
            int d = 0;
            for (int i = 0; i < count; i++) {
                if (i != slot) {
                    dest[d++] = map.getKey(i);
                }
            }
            this.toK0 = dest[0];
            this.toK1 = dest[1];
            this.toK2 = dest[2];
            this.toK3 = dest[3];
            this.toK4 = dest[4];
            this.toK5 = dest[5];
            this.toK6 = dest[6];
        }

        @Override
        public PersistentShapeMap apply(PersistentShapeMap map) {
            int newCount = count - 1;
            return switch (slot) {
                case 0 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v1, toK1, map.v2, toK2, map.v3, toK3, map.v4, toK4, map.v5, toK5, map.v6, toK6, map.v7, null, null);
                case 1 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v2, toK2, map.v3, toK3, map.v4, toK4, map.v5, toK5, map.v6, toK6, map.v7, null, null);
                case 2 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v3, toK3, map.v4, toK4, map.v5, toK5, map.v6, toK6, map.v7, null, null);
                case 3 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v4, toK4, map.v5, toK5, map.v6, toK6, map.v7, null, null);
                case 4 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3, toK4, map.v5, toK5, map.v6, toK6, map.v7, null, null);
                case 5 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3, toK4, map.v4, toK5, map.v6, toK6, map.v7, null, null);
                case 6 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3, toK4, map.v4, toK5, map.v5, toK6, map.v7, null, null);
                case 7 -> new PersistentShapeMap(map.meta(), newCount,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3, toK4, map.v4, toK5, map.v5, toK6, map.v6, null, null);
                default -> throw new AssertionError("Invalid ShapeMap remove slot: " + slot);
            };
        }
    }

    public static DissocTransition dissocTransition(PersistentShapeMap map, Keyword keyword) {
        if (map.count == 0) {
            return new NoOpDissocTransition(map, keyword);
        }
        int slot = -1;
        if (map.k0 == keyword) slot = 0;
        else if (map.k1 == keyword) slot = 1;
        else if (map.k2 == keyword) slot = 2;
        else if (map.k3 == keyword) slot = 3;
        else if (map.k4 == keyword) slot = 4;
        else if (map.k5 == keyword) slot = 5;
        else if (map.k6 == keyword) slot = 6;
        else if (map.k7 == keyword) slot = 7;

        if (slot < 0) {
            return new NoOpDissocTransition(map, keyword);
        }
        if (map.count == 1) {
            return new EmptyDissocTransition(map, keyword);
        }
        return new RemoveTransition(map, keyword, slot);
    }

    /**
     * A bytecode-node-local lookup descriptor for PersistentShapeMap and a constant keyword.
     * Caches the exact slot index or whether the key is missing in this shape.
     */
    @ValueType
    public abstract static class LookupTransition {
        public final Keyword keyword;
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;

        protected LookupTransition(PersistentShapeMap map, Keyword keyword) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
        }

        public final boolean matches(PersistentShapeMap map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7));
        }

        public abstract Object get(PersistentShapeMap map, Object notFound);
    }

    private static final class HitLookupTransition extends LookupTransition {
        private final byte slot;

        private HitLookupTransition(PersistentShapeMap map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;
        }

        @Override
        public Object get(PersistentShapeMap map, Object notFound) {
            return switch (slot) {
                case 0 -> map.v0;
                case 1 -> map.v1;
                case 2 -> map.v2;
                case 3 -> map.v3;
                case 4 -> map.v4;
                case 5 -> map.v5;
                case 6 -> map.v6;
                case 7 -> map.v7;
                default -> notFound;
            };
        }
    }

    private static final class MissLookupTransition extends LookupTransition {
        private MissLookupTransition(PersistentShapeMap map, Keyword keyword) {
            super(map, keyword);
        }

        @Override
        public Object get(PersistentShapeMap map, Object notFound) {
            return notFound;
        }
    }

    public static LookupTransition lookupTransition(PersistentShapeMap map, Keyword keyword) {
        int slot = -1;
        if (map.k0 == keyword) slot = 0;
        else if (map.k1 == keyword) slot = 1;
        else if (map.k2 == keyword) slot = 2;
        else if (map.k3 == keyword) slot = 3;
        else if (map.k4 == keyword) slot = 4;
        else if (map.k5 == keyword) slot = 5;
        else if (map.k6 == keyword) slot = 6;
        else if (map.k7 == keyword) slot = 7;

        if (slot >= 0) {
            return new HitLookupTransition(map, keyword, slot);
        }
        return new MissLookupTransition(map, keyword);
    }

    @Override
    public int count() {
        return count;
    }

    // Slots at or past count always hold a null key, and a Keyword argument is never null,
    // so identity compares alone cannot match an unused slot: no count guards needed.
    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Keyword kw) {
            return kw == k0 || kw == k1 || kw == k2 || kw == k3
                    || kw == k4 || kw == k5 || kw == k6 || kw == k7;
        }
        return false;
    }

    @Override
    public IMapEntry entryAt(Object key) {
        if (key instanceof Keyword kw) {
            if (kw == k0) return (IMapEntry) MapEntry.create(k0, v0);
            if (kw == k1) return (IMapEntry) MapEntry.create(k1, v1);
            if (kw == k2) return (IMapEntry) MapEntry.create(k2, v2);
            if (kw == k3) return (IMapEntry) MapEntry.create(k3, v3);
            if (kw == k4) return (IMapEntry) MapEntry.create(k4, v4);
            if (kw == k5) return (IMapEntry) MapEntry.create(k5, v5);
            if (kw == k6) return (IMapEntry) MapEntry.create(k6, v6);
            if (kw == k7) return (IMapEntry) MapEntry.create(k7, v7);
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
            if (kw == k0) return v0;
            if (kw == k1) return v1;
            if (kw == k2) return v2;
            if (kw == k3) return v3;
            if (kw == k4) return v4;
            if (kw == k5) return v5;
            if (kw == k6) return v6;
            if (kw == k7) return v7;
        }
        return notFound;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        if (!(key instanceof Keyword kw)) {
            return assocNonKeyword(key, val);
        }

        // Check if key already exists
        int existingSlot = -1;
        if (kw == k0) existingSlot = 0;
        else if (kw == k1) existingSlot = 1;
        else if (kw == k2) existingSlot = 2;
        else if (kw == k3) existingSlot = 3;
        else if (kw == k4) existingSlot = 4;
        else if (kw == k5) existingSlot = 5;
        else if (kw == k6) existingSlot = 6;
        else if (kw == k7) existingSlot = 7;

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
            return new PersistentShapeMap(meta(), count,
                    k0, nv0, k1, nv1, k2, nv2, k3, nv3, k4, nv4, k5, nv5, k6, nv6, k7, nv7);
        }

        // Keys are sorted by Keyword.id, so the slots ordering before kw form a contiguous
        // low run and their population count is the insertion index. Building a mask first
        // keeps the eight compares independent instead of chaining them through ins++.
        // The count guards are required here: kN.id would NPE on an unused slot.
        long want = kw.id;
        int lt = ((count > 0 && want > k0.id) ? 1      : 0)
               | ((count > 1 && want > k1.id) ? 1 << 1 : 0)
               | ((count > 2 && want > k2.id) ? 1 << 2 : 0)
               | ((count > 3 && want > k3.id) ? 1 << 3 : 0)
               | ((count > 4 && want > k4.id) ? 1 << 4 : 0)
               | ((count > 5 && want > k5.id) ? 1 << 5 : 0)
               | ((count > 6 && want > k6.id) ? 1 << 6 : 0)
               | ((count > 7 && want > k7.id) ? 1 << 7 : 0);
        int ins = Integer.bitCount(lt);

        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, count == MAX_SHAPE_KEYS)) {
            return assocPromote16(kw, val, ins);
        }

        Keyword nk0 = k0, nk1 = k1, nk2 = k2, nk3 = k3, nk4 = k4, nk5 = k5, nk6 = k6, nk7 = k7;
        Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
        switch (ins) {
            case 0 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = k1; nv2 = v1;
                nk1 = k0; nv1 = v0;
                nk0 = kw; nv0 = val;
            }
            case 1 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = k1; nv2 = v1;
                nk1 = kw; nv1 = val;
            }
            case 2 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = kw; nv2 = val;
            }
            case 3 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = kw; nv3 = val;
            }
            case 4 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = kw; nv4 = val;
            }
            case 5 -> {
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = kw; nv5 = val;
            }
            case 6 -> {
                nk7 = k6; nv7 = v6;
                nk6 = kw; nv6 = val;
            }
            case 7 -> {
                nk7 = kw; nv7 = val;
            }
        }
        return new PersistentShapeMap(meta(), count + 1,
                nk0, nv0, nk1, nv1, nk2, nv2, nk3, nv3, nk4, nv4, nk5, nv5, nk6, nv6, nk7, nv7);
    }

    @TruffleBoundary
    private IPersistentMap assocNonKeyword(Object key, Object val) {
        Object[] arr = toArray();
        return new PersistentArrayMap(meta(), arr).assoc(key, val);
    }

    private PersistentShapeMap16 assocPromote16(Keyword kw, Object val, int ins) {
        Keyword pk0 = k0, pk1 = k1, pk2 = k2, pk3 = k3, pk4 = k4, pk5 = k5, pk6 = k6, pk7 = k7, pk8;
        Object pv0 = v0, pv1 = v1, pv2 = v2, pv3 = v3, pv4 = v4, pv5 = v5, pv6 = v6, pv7 = v7, pv8;
        switch (ins) {
            case 0 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = k4; pv5 = v4;
                pk4 = k3; pv4 = v3;
                pk3 = k2; pv3 = v2;
                pk2 = k1; pv2 = v1;
                pk1 = k0; pv1 = v0;
                pk0 = kw; pv0 = val;
            }
            case 1 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = k4; pv5 = v4;
                pk4 = k3; pv4 = v3;
                pk3 = k2; pv3 = v2;
                pk2 = k1; pv2 = v1;
                pk1 = kw; pv1 = val;
            }
            case 2 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = k4; pv5 = v4;
                pk4 = k3; pv4 = v3;
                pk3 = k2; pv3 = v2;
                pk2 = kw; pv2 = val;
            }
            case 3 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = k4; pv5 = v4;
                pk4 = k3; pv4 = v3;
                pk3 = kw; pv3 = val;
            }
            case 4 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = k4; pv5 = v4;
                pk4 = kw; pv4 = val;
            }
            case 5 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = k5; pv6 = v5;
                pk5 = kw; pv5 = val;
            }
            case 6 -> {
                pk8 = k7; pv8 = v7;
                pk7 = k6; pv7 = v6;
                pk6 = kw; pv6 = val;
            }
            case 7 -> {
                pk8 = k7; pv8 = v7;
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

        int matchIdx = -1;
        if (kw == k0) matchIdx = 0;
        else if (kw == k1) matchIdx = 1;
        else if (kw == k2) matchIdx = 2;
        else if (kw == k3) matchIdx = 3;
        else if (kw == k4) matchIdx = 4;
        else if (kw == k5) matchIdx = 5;
        else if (kw == k6) matchIdx = 6;
        else if (kw == k7) matchIdx = 7;
        else return this;

        if (count == 1) {
            return (IPersistentMap) EMPTY.withMeta(meta());
        }

        Keyword nk0 = k0, nk1 = k1, nk2 = k2, nk3 = k3, nk4 = k4, nk5 = k5, nk6 = k6, nk7 = k7;
        Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
        switch (matchIdx) {
            case 0 -> {
                nk0 = k1; nv0 = v1;
                nk1 = k2; nv1 = v2;
                nk2 = k3; nv2 = v3;
                nk3 = k4; nv3 = v4;
                nk4 = k5; nv4 = v5;
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 1 -> {
                nk1 = k2; nv1 = v2;
                nk2 = k3; nv2 = v3;
                nk3 = k4; nv3 = v4;
                nk4 = k5; nv4 = v5;
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 2 -> {
                nk2 = k3; nv2 = v3;
                nk3 = k4; nv3 = v4;
                nk4 = k5; nv4 = v5;
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 3 -> {
                nk3 = k4; nv3 = v4;
                nk4 = k5; nv4 = v5;
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 4 -> {
                nk4 = k5; nv4 = v5;
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 5 -> {
                nk5 = k6; nv5 = v6;
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 6 -> {
                nk6 = k7; nv6 = v7;
                nk7 = null; nv7 = null;
            }
            case 7 -> {
                nk7 = null; nv7 = null;
            }
            default -> {
                return this;
            }
        }
        return new PersistentShapeMap(meta(), count - 1,
                nk0, nv0, nk1, nv1, nk2, nv2, nk3, nv3, nk4, nv4, nk5, nv5, nk6, nv6, nk7, nv7);
    }

    @Override
    public IPersistentMap empty() {
        return (IPersistentMap) EMPTY.withMeta(meta());
    }

    public Object[] toArray() {
        Object[] arr = new Object[count * 2];
        for (int i = 0; i < count; i++) {
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
        if (count > 0) {
            return new ShapeMapSeq(this, 0);
        }
        return null;
    }

    @Override
    public Sequential drop(int n) {
        if (count > 0) {
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
        return new PersistentShapeMap(meta, count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    @Override
    public Object kvreduce(IFn f, Object init) {
        Object acc = init;
        switch (count) {
            case 0: return acc;
            case 1: {
                acc = f.invoke(acc, k0, v0);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 2: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 3: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 4: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k3, v3);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 5: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k4, v4);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 6: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k5, v5);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 7: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k5, v5);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k6, v6);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 8: {
                acc = f.invoke(acc, k0, v0);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k1, v1);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k2, v2);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k3, v3);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k4, v4);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k5, v5);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k6, v6);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, k7, v7);
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            default:
                for (int i = 0; i < count; i++) {
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
        switch (count) {
            case 0: return acc;
            case 1: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 2: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 3: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 4: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k3, v3));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 5: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k4, v4));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 6: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k5, v5));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 7: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k5, v5));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k6, v6));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            case 8: {
                acc = f.invoke(acc, MapEntry.create(k0, v0));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k1, v1));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k2, v2));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k3, v3));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k4, v4));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k5, v5));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k6, v6));
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
                acc = f.invoke(acc, MapEntry.create(k7, v7));
                return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
            }
            default:
                for (int i = 0; i < count; i++) {
                    acc = f.invoke(acc, MapEntry.create(getKey(i), getVal(i)));
                    if (RT.isReduced(acc))
                        return ((IDeref) acc).deref();
                }
                return acc;
        }
    }

    @Override
    public Object reduce(IFn f) {
        if (count == 0) return f.invoke();
        Object acc = MapEntry.create(k0, v0);
        for (int i = 1; i < count; i++) {
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
            if (i + 1 < map.count)
                return new ShapeMapSeq(map, i + 1);
            return null;
        }

        @Override
        public int count() {
            return map.count - i;
        }

        @Override
        public Sequential drop(int n) {
            if (n <= 0) return this;
            if (i + n < map.count) {
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
            if (i < map.count) {
                Object acc = MapEntry.create(map.getKey(i), map.getVal(i));
                for (int j = i + 1; j < map.count; j++) {
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
            for (int j = i; j < map.count; j++) {
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
            return i < map.count;
        }

        @Override
        public Object next() {
            if (i >= map.count) throw new NoSuchElementException();
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
        int slot = -1;
        if (k == k0) slot = 0;
        else if (k == k1) slot = 1;
        else if (k == k2) slot = 2;
        else if (k == k3) slot = 3;
        else if (k == k4) slot = 4;
        else if (k == k5) slot = 5;
        else if (k == k6) slot = 6;
        else if (k == k7) slot = 7;

        if (slot < 0) return null;
        final int targetSlot = slot;
        return new ILookupThunk() {
            @Override
            public Object get(Object target) {
                return target instanceof PersistentShapeMap sm && sm.getKey(targetSlot) == k ? sm.getVal(targetSlot) : this;
            }
        };
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }
}
