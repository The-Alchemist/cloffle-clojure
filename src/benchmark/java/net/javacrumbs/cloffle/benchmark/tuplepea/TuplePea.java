package net.javacrumbs.cloffle.benchmark.tuplepea;

/**
 * Java-side pseudo-DSL for {@link TuplePeaNodes}. Each factory returns a fresh subtree
 * (Truffle nodes cannot be shared across parents).
 */
public final class TuplePea {

    public static final int LOOP_ITERS = 16;

    private TuplePea() {
    }

    public static TuplePeaNodes.IntExpr a() {
        return new TuplePeaNodes.Arg(0);
    }

    public static TuplePeaNodes.IntExpr b() {
        return new TuplePeaNodes.Arg(1);
    }

    public static TuplePeaNodes.IntExpr lit(int value) {
        return new TuplePeaNodes.Lit(value);
    }

    public static TuplePeaNodes.IntExpr add(TuplePeaNodes.IntExpr left, TuplePeaNodes.IntExpr right) {
        return new TuplePeaNodes.Add(left, right);
    }

    public static TuplePeaNodes.TupleExpr tuple(TuplePeaNodes.IntExpr v0, TuplePeaNodes.IntExpr v1) {
        return new TuplePeaNodes.Tuple2(v0, v1);
    }

    public static TuplePeaNodes.IntExpr nth(TuplePeaNodes.TupleExpr tuple, int index) {
        return new TuplePeaNodes.Nth(tuple, index);
    }

    public static TuplePeaNodes.IntExpr consume(TuplePeaNodes.TupleExpr tuple) {
        return add(nth(tuple, 0), nth(tuple, 1));
    }

    public static TuplePeaNodes.IntExpr consumeHelper(TuplePeaNodes.TupleExpr tuple) {
        return new TuplePeaNodes.ConsumeHelper(tuple);
    }

    public static TuplePeaNodes.IntExpr consumeBoundary(TuplePeaNodes.TupleExpr tuple) {
        return new TuplePeaNodes.BoundaryConsume(tuple);
    }

    public static TuplePeaNodes.TupleExpr sum(TuplePeaNodes.TupleExpr left, TuplePeaNodes.TupleExpr right) {
        return new TuplePeaNodes.Sum(left, right);
    }

    public static TuplePeaNodes.IntExpr branch(int taken, TuplePeaNodes.IntExpr ifTaken, TuplePeaNodes.IntExpr ifNot) {
        return new TuplePeaNodes.ConstBranch(taken, ifTaken, ifNot);
    }

    public static TuplePeaNodes.Expr branchObj(int taken, TuplePeaNodes.Expr ifTaken, TuplePeaNodes.Expr ifNot) {
        return new TuplePeaNodes.ConstBranchObj(taken, ifTaken, ifNot);
    }

    public static TuplePeaNodes.IntExpr ifLt(
            TuplePeaNodes.IntExpr left,
            TuplePeaNodes.IntExpr right,
            TuplePeaNodes.IntExpr thenExpr,
            TuplePeaNodes.IntExpr elseExpr) {
        return new TuplePeaNodes.IfLt(left, right, thenExpr, elseExpr);
    }

    public static TuplePeaNodes.IntExpr counted(
            TuplePeaNodes.CountedKind kind, int trips, int branch) {
        return new TuplePeaNodes.CountedLoop(kind, trips, branch, a(), b());
    }

    public static TuplePeaNodes.Expr whileLoop(
            TuplePeaNodes.WhileKind kind, int trips, int branch) {
        return new TuplePeaNodes.WhileLoop(kind, trips, branch, a(), b());
    }
}
