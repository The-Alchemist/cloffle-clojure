import clojure.lang.*;
public class test_ast {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(fn* first [coll] (. clojure.lang.RT (first coll)))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        Compiler.FnExpr fn = (Compiler.FnExpr) expr;
        Compiler.FnMethod fm = (Compiler.FnMethod) RT.seq(fn.methods()).first();
        System.out.println("AST class: " + fm.body().getClass().getName());
    }
}
