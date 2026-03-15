package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class InstanceFieldNode extends ClojureNode {

    private final String fieldName;
    private final boolean requireField;

    @Child
    private ClojureNode instance;

    public InstanceFieldNode(String fieldName, ClojureNode instance, boolean requireField) {
        this.fieldName = fieldName;
        this.instance = instance;
        this.requireField = requireField;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Object evaluateInstance(VirtualFrame virtualFrame) {
        return instance.executeGeneric(virtualFrame);
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = instance.executeGeneric(virtualFrame);
        try {
            return ClojureInterop.wrapForPolyglot(Reflector.invokeNoArgInstanceMember(obj, fieldName, requireField));
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }
}
