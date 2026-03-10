package net.javacrumbs.cloffle.nodes.binding;

import clojure.lang.Symbol;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.value.DoubleNode;
import net.javacrumbs.cloffle.nodes.vars.LocalNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BindingNodeTest {

    private static final class InspectDoubleBindingNode extends ClojureNode {
        @Child
        private BindingNode binding;

        @Child
        private LocalNode localNode;

        private final int slot;

        private InspectDoubleBindingNode(BindingNode binding, int slot) {
            this.binding = binding;
            this.localNode = new LocalNode(slot);
            this.slot = slot;
        }

        @Override
        public Object executeGeneric(VirtualFrame virtualFrame) {
            binding.executeGeneric(virtualFrame);
            return new Object[] {
                    getRootNode().getFrameDescriptor().getSlotKind(slot),
                    localNode.executeGeneric(virtualFrame)
            };
        }
    }

    @Test
    public void doubleBindingPreservesDoubleSlotKind() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder().defaultValue(null);
        int slot = builder.addSlot(FrameSlotKind.Double, "x", null);
        FrameDescriptor frameDescriptor = builder.build();

        BindingNode binding = BindingNodeGen.create(Symbol.intern("x"), new DoubleNode(1.25), slot);
        Object[] result = (Object[]) ClojureRootNode
                .createRaw(new InspectDoubleBindingNode(binding, slot), frameDescriptor, null)
                .getCallTarget()
                .call();

        assertEquals(FrameSlotKind.Double, result[0]);
        assertEquals(1.25d, ((Number) result[1]).doubleValue(), 0.0d);
    }

    @Test
    public void rebindValuePreservesDoubleSlotKind() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder().defaultValue(null);
        int slot = builder.addSlot(FrameSlotKind.Double, "x", null);
        FrameDescriptor frameDescriptor = builder.build();

        BindingNode binding = BindingNodeGen.create(Symbol.intern("x"), new DoubleNode(1.25), slot);
        ClojureRootNode root = ClojureRootNode.createRaw(new ClojureNode() {
            @Child
            private BindingNode localBinding = binding;

            @Child
            private LocalNode localNode = new LocalNode(slot);

            @Override
            public Object executeGeneric(VirtualFrame virtualFrame) {
                localBinding.executeGeneric(virtualFrame);
                localBinding.rebindValue(2.75d, virtualFrame);
                return new Object[] {
                        getRootNode().getFrameDescriptor().getSlotKind(slot),
                        localNode.executeGeneric(virtualFrame)
                };
            }
        }, frameDescriptor, null);

        Object[] result = (Object[]) root.getCallTarget().call();
        assertEquals(FrameSlotKind.Double, result[0]);
        assertEquals(2.75d, ((Number) result[1]).doubleValue(), 0.0d);
    }
}
