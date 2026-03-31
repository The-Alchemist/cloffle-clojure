import clojure.lang.*;
import java.io.*;
public class test_arglists {
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
            Var vecVar = RT.var("clojure.core", "vec");
            System.out.println("vec arglists: " + RT.get(vecVar.meta(), RT.keyword(null, "arglists")));
            System.out.println("vec arglists type: " + RT.get(vecVar.meta(), RT.keyword(null, "arglists")).getClass());
            System.out.println("vec arglists first type: " + RT.seq(RT.get(vecVar.meta(), RT.keyword(null, "arglists"))).first().getClass());
        } finally {
            Var.popThreadBindings();
        }
    }
}
