package net.javacrumbs.cloffle.ast;

import clojure.lang.Compiler.LocalBinding;
import clojure.lang.Compiler.MethodParamExpr;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * {@link ExprToNode} keys locals by (enclosing {@link clojure.lang.Compiler.FnExpr}, idx, name, isArg).
 * {@code NEXT_LOCAL_NUM} resets per method, so the triple can repeat across methods without being the
 * same slot (defn primitive param vs {@code :inline} param of the same name).
 */
public class ExprToNodeLocalBindingSlotTest {

    @BeforeClass
    public static void initRt() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    @Test
    public void sameIdxNameIsArgInDifferentFnExprScopesGetDistinctSlots() {
        Symbol num = Symbol.intern(null, "num");
        LocalBinding hintedDouble =
                new LocalBinding(0, num, null, new MethodParamExpr(double.class), true, null);
        LocalBinding unhinted =
                new LocalBinding(0, num, null, null, true, null);

        ExprToNode conv = new ExprToNode(null, null);
        Object scopeA = new Object();
        Object scopeB = new Object();
        conv.pushTestFnExprScope(scopeA);
        int slotDouble = conv.findOrAddSlot(hintedDouble, FrameSlotKind.Double);
        conv.popTestFnExprScope();
        conv.pushTestFnExprScope(scopeB);
        int slotObject = conv.findOrAddSlot(unhinted, FrameSlotKind.Object);
        conv.popTestFnExprScope();

        assertNotEquals(slotDouble, slotObject);
    }

    @Test
    public void duplicateLocalBindingTriplesUnderSameScopeStillShareOneSlot() {
        Symbol num = Symbol.intern(null, "num");
        LocalBinding b1 = new LocalBinding(0, num, null, new MethodParamExpr(double.class), true, null);
        LocalBinding b2 = new LocalBinding(0, num, null, new MethodParamExpr(double.class), true, null);

        ExprToNode conv = new ExprToNode(null, null);
        Object scope = new Object();
        conv.pushTestFnExprScope(scope);
        int s1 = conv.findOrAddSlot(b1, FrameSlotKind.Double);
        int s2 = conv.findOrAddSlot(b2, FrameSlotKind.Double);
        conv.popTestFnExprScope();

        assertEquals(s1, s2);
    }
}
