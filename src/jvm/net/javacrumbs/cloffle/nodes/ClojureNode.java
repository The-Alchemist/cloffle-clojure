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


import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.ClojureTypes;
import net.javacrumbs.cloffle.ClojureTypesGen;

@GenerateWrapper
@NodeInfo(language = "Clojure in Truffle")
@TypeSystemReference(ClojureTypes.class)
@ExportLibrary(NodeLibrary.class)
public abstract class ClojureNode extends Node implements InstrumentableNode {

    private static final int NO_SOURCE = -1;

    private int sourceCharIndex = NO_SOURCE;
    private int sourceLength;

    /** When >= 1, source location is specified by line/column; otherwise by char index. */
    private int sourceLine = NO_SOURCE;
    private int sourceColumn = 1;
    private int sourceLengthByLine = 1;

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

    @Override
    public boolean isInstrumentable() {
        return hasSource();
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new ClojureNodeWrapper(this, probe);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return false;
    }

    public final void setSourceSection(int charIndex, int length) {
        assert sourceCharIndex == NO_SOURCE && sourceLine == NO_SOURCE : "source must only be set once";
        if (charIndex < 0) {
            throw new IllegalArgumentException("charIndex < 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        this.sourceCharIndex = charIndex;
        this.sourceLength = length;
    }

    /**
     * Sets source location by line and column (1-based). Use when the compiler
     * provides line/column (e.g. from Expr) for accurate stack traces and tooling.
     */
    public final void setSourceSectionByLine(int line, int column, int length) {
        assert sourceCharIndex == NO_SOURCE && sourceLine == NO_SOURCE : "source must only be set once";
        if (line < 1) {
            throw new IllegalArgumentException("line < 1");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column < 1");
        }
        if (length < 1) {
            length = 1;
        }
        this.sourceLine = line;
        this.sourceColumn = column;
        this.sourceLengthByLine = length;
    }

    @Override
    public SourceSection getSourceSection() {
        RootNode rootNode = getRootNode();
        if (rootNode == null) {
            return null;
        }
        SourceSection rootSourceSection = rootNode.getSourceSection();
        if (rootSourceSection == null) {
            return null;
        }
        Source source = rootSourceSection.getSource();
        if (sourceLine >= 1) {
            int line = Math.min(sourceLine, source.getLineCount());
            int col = sourceColumn;
            int len = sourceLengthByLine;
            try {
                int lineLen = source.getLineLength(line);
                if (col > lineLen) {
                    col = Math.max(1, lineLen);
                }
                if (col + len > lineLen + 1) {
                    len = Math.max(1, lineLen - col + 1);
                }
            } catch (Exception ignored) {
                col = 1;
                len = 1;
            }
            return source.createSection(line, col, len);
        }
        if (sourceCharIndex == NO_SOURCE) {
            return null;
        }
        if (sourceCharIndex + sourceLength > source.getLength()) {
            return source.createSection(sourceCharIndex,
                    Math.max(0, source.getLength() - sourceCharIndex));
        }
        return source.createSection(sourceCharIndex, sourceLength);
    }

    public final boolean hasSource() {
        return sourceCharIndex != NO_SOURCE || sourceLine >= 1;
    }

    public final int getSourceCharIndex() {
        return sourceCharIndex;
    }

    public final int getSourceLength() {
        return sourceLength;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NodeLibrary: expose local variables to debugger/tools
    // ═══════════════════════════════════════════════════════════════════

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasScope(@SuppressWarnings("unused") Frame frame) {
        return getRootNode() != null;
    }

    @ExportMessage
    Object getScope(Frame frame, @SuppressWarnings("unused") boolean nodeEnter)
            throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        RootNode root = getRootNode();
        if (root == null) {
            throw com.oracle.truffle.api.interop.UnsupportedMessageException.create();
        }
        return new ClojureScope(frame, root);
    }
}
