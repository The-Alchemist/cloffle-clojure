package net.javacrumbs.cloffle;

import clojure.lang.IExceptionInfo;
import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps {@link PolyglotException} to Clojure data compatible with
 * {@code clojure.main/ex-triage} / {@code ex-str} tooling (same {@code :clojure.error/*}
 * keys as {@link net.javacrumbs.cloffle.nodes.ClojureException} where possible), plus
 * structured guest stack frames for editors and test harnesses.
 */
public final class PolyglotErrorTriage {

    private static final Keyword PHASE = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN = Keyword.intern("clojure.error", "column");
    private static final Keyword CAUSE = Keyword.intern("clojure.error", "cause");
    private static final Keyword CLASS = Keyword.intern("clojure.error", "class");
    private static final Keyword GUEST_FRAMES = Keyword.intern("clojure.error", "guest-frames");
    private static final Keyword POLYGLOT = Keyword.intern("clojure.error", "polyglot");

    private static final Keyword F_SOURCE = Keyword.intern(null, "source");
    private static final Keyword F_LINE = Keyword.intern(null, "line");
    private static final Keyword F_COLUMN = Keyword.intern(null, "column");
    private static final Keyword F_ROOT = Keyword.intern(null, "root-name");
    private static final Keyword F_SNIPPET = Keyword.intern(null, "snippet");

    private static final Pattern FILENAME_CLJ_IN_MESSAGE = Pattern.compile("(\\S+\\.clj)");

    private PolyglotErrorTriage() {}

    /**
     * Returns a persistent map suitable for passing to {@code clojure.main/ex-str}
     * (after any small adjustments) or for direct inspection in tools.
     */
    public static IPersistentMap triage(PolyglotException e) {
        if (e == null) {
            return PersistentArrayMap.EMPTY;
        }
        List<Object> pairs = new ArrayList<>(32);

        Keyword phase = resolvePhase(e);
        pairs.add(PHASE);
        pairs.add(phase);

        SourceSection sl = firstSourceSectionWithLocation(e);
        if (sl != null && sl.isAvailable()) {
            pairs.add(SOURCE);
            pairs.add(sl.getSource().getName());
            pairs.add(LINE);
            pairs.add((long) sl.getStartLine());
            pairs.add(COLUMN);
            pairs.add((long) sl.getStartColumn());
        }

        String msg = e.getMessage();
        if (msg != null) {
            pairs.add(CAUSE);
            pairs.add(msg);
        }

        if (e.isHostException()) {
            Throwable host = e.asHostException();
            pairs.add(CLASS);
            pairs.add(Symbol.intern(host.getClass().getName()));
            mergeIExceptionInfo(pairs, host);
        }
        mergeGuestObjectExData(pairs, e);

        PersistentVector frames = buildGuestFrames(e);
        if (frames.count() > 0) {
            pairs.add(GUEST_FRAMES);
            pairs.add(frames);
            // Polyglot often has no root SourceSection for bytecode eval; first guest frame still carries file/line.
            if (!pairListContains(pairs, SOURCE)) {
                IPersistentMap first = (IPersistentMap) frames.nth(0);
                Object srcName = first.valAt(F_SOURCE);
                if (srcName != null) {
                    pairs.add(SOURCE);
                    pairs.add(srcName);
                    Object ln = first.valAt(F_LINE);
                    if (ln != null) {
                        pairs.add(LINE);
                        pairs.add(ln);
                    }
                    Object cn = first.valAt(F_COLUMN);
                    if (cn != null) {
                        pairs.add(COLUMN);
                        pairs.add(cn);
                    }
                }
            }
        }

        if (!pairListContains(pairs, SOURCE)) {
            String blob = (msg != null ? msg : "") + " " + e;
            Matcher fm = FILENAME_CLJ_IN_MESSAGE.matcher(blob);
            if (fm.find()) {
                pairs.add(SOURCE);
                pairs.add(fm.group(1));
            } else {
                String fromStack = sourceNameFromStackFallback(e);
                if (fromStack != null) {
                    pairs.add(SOURCE);
                    pairs.add(fromStack);
                }
            }
        }

        pairs.add(POLYGLOT);
        pairs.add(PersistentArrayMap.createAsIfByAssoc(new Object[]{
                Keyword.intern(null, "internal-error?"), e.isInternalError(),
                Keyword.intern(null, "syntax-error?"), e.isSyntaxError(),
                Keyword.intern(null, "guest-exception?"), e.isGuestException(),
                Keyword.intern(null, "host-exception?"), e.isHostException(),
                Keyword.intern(null, "incomplete-source?"), e.isIncompleteSource()
        }));

        return PersistentArrayMap.createAsIfByAssoc(pairs.toArray());
    }

    /**
     * Human-readable message from a triage map (same shape as {@link #triage(PolyglotException)}).
     * For spec-heavy errors, prefer {@code clojure.polyglot.error/triage-ex-str} when running on Clojure
     * for full {@code spec/explain-out} output.
     */
    public static String formatMessage(IPersistentMap triage) {
        return ClojureErrorExStr.formatTriageMessage(triage);
    }

    /**
     * {@link #triage(PolyglotException)} then {@link #formatMessage(IPersistentMap)}.
     */
    public static String formatMessage(PolyglotException e) {
        return formatMessage(triage(e));
    }

    private static Keyword resolvePhase(PolyglotException e) {
        if (e.isIncompleteSource() || e.isSyntaxError()) {
            return Keyword.intern(null, "read-source");
        }
        String msg = e.getMessage();
        if (msg != null) {
            String m = msg.toLowerCase();
            if (m.contains("unmatched delimiter")
                    || m.contains("eof while reading")
                    || m.contains("invalid token")
                    || m.contains("unreadable")
                    || m.contains("reader error")) {
                return Keyword.intern(null, "read-source");
            }
        }
        if (e.isInternalError()) {
            return Keyword.intern(null, "execution");
        }
        return Keyword.intern(null, "execution");
    }

    private static void mergeIExceptionInfo(List<Object> pairs, Throwable host) {
        if (!(host instanceof IExceptionInfo ei)) {
            return;
        }
        IPersistentMap data = ei.getData();
        if (data == null) {
            return;
        }
        for (ISeq s = RT.seq(data); s != null; s = s.next()) {
            IMapEntry me = (IMapEntry) s.first();
            Object k = me.key();
            if (!(k instanceof Keyword kw)) {
                continue;
            }
            String ns = kw.getNamespace();
            if (!"clojure.error".equals(ns)) {
                continue;
            }
            String name = kw.getName();
            if ("phase".equals(name) || "source".equals(name) || "line".equals(name)
                    || "column".equals(name) || "cause".equals(name) || "class".equals(name)
                    || "symbol".equals(name) || "spec".equals(name)
                    || "macro-stack".equals(name)) {
                upsert(pairs, kw, me.val());
            }
        }
    }

    /**
     * When the polyglot boundary exposes a guest exception object (even if
     * {@link PolyglotException#isGuestException()} is false), merge its {@link IExceptionInfo} data.
     */
    private static void mergeGuestObjectExData(List<Object> pairs, PolyglotException e) {
        try {
            Value go = e.getGuestObject();
            if (go == null || go.isNull()) {
                return;
            }
            if (go.isHostObject()) {
                Object ho = go.asHostObject();
                if (ho instanceof Throwable t) {
                    mergeIExceptionInfo(pairs, t);
                }
            }
        } catch (Throwable ignored) {
            // Guest object may be unavailable for this exception shape
        }
    }

    /**
     * Polyglot root {@link PolyglotException#getSourceLocation()} is often null for bytecode eval; guest
     * stack frames may still carry {@link SourceSection}s (including line &gt; 1).
     */
    /**
     * When Truffle does not attach {@link SourceSection} to the polyglot exception, host stack elements may
     * still carry the guest file name (from {@link PolyglotException.StackFrame#toHostFrame()} or the
     * wrapped {@link Throwable} stack).
     */
    private static String sourceNameFromStackFallback(PolyglotException e) {
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            try {
                StackTraceElement h = frame.toHostFrame();
                if (h != null) {
                    String fn = h.getFileName();
                    if (fn != null && fn.endsWith(".clj")) {
                        return fn;
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        for (StackTraceElement ste : e.getStackTrace()) {
            String fn = ste.getFileName();
            if (fn != null && fn.endsWith(".clj")) {
                return fn;
            }
        }
        return null;
    }

    private static SourceSection firstSourceSectionWithLocation(PolyglotException e) {
        SourceSection sl = e.getSourceLocation();
        if (sl != null && sl.isAvailable()) {
            return sl;
        }
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            SourceSection fsl = frame.getSourceLocation();
            if (fsl != null && fsl.isAvailable()) {
                return fsl;
            }
        }
        return null;
    }

    private static boolean pairListContains(List<Object> pairs, Keyword k) {
        for (int i = 0; i < pairs.size(); i += 2) {
            if (k.equals(pairs.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static void upsert(List<Object> pairs, Keyword k, Object v) {
        for (int i = 0; i < pairs.size(); i += 2) {
            if (k.equals(pairs.get(i))) {
                pairs.set(i + 1, v);
                return;
            }
        }
        pairs.add(k);
        pairs.add(v);
    }

    private static PersistentVector buildGuestFrames(PolyglotException e) {
        List<IPersistentMap> out = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection fsl = frame.getSourceLocation();
            if (fsl == null) {
                continue;
            }
            List<Object> fk = new ArrayList<>(10);
            fk.add(F_SOURCE);
            fk.add(fsl.getSource().getName());
            fk.add(F_LINE);
            fk.add((long) fsl.getStartLine());
            fk.add(F_COLUMN);
            fk.add((long) fsl.getStartColumn());
            String root = frame.getRootName();
            if (root != null && !root.isEmpty()) {
                fk.add(F_ROOT);
                fk.add(root);
            }
            try {
                String chars = fsl.getCharacters().toString().trim();
                if (!chars.isEmpty()) {
                    if (chars.length() > 120) {
                        chars = chars.substring(0, 117) + "...";
                    }
                    fk.add(F_SNIPPET);
                    fk.add(chars);
                }
            } catch (Exception ignored) {
            }
            out.add(PersistentArrayMap.createAsIfByAssoc(fk.toArray()));
        }
        return PersistentVector.create(out);
    }
}
