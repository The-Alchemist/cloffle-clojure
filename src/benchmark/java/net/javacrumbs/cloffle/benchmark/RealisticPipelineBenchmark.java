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

        context.eval("cloffle", ClojureClasspathResources.read("realistic-pipeline-benchmark/setup.clj"));

        ringAppFn = context.eval("cloffle", "ring-app");
        ringReqShape = context.eval("cloffle", "ring-req-shape");
        ringReqHash = context.eval("cloffle", "ring-req-hash");
        branchDomainFn = context.eval("cloffle", "process-order");
        orderReqShape = context.eval("cloffle", "order-shape");
        orderReqHash = context.eval("cloffle", "order-hash");
        loopAccumulatorFn = context.eval("cloffle", "aggregate-metrics");
        accReqShape = context.eval("cloffle", "acc-shape");
        accReqHash = context.eval("cloffle", "acc-hash");
        composedPipelineFn = context.eval("cloffle", "composed-workload");
        compReqShape = context.eval("cloffle", "comp-shape");
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
