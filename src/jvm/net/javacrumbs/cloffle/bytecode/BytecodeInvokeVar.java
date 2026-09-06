package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import clojure.lang.Var;
import com.oracle.truffle.api.nodes.DirectCallNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;

final class BytecodeInvokeVar {
    private BytecodeInvokeVar() {
    }

    static ClojureClosure getClojureClosure(Var var) {
        Object r = var.getRawRoot();
        return (r instanceof ClojureClosure cc) ? cc : null;
    }

    static IFn getIFn(Var var) {
        Object r = var.getRawRoot();
        return (r instanceof IFn fn && !(fn instanceof ClojureClosure)) ? fn : null;
    }

    static DirectCallNode createCallNode(ClojureClosure fn) {
        return fn != null ? DirectCallNode.create(fn.getCallTarget()) : null;
    }

    static Object[] withCapturedFrame(ClojureClosure fn, Object[] args) {
        return BytecodeInvoke.withCapturedFrame(fn, args);
    }
}
