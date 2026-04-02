package net.javacrumbs.cloffle.junit;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.rules.ExternalResource;

/**
 * Opt-in JVM bootstrap for tests that call {@link clojure.lang.Compiler} / {@link RT} but do not
 * already use {@code @BeforeClass} with {@link RT#init()}.
 *
 * <pre>{@code
 * @ClassRule
 * public static final CloffleHostClojureRule CLOJURE_HOST = new CloffleHostClojureRule();
 * }</pre>
 *
 * <p>Platform-wide {@link org.junit.platform.launcher.TestExecutionListener} hooks that call
 * {@code RT.init()} before tests are risky: the ConsoleLauncher thread can fail while loading
 * {@code core.clj} through Cloffle (see notes in {@code CLOFFLE_NOTES.md}). Prefer this rule or
 * {@code @BeforeClass} on the test class.
 */
public final class CloffleHostClojureRule extends ExternalResource {

    @Override
    protected void before() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }
}
