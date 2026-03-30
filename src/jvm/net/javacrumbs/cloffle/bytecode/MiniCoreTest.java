package net.javacrumbs.cloffle.bytecode;

import java.io.*;
import clojure.lang.*;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

public class MiniCoreTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Initializing RT...");
        RT.init();

        File file = new File("core_mini.clj");
        LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new FileReader(file));
        
        Source source = Source.newBuilder("cloffle", "", "core_mini.clj").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        
        Object EOF = new Object();
        int formCount = 0;
        
        for (Object form = LispReader.read(reader, false, EOF, false, null); 
             form != EOF; 
             form = LispReader.read(reader, false, EOF, false, null)) {
            
            formCount++;
            System.out.println("Processing form " + formCount + ": " + RT.printString(form));
            
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            
            BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "form_" + formCount);
            CloffleBytecodeRootNode rootNode = nodes.getNode(0);
            
            try {
                Object result = rootNode.getCallTarget().call();
                System.out.println("Result: " + result);
            } catch (Exception e) {
                System.out.println("Execution failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
