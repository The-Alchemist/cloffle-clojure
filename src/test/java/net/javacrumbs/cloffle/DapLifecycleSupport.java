package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared lifecycle helpers for the DAP test classes.
 * <p>
 * These tests stand up roughly eighty short-lived DAP servers per run, which exposes two kinds of
 * cross-test interference that only appear once enough other classes share the JVM.
 */
final class DapLifecycleSupport {

    private static final Set<Integer> ISSUED_PORTS = ConcurrentHashMap.newKeySet();
    private static volatile boolean warmedUp;

    private DapLifecycleSupport() {
    }

    /**
     * Returns a port no other DAP test in this JVM has been handed.
     * <p>
     * Probing with {@code new ServerSocket(0)} and closing it leaves a window in which the OS can
     * hand the same ephemeral port to a later caller. A client socket left over from an earlier test
     * can then land on a freshly started server and attach a second {@code DebuggerSession}, which
     * competes with the session under test for stepping decisions.
     */
    static int allocatePort() throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            if (ISSUED_PORTS.add(port)) {
                return port;
            }
        }
        throw new IOException("no unused DAP port available after 64 attempts");
    }

    /**
     * Loads {@code clojure.core}, including the printing namespaces, outside any debugger session.
     * <p>
     * Otherwise the first debugged eval in the JVM compiles part of core lazily while a DAP client
     * is attached, letting session teardown race macroexpansion; that surfaces as an unrelated
     * compiler error such as "Unexpected error macroexpanding defn at clojure/core_print.clj".
     * Whether it happens depends on which tests ran first, so it only reproduces in the full suite.
     */
    static synchronized void warmUpRuntime() {
        if (warmedUp) {
            return;
        }
        try (Context context = CloffleEvalTestSupport.newContext("dap-warmup")) {
            context.eval(Source.newBuilder("cloffle",
                            "(do (pr-str {:a [1 2] :b \"s\"}) (with-out-str (println (range 3))) nil)",
                            "dap-warmup.clj")
                    .buildLiteral());
        }
        warmedUp = true;
    }
}
