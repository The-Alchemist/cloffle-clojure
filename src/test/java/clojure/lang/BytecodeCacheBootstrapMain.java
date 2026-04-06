package clojure.lang;

/**
 * Cold-start probe for {@code RT.init()} with {@code .bc} files on the classpath.
 * All bootstrap {@code .clj} files should be served from pre-compiled {@code .bc} archives
 * found via the classloader instead of being compiled from source.
 * <p>
 * Invoked only from a <strong>fresh JVM</strong> by
 * {@link BytecodeSerializationRoundTripTest#freshJvmBootstrapsAllNamespacesFromBytecodeCache};
 * do not run after {@code RT.init()} has already run in-process.
 */
public final class BytecodeCacheBootstrapMain {

    private BytecodeCacheBootstrapMain() {}

    public static void main(String[] args) {
        try {
            RT.init();
            Object plus = RT.var("clojure.core", "+").deref();
            if (plus == null) {
                System.err.println("clojure.core/+ unbound after bytecode cache bootstrap");
                System.exit(3);
            }
            Object result = ((IFn) plus).invoke(1L, 2L);
            if (!(result instanceof Number n) || n.longValue() != 3L) {
                System.err.println("(+ 1 2) returned " + result + ", expected 3");
                System.exit(4);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
