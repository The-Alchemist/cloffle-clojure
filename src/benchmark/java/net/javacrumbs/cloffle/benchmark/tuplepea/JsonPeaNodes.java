package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.Associative;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.RT;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;

/**
 * Generic object / {@link JsonParser} AST nodes on the same {@code pea} language as tuple PEA.
 */
public abstract class JsonPeaNodes {

    private JsonPeaNodes() {
    }

    public static final class ArgObj extends TuplePeaNodes.Expr {
        @CompilationFinal private final int index;

        public ArgObj(int index) {
            this.index = index;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return frame.getArguments()[index];
        }
    }

    public static final class ValAt extends TuplePeaNodes.Expr {
        @Child private TuplePeaNodes.Expr map;
        @CompilationFinal private final Keyword key;

        public ValAt(TuplePeaNodes.Expr map, Keyword key) {
            this.map = map;
            this.key = key;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return ((Associative) map.execute(frame)).valAt(key);
        }
    }

    public static final class Nth extends TuplePeaNodes.Expr {
        @Child private TuplePeaNodes.Expr coll;
        @CompilationFinal private final int index;

        public Nth(TuplePeaNodes.Expr coll, int index) {
            this.coll = coll;
            this.index = index;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object c = coll.execute(frame);
            if (c instanceof IPersistentVector v) {
                return v.nth(index);
            }
            return RT.nth(c, index);
        }
    }

    /**
     * {@code pe} uses {@link JsonParser#parseBytesPartialEvaluated}; otherwise the
     * public {@link JsonParser#parseBytes(byte[])} {@code @TruffleBoundary}.
     */
    public static final class ParseBytes extends TuplePeaNodes.Expr {
        @Child private TuplePeaNodes.Expr source;
        @CompilationFinal private final boolean pe;

        public ParseBytes(TuplePeaNodes.Expr source, boolean pe) {
            this.source = source;
            this.pe = pe;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            byte[] json = (byte[]) source.execute(frame);
            if (pe) {
                return JsonParser.parseBytesPartialEvaluated(json);
            }
            return JsonParser.parseBytes(json);
        }
    }

    /**
     * Typed project via {@link JsonTypedProjectPlan#projectPartialEvaluated}
     * ({@link JsonParser#projectTypedBytesPartialEvaluated}).
     */
    public static final class ProjectTyped extends TuplePeaNodes.Expr {
        @Child private TuplePeaNodes.Expr source;
        @CompilationFinal private final JsonTypedProjectPlan plan;

        public ProjectTyped(TuplePeaNodes.Expr source, JsonTypedProjectPlan plan) {
            this.source = source;
            this.plan = plan;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            byte[] json = (byte[]) source.execute(frame);
            return plan.projectPartialEvaluated(json);
        }
    }
}
