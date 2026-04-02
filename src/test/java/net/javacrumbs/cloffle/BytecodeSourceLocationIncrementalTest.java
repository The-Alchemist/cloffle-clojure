package net.javacrumbs.cloffle;

import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.ast.ExprSourceSpans;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Incremental tests for bytecode source spans: cheap {@link ExprSourceSpans} checks without the
 * full {@code SourceLocation*} suites. Polyglot triage and guest frames are covered in
 * {@link PolyglotErrorTriageTest} and {@link ErrorDiagnosticsTest} (Graal often omits
 * {@code clojure.error/source} / line for bytecode stacks).
 */
public class BytecodeSourceLocationIncrementalTest {

    /** Asserts balanced (or fallback) span text at 1-based line/column. */
    private static void assertSpanText(Source src, int line1Based, int column1Based, String expectedSubstring) {
        Optional<ExprSourceSpans.CharSpan> span =
                ExprSourceSpans.computeCharSpanFromLineColumn(src, line1Based, column1Based);
        assertTrue("expected span at line " + line1Based + " col " + column1Based, span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + cs.length()).toString())
                .isEqualTo(expectedSubstring);
    }

    @Test
    public void computeCharSpan_topLevelDivOnLine2_balancedLength7() {
        String code = "(def x 1)\n(/ 1 0)\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(7);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + cs.length()).toString())
                .isEqualTo("(/ 1 0)");
    }

    @Test
    public void computeCharSpan_nestedDivOnLine2_balancedInnerFormOnly() {
        String code = "(def x 1)\n(+ 1 (/ 2 0))\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 6);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(7);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + cs.length()).toString())
                .isEqualTo("(/ 2 0)");
    }

    @Test
    public void computeCharSpan_ifFormOnLine2_fullBalancedSpan() {
        String code = "(def x 1)\n(if true 1 2)\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + cs.length()).toString())
                .isEqualTo("(if true 1 2)");
    }

    @Test
    public void computeCharSpan_letStarOnLine2_fullBalancedSpan() {
        String code = "(def x 1)\n(let* [a 1] a)\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + cs.length()).toString())
                .isEqualTo("(let* [a 1] a)");
    }

    @Test
    public void computeCharSpan_defForm_line2() {
        String code = ";; preamble\n(def y 2)\n";
        Source src = Source.newBuilder("cloffle", code, "def.clj").build();
        assertSpanText(src, 2, 1, "(def y 2)");
    }

    @Test
    public void computeCharSpan_vectorLiteral_line2_bracketBalanced() {
        String code = "(def x 1)\n[1 2 3]\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 1, "[1 2 3]");
    }

    @Test
    public void computeCharSpan_mapLiteral_line2_braceBalanced() {
        String code = "(def x 1)\n{:a 1 :b 2}\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 1, "{:a 1 :b 2}");
    }

    @Test
    public void computeCharSpan_nestedVector_line2() {
        String code = "(def x 1)\n[1 [2 3]]\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 1, "[1 [2 3]]");
    }

    @Test
    public void computeCharSpan_tryForm_line2() {
        String code = "(def x 1)\n(try 1 (catch Exception e nil))\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 1, "(try 1 (catch Exception e nil))");
    }

    @Test
    public void computeCharSpan_line3() {
        String code = "(def a 1)\n(def b 2)\n(/ 3 0)\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 3, 1, "(/ 3 0)");
    }

    @Test
    public void computeCharSpan_leadingSpaces_columnMatchesOpenParen() {
        String code = "(def x 1)\n   (/ 9 0)\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 4, "(/ 9 0)");
    }

    @Test
    public void computeCharSpan_lineCommentAfterForm_doesNotExtendSpan() {
        String code = "(def x 1)\n(/ 1 0) ; div by zero\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        assertSpanText(src, 2, 1, "(/ 1 0)");
    }

    @Test
    public void computeCharSpan_stringOpener_notBalancedAsParens_fallsBackToOneChar() {
        String code = "(def x 1)\n\"((\"\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        // Column 1 is `"` — not `(`, `[`, or `{`, so span is one code unit (opening quote only).
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(1);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + 1).toString()).isEqualTo("\"");
    }

    @Test
    public void computeCharSpan_nonDelimiterAtPosition_fallsBackToSingleCodeUnit() {
        String code = "(def x 1)\nfoobar\n";
        Source src = Source.newBuilder("cloffle", code, "t.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(1);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + 1).toString()).isEqualTo("f");
    }

    @Test
    public void computeCharSpan_unicodeLine_preservesGraphemeAsSingleCharIndex() {
        String code = "(def x 1)\n:über\n";
        Source src = Source.newBuilder("cloffle", code, "uni.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(1);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + 1).toString()).isEqualTo(":");
    }

    @Test
    public void charIndexForLineColumn_secondLine_columnOne() {
        String code = "first\nsecond\n";
        Source src = Source.newBuilder("cloffle", code, "idx.clj").build();
        int idx = ExprSourceSpans.charIndexForLineColumn(src, 2, 1);
        assertThat(idx).isEqualTo(code.indexOf("second"));
        assertThat(src.getCharacters().charAt(idx)).isEqualTo('s');
    }

    @Test
    public void balancedFormLength_hashSetReaderMacro_firstCharNotBrace() {
        // Reader produces set; source still has `#` first — not a balanced ()[]{} starter.
        String code = "(def x 1)\n#{1 2}\n";
        Source src = Source.newBuilder("cloffle", code, "set.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(1);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + 1).toString()).isEqualTo("#");
    }

    @Test
    public void computeCharSpan_multiLineSexp_balancedAcrossNewlines() {
        String code = "(def a 1)\n(+\n  1 2)\n";
        Source src = Source.newBuilder("cloffle", code, "ml.clj").build();
        assertSpanText(src, 2, 1, "(+\n  1 2)");
    }

    @Test
    public void balancedFormLength_atExplicitOffset_matchesSubform() {
        String code = "[skip]\n(b d)";
        Source src = Source.newBuilder("cloffle", code, "bf.clj").build();
        int start = ExprSourceSpans.charIndexForLineColumn(src, 2, 1);
        assertThat(ExprSourceSpans.balancedFormLength(src, start)).isEqualTo("(b d)".length());
        assertThat(src.getCharacters().subSequence(start, start + "(b d)".length()).toString()).isEqualTo("(b d)");
    }

    @Test
    public void computeCharSpan_metadataReaderMacroCaret_fallsBackToOneChar() {
        String code = "(def x 1)\n^{:a 1} [1]\n";
        Source src = Source.newBuilder("cloffle", code, "meta.clj").build();
        Optional<ExprSourceSpans.CharSpan> span = ExprSourceSpans.computeCharSpanFromLineColumn(src, 2, 1);
        assertTrue(span.isPresent());
        ExprSourceSpans.CharSpan cs = span.get();
        assertThat(cs.length()).isEqualTo(1);
        assertThat(src.getCharacters().subSequence(cs.start(), cs.start() + 1).toString()).isEqualTo("^");
    }

    @Test
    public void computeCharSpan_afterQuote_openParenColumn_balancedList() {
        String code = "(def x 1)\n'(1 2)\n";
        Source src = Source.newBuilder("cloffle", code, "q.clj").build();
        // Column 2 is `(` — balanced span is the list, not the reader quote.
        assertSpanText(src, 2, 2, "(1 2)");
    }

    @Test
    public void computeCharSpan_crlfSource_secondLineStillCorrect() {
        String code = "(def x 1)\r\n(/ 1 0)\r\n";
        Source src = Source.newBuilder("cloffle", code, "crlf.clj").build();
        assertSpanText(src, 2, 1, "(/ 1 0)");
    }

    @Test
    public void computeCharSpan_tabIndent_columnAtOpenParen() {
        String code = "(def x 1)\n\t\t(/ 0 1)\n";
        Source src = Source.newBuilder("cloffle", code, "tab.clj").build();
        assertSpanText(src, 2, 3, "(/ 0 1)");
    }

    @Test
    public void balancedFormLength_deepParens_singleForm() {
        String code = "()\n((((9))))\n";
        Source src = Source.newBuilder("cloffle", code, "deep.clj").build();
        int start = ExprSourceSpans.charIndexForLineColumn(src, 2, 1);
        assertThat(ExprSourceSpans.balancedFormLength(src, start)).isEqualTo("((((9))))".length());
        assertSpanText(src, 2, 1, "((((9))))");
    }

    @Test
    public void balancedFormLength_mixedBracketTypes_innerOnly() {
        String code = "(def x 1)\n{:k [1 (2)]}\n";
        Source src = Source.newBuilder("cloffle", code, "mix.clj").build();
        assertSpanText(src, 2, 1, "{:k [1 (2)]}");
    }

    @Test
    public void computeCharSpan_fnStarLine2_fullSpan() {
        String code = "(def x 1)\n(fn* [] 1)\n";
        Source src = Source.newBuilder("cloffle", code, "fn.clj").build();
        assertSpanText(src, 2, 1, "(fn* [] 1)");
    }

    @Test
    public void computeCharSpan_quoteEmptyList() {
        String code = "(def x 1)\n'()\n";
        Source src = Source.newBuilder("cloffle", code, "eq.clj").build();
        assertSpanText(src, 2, 2, "()");
    }

    @Test
    public void balancedFormLength_negativeWhenStartNotAtDelimiter() {
        String code = "(a b)";
        Source src = Source.newBuilder("cloffle", code, "mid.clj").build();
        int spaceIdx = code.indexOf(' ');
        assertThat(ExprSourceSpans.balancedFormLength(src, spaceIdx)).isEqualTo(-1);
    }

}
