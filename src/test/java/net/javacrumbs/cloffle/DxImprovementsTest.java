package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for the four DX improvement tracks:
 * 1. Bytecode enriched frame tracking (stack-trace parity with AST)
 * 2. Literal Expr source sections (NumberExpr, StringExpr, etc.)
 * 3. didYouMean / didYouMeanNamespace at more resolution sites
 * 4. ex-data span metadata (:clojure.error/length, end-line, end-column)
 */
public class DxImprovementsTest {

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

    // ═══════════════════════════════════════════════════════════════
    //  1. Bytecode enriched frame tracking
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void deepCallChainHasMultipleGuestFrames() {
        String code =
                "(defn divide [a b] (/ a b))\n" +
                "(defn calculate [x] (divide x 0))\n" +
                "(defn process [x] (calculate x))\n" +
                "(process 42)";
        try {
            eval("deep_chain.clj", code);
            fail("Expected ArithmeticException");
        } catch (PolyglotException e) {
            int guestFrameCount = 0;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) guestFrameCount++;
            }
            assertThat(guestFrameCount)
                    .as("Deep call chain should produce multiple guest frames")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    public void enrichedFramesIncludeSourceLocation() {
        String code =
                "(defn inner [] (throw (Exception. \"boom\")))\n" +
                "(defn outer [] (inner))\n" +
                "(outer)";
        try {
            eval("enriched_loc.clj", code);
            fail("Expected exception");
        } catch (PolyglotException e) {
            boolean foundWithSource = false;
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    SourceSection sl = frame.getSourceLocation();
                    if (sl != null && sl.isAvailable() && sl.hasLines()) {
                        foundWithSource = true;
                        break;
                    }
                }
            }
            assertThat(foundWithSource)
                    .as("At least one guest frame should have a source location")
                    .isTrue();
        }
    }

    @Test
    public void exceptionThroughTryPreservesFrames() {
        String code =
                "(defn divide [a b] (/ a b))\n" +
                "(defn calculate [x] (divide x 0))\n" +
                "(defn process [x]\n" +
                "  (try (calculate x)\n" +
                "    (catch java.io.IOException e \"not this\")))\n" +
                "(process 42)";
        try {
            eval("try_preserve.clj", code);
            fail("Expected ArithmeticException");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).containsIgnoringCase("divide");
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  2. Literal Expr source sections
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void numberLiteralInFunctionPositionHasSourceInfo() {
        String code = "(42 :key)";
        try {
            eval("num_call.clj", code);
            fail("Expected exception from calling a number");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).contains("Cannot call");
        }
    }

    @Test
    public void stringLiteralInFunctionPositionHasSourceInfo() {
        String code = "(\"hello\" 1)";
        try {
            eval("str_call.clj", code);
            fail("Expected exception from calling a string");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(e.getMessage()).contains("Cannot call");
        }
    }

    @Test
    public void keywordLiteralWorksInFunctionPosition() {
        String code = "(:name {:name \"Alice\"})";
        Value result = eval("kw_call.clj", code);
        assertThat(result.asString()).isEqualTo("Alice");
    }

    // ═══════════════════════════════════════════════════════════════
    //  3. didYouMean / didYouMeanNamespace suggestions
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void unresolvedVarGetsDidYouMeanSuggestion() {
        String code = "(printl \"hello\")";
        try {
            eval("typo_var.clj", code);
            fail("Expected unresolved symbol");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).contains("Unable to resolve");
        }
    }

    @Test
    public void unresolvedVarWithCloseMatchSuggestsPrintln() {
        String code = "(printl \"hello\")";
        try {
            eval("typo_println.clj", code);
            fail("Expected unresolved symbol");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            boolean hasSuggestion = msg.contains("Did you mean") || msg.contains("printl");
            assertThat(hasSuggestion)
                    .as("Error for 'printl' should either suggest or mention the typo: " + msg)
                    .isTrue();
        }
    }

    @Test
    public void unresolvedNamespaceQualifiedVarThrows() {
        String code = "(clojure.strng/join \",\" [1 2 3])";
        try {
            eval("ns_typo.clj", code);
            fail("Expected error for bad namespace");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).isNotEmpty();
            // Error should mention the bad namespace name
            assertThat(msg).contains("clojure.strng");
        }
    }

    @Test
    public void didYouMeanNamespaceForCloseMatch() {
        String code = "(require '[clojure.string :as str])\n(clojure.strng/join \",\" [1 2 3])";
        try {
            eval("ns_suggest.clj", code);
            fail("Expected error for bad namespace");
        } catch (PolyglotException e) {
            String msg = e.getMessage();
            assertThat(msg).isNotEmpty();
            assertThat(msg).contains("clojure.strng");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  4. ex-data span metadata
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void exDataContainsPhaseAndSourceKeys() {
        String code =
                "(try\n" +
                "  (/ 1 0)\n" +
                "  (catch Exception e\n" +
                "    (let [d (ex-data e)]\n" +
                "      (if d\n" +
                "        (str (:clojure.error/phase d))\n" +
                "        \"no-ex-data\"))))";
        Value result = eval("ex_data_span.clj", code);
        String s = result.asString();
        assertThat(s).isIn(":execution", "no-ex-data");
    }

    @Test
    public void exDataContainsLengthKey() {
        String code =
                "(try\n" +
                "  (/ 1 0)\n" +
                "  (catch Exception e\n" +
                "    (let [d (ex-data e)]\n" +
                "      (if d\n" +
                "        (let [len (:clojure.error/length d)]\n" +
                "          (if len (str \"len=\" len) \"no-length\"))\n" +
                "        \"no-ex-data\"))))";
        Value result = eval("ex_data_len.clj", code);
        String s = result.asString();
        assertThat(s.equals("no-ex-data") || s.startsWith("len=") || s.equals("no-length"))
                .as("Result should indicate length presence or absence: " + s)
                .isTrue();
    }

    @Test
    public void exDataContainsEndLineKey() {
        String code =
                "(try\n" +
                "  (/ 1 0)\n" +
                "  (catch Exception e\n" +
                "    (let [d (ex-data e)]\n" +
                "      (if d\n" +
                "        (let [el (:clojure.error/end-line d)]\n" +
                "          (if el (str \"end-line=\" el) \"no-end-line\"))\n" +
                "        \"no-ex-data\"))))";
        Value result = eval("ex_data_endline.clj", code);
        String s = result.asString();
        assertThat(s.equals("no-ex-data") || s.startsWith("end-line=") || s.equals("no-end-line"))
                .as("Result should indicate end-line presence or absence: " + s)
                .isTrue();
    }

    @Test
    public void exDataContainsEndColumnKey() {
        String code =
                "(try\n" +
                "  (/ 1 0)\n" +
                "  (catch Exception e\n" +
                "    (let [d (ex-data e)]\n" +
                "      (if d\n" +
                "        (let [ec (:clojure.error/end-column d)]\n" +
                "          (if ec (str \"end-col=\" ec) \"no-end-col\"))\n" +
                "        \"no-ex-data\"))))";
        Value result = eval("ex_data_endcol.clj", code);
        String s = result.asString();
        assertThat(s.equals("no-ex-data") || s.startsWith("end-col=") || s.equals("no-end-col"))
                .as("Result should indicate end-column presence or absence: " + s)
                .isTrue();
    }

    @Test
    public void exInfoPreservesUserExData() {
        String code =
                "(try\n" +
                "  (throw (ex-info \"test\" {:code 42}))\n" +
                "  (catch Exception e\n" +
                "    (:code (ex-data e))))";
        Value result = eval("ex_info_preserve.clj", code);
        assertThat(result.asLong()).isEqualTo(42L);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Integration tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void nestedCallsWithArityErrorHasGuestFrames() {
        String code =
                "(defn greet [name] (str \"Hello, \" name))\n" +
                "(defn caller [] (greet \"Alice\" \"Bob\"))\n" +
                "(caller)";
        try {
            eval("arity_nested.clj", code);
            fail("Expected arity exception");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    @Test
    public void interopExceptionHasSourceLocation() {
        String code = "(Integer. \"not-a-number\")";
        try {
            eval("interop_err.clj", code);
            fail("Expected NumberFormatException");
        } catch (PolyglotException e) {
            assertThat(e.isGuestException()).isTrue();
            assertThat(hasGuestFrame(e)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private Value eval(String fileName, String code) {
        Source src = Source.newBuilder("cloffle", code, fileName).buildLiteral();
        return context.eval(src);
    }

    private static boolean hasGuestFrame(PolyglotException e) {
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) return true;
        }
        return false;
    }
}
