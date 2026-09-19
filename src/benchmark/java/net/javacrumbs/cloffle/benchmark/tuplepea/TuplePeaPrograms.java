package net.javacrumbs.cloffle.benchmark.tuplepea;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.RootNode;

import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.LOOP_ITERS;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.a;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.b;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.branch;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.branchObj;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.consume;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.consumeBoundary;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.consumeHelper;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.counted;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.ifLt;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.sum;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.tuple;
import static net.javacrumbs.cloffle.benchmark.tuplepea.TuplePea.whileLoop;

/**
 * Named programs matching {@code TuplePeaBenchmark} methods.
 * Source: {@code name}, {@code name:branch}, {@code name:trips}, or {@code name:branch:trips}.
 */
public final class TuplePeaPrograms {

    private TuplePeaPrograms() {
    }

    public static RootNode createRoot(TuplePeaLanguage language, String source) {
        Parsed parsed = Parsed.parse(source);
        TuplePeaNodes.Expr body = build(language, parsed);
        return new TuplePeaRootNode(language, parsed.name, body);
    }

    private static TuplePeaNodes.Expr build(TuplePeaLanguage language, Parsed p) {
        int br = p.branch;
        int trips = p.trips;
        return switch (p.name) {
            case "inMethod" -> consume(tuple(a(), b()));
            case "viaPrivateHelper" -> consumeHelper(tuple(a(), b()));
            case "viaOutOfLineHelper" -> new TuplePeaNodes.OutOfLineConsume(tuple(a(), b()), consumeTarget(language));
            case "viaTruffleBoundaryHelper" -> consumeBoundary(tuple(a(), b()));
            case "materialized" -> tuple(a(), b());
            case "twoTuplesSumThenConsumeNth" -> consume(sum(tuple(a(), b()), tuple(b(), a())));
            case "twoTuplesSumThenConsumeNthOutOfLine" ->
                    new TuplePeaNodes.OutOfLineConsume(sum(tuple(a(), b()), tuple(b(), a())), consumeTarget(language));
            case "twoTuplesSumMaterialized" -> sum(tuple(a(), b()), tuple(b(), a()));
            case "branchLazyTupleCreate" -> branch(br, consume(tuple(a(), b())), TuplePea.add(a(), b()));
            case "branchPickOneTuple" -> branch(br, consume(tuple(a(), b())), consume(tuple(b(), a())));
            case "branchSumTupleOrDirectSlots" -> branch(
                    br,
                    consume(sum(tuple(a(), b()), tuple(b(), a()))),
                    TuplePea.add(consume(tuple(a(), b())), consume(tuple(b(), a()))));
            case "branchConstantConditionConsume" -> ifLt(
                    a(),
                    b(),
                    consume(sum(tuple(a(), b()), tuple(b(), a()))),
                    TuplePea.add(consume(tuple(a(), b())), consume(tuple(b(), a()))));
            case "branchMaterializeOrConsumeInt" -> branchObj(
                    br,
                    sum(tuple(a(), b()), tuple(b(), a())),
                    consume(sum(tuple(a(), b()), tuple(b(), a()))));
            case "loopCreateConsumeNth" -> counted(TuplePeaNodes.CountedKind.CREATE_CONSUME, LOOP_ITERS, 0);
            case "loopPairSumEachIteration" -> counted(TuplePeaNodes.CountedKind.PAIR_SUM, LOOP_ITERS, 0);
            case "loopFoldSumCarry" -> counted(TuplePeaNodes.CountedKind.FOLD, LOOP_ITERS, 0);
            case "loopAlternatingBranchConsume" -> counted(TuplePeaNodes.CountedKind.ALTERNATING, LOOP_ITERS, br);
            case "loopBranchParamSamePathEachIter" -> counted(TuplePeaNodes.CountedKind.SAME_PATH, LOOP_ITERS, br);
            case "loopMaterializeEveryIteration" -> counted(TuplePeaNodes.CountedKind.MATERIALIZE, LOOP_ITERS, 0);
            case "whileCountdownConsume" -> whileLoop(TuplePeaNodes.WhileKind.COUNTDOWN, trips, 0);
            case "whileAccBelowTargetConsume" -> whileLoop(TuplePeaNodes.WhileKind.ACC_TARGET, trips, 0);
            case "whileFoldSumCarry" -> whileLoop(TuplePeaNodes.WhileKind.FOLD, trips, 0);
            case "forFoldSumCarryConstantLimit" -> counted(TuplePeaNodes.CountedKind.FOLD, LOOP_ITERS, 0);
            case "whilePairSumCountdown" -> whileLoop(TuplePeaNodes.WhileKind.PAIR_SUM, trips, 0);
            case "forSameTripsAsWhileConsume" -> whileLoop(TuplePeaNodes.WhileKind.FOR_COUNT, trips, 0);
            case "whileMaterializeCountdown" -> whileLoop(TuplePeaNodes.WhileKind.MATERIALIZE, trips, 0);
            case "whileEarlyReturnLastIterConsume" -> new TuplePeaNodes.EarlyReturnProfiled(trips, a(), b());
            case "whileEarlyReturnLastIterMaterialize" ->
                    whileLoop(TuplePeaNodes.WhileKind.EARLY_MATERIALIZE, trips, 0);
            case "whileEarlyReturnBeforeLastFold" -> whileLoop(TuplePeaNodes.WhileKind.EARLY_BEFORE_FOLD, trips, 0);
            case "whileEarlyReturnOrFullLoop" -> whileLoop(TuplePeaNodes.WhileKind.EARLY_OR_FULL, trips, br);
            default -> throw new IllegalArgumentException("Unknown tuple-pea program: " + p.name);
        };
    }

    private static CallTarget consumeTarget(TuplePeaLanguage language) {
        return new TuplePeaRootNode(language, "sumNth0And1", new TuplePeaNodes.ConsumeTupleArg()).getCallTarget();
    }

    private static final class Parsed {
        final String name;
        final int branch;
        final int trips;

        Parsed(String name, int branch, int trips) {
            this.name = name;
            this.branch = branch;
            this.trips = trips;
        }

        static Parsed parse(String source) {
            String[] parts = source.split(":");
            String name = parts[0];
            int branch = 0;
            int trips = LOOP_ITERS;
            if (parts.length == 2) {
                if (needsBranch(name) && !needsTrips(name)) {
                    branch = Integer.parseInt(parts[1]);
                } else {
                    trips = Integer.parseInt(parts[1]);
                }
            } else if (parts.length == 3) {
                branch = Integer.parseInt(parts[1]);
                trips = Integer.parseInt(parts[2]);
            }
            return new Parsed(name, branch, trips);
        }

        private static boolean needsBranch(String name) {
            return name.startsWith("branch")
                    || name.equals("loopAlternatingBranchConsume")
                    || name.equals("loopBranchParamSamePathEachIter")
                    || name.equals("whileEarlyReturnOrFullLoop");
        }

        private static boolean needsTrips(String name) {
            return name.startsWith("while") || name.equals("forSameTripsAsWhileConsume");
        }
    }
}
