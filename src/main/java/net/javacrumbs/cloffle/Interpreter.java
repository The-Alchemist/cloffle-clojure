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
package net.javacrumbs.cloffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.value.NilNode.Nil;

public class Interpreter {

    private final FrameDescriptor frameDescriptor;
    private final TruffleLanguage<?> language;

    /** @deprecated With org.graalvm.truffle use Context.eval("cloffle", expr) instead */
    @Deprecated
    public Interpreter(FrameDescriptor frameDescriptor) {
        this(frameDescriptor, null);
    }

    public Interpreter(FrameDescriptor frameDescriptor, TruffleLanguage<?> language) {
        this.frameDescriptor = frameDescriptor;
        this.language = language;
    }

    public Object interpret(ClojureNode node) {
        if (language == null) {
            throw new IllegalStateException("Use Context.eval(\"cloffle\", expr) or Interpreter(fd, language)");
        }
        CallTarget callTarget = ClojureRootNode.create(node, frameDescriptor, language).getCallTarget();
        Object result = callTarget.call();
        return result != Nil.VALUE ? result : null;
    }
}
