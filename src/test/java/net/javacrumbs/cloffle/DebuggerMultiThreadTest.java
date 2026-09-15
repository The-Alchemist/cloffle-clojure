package net.javacrumbs.cloffle;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line breakpoints and scopes on Truffle guest worker threads ({@code future} / {@code agent}).
 * Unlike {@link DebuggerTest}, {@link SuspendedCallback} may run on pool threads; handlers must
 * be thread-safe and use latches for assertions after {@code context.eval()}.
 */
public class DebuggerMultiThreadTest {

    private Engine engine;
    private Context context;
    private Debugger debugger;

    @BeforeEach
    public void setUp() {
        engine = Engine.create();
        context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .build();
        CloffleEvalTestSupport.bindFreshNamespace(context, "mt-debugger");
        debugger = Debugger.find(engine);
    }

    @AfterEach
    public void tearDown() {
        if (context != null) {
            context.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    private static Source src(String name, String code) {
        return Source.newBuilder("cloffle", code, name).buildLiteral();
    }

    /**
     * Thread-safe handler queue for suspensions that may occur on agent/future pool threads.
     */
    static final class ConcurrentCallback implements SuspendedCallback {
        private final Queue<Consumer<SuspendedEvent>> handlers = new ConcurrentLinkedQueue<>();

        void add(Consumer<SuspendedEvent> handler) {
            handlers.add(handler);
        }

        @Override
        public void onSuspend(SuspendedEvent event) {
            Consumer<SuspendedEvent> h = handlers.poll();
            if (h != null) {
                h.accept(event);
            } else {
                event.prepareContinue();
            }
        }
    }

    @Test
    public void breakpointFiresOnFutureWorkerThread() throws Exception {
        Source code = src("future_bp.clj",
                "(defn worker [x]\n" +
                "  (+ x 1))\n" +
                "@(future (worker 41))\n");

        ConcurrentCallback cb = new ConcurrentCallback();
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<String> suspendThread = new AtomicReference<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                suspendThread.set(Thread.currentThread().getName());
                assertEquals(2, event.getSourceSection().getStartLine());
                event.prepareContinue();
                stopped.countDown();
            });

            Value result = context.eval(code);

            assertTrue(stopped.await(30, TimeUnit.SECONDS), "breakpoint should suspend on worker");
            assertEquals(42L, result.asLong());
            assertNotNull(suspendThread.get());
            assertTrue(suspendThread.get().startsWith("clojure-agent-send-off-pool-"), "suspend should occur on send-off pool, got: " + suspendThread.get());
        }
    }

    @Test
    public void breakpointFiresOnAgentSendPoolThread() throws Exception {
        Source code = src("agent_bp.clj",
                "(defn agent-inc [s]\n" +
                "  (+ s 1))\n" +
                "(let [a (agent 0)]\n" +
                "  (send a agent-inc)\n" +
                "  (await a)\n" +
                "  @a)\n");

        ConcurrentCallback cb = new ConcurrentCallback();
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<String> suspendThread = new AtomicReference<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                suspendThread.set(Thread.currentThread().getName());
                assertEquals(2, event.getSourceSection().getStartLine());
                event.prepareContinue();
                stopped.countDown();
            });

            Value result = context.eval(code);

            assertTrue(stopped.await(30, TimeUnit.SECONDS), "breakpoint should suspend on worker");
            assertEquals(1L, result.asLong());
            assertNotNull(suspendThread.get());
            assertTrue(suspendThread.get().startsWith("clojure-agent-send-pool-"), "suspend should occur on send pool, got: " + suspendThread.get());
        }
    }

    @Test
    public void twoFuturesBothHitSameBreakpoint() throws Exception {
        Source code = src("dual_future_bp.clj",
                "(defn worker [x]\n" +
                "  (+ x 10))\n" +
                "(+ @(future (worker 1)) @(future (worker 2)))\n");

        ConcurrentCallback cb = new ConcurrentCallback();
        AtomicInteger hitCount = new AtomicInteger();
        List<Integer> hitLines = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(event -> {
            hitCount.incrementAndGet();
            hitLines.add(event.getSourceSection().getStartLine());
            event.prepareContinue();
        })) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            Value result = context.eval(code);

            assertEquals(23L, result.asLong());
            assertTrue(hitCount.get() >= 2, "each future should hit the worker body line at least once");
            assertTrue(hitLines.stream().allMatch(line -> line == 2));
        }
    }

    @Test
    public void breakpointOnWorkerExposesLocals() throws Exception {
        try (Engine dbgEngine = Engine.create()) {
            Context dbgContext = CloffleEvalTestSupport.newDebuggerContext(dbgEngine, "mt-scope");
            Debugger dbg = Debugger.find(dbgEngine);
            try {
                Source code = src("worker_scope.clj",
                        "(defn f [n]\n" +
                        "  (let [m (inc n)]\n" +
                        "    (+ m n)))\n" +
                        "@(future (f 10))\n");

                ConcurrentCallback cb = new ConcurrentCallback();
                CountDownLatch stopped = new CountDownLatch(1);
                long[] nVal = {Long.MIN_VALUE};
                long[] mVal = {Long.MIN_VALUE};

                try (DebuggerSession session = dbg.startSession(cb)) {
                    session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                    cb.add(event -> {
                        DebugStackFrame frame = event.getTopStackFrame();
                        DebugScope scope = frame.getScope();
                        assertNotNull(scope, "scope should be available on worker");
                        DebugValue n = scope.getDeclaredValue("n");
                        DebugValue m = scope.getDeclaredValue("m");
                        assertNotNull(n, "parameter n should be in scope");
                        assertNotNull(m, "let binding m should be in scope");
                        assertTrue(n.isNumber() || n.fitsInLong(), "n should be numeric");
                        assertTrue(m.isNumber() || m.fitsInLong(), "m should be numeric");
                        nVal[0] = n.asLong();
                        mVal[0] = m.asLong();
                        event.prepareContinue();
                        stopped.countDown();
                    });

                    Value result = dbgContext.eval(code);

                    assertTrue(stopped.await(30, TimeUnit.SECONDS));
                    assertEquals(21L, result.asLong());
                    assertEquals(10L, nVal[0]);
                    assertEquals(11L, mVal[0]);
                }
            } finally {
                dbgContext.close();
                dbgEngine.close();
            }
        }
    }

    @Test
    public void workerScopeSymbolLocalIsStringNotExecutable() throws Exception {
        try (Engine dbgEngine = Engine.create()) {
            Context dbgContext = CloffleEvalTestSupport.newDebuggerContext(dbgEngine, "mt-symbol");
            Debugger dbg = Debugger.find(dbgEngine);
            try {
                Source code = src("worker_sym.clj",
                        "(defn f []\n" +
                        "  (let [s 'my.ns/sym]\n" +
                        "    (identity s)))\n" +
                        "@(future (f))\n");

                ConcurrentCallback cb = new ConcurrentCallback();
                CountDownLatch stopped = new CountDownLatch(1);
                boolean[] ok = {false};

                try (DebuggerSession session = dbg.startSession(cb)) {
                    session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                    cb.add(event -> {
                        try {
                            DebugScope scope = event.getTopStackFrame().getScope();
                            assertNotNull(scope);
                            DebugValue sVal = scope.getDeclaredValue("s");
                            assertNotNull(sVal, "symbol local s should be in scope");
                            assertTrue(sVal.isString());
                            assertEquals("my.ns/sym", sVal.asString());
                            ok[0] = true;
                        } catch (DebugException e) {
                            throw new AssertionError("debug value access failed", e);
                        }
                        event.prepareContinue();
                        stopped.countDown();
                    });

                    dbgContext.eval(code);

                    assertTrue(stopped.await(30, TimeUnit.SECONDS));
                    assertTrue(ok[0], "symbol local interop checks should run");
                }
            } finally {
                dbgContext.close();
                dbgEngine.close();
            }
        }
    }

    @Test
    public void mainThreadAndWorkerBothHitBreakpoints() throws Exception {
        Source code = src("main_and_worker_bp.clj",
                "(defn on-worker [x]\n" +
                "  (+ x 1))\n" +
                "(defn main-add []\n" +
                "  (+ 1 2))\n" +
                "(let [f (future (on-worker 9))]\n" +
                "  (main-add)\n" +
                "  @f)\n");

        ConcurrentCallback cb = new ConcurrentCallback();
        AtomicInteger hits = new AtomicInteger();
        List<String> threadNames = new ArrayList<>();
        List<Integer> hitLines = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(event -> {
            hits.incrementAndGet();
            hitLines.add(event.getSourceSection().getStartLine());
            threadNames.add(Thread.currentThread().getName());
            event.prepareContinue();
        })) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

            Value result = context.eval(code);

            assertEquals(10L, result.asLong());
            assertTrue(hits.get() >= 2, "both breakpoint lines should fire at least once each");
            assertTrue(hitLines.contains(2), "worker body line should break");
            assertTrue(hitLines.contains(4), "main thread line should break");
            boolean poolThread = false;
            for (String name : threadNames) {
                if (name.startsWith("clojure-agent-send-off-pool-")
                        || name.startsWith("clojure-agent-send-pool-")) {
                    poolThread = true;
                }
            }
            assertTrue(poolThread, "at least one stop should be on an agent pool thread");
        }
    }
}
