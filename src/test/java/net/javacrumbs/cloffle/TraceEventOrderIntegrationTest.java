package net.javacrumbs.cloffle;

import net.javacrumbs.cloffle.trace.CloffleTracer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TraceEventOrderIntegrationTest {

    @After
    public void tearDown() {
        CloffleTracer.init(null);
    }

    @Test
    public void testFormEnterAndExitOrder() throws Exception {
        Path tempFile = Files.createTempFile("trace", ".jsonl");
        CloffleTracer.init(tempFile.toAbsolutePath().toString());

        try (Engine engine = Engine.create();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

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

        assertTrue("missing formEnter for def: " + lines, idxDefEnter != -1);
        assertTrue("missing formExit for def: " + lines, idxDefExit != -1);
        assertTrue("missing formEnter for +: " + lines, idxAddEnter != -1);
        assertTrue("missing formExit for +: " + lines, idxAddExit != -1);
        assertTrue("missing formEnter for *: " + lines, idxMulEnter != -1);
        assertTrue("missing formExit for *: " + lines, idxMulExit != -1);

        assertTrue(idxDefEnter < idxDefExit);
        assertTrue(idxAddEnter < idxAddExit);
        assertTrue(idxMulEnter < idxMulExit);

        assertTrue(idxDefExit < idxAddEnter);
        assertTrue(idxAddExit < idxMulEnter);
    }
}
