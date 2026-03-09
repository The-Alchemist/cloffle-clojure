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
        Object result;
        // FnNode should not be executed directly as a RootNode unless it's wrapped.
        // Wait, a RootNode *wraps* the AST.
        // If 'node' is a FnNode, we are likely executing the function body?
        // No, if 'node' is a FnNode, executeGeneric() returns the FnNode itself (closure).
        // But here we call fnNode.invoke(virtualFrame).
        
        // If we compiled a FnExpr, convert() returned a FnNode.
        // We put that FnNode into a ClojureRootNode.
        // When we call execute(), we want to GET the function object, not run the body.
        // The body should be run when the function object is invoked.
        
        // Wait, standard Clojure `(fn [])` evaluates to a function object.
        // So executeGeneric on FnNode returns `this`.
        // BUT here:
        /*
        if (node instanceof FnNode fnNode) {
            result = fnNode.invoke(virtualFrame);
        } else {
            result = node.executeGeneric(virtualFrame);
        }
        */
        // This logic seems to assume that if the RootNode contains a FnNode, we are executing the function *body*.
        // But for `((fn [x] x) 1)`, the outer form is an InvokeExpr.
        // convert(InvokeExpr) returns InvokeNode.
        // InvokeNode calls executeGeneric on its child (the FnNode), which returns the function object.
        // Then InvokeNode calls the function object.
        
        // So when is ClojureRootNode wrapping a FnNode directly?
        // Only if we compile just `(fn [x] x)`.
        // In that case, we want the result to be the function object.
        // We DO NOT want to invoke it with the frame arguments!
        
        // UNLESS this RootNode is the *implementation* of the function.
        // But FnNode contains FnMethodNodes, which contain the body.
        // The implementation of the function is inside FnMethodNode.
        
        // So if `node` is a FnNode at the top level, execute() should return the FnNode (or IFn wrapper).
        // It should NOT invoke it.
        
        // The existing code:
        // if (node instanceof FnNode fnNode) { result = fnNode.invoke(virtualFrame); }
        // This looks like it treats the RootNode as the function implementation.
        // But FnNode is an *Expression* that evaluates to a function.
        
        // Let's verify what happens when we compile `(fn [x] x)`.
        // ExprToNode returns a FnNode.
        // CloffleBackend wraps it in ClojureRootNode and calls call().
        // If we use the existing logic, it calls invoke(virtualFrame).
        // virtualFrame arguments are whatever we passed to call().
        // If we passed nothing, argCount is 0.
        // If the fn expects [x], it fails with ArityException.
        
        // THIS IS THE BUG.
        // A FnNode as a top-level expression should evaluate to the function, not execute it.
        
        // HOWEVER, when we create a CallTarget for the function *body*, that CallTarget needs a RootNode.
        // Does FnNode create its own RootNodes for methods?
        // FnNode.toIFn() creates a ClojureRootNode wrapping `this` (the FnNode).
        // And then returns a TruffleIFn wrapping that CallTarget.
        // When TruffleIFn is called, it calls the CallTarget.
        // The CallTarget executes the RootNode.
        // The RootNode executes... fnNode.invoke(virtualFrame).
        
        // So `toIFn` relies on this behavior!
        // But `compile` relies on the other behavior (evaluating to the function).
        
        // We have a conflict of interest for ClojureRootNode.
        // 1. As a wrapper for a script/expression: execute() should evaluate the expression.
        // 2. As a wrapper for a function implementation: execute() should run the function logic.
        
        // FnNode is currently serving both roles?
        // If FnNode is an expression, executeGeneric returns `this`.
        // If FnNode is a function implementation, invoke() runs the dispatch logic.
        
        // We need to distinguish between "compiling a script" and "creating a function call target".
        
        // In `toIFn`, we do: ClojureRootNode.createRaw(this, fd, ...).
        // In `CloffleBackend`, we do: ClojureRootNode.create(node, fd, ...).
        
        // We can check if we are in "function implementation mode" or "script mode".
        // Or we can have a separate FunctionRootNode.
        
        // The easiest fix is to change `toIFn` to use a specialized RootNode, or change how FnNode works.
        // But `toIFn` is what creates the runtime function object.
        
        // If `CloffleBackend` sees a `FnNode`, it wraps it in `ClojureRootNode`.
        // And then calls it.
        // It *expects* to get the function object back.
        // But `ClojureRootNode` sees `FnNode` and calls `invoke`.
        
        // We should wrap the FnNode in something else for the script, or change ClojureRootNode.
        // Actually, `FnNode` IS the literal.
        // The "Body" of the function is inside `FnMethodNode`.
        
        // If we change ClojureRootNode to NOT special case FnNode, then `toIFn` breaks.
        // Because `toIFn` wraps `FnNode` in `ClojureRootNode`.
        
        // Solution: `toIFn` should wrap a `FnDispatchNode` or similar, NOT the `FnNode` itself.
        // Or `FnNode` should have a flag?
        
        // Better: `FnNode` is the *definition* node.
        // `FnBodyNode` or `FnDispatchNode` should be the root of the function call.
        
        // Let's create a simple wrapper node for the script execution if it's a FnNode?
        // Or just wrap it in a `DoNode` or `BlockNode`?
        // `(do (fn ...))`
        
        // Let's modify `ClojureBackend` to wrap the node in a `GroupNode` or just rely on `ExprToNode` not returning raw FnNode?
        // No, `ExprToNode` returns `FnNode`.
        
        // Let's modify `ClojureRootNode` to take a flag?
        // We already have `create` and `createRaw`.
        // But both use the same execute logic.
        
        // Let's change `toIFn` in `FnNode.java`.
        // Instead of passing `this` (FnNode) to `ClojureRootNode`, pass a new node `FnInvokeNode(this)`.
        // And `ClojureRootNode` removes the special case.
        
        // Yes, that seems cleaner.
        // 1. Remove special case in ClojureRootNode.
        // 2. Create FnInvokeNode that calls fnNode.invoke().
        // 3. Update FnNode.toIFn() to wrap `this` in FnInvokeNode.
        
        result = node.executeGeneric(virtualFrame);
        return wrapResult ? ClojureInterop.wrapForPolyglot(result) : result;
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
