package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/**
 * Helpers for Polyglot integration tests. Clojure namespaces are JVM-global, so tests that
 * {@code def} into {@code user} must use a fresh namespace per context (or per test) to avoid
 * "already refers to" warnings when the suite reuses the same JVM.
 */
public final class CloffleEvalTestSupport {

    private CloffleEvalTestSupport() {
    }

    public static String freshNs(String prefix) {
        return "test." + prefix + "." + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** Switch {@code context} to a new empty namespace before evaluating guest code. */
    public static void bindFreshNamespace(Context context, String prefix) {
        String ns = freshNs(prefix);
        context.eval(Source.newBuilder("cloffle", "(do (in-ns '" + ns + ") nil)", "ns-setup.clj").buildLiteral());
    }

    public static Context newContext(org.graalvm.polyglot.Engine engine, String nsPrefix) {
        Context context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .build();
        bindFreshNamespace(context, nsPrefix);
        return context;
    }

    public static Context newContext(String nsPrefix) {
        Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
        bindFreshNamespace(context, nsPrefix);
        return context;
    }
}
