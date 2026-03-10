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
package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

import java.util.function.Supplier;

public class FnNode extends ClojureNode {

    @Children
    private final FnMethodNode[] fnMethodNodes;

    private FrameDescriptor frameDescriptor;
    private Supplier<FrameDescriptor> frameDescriptorSupplier;
    private Source source;

    public FnNode(FnMethodNode[] fnMethodNodes) {
        this.fnMethodNodes = fnMethodNodes;
    }

    public void setFrameDescriptor(FrameDescriptor fd) {
        this.frameDescriptor = fd;
    }

    public void setFrameDescriptorSupplier(Supplier<FrameDescriptor> supplier) {
        this.frameDescriptorSupplier = supplier;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public FrameDescriptor getFrameDescriptor() {
        if (frameDescriptor == null && frameDescriptorSupplier != null) {
            frameDescriptor = frameDescriptorSupplier.get();
        }
        return frameDescriptor;
    }

    public Source getSource() {
        return source;
    }

    public FnMethodNode[] getMethods() {
        return fnMethodNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return new ClojureClosure(getCallTarget(), snapshotFrame(virtualFrame));
    }

    private MaterializedFrame snapshotFrame(VirtualFrame virtualFrame) {
        FrameDescriptor fd = virtualFrame.getFrameDescriptor();
        MaterializedFrame snapshot =
                Truffle.getRuntime().createMaterializedFrame(virtualFrame.getArguments().clone(), fd);

        for (int i = 0; i < fd.getNumberOfSlots(); i++) {
            Object value = virtualFrame.getValue(i);
            if (value == null) {
                continue;
            }

            FrameSlotKind kind = fd.getSlotKind(i);
            switch (kind) {
                case Long -> snapshot.setLong(i, ((Number) value).longValue());
                case Double -> snapshot.setDouble(i, ((Number) value).doubleValue());
                case Boolean -> snapshot.setBoolean(i, (Boolean) value);
                default -> snapshot.setObject(i, value);
            }
        }

        return snapshot;
    }

    public Object invoke(VirtualFrame virtualFrame) {
        // Arg 0 is captured frame, so user args count is length - 1
        int argCount = virtualFrame.getArguments().length - 1;
        for (FnMethodNode method : fnMethodNodes) {
            if (!method.isVariadic() && method.getFixedArity() == argCount) {
                return method.executeGeneric(virtualFrame);
            }
        }
        for (FnMethodNode method : fnMethodNodes) {
            if (method.isVariadic() && argCount >= method.getFixedArity()) {
                return method.executeGeneric(virtualFrame);
            }
        }
        StringBuilder sb = new StringBuilder("Wrong number of args (")
                .append(argCount).append(") passed to fn. Available arities: ");
        for (int i = 0; i < fnMethodNodes.length; i++) {
            if (i > 0) sb.append(", ");
            FnMethodNode m = fnMethodNodes[i];
            sb.append(m.getFixedArity());
            if (m.isVariadic()) sb.append("+");
        }
        throw new clojure.lang.ArityException(argCount, sb.toString());
    }

    public IFn toIFn() {
        return new ClojureClosure(getCallTarget(), null);
    }

    private com.oracle.truffle.api.CallTarget getCallTarget() {
        FrameDescriptor fd = getFrameDescriptor();
        if (fd == null) {
            fd = new FrameDescriptor();
        }
        // Need to pass the language instance.
        Clojure language = null;
        try {
             language = (Clojure) Clojure.getContext().language();
        } catch (Exception e) {
             // ignore
        }
        ClojureRootNode rootNode = ClojureRootNode.createRaw(new FnDispatchNode(this), fd, language);
        if (source != null) {
            rootNode.setSourceSection(source.createSection(0, source.getLength()));
        }
        return rootNode.getCallTarget();
    }
}
