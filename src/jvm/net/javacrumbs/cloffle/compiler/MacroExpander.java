package net.javacrumbs.cloffle.compiler;

import clojure.lang.IFn;
import clojure.lang.IMeta;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.RT;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
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

    private static final ThreadLocal<Source> CURRENT_SOURCE = new ThreadLocal<>();
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    private MacroExpander() {}

    /**
     * Set the Truffle Source for the file currently being compiled.
     * Call this before {@code Compiler.macroexpand()} so that macro
     * expansion errors can reference the real source file.
     */
    public static void setCurrentSource(Source source) {
        CURRENT_SOURCE.set(source);
    }

    public static void clearCurrentSource() {
        CURRENT_SOURCE.remove();
    }

    public static Object expandViaGuest(IFn macroFn, ISeq args, Object form, String macroName) {
        MacroExpandNode node = new MacroExpandNode(macroFn);

        Source source = CURRENT_SOURCE.get();
        SourceSection rootSection;

        if (source != null) {
            rootSection = buildSectionFromMeta(source, form);
            setNodeSourceFromMeta(node, form);
        } else {
            // ASeq.toString() uses RT.printString, which dispatches print-method on (:type (meta x)).
            // Macro forms like ^{:type ::into-schema} (reify ...) would then invoke user print-methods
            // on the unevaluated list during expansion (see MalliIntoSchemaReproTest).
            Object formForLabel = RT.stripTypeMetaForMacroSourceLabel(form);
            String formStr = formForLabel.toString();
            if (formStr.length() > 200) {
                formStr = formStr.substring(0, 200) + "...";
            }
            source = Source.newBuilder("cloffle", formStr, "macroexpand").build();
            rootSection = source.createSection(0, source.getLength());
            node.setSourceSection(0, Math.min(formStr.length(), source.getLength()));
        }

        ClojureRootNode root = ClojureRootNode.createRaw(node, new FrameDescriptor(), null);
        root.setSourceSection(rootSection);
        if (macroName != null) {
            root.setName("macroexpand " + macroName);
        }

        try {
            Object result = root.getCallTarget().call(args);
            return result instanceof NilNode.Nil ? null : result;
        } catch (ClojureException ce) {
            throw ce;
        }
    }

    private static void setNodeSourceFromMeta(MacroExpandNode node, Object form) {
        if (form instanceof IMeta meta) {
            IPersistentMap m = meta.meta();
            if (m != null) {
                Object lineObj = m.valAt(LINE_KEY);
                Object colObj = m.valAt(COLUMN_KEY);
                if (lineObj instanceof Number) {
                    int line = ((Number) lineObj).intValue();
                    int col = (colObj instanceof Number) ? ((Number) colObj).intValue() : 1;
                    node.setSourceSectionByLine(line, col, 1);
                    return;
                }
            }
        }
        node.setSourceSection(0, 1);
    }

    private static SourceSection buildSectionFromMeta(Source source, Object form) {
        if (form instanceof IMeta meta) {
            IPersistentMap m = meta.meta();
            if (m != null) {
                Object lineObj = m.valAt(LINE_KEY);
                Object colObj = m.valAt(COLUMN_KEY);
                if (lineObj instanceof Number) {
                    int line = ((Number) lineObj).intValue();
                    int col = (colObj instanceof Number) ? ((Number) colObj).intValue() : 1;
                    if (line >= 1 && line <= source.getLineCount()) {
                        int startOffset = source.getLineStartOffset(line);
                        int lineLen = source.getLineLength(line);
                        int colOffset = Math.max(0, Math.min(col - 1, lineLen - 1));
                        int charIdx = startOffset + colOffset;
                        int length = Math.max(1, lineLen - colOffset);
                        return source.createSection(charIdx, Math.max(1, length));
                    }
                }
            }
        }
        return source.createSection(0, Math.min(1, source.getLength()));
    }
}
