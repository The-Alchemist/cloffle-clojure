package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import clojure.lang.PersistentVector;
import clojure.lang.PersistentList;
import clojure.lang.PersistentHashMap;
import clojure.lang.ISeq;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.MapEntry;
import com.oracle.truffle.api.bytecode.serialization.BytecodeSerializer;
import com.oracle.truffle.api.source.Source;

import java.io.DataOutput;
import java.io.IOException;

public class CloffleBytecodeSerializer implements BytecodeSerializer {
    
    static final byte TYPE_NULL = 0;
    static final byte TYPE_STRING = 1;
    static final byte TYPE_LONG = 2;
    static final byte TYPE_DOUBLE = 3;
    static final byte TYPE_BOOLEAN = 4;
    static final byte TYPE_SYMBOL = 5;
    static final byte TYPE_KEYWORD = 6;
    static final byte TYPE_ROOT_NODE = 7;
    static final byte TYPE_CLASS = 8;
    /** Truffle {@link Source} (character sources only; see {@link Source#hasBytes()}). */
    static final byte TYPE_SOURCE = 9;

    @Override
    public void serialize(SerializerContext context, DataOutput buffer, Object object) throws IOException {
        if (object == null) {
            buffer.writeByte(TYPE_NULL);
        } else if (object instanceof String s) {
            buffer.writeByte(TYPE_STRING);
            buffer.writeUTF(s);
        } else if (object instanceof Long l) {
            buffer.writeByte(TYPE_LONG);
            buffer.writeLong(l);
        } else if (object instanceof Integer i) {
            // Encode int as long for simplicity in constants for now, or add TYPE_INT
            buffer.writeByte(TYPE_LONG);
            buffer.writeLong((long) i);
        } else if (object instanceof Double d) {
            buffer.writeByte(TYPE_DOUBLE);
            buffer.writeDouble(d);
        } else if (object instanceof Boolean b) {
            buffer.writeByte(TYPE_BOOLEAN);
            buffer.writeBoolean(b);
        } else if (object instanceof Symbol sym) {
            buffer.writeByte(TYPE_SYMBOL);
            if (sym.getNamespace() != null) {
                buffer.writeBoolean(true);
                buffer.writeUTF(sym.getNamespace());
            } else {
                buffer.writeBoolean(false);
            }
            buffer.writeUTF(sym.getName());
        } else if (object instanceof Keyword kw) {
            buffer.writeByte(TYPE_KEYWORD);
            if (kw.getNamespace() != null) {
                buffer.writeBoolean(true);
                buffer.writeUTF(kw.getNamespace());
            } else {
                buffer.writeBoolean(false);
            }
            buffer.writeUTF(kw.getName());
        } else if (object instanceof CloffleBytecodeRootNode rootNode) {
            buffer.writeByte(TYPE_ROOT_NODE);
            context.writeBytecodeNode(buffer, rootNode);
        } else if (object instanceof Class<?> clazz) {
            buffer.writeByte(TYPE_CLASS);
            buffer.writeUTF(clazz.getName());
        } else if (object instanceof Source src) {
            if (src.hasBytes()) {
                throw new AssertionError("Byte-based Source not supported for serialization: " + src);
            }
            buffer.writeByte(TYPE_SOURCE);
            buffer.writeUTF(src.getLanguage());
            buffer.writeUTF(src.getName());
            buffer.writeUTF(src.getCharacters().toString());
        } else {
            throw new AssertionError("Unsupported constant for serialization: " + object + " (" + object.getClass() + ")");
        }
    }
}
