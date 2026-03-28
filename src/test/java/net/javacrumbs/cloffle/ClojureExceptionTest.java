package net.javacrumbs.cloffle;

import clojure.lang.Keyword;
import clojure.lang.RT;
import net.javacrumbs.cloffle.nodes.ClojureException;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ClojureException:
 * - IExceptionInfo (ex-data) support
 * - Stack trace filtering
 * - Phase tracking
 */
public class ClojureExceptionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    @Test
    public void wrapSetsExecutionPhase() {
        Exception cause = new ArithmeticException("/ by zero");
        ClojureException ce = ClojureException.wrap(cause, null);
        assertThat(ce.getPhase()).isNotNull();
        assertThat(ce.getPhase().getName()).isEqualTo("execution");
    }

    @Test
    public void getDataReturnsMapWithPhase() {
        ClojureException ce = new ClojureException("test error", null,
                Keyword.intern(null, "execution"));
        var data = ce.getData();
        assertThat(data).isNotNull();
        Keyword phaseKey = Keyword.intern("clojure.error", "phase");
        assertThat(data.valAt(phaseKey)).isEqualTo(Keyword.intern(null, "execution"));
    }

    @Test
    public void getDataWithCauseIncludesClassAndMessage() {
        RuntimeException cause = new RuntimeException("boom");
        ClojureException ce = new ClojureException("test", cause, null,
                Keyword.intern(null, "execution"));
        var data = ce.getData();
        assertThat(data).isNotNull();
        Keyword classKey = Keyword.intern("clojure.error", "class");
        Keyword causeKey = Keyword.intern("clojure.error", "cause");
        assertThat(data.valAt(classKey)).isNotNull();
        assertThat(data.valAt(causeKey)).isEqualTo("boom");
    }

    @Test
    public void getDataWithoutPhaseReturnsEmptyOrPartialMap() {
        ClojureException ce = new ClojureException("test", (com.oracle.truffle.api.nodes.Node) null);
        var data = ce.getData();
        assertThat(data).isNotNull();
    }

    @Test
    public void filterInternalFramesRemovesTruffleFrames() {
        StackTraceElement[] frames = new StackTraceElement[]{
                new StackTraceElement("com.oracle.truffle.api.impl.DefaultCallTarget", "call", "CallTarget.java", 10),
                new StackTraceElement("net.javacrumbs.cloffle.nodes.FnNode", "invoke", "FnNode.java", 100),
                new StackTraceElement("org.graalvm.polyglot.Context", "eval", "Context.java", 50),
                new StackTraceElement("clojure.core$println", "invoke", "core.clj", 3500),
                new StackTraceElement("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke", "Unknown", 0),
        };

        StackTraceElement[] filtered = ClojureException.filterInternalFrames(frames);
        assertThat(filtered).hasSize(2);
        assertThat(filtered[0].getClassName()).isEqualTo("net.javacrumbs.cloffle.nodes.FnNode");
        assertThat(filtered[1].getClassName()).isEqualTo("clojure.core$println");
    }

    @Test
    public void filterInternalFramesKeepsAllUserFrames() {
        StackTraceElement[] frames = new StackTraceElement[]{
                new StackTraceElement("user$my_fn", "invoke", "user.clj", 10),
                new StackTraceElement("clojure.lang.RT", "seq", "RT.java", 100),
        };

        StackTraceElement[] filtered = ClojureException.filterInternalFrames(frames);
        assertThat(filtered).hasSize(2);
    }

    @Test
    public void publishAndConsumePhase() {
        ClojureException ce = new ClojureException("test", (com.oracle.truffle.api.nodes.Node) null);
        ce.setPhase(Keyword.intern(null, "execution"));
        ce.publishFrames();

        Keyword consumed = ClojureException.consumePhase();
        assertThat(consumed).isNotNull();
        assertThat(consumed.getName()).isEqualTo("execution");

        // Second consume should return null (already consumed)
        assertThat(ClojureException.consumePhase()).isNull();
    }

    @Test
    public void enrichedFramesStartEmpty() {
        ClojureException ce = new ClojureException("test", (com.oracle.truffle.api.nodes.Node) null);
        assertThat(ce.getEnrichedFrames()).isEmpty();
    }

    @Test
    public void addFrameWithNullDoesNotFail() {
        ClojureException ce = new ClojureException("test", (com.oracle.truffle.api.nodes.Node) null);
        ce.addFrame(null);
        assertThat(ce.getEnrichedFrames()).isEmpty();
    }
}
