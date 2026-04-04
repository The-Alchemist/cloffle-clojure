package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import net.javacrumbs.cloffle.Clojure;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Child JVM entry for {@link DclClassBytecodeSerializationTest#reifyRoundTripsInFreshJvm}: proves DCL class bytes
 * in the wire can be deserialized without the class having been loaded in this process beforehand.
 */
public final class DclClassRoundTripChildMain {

    private DclClassRoundTripChildMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: DclClassRoundTripChildMain <wire.bin>");
            System.exit(2);
        }
        RT.init();
        byte[] wire = Files.readAllBytes(Path.of(args[0]));
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.makeClassLoader()));
        ClassLoader dcl = (ClassLoader) Compiler.LOADER.deref();
        Thread.currentThread().setContextClassLoader(dcl);
        Clojure.pushEvalThreadBindings();
        try {
            BytecodeRootNodes<CloffleBytecodeRootNode> nodes = CloffleBytecodeSerialization.deserializeRootNodes(wire);
            Object o = nodes.getNode(0).getCallTarget().call();
            if (!(o instanceof clojure.lang.IDeref)) {
                System.err.println("expected IDeref, got " + o);
                System.exit(3);
            }
            clojure.lang.IDeref d = (clojure.lang.IDeref) o;
            Object v = d.deref();
            if (!(v instanceof Number) || ((Number) v).longValue() != 42L) {
                System.err.println("expected 42 deref, got " + v);
                System.exit(4);
            }
        } finally {
            Var.popThreadBindings();
            Var.popThreadBindings();
        }
        System.exit(0);
    }
}
