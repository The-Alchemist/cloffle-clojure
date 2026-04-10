/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * Modified for Cloffle: the stock implementation labels every language top scope as
 * {@code "Global"}. Use {@link com.oracle.truffle.api.debug.DebugScope#getName()} when
 * non-empty so guest languages can surface a clearer label (e.g. Clojure namespace vars).
 */
package com.oracle.truffle.tools.dap.server;

import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SourceElement;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.tools.dap.types.Scope;
import com.oracle.truffle.tools.dap.types.StackFrame;
import com.oracle.truffle.tools.dap.types.Variable;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public final class StackFramesHandler {

    private final ExecutionContext context;
    private final DebuggerSession debuggerSession;

    public StackFramesHandler(ExecutionContext context, DebuggerSession debuggerSession) {
        this.context = context;
        this.debuggerSession = debuggerSession;
    }

    public List<StackFrame> getStackTrace(ThreadsHandler.SuspendedThreadInfo info) {
        ArrayList<StackFrame> sfs = new ArrayList<>();
        boolean top = true;
        for (DebugStackFrame frame : info.getSuspendedEvent().getStackFrames()) {
            SourceSection sourceSection = frame.getSourceSection();
            if (sourceSection == null || !sourceSection.isAvailable()) {
                continue;
            }
            if (!this.context.isInspectInternal() && frame.isInternal()) {
                continue;
            }
            Source source = sourceSection.getSource();
            if (!this.context.isInspectInternal() && source.isInternal()) {
                continue;
            }
            com.oracle.truffle.tools.dap.types.Source dapSource =
                    this.context.getLoadedSourcesHandler().assureLoaded(source);
            SuspendAnchor anchor = SuspendAnchor.BEFORE;
            DebugValue returnValue = null;
            if (top) {
                anchor = info.getSuspendedEvent().getSuspendAnchor();
                if (info.getSuspendedEvent().hasSourceElement(SourceElement.ROOT)) {
                    returnValue = info.getSuspendedEvent().getReturnValue();
                }
            }
            if (anchor == SuspendAnchor.BEFORE) {
                sfs.add(
                        StackFrame.create(
                                        info.getId(new FrameWrapper(frame, returnValue)),
                                        frame.getName(),
                                        this.context.debuggerToClientLine(sourceSection.getStartLine()),
                                        this.context.debuggerToClientColumn(sourceSection.getStartColumn()))
                                .setSource(dapSource));
            } else {
                sfs.add(
                        StackFrame.create(
                                        info.getId(new FrameWrapper(frame, returnValue)),
                                        frame.getName(),
                                        this.context.debuggerToClientLine(sourceSection.getEndLine()),
                                        this.context.debuggerToClientColumn(sourceSection.getEndColumn() + 1))
                                .setSource(dapSource));
            }
            top = false;
        }
        return sfs;
    }

    public List<Scope> getScopes(ThreadsHandler.SuspendedThreadInfo info, int frameId) {
        FrameWrapper frameWrapper = info.getById(FrameWrapper.class, frameId);
        DebugStackFrame frame = frameWrapper != null ? frameWrapper.getFrame() : null;
        if (frame == null) {
            return null;
        }
        ArrayList<Scope> scopes = new ArrayList<>();
        DebugScope dscope;
        try {
            dscope = frame.getScope();
        } catch (DebugException ex) {
            printScopeError("getScope() has caused ", ex);
            dscope = null;
        }
        String scopeName = "Block";
        boolean wasFunction = false;
        ScopeWrapper topScopeWrapper = null;
        DebugValue thisValue = null;
        while (dscope != null) {
            if (wasFunction) {
                scopeName = "Closure";
            } else if (dscope.isFunctionScope()) {
                scopeName = "Local";
                thisValue = dscope.getReceiver();
                wasFunction = true;
            }
            if (dscope.isFunctionScope() || dscope.getDeclaredValues().iterator().hasNext()) {
                if (scopes.isEmpty()) {
                    topScopeWrapper = new ScopeWrapper(frameWrapper, dscope);
                    scopes.add(Scope.create(scopeName, info.getId(topScopeWrapper), false));
                } else {
                    scopes.add(
                            Scope.create(
                                    scopeName, info.getId(new ScopeWrapper(frameWrapper, dscope)), false));
                }
            }
            dscope = getParent(dscope);
        }
        if (thisValue != null && topScopeWrapper != null) {
            topScopeWrapper.thisValue = thisValue;
        }
        DebugScope topScope = null;
        try {
            topScope =
                    debuggerSession.getTopScope(frame.getSourceSection().getSource().getLanguage());
        } catch (DebugException ex) {
            printScopeError("getTopScope() has caused ", ex);
        }
        dscope = topScope;
        while (dscope != null) {
            if (dscope.isFunctionScope() || dscope.getDeclaredValues().iterator().hasNext()) {
                scopes.add(Scope.create(topScopeDapName(dscope), info.getId(dscope), true));
            }
            dscope = getParent(dscope);
        }
        return scopes;
    }

    /**
     * Stock dap-tool uses the literal {@code "Global"}. Prefer {@link DebugScope#getName()}
     * (from guest scope {@code toDisplayString}) when present.
     */
    private static String topScopeDapName(DebugScope dscope) {
        try {
            String name = dscope.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return "Global";
    }

    private void printScopeError(String prefix, DebugException ex) {
        PrintWriter err = this.context.getErr();
        if (err != null) {
            err.println(prefix + ex);
            ex.printStackTrace(err);
        }
    }

    public static Variable evaluateOnStackFrame(ThreadsHandler.SuspendedThreadInfo info, int frameId, String expression)
            throws DebugException {
        FrameWrapper frameWrapper = info.getById(FrameWrapper.class, frameId);
        DebugStackFrame frame = frameWrapper != null ? frameWrapper.getFrame() : null;
        if (frame != null) {
            DebugValue value = VariablesHandler.getDebugValue(frame, expression);
            if (value != null) {
                return VariablesHandler.createVariable(info, value, "");
            }
        }
        return null;
    }

    private DebugScope getParent(DebugScope dscope) {
        try {
            return dscope.getParent();
        } catch (DebugException ex) {
            PrintWriter err = this.context.getErr();
            if (err != null) {
                err.println("Scope.getParent() has caused " + ex);
                ex.printStackTrace(err);
            }
            return null;
        }
    }

    private static final class FrameWrapper {
        private final DebugStackFrame frame;
        private final DebugValue returnValue;

        private FrameWrapper(DebugStackFrame frame, DebugValue returnValue) {
            this.frame = frame;
            this.returnValue = returnValue;
        }

        public DebugValue getReturnValue() {
            return this.returnValue;
        }

        public DebugStackFrame getFrame() {
            return this.frame;
        }
    }

    static final class ScopeWrapper {
        private final FrameWrapper frame;
        private final DebugScope scope;
        private DebugValue thisValue;

        private ScopeWrapper(FrameWrapper frame, DebugScope scope) {
            this.frame = frame;
            this.scope = scope;
        }

        public DebugValue getThisValue() {
            return this.thisValue;
        }

        public DebugValue getReturnValue() {
            return this.frame.getReturnValue();
        }

        public DebugStackFrame getFrame() {
            return this.frame.getFrame();
        }

        public DebugScope getScope() {
            return this.scope;
        }
    }
}
