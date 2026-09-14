package net.javacrumbs.cloffle.bytecode;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.Keyword;
import clojure.lang.RT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code with-redefs} vs call-site rewrite under explicit {@code *compiler-options*} profiles
 * (bytecode eval path; bindings override {@code JAVA_TOOL_OPTIONS} direct linking).
 */
public class UncheckedMathDirectLinkingProfileTest {

    @BeforeAll
    static void init() {
        RT.init();
    }

    @Test
    @Tag("direct-linking-on")
    void redefinedBitXorIgnoredWhenCallSiteRewritten() throws Exception {
        assertEquals(
                3L,
                ((Number) BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                        "(with-redefs [bit-xor (fn [_ _] :redefined)] (bit-xor 1 2))"))
                        .longValue());
    }

    @Test
    @Tag("direct-linking-off")
    void redefinedPlusObservedWhenCheckedPathNotRewritten() throws Exception {
        assertEquals(
                Keyword.intern("redefined"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(
                        "(with-redefs [+ (fn [_ _] :redefined)] (+ 1 2))"));
    }

    @Test
    @Tag("direct-linking-off")
    void naryDivideObservesRedefWhenNotHostRewritten() throws Exception {
        assertEquals(
                Keyword.intern("redefined"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(
                        "(with-redefs [clojure.core// (fn [& _] :redefined)] (/ 8 2 2))"));
    }
}
