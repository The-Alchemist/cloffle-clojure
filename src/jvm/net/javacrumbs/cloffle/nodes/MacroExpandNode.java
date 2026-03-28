package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import clojure.lang.ISeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Guest node that invokes a macro IFn during macroexpansion.
 * By running the macro body inside a Truffle RootNode, exceptions
 * become ClojureExceptions with source location and guest stack frames,
 * producing better error messages for macro expansion failures.
 */
public class MacroExpandNode extends ClojureNode {

    private final IFn macroFn;

    public MacroExpandNode(IFn macroFn) {
        this.macroFn = macroFn;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] raw = virtualFrame.getArguments();
        ISeq args = (ISeq) raw[0];
        try {
            Object result = macroFn.applyTo(args);
            if (result == null) return NilNode.NIL;
            return result;
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }
}
