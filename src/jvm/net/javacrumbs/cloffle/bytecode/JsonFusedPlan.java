package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Compile-time description of a fused {@code (json/parse-string s)} + constant-path access, built by
 * {@link ExprToBytecodeJsonFuse} and held as a {@code @ConstantOperand} of
 * {@link CloffleBytecodeRootNode.JsonFusedExtract}.
 *
 * <p>Two views of the same expression:
 * <ul>
 *   <li>{@link #steps} — the access path flattened to {@link Keyword} (map lookup) and
 *       {@link Integer} (vector index) steps; what {@link JsonParser#extractString} walks.
 *   <li>{@link #layers} — the original accessor calls in order, used whenever the fast path declines
 *       so that {@code get-in} / {@code nth} / keyword-lookup semantics are the host ones rather
 *       than a reimplementation.
 * </ul>
 */
final class JsonFusedPlan {

    /** One accessor call peeled off the consumer chain. */
    sealed interface Layer {
        Object apply(Object target);

        /** The Var this layer bypasses, or null (keyword invocation goes through no Var). */
        Var var();

        Object applyRedefined(Object target, IndirectCallNode callNode);
    }

    record GetInLayer(Var var, IPersistentVector path) implements Layer {
        @Override
        public Object apply(Object target) {
            return RT.getIn(target, path);
        }

        @Override
        public Object applyRedefined(Object target, IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, target, path);
        }
    }

    record GetLayer(Var var, Keyword keyword) implements Layer {
        @Override
        public Object apply(Object target) {
            return RT.get(target, keyword);
        }

        @Override
        public Object applyRedefined(Object target, IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, target, keyword);
        }
    }

    /** {@code var} is null when analysis already rewrote {@code nth} to {@code RT.nth}. */
    record NthLayer(Var var, int index) implements Layer {
        @Override
        public Object apply(Object target) {
            return RT.nth(target, index);
        }

        @Override
        public Object applyRedefined(Object target, IndirectCallNode callNode) {
            return var == null
                    ? apply(target)
                    : BytecodeLowering.invokeRedefined(var, callNode, target, Integer.valueOf(index));
        }
    }

    /** {@code (:k target)} — a keyword invocation, which no redefinition can intercept. */
    record KeywordLayer(Keyword keyword) implements Layer {
        @Override
        public Var var() {
            return null;
        }

        @Override
        public Object apply(Object target) {
            return keyword.invoke(target);
        }

        @Override
        public Object applyRedefined(Object target, IndirectCallNode callNode) {
            return apply(target);
        }
    }

    final Var parseVar;
    /** True for {@code parse-bytes}: the source operand is UTF-8 bytes rather than a String. */
    final boolean bytesSource;
    final Object[] steps;
    final byte[][] keyUtf8;
    final Layer[] layers;

    JsonFusedPlan(Var parseVar, boolean bytesSource, Object[] steps, Layer[] layers) {
        this.parseVar = parseVar;
        this.bytesSource = bytesSource;
        this.steps = steps;
        this.layers = layers;
        this.keyUtf8 = new byte[steps.length][];
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] instanceof Keyword kw) {
                keyUtf8[i] = kw.sym.toString().getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Every Var the rewrite bypasses must still hold the root that sanctioned it; see
     * {@link BytecodeLowering#sanctionedRootAssumption}. A single unsanctioned Var yields
     * {@link Assumption#NEVER_VALID}, which makes the DSL decline the fused specialization.
     */
    Assumption[] loweringAssumptions() {
        List<Assumption> out = new java.util.ArrayList<>(layers.length + 1);
        out.add(BytecodeLowering.sanctionedRootAssumption(parseVar));
        for (Layer layer : layers) {
            Var var = layer.var();
            if (var != null) {
                out.add(BytecodeLowering.sanctionedRootAssumption(var));
            }
        }
        return out.toArray(new Assumption[0]);
    }

    /**
     * Scans the JSON once and materializes only the value at {@link #steps}. Falls back to a full
     * parse plus the host accessors when the scan declines (missing key, out-of-range index,
     * escaped key, or a structural type the step does not fit) or when the source is not the type
     * the parse function expects.
     */
    @TruffleBoundary
    Object extract(Object source) {
        if (bytesSource ? !(source instanceof byte[]) : !(source instanceof String)) {
            // Let the real parse function produce the real argument error.
            return applyLayers(((clojure.lang.IFn) parseVar.get()).invoke(source));
        }
        Object v = bytesSource
                ? JsonParser.extractBytes((byte[]) source, steps, keyUtf8)
                : JsonParser.extractString((String) source, steps, keyUtf8);
        return v == JsonParser.FALLBACK ? applyLayers(parse(source)) : v;
    }

    /** The unfused expression, run through whatever the Vars now hold. */
    @TruffleBoundary
    Object redefined(Object source, IndirectCallNode callNode) {
        Object cur = BytecodeLowering.invokeRedefined(parseVar, callNode, source);
        for (Layer layer : layers) {
            cur = layer.applyRedefined(cur, callNode);
        }
        return cur;
    }

    private Object parse(Object source) {
        return bytesSource
                ? JsonParser.parseBytes((byte[]) source)
                : JsonParser.parseString((String) source);
    }

    private Object applyLayers(Object parsed) {
        Object cur = parsed;
        for (Layer layer : layers) {
            cur = layer.apply(cur);
        }
        return cur;
    }
}
