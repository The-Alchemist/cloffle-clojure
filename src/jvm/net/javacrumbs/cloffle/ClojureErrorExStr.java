package net.javacrumbs.cloffle;

import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable messages from triage-shaped maps (same shape as {@code clojure.main/ex-triage}
 * output and {@link PolyglotErrorTriage#triage}). Mirrors {@code clojure.main/ex-str} formatting
 * for embedders that do not call into Clojure.
 *
 * <p>For {@code :macro-syntax-check} / {@code :execution} with {@code :clojure.error/spec},
 * this implementation appends {@link RT#printString(Object)} of the spec map (capped), not the
 * full {@code spec/explain-out} tree. Use {@code clojure.polyglot.error/triage-ex-str} from Clojure
 * when you need identical output to {@code clojure.main/ex-str}.
 */
public final class ClojureErrorExStr {

    private static final Keyword PHASE = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE = Keyword.intern("clojure.error", "source");
    private static final Keyword PATH = Keyword.intern("clojure.error", "path");
    private static final Keyword LINE = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN = Keyword.intern("clojure.error", "column");
    private static final Keyword SYMBOL = Keyword.intern("clojure.error", "symbol");
    private static final Keyword CLASS = Keyword.intern("clojure.error", "class");
    private static final Keyword CAUSE = Keyword.intern("clojure.error", "cause");
    private static final Keyword SPEC = Keyword.intern("clojure.error", "spec");
    private static final Keyword MACRO_STACK = Keyword.intern("clojure.error", "macro-stack");
    private static final Keyword GUEST_FRAMES = Keyword.intern("clojure.error", "guest-frames");

    private static final Keyword F_SOURCE = Keyword.intern(null, "source");
    private static final Keyword F_LINE = Keyword.intern(null, "line");
    private static final Keyword F_COLUMN = Keyword.intern(null, "column");
    private static final Keyword F_ROOT = Keyword.intern(null, "root-name");
    private static final Keyword F_SNIPPET = Keyword.intern(null, "snippet");

    private static final Pattern SIMPLE_CLASS = Pattern.compile("([^.]++)$");

    private ClojureErrorExStr() {}

    /**
     * @param triage map from {@link PolyglotErrorTriage#triage} or compatible; may be null/empty
     * @return formatted message, never null (empty map → empty string)
     */
    public static String formatTriageMessage(IPersistentMap triage) {
        if (triage == null || triage.count() == 0) {
            return "";
        }
        Keyword phase = keywordOrNull(triage.valAt(PHASE));
        if (phase == null) {
            phase = Keyword.intern(null, "execution");
        }
        String loc = locationString(triage);
        String cause = stringOrEmpty(triage.valAt(CAUSE));
        Symbol classSym = triage.valAt(CLASS) instanceof Symbol s ? s : null;
        Symbol sym = triage.valAt(SYMBOL) instanceof Symbol s ? s : null;
        Object spec = triage.valAt(SPEC);

        String causeType = causeTypeSuffix(classSym);
        String symStr = sym != null ? sym + " " : "";

        StringBuilder sb = new StringBuilder();
        switch (phase.getName()) {
            case "read-source" ->
                    sb.append(String.format("Syntax error reading source at (%s).%n%s%n", loc, cause));
            case "macro-syntax-check" -> {
                sb.append(String.format("Syntax error macroexpanding %sat (%s).%n", symStr, loc));
                if (spec != null) {
                    sb.append(specAppendix(spec));
                } else {
                    sb.append(String.format("%s%n", cause));
                }
            }
            case "macroexpansion" ->
                    sb.append(String.format("Unexpected error%s macroexpanding %sat (%s).%n%s%n",
                            causeType, symStr, loc, cause));
            case "compile-syntax-check" ->
                    sb.append(String.format("Syntax error%s compiling %sat (%s).%n%s%n",
                            causeType, symStr, loc, cause));
            case "compilation" ->
                    sb.append(String.format("Unexpected error%s compiling %sat (%s).%n%s%n",
                            causeType, symStr, loc, cause));
            case "read-eval-result" ->
                    sb.append(String.format("Error reading eval result%s at %s (%s).%n%s%n",
                            causeType, sym != null ? sym : "", loc, cause));
            case "print-eval-result" ->
                    sb.append(String.format("Error printing return value%s at %s (%s).%n%s%n",
                            causeType, sym != null ? sym : "", loc, cause));
            case "execution" -> {
                if (spec != null) {
                    sb.append(String.format("Execution error - invalid arguments to %s at (%s).%n%s",
                            sym != null ? sym : "unknown", loc, specAppendix(spec)));
                } else {
                    sb.append(String.format("Execution error%s at %s(%s).%n%s%n",
                            causeType, symStr, loc, cause));
                }
            }
            default ->
                    sb.append(String.format("Error (%s) at (%s).%n%s%n", phase.getName(), loc, cause));
        }

        Object macroStack = triage.valAt(MACRO_STACK);
        if (macroStack != null && RT.seq(macroStack) != null) {
            sb.append("  Macro stack: ").append(RT.printString(macroStack)).append(System.lineSeparator());
        }

        appendGuestFrames(sb, triage.valAt(GUEST_FRAMES));

        return sb.toString();
    }

    private static void appendGuestFrames(StringBuilder sb, Object frames) {
        if (frames == null || RT.seq(frames) == null) {
            return;
        }
        sb.append("\n  Guest frames:");
        for (ISeq s = RT.seq(frames); s != null; s = s.next()) {
            Object o = s.first();
            if (!(o instanceof Map<?, ?>)) {
                continue;
            }
            sb.append("\n    ");
            Object src = RT.get(o, F_SOURCE);
            sb.append(src != null ? String.valueOf(src) : "?");
            sb.append(':');
            Object line = RT.get(o, F_LINE);
            sb.append(line != null ? String.valueOf(line) : "?");
            sb.append(':');
            Object col = RT.get(o, F_COLUMN);
            sb.append(col != null ? String.valueOf(col) : "?");
            Object root = RT.get(o, F_ROOT);
            if (root != null) {
                sb.append("  (").append(root).append(')');
            }
            Object snip = RT.get(o, F_SNIPPET);
            if (snip != null) {
                sb.append("  ").append(snip);
            }
        }
        sb.append(System.lineSeparator());
    }

    private static String specAppendix(Object spec) {
        String printed = RT.printString(spec);
        final int cap = 4000;
        if (printed.length() > cap) {
            printed = printed.substring(0, cap - 3) + "...";
        }
        return printed + System.lineSeparator();
    }

    private static String locationString(IPersistentMap triage) {
        Object path = triage.valAt(PATH);
        Object source = triage.valAt(SOURCE);
        String file = path != null ? String.valueOf(path)
                : (source != null ? String.valueOf(source) : "REPL");
        int line = numberToInt(triage.valAt(LINE), 1);
        Object colObj = triage.valAt(COLUMN);
        if (colObj != null) {
            int col = numberToInt(colObj, 1);
            return file + ":" + line + ":" + col;
        }
        return file + ":" + line;
    }

    private static int numberToInt(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return dflt;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Keyword keywordOrNull(Object o) {
        return o instanceof Keyword k ? k : null;
    }

    private static String causeTypeSuffix(Symbol classSym) {
        if (classSym == null) {
            return "";
        }
        String className = classSym.getName();
        Matcher m = SIMPLE_CLASS.matcher(className);
        String simple = m.find() ? m.group(1) : className;
        if ("Exception".equals(simple) || "RuntimeException".equals(simple)) {
            return "";
        }
        return " (" + simple + ")";
    }
}
