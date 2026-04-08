package net.javacrumbs.cloffle;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PolyglotErrorConsoleDisplayTest {

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
