package clojure.lang;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Frame identity for {@link Var#pushThreadBindings} / {@link Var#popThreadBindings}.
 * Lives in {@code clojure.lang} so it can compare {@link Var.Frame} objects without reflecting
 * into {@code dvals} from Clojure.
 */
public class VarThreadBindingTest {

    private static Var internDynamic(Object root) {
        Namespace ns = Namespace.findOrCreate(Symbol.intern(null, "test.var.frame." + System.nanoTime()));
        Var v = Var.intern(ns, Symbol.intern(null, "x"), root);
        v.setDynamic();
        return v;
    }

    @Test
    public void nestedPushPopRestoresPreviousFrameIdentity() {
        Var v = internDynamic("root");
        Object outer = Var.getThreadBindingFrame();
        Var.pushThreadBindings(RT.map(v, "a"));
        Object inner = Var.getThreadBindingFrame();
        assertNotSame(outer, inner);
        assertEquals("a", v.deref());
        Var.pushThreadBindings(RT.map(v, "b"));
        assertNotSame(inner, Var.getThreadBindingFrame());
        assertEquals("b", v.deref());
        Var.popThreadBindings();
        assertSame(inner, Var.getThreadBindingFrame());
        assertEquals("a", v.deref());
        Var.popThreadBindings();
        assertSame(outer, Var.getThreadBindingFrame());
        assertEquals("root", v.deref());
    }

    @Test
    public void unmatchedPopOnFreshThreadThrowsAndLeavesFrameUsable() throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicBoolean usable = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                Var.popThreadBindings();
                fail("expected unmatched pop to throw");
            } catch (IllegalStateException e) {
                err.set(e);
            }
            Var v = internDynamic("root");
            Var.pushThreadBindings(RT.map(v, "bound"));
            try {
                usable.set("bound".equals(v.deref()));
            } finally {
                Var.popThreadBindings();
            }
        });
        t.start();
        t.join();
        assertTrue(err.get() instanceof IllegalStateException);
        assertTrue("frame must remain usable after unmatched pop", usable.get());
    }
}
