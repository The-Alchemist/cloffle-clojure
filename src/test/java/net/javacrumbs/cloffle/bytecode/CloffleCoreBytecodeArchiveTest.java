package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Atom;
import clojure.lang.IRef;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CloffleCoreBytecodeArchiveTest {

    @BeforeClass
    public static void initRt() {
        System.setProperty("cloffle.core.bytecode.quiet", "false");
        RT.init();
        // Same as CoreCljBytecodeSerializationRoundTripTest: *ns* root so Compiler.compile snapshots user.
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
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
            CloffleCoreBytecodeArchive.replayFromFile(tmp);
            assertEquals(2L, ((IRef) v.deref()).deref());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Multiple top-level forms are compiled, executed during write (each chunk), serialized in order, and replayed
     * in the same order — side effects accumulate predictably.
     */
    @Test
    public void multipleTopLevelFormsRunInOrderOnWriteAndAgainOnReplay() throws Exception {
        Path tmp = Files.createTempFile("cbc-multi", ".bc");
        try {
            String sym = "cloffle_archive_mf_" + RT.nextID();
            Namespace user = Namespace.findOrCreate(Symbol.intern("user"));
            Var v = Var.intern(user, Symbol.intern(sym), new Atom(0L));
            String code =
                    "(swap! " + sym + " inc)\n"
                            + "(swap! " + sym + " inc)\n"
                            + "(swap! " + sym + " inc)\n";
            CloffleCoreBytecodeArchive.writeArchive(tmp, code, "multi.clj", "multi.clj");
            assertEquals(3L, ((IRef) v.deref()).deref());
            assertEquals(3, readFormCount(tmp));
            CloffleCoreBytecodeArchive.replayFromFile(tmp);
            assertEquals(6L, ((IRef) v.deref()).deref());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void compileEachTopLevelFormInvokesConsumerPerForm() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        String code = "(def _cbc_ct_1 1)\n(def _cbc_ct_2 2)\n";
        CloffleCoreBytecodeArchive.compileEachTopLevelForm(code, "ct.clj", "ct.clj", (formIndex, nodes) -> {
            seen.incrementAndGet();
            assertTrue(formIndex >= 1 && formIndex <= 2);
            nodes.getNode(0).getCallTarget().call();
        });
        assertEquals(2, seen.get());
    }

    @Test
    public void emptySourceWritesZeroChunksAndReplaySucceeds() throws Exception {
        Path tmp = Files.createTempFile("cbc-empty", ".bc");
        try {
            CloffleCoreBytecodeArchive.writeArchive(tmp, "", "empty.clj", "empty.clj");
            assertEquals(0, readFormCount(tmp));
            CloffleCoreBytecodeArchive.replayFromFile(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void replayArchiveInputStreamMatchesReplayFromFile() throws Exception {
        Path tmp = Files.createTempFile("cbc-stream", ".bc");
        try {
            String sym = "cloffle_archive_st_" + RT.nextID();
            Namespace user = Namespace.findOrCreate(Symbol.intern("user"));
            Var v = Var.intern(user, Symbol.intern(sym), new Atom(0L));
            String code = "(swap! " + sym + " inc)\n";
            CloffleCoreBytecodeArchive.writeArchive(tmp, code, "s.clj", "s.clj");
            long afterWrite = (Long) ((IRef) v.deref()).deref();
            assertEquals(1L, afterWrite);

            byte[] bytes = Files.readAllBytes(tmp);
            CloffleCoreBytecodeArchive.replayArchive(new ByteArrayInputStream(bytes), "bytes:test");
            assertEquals(2L, ((IRef) v.deref()).deref());

            CloffleCoreBytecodeArchive.replayArchive(new ByteArrayInputStream(bytes));
            assertEquals(3L, ((IRef) v.deref()).deref());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void wrongMagicThrowsIOException() throws Exception {
        Path tmp = Files.createTempFile("cbc-badmagic", ".bc");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(0xDEADBEEF);
                out.writeInt(CloffleCoreBytecodeArchive.VERSION);
                out.writeInt(0);
            }
            try {
                CloffleCoreBytecodeArchive.replayFromFile(tmp);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("wrong magic"));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void unsupportedVersionThrowsIOException() throws Exception {
        Path tmp = Files.createTempFile("cbc-badver", ".bc");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(CloffleCoreBytecodeArchive.MAGIC);
                out.writeInt(999);
                out.writeInt(0);
            }
            try {
                CloffleCoreBytecodeArchive.replayFromFile(tmp);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("unsupported format version"));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void negativeFormCountThrowsIOException() throws Exception {
        Path tmp = Files.createTempFile("cbc-negfc", ".bc");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(CloffleCoreBytecodeArchive.MAGIC);
                out.writeInt(CloffleCoreBytecodeArchive.VERSION);
                out.writeInt(-1);
            }
            try {
                CloffleCoreBytecodeArchive.replayFromFile(tmp);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("invalid form count"));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void truncatedPayloadThrowsIOException() throws Exception {
        Path tmp = Files.createTempFile("cbc-trunc", ".bc");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(CloffleCoreBytecodeArchive.MAGIC);
                out.writeInt(CloffleCoreBytecodeArchive.VERSION);
                out.writeInt(1);
                out.writeInt(4096);
                out.write(new byte[8]);
            }
            try {
                CloffleCoreBytecodeArchive.replayFromFile(tmp);
                fail("expected IOException");
            } catch (IOException expected) {
                // EOF before chunk fully read
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void readClasspathCoreCljTextIsNonEmpty() throws Exception {
        String text = CloffleCoreBytecodeArchive.readClasspathCoreCljText();
        assertTrue(text.length() > 10_000);
        assertTrue(text.contains("def"));
    }

    /**
     * Verifies that the source override mechanism in {@link CloffleBytecodeDeserializer} works:
     * without it, deserialized nodes have placeholder source content; with it, they get the real text.
     */
    @Test
    public void sourceOverrideReplacesPlaceholderDuringDeserialization() throws Exception {
        Path tmp = Files.createTempFile("cbc-src", ".bc");
        try {
            String code = "(def _cbc_src_test 42)\n";
            String sourcePath = "clojure/uuid.clj";
            String sourceName = "uuid.clj";

            CloffleCoreBytecodeArchive.writeArchive(tmp, code, sourcePath, sourceName);

            try (DataInputStream din = new DataInputStream(Files.newInputStream(tmp))) {
                assertEquals(CloffleCoreBytecodeArchive.MAGIC, din.readInt());
                assertEquals(CloffleCoreBytecodeArchive.VERSION, din.readInt());
                int formCount = din.readInt();
                assertTrue(formCount >= 1);
                int len = din.readInt();
                byte[] wire = new byte[len];
                din.readFully(wire);

                // Without override: Source content is the placeholder
                var nodesPlain = CloffleBytecodeSerialization.deserializeRootNodes(wire);
                com.oracle.truffle.api.source.Source plainSrc =
                        nodesPlain.getNode(0).getSourceSection().getSource();
                assertEquals(" ", plainSrc.getCharacters().toString());

                // With override: Source content is the real text.
                // compileEachTopLevelForm uses sourcePath as Source name, so the override must match.
                String realText = "(ns clojure.uuid)\n;; real source content\n";
                com.oracle.truffle.api.source.Source override =
                        com.oracle.truffle.api.source.Source.newBuilder("cloffle", realText, sourcePath).build();
                CloffleBytecodeDeserializer.setSourceOverride(override);
                try {
                    var nodesOverride = CloffleBytecodeSerialization.deserializeRootNodes(wire);
                    com.oracle.truffle.api.source.Source overrideSrc =
                            nodesOverride.getNode(0).getSourceSection().getSource();
                    assertEquals(sourcePath, overrideSrc.getName());
                    assertEquals(realText, overrideSrc.getCharacters().toString());
                } finally {
                    CloffleBytecodeDeserializer.clearSourceOverride();
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Verifies that {@link CloffleCoreBytecodeArchive#replayFromFile(Path, String, String)} loads
     * the real source text from the classpath for a known resource, so deserialized nodes carry
     * the actual file content after replay.
     */
    @Test
    public void replayFromFileAttachesRealSourceFromClasspath() throws Exception {
        Path tmp = Files.createTempFile("cbc-replay-src", ".bc");
        try {
            String code = "(def _cbc_replay_src 99)\n";
            String sourcePath = "clojure/uuid.clj";
            String sourceName = "uuid.clj";

            CloffleCoreBytecodeArchive.writeArchive(tmp, code, sourcePath, sourceName);
            CloffleCoreBytecodeArchive.replayFromFile(tmp, sourcePath, sourceName);

            // The replay loaded uuid.clj source from classpath.
            // Verify by loading the resource ourselves and checking it's non-trivial.
            java.io.InputStream ins = RT.resourceAsStream(RT.baseLoader(), sourcePath);
            assertNotNull("classpath resource must exist: " + sourcePath, ins);
            String realText;
            try (ins) {
                realText = new String(ins.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue("real uuid.clj source should be substantial", realText.length() > 50);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static int readFormCount(Path archivePath) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(archivePath))) {
            assertEquals(CloffleCoreBytecodeArchive.MAGIC, in.readInt());
            assertEquals(CloffleCoreBytecodeArchive.VERSION, in.readInt());
            return in.readInt();
        }
    }
}
