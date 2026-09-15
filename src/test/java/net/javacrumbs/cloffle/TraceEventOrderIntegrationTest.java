package net.javacrumbs.cloffle;

import net.javacrumbs.cloffle.trace.CloffleTracer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TraceEventOrderIntegrationTest {

    @AfterEach
    public void tearDown() {
        CloffleTracer.init(null);
    }

    @Test
    public void testFormEnterAndExitOrder() throws Exception {
        Path tempFile = Files.createTempFile("trace", ".jsonl");
        CloffleTracer.init(tempFile.toAbsolutePath().toString());

        try (Engine engine = Engine.create();
             Context context = CloffleEvalTestSupport.newContext(engine, "trace-order")) {

            Source code = Source.newBuilder("cloffle", "(def x 10)\n(+ x 5)\n(* 2 2)", "test.clj")
                    .uri(new java.net.URI("file:///test.clj"))
                    .build();

            context.eval(code);
        }

        List<String> lines = Files.readAllLines(tempFile);
        if (lines.isEmpty()) {
            fail("Trace file empty; expected formEnter/formExit for three top-level forms");
        }

        int idxDefEnter = -1, idxDefExit = -1;
        int idxAddEnter = -1, idxAddExit = -1;
        int idxMulEnter = -1, idxMulExit = -1;

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("formEnter") && l.contains("def x")) idxDefEnter = i;
            if (l.contains("formExit") && l.contains("def x")) idxDefExit = i;

            if (l.contains("formEnter") && l.contains("+ x")) idxAddEnter = i;
            if (l.contains("formExit") && l.contains("+ x")) idxAddExit = i;

            if (l.contains("formEnter") && l.contains("* 2")) idxMulEnter = i;
            if (l.contains("formExit") && l.contains("* 2")) idxMulExit = i;
        }

        assertTrue(idxDefEnter != -1, "missing formEnter for def: " + lines);
        assertTrue(idxDefExit != -1, "missing formExit for def: " + lines);
        assertTrue(idxAddEnter != -1, "missing formEnter for +: " + lines);
        assertTrue(idxAddExit != -1, "missing formExit for +: " + lines);
        assertTrue(idxMulEnter != -1, "missing formEnter for *: " + lines);
        assertTrue(idxMulExit != -1, "missing formExit for *: " + lines);

        assertTrue(idxDefEnter < idxDefExit);
        assertTrue(idxAddEnter < idxAddExit);
        assertTrue(idxMulEnter < idxMulExit);

        assertTrue(idxDefExit < idxAddEnter);
        assertTrue(idxAddExit < idxMulEnter);
    }
}
