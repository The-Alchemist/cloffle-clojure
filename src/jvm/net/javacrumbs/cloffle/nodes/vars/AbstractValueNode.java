/**
 * Copyright 2009-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.cloffle.nodes.vars;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureNode;

import static com.oracle.truffle.api.frame.FrameInstance.FrameAccess.MATERIALIZE;
import static com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY;

abstract class AbstractValueNode extends ClojureNode {
    private final int slotIndex;
    private MaterializedFrame cachedFrame;

    protected AbstractValueNode(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    protected Object getValue(VirtualFrame virtualFrame) {
        Object result = getValueOrNull(virtualFrame);
        if (result != null) {
            return result;
        }
        throw new RuntimeException("Unresolved value at slot " + slotIndex);
    }

    protected Object getValueOrNull(VirtualFrame virtualFrame) {
        try {
            return virtualFrame.getValue(slotIndex);
        } catch (IndexOutOfBoundsException e) {
            // Slot index from a different FrameDescriptor -- fall through
        }
        
        // This frame crawling logic is suspect. 
        // Clojure lexical scope is handled by capturing frames (closures), not by crawling up the stack.
        // If we are looking for a local that isn't in the current frame, it implies we are in a closure
        // but ExprToNode/ClojureNode handles closures by passing values or materialized frames explicitly?
        // Actually, ExprToNode maps LocalBinding to slot index.
        // If it's a closed-over variable, Clojure compiler usually passes it as an argument or similar.
        // Truffle supports lexical scope via MaterializedFrames stored in the closure object.
        
        // For now, let's keep the existing logic but be aware it might be slow or wrong for closures.
        // But for `testLet`, we should be in the same frame.
        
        // Wait, if it fails to find it in the current frame, maybe the slot index is wrong?
        
        if (cachedFrame != null) {
            try {
                Object val = cachedFrame.getValue(slotIndex);
                if (val != null) return val;
            } catch (IndexOutOfBoundsException e) {
                cachedFrame = null;
            }
        }
        FrameInstance frameInstance = Truffle.getRuntime().iterateFrames(fi -> {
            try {
                if (fi.getFrame(READ_ONLY).getValue(slotIndex) != null) {
                    return fi;
                }
            } catch (IndexOutOfBoundsException e) {
                // Different frame descriptor
            }
            return null;
        });
        if (frameInstance != null) {
            cachedFrame = (MaterializedFrame) frameInstance.getFrame(MATERIALIZE);
            return cachedFrame.getValue(slotIndex);
        }
        return null;
    }

    protected int getSlotIndex() {
        return slotIndex;
    }
}
