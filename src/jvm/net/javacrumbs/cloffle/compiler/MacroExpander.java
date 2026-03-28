package net.javacrumbs.cloffle.compiler;

import clojure.lang.IFn;
import clojure.lang.ISeq;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.nodes.ClojureException;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.MacroExpandNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Runs a macro IFn invocation inside a minimal Truffle guest RootNode so that
 * failures in the macro body produce ClojureExceptions with source location
 * and guest stack frames.
 */
public final class MacroExpander {

    private MacroExpander() {}

    /**
     * Invoke a macro function through a Truffle guest node.
     *
     * @param macroFn  the macro IFn (from Var.deref)
     * @param args     the full arg list: (form &env arg1 arg2 ...)
     * @param form     the original macro call form (for source location)
     * @param macroName human-readable name like "clojure.core/when"
     * @return the macro-expanded form
     * @throws ClojureException if the macro body fails (with guest source location)
     */
    public static Object expandViaGuest(IFn macroFn, ISeq args, Object form, String macroName) {
        MacroExpandNode node = new MacroExpandNode(macroFn);

        String formStr = form.toString();
        if (formStr.length() > 200) {
            formStr = formStr.substring(0, 200) + "...";
        }
        Source source = Source.newBuilder("cloffle", formStr, "macroexpand").build();
        node.setSourceSection(0, Math.min(formStr.length(), source.getLength()));

        ClojureRootNode root = ClojureRootNode.createRaw(node, new FrameDescriptor(), null);
        root.setSourceSection(source.createSection(0, source.getLength()));
        if (macroName != null) {
            root.setName("macroexpand " + macroName);
        }

        try {
            Object result = root.getCallTarget().call(args);
            return result instanceof NilNode.Nil ? null : result;
        } catch (ClojureException ce) {
            ce.publishFrames();
            throw ce;
        }
    }
}
