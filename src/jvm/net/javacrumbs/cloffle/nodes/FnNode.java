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
import com.oracle.truffle.api.frame.FrameDescriptor;
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
    private String fnName;
    private int thisSlot = -1;

    public FnNode(FnMethodNode[] fnMethodNodes) {
        this.fnMethodNodes = fnMethodNodes;
    }

    public void setThisSlot(int slot) {
        this.thisSlot = slot;
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

    public void setFnName(String fnName) {
        this.fnName = fnName;
    }

    public String getFnName() {
        return fnName;
    }

    public FnMethodNode[] getMethods() {
        return fnMethodNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        ClojureClosure closure = new ClojureClosure(getCallTarget(), null);
        if (thisSlot >= 0) {
            virtualFrame.setObject(thisSlot, closure);
        }
        closure.setCapturedFrame(ClojureRootNode.snapshotFrame(virtualFrame));
        return closure;
    }

    public Object invoke(VirtualFrame virtualFrame) {
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
        String arities = ErrorMessages.formatArities(fnMethodNodes);
        String name = fnName != null ? fnName : "fn";
        throw new ClojureException(
                "ArityException: Wrong number of args (" + argCount
                        + ") passed to " + name + ". Expected: " + arities,
                this);
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
        if (fnName != null) {
            rootNode.setName(fnName);
        }
        return rootNode.getCallTarget();
    }
}
