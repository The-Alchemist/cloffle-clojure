package clojure.lang;

import com.oracle.truffle.api.Assumption;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import static org.junit.Assert.*;

public class VarInliningTest {

    @Test
    public void testVarAssumptionLifecycle() {
        Namespace ns = Namespace.findOrCreate(Symbol.intern("test.var.lifecycle"));
        Var v = Var.intern(ns, Symbol.intern("my-var"), 100);

        Assumption a1 = v.getRootAssumption();
        assertNotNull(a1);
        assertTrue(a1.isValid());

        // Rebind root
        v.bindRoot(200);
        assertFalse("Old assumption must be invalidated on bindRoot", a1.isValid());
        Assumption a2 = v.getRootAssumption();
        assertNotSame(a1, a2);
        assertTrue(a2.isValid());

        // Swap root
        v.swapRoot(300);
        assertFalse("Old assumption must be invalidated on swapRoot", a2.isValid());
        Assumption a3 = v.getRootAssumption();
        assertTrue(a3.isValid());

        // Unbind root
        v.unbindRoot();
        assertFalse("Old assumption must be invalidated on unbindRoot", a3.isValid());
        Assumption a4 = v.getRootAssumption();
        assertTrue(a4.isValid());

        // Set dynamic
        v.setDynamic(true);
        assertFalse("Old assumption must be invalidated on setDynamic", a4.isValid());
        Assumption a5 = v.getRootAssumption();
        assertTrue(a5.isValid());
    }

    @Test
    public void testDirectStaticVarInvocationArities() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle",
                    "(ns test.var.invocations)\n" +
                    "(defn f0 [] 42)\n" +
                    "(defn f1 [a] (+ a 1))\n" +
                    "(defn f2 [a b] (+ a b))\n" +
                    "(defn f3 [a b c] (+ a b c))\n" +
                    "(defn f4 [a b c d] (+ a b c d))\n" +
                    "(defn f5 [a b c d e] (+ a b c d e))\n" +
                    "(defn caller0 [] (f0))\n" +
                    "(defn caller1 [x] (f1 x))\n" +
                    "(defn caller2 [x y] (f2 x y))\n" +
                    "(defn caller3 [x y z] (f3 x y z))\n" +
                    "(defn caller4 [w x y z] (f4 w x y z))\n" +
                    "(defn caller5 [v w x y z] (f5 v w x y z))\n"
            );

            Value r0 = context.eval("cloffle", "(test.var.invocations/caller0)");
            assertEquals(42L, r0.asLong());

            Value r1 = context.eval("cloffle", "(test.var.invocations/caller1 10)");
            assertEquals(11L, r1.asLong());

            Value r2 = context.eval("cloffle", "(test.var.invocations/caller2 10 20)");
            assertEquals(30L, r2.asLong());

            Value r3 = context.eval("cloffle", "(test.var.invocations/caller3 10 20 30)");
            assertEquals(60L, r3.asLong());

            Value r4 = context.eval("cloffle", "(test.var.invocations/caller4 10 20 30 40)");
            assertEquals(100L, r4.asLong());

            Value r5 = context.eval("cloffle", "(test.var.invocations/caller5 1 2 3 4 5)");
            assertEquals(15L, r5.asLong());
        }
    }

    @Test
    public void testVarRedefinitionInvalidatesInlinedCallSite() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle",
                    "(ns test.var.redef)\n" +
                    "(defn compute [x y] (+ x y))\n" +
                    "(defn run-caller [a b] (compute a b))\n"
            );

            Value res1 = context.eval("cloffle", "(test.var.redef/run-caller 3 4)");
            assertEquals(7L, res1.asLong());

            // Redefine compute in test.var.redef to multiply
            context.eval("cloffle",
                    "(ns test.var.redef)\n" +
                    "(ns-unmap 'test.var.redef 'compute)\n" +
                    "(defn compute [x y] (* x y))\n"
            );

            Value res2 = context.eval("cloffle", "(test.var.redef/run-caller 3 4)");
            assertEquals(12L, res2.asLong());
        }
    }

    @Test
    public void testReadVarConstValueAndRedefinition() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle",
                    "(ns test.var.readconst)\n" +
                    "(def my-const 12345)\n" +
                    "(defn get-const [] my-const)\n"
            );

            Value v1 = context.eval("cloffle", "(test.var.readconst/get-const)");
            assertEquals(12345L, v1.asLong());

            // Redefine constant in test.var.readconst
            context.eval("cloffle",
                    "(ns test.var.readconst)\n" +
                    "(def my-const 67890)\n"
            );

            Value v2 = context.eval("cloffle", "(test.var.readconst/get-const)");
            assertEquals(67890L, v2.asLong());
        }
    }

    @Test
    public void testDynamicVarsBypassAssumptionAndRespectBinding() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle",
                    "(ns test.var.dyn)\n" +
                    "(def ^:dynamic *dyn-val* 10)\n" +
                    "(defn read-dyn [] *dyn-val*)\n" +
                    "(defn with-dyn [v] (binding [*dyn-val* v] (read-dyn)))\n"
            );

            Value rootVal = context.eval("cloffle", "(test.var.dyn/read-dyn)");
            assertEquals(10L, rootVal.asLong());

            Value boundVal = context.eval("cloffle", "(test.var.dyn/with-dyn 99)");
            assertEquals(99L, boundVal.asLong());

            Value rootAgain = context.eval("cloffle", "(test.var.dyn/read-dyn)");
            assertEquals(10L, rootAgain.asLong());
        }
    }

    @Test
    public void testTupleAndShapeMapAcrossVarCalls() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            context.eval("cloffle",
                    "(ns test.var.pea)\n" +
                    "(defn make-pair [x y] [x y])\n" +
                    "(defn consume-pair [p] (let [[a b] p] (+ a b)))\n" +
                    "(defn pipeline [x y] (consume-pair (make-pair x y)))\n" +
                    "(defn make-record [id name] {:id id :name name})\n" +
                    "(defn process-record [r] (assoc r :active true))\n" +
                    "(defn record-pipeline [id name] (process-record (make-record id name)))\n"
            );

            Value r1 = context.eval("cloffle", "(test.var.pea/pipeline 100 200)");
            assertEquals(300L, r1.asLong());

            Value r2Id = context.eval("cloffle", "(:id (test.var.pea/record-pipeline 42 \"cloffle\"))");
            assertEquals(42L, r2Id.asLong());

            Value r2Name = context.eval("cloffle", "(:name (test.var.pea/record-pipeline 42 \"cloffle\"))");
            assertEquals("cloffle", r2Name.asString());

            Value r2Active = context.eval("cloffle", "(:active (test.var.pea/record-pipeline 42 \"cloffle\"))");
            assertTrue(r2Active.asBoolean());
        }
    }
}
