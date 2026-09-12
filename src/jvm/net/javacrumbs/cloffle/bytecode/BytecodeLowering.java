package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import clojure.lang.Var;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;

/**
 * Shared Tier 2 lowering plumbing: the sanctioned-root assumption every fast specialization runs
 * under, the {@code doRedefined} fallbacks that call whatever the Var now holds, and
 * {@code clojure.core/str} argument conversion.
 */
final class BytecodeLowering {
    private BytecodeLowering() {
    }

    /**
     * The assumption every Tier 2 fast specialization runs under: valid only while the Var still holds
     * the root that sanctioned the lowering.
     *
     * <p>{@code var.getRootAssumption()} on its own is not enough. {@code bindRoot} invalidates the old
     * assumption and installs a fresh <em>valid</em> one, so guarding on it alone lets the node
     * re-specialize straight back onto the intrinsic against a redefined root — the very bypass these
     * operations have to avoid. Returning {@link Assumption#NEVER_VALID} instead makes the Truffle DSL
     * decline to install the instance at all, so execution falls through to the {@code doRedefined}
     * specialization, which calls whatever the Var now holds.
     */
    static Assumption sanctionedRootAssumption(Var var) {
        Object sanctioned = var.getLoweringRoot();
        return sanctioned != null && sanctioned == var.getRawRoot()
                ? var.getRootAssumption()
                : Assumption.NEVER_VALID;
    }

    /** Redefined-path call at arity 1: the Var no longer holds the root that sanctioned the lowering. */
    static Object invokeRedefined(Var var, IndirectCallNode callNode, Object a0) {
        Object root = var.get();
        if (root instanceof ClojureClosure cc) {
            return BytecodeInvoke.callIndirect(
                    callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0});
        } else if (root instanceof IFn fn) {
            return BytecodeInvoke.invokeIFn(fn, a0);
        } else {
            return BytecodeInvoke.cannotCall(root);
        }
    }

    /** Redefined-path call at arity 2; constant operands become ordinary arguments again. */
    static Object invokeRedefined(Var var, IndirectCallNode callNode, Object a0, Object a1) {
        Object root = var.get();
        if (root instanceof ClojureClosure cc) {
            return BytecodeInvoke.callIndirect(
                    callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1});
        } else if (root instanceof IFn fn) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1);
        } else {
            return BytecodeInvoke.cannotCall(root);
        }
    }

    /** Redefined-path call at arity 3. */
    static Object invokeRedefined(Var var, IndirectCallNode callNode, Object a0, Object a1, Object a2) {
        Object root = var.get();
        if (root instanceof ClojureClosure cc) {
            return BytecodeInvoke.callIndirect(
                    callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1, a2});
        } else if (root instanceof IFn fn) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1, a2);
        } else {
            return BytecodeInvoke.cannotCall(root);
        }
    }

    /** Redefined-path call at arity 4. */
    static Object invokeRedefined(
            Var var, IndirectCallNode callNode, Object a0, Object a1, Object a2, Object a3) {
        Object root = var.get();
        if (root instanceof ClojureClosure cc) {
            return BytecodeInvoke.callIndirect(
                    callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1, a2, a3});
        } else if (root instanceof IFn fn) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1, a2, a3);
        } else {
            return BytecodeInvoke.cannotCall(root);
        }
    }

    /** Match {@code clojure.core/str} on one arg: nil → "", else {@code toString()}. */
    static String str(Object x) {
        return x == null ? "" : x.toString();
    }

    static String str(Object a, Object b) {
        return str(a) + str(b);
    }

    static String str(Object a, Object b, Object c) {
        return str(a) + str(b) + str(c);
    }

    static String str(Object a, Object b, Object c, Object d) {
        return str(a) + str(b) + str(c) + str(d);
    }
}
