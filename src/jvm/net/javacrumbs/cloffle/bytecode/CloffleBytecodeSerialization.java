package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * Single entry points for Cloffle Truffle bytecode wire format: {@link CloffleBytecodeSerializer} /
 * {@link CloffleBytecodeDeserializer} with {@link ExprToBytecode#BYTECODE_CONFIG}.
 * <p>
 * Used by {@link CloffleCoreBytecodeArchive} (and thus {@link net.javacrumbs.cloffle.CloffleBytecodeSerializerMain}
 * {@code dump-core}), and by JVM tests that round-trip bytecode without writing a CFBC archive.
 */
public final class CloffleBytecodeSerialization {

    private CloffleBytecodeSerialization() {}

    public static byte[] serializeRootNodes(BytecodeRootNodes<CloffleBytecodeRootNode> nodes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        return baos.toByteArray();
    }

    public static BytecodeRootNodes<CloffleBytecodeRootNode> deserializeRootNodes(byte[] wire) throws IOException {
        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(wire));
        return CloffleBytecodeRootNodeGen.deserialize(
                null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
    }
}
