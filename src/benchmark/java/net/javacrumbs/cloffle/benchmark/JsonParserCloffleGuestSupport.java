package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import org.graalvm.polyglot.Context;

/**
 * Opens a Cloffle context, evaluates {@code json-parser-benchmark/setup.clj}, and captures guest
 * IFns. Owned by suites that invoke Cloffle guests — not by pure Jackson baselines.
 */
final class JsonParserCloffleGuestSupport implements AutoCloseable {

    private Context context;

    void open() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
        context.eval("cloffle", ClojureClasspathResources.read("json-parser-benchmark/setup.clj"));
        context.eval("cloffle",
                ClojureClasspathResources.read("json-parser-benchmark/late-consume-setup.clj"));
        context.enter();
    }

    IFn guest(String name) {
        context.eval("cloffle",
                "(net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/captureGuestValue \""
                        + name + "\" " + name + ")");
        Object value = JsonParserBenchmarkBase.CAPTURED.get().remove(name);
        if (value == null) {
            throw new IllegalStateException("Guest value was not captured: " + name);
        }
        return (IFn) value;
    }

    @Override
    public void close() {
        if (context != null) {
            context.leave();
            context.close();
            context = null;
        }
        JsonParserBenchmarkBase.CAPTURED.remove();
    }
}
