import clojure.lang.*;
public class test_def_meta {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(def ^{:macro true} my-macro (fn* [] 1))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        System.out.println("Analyzed. Is macro? " + ((clojure.lang.Var)((clojure.lang.Compiler.DefExpr)expr).var).isMacro());
    }
}
