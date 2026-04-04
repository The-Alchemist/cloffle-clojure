package net.javacrumbs.cloffle.bytecode;

import clojure.asm.Type;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.MapEntry;
import clojure.lang.Namespace;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.RT;
import clojure.lang.Seqable;
import clojure.lang.Symbol;
import clojure.lang.DynamicClassLoader;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.serialization.BytecodeSerializer;
import com.oracle.truffle.api.source.Source;

import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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
    /**
     * Truffle {@link Source} (character sources only; see {@link Source#hasBytes()}).
     * Only the language and name are preserved; the source <em>text</em> is replaced with a single-space
     * placeholder to avoid duplicating the full file body in every per-form chunk (the replay side
     * provides its own compile-frame bindings and does not need the original text).
     */
    static final byte TYPE_SOURCE = 9;
    /**
     * Serialized form of {@link Boolean#FALSE} used as a sentinel for “no resolved overload” in
     * {@code StaticMethod} / {@code InstanceMethod} constant operands (Truffle cannot store
     * {@code null} there).
     */
    static final byte TYPE_FALSE_SENTINEL = 10;
    /** Wire form of {@link java.lang.reflect.Method} (declaring class name, method name, JVM descriptor). */
    static final byte TYPE_RESOLVED_METHOD = 11;
    /** Namespace-qualified {@link Var} via {@link Var#toSymbol()}. */
    static final byte TYPE_VAR = 12;
    /** {@link CloffleBytecodeRootNode.IdentityConstant} — serializes the wrapped value recursively. */
    static final byte TYPE_IDENTITY_CONSTANT = 13;
    /** Structural serialization for collection constants inside {@link #TYPE_IDENTITY_CONSTANT}. */
    static final byte TYPE_PERSISTENT_MAP = 14;
    static final byte TYPE_PERSISTENT_VECTOR = 15;
    static final byte TYPE_SEQ = 16;
    static final byte TYPE_PERSISTENT_SET = 17;
    static final byte TYPE_MAP_ENTRY = 18;
    static final byte TYPE_CHAR = 19;
    static final byte TYPE_INT = 20;
    /** {@link java.util.regex.Pattern} as {@link Pattern#pattern()} + {@link Pattern#flags()}. */
    static final byte TYPE_REGEX_PATTERN = 21;
    /** {@link Namespace} via {@link Namespace#getName()} (same wire shape as {@link #TYPE_SYMBOL}). */
    static final byte TYPE_NAMESPACE = 22;
    /**
     * JVM class defined in a {@link DynamicClassLoader} (e.g. {@code reify}, {@code fn}, deftype stubs). Carries
     * {@link DynamicClassLoader#findClassBytes(String)} so a fresh JVM can {@link DynamicClassLoader#defineClass}
     * before {@link Class#forName(String)} would succeed.
     */
    static final byte TYPE_CLASS_DCL = 23;

    /** {@link DataOutput#writeUTF(String)} is limited to 65535 bytes of modified UTF-8; large sources need this. */
    static void writeUtfLarge(DataOutput buffer, String s) throws IOException {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        buffer.writeInt(utf8.length);
        buffer.write(utf8);
    }

    @Override
    public void serialize(SerializerContext context, DataOutput buffer, Object object) throws IOException {
        if (object == null) {
            buffer.writeByte(TYPE_NULL);
        } else if (object == Boolean.FALSE) {
            buffer.writeByte(TYPE_FALSE_SENTINEL);
        } else if (object instanceof Method m) {
            buffer.writeByte(TYPE_RESOLVED_METHOD);
            buffer.writeUTF(m.getDeclaringClass().getName());
            buffer.writeUTF(m.getName());
            buffer.writeUTF(Type.getMethodDescriptor(m));
        } else if (object instanceof Var v) {
            buffer.writeByte(TYPE_VAR);
            Symbol q = v.toSymbol();
            if (q.getNamespace() != null) {
                buffer.writeBoolean(true);
                buffer.writeUTF(q.getNamespace());
            } else {
                buffer.writeBoolean(false);
            }
            buffer.writeUTF(q.getName());
        } else if (object instanceof CloffleBytecodeRootNode.IdentityConstant ic) {
            buffer.writeByte(TYPE_IDENTITY_CONSTANT);
            serialize(context, buffer, ic.value);
        } else if (object instanceof String s) {
            buffer.writeByte(TYPE_STRING);
            buffer.writeUTF(s);
        } else if (object instanceof IPersistentMap m && !(object instanceof MapEntry)) {
            buffer.writeByte(TYPE_PERSISTENT_MAP);
            buffer.writeInt(m.count());
            for (Object o : m) {
                MapEntry e = (MapEntry) o;
                serialize(context, buffer, e.getKey());
                serialize(context, buffer, e.getValue());
            }
        } else if (object instanceof IPersistentVector v) {
            buffer.writeByte(TYPE_PERSISTENT_VECTOR);
            int n = v.count();
            buffer.writeInt(n);
            for (int i = 0; i < n; i++) {
                serialize(context, buffer, v.nth(i));
            }
        } else if (object instanceof IPersistentSet set) {
            buffer.writeByte(TYPE_PERSISTENT_SET);
            buffer.writeInt(set.count());
            for (ISeq s = RT.seq(set); s != null; s = s.next()) {
                serialize(context, buffer, s.first());
            }
        } else if (object instanceof MapEntry me) {
            buffer.writeByte(TYPE_MAP_ENTRY);
            serialize(context, buffer, me.getKey());
            serialize(context, buffer, me.getValue());
        } else if (object instanceof CloffleBytecodeRootNode rootNode) {
            buffer.writeByte(TYPE_ROOT_NODE);
            context.writeBytecodeNode(buffer, rootNode);
        } else if (object instanceof Class<?> clazz) {
            String name = clazz.getName();
            byte[] dclBytes = DynamicClassLoader.findClassBytes(name);
            if (dclBytes != null) {
                buffer.writeByte(TYPE_CLASS_DCL);
                buffer.writeUTF(name);
                buffer.writeInt(dclBytes.length);
                buffer.write(dclBytes);
            } else {
                buffer.writeByte(TYPE_CLASS);
                buffer.writeUTF(name);
            }
        } else if (object instanceof Source src) {
            if (src.hasBytes()) {
                throw new AssertionError("Byte-based Source not supported for serialization: " + src);
            }
            buffer.writeByte(TYPE_SOURCE);
            buffer.writeUTF(src.getLanguage());
            buffer.writeUTF(src.getName());
            writeUtfLarge(buffer, " ");
        } else if (object instanceof Long l) {
            buffer.writeByte(TYPE_LONG);
            buffer.writeLong(l);
        } else if (object instanceof Integer i) {
            buffer.writeByte(TYPE_INT);
            buffer.writeInt(i);
        } else if (object instanceof Double d) {
            buffer.writeByte(TYPE_DOUBLE);
            buffer.writeDouble(d);
        } else if (object instanceof Boolean b) {
            buffer.writeByte(TYPE_BOOLEAN);
            buffer.writeBoolean(b);
        } else if (object instanceof Character c) {
            buffer.writeByte(TYPE_CHAR);
            buffer.writeChar(c);
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
        } else if (object instanceof Namespace ns) {
            buffer.writeByte(TYPE_NAMESPACE);
            Symbol name = ns.getName();
            if (name.getNamespace() != null) {
                buffer.writeBoolean(true);
                buffer.writeUTF(name.getNamespace());
            } else {
                buffer.writeBoolean(false);
            }
            buffer.writeUTF(name.getName());
        } else if (object instanceof Pattern p) {
            buffer.writeByte(TYPE_REGEX_PATTERN);
            buffer.writeUTF(p.pattern());
            buffer.writeInt(p.flags());
        } else if (object instanceof ISeq sHead) {
            buffer.writeByte(TYPE_SEQ);
            int n = RT.count(sHead);
            buffer.writeInt(n);
            for (ISeq s = sHead; s != null; s = s.next()) {
                serialize(context, buffer, s.first());
            }
        } else if (object instanceof Seqable seqable) {
            ISeq sHead = seqable.seq();
            buffer.writeByte(TYPE_SEQ);
            if (sHead == null) {
                buffer.writeInt(0);
            } else {
                int n = RT.count(sHead);
                buffer.writeInt(n);
                for (ISeq s = sHead; s != null; s = s.next()) {
                    serialize(context, buffer, s.first());
                }
            }
        } else {
            throw new AssertionError("Unsupported constant for serialization: " + object + " (" + object.getClass() + ")");
        }
    }
}
