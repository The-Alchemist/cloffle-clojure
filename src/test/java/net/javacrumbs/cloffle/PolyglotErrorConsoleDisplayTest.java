package net.javacrumbs.cloffle;

import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PolyglotErrorConsoleDisplayTest {

    @After
    public void clearVerboseProps() {
        System.clearProperty(PolyglotErrorConsoleDisplay.PROP_VERBOSE);
        System.clearProperty(PolyglotErrorConsoleDisplay.PROP_UNIFIED_DIAGNOSTICS);
    }

    @Test
    public void isErrorDisplayVerbose_readsSystemProperty() {
        System.setProperty(PolyglotErrorConsoleDisplay.PROP_VERBOSE, "true");
        assertThat(PolyglotErrorConsoleDisplay.isErrorDisplayVerbose()).isTrue();
    }

    @Test
    public void isUnifiedErrorDiagnostics_readsSystemProperty() {
        System.setProperty(PolyglotErrorConsoleDisplay.PROP_UNIFIED_DIAGNOSTICS, "1");
        assertThat(PolyglotErrorConsoleDisplay.isUnifiedErrorDiagnostics()).isTrue();
    }

    @Test
    public void stackRegionLocation_appendsLen() {
        PolyglotErrorLocations.Region r =
                new PolyglotErrorLocations.Region(
                        3, 16, 42, "t.clj:3:16 → (throw :x)", "t/inner", false, 3, 57);
        assertThat(PolyglotErrorConsoleDisplay.stackRegionLocation(r)).isEqualTo("t.clj:3:16 len=42");
    }

    @Test
    public void stackFrameSuffix_prefersFnName() {
        PolyglotErrorLocations.Region r =
                new PolyglotErrorLocations.Region(4, 16, 7, "t.clj:4:16 → (inner)", "t/outer", false, 4, 22);
        String s = PolyglotErrorConsoleDisplay.stackFrameSuffix(r);
        assertThat(s).contains("in t/outer");
    }

    @Test
    public void stackFrameSuffix_usesFormHintWhenNoFnName() {
        PolyglotErrorLocations.Region r =
                new PolyglotErrorLocations.Region(
                        3,
                        16,
                        50,
                        "t.clj:3:16 → (throw (Exception. \"boom\"))",
                        null,
                        true,
                        3,
                        65);
        String s = PolyglotErrorConsoleDisplay.stackFrameSuffix(r);
        assertThat(s).contains("(throw (Exception. \"boom\"))");
    }

    @Test
    public void formHintFromRegionLabel_truncatesLongSnippet() {
        String longForm = "(" + "x".repeat(60) + ")";
        String lab = "f.clj:1:1 → " + longForm;
        String h = PolyglotErrorConsoleDisplay.formHintFromRegionLabel(lab);
        assertThat(h).endsWith("...");
        assertThat(h.length()).isLessThanOrEqualTo(48);
    }
}
