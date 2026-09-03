package net.javacrumbs.cloffle;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureException;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves source regions from a {@link PolyglotException} for editors and diagnostics: guest stack
 * {@link SourceSection}s (skipping useless whole-file spans when narrower ones exist),
 * {@link ClojureException#getEnrichedFrames() enriched Clojure call frames} (via {@link ClojureException#findForPolyglotWithEnrichedFrames}), then
 * Polyglot's top {@link PolyglotException#getSourceLocation()} (including line-only sections),
 * then {@link PolyglotErrorTriage} for {@code :line}/{@code :column}, {@code :source}-only
 * (synthesized {@code 1:1}), and finally {@link PolyglotErrorTriage#sourceNameFromStackFallback}.
 * <p>
 * Prefer this over triage's top-level {@code :line} / {@code :column} alone when you need ranges
 * (underlines, selection, DAP decorations).
 */
public final class PolyglotErrorLocations {

    /**
     * A source span (1-based line/column, UTF-16 column like Truffle).
     *
     * @param primary preferred guest location for emphasis (innermost stack frame / “fault at raise”
     *                when a matching {@link SourceSection} exists; otherwise a sensible fallback)
     * @param endLine   inclusive end line of the span (same as {@code line} when single-line)
     * @param endCol    inclusive end column on {@code endLine} (1-based, UTF-16 like Truffle)
     */
    public record Region(
            int line,
            int startCol,
            int length,
            String label,
            String fnName,
            boolean primary,
            int endLine,
            int endCol) {}

    private PolyglotErrorLocations() {}

    /**
     * Collects regions from a {@linkplain Throwable throwable} when there is no {@link PolyglotException}
     * (e.g. errors caught inside Cloffle before they cross the polyglot boundary). Walks
     * {@link IExceptionInfo} on the cause chain (including {@code CompilerException}) for
     * {@code :clojure.error/*} line/column, then merges {@link ClojureException#getEnrichedFrames()}.
     */
    public static List<Region> collectGuest(Throwable t) {
        if (t == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Region> regions = new ArrayList<>();
        // CompilerException / ExceptionInfo / ClojureException ex-data (line:col) — parity with polyglot guest frames
        appendRegionsFromIExceptionInfoChain(t, regions, seen);
        ClojureException ceWithFrames = ClojureException.findThrowableWithEnrichedFrames(t);
        List<ClojureException.CallFrame> enriched =
                ceWithFrames != null
                        ? new ArrayList<>(ceWithFrames.getEnrichedFrames())
                        : List.of();
        insertEnrichedFrames(regions, seen, enriched);
        mergeEnrichedFnNamesIntoRegions(regions, enriched);
        if (regions.isEmpty()) {
            // Runtime throws inside load-file often lack CompilerException and may omit :clojure.error/line
            // on ClojureException#getData(); enriched frames can also be empty. JVM stack still has .clj sites.
            appendRegionsFromHostStackTrace(t, regions, seen);
        }
        repickPrimaryGuest(regions, enriched);
        return List.copyOf(regions);
    }

    /**
     * Collects regions for {@code e}.
     */
    public static List<Region> collect(PolyglotException e) {
        Set<String> seen = new LinkedHashSet<>();
        List<Region> regions = new ArrayList<>();

        appendNarrowPolyglotTop(e, regions, seen);
        appendGuestFrames(e, regions, seen, false);
        List<ClojureException.CallFrame> enriched = obtainEnrichedFrames(e);
        insertEnrichedFrames(regions, seen, enriched);
        mergeEnrichedFnNamesIntoRegions(regions, enriched);

        if (regions.isEmpty()) {
            appendGuestFrames(e, regions, seen, true);
        }
        if (regions.isEmpty()) {
            appendParseTopLocation(e, regions);
        }
        if (regions.isEmpty()) {
            appendTriageLocationFallback(e, regions);
        }
        repickPrimaryFaultAtRaise(e, regions, enriched);
        return List.copyOf(regions);
    }

    private static List<ClojureException.CallFrame> obtainEnrichedFrames(PolyglotException e) {
        ClojureException ce = ClojureException.findForPolyglotWithEnrichedFrames(e);
        if (ce == null) {
            return List.of();
        }
        return new ArrayList<>(ce.getEnrichedFrames());
    }

    /**
     * Python-style “fault at raise”: prefer the first {@link ClojureException.CallFrame} in unwind order
     * (innermost bytecode/invoke site first) that matches a collected region, else the polyglot guest stack
     * (see {@link #resolveInnermostGuestPrimary}), else the narrowest span.
     */
    private static void repickPrimaryFaultAtRaise(
            PolyglotException e,
            List<Region> regions,
            List<ClojureException.CallFrame> enriched) {
        if (regions.isEmpty()) {
            return;
        }
        Region fromEnriched = resolvePrimaryFromEnriched(enriched, regions);
        if (fromEnriched != null) {
            reorderWithPrimary(regions, fromEnriched);
            return;
        }
        Region fromGuest = resolveInnermostGuestPrimary(e, regions);
        if (fromGuest != null) {
            reorderWithPrimary(regions, fromGuest);
            return;
        }
        repickPrimaryNarrowestFirst(regions);
    }

    /**
     * First enriched frame that maps to a region (innermost site recorded during bytecode / invoke unwind).
     */
    private static Region resolvePrimaryFromEnriched(
            List<ClojureException.CallFrame> enriched, List<Region> regions) {
        for (ClojureException.CallFrame cf : enriched) {
            Region m = matchRegionForLineCol(regions, cf.line(), cf.column());
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Region matchRegionForLineCol(List<Region> regions, int line, int col) {
        Region best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Region r : regions) {
            if (r.line() != line) {
                continue;
            }
            int d = Math.abs(r.startCol() - col);
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best;
    }

    /**
     * Last guest stack frame in walk order (outermost→innermost here) mapped to a region: for each frame
     * we pick the region on the same {@link Region#line()} with {@link Region#startCol()} closest to the
     * frame’s column (Truffle vs collected spans can differ slightly). The final assignment wins so the
     * innermost frame’s match becomes primary.
     */
    private static Region resolveInnermostGuestPrimary(PolyglotException e, List<Region> regions) {
        Region best = null;
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines() || !isGuestLanguageSource(sl)) {
                continue;
            }
            if (isLikelyWholeSourceSection(sl)) {
                continue;
            }
            int line = sl.getStartLine();
            int col = sl.hasColumns() ? sl.getStartColumn() : 1;
            Region frameBest = null;
            int bestDist = Integer.MAX_VALUE;
            for (Region r : regions) {
                if (r.line() != line) {
                    continue;
                }
                int d = Math.abs(r.startCol() - col);
                if (d < bestDist) {
                    bestDist = d;
                    frameBest = r;
                }
            }
            if (frameBest != null) {
                best = frameBest;
            }
        }
        return best;
    }

    private static void reorderWithPrimary(List<Region> regions, Region best) {
        List<Region> out = new ArrayList<>(regions.size());
        out.add(withPrimary(best, true));
        for (Region r : regions) {
            if (sameSpan(r, best)) {
                continue;
            }
            out.add(withPrimary(r, false));
        }
        regions.clear();
        regions.addAll(out);
    }

    /**
     * When no guest frame matches collected regions (unusual), prefer the narrowest span, then
     * deeper line/column — useful when the fault is a nested span competing with a whole-buffer
     * section.
     */
    private static void repickPrimaryNarrowestFirst(List<Region> regions) {
        if (regions.isEmpty()) {
            return;
        }
        Comparator<Region> narrowestThenDeepest = Comparator.comparingInt(Region::length)
                .thenComparing(Region::line, Comparator.reverseOrder())
                .thenComparing(Region::startCol, Comparator.reverseOrder());
        Region best = regions.stream().min(narrowestThenDeepest).orElse(regions.get(0));
        reorderWithPrimary(regions, best);
    }

    private static void repickPrimaryGuest(List<Region> regions, List<ClojureException.CallFrame> enriched) {
        if (regions.isEmpty()) {
            return;
        }
        Region fromEnriched = resolvePrimaryFromEnriched(enriched, regions);
        if (fromEnriched != null) {
            reorderWithPrimary(regions, fromEnriched);
            return;
        }
        // Without polyglot guest frames: regions are often single-line stubs (ex-data, host stack).
        // Narrowest-first wrongly prefers a higher line number when lengths tie (outer call vs throw).
        boolean allSingleLineStubs =
                regions.stream()
                        .allMatch(r -> r.length() <= 1 && r.line() == r.endLine());
        if (allSingleLineStubs) {
            reorderWithPrimary(regions, regions.get(0));
            return;
        }
        repickPrimaryNarrowestFirst(regions);
    }

    /**
     * Best-effort .clj sites from {@link Throwable#getStackTrace()} (innermost frames first), when Truffle
     * ex-data and enriched frames are absent (common for guest load-file).
     */
    private static void appendRegionsFromHostStackTrace(Throwable t, List<Region> regions, Set<String> seen) {
        int guard = 0;
        Throwable x = t;
        while (x != null && guard++ < 32) {
            StackTraceElement[] st = x.getStackTrace();
            if (st != null) {
                for (StackTraceElement e : st) {
                    String fn = e.getFileName();
                    if (fn == null || !fn.endsWith(".clj")) {
                        continue;
                    }
                    int line = e.getLineNumber();
                    if (line <= 0) {
                        continue;
                    }
                    int col = 1;
                    String key = fn + ":" + line + ":" + col;
                    if (!seen.add(key)) {
                        continue;
                    }
                    String label = fn + ":" + line + ":" + col;
                    regions.add(new Region(line, col, 1, label, null, true, line, col));
                }
            }
            x = x.getCause();
        }
    }

    /**
     * {@link ClojureException} and {@code clojure.lang.Compiler.CompilerException} implement
     * {@link IExceptionInfo}; the compiler layer often has {@code :clojure.error/line} when the wrapped
     * {@link ClojureException} does not populate it the same way as the polyglot path.
     */
    private static void appendRegionsFromIExceptionInfoChain(Throwable t, List<Region> regions, Set<String> seen) {
        int guard = 0;
        Throwable x = t;
        while (x != null && guard++ < 32) {
            if (x instanceof IExceptionInfo ei) {
                appendRegionFromExData(ei.getData(), regions, seen);
            }
            x = x.getCause();
        }
    }

    private static void appendRegionFromExData(IPersistentMap m, List<Region> regions, Set<String> seen) {
        if (m == null || m.count() == 0) {
            return;
        }
        Keyword lineK = Keyword.intern("clojure.error", "line");
        Keyword colK = Keyword.intern("clojure.error", "column");
        Keyword srcK = Keyword.intern("clojure.error", "source");
        Keyword lenK = Keyword.intern("clojure.error", "length");
        Keyword endLineK = Keyword.intern("clojure.error", "end-line");
        Keyword endColK = Keyword.intern("clojure.error", "end-column");
        Keyword symK = Keyword.intern("clojure.error", "symbol");
        Object lineObj = m.valAt(lineK);
        Object srcObj = m.valAt(srcK);
        if (!(lineObj instanceof Number)) {
            return;
        }
        int line = ((Number) lineObj).intValue();
        Object colObj = m.valAt(colK);
        int col = colObj instanceof Number ? ((Number) colObj).intValue() : 1;
        String name = srcObj != null ? srcObj.toString() : "?";
        Object lenObj = m.valAt(lenK);
        int len = lenObj instanceof Number ? Math.max(1, ((Number) lenObj).intValue()) : 1;
        Object endLineObj = m.valAt(endLineK);
        int endLine = endLineObj instanceof Number ? ((Number) endLineObj).intValue() : line;
        Object endColObj = m.valAt(endColK);
        int endCol =
                endColObj instanceof Number
                        ? ((Number) endColObj).intValue()
                        : col + len - 1;
        String key = line + ":" + col;
        if (!seen.add(key)) {
            return;
        }
        String label = name + ":" + line + ":" + col;
        String fnName = null;
        Object sym = m.valAt(symK);
        if (sym != null) {
            fnName = sym.toString();
        }
        regions.add(new Region(line, col, len, label, fnName, true, endLine, endCol));
    }

    private static boolean sameSpan(Region a, Region b) {
        return a.line() == b.line() && a.startCol() == b.startCol() && a.length() == b.length();
    }

    private static Region withPrimary(Region r, boolean primary) {
        return new Region(
                r.line(),
                r.startCol(),
                r.length(),
                r.label(),
                r.fnName(),
                primary,
                r.endLine(),
                r.endCol());
    }

    /**
     * {@link PolyglotException#getSourceLocation()} is often a precise span (e.g. enclosing {@code defn} +
     * {@code throw}) while guest stack frames add a whole-buffer section; if we only walk frames, we miss the
     * top until regions are empty. Record a non-whole top section first so it competes in {@link
     * #repickPrimaryFaultAtRaise}.
     */
    private static void appendNarrowPolyglotTop(PolyglotException e, List<Region> regions, Set<String> seen) {
        SourceSection sl = e.getSourceLocation();
        if (sl == null || !sl.isAvailable() || !sl.hasLines() || !isGuestLanguageSource(sl)) {
            return;
        }
        if (isLikelyWholeSourceSection(sl)) {
            return;
        }
        Region r = fromSourceSection(sl, null, true);
        String key = r.line() + ":" + r.startCol();
        if (seen.add(key)) {
            regions.add(r);
        }
    }

    private static void appendGuestFrames(
            PolyglotException e, List<Region> regions, Set<String> seen, boolean allowWholeFile) {
        boolean firstPrimary = regions.stream().noneMatch(Region::primary);
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines() || !isGuestLanguageSource(sl)) {
                continue;
            }
            if (!allowWholeFile && isLikelyWholeSourceSection(sl)) {
                continue;
            }
            Region r = fromSourceSection(sl, frame.getRootName(), firstPrimary);
            String key = r.line() + ":" + r.startCol();
            if (seen.add(key)) {
                regions.add(r);
                firstPrimary = false;
            }
        }
    }

    private static void insertEnrichedFrames(
            List<Region> regions, Set<String> seen, List<ClojureException.CallFrame> enriched) {
        if (enriched.isEmpty()) {
            return;
        }
        int insertPos = Math.min(1, regions.size());
        for (ClojureException.CallFrame cf : enriched) {
            String key = cf.line() + ":" + cf.column();
            if (!seen.add(key)) {
                continue;
            }
            String loc = cf.sourceName() + ":" + cf.line() + ":" + cf.column();
            String snippet = cf.snippet() != null ? " → " + cf.snippet() : "";
            int el = cf.line();
            int ec = cf.column() + Math.max(1, cf.length()) - 1;
            regions.add(
                    insertPos,
                    new Region(
                            cf.line(),
                            cf.column(),
                            Math.max(1, cf.length()),
                            loc + snippet,
                            cf.fnName(),
                            false,
                            el,
                            ec));
            insertPos++;
        }
    }

    /**
     * Polyglot guest frames often have {@code getRootName() == null} at the actual fault (e.g. {@code throw})
     * while bytecode enrichment recorded the real {@link ClojureException.CallFrame#fnName()} for that
     * instruction. Enriched rows are skipped when they duplicate {@code line:column} with a guest region, so
     * merge names onto regions that are still missing {@link Region#fnName()}.
     */
    private static void mergeEnrichedFnNamesIntoRegions(
            List<Region> regions, List<ClojureException.CallFrame> enriched) {
        if (enriched.isEmpty()) {
            return;
        }
        for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);
            if (r.fnName() != null && !r.fnName().isEmpty()) {
                continue;
            }
            ClojureException.CallFrame best = null;
            int bestDist = Integer.MAX_VALUE;
            for (ClojureException.CallFrame cf : enriched) {
                if (cf.fnName() == null || cf.fnName().isEmpty()) {
                    continue;
                }
                if (cf.line() != r.line()) {
                    continue;
                }
                if (!cf.sourceName().equals(sourceNamePrefixFromRegionLabel(r.label()))) {
                    continue;
                }
                int d = Math.abs(cf.column() - r.startCol());
                if (d < bestDist) {
                    bestDist = d;
                    best = cf;
                }
            }
            if (best == null) {
                for (ClojureException.CallFrame cf : enriched) {
                    if (cf.fnName() == null || cf.fnName().isEmpty()) {
                        continue;
                    }
                    if (cf.line() != r.line()) {
                        continue;
                    }
                    int d = Math.abs(cf.column() - r.startCol());
                    if (d < bestDist) {
                        bestDist = d;
                        best = cf;
                    }
                }
            }
            if (best != null) {
                regions.set(
                        i,
                        new Region(
                                r.line(),
                                r.startCol(),
                                r.length(),
                                r.label(),
                                best.fnName(),
                                r.primary(),
                                r.endLine(),
                                r.endCol()));
            }
        }
    }

    /** Leading source name from {@code "file.clj:line:col"} or {@code "file.clj:line:col → ..."}. */
    static String sourceNamePrefixFromRegionLabel(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        int arrow = label.indexOf(" → ");
        String head = arrow >= 0 ? label.substring(0, arrow) : label;
        int c = head.lastIndexOf(':');
        if (c <= 0) {
            return head;
        }
        int b = head.lastIndexOf(':', c - 1);
        if (b <= 0) {
            return head;
        }
        return head.substring(0, b);
    }

    private static void appendParseTopLocation(PolyglotException e, List<Region> regions) {
        SourceSection sl = e.getSourceLocation();
        if (sl == null || !sl.isAvailable() || !sl.hasLines() || !isGuestLanguageSource(sl)) {
            return;
        }
        regions.add(fromSourceSection(sl, null, true));
    }

    private static void appendTriageLocationFallback(PolyglotException e, List<Region> regions) {
        IPersistentMap m = PolyglotErrorTriage.triage(e);
        if (m == null || m.count() == 0) {
            appendSourceNameOnlyRegion(e, regions);
            return;
        }
        Keyword lineK = Keyword.intern("clojure.error", "line");
        Keyword colK = Keyword.intern("clojure.error", "column");
        Keyword srcK = Keyword.intern("clojure.error", "source");
        Object lineObj = m.valAt(lineK);
        Object srcObj = m.valAt(srcK);
        if (lineObj instanceof Number) {
            int line = ((Number) lineObj).intValue();
            Object colObj = m.valAt(colK);
            int col = colObj instanceof Number ? ((Number) colObj).intValue() : 1;
            String name = srcObj != null ? srcObj.toString() : "?";
            regions.add(new Region(line, col, 1, name + ":" + line + ":" + col, null, true, line, col));
            return;
        }
        if (srcObj != null) {
            String name = srcObj.toString();
            regions.add(new Region(1, 1, 1, name + ":1:1", null, true, 1, 1));
            return;
        }
        appendSourceNameOnlyRegion(e, regions);
    }

    private static void appendSourceNameOnlyRegion(PolyglotException e, List<Region> regions) {
        String sn = PolyglotErrorTriage.sourceNameFromStackFallback(e);
        if (sn != null) {
            regions.add(new Region(1, 1, 1, sn + ":1:1", null, true, 1, 1));
        }
    }

    static Region fromSourceSection(SourceSection sl, String rootName, boolean primary) {
        int line = sl.getStartLine();
        int col = sl.hasColumns() ? sl.getStartColumn() : 1;
        int len;
        if (sl.hasCharIndex()) {
            len = Math.max(1, sl.getCharLength());
        } else if (sl.hasColumns()) {
            len = Math.max(1, sl.getEndColumn() - sl.getStartColumn() + 1);
        } else {
            len = 1;
        }

        int endLine = sl.hasLines() ? sl.getEndLine() : line;
        int endCol;
        if (sl.hasLines() && sl.hasColumns()) {
            endCol = sl.getEndColumn();
        } else {
            endCol = col + len - 1;
        }

        String loc = sl.getSource().getName() + ":" + line + ":" + col;
        String snippet = "";
        try {
            snippet = " → " + sl.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {
        }

        return new Region(line, col, len, loc + snippet, rootName, primary, endLine, endCol);
    }

    /**
     * True when the section's {@link Source#getName()} looks like guest code, not a JVM/Truffle
     * internal frame. Truffle 25.1+ sometimes attaches sections such as {@code TruffleStackTrace.java}
     * to polyglot exceptions; those must not win over the real guest file.
     */
    static boolean isGuestLanguageSource(SourceSection sl) {
        if (sl == null || !sl.isAvailable()) {
            return false;
        }
        try {
            Source src = sl.getSource();
            if (src == null) {
                return false;
            }
            String name = src.getName();
            if (name == null || name.isEmpty()) {
                return false;
            }
            return !name.endsWith(".java") && !name.endsWith(".class");
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * True when the section covers essentially the entire character source (useless for “which form”).
     */
    static boolean isLikelyWholeSourceSection(SourceSection sl) {
        try {
            Source src = sl.getSource();
            if (src == null || !sl.hasCharIndex()) {
                return false;
            }
            return isWholeFileSpan(src.getLength(), sl.getCharIndex(), sl.getCharLength());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Exposed for tests; same predicate as {@link #isLikelyWholeSourceSection(SourceSection)}. */
    static boolean isWholeFileSpan(int sourceLength, int charIndex, int charLength) {
        return sourceLength > 0 && charIndex == 0 && charLength >= sourceLength;
    }
}
