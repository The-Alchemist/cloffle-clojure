package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.PersistentTuple;
import clojure.lang.PersistentTuple.PersistentTuple2;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

@GenerateInline(false)
@GenerateCached(true)
public abstract class EarlyReturnProfiledNode extends Node {

    @CompilationFinal int trips;

    public static EarlyReturnProfiledNode create(int trips) {
        EarlyReturnProfiledNode node = EarlyReturnProfiledNodeGen.create();
        node.trips = trips;
        return node;
    }

    public abstract int executeLoop(int a, int b);

    @Specialization
    int doLoop(int a, int b, @Cached InlinedBranchProfile early) {
        int acc = 0;
        int n = trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(a + n, b + n);
            if (n == 1) {
                early.enter(this);
                return acc + TuplePeaNodes.consume(t);
            }
            acc += TuplePeaNodes.consume(t);
            n--;
        }
        return acc;
    }
}
