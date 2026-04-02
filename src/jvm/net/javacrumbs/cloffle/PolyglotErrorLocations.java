package net.javacrumbs.cloffle;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureException;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves source regions from a {@link PolyglotException} for editors and diagnostics: guest stack
 * {@link SourceSection}s (skipping useless whole-file spans when narrower ones exist),
 * {@link ClojureException#consumeEnrichedFrames() enriched Clojure call frames}, then
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
     * @param primary first distinct guest location (strongest signal for “error here”)
     */
    public record Region(int line, int startCol, int length, String label, String fnName, boolean primary) {}

    private PolyglotErrorLocations() {}

    /**
     * Collects regions for {@code e}. Consumes {@link ClojureException#consumeEnrichedFrames()} at most once.
     */
    public static List<Region> collect(PolyglotException e) {
        Set<String> seen = new LinkedHashSet<>();
        List<Region> regions = new ArrayList<>();

        appendGuestFrames(e, regions, seen, false);
        insertEnrichedFrames(regions, seen);

        if (regions.isEmpty()) {
            appendGuestFrames(e, regions, seen, true);
        }
        if (regions.isEmpty()) {
            appendParseTopLocation(e, regions);
        }
        if (regions.isEmpty()) {
            appendTriageLocationFallback(e, regions);
        }
        return List.copyOf(regions);
    }

    private static void appendGuestFrames(
            PolyglotException e, List<Region> regions, Set<String> seen, boolean allowWholeFile) {
        boolean firstPrimary = regions.stream().noneMatch(Region::primary);
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection sl = frame.getSourceLocation();
            if (sl == null || !sl.isAvailable() || !sl.hasLines()) {
                continue;
            }
            if (!allowWholeFile && isLikelyWholeSourceSection(sl)) {
                continue;
            }
            Region r = fromSourceSection(sl, frame.getRootName(), firstPrimary);
            String key = r.line + ":" + r.startCol;
            if (seen.add(key)) {
                regions.add(r);
                firstPrimary = false;
            }
        }
    }

    private static void insertEnrichedFrames(List<Region> regions, Set<String> seen) {
        List<ClojureException.CallFrame> enriched = ClojureException.consumeEnrichedFrames();
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
            regions.add(
                    insertPos,
                    new Region(
                            cf.line(),
                            cf.column(),
                            Math.max(1, cf.length()),
                            loc + snippet,
                            cf.fnName(),
                            false));
            insertPos++;
        }
    }

    private static void appendParseTopLocation(PolyglotException e, List<Region> regions) {
        SourceSection sl = e.getSourceLocation();
        if (sl == null || !sl.isAvailable() || !sl.hasLines()) {
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
            regions.add(new Region(line, col, 1, name + ":" + line + ":" + col, null, true));
            return;
        }
        if (srcObj != null) {
            String name = srcObj.toString();
            regions.add(new Region(1, 1, 1, name + ":1:1", null, true));
            return;
        }
        appendSourceNameOnlyRegion(e, regions);
    }

    private static void appendSourceNameOnlyRegion(PolyglotException e, List<Region> regions) {
        String sn = PolyglotErrorTriage.sourceNameFromStackFallback(e);
        if (sn != null) {
            regions.add(new Region(1, 1, 1, sn + ":1:1", null, true));
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

        String loc = sl.getSource().getName() + ":" + line + ":" + col;
        String snippet = "";
        try {
            snippet = " → " + sl.getCharacters().toString().trim();
            if (snippet.length() > 50) {
                snippet = snippet.substring(0, 47) + "...";
            }
        } catch (Exception ignored) {
        }

        return new Region(line, col, len, loc + snippet, rootName, primary);
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
