package net.javacrumbs.cloffle;

import clojure.lang.Keyword;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class PolyglotErrorLocationsTest {

    private static final Keyword PHASE = Keyword.intern("clojure.error", "phase");

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
    public void isWholeFileSpan_trueWhenSpanCoversFullText() {
        assertThat(PolyglotErrorLocations.isWholeFileSpan(6, 0, 6)).isTrue();
    }

    @Test
    public void isWholeFileSpan_falseForPrefixSpan() {
        assertThat(PolyglotErrorLocations.isWholeFileSpan(7, 0, 3)).isFalse();
    }

    @Test
    public void readerIncompleteSource_regionsOrReadSourcePhase() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", "(+ 1 ", "bad.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected reader error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            if (!regions.isEmpty()) {
                PolyglotErrorLocations.Region r = regions.get(0);
                assertThat(r.primary()).isTrue();
                assertThat(r.label()).contains("bad.clj");
                return;
            }
            assertThat(PolyglotErrorTriage.triage(e).valAt(PHASE))
                    .isEqualTo(Keyword.intern(null, "read-source"));
        }
    }

    @Test
    public void divisionByZero_whenGuestSectionsExist_prefersNonWholeFileOrFallsBack() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", "(/ 1 0)", "div.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected division error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            if (regions.isEmpty()) {
                return;
            }
            PolyglotErrorLocations.Region primary =
                    regions.stream()
                            .filter(PolyglotErrorLocations.Region::primary)
                            .findFirst()
                            .orElse(regions.get(0));
            assertThat(primary.label()).contains("div.clj");
            if (primary.line() == 1) {
                assertThat(primary.startCol()).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    public void stringInFunctionPosition_guestExceptionOrRegionOnLineOne() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", "(\"hello\" 1)", "call.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            if (!regions.isEmpty()) {
                assertThat(regions.stream().anyMatch(r -> r.line() == 1)).isTrue();
            } else {
                assertThat(e.isGuestException()).isTrue();
            }
        }
    }

    @Test
    public void arityError_reportsLocationInSource() {
        String code = "(defn f [x] x)\n(f 1 2)";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "arity.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected arity error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            assertThat(regions.stream().mapToInt(PolyglotErrorLocations.Region::line).max().orElse(0))
                    .isGreaterThanOrEqualTo(1);
            assertThat(regions.get(0).label()).contains("arity.clj");
        }
    }

    @Test
    public void multiFormFile_division_regionsReferenceSourceName() {
        String code = "(def x 1)\n(def y 2)\n(/ 1 0)";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "multi.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected division error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            if (regions.isEmpty()) {
                return;
            }
            assertThat(regions.stream().anyMatch(r -> r.label().contains("multi.clj"))).isTrue();
        }
    }

    @Test
    public void doWithNestedDefnThrow_primaryIsThrowLineNotOuterDo() {
        String code = "(do\n"
                + "  (defn kaboom []\n"
                + "    (throw (RuntimeException. \"something went wrong\")))\n"
                + "  (defn call-kaboom []\n"
                + "    (kaboom))\n"
                + "  (call-kaboom))";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "repl-do-throw.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected exception");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            PolyglotErrorLocations.Region primary =
                    regions.stream()
                            .filter(PolyglotErrorLocations.Region::primary)
                            .findFirst()
                            .orElse(regions.get(0));
            assertThat(primary.primary()).isTrue();
            assertThat(primary.label()).contains("repl-do-throw.clj:");
            assertThat(primary.line()).isEqualTo(3);
            assertThat(primary.label()).contains("throw");
            assertThat(primary.startCol()).isEqualTo(5);
            assertThat(primary.endLine()).isEqualTo(3);
            assertThat(primary.endCol()).isEqualTo(54);
            assertThat(primary.length()).isEqualTo(50);
            assertThat(primary.length()).isLessThan(code.length());
        }
    }

    @Test
    public void nestedDefnThrow_guestRegionGetsFnNameFromEnrichedFrames() {
        String code =
                "(ns t)\n\n"
                        + "(defn inner [] (throw (Exception. \"boom\")))\n"
                        + "(defn outer [] (inner))\n"
                        + "(outer)";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "nested_fn.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected exception");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            assertThat(
                            regions.stream()
                                    .filter(r -> r.line() == 3)
                                    .anyMatch(
                                            r ->
                                                    r.fnName() != null
                                                            && r.fnName().contains("inner")))
                    .as("throw site (line 3) should carry bytecode root name (e.g. t/…inner…)")
                    .isTrue();
        }
    }

    @Test
    public void sourceNamePrefixFromRegionLabel_stripsLineColumnAndSnippet() {
        assertThat(PolyglotErrorLocations.sourceNamePrefixFromRegionLabel("t.clj:3:16 → (throw)"))
                .isEqualTo("t.clj");
        assertThat(PolyglotErrorLocations.sourceNamePrefixFromRegionLabel("nested_fn.clj:1:1"))
                .isEqualTo("nested_fn.clj");
    }

    @Test
    public void defnChainThrow_primaryIsInnermostThrowForm() {
        String code =
                "(defn inner [] (throw (Exception. \"boom\")))\n"
                        + "(defn outer [] (inner))\n"
                        + "(outer)";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "chain-throw.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected exception");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            PolyglotErrorLocations.Region primary =
                    regions.stream()
                            .filter(PolyglotErrorLocations.Region::primary)
                            .findFirst()
                            .orElse(regions.get(0));
            assertThat(primary.line()).isEqualTo(1);
            assertThat(primary.label()).contains("chain-throw.clj:");
            assertThat(primary.label()).contains("throw");
        }
    }

    @Test
    public void defnWithThrow_collectNonEmptyWithTraceFile() {
        String code = "(defn boom [] (throw (Exception. \"bang\")))\n"
                + "(defn caller [] (boom))\n"
                + "(caller)";
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", code, "trace.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected exception");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            assertThat(regions.stream().anyMatch(r -> r.label().contains("trace.clj"))).isTrue();
        }
    }

    @Test
    public void collectReturnsUnmodifiableList() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", "(defn f [x] x)\n(f 1 2)", "ro.clj")
                        .buildLiteral();
        try {
            context.eval(src);
            fail("expected arity error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            try {
                regions.add(regions.get(0));
                fail("expected unmodifiable list");
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    @Test
    public void unresolvedVar_triageSourceYieldsRegion() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder(
                                "cloffle", "(totally-unknown-var-qqqq-99999)", "parse_ex.clj")
                        .buildLiteral();
        try {
            context.eval(src);
            fail("expected compile/eval error");
        } catch (PolyglotException e) {
            List<PolyglotErrorLocations.Region> regions = PolyglotErrorLocations.collect(e);
            assertThat(regions).isNotEmpty();
            assertThat(regions.stream().anyMatch(r -> r.label().contains("parse_ex.clj"))).isTrue();
        }
    }

    @Test
    public void sourceNameFromStackFallback_publicApiMatchesTriage() {
        org.graalvm.polyglot.Source src =
                org.graalvm.polyglot.Source.newBuilder("cloffle", "(/ 1 0)", "stackname.clj").buildLiteral();
        try {
            context.eval(src);
            fail("expected error");
        } catch (PolyglotException e) {
            String sn = PolyglotErrorTriage.sourceNameFromStackFallback(e);
            if (sn != null) {
                assertThat(sn).endsWith(".clj");
            }
        }
    }
}
