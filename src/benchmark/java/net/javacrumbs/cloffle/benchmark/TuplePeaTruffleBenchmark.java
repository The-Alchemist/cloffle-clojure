package net.javacrumbs.cloffle.benchmark;

import net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea;
import net.javacrumbs.cloffle.benchmark.tuplepea.TuplePeaLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import com.oracle.truffle.api.CallTarget;

import java.util.concurrent.TimeUnit;

/**
 * Truffle-PE twin of {@link TuplePeaBenchmark}: same Tuple2 PEA shapes, compiled as
 * {@code pea} roots (no Cloffle). Branch/trip params are baked as {@code @CompilationFinal}
 * at {@link Setup} so each JMH {@code @Param} trial is a constant in the AST.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class TuplePeaTruffleBenchmark {

    private Context context;
    private int argA = 2;
    private int argB = 3;

    private CallTarget inMethod;
    private CallTarget viaPrivateHelper;
    private CallTarget viaOutOfLineHelper;
    private CallTarget viaTruffleBoundaryHelper;
    private CallTarget materialized;
    private CallTarget twoTuplesSumThenConsumeNth;
    private CallTarget twoTuplesSumThenConsumeNthOutOfLine;
    private CallTarget twoTuplesSumMaterialized;
    private CallTarget branchConstantConditionConsume;
    private CallTarget loopCreateConsumeNth;
    private CallTarget loopPairSumEachIteration;
    private CallTarget loopFoldSumCarry;
    private CallTarget loopMaterializeEveryIteration;
    private CallTarget forFoldSumCarryConstantLimit;

    @State(Scope.Benchmark)
    public static class BranchParam {
        @Param({"0", "1"})
        public int branch;

        CallTarget lazy;
        CallTarget pickOne;
        CallTarget sumOrSlots;
        CallTarget materializeOrConsume;
        CallTarget loopAlternating;
        CallTarget loopSamePath;

        @Setup(Level.Trial)
        public void setup(TuplePeaTruffleBenchmark parent) {
            lazy = parent.parse("branchLazyTupleCreate:" + branch);
            pickOne = parent.parse("branchPickOneTuple:" + branch);
            sumOrSlots = parent.parse("branchSumTupleOrDirectSlots:" + branch);
            materializeOrConsume = parent.parse("branchMaterializeOrConsumeInt:" + branch);
            loopAlternating = parent.parse("loopAlternatingBranchConsume:" + branch);
            loopSamePath = parent.parse("loopBranchParamSamePathEachIter:" + branch);
        }
    }

    @State(Scope.Benchmark)
    public static class TripParam {
        @Param({"8", "16", "32"})
        public int trips;

        CallTarget countdown;
        CallTarget accTarget;
        CallTarget fold;
        CallTarget pairSum;
        CallTarget forSame;
        CallTarget materialize;
        CallTarget earlyConsume;
        CallTarget earlyMaterialize;
        CallTarget earlyBeforeFold;

        @Setup(Level.Trial)
        public void setup(TuplePeaTruffleBenchmark parent) {
            countdown = parent.parse("whileCountdownConsume:" + trips);
            accTarget = parent.parse("whileAccBelowTargetConsume:" + trips);
            fold = parent.parse("whileFoldSumCarry:" + trips);
            pairSum = parent.parse("whilePairSumCountdown:" + trips);
            forSame = parent.parse("forSameTripsAsWhileConsume:" + trips);
            materialize = parent.parse("whileMaterializeCountdown:" + trips);
            earlyConsume = parent.parse("whileEarlyReturnLastIterConsume:" + trips);
            earlyMaterialize = parent.parse("whileEarlyReturnLastIterMaterialize:" + trips);
            earlyBeforeFold = parent.parse("whileEarlyReturnBeforeLastFold:" + trips);
        }
    }

    @State(Scope.Benchmark)
    public static class BranchAndTripParam {
        @Param({"0", "1"})
        public int branch;
        @Param({"8", "16", "32"})
        public int trips;

        CallTarget earlyOrFull;

        @Setup(Level.Trial)
        public void setup(TuplePeaTruffleBenchmark parent) {
            earlyOrFull = parent.parse("whileEarlyReturnOrFullLoop:" + branch + ":" + trips);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        context = Context.newBuilder(TuplePeaLanguage.ID)
                .allowAllAccess(true)
                .build();
        context.enter();
        inMethod = parse("inMethod");
        viaPrivateHelper = parse("viaPrivateHelper");
        viaOutOfLineHelper = parse("viaOutOfLineHelper");
        viaTruffleBoundaryHelper = parse("viaTruffleBoundaryHelper");
        materialized = parse("materialized");
        twoTuplesSumThenConsumeNth = parse("twoTuplesSumThenConsumeNth");
        twoTuplesSumThenConsumeNthOutOfLine = parse("twoTuplesSumThenConsumeNthOutOfLine");
        twoTuplesSumMaterialized = parse("twoTuplesSumMaterialized");
        branchConstantConditionConsume = parse("branchConstantConditionConsume");
        loopCreateConsumeNth = parse("loopCreateConsumeNth");
        loopPairSumEachIteration = parse("loopPairSumEachIteration");
        loopFoldSumCarry = parse("loopFoldSumCarry");
        loopMaterializeEveryIteration = parse("loopMaterializeEveryIteration");
        forFoldSumCarryConstantLimit = parse("forFoldSumCarryConstantLimit");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.leave();
            context.close();
            context = null;
        }
    }

    CallTarget parse(String program) {
        try {
            context.parse(Source.newBuilder(TuplePeaLanguage.ID, program, program + ".pea").build());
        } catch (Exception e) {
            throw new RuntimeException("pea parse failed: " + program, e);
        }
        return TuplePeaLanguage.takeLastParsed();
    }

    private int callInt(CallTarget target) {
        return (Integer) target.call(argA, argB);
    }

    @Benchmark
    public int inMethod() {
        return callInt(inMethod);
    }

    @Benchmark
    public int viaPrivateHelper() {
        return callInt(viaPrivateHelper);
    }

    @Benchmark
    public int viaOutOfLineHelper() {
        return callInt(viaOutOfLineHelper);
    }

    @Benchmark
    public int viaTruffleBoundaryHelper() {
        return callInt(viaTruffleBoundaryHelper);
    }

    @Benchmark
    public Object materialized() {
        return materialized.call(argA, argB);
    }

    @Benchmark
    public int twoTuplesSumThenConsumeNth() {
        return callInt(twoTuplesSumThenConsumeNth);
    }

    @Benchmark
    public int twoTuplesSumThenConsumeNthOutOfLine() {
        return callInt(twoTuplesSumThenConsumeNthOutOfLine);
    }

    @Benchmark
    public Object twoTuplesSumMaterialized() {
        return twoTuplesSumMaterialized.call(argA, argB);
    }

    @Benchmark
    public int branchLazyTupleCreate(BranchParam p) {
        return callInt(p.lazy);
    }

    @Benchmark
    public int branchPickOneTuple(BranchParam p) {
        return callInt(p.pickOne);
    }

    @Benchmark
    public int branchSumTupleOrDirectSlots(BranchParam p) {
        return callInt(p.sumOrSlots);
    }

    @Benchmark
    public int branchConstantConditionConsume() {
        return callInt(branchConstantConditionConsume);
    }

    @Benchmark
    public Object branchMaterializeOrConsumeInt(BranchParam p) {
        return p.materializeOrConsume.call(argA, argB);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopCreateConsumeNth() {
        return callInt(loopCreateConsumeNth);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopPairSumEachIteration() {
        return callInt(loopPairSumEachIteration);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopFoldSumCarry() {
        return callInt(loopFoldSumCarry);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopAlternatingBranchConsume(BranchParam p) {
        return callInt(p.loopAlternating);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopBranchParamSamePathEachIter(BranchParam p) {
        return callInt(p.loopSamePath);
    }

    @Benchmark
    @OperationsPerInvocation(TuplePea.LOOP_ITERS)
    public int loopMaterializeEveryIteration() {
        return callInt(loopMaterializeEveryIteration);
    }

    @Benchmark
    public int whileCountdownConsume(TripParam p) {
        return callInt(p.countdown);
    }

    @Benchmark
    public int whileAccBelowTargetConsume(TripParam p) {
        return callInt(p.accTarget);
    }

    @Benchmark
    public int whileFoldSumCarry(TripParam p) {
        return callInt(p.fold);
    }

    @Benchmark
    public int forFoldSumCarryConstantLimit() {
        return callInt(forFoldSumCarryConstantLimit);
    }

    @Benchmark
    public int whilePairSumCountdown(TripParam p) {
        return callInt(p.pairSum);
    }

    @Benchmark
    public int forSameTripsAsWhileConsume(TripParam p) {
        return callInt(p.forSame);
    }

    @Benchmark
    public int whileMaterializeCountdown(TripParam p) {
        return callInt(p.materialize);
    }

    @Benchmark
    public int whileEarlyReturnLastIterConsume(TripParam p) {
        return callInt(p.earlyConsume);
    }

    @Benchmark
    public Object whileEarlyReturnLastIterMaterialize(TripParam p) {
        return p.earlyMaterialize.call(argA, argB);
    }

    @Benchmark
    public int whileEarlyReturnBeforeLastFold(TripParam p) {
        return callInt(p.earlyBeforeFold);
    }

    @Benchmark
    public int whileEarlyReturnOrFullLoop(BranchAndTripParam p) {
        return callInt(p.earlyOrFull);
    }
}
