package net.javacrumbs.cloffle;

import net.javacrumbs.cloffle.trace.CloffleTracer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TraceJsonlIntegrationTest {

    @AfterEach
    public void tearDown() {
        CloffleTracer.init(null);
    }

    @Test
    public void testTopLevelFormEmitsTrace() throws Exception {
        Path tempFile = Files.createTempFile("trace", ".jsonl");
        CloffleTracer.init(tempFile.toAbsolutePath().toString());

        try (Engine engine = Engine.create();
             Context context = CloffleEvalTestSupport.newContext(engine, "trace-jsonl")) {

            Source code = Source.newBuilder("cloffle", "(def x 10)\n(throw (Exception. \"test-ex\"))", "test.clj")
                    .uri(new java.net.URI("file:///test.clj"))
                    .build();

            try {
                context.eval(code);
                fail("expected exception from throw form");
            } catch (PolyglotException expected) {
                String msg = expected.getMessage();
                String cause = expected.getCause() != null ? String.valueOf(expected.getCause()) : "";
                assertTrue((msg != null && msg.contains("test-ex")) || cause.contains("test-ex"), "expected test-ex in exception, got message=" + msg + " cause=" + cause);
            }
        }

        List<String> lines = Files.readAllLines(tempFile);
        assertTrue(lines.size() >= 3, "Trace should contain events, got: " + lines);

        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"formEnter\"") && l.contains("def x")), "Should have formEnter for def: " + lines);

        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"bindingWrite\"")
                        && l.contains("\"symbol\":\"x\"")
                        && l.contains("\"value\":\"10\"")), "Should have bindingWrite for x: " + lines);

        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"formExit\"") && l.contains("def x")), "Should have formExit for def: " + lines);

        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"exception\"") && l.contains("test-ex")), "Should have exception event: " + lines);
    }
}
