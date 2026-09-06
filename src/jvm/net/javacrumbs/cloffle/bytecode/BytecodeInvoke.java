package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import clojure.lang.RT;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import net.javacrumbs.cloffle.nodes.ClojureException;
import net.javacrumbs.cloffle.nodes.ErrorMessages;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

final class BytecodeInvoke {
    private BytecodeInvoke() {
    }

    static boolean isClojureClosure(IFn fn) {
        return fn instanceof ClojureClosure;
    }

    static Object[] withCapturedFrame(ClojureClosure fn, Object[] args) {
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = fn.getCapturedFrame();
        System.arraycopy(args, 0, callArgs, 1, args.length);
        return callArgs;
    }

    static Object callDirect(DirectCallNode callNode, Object[] args) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callNode.call(args));
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object callIndirect(IndirectCallNode callNode, CallTarget target, Object[] args) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callNode.call(target, args));
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFn(IFn fn) {
        try {
            return fn.invoke();
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFn(IFn fn, Object a0) {
        try {
            return fn.invoke(a0);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFn(IFn fn, Object a0, Object a1) {
        try {
            return fn.invoke(a0, a1);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFn(IFn fn, Object a0, Object a1, Object a2) {
        try {
            return fn.invoke(a0, a1, a2);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFn(IFn fn, Object a0, Object a1, Object a2, Object a3) {
        try {
            return fn.invoke(a0, a1, a2, a3);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeIFnVariadic(IFn fn, Object[] args) {
        try {
            switch (args.length) {
                case 0:
                    return fn.invoke();
                case 1:
                    return fn.invoke(args[0]);
                case 2:
                    return fn.invoke(args[0], args[1]);
                case 3:
                    return fn.invoke(args[0], args[1], args[2]);
                case 4:
                    return fn.invoke(args[0], args[1], args[2], args[3]);
                default:
                    return fn.applyTo(RT.seq(args));
            }
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object cannotCall(Object fn) {
        CompilerDirectives.transferToInterpreter();
        throw new ClojureException(ErrorMessages.cannotCallMessage(fn), null);
    }
}
