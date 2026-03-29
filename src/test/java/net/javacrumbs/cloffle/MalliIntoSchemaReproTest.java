package net.javacrumbs.cloffle;

import mikera.cljutils.Clojure;
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
 * Additional tests compare JVM Clojure ({@link Clojure#eval}) with Cloffle on the broader pitfall:
 * treating {@code :type} on <em>code</em> as if it were a runtime tag. Use a <strong>fresh
 * {@link Context}</strong> per Cloffle eval so thread finalization does not hit unbalanced
 * {@link clojure.lang.Var} bindings from macro expansion.
 */
public class MalliIntoSchemaReproTest {

    private static Object clojureEval(String expr) {
        return Clojure.eval(expr);
    }

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

    private static Object normalize(Object val) {
        if (val instanceof Integer i) {
            return i.longValue();
        }
        if (val instanceof Short s) {
            return s.longValue();
        }
        if (val instanceof Byte b) {
            return b.longValue();
        }
        return val;
    }

    private static void assertBothEqual(String expr) {
        Object expected = normalize(clojureEval(expr));
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Object actual = normalize(evalCloffle(ctx, expr));
            assertThat(actual).as("Expression: %s", expr).isEqualTo(expected);
        }
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

    @Test(timeout = 120_000)
    public void annotatedReify_prStr_matchesJvmClojure() {
        assertBothEqual(ANNOTATED_REIFY_DO);
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

    @Test(timeout = 120_000)
    public void annotatedReifyInDefnBody_prStr_matchesJvmClojure() {
        assertBothEqual(DEFN_BODY_ANNOTATED_REIFY_DO);
    }

    /**
     * Non-print path: user multimethod that keys off {@code (:type (meta x))} like
     * {@code print-method} does. Both runtimes should agree on dispatch for in-memory structures.
     */
    @Test(timeout = 120_000)
    public void multimethodDispatchOnTypeMeta_matchesJvmClojure() {
        assertBothEqual(
                "(do "
                        + "(defmulti pitfall-meta-kind (fn [x] "
                        + "  (let [t (:type (meta x))] (if (keyword? t) t :plain)))) "
                        + "(defmethod pitfall-meta-kind :pit-tag [_] 10) "
                        + "(defmethod pitfall-meta-kind :plain [_] 1) "
                        + "(+ (pitfall-meta-kind ^{:type :pit-tag} (list 1)) "
                        + "   (pitfall-meta-kind (list 1))))");
    }

    /**
     * Runtime call of a protocol on a plain list throws on both; {@code :type} does not make the
     * list satisfy the protocol.
     */
    @Test(timeout = 120_000)
    public void protocolOnList_throwsOnBoth() {
        String expr =
                "(do "
                        + "(defprotocol PitfallRtP (pitfall-rt-p [this])) "
                        + "(pitfall-rt-p (list 1)))";
        boolean clojureThrew = false;
        try {
            clojureEval(expr);
        } catch (Throwable ignored) {
            clojureThrew = true;
        }
        assertThat(clojureThrew).as("JVM Clojure should throw for protocol on list").isTrue();

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
     * At runtime (not inside macro expansion), {@code pr-str} still dispatches {@code print-method}
     * on {@code :type}. Handler does not assume a protocol instance.
     */
    @Test(timeout = 120_000)
    public void prStrTaggedList_safePrintMethod_matchesJvmClojure() {
        assertBothEqual(
                "(do "
                        + "(defmethod clojure.core/print-method :pitfall-safe-tagged [v ^java.io.Writer w] "
                        + "  (.write w \"<tagged>\")) "
                        + "(pr-str ^{:type :pitfall-safe-tagged} (list 1 2)))");
    }

    /**
     * {@code (str x)} in a macro on {@code ^{:type …} (reify …)}: with {@code *print-initialized*}
     * false (typical for minimal {@code eval}), JVM and Cloffle both avoid {@code print-method}
     * dispatch and agree on the structural string. This anchors parity for the “pitfall” shape
     * without the printing stack.
     */
    @Test(timeout = 120_000)
    public void macroStrOnTaggedReify_matchesJvmClojure_whenPrintStackNotInitialized() {
        assertBothEqual(
                "(do "
                        + "(defprotocol PitfallMacroP (pitfall-macro-p [this])) "
                        + "(defmethod clojure.core/print-method :pitfall-macro-rx [v ^java.io.Writer w] "
                        + "  (.write w (str (pitfall-macro-p ^PitfallMacroP v)))) "
                        + "(defmacro pitfall-str-macro [x] (str x)) "
                        + "(pitfall-str-macro ^{:type :pitfall-macro-rx} "
                        + "  (reify PitfallMacroP (pitfall-macro-p [_] 99))))");
    }

    /**
     * After Cloffle has loaded a full {@code clojure.core}, {@code *print-initialized*} is usually
     * true during macro expansion, so {@code str} on a type-tagged unevaluated form would otherwise
     * dispatch {@code print-method} and can throw if that method calls a protocol on a list. Cloffle
     * strips {@code :type} while expanding macros, so this expansion still completes. (Stock Clojure
     * with the same flag true would throw here; we do not automate that JVM check because
     * {@code *print-initialized*} is not publicly bindable for tests.)
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
