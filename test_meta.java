import clojure.lang.*;
import java.io.*;
public class test_meta {
    public static void main(String[] args) throws Exception {
        RT.init();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.class.getClassLoader(), RT.CURRENT_NS, RT.CURRENT_NS.deref()));
        try {
            Object form = RT.readString("(def ^{:arglists '([obj])} meta (fn* meta [x] (if (instance? clojure.lang.IMeta x) (. x (meta)))))");
            Object expanded = Compiler.macroexpand(form);
            Compiler.analyze(Compiler.C.EVAL, expanded).eval();
            System.out.println("Meta parsed");
        } finally {
            Var.popThreadBindings();
        }
    }
}
