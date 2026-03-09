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
package net.javacrumbs.cloffle.nodes.binding;

import clojure.lang.RT;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureNode;

/**
 * Initializes binding from argument value.
 * We skip the first argument (index 0) because it is always the captured frame (for closures)
 * or null (for top-level calls potentially, or just handled by consistent calling convention).
 * Wait, for top-level calls via CloffleMain, we might not pass a frame?
 *
 * If we standardize on "closures take captured frame as arg 0", then `ArgInitNode`
 * which corresponds to user arguments must start at index 1.
 */
public class ArgInitNode extends ClojureNode {
    private final int argIndex;

    public ArgInitNode(Long argId) {
        this.argIndex = RT.intCast(argId);
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        // User arguments start at index 1 (index 0 is captured frame)
        return virtualFrame.getArguments()[1 + argIndex];
    }
}
