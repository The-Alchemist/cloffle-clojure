package net.javacrumbs.cloffle.junit;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Opt-in JVM bootstrap for tests that call {@link clojure.lang.Compiler} / {@link RT} but do not
 * already use {@code @BeforeAll} with {@link RT#init()}.
 *
 * <pre>{@code
 * @ExtendWith(CloffleHostClojureExtension.class)
 * class MyCompilerTest { ... }
 * }</pre>
 *
 * <p>Platform-wide {@link org.junit.platform.launcher.TestExecutionListener} hooks that call
 * {@code RT.init()} before tests are risky: the ConsoleLauncher thread can fail while loading
 * {@code core.clj} through Cloffle (see notes in {@code CLOFFLE_NOTES.md}). Prefer this extension or
 * {@code @BeforeAll} on the test class.
 */
public final class CloffleHostClojureExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }
}
