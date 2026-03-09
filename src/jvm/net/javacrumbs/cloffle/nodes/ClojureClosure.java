package net.javacrumbs.cloffle.nodes;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.RT;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.MaterializedFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * A runtime closure: combines the compiled code (CallTarget) with the
 * captured lexical environment (MaterializedFrame).
 */
public class ClojureClosure extends AFn implements IFn {
    private final CallTarget callTarget;
    private final MaterializedFrame capturedFrame;

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame) {
        this.callTarget = callTarget;
        this.capturedFrame = capturedFrame;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public MaterializedFrame getCapturedFrame() {
        return capturedFrame;
    }

    // --- IFn implementation delegates to the CallTarget, passing capturedFrame as first arg ---

    private Object call(Object... args) {
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = capturedFrame;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        return ClojureInterop.unwrapFromPolyglot(callTarget.call(callArgs));
    }

    @Override
    public Object invoke() {
        return call();
    }

    @Override
    public Object invoke(Object a1) {
        return call(a1);
    }

    @Override
    public Object invoke(Object a1, Object a2) {
        return call(a1, a2);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3) {
        return call(a1, a2, a3);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4) {
        return call(a1, a2, a3, a4);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) {
        return call(a1, a2, a3, a4, a5);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        return call(a1, a2, a3, a4, a5, a6);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        return call(a1, a2, a3, a4, a5, a6, a7);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20) {
        return call(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20);
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
        return call(allArgs);
    }

    @Override
    public Object applyTo(ISeq arglist) {
        return call(RT.seqToArray(arglist));
    }
}
