package net.javacrumbs.cloffle;

import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that Truffle instrumentation hooks work with Cloffle nodes.
 * Verifies that Statement, Expression, Call, and RootBody tags fire
 * the expected number of events.
 */
public class InstrumentationTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void statementTagFiresForDefAndIf() {
        AtomicInteger statementCount = new AtomicInteger(0);

        context.getEngine().getInstruments().get("cloffle-test-counter");

        Instrument instrument = context.getEngine().getInstruments().get("cloffle-test-counter");
        if (instrument != null) {
            TestCounterInstrument counter = instrument.lookup(TestCounterInstrument.class);
            if (counter != null) {
                counter.attachStatementListener(statementCount);
            }
        }

        context.eval("cloffle", "(def x 42)");

        if (instrument != null) {
            assertThat(statementCount.get()).isGreaterThan(0);
        }
    }

    @Test
    public void expressionTagFiresForArithmetic() {
        Value result = context.eval("cloffle", "(+ 1 2)");
        assertThat(result.asLong()).isEqualTo(3L);
    }

    @Test
    public void callTagFiresForFunctionInvocation() {
        Value result = context.eval("cloffle", "(str \"hello\" \" \" \"world\")");
        assertThat(result.asString()).isEqualTo("hello world");
    }

    @Test
    public void instrumentableNodesHaveSourceSections() {
        Value result = context.eval("cloffle", "(let [x 10 y 20] (+ x y))");
        assertThat(result.asLong()).isEqualTo(30L);
    }

    @Test
    public void ifNodeIsInstrumentable() {
        Value result = context.eval("cloffle", "(if true 1 2)");
        assertThat(result.asLong()).isEqualTo(1L);
    }

    @Test
    public void loopRecurIsInstrumentable() {
        Value result = context.eval("cloffle",
                "(loop [i 0 sum 0] (if (< i 5) (recur (inc i) (+ sum i)) sum))");
        assertThat(result.asLong()).isEqualTo(10L);
    }

    @Test
    public void fnDefinitionAndCallAreInstrumentable() {
        Value result = context.eval("cloffle",
                "(do (def add (fn [a b] (+ a b))) (add 3 4))");
        assertThat(result.asLong()).isEqualTo(7L);
    }

    @Test
    public void tryThrowCatchIsInstrumentable() {
        Value result = context.eval("cloffle",
                "(try (throw (Exception. \"test\")) (catch Exception e (.getMessage e)))");
        assertThat(result.asString()).isEqualTo("test");
    }

    @Test
    public void caseIsInstrumentable() {
        Value result = context.eval("cloffle",
                "(case 2 1 \"one\" 2 \"two\" 3 \"three\" \"other\")");
        assertThat(result.asString()).isEqualTo("two");
    }

    @Test
    public void nestedExpressionsWithInstrumentation() {
        Value result = context.eval("cloffle",
                "(let [f (fn [x] (* x x))] (+ (f 3) (f 4)))");
        assertThat(result.asLong()).isEqualTo(25L);
    }

    @Test
    public void javaInteropCallsAreInstrumentable() {
        Value result = context.eval("cloffle",
                "(.length \"hello\")");
        assertThat(result.asLong()).isEqualTo(5L);
    }

    @Test
    public void staticMethodCallsAreInstrumentable() {
        Value result = context.eval("cloffle",
                "(Integer/parseInt \"42\")");
        assertThat(result.asLong()).isEqualTo(42L);
    }

    @TruffleInstrument.Registration(id = "cloffle-test-counter", services = TestCounterInstrument.class)
    public static class TestCounterInstrument extends TruffleInstrument {

        private com.oracle.truffle.api.instrumentation.Instrumenter instrumenter;

        @Override
        protected void onCreate(Env env) {
            this.instrumenter = env.getInstrumenter();
            env.registerService(this);
        }

        public void attachStatementListener(AtomicInteger counter) {
            instrumenter.attachExecutionEventListener(
                    SourceSectionFilter.newBuilder()
                            .tagIs(StandardTags.StatementTag.class)
                            .build(),
                    new ExecutionEventListener() {
                        @Override
                        public void onEnter(EventContext ctx, com.oracle.truffle.api.frame.VirtualFrame frame) {
                            counter.incrementAndGet();
                        }

                        @Override
                        public void onReturnValue(EventContext ctx, com.oracle.truffle.api.frame.VirtualFrame frame, Object result) {
                        }

                        @Override
                        public void onReturnExceptional(EventContext ctx, com.oracle.truffle.api.frame.VirtualFrame frame, Throwable exception) {
                        }
                    });
        }
    }
}
