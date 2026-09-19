package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.PersistentTuple.PersistentTuple2;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;

@GenerateInline(false)
@GenerateCached(true)
@ImportStatic(DirectCallNode.class)
public abstract class CachedCallConsumeNode extends Node {

    @CompilationFinal CallTarget target;

    public static CachedCallConsumeNode create(CallTarget target) {
        CachedCallConsumeNode node = CachedCallConsumeNodeGen.create();
        node.target = target;
        return node;
    }

    public abstract int executeCall(PersistentTuple2 t);

    @Specialization
    int doCall(PersistentTuple2 t, @Cached("create(target)") DirectCallNode call) {
        return TuplePeaNodes.unboxInt(call.call(t));
    }
}
