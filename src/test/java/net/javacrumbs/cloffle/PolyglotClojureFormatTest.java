package net.javacrumbs.cloffle;

import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code clojure.polyglot.error} loaded inside the Cloffle engine (sources on {@code src/clj}).
 */
public class PolyglotClojureFormatTest {

    private static final String TRIAGE_PREFIX = "(clojure.polyglot.error/triage-ex-str ";

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = Context.newBuilder("cloffle").allowAllAccess(true).build();
        // Host classpath load: Cloffle's reader rejects some require/libspec shapes; RT.load uses baseLoader (TCCL).
        RT.load("clojure/core");
        RT.load("clojure/polyglot/error");
    }

    @After
    public void tearDown() {
        context.close();
    }

    @Test
    public void triageExStrAppendsGuestFramesFromClojure() {
        Value v = context.eval(Source.newBuilder("cloffle",
                TRIAGE_PREFIX + "{:clojure.error/phase :execution "
                        + ":clojure.error/source \"a.clj\" :clojure.error/line 2 "
                        + ":clojure.error/cause \"boom\" "
                        + ":clojure.error/guest-frames [{:source \"a.clj\" :line 1 :column 1 "
                        + ":root-name \"f\" :snippet \"(+ 1)\"}]})",
                "fmt.clj").buildLiteral());
        String s = v.asString();
        assertThat(s).contains("Execution error");
        assertThat(s).contains("Guest frames");
        assertThat(s).contains("a.clj:1:1");
        assertThat(s).contains("(f)");
    }

    @Test
    public void polyglotFormatMessageMatchesJavaForSimpleTriage() {
        context.eval(Source.newBuilder("cloffle",
                "(def m {:clojure.error/phase :read-source :clojure.error/source \"z.clj\" "
                        + ":clojure.error/line 1 :clojure.error/column 1 :clojure.error/cause \"eof\"})",
                "defm.clj").buildLiteral());
        Value v = context.eval(Source.newBuilder("cloffle",
                "(= (clojure.string/trim " + TRIAGE_PREFIX + "m)) "
                        + "(clojure.string/trim (net.javacrumbs.cloffle.PolyglotErrorTriage/formatMessage m)))",
                "cmp.clj").buildLiteral());
        assertThat(v.asBoolean()).isTrue();
    }

    @Test
    public void triageExStrMatchesJavaWithGuestFrames() {
        context.eval(Source.newBuilder("cloffle",
                "(def m {:clojure.error/phase :execution :clojure.error/source \"a.clj\" "
                        + ":clojure.error/line 2 :clojure.error/cause \"boom\" "
                        + ":clojure.error/guest-frames [{:source \"a.clj\" :line 1 :column 1 "
                        + ":root-name \"f\" :snippet \"(+ 1)\"}]})",
                "defm2.clj").buildLiteral());
        Value v = context.eval(Source.newBuilder("cloffle",
                "(= (clojure.string/trim " + TRIAGE_PREFIX + "m)) "
                        + "(clojure.string/trim (net.javacrumbs.cloffle.PolyglotErrorTriage/formatMessage m)))",
                "cmp2.clj").buildLiteral());
        assertThat(v.asBoolean()).isTrue();
    }
}
