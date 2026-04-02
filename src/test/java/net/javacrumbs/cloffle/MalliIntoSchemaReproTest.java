package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * Regression for Malli / Reitit-style setups (no {@code malli} on the test classpath).
 * <p>
 * Malli uses {@code ^{:type ::into-schema}} on {@code reify} plus a {@code print-method} for that
 * keyword. While expanding {@code reify}, Cloffle must not call {@code form.toString()} on the raw
 * macro form: {@link clojure.lang.ASeq#toString} uses {@link clojure.lang.RT#printString}, which
 * dispatches {@code print-method} on {@code (:type (meta x))} and can invoke protocol methods on
 * a {@link clojure.lang.PersistentList}. {@link clojure.lang.RT#print} strips {@code :type}
 * while macro expansion is active, and {@link net.javacrumbs.cloffle.compiler.MacroExpander} strips
 * shallowly when synthesizing a fallback source label.
 * <p>
 * Use a <strong>fresh {@link Context}</strong> per Cloffle eval so thread finalization does not hit
 * unbalanced {@link clojure.lang.Var} bindings from macro expansion.
 */
public class MalliIntoSchemaReproTest {

    private static Object evalCloffle(Context ctx, String expr) {
        Value result = ctx.eval("cloffle", expr);
        if (result.isNull()) {
            return null;
        }
        if (result.isNumber()) {
            if (result.fitsInLong()) {
                return result.asLong();
            }
            if (result.fitsInDouble()) {
                return result.asDouble();
            }
        }
        if (result.isBoolean()) {
            return result.asBoolean();
        }
        if (result.isString()) {
            return result.asString();
        }
        return result.as(Object.class);
    }

    static final String ANNOTATED_REIFY_DO =
            "(do "
                    + "(defprotocol P (p [this])) "
                    + "(defmethod clojure.core/print-method :rx [v ^java.io.Writer w] "
                    + "  (.write w (str (p ^P v)))) "
                    + "(def r ^{:type :rx} (reify P (p [_] 7))) "
                    + "(pr-str r))";

    @Test(timeout = 120_000)
    public void annotatedReifyForm_worksUnderCloffleWhenPrintMethodRegistered() {
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value v = ctx.eval("cloffle", ANNOTATED_REIFY_DO);
            assertEquals("7", v.asString());
        }
    }

    static final String DEFN_BODY_ANNOTATED_REIFY_DO =
            "(do "
                    + "(defprotocol P2 (p2 [this])) "
                    + "(defmethod clojure.core/print-method :rx2 [v ^java.io.Writer w] "
                    + "  (.write w (str (p2 ^P2 v)))) "
                    + "(defn make-r2 [] ^{:type :rx2} (reify P2 (p2 [_] 7))) "
                    + "(pr-str (make-r2)))";

    @Test(timeout = 120_000)
    public void annotatedReifyInDefnBody_worksUnderCloffleWhenPrintMethodRegistered() {
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value v = ctx.eval("cloffle", DEFN_BODY_ANNOTATED_REIFY_DO);
            assertEquals("7", v.asString());
        }
    }

    /**
     * Runtime call of a protocol on a plain list should throw; {@code :type} does not make the
     * list satisfy the protocol.
     */
    @Test(timeout = 120_000)
    public void protocolOnList_throwsInCloffle() {
        String expr =
                "(do "
                        + "(defprotocol PitfallRtP (pitfall-rt-p [this])) "
                        + "(pitfall-rt-p (list 1)))";
        boolean cloffleThrew = false;
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            try {
                evalCloffle(ctx, expr);
            } catch (Throwable ignored) {
                cloffleThrew = true;
            }
        }
        assertThat(cloffleThrew).as("Cloffle should throw for protocol on list").isTrue();
    }

    /**
     * After Cloffle has loaded a full {@code clojure.core}, {@code *print-initialized*} is usually
     * true during macro expansion, so {@code str} on a type-tagged unevaluated form would otherwise
     * dispatch {@code print-method} and can throw if that method calls a protocol on a list. Cloffle
     * strips {@code :type} while expanding macros, so this expansion still completes.
     */
    @Test(timeout = 120_000)
    public void macroStrOnTaggedReify_protocolInPrintMethod_expansionCompletesInCloffle() {
        String expr =
                "(do "
                        + "(defprotocol PitfallMacroP2 (pitfall-macro-p2 [this])) "
                        + "(defmethod clojure.core/print-method :pitfall-macro-rx2 [v ^java.io.Writer w] "
                        + "  (.write w (str (pitfall-macro-p2 ^PitfallMacroP2 v)))) "
                        + "(defmacro pitfall-str-macro2 [x] (str x)) "
                        + "(pitfall-str-macro2 ^{:type :pitfall-macro-rx2} "
                        + "  (reify PitfallMacroP2 (pitfall-macro-p2 [_] 99))))";
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Object c = evalCloffle(ctx, expr);
            assertThat(c).isInstanceOf(String.class);
            assertThat(((String) c).toLowerCase()).contains("reify");
        }
    }
}
