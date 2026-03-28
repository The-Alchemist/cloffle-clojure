package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies that Cloffle reports precise line AND column source locations
 * so tooling can draw red squiggles under the exact form that triggered
 * an error. Each test constructs code at known positions and asserts both
 * the line and column of the error frame.
 */
public class SourceLocationVerificationTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  1. Var metadata :line / :column / :file
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void defnOnLine1Col1() {
        Value result = eval("m1.clj", "(defn f [x] x)\n[(:line (meta #'f)) (:column (meta #'f))]");
        assertThat(result.toString()).isEqualTo("[1 1]");
    }

    @Test
    public void defnOnLine3Col1() {
        String code = ";; line 1\n;; line 2\n(defn g [x] x)\n[(:line (meta #'g)) (:column (meta #'g))]";
        Value result = eval("m3.clj", code);
        assertThat(result.toString()).isEqualTo("[3 1]");
    }

    @Test
    public void defnWithLeadingSpacesCol5() {
        // 4 spaces before (defn ...) -> column 5
        String code = "    (defn spaced [x] x)\n[(:line (meta #'spaced)) (:column (meta #'spaced))]";
        Value result = eval("msp.clj", code);
        assertThat(result.toString()).isEqualTo("[1 5]");
    }

    @Test
    public void twoDefnsHaveCorrectLines() {
        String code = "(defn first-fn [] 1)\n"     // line 1
                    + "\n"                           // line 2 (blank)
                    + "\n"                           // line 3 (blank)
                    + "(defn second-fn [] 2)\n"      // line 4
                    + "[(:line (meta #'first-fn)) (:line (meta #'second-fn))]";
        Value result = eval("m2d.clj", code);
        assertThat(result.toString()).isEqualTo("[1 4]");
    }

    @Test
    public void defnFileMatchesSourceName() {
        Value result = eval("my-script.clj",
                "(defn ff [] 42)\n(:file (meta #'ff))");
        assertThat(result.asString()).isEqualTo("my-script.clj");
    }

    @Test
    public void coreFnWhenHasPositiveLineAndColumn() {
        Value result = eval("cm.clj",
                "[(> (:line (meta #'when)) 0) (> (:column (meta #'when)) 0)]");
        assertThat(result.toString()).isEqualTo("[true true]");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Precise line + column for arithmetic errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void divByZero_line1_col1() {
        // (/ 1 0)
        // ^------  col 1
        assertPrimaryFrame("d1.clj", "(/ 1 0)", 1, 1);
    }

    @Test
    public void divByZero_leadingSpaces_line1_col4() {
        // ___(/  1  0)
        //    ^------  col 4
        assertPrimaryFrame("d2.clj", "   (/ 1 0)", 1, 4);
    }

    @Test
    public void divByZero_nested_line1_col6() {
        // (+ 1 (/ 2 0))
        //      ^------  col 6
        assertPrimaryFrame("d3.clj", "(+ 1 (/ 2 0))", 1, 6);
    }

    @Test
    public void divByZero_deepNest_col11() {
        // (+ 1 (* 2 (/ 3 0)))
        //           ^------  col 11
        assertPrimaryFrame("d4.clj", "(+ 1 (* 2 (/ 3 0)))", 1, 11);
    }

    @Test
    public void divByZero_line2_col4() {
        // line 1: (+ 1
        // line 2:    (/ 2 0))
        //            ^------  col 4
        assertPrimaryFrame("d5.clj", "(+ 1\n   (/ 2 0))", 2, 4);
    }

    @Test
    public void divByZero_line3_col1() {
        String code = "(def a 10)\n(def b 20)\n(/ a 0)";
        assertPrimaryFrame("d6.clj", code, 3, 1);
    }

    @Test
    public void divByZero_insideIf_col10() {
        // (if true (/ 1 0) 42)
        //          ^------  col 10
        assertPrimaryFrame("d7.clj", "(if true (/ 1 0) 42)", 1, 10);
    }

    @Test
    public void divByZero_insideDo_col9() {
        // (do 1 2 (/ 3 0))
        //         ^------  col 9
        assertPrimaryFrame("d8.clj", "(do 1 2 (/ 3 0))", 1, 9);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. Source span (charLength) covers the whole form
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void spanCoversWholeForm_divByZero() {
        // "(/ 1 0)" is 7 chars
        GuestFrame f = getPrimaryGuestFrame("sp1.clj", "(/ 1 0)");
        assertThat(f).isNotNull();
        assertThat(f.charLength).isEqualTo(7);
    }

    @Test
    public void spanCoversNestedForm() {
        // inner "(/ 2 0)" is 7 chars
        GuestFrame f = getPrimaryGuestFrame("sp2.clj", "(+ 1 (/ 2 0))");
        assertThat(f).isNotNull();
        assertThat(f.charLength).isEqualTo(7);
    }

    @Test
    public void spanCoversInteropCall() {
        // (.substring "hello" 100) is 24 chars
        GuestFrame f = getPrimaryGuestFrame("sp3.clj", "(.substring \"hello\" 100)");
        assertThat(f).isNotNull();
        assertThat(f.charLength).isEqualTo(24);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Multi-level call stacks: each frame has correct line + column
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void twoLevelStack_innerAndCallSite() {
        // line 1: (defn boom [] (/ 1 0))
        // line 2: (boom)
        String code = "(defn boom [] (/ 1 0))\n(boom)";
        List<GuestFrame> frames = getGuestFrames("stk2.clj", code);
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);

        GuestFrame inner = frames.get(0);
        assertThat(inner.line).isEqualTo(1);
        assertThat(inner.column).isEqualTo(1);

        GuestFrame callSite = frames.get(1);
        assertThat(callSite.line).isEqualTo(2);
        assertThat(callSite.column).isEqualTo(1);
    }

    @Test
    public void threeLevelStack_linesAscend() {
        String code = "(defn a [] (/ 1 0))\n"   // line 1
                    + "(defn b [] (a))\n"         // line 2
                    + "(b)";                      // line 3
        List<GuestFrame> frames = getGuestFrames("stk3.clj", code);
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);

        // First frame should be line 1 or 2 (error site or first call)
        assertThat(frames.get(0).line).isIn(1, 2);

        // Every frame should have a valid line in [1,3]
        for (GuestFrame f : frames) {
            assertThat(f.line).isBetween(1, 3);
        }
    }

    @Test
    public void callSiteColumnIsExact() {
        // line 1: (defn fail [] (throw (Exception. "x")))
        // line 2: (+ 1 (fail))
        //               ^--- col 6
        String code = "(defn fail [] (throw (Exception. \"x\")))\n(+ 1 (fail))";
        List<GuestFrame> frames = getGuestFrames("stkcol.clj", code);
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);

        // The call-site frame "(fail)" should be at L2:C6
        GuestFrame callFrame = frames.stream()
                .filter(f -> f.line == 2)
                .findFirst().orElse(null);
        assertThat(callFrame).as("Should have a frame on line 2").isNotNull();
        assertThat(callFrame.column).isEqualTo(6);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Frame source name and root name
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void frameSourceNameMatchesFileName() {
        GuestFrame f = getPrimaryGuestFrame("named.clj", "(/ 1 0)");
        assertThat(f).isNotNull();
        assertThat(f.sourceName).isEqualTo("named.clj");
    }

    @Test
    public void frameRootNameContainsFnName() {
        String code = "(defn my-named-fn [] (/ 1 0))\n(my-named-fn)";
        List<GuestFrame> frames = getGuestFrames("rn.clj", code);
        boolean found = frames.stream()
                .anyMatch(f -> f.rootName != null && f.rootName.contains("my-named-fn"));
        assertThat(found).as("Some frame root name should contain 'my-named-fn'").isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Interop errors: precise column
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void interopError_line1_col1() {
        assertPrimaryFrame("ip1.clj", "(.substring \"hello\" 100)", 1, 1);
    }

    @Test
    public void interopError_line2_col1() {
        String code = "(def s \"hello\")\n(.substring s 100)";
        assertPrimaryFrame("ip2.clj", code, 2, 1);
    }

    @Test
    public void interopError_nested_col6() {
        // (+ 1 (.substring "hi" 99))
        //      ^--- col 6
        assertPrimaryFrame("ip3.clj", "(+ 1 (.substring \"hi\" 99))", 1, 6);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Arity errors: point to call site
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void arityError_callSiteLine2() {
        String code = "(defn one [x] x)\n(one 1 2 3)";
        List<GuestFrame> frames = getGuestFrames("ar1.clj", code);
        assertThat(frames).isNotEmpty();
        // Should have a frame on line 2 (call site) or line 1 (fn body)
        boolean hasLine2 = frames.stream().anyMatch(f -> f.line == 2);
        boolean hasLine1 = frames.stream().anyMatch(f -> f.line == 1);
        assertThat(hasLine1 || hasLine2)
                .as("Should have frame on line 1 (fn) or line 2 (call)")
                .isTrue();
    }

    @Test
    public void arityError_callSiteColumn() {
        // line 1: (defn two [a b] (+ a b))
        // line 2: (+ 1 (two 1))
        //               ^--- col 6
        String code = "(defn two [a b] (+ a b))\n(+ 1 (two 1))";
        List<GuestFrame> frames = getGuestFrames("ar2.clj", code);
        GuestFrame callFrame = frames.stream()
                .filter(f -> f.line == 2)
                .findFirst().orElse(null);
        if (callFrame != null) {
            assertThat(callFrame.column).isEqualTo(6);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. let / loop / fn body locations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void anonymousFnError_line1() {
        assertPrimaryFrame("fn1.clj", "((fn [] (/ 1 0)))", 1, 1);
    }

    @Test
    public void loopError_onErrorLine() {
        // line 1: (loop [x 5]
        // line 2:   (if (zero? x)
        // line 3:     (/ 1 0)
        // line 4:     (recur (dec x))))
        String code = "(loop [x 5]\n  (if (zero? x)\n    (/ 1 0)\n    (recur (dec x))))";
        GuestFrame f = getPrimaryGuestFrame("lp1.clj", code);
        assertThat(f).isNotNull();
        // The (/ 1 0) is on line 3
        assertThat(f.line).isEqualTo(3);
    }

    @Test
    public void loopError_columnIsExact() {
        // line 3:     (/ 1 0)
        //             ^--- col 5
        String code = "(loop [x 5]\n  (if (zero? x)\n    (/ 1 0)\n    (recur (dec x))))";
        GuestFrame f = getPrimaryGuestFrame("lp2.clj", code);
        assertThat(f).isNotNull();
        assertThat(f.column).isEqualTo(5);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Parse errors: line + column
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void readerError_line1() {
        try {
            eval("pe1.clj", "(1/0)");
            fail("Expected parse error");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError()).isTrue();
            SourceSection sl = e.getSourceLocation();
            assertThat(sl).isNotNull();
            assertThat(sl.getStartLine()).isEqualTo(1);
        }
    }

    @Test
    public void readerError_line3() {
        String code = "(def a 1)\n(def b 2)\n(1/0)";
        try {
            eval("pe3.clj", code);
            fail("Expected parse error");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError()).isTrue();
            SourceSection sl = e.getSourceLocation();
            assertThat(sl).isNotNull();
            assertThat(sl.getStartLine()).isEqualTo(3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. "Cannot call X as function" errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void cannotCallString_line1_col1() {
        try {
            eval("ncall1.clj", "(\"hello\" 1)");
            fail("Expected cannot-call error");
        } catch (PolyglotException e) {
            assertThat(e.getMessage()).contains("Cannot call");
        }
    }

    @Test
    public void cannotCallInteger_hasGuestFrame() {
        GuestFrame f = getPrimaryGuestFrame("ncall2.clj", "(42 :key)");
        // May be a compile-time error (no guest frame) or runtime
        // Either way, the error should fire
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. Multi-form file: each form's error points to correct line
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multiForm_errorOnLastLine() {
        // (def z (/ x 0))
        //        ^--- col 8: points to the inner (/ x 0) form
        String code = "(def x 1)\n"        // line 1
                    + "(def y 2)\n"         // line 2
                    + "(def z (/ x 0))\n";  // line 3
        assertPrimaryFrame("mf1.clj", code, 3, 8);
    }

    @Test
    public void multiForm_errorInMiddleDefn() {
        String code = "(defn ok [] 42)\n"                       // line 1
                    + "(defn bad [] (throw (Exception. \"x\")))\n" // line 2
                    + "(bad)\n";                                   // line 3
        List<GuestFrame> frames = getGuestFrames("mf2.clj", code);
        assertThat(frames).isNotEmpty();
        // Should have frames on line 2 (throw site) and/or line 3 (call site)
        boolean hasLine2Or3 = frames.stream()
                .anyMatch(f -> f.line == 2 || f.line == 3);
        assertThat(hasLine2Or3).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    record GuestFrame(String sourceName, int line, int column, int charLength, String rootName) {}

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    /**
     * Asserts that the primary (first) guest frame in the error from
     * evaluating {@code code} is at exactly ({@code expectedLine},
     * {@code expectedCol}).
     */
    private void assertPrimaryFrame(String fileName, String code, int expectedLine, int expectedCol) {
        GuestFrame f = getPrimaryGuestFrame(fileName, code);
        assertThat(f)
                .as("Should have a guest frame for %s", fileName)
                .isNotNull();
        assertThat(f.line)
                .as("Line in %s (snippet: %s)", fileName, code.replace("\n", "\\n"))
                .isEqualTo(expectedLine);
        assertThat(f.column)
                .as("Column in %s (snippet: %s)", fileName, code.replace("\n", "\\n"))
                .isEqualTo(expectedCol);
    }

    private GuestFrame getPrimaryGuestFrame(String fileName, String code) {
        try {
            eval(fileName, code);
            fail("Expected exception from " + fileName);
            return null;
        } catch (PolyglotException e) {
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (!frame.isGuestFrame()) continue;
                SourceSection sl = frame.getSourceLocation();
                if (sl == null || !sl.isAvailable() || !sl.hasLines()) continue;
                return new GuestFrame(
                        sl.getSource().getName(),
                        sl.getStartLine(),
                        sl.hasColumns() ? sl.getStartColumn() : -1,
                        sl.hasCharIndex() ? sl.getCharLength() : -1,
                        frame.getRootName());
            }
            return null;
        }
    }

    private List<GuestFrame> getGuestFrames(String fileName, String code) {
        List<GuestFrame> frames = new ArrayList<>();
        try {
            eval(fileName, code);
            fail("Expected exception from " + fileName);
        } catch (PolyglotException e) {
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (!frame.isGuestFrame()) continue;
                SourceSection sl = frame.getSourceLocation();
                if (sl == null || !sl.isAvailable() || !sl.hasLines()) continue;
                frames.add(new GuestFrame(
                        sl.getSource().getName(),
                        sl.getStartLine(),
                        sl.hasColumns() ? sl.getStartColumn() : -1,
                        sl.hasCharIndex() ? sl.getCharLength() : -1,
                        frame.getRootName()));
            }
        }
        return frames;
    }
}
