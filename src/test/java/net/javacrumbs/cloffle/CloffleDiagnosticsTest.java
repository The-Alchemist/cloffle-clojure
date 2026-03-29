package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class CloffleDiagnosticsTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle").allowAllAccess(true).build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    @Test
    public void checkParseSucceedsOnValidSource() throws Exception {
        Source src = Source.newBuilder("cloffle", "(+ 1 2)", "ok.clj").buildLiteral();
        List<CloffleDiagnostics.Diagnostic> d = CloffleDiagnostics.checkParse(context, src);
        assertThat(d).isEmpty();
    }

    @Test
    public void checkParseReturnsDiagnosticOnSyntaxError() throws Exception {
        Source src = Source.newBuilder("cloffle", "(+ 1 ", "bad.clj").buildLiteral();
        List<CloffleDiagnostics.Diagnostic> d = CloffleDiagnostics.checkParse(context, src);
        assertThat(d).hasSize(1);
        CloffleDiagnostics.Diagnostic diag = d.get(0);
        assertThat(diag.sourceName()).isEqualTo("bad.clj");
        assertThat(diag.severity()).isEqualTo(CloffleDiagnostics.Severity.ERROR);
        assertThat(diag.message()).isNotEmpty();
        assertThat(diag.startLine()).isGreaterThan(0);
    }

    @Test
    public void diagnosticFromExceptionUsesTriageMessage() {
        try {
            context.eval(Source.newBuilder("cloffle", "(/ 1 0)", "div.clj").buildLiteral());
        } catch (PolyglotException e) {
            CloffleDiagnostics.Diagnostic diag =
                    CloffleDiagnostics.diagnosticFromException("div.clj", e);
            String low = diag.message().toLowerCase();
            assertThat(low.contains("divide") || low.contains("zero") || low.contains("/ by"))
                    .as("message: %s", diag.message())
                    .isTrue();
            assertThat(diag.phase()).isEqualTo("execution");
            return;
        }
        throw new AssertionError("expected PolyglotException");
    }
}
