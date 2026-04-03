package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Instruction-scoped locals via {@link BytecodeLocalScope}. {@link NodeLibrary#getScope} passes the same plain
 * {@link Frame} the API provides; introspection uses {@link BytecodeNode} local accessors on that frame (no
 * materialization at this boundary). Do not call {@link BytecodeNode#getBytecodeIndex(Frame)} from {@link #hasScope}:
 * it touches internal slot 0 before it may be initialized.
 */
@ExportLibrary(value = NodeLibrary.class, receiverType = TagTreeNode.class)
public final class CloffleBytecodeTagTreeNodeExports {

    private CloffleBytecodeTagTreeNodeExports() {
    }

    @ExportMessage
    @SuppressWarnings("unused")
    static boolean hasScope(TagTreeNode node, Frame frame) {
        if (frame == null) {
            return false;
        }
        if (isCloffleBytecode(node)) {
            return true;
        }
        return node.createDefaultScope(frame, true) != null;
    }

    @ExportMessage
    static Object getScope(TagTreeNode node, Frame frame, boolean nodeEnter)
            throws UnsupportedMessageException {
        if (isCloffleBytecode(node) && frame != null && frame.getFrameDescriptor().getNumberOfSlots() > 0) {
            BytecodeNode bc = node.getBytecodeNode();
            int bci = -1;
            if (frame.isInt(0)) {
                bci = bc.getBytecodeIndex(frame);
            } else {
                // Slot 0 may be illegal on debugger READ_ONLY views (e.g. right after step-into).
                // {@link BytecodeNode#findBytecodeIndex(Frame, Node)} also reads slot 0 — use the tag's BCI instead.
                bci = nodeEnter ? node.getEnterBytecodeIndex() : node.getReturnBytecodeIndex();
            }
            if (bci < 0) {
                bci = 0;
            }
            if (bci >= 0) {
                RootNode root = (RootNode) bc.getBytecodeRootNode();
                return new BytecodeLocalScope(frame, bc, bci, root);
            }
        }
        Object scope = node.createDefaultScope(frame, nodeEnter);
        if (scope == null) {
            throw UnsupportedMessageException.create();
        }
        return scope;
    }

    private static boolean isCloffleBytecode(TagTreeNode node) {
        BytecodeNode bc = node.getBytecodeNode();
        if (bc == null) {
            return false;
        }
        BytecodeRootNode br = bc.getBytecodeRootNode();
        return br instanceof CloffleBytecodeRootNode;
    }
}
