package net.javacrumbs.cloffle.nodes;

import clojure.lang.AFn;
import clojure.lang.AFunction;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.RT;
import clojure.lang.Util;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * A runtime closure: combines the compiled code (CallTarget) with the
 * captured lexical environment (MaterializedFrame).
 */
public class ClojureClosure extends AFunction {
    private final CallTarget callTarget;
    @CompilerDirectives.CompilationFinal
    private MaterializedFrame capturedFrame;
    private final int requiredArity;
    private final boolean variadic;
    private final IPersistentMap meta;
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private Object[] callArgs0;

    /**
     * Wraps an ISeq so VariadicArgInitNode can pass rest args lazily
     * without realizing the full sequence.
     */
    public static final class RestArgs {
        public final ISeq seq;
        public RestArgs(ISeq seq) { this.seq = seq; }
    }

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame) {
        this(callTarget, capturedFrame, 0, false, null);
    }

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame,
                          int requiredArity, boolean variadic) {
        this(callTarget, capturedFrame, requiredArity, variadic, null);
    }

    public ClojureClosure(CallTarget callTarget, MaterializedFrame capturedFrame,
                          int requiredArity, boolean variadic, IPersistentMap meta) {
        this.callTarget = callTarget;
        this.capturedFrame = capturedFrame;
        this.requiredArity = requiredArity;
        this.variadic = variadic;
        this.meta = meta;
        this.callArgs0 = new Object[]{capturedFrame};
    }

    @Override
    public IPersistentMap meta() {
        return meta;
    }

    @Override
    public IObj withMeta(IPersistentMap newMeta) {
        if (this.meta == newMeta) {
            return this;
        }
        return new ClojureClosure(callTarget, capturedFrame, requiredArity, variadic, newMeta);
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public MaterializedFrame getCapturedFrame() {
        return capturedFrame;
    }

    public void setCapturedFrame(MaterializedFrame capturedFrame) {
        if (this.capturedFrame != null && this.capturedFrame != capturedFrame) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        this.capturedFrame = capturedFrame;
        this.callArgs0 = new Object[]{capturedFrame};
    }

    /**
     * Readable label for debuggers and {@link clojure.lang.AFn}'s {@code toDisplayString} (which uses
     * {@code toString()}). Avoids the default {@code ClassName@hash} form.
     */
    @Override
    public String toString() {
        return debuggerLabel();
    }

    @CompilerDirectives.TruffleBoundary
    private String debuggerLabel() {
        try {
            if (callTarget instanceof RootCallTarget rct) {
                RootNode root = rct.getRootNode();
                if (root != null) {
                    String n = root.getName();
                    if (n != null && !n.isEmpty()) {
                        if (!"CloffleBytecodeRootNode".equals(n)) {
                            return "#<fn " + n + ">";
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "#<fn>";
    }

    // --- IFn implementation delegates to the CallTarget, passing capturedFrame as first arg ---

    private Object doCall0() {
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(callArgs0));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    private Object doCall1(Object a1) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(new Object[]{capturedFrame, a1}));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    private Object doCall2(Object a1, Object a2) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(new Object[]{capturedFrame, a1, a2}));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    private Object doCall3(Object a1, Object a2, Object a3) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(new Object[]{capturedFrame, a1, a2, a3}));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    private Object doCall4(Object a1, Object a2, Object a3, Object a4) {
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(new Object[]{capturedFrame, a1, a2, a3, a4}));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    private Object doCall(Object... args) {
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = capturedFrame;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        try {
            return ClojureInterop.unwrapFromPolyglot(callTarget.call(callArgs));
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException fste) {
            throw new ClojureException(fste.getMessage(), fste, null);
        }
    }

    @Override
    public Object invoke() {
        return doCall0();
    }

    @Override
    public Object invoke(Object a1) {
        return doCall1(a1);
    }

    @Override
    public Object invoke(Object a1, Object a2) {
        return doCall2(a1, a2);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3) {
        return doCall3(a1, a2, a3);
    }

    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4) {
        return doCall4(a1, a2, a3, a4);
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
