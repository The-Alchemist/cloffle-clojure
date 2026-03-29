package net.javacrumbs.cloffle;

import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.Symbol;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClojureErrorExStrTest {

    private static final Keyword PHASE = Keyword.intern("clojure.error", "phase");
    private static final Keyword SOURCE = Keyword.intern("clojure.error", "source");
    private static final Keyword LINE = Keyword.intern("clojure.error", "line");
    private static final Keyword COLUMN = Keyword.intern("clojure.error", "column");
    private static final Keyword CAUSE = Keyword.intern("clojure.error", "cause");
    private static final Keyword CLASS = Keyword.intern("clojure.error", "class");
    private static final Keyword SYMBOL = Keyword.intern("clojure.error", "symbol");
    private static final Keyword MACRO_STACK = Keyword.intern("clojure.error", "macro-stack");
    private static final Keyword SPEC = Keyword.intern("clojure.error", "spec");

    @Test
    public void emptyTriageYieldsEmptyString() {
        assertThat(ClojureErrorExStr.formatTriageMessage(null)).isEmpty();
        assertThat(ClojureErrorExStr.formatTriageMessage(PersistentArrayMap.EMPTY)).isEmpty();
    }

    @Test
    public void readSourcePhaseMatchesExStrShape() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "read-source"),
                SOURCE, "file.clj",
                LINE, 3L,
                COLUMN, 2L,
                CAUSE, "Unmatched delimiter: )"
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("Syntax error reading source");
        assertThat(s).contains("file.clj:3:2");
        assertThat(s).contains("Unmatched delimiter");
    }

    @Test
    public void executionPhaseOmitsGenericExceptionClassName() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "execution"),
                SOURCE, "e.clj",
                LINE, 1L,
                CAUSE, "Divide by zero",
                CLASS, Symbol.intern("java.lang.RuntimeException")
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("Execution error");
        assertThat(s).contains("Divide by zero");
        assertThat(s).doesNotContain("(RuntimeException)");
    }

    @Test
    public void executionPhaseShowsArithmeticExceptionSimpleName() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "execution"),
                SOURCE, "e.clj",
                LINE, 1L,
                CAUSE, "/ by zero",
                CLASS, Symbol.intern("java.lang.ArithmeticException")
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("(ArithmeticException)");
    }

    @Test
    public void macroStackAppended() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "read-source"),
                SOURCE, "m.clj",
                LINE, 1L,
                CAUSE, "x",
                MACRO_STACK, clojure.lang.PersistentVector.create(
                        Symbol.intern("user", "outer"),
                        Symbol.intern("user", "inner"))
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("Macro stack:");
        assertThat(s).contains("outer");
        assertThat(s).contains("inner");
    }

    @Test
    public void compileSyntaxCheckIncludesSymbol() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "compile-syntax-check"),
                SOURCE, "c.clj",
                LINE, 5L,
                COLUMN, 1L,
                SYMBOL, Symbol.intern("user", "foo"),
                CAUSE, "Unable to resolve symbol: x"
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("compiling");
        assertThat(s).contains("user/foo");
    }

    @Test
    public void guestFramesAppended() {
        var frame = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                Keyword.intern(null, "source"), "a.clj",
                Keyword.intern(null, "line"), 1L,
                Keyword.intern(null, "column"), 1L,
                Keyword.intern(null, "root-name"), "f",
                Keyword.intern(null, "snippet"), "(+ 1)"
        });
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "execution"),
                SOURCE, "a.clj",
                LINE, 2L,
                CAUSE, "boom",
                Keyword.intern("clojure.error", "guest-frames"),
                clojure.lang.RT.vector(frame)
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("Guest frames");
        assertThat(s).contains("a.clj:1:1");
        assertThat(s).contains("(f)");
    }

    @Test
    public void specMapAppendsPrintedSpec() {
        var m = PersistentArrayMap.createAsIfByAssoc(new Object[]{
                PHASE, Keyword.intern(null, "macro-syntax-check"),
                SOURCE, "s.clj",
                LINE, 1L,
                SYMBOL, Symbol.intern("user", "m"),
                SPEC, PersistentArrayMap.createAsIfByAssoc(new Object[]{
                        Keyword.intern("clojure.spec.alpha", "problems"),
                        clojure.lang.PersistentVector.EMPTY
                })
        });
        String s = ClojureErrorExStr.formatTriageMessage(m);
        assertThat(s).contains("macroexpanding");
        assertThat(s).contains("problems");
    }
}
