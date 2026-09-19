package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;

/**
 * Generic {@code pea} factories: object args, map {@code valAt}, vector {@code nth},
 * and {@link clojure.lang.JsonParser} parse / typed project.
 */
public final class Pea {

    private Pea() {
    }

    public static TuplePeaNodes.Expr arg(int index) {
        return new JsonPeaNodes.ArgObj(index);
    }

    public static TuplePeaNodes.Expr valAt(TuplePeaNodes.Expr map, Keyword key) {
        return new JsonPeaNodes.ValAt(map, key);
    }

    public static TuplePeaNodes.Expr nth(TuplePeaNodes.Expr coll, int index) {
        return new JsonPeaNodes.Nth(coll, index);
    }

    /** {@link clojure.lang.JsonParser#parseBytesPartialEvaluated} (visible to Truffle PE). */
    public static TuplePeaNodes.Expr parseBytes(TuplePeaNodes.Expr json) {
        return new JsonPeaNodes.ParseBytes(json, true);
    }

    /** {@link clojure.lang.JsonParser#parseBytes(byte[])} {@code @TruffleBoundary}. */
    public static TuplePeaNodes.Expr parseBytesBoundary(TuplePeaNodes.Expr json) {
        return new JsonPeaNodes.ParseBytes(json, false);
    }

    public static TuplePeaNodes.Expr projectTyped(TuplePeaNodes.Expr json, JsonTypedProjectPlan plan) {
        return new JsonPeaNodes.ProjectTyped(json, plan);
    }
}
