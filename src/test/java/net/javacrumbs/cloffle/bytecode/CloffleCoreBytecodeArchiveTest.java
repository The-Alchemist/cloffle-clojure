package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Atom;
import clojure.lang.IRef;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CloffleCoreBytecodeArchiveTest {

    @BeforeClass
    public static void initRt() {
        System.setProperty("cloffle.core.bytecode.quiet", "true");
        RT.init();
        // Same as CoreCljBytecodeSerializationRoundTripTest: *ns* root so Compiler.compile snapshots user.
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    @Test
    public void replayRejectsBadMagic() throws Exception {
        Path tmp = Files.createTempFile("cbc-bad", ".bc");
        try {
            Files.write(tmp, new byte[] {'x', 'x', 'x', 'x', 0, 0, 0, 1});
            assertFalse(CloffleCoreBytecodeArchive.replayFromFile(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void preboundAtomArchiveSwapRunsTwiceAfterReplay() throws Exception {
        Path tmp = Files.createTempFile("cbc-swap", ".bc");
        try {
            String sym = "cloffle_archive_sw_m_" + RT.nextID();
            Namespace user = Namespace.findOrCreate(Symbol.intern("user"));
            Var v = Var.intern(user, Symbol.intern(sym), new Atom(0L));
            String code = "(swap! " + sym + " inc)\n";
            CloffleCoreBytecodeArchive.writeArchive(tmp, code, "smoke.clj", "smoke.clj");
            assertEquals(1L, ((IRef) v.deref()).deref());
            assertTrue(CloffleCoreBytecodeArchive.replayFromFile(tmp));
            assertEquals(2L, ((IRef) v.deref()).deref());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
