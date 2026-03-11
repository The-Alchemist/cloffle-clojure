package net.javacrumbs.cloffle.nodes;

import clojure.lang.AFn;
import clojure.lang.ISeq;
import clojure.lang.RT;
import com.oracle.truffle.api.CallTarget;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * IFn adapter backed by a Truffle CallTarget. Exposes the CallTarget
 * so InvokeNode can use DirectCallNode for Truffle-optimized dispatch
 * while remaining a fully compliant IFn for Clojure interop.
 */
public class TruffleIFn extends AFn {

    private final CallTarget callTarget;

    public TruffleIFn(CallTarget callTarget) {
        this.callTarget = callTarget;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    private Object callTrampoline(Object... args) {
        CallTarget currentTarget = callTarget;
        Object currentClosureFrame = null;
        Object[] currentArgs = args;

        while (true) {
            Object[] callArgs = new Object[currentArgs.length + 1];
            callArgs[0] = currentClosureFrame;
            System.arraycopy(currentArgs, 0, callArgs, 1, currentArgs.length);
            try {
                return ClojureInterop.unwrapFromPolyglot(currentTarget.call(callArgs));
            } catch (TailCallException e) {
                currentTarget = e.getCallTarget();
                currentClosureFrame = e.getClosureFrame();
                currentArgs = e.getArgs();
            }
        }
    }

    @Override
    public Object invoke() {
        return callTrampoline();
    }

    @Override
    public Object invoke(Object a1) {
        return callTrampoline(a1);
    }

    @Override
    public Object invoke(Object a1, Object a2) {
        return callTrampoline(a1, a2);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3) {
        return callTrampoline(a1, a2, a3);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4) {
        return callTrampoline(a1, a2, a3, a4);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) {
        return callTrampoline(a1, a2, a3, a4, a5);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6) {
        return callTrampoline(a1, a2, a3, a4, a5, a6);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16, Object a17) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16, Object a17, Object a18) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16, Object a17, Object a18, Object a19) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16, Object a17, Object a18, Object a19, Object a20) {
        return callTrampoline(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5,
                         Object a6, Object a7, Object a8, Object a9, Object a10,
                         Object a11, Object a12, Object a13, Object a14, Object a15,
                         Object a16, Object a17, Object a18, Object a19, Object a20,
                         Object... rest) {
        Object[] allArgs = new Object[20 + rest.length];
        allArgs[0] = a1; allArgs[1] = a2; allArgs[2] = a3; allArgs[3] = a4;
        allArgs[4] = a5; allArgs[5] = a6; allArgs[6] = a7; allArgs[7] = a8;
        allArgs[8] = a9; allArgs[9] = a10; allArgs[10] = a11; allArgs[11] = a12;
        allArgs[12] = a13; allArgs[13] = a14; allArgs[14] = a15; allArgs[15] = a16;
        allArgs[16] = a17; allArgs[17] = a18; allArgs[18] = a19; allArgs[19] = a20;
        System.arraycopy(rest, 0, allArgs, 20, rest.length);
        return callTrampoline(allArgs);
    }

    @Override
    public Object applyTo(ISeq arglist) {
        return callTrampoline(RT.seqToArray(arglist));
    }
}
