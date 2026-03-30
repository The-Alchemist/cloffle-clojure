package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import com.oracle.truffle.api.bytecode.serialization.BytecodeDeserializer;

import java.io.DataInput;
import java.io.IOException;

public class CloffleBytecodeDeserializer implements BytecodeDeserializer {
    
    @Override
    public Object deserialize(DeserializerContext context, DataInput buffer) throws IOException {
        byte typeCode = buffer.readByte();
        return switch (typeCode) {
            case CloffleBytecodeSerializer.TYPE_NULL -> null;
            case CloffleBytecodeSerializer.TYPE_STRING -> buffer.readUTF();
            case CloffleBytecodeSerializer.TYPE_LONG -> buffer.readLong();
            case CloffleBytecodeSerializer.TYPE_DOUBLE -> buffer.readDouble();
            case CloffleBytecodeSerializer.TYPE_BOOLEAN -> buffer.readBoolean();
            case CloffleBytecodeSerializer.TYPE_SYMBOL -> {
                boolean hasNs = buffer.readBoolean();
                String ns = hasNs ? buffer.readUTF() : null;
                String name = buffer.readUTF();
                yield Symbol.intern(ns, name);
            }
            case CloffleBytecodeSerializer.TYPE_KEYWORD -> {
                boolean hasNs = buffer.readBoolean();
                String ns = hasNs ? buffer.readUTF() : null;
                String name = buffer.readUTF();
                yield Keyword.intern(ns, name);
            }
            case CloffleBytecodeSerializer.TYPE_ROOT_NODE -> context.readBytecodeNode(buffer);
            case CloffleBytecodeSerializer.TYPE_CLASS -> {
                String className = buffer.readUTF();
                try {
                    yield Class.forName(className);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not deserialize class " + className, e);
                }
            }
            default -> throw new AssertionError("Unknown type code " + typeCode);
        };
    }
}
