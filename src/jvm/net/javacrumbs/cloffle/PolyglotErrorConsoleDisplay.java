package net.javacrumbs.cloffle;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureException;
import org.graalvm.polyglot.PolyglotException;

import java.io.PrintStream;
import java.util.List;

/**
 * Terminal rendering for {@link PolyglotException}: numbered source, multi-region underlines
 * (via {@link PolyglotErrorLocations}), triage-shaped messages (same pipeline as
 * {@link PolyglotErrorTriage#formatMessage(PolyglotException)}, without duplicating the textual guest-frame appendix),
 * and an optional call-stack summary.
 * <p>
 * <strong>Streams (default):</strong> Numbered source is written to {@link System#out}; the message and optional
 * stack summary go to {@link System#err}. That split keeps “normal” output separate from diagnostics, but
 * means copy-pasting a full error may interleave streams depending on the shell. Set
 * {@link #PROP_UNIFIED_DIAGNOSTICS} or environment variable {@link #ENV_UNIFIED_DIAGNOSTICS} to {@code true}
 * to print numbered source to stderr as well so the entire diagnostic block can be captured from one stream.
 * <p>
 * <strong>Verbosity:</strong> By default, when multiple source regions exist, the numbered listing already shows
 * each site; the compact {@code Call stack:} list (with source span lengths) is omitted. Set
 * {@link #PROP_VERBOSE} or {@link #ENV_VERBOSE} to {@code true} to print that list (useful for debugging
 * span alignment).
 * <p>
 * Shared by {@link CloffleRepl}, guest-side {@code cloffle.repl}, and demos/tests that want the same UX.
 */
public final class PolyglotErrorConsoleDisplay {

    /** When {@code true}, print numbered source to stderr so all diagnostics share one stream. */
    public static final String PROP_UNIFIED_DIAGNOSTICS = "cloffle.error.unifiedDiagnostics";

    public static final String ENV_UNIFIED_DIAGNOSTICS = "CLOFFLE_ERROR_UNIFIED_DIAGNOSTICS";

    /** When {@code true}, print the optional {@code Call stack:} list including {@code len=} span lengths. */
    public static final String PROP_VERBOSE = "cloffle.error.verbose";

    public static final String ENV_VERBOSE = "CLOFFLE_ERROR_VERBOSE";

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String DIM = "\u001B[2m";

    private static final String GUTTER = "      " + DIM + "│ " + RESET;

    private PolyglotErrorConsoleDisplay() {}

    /** Default: numbered source on stdout; set {@link #PROP_UNIFIED_DIAGNOSTICS} for stderr. */
    public static boolean isUnifiedErrorDiagnostics() {
        String p = System.getProperty(PROP_UNIFIED_DIAGNOSTICS);
        if (p != null) {
            return Boolean.parseBoolean(p.trim()) || "1".equals(p.trim());
        }
        String e = System.getenv(ENV_UNIFIED_DIAGNOSTICS);
        return e != null && ("1".equals(e) || Boolean.parseBoolean(e.trim()));
    }

    /** When true, print the optional {@code Call stack:} block with {@code len=} for each frame. */
    public static boolean isErrorDisplayVerbose() {
        String p = System.getProperty(PROP_VERBOSE);
        if (p != null) {
            return Boolean.parseBoolean(p.trim()) || "1".equals(p.trim());
        }
        String e = System.getenv(ENV_VERBOSE);
        return e != null && ("1".equals(e) || Boolean.parseBoolean(e.trim()));
    }

    /**
     * Prints numbered source with underlines, then a triage-shaped message (same pipeline as
     * {@link PolyglotErrorTriage#formatMessage(PolyglotException)} but without duplicating the textual
     * {@code Guest frames:} appendix, since regions are shown above and optionally in {@code Call stack:}).
     */
    public static void printError(String code, PolyglotException e) {
        List<PolyglotErrorLocations.Region> annotations = PolyglotErrorLocations.collect(e);
        String body = PolyglotErrorTriage.formatMessage(e, false);
        if (body.isEmpty()) {
            String fallback = e.getMessage();
            body = fallback != null ? fallback : "";
        }
        printErrorCore(code, annotations, body, false, null);
    }

    /**
     * Same layout as {@link #printError(String, PolyglotException)} for errors that never crossed the
     * polyglot boundary (e.g. caught in {@code cloffle.repl}). Delegates to the polyglot overload when
     * {@code t} is a {@link PolyglotException}.
     */
    public static void printError(String code, Throwable t) {
        if (t instanceof PolyglotException pe) {
            printError(code, pe);
            return;
        }
        List<PolyglotErrorLocations.Region> annotations = PolyglotErrorLocations.collectGuest(t);
        String phaseInfo = formatPhaseGuest(t, annotations, code);
        String label = phaseInfo != null ? phaseInfo : "Error: ";
        printErrorCore(code, annotations, rootMessage(t), true, label);
    }

    private static void printErrorCore(
            String code,
            List<PolyglotErrorLocations.Region> annotations,
            String message,
            boolean labelPlusMessage,
            String label) {
        PrintStream srcOut = isUnifiedErrorDiagnostics() ? System.err : System.out;
        if (!annotations.isEmpty()) {
            printNumberedSource(code, annotations, srcOut);
            srcOut.println();
        }

        String msg = message != null ? message : "";
        if (labelPlusMessage) {
            String lbl = label != null ? label : "Error: ";
            System.err.println(RED + BOLD + lbl + RESET + RED + msg + RESET);
        } else {
            for (String line : msg.split("\n", -1)) {
                System.err.println(RED + line + RESET);
            }
        }

        if (isErrorDisplayVerbose() && annotations.size() > 1) {
            System.err.println();
            System.err.println(CYAN + "  Call stack:" + RESET);
            for (int i = 0; i < annotations.size(); i++) {
                PolyglotErrorLocations.Region a = annotations.get(i);
                String prefix = i == 0 ? "──▶ " : "    ";
                System.err.println(
                        CYAN + "  " + prefix + stackRegionLocation(a) + RESET + stackFrameSuffix(a));
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        int guard = 0;
        while (x != null && x.getCause() != null && x.getCause() != x && guard++ < 32) {
            x = x.getCause();
        }
        String m = x != null ? x.getMessage() : null;
        return m != null ? m : (t != null ? t.getClass().getName() : "");
    }

    /** {@code file:line:col} only; drops {@code → snippet} which duplicates numbered source above. */
    public static String shortRegionLabel(PolyglotErrorLocations.Region a) {
        String lab = a.label();
        int arrow = lab.indexOf(" → ");
        return arrow >= 0 ? lab.substring(0, arrow) : lab;
    }

    /**
     * Location for the guest-frame stack list: {@link #shortRegionLabel} plus {@code len=} (source span
     * length in characters, same basis as {@link PolyglotErrorLocations.Region#length()}).
     */
    public static String stackRegionLocation(PolyglotErrorLocations.Region a) {
        return shortRegionLabel(a) + " len=" + a.length();
    }

    /**
     * {@code in ns/fn} when {@link PolyglotErrorLocations.Region#fnName()} is set; otherwise a short
     * form snippet from the region label (same text as after {@code →} in numbered source) so frames
     * without a root name (e.g. a {@code throw} site) still identify the form.
     */
    static String stackFrameSuffix(PolyglotErrorLocations.Region a) {
        if (a.fnName() != null && !a.fnName().isEmpty()) {
            return "  " + DIM + "in " + a.fnName() + RESET;
        }
        String hint = formHintFromRegionLabel(a.label());
        if (hint == null || hint.isEmpty()) {
            return "";
        }
        return "  " + DIM + hint + RESET;
    }

    /** Text after {@code " → "} in a region label, trimmed and capped, or {@code null}. */
    static String formHintFromRegionLabel(String label) {
        if (label == null) {
            return null;
        }
        int arrow = label.indexOf(" → ");
        if (arrow < 0) {
            return null;
        }
        String s = label.substring(arrow + 3).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > 48) {
            s = s.substring(0, 45) + "...";
        }
        return s;
    }

    /**
     * {@code source:line:column} for the caret label and phase banner.
     * <p>
     * Truffle {@link PolyglotErrorLocations.Region} uses: {@code line}/{@code startCol} = <em>first</em>
     * character of the attributed {@code SourceSection}; {@code endLine}/{@code endCol} = <em>last</em>
     * character. For a multi-line span that wraps {@code defn} + body, that last character is often the
     * closing {@code )} of the {@code defn}, not the end of the {@code (throw ...)} form. When we find
     * {@code (throw} on the end line, we narrow the displayed endpoint (and underline) to the balanced
     * list so the numbers match the highlighted range. Prefer fixing spans in the guest (bytecode
     * {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode} nests
     * {@link net.javacrumbs.cloffle.ast.ExprSourceSpans} per expr); this
     * stays as a display fallback when Polyglot still reports a wide multi-line primary region.
     */
    public static String displayFileLineCol(PolyglotErrorLocations.Region a, String fullSource) {
        String sl = shortRegionLabel(a);
        if (a.primary() && a.endLine() > a.line()) {
            int[] elc = adjustedEndLineAndColForPrimaryMulti(a, fullSource);
            sl = sl.replaceFirst(":\\d+:\\d+$", ":" + elc[0] + ":" + elc[1]);
        }
        return sl;
    }

    /** {@code [endLine, endCol]} 1-based inclusive column of the fault character for the banner. */
    static int[] adjustedEndLineAndColForPrimaryMulti(
            PolyglotErrorLocations.Region a, String fullSource) {
        if (fullSource == null) {
            return new int[]{a.endLine(), a.endCol()};
        }
        String[] lines = fullSource.split("\n", -1);
        int li = a.endLine() - 1;
        if (li < 0 || li >= lines.length) {
            return new int[]{a.endLine(), a.endCol()};
        }
        int[] tr = throwFormRange0(lines[li]);
        if (tr == null) {
            return new int[]{a.endLine(), a.endCol()};
        }
        return new int[]{a.endLine(), tr[0] + tr[1]};
    }

    /**
     * Balanced {@code (throw ...)} on one source line, or {@code null}. {@code tr[0]} is 0-based start
     * index, {@code tr[1]} is length in {@link String} code units (same basis as Truffle columns here).
     */
    static int[] throwFormRange0(String lineText) {
        int i = lineText.indexOf("(throw");
        if (i < 0) {
            return null;
        }
        int end = endExclusiveAfterBalancedList(lineText, i);
        if (end <= i) {
            return null;
        }
        return new int[]{i, end - i};
    }

    /** Exclusive end index after the {@code )} that matches {@code '('} at {@code openParenIndex}. */
    static int endExclusiveAfterBalancedList(String s, int openParenIndex) {
        if (openParenIndex < 0 || openParenIndex >= s.length() || s.charAt(openParenIndex) != '(') {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int j = openParenIndex; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inString) {
                if (c == '\\' && j + 1 < s.length()) {
                    j++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == ';') {
                while (j + 1 < s.length() && s.charAt(j + 1) != '\n') {
                    j++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j + 1;
                }
            }
        }
        return -1;
    }

    /** Numbered source lines; optional regions as underlines (default stream: stdout). */
    public static void printNumberedSource(String code, List<PolyglotErrorLocations.Region> annotations) {
        printNumberedSource(code, annotations, System.out);
    }

    /** Numbered source lines; optional regions as underlines. */
    public static void printNumberedSource(
            String code, List<PolyglotErrorLocations.Region> annotations, PrintStream out) {
        String[] lines = code.split("\n", -1);
        out.println();
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String lineText = lines[i];

            // Polyglot often attributes a multi-line span (e.g. defn head + body). For the primary
            // region, only underline the last line — the actual fault site (e.g. throw) — not the
            // enclosing form’s first line.
            List<PolyglotErrorLocations.Region> lineAnnotations = annotations.stream()
                    .filter(a -> primarySpanCoversLine(a, lineNum))
                    .toList();

            boolean isErrorLine = lineAnnotations.stream().anyMatch(PolyglotErrorLocations.Region::primary);

            String lineColor = isErrorLine ? RED : "";
            String lineReset = isErrorLine ? RESET : "";

            out.printf(DIM + "  %3d " + DIM + "│ " + RESET + "%s%s%s%n",
                    lineNum, lineColor, lineText, lineReset);

            for (PolyglotErrorLocations.Region a : lineAnnotations) {
                String color = a.primary() ? RED : YELLOW;
                int underlineStart;
                int underlineLen;
                boolean useCaret;

                if (a.endLine() > a.line()) {
                    if (lineNum == a.line()) {
                        underlineStart = Math.max(0, a.startCol() - 1);
                        underlineLen = Math.max(1, lineText.length() - underlineStart);
                        useCaret = false;
                    } else if (lineNum == a.endLine()) {
                        if (a.primary()) {
                            int[] tr = throwFormRange0(lineText);
                            if (tr != null) {
                                underlineStart = tr[0];
                                underlineLen = tr[1];
                            } else {
                                underlineStart = firstNonWhitespaceIndex(lineText);
                                underlineLen = Math.max(1, a.endCol() - underlineStart);
                            }
                        } else {
                            underlineStart = 0;
                            underlineLen = Math.max(1, lineText.length() - underlineStart);
                        }
                        useCaret = a.primary();
                    } else {
                        underlineStart = 0;
                        underlineLen = Math.max(1, lineText.length());
                        useCaret = false;
                    }
                } else {
                    underlineStart = Math.max(0, a.startCol() - 1);
                    underlineLen = Math.min(a.length(), Math.max(0, lineText.length() - underlineStart));
                    underlineLen = Math.max(1, underlineLen);
                    useCaret = a.primary();
                }

                StringBuilder squiggly = new StringBuilder();
                squiggly.append(GUTTER);
                squiggly.append(color);
                squiggly.append(" ".repeat(underlineStart));
                String locTag = a.primary() ? displayFileLineCol(a, code) : shortRegionLabel(a);
                String fnTag = (a.fnName() != null && !a.fnName().isEmpty())
                        ? DIM + "  in " + a.fnName() + RESET
                        : "";
                if (useCaret) {
                    squiggly.append("^");
                    squiggly.append("~".repeat(Math.max(0, underlineLen - 1)));
                    squiggly.append(" " + BOLD + locTag + RESET + fnTag);
                } else {
                    squiggly.append("~".repeat(underlineLen));
                    squiggly.append(" " + locTag + RESET + fnTag);
                }
                out.println(squiggly);
            }
        }
    }

    private static int firstNonWhitespaceIndex(String lineText) {
        int u = 0;
        while (u < lineText.length() && Character.isWhitespace(lineText.charAt(u))) {
            u++;
        }
        return u;
    }

    private static boolean primarySpanCoversLine(PolyglotErrorLocations.Region a, int lineNum) {
        if (a.primary() && a.endLine() > a.line()) {
            return lineNum == a.endLine();
        }
        return lineNum >= a.line() && lineNum <= a.endLine();
    }

    /**
     * Phase banner when the failure was not wrapped in {@link PolyglotException} (guest-only catch).
     */
    private static String formatPhaseGuest(
            Throwable t, List<PolyglotErrorLocations.Region> annotations, String fullSource) {
        Keyword phase = null;
        ClojureException guestCe = ClojureException.findFirstInChain(t);
        if (guestCe != null) {
            phase = guestCe.getPhase();
        }
        if (phase == null) {
            return null;
        }
        String phaseName = phase.getName();
        String location = "";
        if (!annotations.isEmpty()) {
            PolyglotErrorLocations.Region primary = annotations.get(0);
            location = " at (" + displayFileLineCol(primary, fullSource) + ")";
        }
        String category;
        if ("read-source".equals(phaseName) || "macro-syntax-check".equals(phaseName)) {
            category = "Syntax error";
        } else if ("macroexpansion".equals(phaseName)) {
            category = "Syntax error (macroexpansion)";
        } else if ("compile-syntax-check".equals(phaseName) || "compilation".equals(phaseName)) {
            category = "Compile error";
        } else if ("print-eval-result".equals(phaseName)) {
            category = "Error printing result";
        } else {
            category = "Execution error";
        }
        return category + " (" + phaseName + ")" + location + ": ";
    }
}
