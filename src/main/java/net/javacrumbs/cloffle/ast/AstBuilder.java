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
package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import net.javacrumbs.cloffle.nodes.ClojureNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Arrays.asList;

public class AstBuilder {

    private final TruffleLanguage<?> language;
    private final List<AbstractNodeBuilder> builders;

    public AstBuilder() {
        this(null);
    }

    public AstBuilder(TruffleLanguage<?> language) {
        this.language = language;
        this.builders = asList(
        new ConstNodeBuilder(this),
        new IfNodeBuilder(this),
        new StaticCallNodeBuilder(this),
        new StaticFieldNodeBuilder(this),
        new InstanceCallNodeBuilder(this),
        new BindingNodeBuilder(this),
        new LocalNodeBuilder(this),
        new DoNodeBuilder(this),
        new FnMethodNodeBuilder(this),
        new FnNodeBuilder(this),
        new InvokeNodeBuilder(this),
        new KeywordInvokeNodeBuilder(this),
        new MapNodeBuilder(this),
        new VectorNodeBuilder(this),
        new SetNodeBuilder(this),
        new DefNodeBuilder(this),
        new QuoteNodeBuilder(this),
        new WithMetaNodeBuilder(this),
        new VarNodeBuilder(this),
        new TheVarNodeBuilder(this),
        new InstanceCheckNodeBuilder(this),
        new ThrowNodeBuilder(this),
        new NewNodeBuilder(this),
        new InstanceFieldNodeBuilder(this),
        new StaticFieldNodeBuilder(this),
        new SetBangNodeBuilder(this),
        new ImportNodeBuilder(this),
        new HostInteropNodeBuilder(this),
        new MonitorEnterNodeBuilder(this),
        new MonitorExitNodeBuilder(this),
        new ProtocolInvokeNodeBuilder(this),
        new ReifyNodeBuilder(this),
        new PrimInvokeNodeBuilder(this),
        new LetFnNodeBuilder(this),
        new DefTypeNodeBuilder(this),
        new CaseNodeBuilder(this),
        new LoopNodeBuilder(this),
        new RecurNodeBuilder(this),
        new LetNodeBuilder(this),
        new TryNodeBuilder(this)
        );
    }

    private final FrameDescriptor.Builder frameDescriptorBuilder = FrameDescriptor.newBuilder().defaultValue(null);
    private final Map<Object, Integer> slotByName = new HashMap<>();
    private FrameDescriptor frameDescriptor;

    /**
     * Find or add an indexed frame slot for the given name.
     * Returns the slot index for use with Frame.get/set methods.
     */
    public int findOrAddSlot(Object name) {
        return slotByName.computeIfAbsent(name,
                n -> frameDescriptorBuilder.addSlot(FrameSlotKind.Illegal, n, null));
    }

    public TruffleLanguage<?> getLanguage() {
        return language;
    }


    public ClojureNode build(Object node) {
        Map<Keyword, Object> tree = (Map<Keyword, Object>) Objects.requireNonNull(node);
        return builders.stream()
            .filter(b -> b.supports(tree))
            .findFirst().map(b -> b.buildNode(tree))
            .orElseThrow(() -> {
                Object op = tree.get(Keyword.intern("op"));
                String msg = tree.toString();
                if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
                return new AstBuildException("Unsupported op :" + op + " -- " + msg);
            });
    }

    public FrameDescriptor getFrameDescriptor() {
        if (frameDescriptor == null) {
            frameDescriptor = frameDescriptorBuilder.build();
        }
        return frameDescriptor;
    }
}
