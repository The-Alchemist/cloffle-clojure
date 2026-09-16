/**
 * Value materialization for shape-map merge transitions.
 * Layout planning lives on {@link MapShape} / {@link MapShape16} via {@link ShapeMergePlan}.
 *
 * <p>≤16 results: slot-unrolled field copies into {@code new PersistentShapeMap} /
 * {@code PersistentShapeMap16} — no {@code Object[]} / {@code createFromKeys} on the apply path.
 * Counts &gt; 16 fall through to hash with arrays (slow path).
 */
package clojure.lang;

final class ShapeMapMergeSupport {

    private ShapeMapMergeSupport() {}

    static Object pick(byte s, PersistentShapeMap left, PersistentShapeMap right) {
        return (s & ShapeMergePlan.FROM_RIGHT) != 0
                ? right.getVal(s & ShapeMergePlan.SLOT_MASK)
                : left.getVal(s);
    }

    static Object pick(byte s, PersistentShapeMap left, PersistentShapeMap16 right) {
        return (s & ShapeMergePlan.FROM_RIGHT) != 0
                ? right.getVal(s & ShapeMergePlan.SLOT_MASK)
                : left.getVal(s);
    }

    static Object pick(byte s, PersistentShapeMap16 left, PersistentShapeMap right) {
        return (s & ShapeMergePlan.FROM_RIGHT) != 0
                ? right.getVal(s & ShapeMergePlan.SLOT_MASK)
                : left.getVal(s);
    }

    static Object pick(byte s, PersistentShapeMap16 left, PersistentShapeMap16 right) {
        return (s & ShapeMergePlan.FROM_RIGHT) != 0
                ? right.getVal(s & ShapeMergePlan.SLOT_MASK)
                : left.getVal(s);
    }

    static IPersistentMap materialize(IPersistentMap meta, ShapeMergePlan plan,
                                      PersistentShapeMap left, PersistentShapeMap right) {
        if (plan.count == 0) {
            return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(meta);
        }
        if (plan.resultShape8 != null) {
            return shape8(meta, plan, left, right);
        }
        if (plan.resultShape16 != null) {
            return shape16(meta, plan, left, right);
        }
        return hash(meta, plan, left, right);
    }

    static IPersistentMap materialize(IPersistentMap meta, ShapeMergePlan plan,
                                      PersistentShapeMap left, PersistentShapeMap16 right) {
        if (plan.count == 0) {
            return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(meta);
        }
        if (plan.resultShape8 != null) {
            return shape8(meta, plan, left, right);
        }
        if (plan.resultShape16 != null) {
            return shape16(meta, plan, left, right);
        }
        return hash(meta, plan, left, right);
    }

    static IPersistentMap materialize(IPersistentMap meta, ShapeMergePlan plan,
                                      PersistentShapeMap16 left, PersistentShapeMap right) {
        if (plan.count == 0) {
            return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(meta);
        }
        if (plan.resultShape8 != null) {
            return shape8(meta, plan, left, right);
        }
        if (plan.resultShape16 != null) {
            return shape16(meta, plan, left, right);
        }
        return hash(meta, plan, left, right);
    }

    static IPersistentMap materialize(IPersistentMap meta, ShapeMergePlan plan,
                                      PersistentShapeMap16 left, PersistentShapeMap16 right) {
        if (plan.count == 0) {
            return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(meta);
        }
        if (plan.resultShape8 != null) {
            return shape8(meta, plan, left, right);
        }
        if (plan.resultShape16 != null) {
            return shape16(meta, plan, left, right);
        }
        return hash(meta, plan, left, right);
    }

    // ── ≤8: unrolled ────────────────────────────────────────────────────

    private static PersistentShapeMap shape8(IPersistentMap meta, ShapeMergePlan plan,
                                             PersistentShapeMap left, PersistentShapeMap right) {
        MapShape sh = plan.resultShape8;
        return switch (plan.count) {
            case 1 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), null, null, null, null, null, null, null);
            case 2 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    null, null, null, null, null, null);
            case 3 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right), pick(plan.s2, left, right),
                    null, null, null, null, null);
            case 4 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    null, null, null, null);
            case 5 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right), pick(plan.s4, left, right),
                    null, null, null);
            case 6 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    null, null);
            case 7 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right), pick(plan.s6, left, right),
                    null);
            case 8 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    pick(plan.s6, left, right), pick(plan.s7, left, right));
            default -> throw new AssertionError("Shape8 merge count: " + plan.count);
        };
    }

    private static PersistentShapeMap shape8(IPersistentMap meta, ShapeMergePlan plan,
                                             PersistentShapeMap left, PersistentShapeMap16 right) {
        MapShape sh = plan.resultShape8;
        return switch (plan.count) {
            case 1 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), null, null, null, null, null, null, null);
            case 2 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    null, null, null, null, null, null);
            case 3 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right), pick(plan.s2, left, right),
                    null, null, null, null, null);
            case 4 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    null, null, null, null);
            case 5 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right), pick(plan.s4, left, right),
                    null, null, null);
            case 6 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    null, null);
            case 7 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right), pick(plan.s6, left, right),
                    null);
            case 8 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    pick(plan.s6, left, right), pick(plan.s7, left, right));
            default -> throw new AssertionError("Shape8 merge count: " + plan.count);
        };
    }

    private static PersistentShapeMap shape8(IPersistentMap meta, ShapeMergePlan plan,
                                             PersistentShapeMap16 left, PersistentShapeMap right) {
        MapShape sh = plan.resultShape8;
        return switch (plan.count) {
            case 1 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), null, null, null, null, null, null, null);
            case 2 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    null, null, null, null, null, null);
            case 3 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right), pick(plan.s2, left, right),
                    null, null, null, null, null);
            case 4 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    null, null, null, null);
            case 5 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right), pick(plan.s4, left, right),
                    null, null, null);
            case 6 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    null, null);
            case 7 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right), pick(plan.s6, left, right),
                    null);
            case 8 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    pick(plan.s6, left, right), pick(plan.s7, left, right));
            default -> throw new AssertionError("Shape8 merge count: " + plan.count);
        };
    }

    private static PersistentShapeMap shape8(IPersistentMap meta, ShapeMergePlan plan,
                                             PersistentShapeMap16 left, PersistentShapeMap16 right) {
        MapShape sh = plan.resultShape8;
        return switch (plan.count) {
            case 1 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), null, null, null, null, null, null, null);
            case 2 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    null, null, null, null, null, null);
            case 3 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right), pick(plan.s2, left, right),
                    null, null, null, null, null);
            case 4 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    null, null, null, null);
            case 5 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right), pick(plan.s4, left, right),
                    null, null, null);
            case 6 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    null, null);
            case 7 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right), pick(plan.s6, left, right),
                    null);
            case 8 -> new PersistentShapeMap(meta, sh,
                    pick(plan.s0, left, right), pick(plan.s1, left, right),
                    pick(plan.s2, left, right), pick(plan.s3, left, right),
                    pick(plan.s4, left, right), pick(plan.s5, left, right),
                    pick(plan.s6, left, right), pick(plan.s7, left, right));
            default -> throw new AssertionError("Shape8 merge count: " + plan.count);
        };
    }

    // ── 9..16: unrolled ──────────────────────────────────────────────────

    private static PersistentShapeMap16 shape16(IPersistentMap meta, ShapeMergePlan plan,
                                                PersistentShapeMap left, PersistentShapeMap right) {
        MapShape16 sh = plan.resultShape16;
        return shape16(meta, plan.count, sh,
                pick(plan.s0, left, right), pick(plan.s1, left, right),
                pick(plan.s2, left, right), pick(plan.s3, left, right),
                pick(plan.s4, left, right), pick(plan.s5, left, right),
                pick(plan.s6, left, right), pick(plan.s7, left, right),
                pick(plan.s8, left, right),
                plan.count > 9 ? pick(plan.s9, left, right) : null,
                plan.count > 10 ? pick(plan.s10, left, right) : null,
                plan.count > 11 ? pick(plan.s11, left, right) : null,
                plan.count > 12 ? pick(plan.s12, left, right) : null,
                plan.count > 13 ? pick(plan.s13, left, right) : null,
                plan.count > 14 ? pick(plan.s14, left, right) : null,
                plan.count > 15 ? pick(plan.s15, left, right) : null);
    }

    private static PersistentShapeMap16 shape16(IPersistentMap meta, ShapeMergePlan plan,
                                                PersistentShapeMap left, PersistentShapeMap16 right) {
        MapShape16 sh = plan.resultShape16;
        return shape16(meta, plan.count, sh,
                pick(plan.s0, left, right), pick(plan.s1, left, right),
                pick(plan.s2, left, right), pick(plan.s3, left, right),
                pick(plan.s4, left, right), pick(plan.s5, left, right),
                pick(plan.s6, left, right), pick(plan.s7, left, right),
                pick(plan.s8, left, right),
                plan.count > 9 ? pick(plan.s9, left, right) : null,
                plan.count > 10 ? pick(plan.s10, left, right) : null,
                plan.count > 11 ? pick(plan.s11, left, right) : null,
                plan.count > 12 ? pick(plan.s12, left, right) : null,
                plan.count > 13 ? pick(plan.s13, left, right) : null,
                plan.count > 14 ? pick(plan.s14, left, right) : null,
                plan.count > 15 ? pick(plan.s15, left, right) : null);
    }

    private static PersistentShapeMap16 shape16(IPersistentMap meta, ShapeMergePlan plan,
                                                PersistentShapeMap16 left, PersistentShapeMap right) {
        MapShape16 sh = plan.resultShape16;
        return shape16(meta, plan.count, sh,
                pick(plan.s0, left, right), pick(plan.s1, left, right),
                pick(plan.s2, left, right), pick(plan.s3, left, right),
                pick(plan.s4, left, right), pick(plan.s5, left, right),
                pick(plan.s6, left, right), pick(plan.s7, left, right),
                pick(plan.s8, left, right),
                plan.count > 9 ? pick(plan.s9, left, right) : null,
                plan.count > 10 ? pick(plan.s10, left, right) : null,
                plan.count > 11 ? pick(plan.s11, left, right) : null,
                plan.count > 12 ? pick(plan.s12, left, right) : null,
                plan.count > 13 ? pick(plan.s13, left, right) : null,
                plan.count > 14 ? pick(plan.s14, left, right) : null,
                plan.count > 15 ? pick(plan.s15, left, right) : null);
    }

    private static PersistentShapeMap16 shape16(IPersistentMap meta, ShapeMergePlan plan,
                                                PersistentShapeMap16 left, PersistentShapeMap16 right) {
        MapShape16 sh = plan.resultShape16;
        return shape16(meta, plan.count, sh,
                pick(plan.s0, left, right), pick(plan.s1, left, right),
                pick(plan.s2, left, right), pick(plan.s3, left, right),
                pick(plan.s4, left, right), pick(plan.s5, left, right),
                pick(plan.s6, left, right), pick(plan.s7, left, right),
                pick(plan.s8, left, right),
                plan.count > 9 ? pick(plan.s9, left, right) : null,
                plan.count > 10 ? pick(plan.s10, left, right) : null,
                plan.count > 11 ? pick(plan.s11, left, right) : null,
                plan.count > 12 ? pick(plan.s12, left, right) : null,
                plan.count > 13 ? pick(plan.s13, left, right) : null,
                plan.count > 14 ? pick(plan.s14, left, right) : null,
                plan.count > 15 ? pick(plan.s15, left, right) : null);
    }

    private static PersistentShapeMap16 shape16(
            IPersistentMap meta, int count, MapShape16 sh,
            Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7,
            Object v8, Object v9, Object v10, Object v11, Object v12, Object v13, Object v14, Object v15) {
        return new PersistentShapeMap16(meta, count,
                sh.k0, v0, sh.k1, v1, sh.k2, v2, sh.k3, v3,
                sh.k4, v4, sh.k5, v5, sh.k6, v6, sh.k7, v7,
                sh.k8, v8, sh.k9, v9, sh.k10, v10, sh.k11, v11,
                sh.k12, v12, sh.k13, v13, sh.k14, v14, sh.k15, v15);
    }

    // ── >16: hash (arrays OK) ────────────────────────────────────────────

    private static IPersistentMap hash(IPersistentMap meta, ShapeMergePlan plan,
                                       PersistentShapeMap left, PersistentShapeMap right) {
        int n = plan.count;
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = plan.hashKeys[i];
            init[i * 2 + 1] = pick(plan.hashSources[i], left, right);
        }
        return PersistentHashMap.create(meta, init);
    }

    private static IPersistentMap hash(IPersistentMap meta, ShapeMergePlan plan,
                                       PersistentShapeMap left, PersistentShapeMap16 right) {
        int n = plan.count;
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = plan.hashKeys[i];
            init[i * 2 + 1] = pick(plan.hashSources[i], left, right);
        }
        return PersistentHashMap.create(meta, init);
    }

    private static IPersistentMap hash(IPersistentMap meta, ShapeMergePlan plan,
                                       PersistentShapeMap16 left, PersistentShapeMap right) {
        int n = plan.count;
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = plan.hashKeys[i];
            init[i * 2 + 1] = pick(plan.hashSources[i], left, right);
        }
        return PersistentHashMap.create(meta, init);
    }

    private static IPersistentMap hash(IPersistentMap meta, ShapeMergePlan plan,
                                       PersistentShapeMap16 left, PersistentShapeMap16 right) {
        int n = plan.count;
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = plan.hashKeys[i];
            init[i * 2 + 1] = pick(plan.hashSources[i], left, right);
        }
        return PersistentHashMap.create(meta, init);
    }
}
