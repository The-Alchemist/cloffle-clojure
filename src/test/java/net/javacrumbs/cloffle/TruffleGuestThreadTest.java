package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent send/send-off pools and {@code future} must run as Truffle guest threads so
 * {@link Clojure#getContext()} and {@code initializeThread} are available.
 */
public class TruffleGuestThreadTest {

    private static Context newContext() {
        return Context.newBuilder("cloffle").allowAllAccess(true).build();
    }

    @Test
    public void futureSeesCloffleContextOnSendOffPoolThread() {
        try (Context context = newContext()) {
            Value threadName = context.eval(
                    "cloffle",
                    "@(future (.getName (Thread/currentThread)))");
            assertTrue(threadName.asString().startsWith("clojure-agent-send-off-pool-"), "future must run on the send-off pool, got: " + threadName.asString());

            Value hasCtx = context.eval(
                    "cloffle",
                    "@(future (some? (net.javacrumbs.cloffle.Clojure/getContext)))");
            assertTrue(hasCtx.asBoolean(), "future body must see Clojure.getContext()");
        }
    }

    @Test
    public void agentSendAwaitUpdatesState() {
        try (Context context = newContext()) {
            Value result = context.eval(
                    "cloffle",
                    "(let [a (agent 0)] (send a inc) (await a) @a)");
            assertEquals(1L, result.asLong());
        }
    }

    @Test
    public void initializeThreadBindsNsOnGuestThread() {
        try (Context context = newContext()) {
            Value nsName = context.eval(
                    "cloffle",
                    "(let [p (promise)"
                            + " t (net.javacrumbs.cloffle.CloffleThreads/newThread"
                            + "     (fn [] (in-ns 'user) (deliver p (str *ns*)))"
                            + "     \"cloffle-init-ns-check\")]"
                            + "  (.start t)"
                            + "  (.join t)"
                            + "  @p)");
            assertEquals("user", nsName.asString());
        }
    }

    @Test
    public void contextClosesWithIdleCachedPoolWorker() {
        try (Context context = newContext()) {
            context.eval("cloffle", "@(future \"done\")");
        }
    }

    @Test
    public void sequentialContextsEachRunAFuture() {
        try (Context first = newContext()) {
            Value a = first.eval("cloffle", "@(future \"first\")");
            assertEquals("first", a.asString());
        }
        try (Context second = newContext()) {
            Value b = second.eval("cloffle", "@(future \"second\")");
            assertEquals("second", b.asString());
        }
    }
}
