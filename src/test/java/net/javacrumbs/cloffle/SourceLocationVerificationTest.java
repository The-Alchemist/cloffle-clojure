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
 * Verifies that Cloffle source locations are precise. Each test constructs
 * Clojure code with known line/column positions, triggers an error or
 * inspects var metadata, and asserts that the reported location matches.
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
    public void defnOnLine1HasLine1() {
        Value result = eval("meta1.clj",
                "(defn f [x] x)\n(:line (meta #'f))");
        assertThat(result.asLong()).isEqualTo(1L);
    }

    @Test
    public void defnOnLine3HasLine3() {
        String code = ";; comment line 1\n"
                    + ";; comment line 2\n"
                    + "(defn g [x] (* x x))\n"
                    + "(:line (meta #'g))";
        Value result = eval("meta3.clj", code);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void secondDefnOnLine4HasLine4() {
        String code = "(defn first-fn [] 1)\n"
                    + "\n"
                    + "\n"
                    + "(defn second-fn [] 2)\n"
                    + "[(:line (meta #'first-fn)) (:line (meta #'second-fn))]";
        Value result = eval("meta_two.clj", code);
        assertThat(result.toString()).isEqualTo("[1 4]");
    }

    @Test
    public void defnColumnIsCorrect() {
        String code = "(defn col-fn [x] x)\n(:column (meta #'col-fn))";
        Value result = eval("meta_col.clj", code);
        assertThat(result.asLong()).isEqualTo(1L);
    }

    @Test
    public void defnColumnWithLeadingSpaces() {
        String code = "  (defn spaced-fn [x] x)\n(:column (meta #'spaced-fn))";
        Value result = eval("meta_col_space.clj", code);
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void defnFileMetadataMatchesSourceName() {
        Value result = eval("my-source-file.clj",
                "(defn file-fn [] 42)\n(:file (meta #'file-fn))");
        assertThat(result.asString()).isEqualTo("my-source-file.clj");
    }

    @Test
    public void coreFnWhenHasPositiveLine() {
        Value result = eval("core_meta.clj",
                "(:line (meta #'when))");
        assertThat(result.asLong()).isGreaterThan(0L);
    }

    @Test
    public void coreFnWhenHasPositiveColumn() {
        Value result = eval("core_meta.clj",
                "(:column (meta #'when))");
        assertThat(result.asLong()).isGreaterThan(0L);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Error location: primary guest frame line/column
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void divisionByZeroOnLine1() {
        //            1234567
        // line 1:    (/ 1 0)
        assertErrorOnLine("div0_l1.clj", "(/ 1 0)", 1);
    }

    @Test
    public void divisionByZeroOnLine3() {
        String code = "(def a 10)\n"     // line 1
                    + "(def b 20)\n"     // line 2
                    + "(/ a 0)\n";       // line 3
        assertErrorOnLine("div0_l3.clj", code, 3);
    }

    @Test
    public void exceptionInNestedCall_innerOnLine1() {
        String code = "(defn boom [] (throw (Exception. \"bang\")))\n"  // line 1
                    + "(boom)";                                         // line 2
        GuestFrame primary = getPrimaryGuestFrame("nested1.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.line)
                .as("Primary frame should be at line 1 (throw) or 2 (call site)")
                .isIn(1, 2);
    }

    @Test
    public void exceptionInDeepStack_eachFrameHasCorrectLine() {
        String code = "(defn a [] (/ 1 0))\n"   // line 1 — error site
                    + "(defn b [] (a))\n"         // line 2
                    + "(defn c [] (b))\n"         // line 3
                    + "(c)";                      // line 4
        List<GuestFrame> frames = getGuestFrames("deep.clj", code);
        assertThat(frames).as("Should have guest frames").isNotEmpty();

        for (GuestFrame f : frames) {
            assertThat(f.line)
                    .as("Guest frame line should be between 1 and 4, got %d in %s", f.line, f.rootName)
                    .isBetween(1, 4);
        }
    }

    @Test
    public void errorColumnOnLine1() {
        //         col: 1234567
        // line 1:      (/ 1 0)
        GuestFrame primary = getPrimaryGuestFrame("col1.clj", "(/ 1 0)");
        assertThat(primary).isNotNull();
        assertThat(primary.line).isEqualTo(1);
        assertThat(primary.column).isGreaterThanOrEqualTo(1);
    }

    @Test
    public void errorAfterWhitespace() {
        //         col: 12345678901
        // line 1:        (/ 1 0)
        String code = "      (/ 1 0)";
        GuestFrame primary = getPrimaryGuestFrame("ws.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.line).isEqualTo(1);
        assertThat(primary.column).isGreaterThanOrEqualTo(7);
    }

    @Test
    public void multiFormError_errorOnCorrectLine() {
        String code = "(def x 1)\n"       // line 1
                    + "(def y 2)\n"        // line 2
                    + "(def z (/ x 0))\n"; // line 3 — error
        assertErrorOnLine("multi_err.clj", code, 3);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. Source file name in stack frames
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void guestFrameSourceNameMatchesFileName() {
        String code = "(throw (Exception. \"test\"))";
        GuestFrame primary = getPrimaryGuestFrame("my_file.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.sourceName).isEqualTo("my_file.clj");
    }

    @Test
    public void guestFrameRootNameContainsFnName() {
        String code = "(defn named-fn [] (throw (Exception. \"x\")))\n"
                    + "(named-fn)";
        List<GuestFrame> frames = getGuestFrames("fn_name.clj", code);
        boolean foundNamedFn = frames.stream()
                .anyMatch(f -> f.rootName != null && f.rootName.contains("named-fn"));
        assertThat(foundNamedFn)
                .as("Should have a frame whose root name contains 'named-fn'")
                .isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. let / loop / fn source locations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void letBindingErrorHasLocation() {
        String code = "(let [x (/ 1 0)] x)";
        assertErrorOnLine("let_err.clj", code, 1);
    }

    @Test
    public void loopRecurErrorHasLocation() {
        String code = "(loop [x 5]\n"           // line 1
                    + "  (if (zero? x)\n"         // line 2
                    + "    (/ 1 0)\n"              // line 3 — error
                    + "    (recur (dec x))))";      // line 4
        assertErrorOnLine("loop_err.clj", code, 3);
    }

    @Test
    public void anonymousFnErrorHasLocation() {
        String code = "((fn [] (/ 1 0)))";
        assertErrorOnLine("anon_fn.clj", code, 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Interop error locations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void javaMethodCallErrorHasLocation() {
        String code = "(.substring \"hello\" 100)";
        GuestFrame primary = getPrimaryGuestFrame("interop_err.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.line).isEqualTo(1);
    }

    @Test
    public void javaMethodCallOnLine2() {
        String code = "(def s \"hello\")\n"
                    + "(.substring s 100)";
        GuestFrame primary = getPrimaryGuestFrame("interop_l2.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.line).isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Arity error locations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void arityErrorFromDefnHasLocation() {
        String code = "(defn one-arg [x] x)\n"
                    + "(one-arg 1 2 3)";
        GuestFrame primary = getPrimaryGuestFrame("arity_loc.clj", code);
        assertThat(primary).isNotNull();
        assertThat(primary.line)
                .as("Arity error should point to the call site (line 2) or fn body (line 1)")
                .isIn(1, 2);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Parse error locations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void readerErrorHasSourceLocation() {
        try {
            eval("parse_loc.clj", "(1/0)");
            fail("Expected parse error");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError()).isTrue();
            SourceSection sl = e.getSourceLocation();
            assertThat(sl).isNotNull();
            assertThat(sl.getStartLine()).isEqualTo(1);
        }
    }

    @Test
    public void readerErrorOnLine3() {
        String code = "(def a 1)\n"
                    + "(def b 2)\n"
                    + "(1/0)";
        try {
            eval("parse_l3.clj", code);
            fail("Expected parse error");
        } catch (PolyglotException e) {
            assertThat(e.isSyntaxError()).isTrue();
            SourceSection sl = e.getSourceLocation();
            assertThat(sl).isNotNull();
            assertThat(sl.getStartLine()).isEqualTo(3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    record GuestFrame(String sourceName, int line, int column, String rootName) {}

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    private void assertErrorOnLine(String fileName, String code, int expectedLine) {
        GuestFrame primary = getPrimaryGuestFrame(fileName, code);
        assertThat(primary)
                .as("Should have at least one guest frame for error in %s", fileName)
                .isNotNull();
        assertThat(primary.line)
                .as("Primary guest frame in %s should be on line %d", fileName, expectedLine)
                .isEqualTo(expectedLine);
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
                        frame.getRootName()));
            }
        }
        return frames;
    }
}
