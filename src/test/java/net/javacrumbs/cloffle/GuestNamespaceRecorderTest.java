package net.javacrumbs.cloffle;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import net.javacrumbs.cloffle.nodes.ClojureTopScope;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link GuestNamespaceRecorder}: debugger/DAP tooling threads must see the
 * same logical namespace as the guest Truffle thread for {@link ClojureTopScope}, not
 * {@code clojure.core} from root {@code *ns*}.
 */
public class GuestNamespaceRecorderTest {

    private static final String NS = "com.cloffle.test.guest-ns-recorder";

    private Engine engine;
    private Context context;

    @BeforeEach
    public void setUp() {
        engine = Engine.create();
        context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .build();
    }

    @AfterEach
    public void tearDown() {
        if (context != null) {
            context.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Simulates a protocol thread where {@code *ns*} is bound to {@code clojure.core} but
     * {@link CloffleContext#getGuestNamespaceForDebugger()} holds the script namespace: the top
     * scope label and var lookups must still match the guest namespace.
     */
    @Test
    public void topScopeUsesRecordedNamespaceWhenVarDerefWouldBeCore()
            throws UnsupportedMessageException, UnknownIdentifierException {
        context.eval(
                "cloffle",
                "(ns " + NS + ") (def recorder-var 4242)\n");

        context.enter();
        try {
            Namespace scriptNs = Namespace.find(Symbol.intern(NS));
            assertNotNull(scriptNs, "namespace should exist after eval");

            Var.pushThreadBindings(RT.mapUniqueKeys(RT.CURRENT_NS, scriptNs));
            try {
                GuestNamespaceRecorder.recordIfPossible();
            } finally {
                Var.popThreadBindings();
            }

            Namespace coreNs = Namespace.findOrCreate(Symbol.intern("clojure.core"));
            Var.pushThreadBindings(RT.mapUniqueKeys(RT.CURRENT_NS, coreNs));
            try {
                CloffleContext cc = Clojure.getContext();
                assertNotNull(cc);
                assertNotNull(cc.getGuestNamespaceForDebugger(), "snapshot should be set");

                ClojureTopScope top = new ClojureTopScope();
                InteropLibrary interop = InteropLibrary.getUncached();
                String display = interop.asString(interop.toDisplayString(top));
                assertTrue(display.contains(NS), "top scope label should show guest ns, got: " + display);

                assertTrue(interop.isMemberReadable(top, "recorder-var"), "expected interned var in recorded namespace");
                Object v = interop.readMember(top, "recorder-var");
                assertNotNull(v);
            } finally {
                Var.popThreadBindings();
            }
        } finally {
            context.leave();
        }
    }
}
