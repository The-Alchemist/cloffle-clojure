package net.javacrumbs.cloffle.nodes;

import clojure.lang.AFn;
import clojure.lang.AFunction;
import clojure.lang.ISeq;
import clojure.lang.RT;
import clojure.lang.Util;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * A runtime closure: combines the compiled code (CallTarget) with the
 * captured lexical environment (MaterializedFrame).
 */
public class ClojureClosure extends AFunction {
    private final CallTarget callTarget;
    private MaterializedFrame capturedFrame;
    private final int requiredArity;
    private final boolean variadic;

    /**
     * Wraps an ISeq so VariadicArgInitNode can pass rest args lazily
     * without realizing the full sequence.
     */
    public static final class RestArgs {
        public final ISeq seq;
        public RestArgs(ISeq seq) { this.seq = seq; }
    }

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame) {
        this(callTarget, capturedFrame, 0, false);
    }

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame,
                          int requiredArity, boolean variadic) {
        this.callTarget = callTarget;
        this.capturedFrame = capturedFrame;
        this.requiredArity = requiredArity;
        this.variadic = variadic;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public MaterializedFrame getCapturedFrame() {
        return capturedFrame;
    }

    public void setCapturedFrame(MaterializedFrame capturedFrame) {
        this.capturedFrame = capturedFrame;
    }

    // --- IFn implementation delegates to the CallTarget, passing capturedFrame as first arg ---

    private Object doCall(Object... args) {
        CallTarget currentTarget = callTarget;
        Object currentCapturedFrame = capturedFrame;
        Object[] currentArgs = args;

        while (true) {
            Object[] callArgs = new Object[currentArgs.length + 1];
            callArgs[0] = currentCapturedFrame;
            System.arraycopy(currentArgs, 0, callArgs, 1, currentArgs.length);
            try {
                return ClojureInterop.unwrapFromPolyglot(currentTarget.call(callArgs));
            } catch (TailCallException e) {
                currentTarget = e.getCallTarget();
                currentCapturedFrame = e.getClosureFrame();
                currentArgs = e.getArgs();
            }
        }
    }

    @Override
    public Object invoke() {
        return doCall();
    }

    @Override
    public Object invoke(Object a1) {
        return doCall(a1);
    }

    @Override
    public Object invoke(Object a1, Object a2) {
        return doCall(a1, a2);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3) {
        return doCall(a1, a2, a3);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4) {
        return doCall(a1, a2, a3, a4);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) {
        return doCall(a1, a2, a3, a4, a5);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        return doCall(a1, a2, a3, a4, a5, a6);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        return doCall(a1, a2, a3, a4, a5, a6, a7);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20) {
        return doCall(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20, Object... rest) {
        Object[] allArgs = new Object[20 + rest.length];
        allArgs[0] = a1; allArgs[1] = a2; allArgs[2] = a3; allArgs[3] = a4;
        allArgs[4] = a5; allArgs[5] = a6; allArgs[6] = a7; allArgs[7] = a8;
        allArgs[8] = a9; allArgs[9] = a10; allArgs[10] = a11; allArgs[11] = a12;
        allArgs[12] = a13; allArgs[13] = a14; allArgs[14] = a15; allArgs[15] = a16;
        allArgs[16] = a17; allArgs[17] = a18; allArgs[18] = a19; allArgs[19] = a20;
        System.arraycopy(rest, 0, allArgs, 20, rest.length);
        return doCall(allArgs);
    }

    @Override
    public Object applyTo(ISeq arglist) {
        if (!variadic) {
            return AFn.applyToHelper(this, Util.ret1(arglist, arglist = null));
        }
        // For variadic fns, preserve exact-arity dispatch first so fixed-arity
        // methods with the same required prefix win (e.g. macro overloads).
        int bounded = RT.boundedLength(arglist, requiredArity + 1);
        if (bounded < requiredArity) {
            return AFn.applyToHelper(this, Util.ret1(arglist, arglist = null));
        }
        if (bounded == requiredArity) {
            return AFn.applyToHelper(this, Util.ret1(arglist, arglist = null));
        }
        // There are extra args beyond requiredArity: pass rest lazily.
        Object[] args = new Object[requiredArity + 1];
        ISeq s = arglist;
        for (int i = 0; i < requiredArity; i++) {
            args[i] = s.first();
            s = s.next();
        }
        args[requiredArity] = new RestArgs(RT.seq(s));
        return doCall(args);
    }
}
