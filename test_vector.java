import clojure.lang.*;
import java.io.*;
import net.javacrumbs.cloffle.bytecode.*;

public class test_vector {
    public static void main(String[] args) throws Exception {
        RT.init();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.class.getClassLoader(), RT.CURRENT_NS, RT.CURRENT_NS.deref()));
        try {
            Source source = com.oracle.truffle.api.source.Source.newBuilder("cloffle", "", "test").build();
            ExprToBytecode converter = new ExprToBytecode(null, source);
            
            Object form = RT.readString("(fn [x] (instance? clojure.lang.IPersistentVector x))");
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            
            com.oracle.truffle.api.bytecode.BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "test");
            CloffleBytecodeRootNode rootNode = nodes.getNode(0);
            
            IFn fn = (IFn) rootNode.getCallTarget().call();
            Object vec = RT.vector(1, 2, 3);
            Object res = fn.invoke(vec);
            System.out.println("Vector check result: " + res + " type: " + (res != null ? res.getClass() : "null"));
        } finally {
            Var.popThreadBindings();
        }
    }
}
