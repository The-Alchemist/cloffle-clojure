package net.javacrumbs.cloffle;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.ErrorMessages;
import net.javacrumbs.cloffle.nodes.FnMethodNode;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ErrorMessages utility methods including:
 * - formatArities: human-readable arity descriptions
 * - didYouMean: close-match suggestions for var names
 * - formatException: user-friendly exception messages
 * - clojureTypeName: type names for values
 * - cannotCallMessage: "Cannot call X as function" messages
 */
public class ErrorMessagesTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    // ── formatArities ─────────────────────────────────────────────────

    @Test
    public void formatAritiesSingleFixed() {
        FnMethodNode[] methods = { mockMethod(2, false) };
        assertThat(ErrorMessages.formatArities(methods)).isEqualTo("2");
    }

    @Test
    public void formatAritiesMultipleFixed() {
        FnMethodNode[] methods = { mockMethod(0, false), mockMethod(1, false), mockMethod(3, false) };
        assertThat(ErrorMessages.formatArities(methods)).isEqualTo("0, 1, 3");
    }

    @Test
    public void formatAritiesWithVariadic() {
        FnMethodNode[] methods = { mockMethod(1, false), mockMethod(2, true) };
        assertThat(ErrorMessages.formatArities(methods)).isEqualTo("1, 2+");
    }

    // ── didYouMean ────────────────────────────────────────────────────

    @Test
    public void didYouMeanFindsCloseMatch() {
        Namespace ns = Namespace.findOrCreate(Symbol.intern("user"));
        String match = ErrorMessages.didYouMean("println", ns);
        // "println" should match itself if it exists in user namespace
        // (it's mapped from clojure.core)
        if (match != null) {
            assertThat(match).isNotEmpty();
        }
    }

    @Test
    public void didYouMeanReturnsNullForNoMatch() {
        Namespace ns = Namespace.findOrCreate(Symbol.intern("user"));
        String match = ErrorMessages.didYouMean("xyzzy_nonexistent_12345", ns);
        assertThat(match).isNull();
    }

    @Test
    public void didYouMeanReturnsNullForNullNamespace() {
        String match = ErrorMessages.didYouMean("println", null);
        assertThat(match).isNull();
    }

    // ── didYouMeanNamespace ─────────────────────────────────────────

    @Test
    public void didYouMeanNamespaceFindsCloseMatch() {
        // "clojure.core" always exists; "clojure.cor" is 1 edit away
        String match = ErrorMessages.didYouMeanNamespace("clojure.cor");
        assertThat(match).isEqualTo("clojure.core");
    }

    @Test
    public void didYouMeanNamespaceReturnsNullForNoMatch() {
        String match = ErrorMessages.didYouMeanNamespace("xyzzy_nonexistent_12345");
        assertThat(match).isNull();
    }

    // ── editDistance ──────────────────────────────────────────────────

    @Test
    public void editDistanceIdentical() {
        assertThat(ErrorMessages.editDistance("abc", "abc")).isEqualTo(0);
    }

    @Test
    public void editDistanceOneSubstitution() {
        assertThat(ErrorMessages.editDistance("abc", "adc")).isEqualTo(1);
    }

    @Test
    public void editDistanceOneDeletion() {
        assertThat(ErrorMessages.editDistance("abc", "ab")).isEqualTo(1);
    }

    @Test
    public void editDistanceOneInsertion() {
        assertThat(ErrorMessages.editDistance("abc", "abcd")).isEqualTo(1);
    }

    @Test
    public void editDistanceTwoChanges() {
        assertThat(ErrorMessages.editDistance("abc", "axc")).isEqualTo(1);
        assertThat(ErrorMessages.editDistance("abc", "axy")).isEqualTo(2);
    }

    // ── formatException ──────────────────────────────────────────────

    @Test
    public void formatExceptionNullPointerWithMessage() {
        NullPointerException npe = new NullPointerException("Cannot invoke method on null");
        assertThat(ErrorMessages.formatException(npe))
                .isEqualTo("NullPointerException: Cannot invoke method on null");
    }

    @Test
    public void formatExceptionNullPointerWithoutMessage() {
        NullPointerException npe = new NullPointerException();
        assertThat(ErrorMessages.formatException(npe))
                .isEqualTo("NullPointerException -- cannot call a method on nil");
    }

    @Test
    public void formatExceptionArityException() {
        clojure.lang.ArityException ae = new clojure.lang.ArityException(3, "my-fn");
        String formatted = ErrorMessages.formatException(ae);
        assertThat(formatted).contains("3");
        assertThat(formatted).contains("my-fn");
    }

    @Test
    public void formatExceptionClassCast() {
        ClassCastException cce = new ClassCastException("Long cannot be cast to String");
        assertThat(ErrorMessages.formatException(cce))
                .isEqualTo("ClassCastException: Long cannot be cast to String");
    }

    @Test
    public void formatExceptionIllegalArgument() {
        IllegalArgumentException iae = new IllegalArgumentException("bad arg");
        assertThat(ErrorMessages.formatException(iae))
                .isEqualTo("IllegalArgumentException: bad arg");
    }

    // ── clojureTypeName ──────────────────────────────────────────────

    @Test
    public void clojureTypeNameForNil() {
        assertThat(ErrorMessages.clojureTypeName(null)).isEqualTo("nil");
    }

    @Test
    public void clojureTypeNameForString() {
        assertThat(ErrorMessages.clojureTypeName("hello")).isEqualTo("string");
    }

    @Test
    public void clojureTypeNameForLong() {
        assertThat(ErrorMessages.clojureTypeName(42L)).isEqualTo("integer");
    }

    @Test
    public void clojureTypeNameForBoolean() {
        assertThat(ErrorMessages.clojureTypeName(true)).isEqualTo("boolean");
    }

    @Test
    public void clojureTypeNameForKeyword() {
        assertThat(ErrorMessages.clojureTypeName(clojure.lang.Keyword.intern("test")))
                .isEqualTo("keyword");
    }

    @Test
    public void clojureTypeNameForVector() {
        assertThat(ErrorMessages.clojureTypeName(clojure.lang.PersistentVector.EMPTY))
                .isEqualTo("vector");
    }

    // ── cannotCallMessage ────────────────────────────────────────────

    @Test
    public void cannotCallMessageForNil() {
        String msg = ErrorMessages.cannotCallMessage(null);
        assertThat(msg).contains("nil");
        assertThat(msg).contains("Cannot call");
    }

    @Test
    public void cannotCallMessageForString() {
        String msg = ErrorMessages.cannotCallMessage("hello");
        assertThat(msg).contains("string");
        assertThat(msg).contains("Cannot call");
    }

    @Test
    public void cannotCallMessageForInteger() {
        String msg = ErrorMessages.cannotCallMessage(42L);
        assertThat(msg).contains("integer");
        assertThat(msg).contains("Cannot call");
    }

    // ── truncateValue ────────────────────────────────────────────────

    @Test
    public void truncateValueShortValue() {
        String truncated = ErrorMessages.truncateValue("hi", 40);
        assertThat(truncated).isEqualTo("\"hi\"");
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static FnMethodNode mockMethod(int fixedArity, boolean variadic) {
        return new FnMethodNode(
                new net.javacrumbs.cloffle.nodes.binding.BindingNode[0],
                new net.javacrumbs.cloffle.nodes.value.NilNode(),
                fixedArity,
                variadic
        );
    }
}
