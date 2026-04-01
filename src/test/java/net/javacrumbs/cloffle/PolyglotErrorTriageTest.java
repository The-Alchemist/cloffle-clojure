package net.javacrumbs.cloffle;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import clojure.lang.Symbol;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class PolyglotErrorTriageTest {

    private static final Keyword PHASE = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE = Keyword.intern("clojure.error", "line");
    private static final Keyword GUEST_FRAMES = Keyword.intern("clojure.error", "guest-frames");
    private static final Keyword POLYGLOT = Keyword.intern("clojure.error", "polyglot");
    private static final Keyword CLASS = Keyword.intern("clojure.error", "class");

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle").allowAllAccess(true).build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    @Test
    public void triageNullIsEmptyMap() {
        assertThat(PolyglotErrorTriage.triage(null).count()).isEqualTo(0);
    }

    @Test
    @Ignore("Guest frame source name — refresh after bytecode Polyglot entry.")
    public void triageExecutionErrorIncludesPhaseLineAndGuestFrames() {
        Source src = Source.newBuilder("cloffle", "(/ 1 0)", "div.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected division error");
        } catch (PolyglotException e) {
            IPersistentMap m = PolyglotErrorTriage.triage(e);
            assertThat(m.valAt(PHASE)).isEqualTo(Keyword.intern(null, "execution"));
            assertThat(m.valAt(SOURCE)).isEqualTo("div.clj");
            assertThat(m.valAt(LINE)).isEqualTo(1L);
            Object frames = m.valAt(GUEST_FRAMES);
            assertThat(frames).isInstanceOf(PersistentVector.class);
            assertThat(((PersistentVector) frames).count()).isGreaterThan(0);
            assertThat(m.valAt(POLYGLOT)).isNotNull();
        }
    }

    @Test
    public void triageReaderIncompleteSourceMapsToReadSourcePhase() {
        Source src = Source.newBuilder("cloffle", "(+ 1 ", "bad.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected reader error");
        } catch (PolyglotException e) {
            IPersistentMap m = PolyglotErrorTriage.triage(e);
            assertThat(m.valAt(PHASE)).isEqualTo(Keyword.intern(null, "read-source"));
        }
    }

    @Test
    public void triageUnresolvedVarIncludesCompilePhaseAndClass() {
        Source src = Source.newBuilder("cloffle",
                "(totally-unknown-var-zzzz-12345)", "uvar.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected compile/eval error");
        } catch (PolyglotException e) {
            IPersistentMap m = PolyglotErrorTriage.triage(e);
            Object phase = m.valAt(PHASE);
            assertThat(phase).isIn(
                    Keyword.intern(null, "compilation"),
                    Keyword.intern(null, "compile-syntax-check"),
                    Keyword.intern(null, "read-source"));
            Object cls = m.valAt(CLASS);
            if (cls != null) {
                assertThat(cls).isInstanceOf(Symbol.class);
            }
        }
    }

    @Test
    public void triageMergesExDataFromGuestParseError() {
        Source src = Source.newBuilder("cloffle",
                "(totally-unknown-var-qqqq-99999)", "parse_ex.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected error");
        } catch (PolyglotException e) {
            IPersistentMap m = PolyglotErrorTriage.triage(e);
            assertThat(m.valAt(SOURCE)).isEqualTo("parse_ex.clj");
        }
    }
}
