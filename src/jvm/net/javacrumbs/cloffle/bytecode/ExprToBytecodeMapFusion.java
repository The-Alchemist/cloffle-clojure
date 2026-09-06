package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.*;
import clojure.lang.IPersistentVector;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeLocal;

final class ExprToBytecodeMapFusion {
    private ExprToBytecodeMapFusion() {
    }

    static void emitUnrolledUpdate(ExprToBytecode gen, Expr mExpr, Expr keyExpr, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        b.beginBlock();
        BytecodeLocal mLocal = gen.createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        gen.convert(mExpr, b);
        b.endStoreLocal();

        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(mLocal);

            emitInvokeWithFirstArg(gen, 
                    () -> {
                        b.beginKeywordLookup(ke.k);
                        b.emitLoadLocal(mLocal);
                        b.endKeywordLookup();
                    },
                    fnExpr,
                    extraArgs,
                    b
            );

            b.endKeywordAssoc();
        } else {
            BytecodeLocal keyLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(keyLocal);
            gen.convert(keyExpr, b);
            b.endStoreLocal();

            b.beginMapAssoc();
            b.emitLoadLocal(mLocal);
            b.emitLoadLocal(keyLocal);

            emitInvokeWithFirstArg(gen, 
                    () -> {
                        b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                        b.emitLoadLocal(mLocal);
                        b.emitLoadLocal(keyLocal);
                        b.endStaticMethod();
                    },
                    fnExpr,
                    extraArgs,
                    b
            );

            b.endMapAssoc();
        }
        b.endBlock();
    }

    static void emitInvokeWithFirstArg(ExprToBytecode gen, Runnable emitFirstArg, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        int extraCount = extraArgs == null ? 0 : extraArgs.count();
        int totalArity = 1 + extraCount;
        switch (totalArity) {
            case 1 -> {
                b.beginInvoke1();
                gen.convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                b.endInvoke1();
            }
            case 2 -> {
                b.beginInvoke2();
                gen.convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                b.endInvoke2();
            }
            case 3 -> {
                b.beginInvoke3();
                gen.convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(1), b);
                b.endInvoke3();
            }
            case 4 -> {
                b.beginInvoke4();
                gen.convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(0), b);
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(1), b);
                gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(2), b);
                b.endInvoke4();
            }
            default -> {
                b.beginInvokeN();
                gen.convertCalleeOrArgForInvoke(fnExpr, b);
                emitFirstArg.run();
                for (int i = 0; i < extraCount; i++) {
                    gen.convertCalleeOrArgForInvoke((Expr) extraArgs.nth(i), b);
                }
                b.endInvokeN();
            }
        }
    }

    static void emitUnrolledUpdateIn(ExprToBytecode gen, Expr mExpr, VectorLikeExpr pathExpr, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args();
        int n = keys.count();
        if (n == 0) {
            emitInvokeWithFirstArg(gen, () -> gen.convert(mExpr, b), fnExpr, extraArgs, b);
            return;
        }
        if (n == 1) {
            emitUnrolledUpdate(gen, mExpr, (Expr) keys.nth(0), fnExpr, extraArgs, b);
            return;
        }
        b.beginBlock();
        BytecodeLocal mLocal = gen.createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        gen.convert(mExpr, b);
        b.endStoreLocal();

        emitUpdateInStep(gen, mLocal, keys, 0, fnExpr, extraArgs, b);

        b.endBlock();
    }

    static void emitUpdateInStep(ExprToBytecode gen, BytecodeLocal currMapLocal, IPersistentVector keys, int index, Expr fnExpr, IPersistentVector extraArgs, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keys.nth(index);
        if (index == keys.count() - 1) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                b.emitLoadLocal(currMapLocal);
                emitInvokeWithFirstArg(gen, 
                        () -> {
                            b.beginKeywordLookup(ke.k);
                            b.emitLoadLocal(currMapLocal);
                            b.endKeywordLookup();
                        },
                        fnExpr,
                        extraArgs,
                        b
                );
                b.endKeywordAssoc();
            } else {
                BytecodeLocal keyLocal = gen.createTrackedLocal(b);
                b.beginStoreLocal(keyLocal);
                gen.convert(keyExpr, b);
                b.endStoreLocal();

                b.beginMapAssoc();
                b.emitLoadLocal(currMapLocal);
                b.emitLoadLocal(keyLocal);
                emitInvokeWithFirstArg(gen, 
                        () -> {
                            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                            b.emitLoadLocal(currMapLocal);
                            b.emitLoadLocal(keyLocal);
                            b.endStaticMethod();
                        },
                        fnExpr,
                        extraArgs,
                        b
                );
                b.endMapAssoc();
            }
            return;
        }
        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(currMapLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginKeywordLookup(ke.k);
            b.emitLoadLocal(currMapLocal);
            b.endKeywordLookup();
            b.endStoreLocal();

            emitUpdateInStep(gen, nextMapLocal, keys, index + 1, fnExpr, extraArgs, b);

            b.endBlock();
            b.endKeywordAssoc();
        } else {
            BytecodeLocal keyLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(keyLocal);
            gen.convert(keyExpr, b);
            b.endStoreLocal();

            b.beginMapAssoc();
            b.emitLoadLocal(currMapLocal);
            b.emitLoadLocal(keyLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
            b.emitLoadLocal(currMapLocal);
            b.emitLoadLocal(keyLocal);
            b.endStaticMethod();
            b.endStoreLocal();

            emitUpdateInStep(gen, nextMapLocal, keys, index + 1, fnExpr, extraArgs, b);

            b.endBlock();
            b.endMapAssoc();
        }
    }

    static void emitUnrolledMergeMapLiteral(ExprToBytecode gen, Expr mExpr, MapLikeExpr mapLiteral, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keyvals = mapLiteral.keyvals();
        if (keyvals == null || keyvals.count() == 0) {
            gen.convert(mExpr, b);
            return;
        }
        int numPairs = keyvals.count() / 2;
        emitMergeStep(gen, mExpr, keyvals, numPairs - 1, b);
    }

    static void emitMergeStep(ExprToBytecode gen, Expr mExpr, IPersistentVector keyvals, int pairIndex, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keyvals.nth(2 * pairIndex);
        Expr valExpr = (Expr) keyvals.nth(2 * pairIndex + 1);
        if (pairIndex == 0) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                gen.convert(mExpr, b);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                gen.convert(mExpr, b);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
        } else {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                emitMergeStep(gen, mExpr, keyvals, pairIndex - 1, b);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                emitMergeStep(gen, mExpr, keyvals, pairIndex - 1, b);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
        }
    }

    static void emitUnrolledAssoc(ExprToBytecode gen, Expr mExpr, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b) {
        int numPairs = (args.count() - 1) / 2;
        emitAssocStep(gen, mExpr, args, numPairs - 1, b);
    }

    static void emitAssocStep(ExprToBytecode gen, Expr mExpr, IPersistentVector args, int pairIndex, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) args.nth(1 + 2 * pairIndex);
        Expr valExpr = (Expr) args.nth(2 + 2 * pairIndex);
        if (pairIndex == 0) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                gen.convert(mExpr, b);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                gen.convert(mExpr, b);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
        } else {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                emitAssocStep(gen, mExpr, args, pairIndex - 1, b);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                emitAssocStep(gen, mExpr, args, pairIndex - 1, b);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
        }
    }

    static void emitUnrolledDissoc(ExprToBytecode gen, Expr mExpr, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b) {
        int numKeys = args.count() - 1;
        emitDissocStep(gen, mExpr, args, numKeys - 1, b);
    }

    static void emitDissocStep(ExprToBytecode gen, Expr mExpr, IPersistentVector args, int keyIndex, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) args.nth(1 + keyIndex);
        if (keyIndex == 0) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordDissoc(ke.k);
                gen.convert(mExpr, b);
                b.endKeywordDissoc();
            } else {
                b.beginMapDissoc();
                gen.convert(mExpr, b);
                gen.convert(keyExpr, b);
                b.endMapDissoc();
            }
        } else {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordDissoc(ke.k);
                emitDissocStep(gen, mExpr, args, keyIndex - 1, b);
                b.endKeywordDissoc();
            } else {
                b.beginMapDissoc();
                emitDissocStep(gen, mExpr, args, keyIndex - 1, b);
                gen.convert(keyExpr, b);
                b.endMapDissoc();
            }
        }
    }

    static void emitUnrolledGetIn(ExprToBytecode gen, Expr mExpr, VectorLikeExpr pathExpr, Expr notFoundExpr, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args();
        int n = keys.count();
        if (n == 0) {
            gen.convert(mExpr, b);
            return;
        }
        emitGetChain(gen, mExpr, keys, 0, notFoundExpr, b);
    }

    static void emitGetChain(ExprToBytecode gen, Expr mExpr, IPersistentVector keys, int index, Expr notFoundExpr, CloffleBytecodeRootNodeGen.Builder b) {
        if (index == keys.count()) {
            gen.convert(mExpr, b);
            return;
        }
        Expr keyExpr = (Expr) keys.nth(keys.count() - 1 - index);
        boolean isLast = (index == 0);
        if (keyExpr instanceof KeywordExpr ke) {
            if (isLast && notFoundExpr != null) {
                b.beginKeywordLookupDefault(ke.k);
                emitGetChain(gen, mExpr, keys, index + 1, notFoundExpr, b);
                gen.convert(notFoundExpr, b);
                b.endKeywordLookupDefault();
            } else {
                b.beginKeywordLookup(ke.k);
                emitGetChain(gen, mExpr, keys, index + 1, notFoundExpr, b);
                b.endKeywordLookup();
            }
        } else {
            if (isLast && notFoundExpr != null) {
                b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                emitGetChain(gen, mExpr, keys, index + 1, notFoundExpr, b);
                gen.convert(keyExpr, b);
                gen.convert(notFoundExpr, b);
                b.endStaticMethod();
            } else {
                b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
                emitGetChain(gen, mExpr, keys, index + 1, notFoundExpr, b);
                gen.convert(keyExpr, b);
                b.endStaticMethod();
            }
        }
    }

    static void emitUnrolledAssocIn(ExprToBytecode gen, Expr mExpr, VectorLikeExpr pathExpr, Expr valExpr, CloffleBytecodeRootNodeGen.Builder b) {
        IPersistentVector keys = pathExpr.args();
        int n = keys.count();
        if (n == 0) {
            gen.convert(valExpr, b);
            return;
        }
        if (n == 1) {
            Expr keyExpr = (Expr) keys.nth(0);
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                gen.convert(mExpr, b);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                gen.convert(mExpr, b);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
            return;
        }
        b.beginBlock();
        BytecodeLocal mLocal = gen.createTrackedLocal(b);
        b.beginStoreLocal(mLocal);
        gen.convert(mExpr, b);
        b.endStoreLocal();

        emitAssocInStep(gen, mLocal, keys, 0, valExpr, b);

        b.endBlock();
    }

    static void emitAssocInStep(ExprToBytecode gen, BytecodeLocal currMapLocal, IPersistentVector keys, int index, Expr valExpr, CloffleBytecodeRootNodeGen.Builder b) {
        Expr keyExpr = (Expr) keys.nth(index);
        if (index == keys.count() - 1) {
            if (keyExpr instanceof KeywordExpr ke) {
                b.beginKeywordAssoc(ke.k);
                b.emitLoadLocal(currMapLocal);
                gen.convert(valExpr, b);
                b.endKeywordAssoc();
            } else {
                b.beginMapAssoc();
                b.emitLoadLocal(currMapLocal);
                gen.convert(keyExpr, b);
                gen.convert(valExpr, b);
                b.endMapAssoc();
            }
            return;
        }
        if (keyExpr instanceof KeywordExpr ke) {
            b.beginKeywordAssoc(ke.k);
            b.emitLoadLocal(currMapLocal);

            b.beginBlock();
            BytecodeLocal nextMapLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginKeywordLookup(ke.k);
            b.emitLoadLocal(currMapLocal);
            b.endKeywordLookup();
            b.endStoreLocal();

            emitAssocInStep(gen, nextMapLocal, keys, index + 1, valExpr, b);

            b.endBlock();

            b.endKeywordAssoc();
        } else {
            b.beginMapAssoc();
            b.emitLoadLocal(currMapLocal);
            gen.convert(keyExpr, b);

            b.beginBlock();
            BytecodeLocal nextMapLocal = gen.createTrackedLocal(b);
            b.beginStoreLocal(nextMapLocal);
            b.beginStaticMethod(RT.class, "get", Boolean.FALSE);
            b.emitLoadLocal(currMapLocal);
            gen.convert(keyExpr, b);
            b.endStaticMethod();
            b.endStoreLocal();

            emitAssocInStep(gen, nextMapLocal, keys, index + 1, valExpr, b);

            b.endBlock();

            b.endMapAssoc();
        }
    }
}
