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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.ClojureTypes;
import net.javacrumbs.cloffle.ClojureTypesGen;

@NodeInfo(language = "Clojure in Truffle")
@TypeSystemReference(ClojureTypes.class)
public abstract class ClojureNode extends Node {

    private static final int NO_SOURCE = -1;

    private int sourceCharIndex = NO_SOURCE;
    private int sourceLength;

    public abstract Object executeGeneric(VirtualFrame virtualFrame);

    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        return ClojureTypesGen.expectBoolean(this.executeGeneric(virtualFrame));
    }

    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        return ClojureTypesGen.expectLong(this.executeGeneric(virtualFrame));
    }

    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        return ClojureTypesGen.expectDouble(this.executeGeneric(virtualFrame));
    }

    public final void setSourceSection(int charIndex, int length) {
        assert sourceCharIndex == NO_SOURCE : "source must only be set once";
        if (charIndex < 0) {
            throw new IllegalArgumentException("charIndex < 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        this.sourceCharIndex = charIndex;
        this.sourceLength = length;
    }

    @Override
    @TruffleBoundary
    public final SourceSection getSourceSection() {
        if (sourceCharIndex == NO_SOURCE) {
            return null;
        }
        RootNode rootNode = getRootNode();
        if (rootNode == null) {
            return null;
        }
        SourceSection rootSourceSection = rootNode.getSourceSection();
        if (rootSourceSection == null) {
            return null;
        }
        Source source = rootSourceSection.getSource();
        if (sourceCharIndex + sourceLength > source.getLength()) {
            return source.createSection(sourceCharIndex,
                    Math.max(0, source.getLength() - sourceCharIndex));
        }
        return source.createSection(sourceCharIndex, sourceLength);
    }

    public final boolean hasSource() {
        return sourceCharIndex != NO_SOURCE;
    }

    public final int getSourceCharIndex() {
        return sourceCharIndex;
    }

    public final int getSourceLength() {
        return sourceLength;
    }
}
