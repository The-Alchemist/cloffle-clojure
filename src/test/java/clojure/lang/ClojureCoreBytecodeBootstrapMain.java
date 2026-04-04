package clojure.lang;

/**
 * Cold-start probe for {@code RT#init()} with {@code -Dcloffle.core.bytecode.archive} set (see
 * {@link clojure.lang.RT#doInit()} → {@link net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive}).
 * Invoked only from a <strong>fresh JVM</strong> by {@link BytecodeSerializationRoundTripTest}; do not run
 * after {@code RT.init()} has already run in-process.
 * <p>
 * Pass {@code check-property} as the first argument to exit 0 iff the archive property is set — no
 * {@link RT#init()} (used to verify subprocess / {@code -D} wiring without loading core).
 */
public final class ClojureCoreBytecodeBootstrapMain {

    private ClojureCoreBytecodeBootstrapMain() {}

    public static void main(String[] args) {
        try {
            String archive = System.getProperty("cloffle.core.bytecode.archive");
            if (archive == null || archive.isBlank()) {
                System.err.println("expected -Dcloffle.core.bytecode.archive=<path to .bc file>");
                System.exit(2);
            }
            if (args.length > 0 && "check-property".equals(args[0])) {
                System.exit(0);
            }
            RT.init();
            Object plus = RT.var("clojure.core", "+").deref();
            if (plus == null) {
                System.err.println("clojure.core/+ unbound after bytecode bootstrap");
                System.exit(3);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
