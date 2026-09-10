package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.ConstantMapExpr;
import clojure.lang.Compiler.MapLikeExpr;
import clojure.lang.Compiler.ConstantVectorExpr;
import clojure.lang.Compiler.Expr;
import clojure.lang.Compiler.KeywordExpr;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap16;
import net.javacrumbs.cloffle.bytecode.archive.IdentityConstant;

final class ExprToBytecodeLiterals {
    private ExprToBytecodeLiterals() {
    }

    static void emitCreateList(IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> {
                b.emitCreateList0();
            }
            case 1 -> {
                b.beginCreateList1();
                convert.accept((Expr) args.nth(0), b);
                b.endCreateList1();
            }
            case 2 -> {
                b.beginCreateList2();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                b.endCreateList2();
            }
            case 3 -> {
                b.beginCreateList3();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                b.endCreateList3();
            }
            case 4 -> {
                b.beginCreateList4();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                b.endCreateList4();
            }
            case 5 -> {
                b.beginCreateList5();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                b.endCreateList5();
            }
            case 6 -> {
                b.beginCreateList6();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                b.endCreateList6();
            }
            case 7 -> {
                b.beginCreateList7();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                convert.accept((Expr) args.nth(6), b);
                b.endCreateList7();
            }
            case 8 -> {
                b.beginCreateList8();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                convert.accept((Expr) args.nth(6), b);
                convert.accept((Expr) args.nth(7), b);
                b.endCreateList8();
            }
            default -> {
                b.beginCreateListN();
                for (int i = 0; i < count; i++) {
                    convert.accept((Expr) args.nth(i), b);
                }
                b.endCreateListN();
            }
        }
    }

    static boolean isSmallConstantVector(ConstantVectorExpr cve) {
        IPersistentVector args = cve.args;
        // Analyzer folds (e.g. constant conj) may set val without per-element arg exprs; emit val from the pool.
        return args != null && args.count() > 0 && args.count() <= 8;
    }

    static void emitCreateVector(IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> {
                b.emitCreateVector0();
            }
            case 1 -> {
                b.beginCreateVector1();
                convert.accept((Expr) args.nth(0), b);
                b.endCreateVector1();
            }
            case 2 -> {
                b.beginCreateVector2();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                b.endCreateVector2();
            }
            case 3 -> {
                b.beginCreateVector3();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                b.endCreateVector3();
            }
            case 4 -> {
                b.beginCreateVector4();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                b.endCreateVector4();
            }
            case 5 -> {
                b.beginCreateVector5();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                b.endCreateVector5();
            }
            case 6 -> {
                b.beginCreateVector6();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                b.endCreateVector6();
            }
            case 7 -> {
                b.beginCreateVector7();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                convert.accept((Expr) args.nth(6), b);
                b.endCreateVector7();
            }
            case 8 -> {
                b.beginCreateVector8();
                convert.accept((Expr) args.nth(0), b);
                convert.accept((Expr) args.nth(1), b);
                convert.accept((Expr) args.nth(2), b);
                convert.accept((Expr) args.nth(3), b);
                convert.accept((Expr) args.nth(4), b);
                convert.accept((Expr) args.nth(5), b);
                convert.accept((Expr) args.nth(6), b);
                convert.accept((Expr) args.nth(7), b);
                b.endCreateVector8();
            }
            default -> {
                b.beginCreateVectorN();
                for (int i = 0; i < count; i++) {
                    convert.accept((Expr) args.nth(i), b);
                }
                b.endCreateVectorN();
            }
        }
    }

    static boolean isSmallKeywordMap(ConstantMapExpr cme) {
        IPersistentVector keyvals = cme.keyvals;
        int count = keyvals == null ? 0 : keyvals.count();
        int pairCount = count / 2;
        if (pairCount > 16) {
            return false;
        }
        for (int i = 0; i < count; i += 2) {
            if (!(keyvals.nth(i) instanceof KeywordExpr)) {
                return false;
            }
        }
        return true;
    }

    static void emitCreateMap(MapLikeExpr me, CloffleBytecodeRootNodeGen.Builder b,
                              java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        emitCreateMap(me.keyvals(), me.shape(), me.shape16(), b, convert);
    }

    static void emitCreateMap(IPersistentVector keyvals, MapShape shape, CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        emitCreateMap(keyvals, shape, null, b, convert);
    }

    static void emitCreateMap(IPersistentVector keyvals, MapShape shape, PersistentShapeMap16.Factory shape16,
                              CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        if (shape != null) {
            emitCreateMapShaped(keyvals, shape, b, convert);
            return;
        }
        PersistentShapeMap16.Factory factory16 = shape16 != null ? shape16 : factory16FromKeyvals(keyvals);
        if (factory16 != null) {
            emitCreateMapShaped16(keyvals, factory16, b, convert);
            return;
        }
        emitCreateMapUnshaped(keyvals, b, convert);
    }

    private static PersistentShapeMap16.Factory factory16FromKeyvals(IPersistentVector keyvals) {
        int pairCount = keyvals == null ? 0 : (keyvals.count() / 2);
        if (pairCount < 9 || pairCount > 16) {
            return null;
        }
        Keyword[] sourceKeys = new Keyword[pairCount];
        for (int i = 0; i < pairCount; i++) {
            Object k = keyvals.nth(i * 2);
            if (!(k instanceof KeywordExpr ke)) {
                return null;
            }
            sourceKeys[i] = ke.k;
        }
        return new PersistentShapeMap16.Factory(sourceKeys);
    }

    private static void emitCreateMapShaped16(IPersistentVector keyvals, PersistentShapeMap16.Factory factory,
                                              CloffleBytecodeRootNodeGen.Builder b,
                                              java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        int pairCount = keyvals.count() / 2;
        b.beginCreateMapShaped16(factory);
        for (int slot = 0; slot < 16; slot++) {
            if (slot < pairCount) {
                convert.accept((Expr) keyvals.nth(factory.sourceIndex(slot) * 2 + 1), b);
            } else {
                b.emitLoadNull();
            }
        }
        b.endCreateMapShaped16();
    }

    private static void emitCreateMapShaped(IPersistentVector keyvals, MapShape shape, CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        int pairCount = keyvals.count() / 2;
        Keyword[] sourceKeys = new Keyword[pairCount];
        for (int i = 0; i < pairCount; i++) {
            sourceKeys[i] = (Keyword) ((KeywordExpr) keyvals.nth(i * 2)).k;
        }
        MapShape.Factory factory = new MapShape.Factory(shape, sourceKeys);

        switch (pairCount) {
            case 1 -> {
                b.beginCreateMapShaped1(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                b.endCreateMapShaped1();
            }
            case 2 -> {
                b.beginCreateMapShaped2(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                b.endCreateMapShaped2();
            }
            case 3 -> {
                b.beginCreateMapShaped3(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                b.endCreateMapShaped3();
            }
            case 4 -> {
                b.beginCreateMapShaped4(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(7), b);
                b.endCreateMapShaped4();
            }
            case 5 -> {
                b.beginCreateMapShaped5(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(9), b);
                b.endCreateMapShaped5();
            }
            case 6 -> {
                b.beginCreateMapShaped6(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(11), b);
                b.endCreateMapShaped6();
            }
            case 7 -> {
                b.beginCreateMapShaped7(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(11), b);
                convert.accept((Expr) keyvals.nth(13), b);
                b.endCreateMapShaped7();
            }
            case 8 -> {
                b.beginCreateMapShaped8(factory);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(11), b);
                convert.accept((Expr) keyvals.nth(13), b);
                convert.accept((Expr) keyvals.nth(15), b);
                b.endCreateMapShaped8();
            }
            default -> emitCreateMapUnshaped(keyvals, b, convert);
        }
    }

    private static void emitCreateMapUnshaped(IPersistentVector keyvals, CloffleBytecodeRootNodeGen.Builder b, java.util.function.BiConsumer<Expr, CloffleBytecodeRootNodeGen.Builder> convert) {
        int pairCount = keyvals == null ? 0 : (keyvals.count() / 2);
        switch (pairCount) {
            case 0 -> {
                b.emitCreateMap0();
            }
            case 1 -> {
                b.beginCreateMap1();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                b.endCreateMap1();
            }
            case 2 -> {
                b.beginCreateMap2();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                b.endCreateMap2();
            }
            case 3 -> {
                b.beginCreateMap3();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                b.endCreateMap3();
            }
            case 4 -> {
                b.beginCreateMap4();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(6), b);
                convert.accept((Expr) keyvals.nth(7), b);
                b.endCreateMap4();
            }
            case 5 -> {
                b.beginCreateMap5();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(6), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(8), b);
                convert.accept((Expr) keyvals.nth(9), b);
                b.endCreateMap5();
            }
            case 6 -> {
                b.beginCreateMap6();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(6), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(8), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(10), b);
                convert.accept((Expr) keyvals.nth(11), b);
                b.endCreateMap6();
            }
            case 7 -> {
                b.beginCreateMap7();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(6), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(8), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(10), b);
                convert.accept((Expr) keyvals.nth(11), b);
                convert.accept((Expr) keyvals.nth(12), b);
                convert.accept((Expr) keyvals.nth(13), b);
                b.endCreateMap7();
            }
            case 8 -> {
                b.beginCreateMap8();
                convert.accept((Expr) keyvals.nth(0), b);
                convert.accept((Expr) keyvals.nth(1), b);
                convert.accept((Expr) keyvals.nth(2), b);
                convert.accept((Expr) keyvals.nth(3), b);
                convert.accept((Expr) keyvals.nth(4), b);
                convert.accept((Expr) keyvals.nth(5), b);
                convert.accept((Expr) keyvals.nth(6), b);
                convert.accept((Expr) keyvals.nth(7), b);
                convert.accept((Expr) keyvals.nth(8), b);
                convert.accept((Expr) keyvals.nth(9), b);
                convert.accept((Expr) keyvals.nth(10), b);
                convert.accept((Expr) keyvals.nth(11), b);
                convert.accept((Expr) keyvals.nth(12), b);
                convert.accept((Expr) keyvals.nth(13), b);
                convert.accept((Expr) keyvals.nth(14), b);
                convert.accept((Expr) keyvals.nth(15), b);
                b.endCreateMap8();
            }
            default -> {
                b.beginCreateMapN();
                for (int i = 0; i < keyvals.count(); i += 2) {
                    convert.accept((Expr) keyvals.nth(i), b);
                    convert.accept((Expr) keyvals.nth(i + 1), b);
                }
                b.endCreateMapN();
            }
        }
    }

    /**
     * Emit a constant value, handling the case where the value is an {@link clojure.lang.IObj}
     * with non-null metadata. Truffle's {@code ConstantsBuffer} deduplicates constants using
     * {@code Object.equals()}, but Clojure's {@code Symbol.equals()} (and similar) ignores
     * metadata. Two symbols with the same name but different metadata would be collapsed to
     * whichever was added first, losing the metadata of the second. To prevent this, we strip
     * the metadata, emit the bare value as the constant, and re-apply the metadata at runtime
     * via {@code WithMeta}.
     */
    static void emitConstantValue(Object v, CloffleBytecodeRootNodeGen.Builder b) {
        if (v instanceof clojure.lang.IObj iobj) {
            clojure.lang.IPersistentMap meta = iobj.meta();
            if (meta != null) {
                b.beginWithMeta();
                emitConstantNoMeta(iobj.withMeta(null), b);
                emitConstantNoMeta(meta, b);
                b.endWithMeta();
                return;
            }
        }
        emitConstantNoMeta(v, b);
    }

    static boolean safeForConstantPool(Object v) {
        return v == null
            || v instanceof String
            || v instanceof Number
            || v instanceof Boolean
            || v instanceof Character
            || v instanceof Class
            || v instanceof clojure.lang.Keyword
            || v instanceof clojure.lang.Symbol;
    }

    static void emitConstantNoMeta(Object v, CloffleBytecodeRootNodeGen.Builder b) {
        if (!safeForConstantPool(v)) {
            b.emitLoadIdentityConstant(new IdentityConstant(v));
        } else if (v instanceof Integer i) {
            b.emitConstLong(i.longValue());
        } else if (v instanceof Long l) {
            b.emitConstLong(l.longValue());
        } else if (v instanceof Double d) {
            b.emitConstDouble(d.doubleValue());
        } else if (v instanceof Float f) {
            b.emitConstDouble(f.doubleValue());
        } else {
            b.emitLoadConstant(v);
        }
    }
}
