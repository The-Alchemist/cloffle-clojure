package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.Expr;
import clojure.lang.IPersistentVector;
import clojure.lang.Var;

import java.util.function.Consumer;

final class ExprToBytecodeInvoke {
    private ExprToBytecodeInvoke() {
    }

    static void emitInvoke(Runnable emitCallee, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, java.util.function.Consumer<Expr> argConverter) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> {
                b.beginInvoke0();
                emitCallee.run();
                b.endInvoke0();
            }
            case 1 -> {
                b.beginInvoke1();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                b.endInvoke1();
            }
            case 2 -> {
                b.beginInvoke2();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                b.endInvoke2();
            }
            case 3 -> {
                b.beginInvoke3();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                b.endInvoke3();
            }
            case 4 -> {
                b.beginInvoke4();
                emitCallee.run();
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                argConverter.accept((Expr) args.nth(3));
                b.endInvoke4();
            }
            default -> {
                b.beginInvokeN();
                emitCallee.run();
                for (int i = 0; i < count; i++) {
                    argConverter.accept((Expr) args.nth(i));
                }
                b.endInvokeN();
            }
        }
    }

    static void emitInvokeVar(Var var, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, java.util.function.Consumer<Expr> argConverter) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> {
                b.emitInvokeVar0(var);
            }
            case 1 -> {
                b.beginInvokeVar1(var);
                argConverter.accept((Expr) args.nth(0));
                b.endInvokeVar1();
            }
            case 2 -> {
                b.beginInvokeVar2(var);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                b.endInvokeVar2();
            }
            case 3 -> {
                b.beginInvokeVar3(var);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                b.endInvokeVar3();
            }
            case 4 -> {
                b.beginInvokeVar4(var);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                argConverter.accept((Expr) args.nth(3));
                b.endInvokeVar4();
            }
            default -> {
                b.beginInvokeVarN(var);
                for (int i = 0; i < count; i++) {
                    argConverter.accept((Expr) args.nth(i));
                }
                b.endInvokeVarN();
            }
        }
    }

    /** Direct-linking: invoke a pinned IFn/ClojureClosure with no Var on the fast path. */
    static void emitInvokePinned(Object fn, IPersistentVector args, CloffleBytecodeRootNodeGen.Builder b, Consumer<Expr> argConverter) {
        int count = args == null ? 0 : args.count();
        switch (count) {
            case 0 -> b.emitInvokePinned0(fn);
            case 1 -> {
                b.beginInvokePinned1(fn);
                argConverter.accept((Expr) args.nth(0));
                b.endInvokePinned1();
            }
            case 2 -> {
                b.beginInvokePinned2(fn);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                b.endInvokePinned2();
            }
            case 3 -> {
                b.beginInvokePinned3(fn);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                b.endInvokePinned3();
            }
            case 4 -> {
                b.beginInvokePinned4(fn);
                argConverter.accept((Expr) args.nth(0));
                argConverter.accept((Expr) args.nth(1));
                argConverter.accept((Expr) args.nth(2));
                argConverter.accept((Expr) args.nth(3));
                b.endInvokePinned4();
            }
            default -> {
                b.beginInvokePinnedN(fn);
                for (int i = 0; i < count; i++) {
                    argConverter.accept((Expr) args.nth(i));
                }
                b.endInvokePinnedN();
            }
        }
    }
}
