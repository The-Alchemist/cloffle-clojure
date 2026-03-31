package clojure.lang;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Reproducer for static method overload resolution with null arguments.
 *
 * <p>The bytecode path's {@code StaticMethod} operation passes class + method name
 * to {@link Reflector#invokeStaticMethod(Class, String, Object[])} at runtime. When
 * an argument is {@code null}, the reflector cannot distinguish between overloads by
 * argument type and may pick the wrong one.
 *
 * <p>The Clojure analyzer resolves the specific {@link java.lang.reflect.Method} at
 * compile time ({@code StaticMethodExpr.method}), but the bytecode converter discards
 * it, passing only class + method name to the {@code StaticMethod} operation.
 *
 * <p>Triggered at {@code core.clj:8208}: {@code (case s "true" true "false" false nil)}
 * — the {@code case} macro calls {@code (sorted-map)}, which is
 * {@code (PersistentTreeMap/create keyvals)} where {@code keyvals} is {@code nil}
 * (rest-arg of a zero-arg call). The analyzer resolves to {@code create(ISeq)} because
 * the rest param is typed, but at runtime the reflector picks {@code create(Map)}, NPE.
 */
public class StaticMethodOverloadTest {

    /**
     * Minimal reproducer: fn with rest arg calling an overloaded static method.
     * {@code PersistentTreeMap/create} has {@code create(Map)} and {@code create(ISeq)}.
     * The rest param {@code keyvals} is nil at runtime (zero-arg call).
     * The analyzer resolves to {@code create(ISeq)}, but the bytecode path
     * delegates to {@code Reflector} which picks {@code create(Map)} → NPE.
     */
    @Test
    public void sortedMapNoArgs() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "((fn* [& keyvals] (clojure.lang.PersistentTreeMap/create keyvals)))");
        assertNotNull(result);
        assertTrue("Expected PersistentTreeMap, got " + result.getClass(),
                result instanceof PersistentTreeMap);
        assertEquals(0, ((PersistentTreeMap) result).count());
    }

    /**
     * Same fn with actual args — both overloads would work, but verifies the path
     * doesn't break with non-null args.
     */
    @Test
    public void sortedMapWithArgs() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "((fn* [& keyvals] (clojure.lang.PersistentTreeMap/create keyvals)) 1 2 3 4)");
        assertNotNull(result);
        assertTrue("Expected PersistentTreeMap", result instanceof PersistentTreeMap);
        assertEquals(2, ((PersistentTreeMap) result).count());
    }

    /**
     * Two-arg overload: {@code create(Comparator, ISeq)}.
     * Comparator is non-null but keyvals may be nil.
     */
    @Test
    public void sortedMapByNoKeyvals() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "((fn* [comp & keyvals] (clojure.lang.PersistentTreeMap/create comp keyvals))"
                + " clojure.lang.RT/DEFAULT_COMPARATOR)");
        assertNotNull(result);
        assertTrue("Expected PersistentTreeMap", result instanceof PersistentTreeMap);
        assertEquals(0, ((PersistentTreeMap) result).count());
    }

    /**
     * The full pattern from core.clj: sorted-map fn result used by assoc.
     * Models: {@code (into (sorted-map) (zipmap ...))}
     */
    @Test
    public void sortedMapThenAssoc() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "(let* [f (fn* [& keyvals] (clojure.lang.PersistentTreeMap/create keyvals))"
                + "      m (f)]"
                + "  (.assoc m 1 2))");
        assertNotNull(result);
        assertTrue(result instanceof PersistentTreeMap);
        assertEquals(1, ((PersistentTreeMap) result).count());
    }

    /**
     * Verifies that AST path handles the same form correctly — parity check.
     */
    @Test
    public void sortedMapNoArgsAstParity() {
        Object result = BytecodeDslTestSupport.evalAst(
                "((fn* [& keyvals] (clojure.lang.PersistentTreeMap/create keyvals)))");
        assertNotNull(result);
        assertTrue("Expected PersistentTreeMap from AST path, got " + result.getClass(),
                result instanceof PersistentTreeMap);
        assertEquals(0, ((PersistentTreeMap) result).count());
    }
}
