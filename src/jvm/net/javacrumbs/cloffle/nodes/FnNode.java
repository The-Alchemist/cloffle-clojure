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
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

import java.util.function.Supplier;

public class FnNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.ExpressionTag.class;
    }

    @Children
    private final FnMethodNode[] fnMethodNodes;

    private FrameDescriptor frameDescriptor;
    private Supplier<FrameDescriptor> frameDescriptorSupplier;
    private Source source;
    private String fnName;
    private int thisSlot = -1;
    private com.oracle.truffle.api.TruffleLanguage<?> language;

    public FnNode(FnMethodNode[] fnMethodNodes) {
        this.fnMethodNodes = fnMethodNodes;
    }

    public void setLanguage(com.oracle.truffle.api.TruffleLanguage<?> language) {
        this.language = language;
    }

    public void setThisSlot(int slot) {
        this.thisSlot = slot;
    }

    public boolean hasSelfReference() {
        return thisSlot >= 0;
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
        int reqArity = 0;
        boolean isVariadic = false;
        for (FnMethodNode m : fnMethodNodes) {
            if (m.isVariadic()) {
                isVariadic = true;
                reqArity = m.getFixedArity();
                break;
            }
        }
        ClojureClosure closure = new ClojureClosure(getCallTarget(), null, reqArity, isVariadic);
        if (thisSlot >= 0) {
            virtualFrame.setObject(thisSlot, closure);
        }
        closure.setCapturedFrame(ClojureRootNode.snapshotFrame(virtualFrame));
        return closure;
    }

    /**
     * Source span for the first method's body (narrower than the whole {@code fn}/{@code defn} form),
     * used for function-entry roots so debuggers and breakpoints align with the implementation line.
     */
    private com.oracle.truffle.api.source.SourceSection preferredFunctionBodySection() {
        if (fnMethodNodes.length == 0) {
            return null;
        }
        ClojureNode b = fnMethodNodes[0].getBody();
        if (b == null) {
            return null;
        }
        com.oracle.truffle.api.source.SourceSection bs = b.getSourceSection();
        if (bs == null) {
            bs = b.getEncapsulatingSourceSection();
        }
        if (bs != null && bs.isAvailable()) {
            return bs;
        }
        return hasSource() ? getSourceSection() : null;
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
        String name = fnName != null ? fnName : "fn";
        String arities = ErrorMessages.formatArities(fnMethodNodes);
        String msg = "Wrong number of args (" + argCount + ") passed to " + name
                + " -- expected: " + arities;
        throw new ClojureException(msg, new clojure.lang.ArityException(argCount, name), this);
    }

    public IFn toIFn() {
        int reqArity = 0;
        boolean isVariadic = false;
        for (FnMethodNode m : fnMethodNodes) {
            if (m.isVariadic()) {
                isVariadic = true;
                reqArity = m.getFixedArity();
                break;
            }
        }
        return new ClojureClosure(getCallTarget(), null, reqArity, isVariadic);
    }

    private com.oracle.truffle.api.CallTarget getCallTarget() {
        FrameDescriptor fd = getFrameDescriptor();
        if (fd == null) {
            fd = new FrameDescriptor();
        }
        com.oracle.truffle.api.TruffleLanguage<?> lang = this.language;
        if (lang == null) {
            try {
                lang = Clojure.getContext().language();
            } catch (Exception e) {
                // ignore
            }
        }
        FnDispatchNode dispatchNode = new FnDispatchNode(this);
        com.oracle.truffle.api.source.SourceSection rootSection = preferredFunctionBodySection();
        if (rootSection != null && rootSection.isAvailable()) {
            dispatchNode.setSourceSection(rootSection.getCharIndex(), rootSection.getCharLength());
        } else if (hasSource()) {
            com.oracle.truffle.api.source.SourceSection fnSection = getSourceSection();
            if (fnSection != null && fnSection.isAvailable()) {
                dispatchNode.setSourceSection(fnSection.getCharIndex(), fnSection.getCharLength());
            }
        }
        ClojureRootNode rootNode = ClojureRootNode.createRaw(dispatchNode, fd, lang);
        if (source != null) {
            if (rootSection != null && rootSection.isAvailable()) {
                rootNode.setSourceSection(rootSection);
            } else {
                com.oracle.truffle.api.source.SourceSection formSection = getSourceSection();
                if (formSection != null && formSection.isAvailable()) {
                    rootNode.setSourceSection(formSection);
                } else {
                    rootNode.setSourceSection(source.createSection(0, source.getLength()));
                }
            }
        }
        if (fnName != null) {
            rootNode.setName(fnName);
        }
        return rootNode.getCallTarget();
    }
}
