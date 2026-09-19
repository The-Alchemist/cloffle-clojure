package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.PersistentTuple;
import clojure.lang.PersistentTuple.PersistentTuple2;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

/**
 * Tiny typed AST for Tuple2 create / nth / sum / branch / loops.
 * {@link CompilationFinal} fields are the Truffle analogue of JMH {@code @Param} isolation.
 */
public abstract class TuplePeaNodes {

    private TuplePeaNodes() {
    }

    public abstract static class Expr extends Node {
        public abstract Object execute(VirtualFrame frame);
    }

    public abstract static class IntExpr extends Expr {
        @Override
        public final Object execute(VirtualFrame frame) {
            return executeInt(frame);
        }

        public abstract int executeInt(VirtualFrame frame);
    }

    public abstract static class TupleExpr extends Expr {
        @Override
        public final Object execute(VirtualFrame frame) {
            return executeTuple(frame);
        }

        public abstract PersistentTuple2 executeTuple(VirtualFrame frame);
    }

    static int unboxInt(Object value) {
        return value instanceof Integer i ? i : (int) value;
    }

    static int consume(PersistentTuple2 t) {
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    static PersistentTuple2 sum(PersistentTuple2 t1, PersistentTuple2 t2) {
        return PersistentTuple.create((Integer) t1.v0 + (Integer) t2.v0, (Integer) t1.v1 + (Integer) t2.v1);
    }

    public static final class Arg extends IntExpr {
        @CompilationFinal private final int index;

        public Arg(int index) {
            this.index = index;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return unboxInt(frame.getArguments()[index]);
        }
    }

    public static final class Lit extends IntExpr {
        @CompilationFinal private final int value;

        public Lit(int value) {
            this.value = value;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return value;
        }
    }

    public static final class Add extends IntExpr {
        @Child private IntExpr left;
        @Child private IntExpr right;

        public Add(IntExpr left, IntExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return left.executeInt(frame) + right.executeInt(frame);
        }
    }

    public static final class Tuple2 extends TupleExpr {
        @Child private IntExpr v0;
        @Child private IntExpr v1;

        public Tuple2(IntExpr v0, IntExpr v1) {
            this.v0 = v0;
            this.v1 = v1;
        }

        @Override
        public PersistentTuple2 executeTuple(VirtualFrame frame) {
            return PersistentTuple.create(v0.executeInt(frame), v1.executeInt(frame));
        }
    }

    public static final class Nth extends IntExpr {
        @Child private TupleExpr tuple;
        @CompilationFinal private final int index;

        public Nth(TupleExpr tuple, int index) {
            this.tuple = tuple;
            this.index = index;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            PersistentTuple2 t = tuple.executeTuple(frame);
            return (Integer) t.nth(index);
        }
    }

    public static final class Sum extends TupleExpr {
        @Child private TupleExpr left;
        @Child private TupleExpr right;

        public Sum(TupleExpr left, TupleExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public PersistentTuple2 executeTuple(VirtualFrame frame) {
            return sum(left.executeTuple(frame), right.executeTuple(frame));
        }
    }

    /** Private-helper analogue: Java method, Truffle PE inlines it. */
    public static final class ConsumeHelper extends IntExpr {
        @Child private TupleExpr tuple;

        public ConsumeHelper(TupleExpr tuple) {
            this.tuple = tuple;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return consume(tuple.executeTuple(frame));
        }
    }

    public static final class BoundaryConsume extends IntExpr {
        @Child private TupleExpr tuple;

        public BoundaryConsume(TupleExpr tuple) {
            this.tuple = tuple;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return boundary(tuple.executeTuple(frame));
        }

        @TruffleBoundary
        private static int boundary(PersistentTuple2 t) {
            return consume(t);
        }
    }

    public static final class ConstBranch extends IntExpr {
        @CompilationFinal private final int branch;
        @Child private IntExpr taken;
        @Child private IntExpr notTaken;

        public ConstBranch(int branch, IntExpr taken, IntExpr notTaken) {
            this.branch = branch;
            this.taken = taken;
            this.notTaken = notTaken;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            if (branch != 0) {
                return taken.executeInt(frame);
            }
            return notTaken.executeInt(frame);
        }
    }

    public static final class ConstBranchObj extends Expr {
        @CompilationFinal private final int branch;
        @Child private Expr taken;
        @Child private Expr notTaken;

        public ConstBranchObj(int branch, Expr taken, Expr notTaken) {
            this.branch = branch;
            this.taken = taken;
            this.notTaken = notTaken;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (branch != 0) {
                return taken.execute(frame);
            }
            return notTaken.execute(frame);
        }
    }

    public static final class IfLt extends IntExpr {
        @Child private IntExpr left;
        @Child private IntExpr right;
        @Child private IntExpr thenExpr;
        @Child private IntExpr elseExpr;

        public IfLt(IntExpr left, IntExpr right, IntExpr thenExpr, IntExpr elseExpr) {
            this.left = left;
            this.right = right;
            this.thenExpr = thenExpr;
            this.elseExpr = elseExpr;
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            if (left.executeInt(frame) < right.executeInt(frame)) {
                return thenExpr.executeInt(frame);
            }
            return elseExpr.executeInt(frame);
        }
    }

    public enum CountedKind {
        CREATE_CONSUME,
        PAIR_SUM,
        FOLD,
        ALTERNATING,
        SAME_PATH,
        MATERIALIZE
    }

    /** Fixed {@code LOOP_ITERS} loops; {@link ExplodeLoop} so PE can unroll. */
    public static final class CountedLoop extends IntExpr {
        @CompilationFinal private final CountedKind kind;
        @CompilationFinal private final int trips;
        @CompilationFinal private final int branch;
        @Child private IntExpr argA;
        @Child private IntExpr argB;

        public CountedLoop(CountedKind kind, int trips, int branch, IntExpr argA, IntExpr argB) {
            this.kind = kind;
            this.trips = trips;
            this.branch = branch;
            this.argA = argA;
            this.argB = argB;
        }

        @ExplodeLoop
        @Override
        public int executeInt(VirtualFrame frame) {
            int a = argA.executeInt(frame);
            int b = argB.executeInt(frame);
            return switch (kind) {
                case CREATE_CONSUME -> createConsume(a, b);
                case PAIR_SUM -> pairSum(a, b);
                case FOLD -> fold(trips);
                case ALTERNATING -> alternating(a, b);
                case SAME_PATH -> samePath(a, b);
                case MATERIALIZE -> materialize(a, b);
            };
        }

        @ExplodeLoop
        private int createConsume(int a, int b) {
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                acc += consume(PersistentTuple.create(a + i, b + i));
            }
            return acc;
        }

        @ExplodeLoop
        private int pairSum(int a, int b) {
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                PersistentTuple2 t1 = PersistentTuple.create(a + i, b);
                PersistentTuple2 t2 = PersistentTuple.create(b, a + i);
                acc += consume(sum(t1, t2));
            }
            return acc;
        }

        @ExplodeLoop
        private int fold(int limit) {
            PersistentTuple2 acc = PersistentTuple.create(0, 0);
            for (int i = 1; i <= limit; i++) {
                acc = sum(acc, PersistentTuple.create(i, i + 1));
            }
            return consume(acc);
        }

        @ExplodeLoop
        private int alternating(int a, int b) {
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                if ((i & 1) == branch) {
                    PersistentTuple2 t1 = PersistentTuple.create(a + i, b);
                    PersistentTuple2 t2 = PersistentTuple.create(b, a + i);
                    acc += consume(sum(t1, t2));
                } else {
                    acc += consume(PersistentTuple.create(a, b + i));
                }
            }
            return acc;
        }

        @ExplodeLoop
        private int samePath(int a, int b) {
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                PersistentTuple2 t1 = PersistentTuple.create(a + i, b);
                PersistentTuple2 t2 = PersistentTuple.create(b, a + i);
                if (branch != 0) {
                    acc += consume(sum(t1, t2));
                } else {
                    acc += consume(t1) + consume(t2);
                }
            }
            return acc;
        }

        @ExplodeLoop
        private int materialize(int a, int b) {
            PersistentTuple2[] sink = new PersistentTuple2[trips];
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                PersistentTuple2 t = PersistentTuple.create(a + i, b + i);
                sink[i] = t;
                escape(t);
                acc += consume(t);
            }
            for (int i = 0; i < trips; i++) {
                escape(sink[i]);
                acc += consume(sink[i]);
            }
            return acc;
        }
    }

    public enum WhileKind {
        COUNTDOWN,
        ACC_TARGET,
        FOLD,
        PAIR_SUM,
        FOR_COUNT,
        MATERIALIZE,
        EARLY_CONSUME,
        EARLY_MATERIALIZE,
        EARLY_BEFORE_FOLD,
        EARLY_OR_FULL
    }

    /**
     * Trip count is {@link CompilationFinal} (JMH {@code @Param} analogue).
     * No {@link ExplodeLoop} — PE must keep a loop header.
     */
    public static final class WhileLoop extends Expr {
        @CompilationFinal private final WhileKind kind;
        @CompilationFinal private final int trips;
        @CompilationFinal private final int branch;
        @Child private IntExpr argA;
        @Child private IntExpr argB;

        public WhileLoop(WhileKind kind, int trips, int branch, IntExpr argA, IntExpr argB) {
            this.kind = kind;
            this.trips = trips;
            this.branch = branch;
            this.argA = argA;
            this.argB = argB;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int a = argA.executeInt(frame);
            int b = argB.executeInt(frame);
            return switch (kind) {
                case EARLY_MATERIALIZE -> earlyMaterialize(a, b);
                default -> executeInt(a, b);
            };
        }

        private int executeInt(int a, int b) {
            return switch (kind) {
                case COUNTDOWN -> countdown(a, b);
                case ACC_TARGET -> accTarget(a, b);
                case FOLD -> fold();
                case PAIR_SUM -> pairSum(a, b);
                case FOR_COUNT -> forCount(a, b);
                case MATERIALIZE -> materialize(a, b);
                case EARLY_CONSUME -> earlyConsume(a, b);
                case EARLY_BEFORE_FOLD -> earlyBeforeFold();
                case EARLY_OR_FULL -> earlyOrFull(a, b);
                case EARLY_MATERIALIZE -> throw CompilerDirectives.shouldNotReachHere();
            };
        }

        private int countdown(int a, int b) {
            int acc = 0;
            int n = trips;
            while (n > 0) {
                acc += consume(PersistentTuple.create(a + n, b + n));
                n--;
            }
            return acc;
        }

        private int accTarget(int a, int b) {
            int target = trips * (a + b);
            int acc = 0;
            int i = 0;
            while (acc < target) {
                acc += consume(PersistentTuple.create(a + i, b + i));
                i++;
            }
            return acc;
        }

        private int fold() {
            PersistentTuple2 acc = PersistentTuple.create(0, 0);
            for (int i = 1; i <= trips; i++) {
                acc = sum(acc, PersistentTuple.create(i, i + 1));
            }
            return consume(acc);
        }

        private int pairSum(int a, int b) {
            int acc = 0;
            int n = trips;
            while (n > 0) {
                PersistentTuple2 t1 = PersistentTuple.create(a + n, b);
                PersistentTuple2 t2 = PersistentTuple.create(b, a + n);
                acc += consume(sum(t1, t2));
                n--;
            }
            return acc;
        }

        private int forCount(int a, int b) {
            int acc = 0;
            for (int i = 0; i < trips; i++) {
                acc += consume(PersistentTuple.create(a + i, b + i));
            }
            return acc;
        }

        private int materialize(int a, int b) {
            int acc = 0;
            int n = trips;
            while (n > 0) {
                PersistentTuple2 t = PersistentTuple.create(a + n, b + n);
                escape(t);
                acc += consume(t);
                n--;
            }
            return acc;
        }

        private int earlyConsume(int a, int b) {
            int acc = 0;
            int n = trips;
            while (n > 0) {
                PersistentTuple2 t = PersistentTuple.create(a + n, b + n);
                if (n == 1) {
                    return acc + consume(t);
                }
                acc += consume(t);
                n--;
            }
            return acc;
        }

        private PersistentTuple2 earlyMaterialize(int a, int b) {
            int n = trips;
            while (n > 0) {
                PersistentTuple2 t = PersistentTuple.create(a + n, b + n);
                if (n == 1) {
                    return t;
                }
                n--;
            }
            return PersistentTuple.create(a, b);
        }

        private int earlyBeforeFold() {
            PersistentTuple2 acc = PersistentTuple.create(0, 0);
            int n = trips;
            while (n > 0) {
                if (n == 1) {
                    return consume(acc);
                }
                acc = sum(acc, PersistentTuple.create(n, n + 1));
                n--;
            }
            return consume(acc);
        }

        private int earlyOrFull(int a, int b) {
            int acc = 0;
            int n = trips;
            while (n > 0) {
                PersistentTuple2 t = PersistentTuple.create(a + n, b + n);
                if (branch != 0 && n == 1) {
                    return acc + consume(t);
                }
                acc += consume(t);
                n--;
            }
            return acc;
        }
    }

    @TruffleBoundary
    static void escape(Object value) {
        if (value == null) {
            throw new IllegalStateException();
        }
    }

    public static final class OutOfLineConsume extends IntExpr {
        @Child private TupleExpr tuple;
        @Child private CachedCallConsumeNode call;

        public OutOfLineConsume(TupleExpr tuple, com.oracle.truffle.api.CallTarget target) {
            this.tuple = tuple;
            this.call = CachedCallConsumeNode.create(target);
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return call.executeCall(tuple.executeTuple(frame));
        }
    }

    public static final class EarlyReturnProfiled extends IntExpr {
        @Child private IntExpr argA;
        @Child private IntExpr argB;
        @Child private EarlyReturnProfiledNode loop;

        public EarlyReturnProfiled(int trips, IntExpr argA, IntExpr argB) {
            this.argA = argA;
            this.argB = argB;
            this.loop = EarlyReturnProfiledNode.create(trips);
        }

        @Override
        public int executeInt(VirtualFrame frame) {
            return loop.executeLoop(argA.executeInt(frame), argB.executeInt(frame));
        }
    }

    public static final class ConsumeTupleArg extends IntExpr {
        @Override
        public int executeInt(VirtualFrame frame) {
            return consume((PersistentTuple2) frame.getArguments()[0]);
        }
    }

    public static final class SumTupleArgs extends TupleExpr {
        @Override
        public PersistentTuple2 executeTuple(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            return sum((PersistentTuple2) args[0], (PersistentTuple2) args[1]);
        }
    }
}
