package net.javacrumbs.cloffle.bytecode;

import clojure.asm.Type;
import clojure.lang.Compiler;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.MapEntry;
import clojure.lang.Namespace;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.serialization.BytecodeDeserializer;
import com.oracle.truffle.api.source.Source;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

public class CloffleBytecodeDeserializer implements BytecodeDeserializer {

    /**
     * One {@link DynamicClassLoader} per {@link CloffleBytecodeSerialization#deserializeRootNodes(byte[])} call so
     * multiple {@link CloffleBytecodeSerializer#TYPE_CLASS_DCL} constants share definitions.
     */
    private static final ThreadLocal<DynamicClassLoader> DESERIALIZE_SESSION_DCL = new ThreadLocal<>();

    /**
     * When set, {@code TYPE_SOURCE} deserialization uses this {@link Source} instead of building one
     * from the placeholder content stored in the wire format. This allows bytecode cache replay to
     * attach the real source text (loaded from the classpath) without bloating the archive.
     */
    private static final ThreadLocal<Source> SOURCE_OVERRIDE = new ThreadLocal<>();

    static void beginDeserializeSession() {
        DESERIALIZE_SESSION_DCL.remove();
    }

    static void endDeserializeSession() {
        DESERIALIZE_SESSION_DCL.remove();
    }

    static void setSourceOverride(Source source) {
        SOURCE_OVERRIDE.set(source);
    }

    static void clearSourceOverride() {
        SOURCE_OVERRIDE.remove();
    }

    static String readUtfLarge(DataInput buffer) throws IOException {
        int len = buffer.readInt();
        if (len < 0) {
            throw new IOException("invalid UTF-8 chunk length: " + len);
        }
        byte[] utf8 = new byte[len];
        buffer.readFully(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    private static ClassLoader loaderForResolve() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : CloffleBytecodeDeserializer.class.getClassLoader();
    }

    private static Class<?> loadClass(String binaryName) throws ClassNotFoundException {
        return Class.forName(binaryName, false, loaderForResolve());
    }

    /**
     * Restores a class that was defined in a {@link DynamicClassLoader} in the dumping JVM by reusing embedded
     * bytecode in the wire format.
     */
    private static Class<?> loadClassFromDclBytes(String className, byte[] bytes) throws ClassNotFoundException {
        DynamicClassLoader dcl = dynamicLoaderForDefine();
        try {
            return Class.forName(className, false, dcl);
        } catch (ClassNotFoundException e) {
            return dcl.defineClass(className, bytes, null);
        }
    }

    /** Prefer session cache, then CCL or {@link Compiler#LOADER}, else a new {@link DynamicClassLoader}. */
    private static DynamicClassLoader dynamicLoaderForDefine() {
        DynamicClassLoader cached = DESERIALIZE_SESSION_DCL.get();
        if (cached != null) {
            return cached;
        }
        ClassLoader cl = loaderForResolve();
        if (cl instanceof DynamicClassLoader d) {
            DESERIALIZE_SESSION_DCL.set(d);
            return d;
        }
        if (Compiler.LOADER.isBound() && Compiler.LOADER.deref() instanceof DynamicClassLoader d) {
            DESERIALIZE_SESSION_DCL.set(d);
            return d;
        }
        DynamicClassLoader created = new DynamicClassLoader(cl);
        DESERIALIZE_SESSION_DCL.set(created);
        return created;
    }

    /**
     * Maps an ASM {@link Type} (field or single-parameter descriptor) to a {@link Class}, using the
     * same loader as {@link #loadClass(String)}.
     */
    private static Class<?> asmTypeToClass(Type t) throws ClassNotFoundException {
        switch (t.getSort()) {
            case Type.VOID:
                return void.class;
            case Type.BOOLEAN:
                return boolean.class;
            case Type.CHAR:
                return char.class;
            case Type.BYTE:
                return byte.class;
            case Type.SHORT:
                return short.class;
            case Type.INT:
                return int.class;
            case Type.FLOAT:
                return float.class;
            case Type.LONG:
                return long.class;
            case Type.DOUBLE:
                return double.class;
            case Type.ARRAY:
                // Descriptor uses internal names (slashes); Class.forName expects binary names (dots), e.g.
                // [Ljava/lang/Object; → [Ljava.lang.Object;
                return Class.forName(t.getDescriptor().replace('/', '.'), false, loaderForResolve());
            case Type.OBJECT:
                return loadClass(t.getClassName());
            default:
                throw new AssertionError("unsupported ASM type sort: " + t.getSort());
        }
    }

    private static Method resolveMethod(String declaringClassName, String methodName, String descriptor)
            throws ReflectiveOperationException {
        Class<?> decl = loadClass(declaringClassName);
        Type methodType = Type.getMethodType(descriptor);
        Type[] argAsm = methodType.getArgumentTypes();
        Class<?>[] paramTypes = new Class<?>[argAsm.length];
        for (int i = 0; i < argAsm.length; i++) {
            paramTypes[i] = asmTypeToClass(argAsm[i]);
        }
        try {
            return decl.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            return decl.getMethod(methodName, paramTypes);
        }
    }

    @Override
    public Object deserialize(DeserializerContext context, DataInput buffer) throws IOException {
        byte typeCode = buffer.readByte();
        return switch (typeCode) {
            case CloffleBytecodeSerializer.TYPE_NULL -> null;
            case CloffleBytecodeSerializer.TYPE_FALSE_SENTINEL -> Boolean.FALSE;
            case CloffleBytecodeSerializer.TYPE_STRING -> buffer.readUTF();
            case CloffleBytecodeSerializer.TYPE_LONG -> buffer.readLong();
            case CloffleBytecodeSerializer.TYPE_INT -> buffer.readInt();
            case CloffleBytecodeSerializer.TYPE_DOUBLE -> buffer.readDouble();
            case CloffleBytecodeSerializer.TYPE_BOOLEAN -> buffer.readBoolean();
            case CloffleBytecodeSerializer.TYPE_CHAR -> buffer.readChar();
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
            case CloffleBytecodeSerializer.TYPE_NAMESPACE -> {
                boolean hasNs = buffer.readBoolean();
                String ns = hasNs ? buffer.readUTF() : null;
                String name = buffer.readUTF();
                yield Namespace.findOrCreate(Symbol.intern(ns, name));
            }
            case CloffleBytecodeSerializer.TYPE_ROOT_NODE -> context.readBytecodeNode(buffer);
            case CloffleBytecodeSerializer.TYPE_CLASS -> {
                String className = buffer.readUTF();
                try {
                    yield loadClass(className);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not deserialize class " + className, e);
                }
            }
            case CloffleBytecodeSerializer.TYPE_CLASS_DCL -> {
                String className = buffer.readUTF();
                int len = buffer.readInt();
                if (len < 0) {
                    throw new IOException("invalid DCL class bytes length: " + len);
                }
                byte[] bytes = new byte[len];
                buffer.readFully(bytes);
                try {
                    yield loadClassFromDclBytes(className, bytes);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not deserialize DCL class " + className, e);
                }
            }
            case CloffleBytecodeSerializer.TYPE_RESOLVED_METHOD -> {
                String declaringClassName = buffer.readUTF();
                String mName = buffer.readUTF();
                String descriptor = buffer.readUTF();
                try {
                    yield resolveMethod(declaringClassName, mName, descriptor);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(
                            "Could not deserialize method " + declaringClassName + "." + mName + descriptor, e);
                }
            }
            case CloffleBytecodeSerializer.TYPE_VAR -> {
                boolean hasNs = buffer.readBoolean();
                String nsPart = hasNs ? buffer.readUTF() : null;
                String namePart = buffer.readUTF();
                if (hasNs) {
                    // Var.find is null for vars not yet interned — common during clojure.core bytecode bootstrap
                    // (replay runs before earlier top-level forms have executed). Match load order by interning.
                    Namespace ns = Namespace.findOrCreate(Symbol.intern(nsPart));
                    Symbol nameSym = Symbol.intern(namePart);
                    Var v = ns.findInternedVar(nameSym);
                    yield v != null ? v : Var.intern(ns, nameSym);
                }
                Namespace cur = (Namespace) RT.CURRENT_NS.deref();
                Var v = cur.findInternedVar(Symbol.intern(namePart));
                if (v == null) {
                    throw new RuntimeException(
                            "Could not deserialize Var " + namePart + " in namespace " + cur.getName());
                }
                yield v;
            }
            case CloffleBytecodeSerializer.TYPE_IDENTITY_CONSTANT ->
                    new CloffleBytecodeRootNode.IdentityConstant(deserialize(context, buffer));
            case CloffleBytecodeSerializer.TYPE_PERSISTENT_MAP -> {
                int n = buffer.readInt();
                IPersistentMap acc = PersistentArrayMap.EMPTY;
                for (int i = 0; i < n; i++) {
                    Object k = deserialize(context, buffer);
                    Object v = deserialize(context, buffer);
                    acc = acc.assoc(k, v);
                }
                yield acc;
            }
            case CloffleBytecodeSerializer.TYPE_PERSISTENT_VECTOR -> {
                int n = buffer.readInt();
                Object[] items = new Object[n];
                for (int i = 0; i < n; i++) {
                    items[i] = deserialize(context, buffer);
                }
                yield RT.vector(items);
            }
            case CloffleBytecodeSerializer.TYPE_PERSISTENT_SET -> {
                int n = buffer.readInt();
                Object[] items = new Object[n];
                for (int i = 0; i < n; i++) {
                    items[i] = deserialize(context, buffer);
                }
                yield PersistentHashSet.create(items);
            }
            case CloffleBytecodeSerializer.TYPE_MAP_ENTRY ->
                    MapEntry.create(deserialize(context, buffer), deserialize(context, buffer));
            case CloffleBytecodeSerializer.TYPE_SEQ -> {
                int n = buffer.readInt();
                Object[] items = new Object[n];
                for (int i = 0; i < n; i++) {
                    items[i] = deserialize(context, buffer);
                }
                yield PersistentList.create(Arrays.asList(items));
            }
            case CloffleBytecodeSerializer.TYPE_SOURCE -> {
                String language = buffer.readUTF();
                String name = buffer.readUTF();
                String content = readUtfLarge(buffer);
                Source override = SOURCE_OVERRIDE.get();
                if (override != null && override.getLanguage().equals(language)) {
                    yield override;
                }
                yield Source.newBuilder(language, content, name).build();
            }
            case CloffleBytecodeSerializer.TYPE_REGEX_PATTERN ->
                    Pattern.compile(buffer.readUTF(), buffer.readInt());
            default -> throw new AssertionError("Unknown type code " + typeCode);
        };
    }
}
