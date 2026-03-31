import clojure.lang.*;
public class test_ast2 {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(fn* first [coll] (. clojure.lang.RT (first coll)))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        Compiler.FnExpr fn = (Compiler.FnExpr) expr;
        Compiler.FnMethod fm = (Compiler.FnMethod) RT.seq(fn.methods()).first();
        Compiler.BodyExpr be = (Compiler.BodyExpr) fm.body();
        for (ISeq s = RT.seq(be.exprs()); s != null; s = s.next()) {
            System.out.println("Inner AST: " + s.first().getClass().getName());
        }
    }
}
