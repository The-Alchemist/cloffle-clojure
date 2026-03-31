package net.javacrumbs.cloffle.bytecode;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import clojure.lang.*;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;

public class BytecodeSerializationTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Initializing RT...");
        RT.init();

        System.out.println("Parsing a test expression...");
        // Let's create an expression that our ExprToBytecode supports:
        // (if true (clojure.core/+ 10 20) :false)
        String cljCode = "(if true (clojure.core/+ 10 20) :false)";
        Object form = clojure.lang.LispReader.read(new clojure.lang.LineNumberingPushbackReader(new StringReader(cljCode)), false, null, false, null);
        
        Object expanded = clojure.lang.Compiler.macroexpand(form);
        clojure.lang.Compiler.Expr expr = clojure.lang.Compiler.analyze(clojure.lang.Compiler.C.EVAL, expanded);
        
        System.out.println("Generating Bytecode...");
        Source source = Source.newBuilder("cloffle", cljCode, "test").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "testEval");
        
        CloffleBytecodeRootNode rootNode = nodes.getNode(0);
        System.out.println("Before serialization, running node...");
        // Polyglot context is needed to run nodes, but we don't have one here for bare testing. Let's see if it runs.
        // Wait! We can't just call getCallTarget().call() without a context if it relies on ClojureLanguage.
        // We'll see.
        try {
            Object result1 = rootNode.getCallTarget().call();
            System.out.println("Result 1: " + result1);
        } catch (Exception e) {
            System.out.println("Could not run node natively without context: " + e.getMessage());
        }

        System.out.println("Serializing...");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        nodes.serialize(out, new CloffleBytecodeSerializer());
        byte[] serialized = baos.toByteArray();
        System.out.println("Serialization successful. Size: " + serialized.length + " bytes");

        System.out.println("Deserializing...");
        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(serialized));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserializedNodes = CloffleBytecodeRootNodeGen.deserialize(
                null, // language
                ExprToBytecode.BYTECODE_CONFIG,
                supplier,
                new CloffleBytecodeDeserializer());

        CloffleBytecodeRootNode deserializedRoot = deserializedNodes.getNode(0);
        System.out.println("Deserialization successful! " + deserializedRoot.getName());
    }
}
