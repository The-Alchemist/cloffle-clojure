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

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class ClojureRootNode extends RootNode {

    @Child
    private ClojureNode node;
    private final boolean wrapResult;
    private SourceSection sourceSection;
    private String name;

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
        Object[] args = virtualFrame.getArguments();
        if (args.length > 0 && args[0] instanceof MaterializedFrame capturedFrame) {
            restoreCapturedFrame(capturedFrame, virtualFrame);
        }

        Object result;
        if (wrapResult) {
            try {
                result = node.executeGeneric(virtualFrame);
            } catch (ClojureException ce) {
                ce.publishFrames();
                throw ce;
            }
            return ClojureInterop.wrapForPolyglot(result);
        } else {
            result = node.executeGeneric(virtualFrame);
            return result;
        }
    }

    public static MaterializedFrame snapshotFrame(VirtualFrame virtualFrame) {
        FrameDescriptor fd = virtualFrame.getFrameDescriptor();
        MaterializedFrame snapshot =
                Truffle.getRuntime().createMaterializedFrame(virtualFrame.getArguments().clone(), fd);

        for (int i = 0; i < fd.getNumberOfSlots(); i++) {
            FrameSlotKind kind = fd.getSlotKind(i);
            Object value;
            try {
                value = virtualFrame.getValue(i);
            } catch (FrameSlotTypeException e) {
                // Bytecode DSL may leave typed locals uninitialized until first write.
                continue;
            }
            if (value == null) {
                continue;
            }
            // Named fn self-slot: setObject may not flip descriptor kind from Illegal; still must
            // copy into the materialized frame or recursive reads see uninitialized (e.g. concat's cat).
            if (kind == FrameSlotKind.Illegal) {
                snapshot.setObject(i, value);
                continue;
            }

            switch (kind) {
                case Long -> snapshot.setLong(i, ((Number) value).longValue());
                case Double -> snapshot.setDouble(i, ((Number) value).doubleValue());
                case Boolean -> snapshot.setBoolean(i, (Boolean) value);
                default -> snapshot.setObject(i, value);
            }
        }

        return snapshot;
    }

    private void restoreCapturedFrame(MaterializedFrame captured, VirtualFrame callee) {
        FrameDescriptor fd = getFrameDescriptor();
        FrameDescriptor capturedFd = captured.getFrameDescriptor();
        int n = fd.getNumberOfSlots();
        int capturedN = capturedFd.getNumberOfSlots();
        for (int i = 0; i < n; i++) {
            try {
                if (i >= capturedN) break;
                Object val = captured.getValue(i);
                if (val == null) {
                    continue;
                }
                FrameSlotKind kind = fd.getSlotKind(i);
                if (kind == FrameSlotKind.Illegal && i < capturedN) {
                    kind = capturedFd.getSlotKind(i);
                }

                switch (kind) {
                    case Long -> callee.setLong(i, ((Number) val).longValue());
                    case Double -> callee.setDouble(i, ((Number) val).doubleValue());
                    case Boolean -> callee.setBoolean(i, (Boolean) val);
                    default -> callee.setObject(i, val);
                }
            } catch (IndexOutOfBoundsException ignored) {
                break;
            }
        }
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static ClojureRootNode create(ClojureNode node, FrameDescriptor frameDescriptor, TruffleLanguage<?> language) {
        return new ClojureRootNode(node, frameDescriptor, language, true);
    }

    public static ClojureRootNode createRaw(ClojureNode node, FrameDescriptor frameDescriptor, TruffleLanguage<?> language) {
        return new ClojureRootNode(node, frameDescriptor, language, false);
    }
}
