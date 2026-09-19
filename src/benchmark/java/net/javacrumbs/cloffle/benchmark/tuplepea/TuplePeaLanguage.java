package net.javacrumbs.cloffle.benchmark.tuplepea;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Tiny host Truffle language (no Cloffle). Named programs are Java-built ASTs:
 * tuple PEA in {@link TuplePeaPrograms}, {@link clojure.lang.JsonParser} in
 * {@link JsonPeaPrograms}. JMH calls the raw {@link CallTarget}.
 */
public final class TuplePeaLanguage extends TruffleLanguage<TuplePeaLanguage.LangContext> {

    public static final String ID = "pea";

    private static final ThreadLocal<CallTarget> LAST_PARSED = new ThreadLocal<>();

    public static final class LangContext {
    }

    @Override
    protected LangContext createContext(Env env) {
        return new LangContext();
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String src = request.getSource().getCharacters().toString().trim();
        RootNode root = src.startsWith("json:")
                ? JsonPeaPrograms.createRoot(this, src.substring("json:".length()))
                : TuplePeaPrograms.createRoot(this, src);
        CallTarget target = root.getCallTarget();
        LAST_PARSED.set(target);
        return target;
    }

    /** Install a Java-built AST (generic programs that are not in the string catalogs). */
    public CallTarget compile(String name, TuplePeaNodes.Expr body) {
        CallTarget target = new TuplePeaRootNode(this, name, body).getCallTarget();
        LAST_PARSED.set(target);
        return target;
    }

    /** Polyglot {@code parse} wraps the target; JMH uses this raw {@link CallTarget}. */
    public static CallTarget takeLastParsed() {
        CallTarget target = LAST_PARSED.get();
        LAST_PARSED.remove();
        if (target == null) {
            throw new IllegalStateException("No pea CallTarget from parse");
        }
        return target;
    }
}
