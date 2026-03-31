import clojure.lang.*;
public class test_spec {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(defn vec ([coll] (if (vector? coll) (clojure.lang.LazilyPersistentVector/create coll) coll)))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        System.out.println("Analyzed!");
    }
}