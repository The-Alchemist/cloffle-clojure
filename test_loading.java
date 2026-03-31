import clojure.lang.*;
import java.io.*;
public class test_loading {
    public static void main(String[] args) throws Exception {
        RT.init();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.class.getClassLoader(), RT.CURRENT_NS, RT.CURRENT_NS.deref()));
        try {
            Object form = RT.readString("(clojure.core/with-loading-context)");
            Object expanded = Compiler.macroexpand(form);
            System.out.println(RT.printString(expanded));
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            System.out.println("Analyzed loading macro");
        } finally {
            Var.popThreadBindings();
        }
    }
}
