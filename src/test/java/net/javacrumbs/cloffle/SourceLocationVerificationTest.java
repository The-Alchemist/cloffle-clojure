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
 * Verifies precise line, column, AND length of source locations reported
 * by Cloffle so that tooling can draw red squiggles under the exact form
 * that triggered an error.
 *
 * <p>Each test constructs code at known positions, triggers an error, and
 * asserts the primary guest frame's (line, column, charLength) triple.
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
        Value r = eval("m1.clj", "(defn f [x] x)\n[(:line (meta #'f)) (:column (meta #'f))]");
        assertThat(r.toString()).isEqualTo("[1 1]");
    }

    @Test
    public void defnOnLine3Col1() {
        String code = ";; line 1\n;; line 2\n(defn g [x] x)\n[(:line (meta #'g)) (:column (meta #'g))]";
        assertThat(eval("m3.clj", code).toString()).isEqualTo("[3 1]");
    }

    @Test
    public void defnWithLeadingSpacesCol5() {
        String code = "    (defn spaced [x] x)\n[(:line (meta #'spaced)) (:column (meta #'spaced))]";
        assertThat(eval("msp.clj", code).toString()).isEqualTo("[1 5]");
    }

    @Test
    public void twoDefnsHaveCorrectLines() {
        String code = "(defn first-fn [] 1)\n\n\n(defn second-fn [] 2)\n"
                    + "[(:line (meta #'first-fn)) (:line (meta #'second-fn))]";
        assertThat(eval("m2d.clj", code).toString()).isEqualTo("[1 4]");
    }

    @Test
    public void defnFileMatchesSourceName() {
        assertThat(eval("my-script.clj", "(defn ff [] 42)\n(:file (meta #'ff))").asString())
                .isEqualTo("my-script.clj");
    }

    @Test
    public void coreFnWhenHasPositiveLineAndColumn() {
        assertThat(eval("cm.clj", "[(> (:line (meta #'when)) 0) (> (:column (meta #'when)) 0)]").toString())
                .isEqualTo("[true true]");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Simple arithmetic errors: line, column, length
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void divByZero_L1_C1_len7() {
        // (/ 1 0)
        // ^~~~~~~  C1, len=7
        assertPrimaryFrame("a1.clj", "(/ 1 0)", 1, 1, 7);
    }

    @Test
    public void divByZero_leadingSpaces_L1_C4_len7() {
        //    (/ 1 0)
        //    ^~~~~~~  C4, len=7
        assertPrimaryFrame("a2.clj", "   (/ 1 0)", 1, 4, 7);
    }

    @Test
    public void divByZero_nested_L1_C6_len7() {
        // (+ 1 (/ 2 0))
        //      ^~~~~~~  C6, len=7
        assertPrimaryFrame("a3.clj", "(+ 1 (/ 2 0))", 1, 6, 7);
    }

    @Test
    public void divByZero_deepNest_L1_C11_len7() {
        // (+ 1 (* 2 (/ 3 0)))
        //           ^~~~~~~  C11, len=7
        assertPrimaryFrame("a4.clj", "(+ 1 (* 2 (/ 3 0)))", 1, 11, 7);
    }

    @Test
    public void divByZero_multiline_L2_C4_len7() {
        // line 1: (+ 1
        // line 2:    (/ 2 0))
        //            ^~~~~~~  L2:C4, len=7
        assertPrimaryFrame("a5.clj", "(+ 1\n   (/ 2 0))", 2, 4, 7);
    }

    @Test
    public void divByZero_thirdForm_L3_C1_len7() {
        assertPrimaryFrame("a6.clj", "(def a 10)\n(def b 20)\n(/ a 0)", 3, 1, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. if branches: error in test, then, else
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void if_errorInTest_L1_C5_len7() {
        // (if (/ 1 0) :a :b)
        //     ^~~~~~~  C5, len=7
        assertPrimaryFrame("if1.clj", "(if (/ 1 0) :a :b)", 1, 5, 7);
    }

    @Test
    public void if_errorInThen_L2_C3_len7() {
        // (if true
        //   (/ 1 0)
        //   ^~~~~~~  L2:C3, len=7
        assertPrimaryFrame("if2.clj", "(if true\n  (/ 1 0)\n  :else)", 2, 3, 7);
    }

    @Test
    public void if_errorInElse_L3_C3_len7() {
        // (if false
        //   :then
        //   (/ 1 0))
        //   ^~~~~~~  L3:C3, len=7
        assertPrimaryFrame("if3.clj", "(if false\n  :then\n  (/ 1 0))", 3, 3, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. let: error in init vs body
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void let_errorInFirstInit_L1_C9_len7() {
        // (let [x (/ 1 0)] x)
        //         ^~~~~~~  C9, len=7
        assertPrimaryFrame("let1.clj", "(let [x (/ 1 0)] x)", 1, 9, 7);
    }

    @Test
    public void let_errorInSecondInit_L2_C9_len7() {
        // (let [x 1
        //       y (/ 1 0)] y)
        //         ^~~~~~~  L2:C9, len=7
        assertPrimaryFrame("let2.clj", "(let [x 1\n      y (/ 1 0)] y)", 2, 9, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. do: error in last expression
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void do_errorInLast_L1_C9_len7() {
        // (do 1 2 (/ 3 0))
        //         ^~~~~~~  C9, len=7
        assertPrimaryFrame("do1.clj", "(do 1 2 (/ 3 0))", 1, 9, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. throw: line, column, length
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void throw_L1_C1_len24() {
        // (throw (Exception. "x"))
        // ^~~~~~~~~~~~~~~~~~~~~~~~  C1, len=24
        assertPrimaryFrame("th1.clj", "(throw (Exception. \"x\"))", 1, 1, 24);
    }

    @Test
    public void throw_nested_callSite_L1_C6_len24() {
        // (+ 1 (throw (Exception. "x")))
        //      ^~~~~~~~~~~~~~~~~~~~~~~~  call-site frame at C6, len=24
        List<GuestFrame> frames = getGuestFrames("th2.clj", "(+ 1 (throw (Exception. \"x\")))");
        GuestFrame throwFrame = frames.stream()
                .filter(f -> f.charLength == 24)
                .findFirst().orElse(null);
        assertThat(throwFrame).as("Should have a 24-char throw frame").isNotNull();
        assertThat(throwFrame.line).isEqualTo(1);
        assertThat(throwFrame.column).isEqualTo(6);
    }

    @Test
    public void throw_line2_L2_C3_len24() {
        // (do 1
        //   (throw (Exception. "x")))
        //   ^~~~~~~~~~~~~~~~~~~~~~~~  L2:C3, len=24
        List<GuestFrame> frames = getGuestFrames("th3.clj", "(do 1\n  (throw (Exception. \"x\")))");
        GuestFrame throwFrame = frames.stream()
                .filter(f -> f.charLength == 24)
                .findFirst().orElse(null);
        assertThat(throwFrame).isNotNull();
        assertThat(throwFrame.line).isEqualTo(2);
        assertThat(throwFrame.column).isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. cond / and / or macro expansion
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void cond_errorInLastBranch_L4_C9_len7() {
        // (cond
        //   false 1
        //   false 2
        //   :else (/ 1 0))
        //         ^~~~~~~  L4:C9, len=7
        assertPrimaryFrame("cond1.clj",
                "(cond\n  false 1\n  false 2\n  :else (/ 1 0))", 4, 9, 7);
    }

    @Test
    public void and_errorInSecond_L2_C6_len7() {
        // (and true
        //      (/ 1 0))
        //      ^~~~~~~  L2:C6, len=7
        assertPrimaryFrame("and1.clj", "(and true\n     (/ 1 0))", 2, 6, 7);
    }

    @Test
    public void or_errorInSecond_L2_C5_len7() {
        // (or false
        //     (/ 1 0))
        //     ^~~~~~~  L2:C5, len=7
        assertPrimaryFrame("or1.clj", "(or false\n    (/ 1 0))", 2, 5, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. Threading macros
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void threadFirst_errorForm_L2_C5_len5() {
        // (-> 0
        //     (/ 0))
        //     ^~~~~  L2:C5, len=5
        assertPrimaryFrame("tf1.clj", "(-> 0\n    (/ 0))", 2, 5, 5);
    }

    @Test
    public void threadLast_errorForm_L2_C6_len5() {
        // (->> 0
        //      (/ 1))
        //      ^~~~~  L2:C6, len=5
        assertPrimaryFrame("tl1.clj", "(->> 0\n     (/ 1))", 2, 6, 5);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Java interop: static methods, instance methods, constructors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void staticMethod_L1_C1_len24() {
        // (Integer/parseInt "xyz")
        // ^~~~~~~~~~~~~~~~~~~~~~~~  C1, len=24
        assertPrimaryFrame("sm1.clj", "(Integer/parseInt \"xyz\")", 1, 1, 24);
    }

    @Test
    public void staticMethod_nested_L1_C6_len24() {
        // (+ 1 (Integer/parseInt "xyz"))
        //      ^~~~~~~~~~~~~~~~~~~~~~~~  C6, len=24
        assertPrimaryFrame("sm2.clj", "(+ 1 (Integer/parseInt \"xyz\"))", 1, 6, 24);
    }

    @Test
    public void instanceMethod_L1_C1_len24() {
        // (.substring "hello" 100)
        // ^~~~~~~~~~~~~~~~~~~~~~~~  C1, len=24
        assertPrimaryFrame("im1.clj", "(.substring \"hello\" 100)", 1, 1, 24);
    }

    @Test
    public void instanceMethod_nested_L1_C6_len24() {
        assertPrimaryFrame("im2.clj", "(+ 1 (.substring \"hi\" 99))", 1, 6, 20);
    }

    @Test
    public void instanceMethod_L2_C1_len18() {
        String code = "(def s \"hello\")\n(.substring s 100)";
        assertPrimaryFrame("im3.clj", code, 2, 1, 18);
    }

    @Test
    public void constructor_L1_C1_len16() {
        // (Integer. "xyz")
        // ^~~~~~~~~~~~~~~~  C1, len=16
        assertPrimaryFrame("ct1.clj", "(Integer. \"xyz\")", 1, 1, 16);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. Collection literals with errors inside
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void vector_errorElement_L1_C2_len7() {
        // [(/ 1 0) 2 3]
        //  ^~~~~~~  C2, len=7
        assertPrimaryFrame("vec1.clj", "[(/ 1 0) 2 3]", 1, 2, 7);
    }

    @Test
    public void map_errorInValue_L1_C5_len7() {
        // {:a (/ 1 0)}
        //     ^~~~~~~  C5, len=7
        assertPrimaryFrame("map1.clj", "{:a (/ 1 0)}", 1, 5, 7);
    }

    @Test
    public void set_errorElement_L1_C3_len7() {
        // #{(/ 1 0)}
        //   ^~~~~~~  C3, len=7
        assertPrimaryFrame("set1.clj", "#{(/ 1 0)}", 1, 3, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. "Cannot call X as function" errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stringAsFn_L1_C1_len11() {
        // ("hello" 1)
        // ^~~~~~~~~~~  C1, len=11
        assertPrimaryFrame("scall.clj", "(\"hello\" 1)", 1, 1, 11);
    }

    @Test
    public void booleanAsFn_L1_C1_len8() {
        // (true 1)
        // ^~~~~~~~  C1, len=8
        assertPrimaryFrame("bcall.clj", "(true 1)", 1, 1, 8);
    }

    @Test
    public void numberAsFn_L1_C1_len9() {
        // (42 :key)
        // ^~~~~~~~~  C1, len=9
        assertPrimaryFrame("ncall.clj", "(42 :key)", 1, 1, 9);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. Multi-level call stacks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void twoLevelStack_throwSiteAndCallSite() {
        // line 1: (defn boom [] (/ 1 0))        <- error origin
        // line 2: (boom)                         <- call site
        String code = "(defn boom [] (/ 1 0))\n(boom)";
        List<GuestFrame> frames = getGuestFrames("stk2.clj", code);
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);
        assertThat(frames.get(0).line).isEqualTo(1);
        assertThat(frames.get(1).line).isEqualTo(2);
        assertThat(frames.get(1).column).isEqualTo(1);
    }

    @Test
    public void callSiteColumn_L2_C6() {
        // line 1: (defn fail [] (throw (Exception. "x")))
        // line 2: (+ 1 (fail))
        //               ^~~~~  L2:C6
        String code = "(defn fail [] (throw (Exception. \"x\")))\n(+ 1 (fail))";
        List<GuestFrame> frames = getGuestFrames("stkcol.clj", code);
        GuestFrame callFrame = frames.stream()
                .filter(f -> f.line == 2)
                .findFirst().orElse(null);
        assertThat(callFrame).as("Should have a frame on line 2").isNotNull();
        assertThat(callFrame.column).isEqualTo(6);
    }

    @Test
    public void threeLevelStack_allLinesValid() {
        String code = "(defn a [] (/ 1 0))\n(defn b [] (a))\n(b)";
        List<GuestFrame> frames = getGuestFrames("stk3.clj", code);
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);
        for (GuestFrame f : frames) {
            assertThat(f.line).isBetween(1, 3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  13. Arity errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void arityError_callSiteLine() {
        String code = "(defn one [x] x)\n(one 1 2 3)";
        List<GuestFrame> frames = getGuestFrames("ar1.clj", code);
        assertThat(frames).isNotEmpty();
        boolean hasExpectedLine = frames.stream()
                .anyMatch(f -> f.line == 1 || f.line == 2);
        assertThat(hasExpectedLine).isTrue();
    }

    @Test
    public void multiArityError_callSite_L4_C1() {
        String code = "(defn m\n  ([x] x)\n  ([x y] (+ x y)))\n(m 1 2 3)";
        List<GuestFrame> frames = getGuestFrames("ar2.clj", code);
        GuestFrame callFrame = frames.stream()
                .filter(f -> f.line == 4)
                .findFirst().orElse(null);
        assertThat(callFrame).as("Should have call-site frame on line 4").isNotNull();
        assertThat(callFrame.column).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  14. loop / recur
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void loopError_L3_C5_len7() {
        // line 1: (loop [x 5]
        // line 2:   (if (zero? x)
        // line 3:     (/ 1 0)             <- C5, len=7
        // line 4:     (recur (dec x))))
        assertPrimaryFrame("lp1.clj",
                "(loop [x 5]\n  (if (zero? x)\n    (/ 1 0)\n    (recur (dec x))))", 3, 5, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  15. Nested let / defn
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void defnLetDiv_callSite_L4_C1() {
        String code = "(defn f [x]\n  (let [y (* x 2)]\n    (/ y 0)))\n(f 5)";
        List<GuestFrame> frames = getGuestFrames("dld1.clj", code);
        GuestFrame callFrame = frames.stream()
                .filter(f -> f.line == 4)
                .findFirst().orElse(null);
        assertThat(callFrame).as("Should have call-site frame on line 4").isNotNull();
        assertThat(callFrame.column).isEqualTo(1);
        assertThat(callFrame.charLength).isEqualTo(5);
    }

    @Test
    public void defnInsideInnerDiv_L3_C8() {
        // (def z (/ x 0))
        //        ^~~~~~~  C8, len=7
        String code = "(def x 1)\n(def y 2)\n(def z (/ x 0))";
        assertPrimaryFrame("dld2.clj", code, 3, 8, 7);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. Frame source name and root name
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
    //  17. Parse errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void readerError_L1() {
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
    public void readerError_L3() {
        try {
            eval("pe3.clj", "(def a 1)\n(def b 2)\n(1/0)");
            fail("Expected parse error");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError()).isTrue();
            SourceSection sl = e.getSourceLocation();
            assertThat(sl).isNotNull();
            assertThat(sl.getStartLine()).isEqualTo(3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  18. try/catch: error location when not caught
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void tryCatchCatches() {
        Value r = eval("tc1.clj", "(try (/ 1 0) (catch ArithmeticException e :caught))");
        assertThat(r.toString()).isEqualTo(":caught");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    record GuestFrame(String sourceName, int line, int column, int charLength, String rootName) {}

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    private void assertPrimaryFrame(String fileName, String code,
                                    int expectedLine, int expectedCol, int expectedLen) {
        GuestFrame f = getPrimaryGuestFrame(fileName, code);
        assertThat(f)
                .as("Should have a guest frame for %s", fileName)
                .isNotNull();
        assertThat(f.line)
                .as("Line in %s", fileName)
                .isEqualTo(expectedLine);
        assertThat(f.column)
                .as("Column in %s", fileName)
                .isEqualTo(expectedCol);
        assertThat(f.charLength)
                .as("Length in %s", fileName)
                .isEqualTo(expectedLen);
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
