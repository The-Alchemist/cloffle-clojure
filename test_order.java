import clojure.lang.*;
public class test_order {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(fn* ([] 1) ([x & ys] 3) ([x] 2))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        Compiler.FnExpr fn = (Compiler.FnExpr) expr;
        for (ISeq s = RT.seq(fn.methods()); s != null; s = s.next()) {
            Compiler.FnMethod fm = (Compiler.FnMethod) s.first();
            System.out.println("Arity: " + fm.reqParms().count() + ", variadic: " + (fm.restParm() != null));
        }
    }
}
