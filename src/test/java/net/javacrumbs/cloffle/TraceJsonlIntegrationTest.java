package net.javacrumbs.cloffle;

import net.javacrumbs.cloffle.trace.CloffleTracer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TraceJsonlIntegrationTest {

    @After
    public void tearDown() {
        CloffleTracer.init(null);
    }

    @Test
    public void testTopLevelFormEmitsTrace() throws Exception {
        Path tempFile = Files.createTempFile("trace", ".jsonl");
        CloffleTracer.init(tempFile.toAbsolutePath().toString());

        try (Engine engine = Engine.create();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Source code = Source.newBuilder("cloffle", "(def x 10)\n(throw (Exception. \"test-ex\"))", "test.clj")
                    .uri(new java.net.URI("file:///test.clj"))
                    .build();

            try {
                context.eval(code);
                fail("expected exception from throw form");
            } catch (PolyglotException expected) {
                String msg = expected.getMessage();
                String cause = expected.getCause() != null ? String.valueOf(expected.getCause()) : "";
                assertTrue("expected test-ex in exception, got message=" + msg + " cause=" + cause,
                        (msg != null && msg.contains("test-ex")) || cause.contains("test-ex"));
            }
        }

        List<String> lines = Files.readAllLines(tempFile);
        assertTrue("Trace should contain events, got: " + lines, lines.size() >= 3);

        assertTrue("Should have formEnter for def: " + lines,
                lines.stream().anyMatch(l -> l.contains("\"kind\":\"formEnter\"") && l.contains("def x")));

        assertTrue("Should have bindingWrite for x: " + lines,
                lines.stream().anyMatch(l -> l.contains("\"kind\":\"bindingWrite\"")
                        && l.contains("\"symbol\":\"x\"")
                        && l.contains("\"value\":\"10\"")));

        assertTrue("Should have formExit for def: " + lines,
                lines.stream().anyMatch(l -> l.contains("\"kind\":\"formExit\"") && l.contains("def x")));

        assertTrue("Should have exception event: " + lines,
                lines.stream().anyMatch(l -> l.contains("\"kind\":\"exception\"") && l.contains("test-ex")));
    }
}
