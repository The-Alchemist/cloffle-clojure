package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.concurrent.TimeUnit;

/**
 * Realistic Clojure pipeline workloads evaluating Partial Escape Analysis (PEA)
 * and PersistentShapeMap / PersistentShapeMap16 behavior across multi-step functions,
 * branching state machines, loop/recur accumulators, and deep function call chains.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class RealisticPipelineBenchmark {

    private Context context;

    // 1. Ring HTTP middleware pipeline
    private Value ringAppFn;
    private Value ringReqShape;
    private Value ringReqHash;

    // 2. Complex branching domain logic (Phi merging across cond branches)
    private Value branchDomainFn;
    private Value orderReqShape;
    private Value orderReqHash;

    // 3. High-frequency loop/recur state accumulator
    private Value loopAccumulatorFn;
    private Value accReqShape;
    private Value accReqHash;

    // 4. Multi-stage functional composition chain
    private Value composedPipelineFn;
    private Value compReqShape;
    private Value compReqHash;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();

        // -------------------------------------------------------------
        // 1. In-Memory Ring HTTP Middleware Pipeline
        // -------------------------------------------------------------
        context.eval("cloffle",
                "(defn wrap-auth [handler]\n" +
                "  (fn [req]\n" +
                "    (if (= (get req :auth-token) \"secret-token\")\n" +
                "      (handler (assoc req :user-id 42 :authenticated? true :role :admin))\n" +
                "      {:status 401 :body \"Unauthorized\"})))\n" +
                "\n" +
                "(defn wrap-params [handler]\n" +
                "  (fn [req]\n" +
                "    (let [query (get req :query-string \"\")\n" +
                "          page (if (= query \"page=2\") 2 1)]\n" +
                "      (handler (assoc req :page page :limit 50)))))\n" +
                "\n" +
                "(defn wrap-enrich [handler]\n" +
                "  (fn [req]\n" +
                "    (handler (assoc req :tenant-id 1001 :trace-id \"trace-xyz\" :request-time 1700000000))))\n" +
                "\n" +
                "(defn api-handler [req]\n" +
                "  (if (get req :authenticated?)\n" +
                "    {:status 200\n" +
                "     :user-id (get req :user-id)\n" +
                "     :tenant-id (get req :tenant-id)\n" +
                "     :page (get req :page)\n" +
                "     :body \"success\"}\n" +
                "    {:status 403 :body \"Forbidden\"}))\n" +
                "\n" +
                "(def ring-app (-> api-handler wrap-enrich wrap-params wrap-auth))"
        );

        ringAppFn = context.eval("cloffle", "ring-app");

        // 8 keys: fits in PersistentShapeMap, enriches into PersistentShapeMap16 during middleware
        context.eval("cloffle",
                "(def ring-req-shape {:uri \"/api/items\" :method :get :auth-token \"secret-token\" " +
                ":query-string \"page=2\" :remote-addr \"127.0.0.1\" :host \"example.com\" " +
                ":scheme :https :content-type \"application/json\"})"
        );
        ringReqShape = context.eval("cloffle", "ring-req-shape");

        // 18 keys: forces PersistentHashMap (HAMT)
        context.eval("cloffle",
                "(def ring-req-hash {:uri \"/api/items\" :method :get :auth-token \"secret-token\" " +
                ":query-string \"page=2\" :remote-addr \"127.0.0.1\" :host \"example.com\" " +
                ":scheme :https :content-type \"application/json\" " +
                ":k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})"
        );
        ringReqHash = context.eval("cloffle", "ring-req-hash");

        // -------------------------------------------------------------
        // 2. Complex Branching Domain State Machine
        // -------------------------------------------------------------
        context.eval("cloffle",
                "(defn process-order [order]\n" +
                "  (let [status (get order :status)\n" +
                "        total (get order :total)]\n" +
                "    (cond\n" +
                "      (= status :pending)\n" +
                "      (if (> total 1000)\n" +
                "        (assoc order :status :manual-review :priority :high :audit-tag :large-order)\n" +
                "        (assoc order :status :auto-approved :priority :normal :audit-tag :standard))\n" +
                "      (= status :in-review)\n" +
                "      (assoc order :status :approved :approved-by :risk-engine :review-score 95)\n" +
                "      (= status :flagged)\n" +
                "      (assoc order :status :rejected :reason :fraud-suspicion :lock-account? true)\n" +
                "      :else\n" +
                "      (assoc order :status :unknown :error-code -1))))"
        );
        branchDomainFn = context.eval("cloffle", "process-order");

        context.eval("cloffle",
                "(def order-shape {:order-id 501 :customer-id 12 :status :pending :total 1500 " +
                ":currency :USD :items-count 3 :payment-method :credit-card})"
        );
        orderReqShape = context.eval("cloffle", "order-shape");

        context.eval("cloffle",
                "(def order-hash {:order-id 501 :customer-id 12 :status :pending :total 1500 " +
                ":currency :USD :items-count 3 :payment-method :credit-card " +
                ":k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})"
        );
        orderReqHash = context.eval("cloffle", "order-hash");

        // -------------------------------------------------------------
        // 3. Loop / Recur State Accumulator (1,000 iterations)
        // -------------------------------------------------------------
        context.eval("cloffle",
                "(defn aggregate-metrics [initial-state n]\n" +
                "  (loop [i 0\n" +
                "         state initial-state]\n" +
                "    (if (< i n)\n" +
                "      (recur (unchecked-inc i)\n" +
                "             (assoc state\n" +
                "                    :count (unchecked-inc (get state :count))\n" +
                "                    :sum (+ (get state :sum) i)\n" +
                "                    :last-val i))\n" +
                "      state)))"
        );
        loopAccumulatorFn = context.eval("cloffle", "aggregate-metrics");

        context.eval("cloffle",
                "(def acc-shape {:count 0 :sum 0 :last-val 0 :min 0 :max 1000 :active true :source :sensor-1 :window 60})"
        );
        accReqShape = context.eval("cloffle", "acc-shape");

        context.eval("cloffle",
                "(def acc-hash {:count 0 :sum 0 :last-val 0 :min 0 :max 1000 :active true :source :sensor-1 :window 60 " +
                ":k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})"
        );
        accReqHash = context.eval("cloffle", "acc-hash");

        // -------------------------------------------------------------
        // 4. Multi-Stage Functional Composition Chain
        // -------------------------------------------------------------
        context.eval("cloffle",
                "(defn step1-parse [m] (assoc m :parsed-val (unchecked-inc (get m :raw-val 0))))\n" +
                "(defn step2-validate [m] (if (> (get m :parsed-val) 0) (assoc m :valid? true) (assoc m :valid? false)))\n" +
                "(defn step3-enrich [m] (assoc m :enriched-tag :tier-gold :discount 0.15))\n" +
                "(defn step4-compute [m] (assoc m :final-score (* (get m :parsed-val) 10)))\n" +
                "(defn step5-finalize [m] (assoc m :completed? true :status :done))\n" +
                "\n" +
                "(defn composed-workload [m]\n" +
                "  (-> m\n" +
                "      step1-parse\n" +
                "      step2-validate\n" +
                "      step3-enrich\n" +
                "      step4-compute\n" +
                "      step5-finalize))"
        );
        composedPipelineFn = context.eval("cloffle", "composed-workload");

        context.eval("cloffle",
                "(def comp-shape {:raw-val 41 :user-id \"u102\" :tenant \"acme\" :region :us-east :priority :high :flag true})"
        );
        compReqShape = context.eval("cloffle", "comp-shape");

        context.eval("cloffle",
                "(def comp-hash {:raw-val 41 :user-id \"u102\" :tenant \"acme\" :region :us-east :priority :high :flag true " +
                ":k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})"
        );
        compReqHash = context.eval("cloffle", "comp-hash");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.close();
        }
    }

    // -------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------

    @Benchmark
    public Value ringPipelineShapeMap() {
        return ringAppFn.execute(ringReqShape);
    }

    @Benchmark
    public Value ringPipelineHashMap() {
        return ringAppFn.execute(ringReqHash);
    }

    @Benchmark
    public Value branchingDomainModelShapeMap() {
        return branchDomainFn.execute(orderReqShape);
    }

    @Benchmark
    public Value branchingDomainModelHashMap() {
        return branchDomainFn.execute(orderReqHash);
    }

    @Benchmark
    public Value loopAccumulatorShapeMap() {
        return loopAccumulatorFn.execute(accReqShape, 1000);
    }

    @Benchmark
    public Value loopAccumulatorHashMap() {
        return loopAccumulatorFn.execute(accReqHash, 1000);
    }

    @Benchmark
    public Value composedPipelineShapeMap() {
        return composedPipelineFn.execute(compReqShape);
    }

    @Benchmark
    public Value composedPipelineHashMap() {
        return composedPipelineFn.execute(compReqHash);
    }
}
