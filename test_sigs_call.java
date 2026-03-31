import clojure.lang.*;
import java.io.*;
public class test_sigs_call {
    public static void main(String[] args) throws Exception {
        RT.init();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.class.getClassLoader(), RT.CURRENT_NS, RT.CURRENT_NS.deref()));
        try {
            File file = new File("core_mini.clj");
            LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new FileReader(file));
            Object EOF = new Object();
            for (Object form = LispReader.read(reader, false, EOF, false, null); form != EOF; form = LispReader.read(reader, false, EOF, false, null)) {
                Object expanded = Compiler.macroexpand(form);
                Compiler.analyze(Compiler.C.EVAL, expanded).eval();
            }
            Var sigsVar = RT.var("clojure.core", "sigs");
            System.out.println("Invoking sigs...");
            Object result = sigsVar.invoke(RT.list(RT.vector(Symbol.intern("x"))));
            System.out.println("Result: " + result);
        } finally {
            Var.popThreadBindings();
        }
    }
}
