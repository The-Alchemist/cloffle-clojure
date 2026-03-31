import clojure.lang.*;
import java.io.*;
public class test_sigs {
    public static void main(String[] args) throws Exception {
        RT.init();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, new DynamicClassLoader(RT.class.getClassLoader()), RT.CURRENT_NS, RT.CURRENT_NS.deref()));
        try {
            File file = new File("core_mini.clj");
            LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new FileReader(file));
            Object EOF = new Object();
            for (int i = 0; i < 48; i++) {
                Object form = LispReader.read(reader, false, EOF, false, null);
                Object expanded = Compiler.macroexpand(form);
                Compiler.analyze(Compiler.C.EVAL, expanded).eval();
            }
            Var sigsVar = RT.var("clojure.core", "sigs");
            System.out.println("Invoking sigs...");
            Object result = sigsVar.invoke(RT.list(RT.vector(Symbol.intern("coll")), Symbol.intern("foo")));
            System.out.println("Result: " + result);
        } finally {
            Var.popThreadBindings();
        }
    }
}
