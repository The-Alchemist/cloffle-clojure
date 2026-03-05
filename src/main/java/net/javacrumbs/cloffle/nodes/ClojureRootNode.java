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

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class ClojureRootNode extends RootNode {
    @Child
    private ClojureNode node;
    private final boolean wrapResult;

    private ClojureRootNode(ClojureNode node,
                           FrameDescriptor frameDescriptor,
                           TruffleLanguage<?> language,
                           boolean wrapResult) {
        super(language, frameDescriptor);
        this.node = node;
        this.wrapResult = wrapResult;
    }

    @Override
    public Object execute(VirtualFrame virtualFrame) {
        Object result;
        if (node instanceof FnNode fnNode) {
            result = fnNode.invoke(virtualFrame);
        } else {
            result = node.executeGeneric(virtualFrame);
        }
        return wrapResult ? ClojureInterop.wrapForPolyglot(result) : result;
    }

    public static ClojureRootNode create(ClojureNode node, FrameDescriptor frameDescriptor, TruffleLanguage<?> language) {
        return new ClojureRootNode(node, frameDescriptor, language, true);
    }

    public static ClojureRootNode createRaw(ClojureNode node, FrameDescriptor frameDescriptor, TruffleLanguage<?> language) {
        return new ClojureRootNode(node, frameDescriptor, language, false);
    }
}
