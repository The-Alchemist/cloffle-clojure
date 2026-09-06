package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.strings.TruffleString;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class StringBenchmark {

    private static final ThreadLocal<Map<String, Object>> CAPTURED_GUEST_VALUES =
            ThreadLocal.withInitial(HashMap::new);

    private Context context;
    private Value strJoinFn;
    private Value strSplitFn;
    private Value strSubsFn;
    private Value symbolEvalFn;
    private IFn guestStr2LengthFn;
    private IFn guestStr3LengthFn;

    private Symbol testSym;
    private TruffleString testTruffleStr;
    private String strA;
    private String strB;
    private String strC;
    private String joinItemsSource;

    public static Object captureGuestValue(String name, Object value) {
        CAPTURED_GUEST_VALUES.get().put(name, value);
        return value;
    }

    private Object guestValue(String name) {
        context.eval("cloffle",
                "(net.javacrumbs.cloffle.benchmark.StringBenchmark/captureGuestValue "
                        + "\"" + name + "\" " + name + ")");
        Object value = CAPTURED_GUEST_VALUES.get().remove(name);
        if (value == null) {
            throw new IllegalStateException("Guest value was not captured: " + name);
        }
        return value;
    }

    private IFn guestFn(String name) {
        return (IFn) guestValue(name);
    }

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
            .allowAllAccess(true)
            .build();

        testSym = Symbol.intern("clojure.core", "defn");
        testTruffleStr = TruffleString.fromJavaStringUncached("clojure.core/defn", TruffleString.Encoding.UTF_16);
        strA = new String("cloffle-");
        strB = new String("fixed-arity-");
        strC = new String("string");

        context.eval("cloffle", ClojureClasspathResources.read("string-benchmark/setup.clj"));
        joinItemsSource = ClojureClasspathResources.read("string-benchmark/join-items.clj");
        strJoinFn = context.eval("cloffle", "benchmark-join");
        strSplitFn = context.eval("cloffle", "benchmark-split");
        strSubsFn = context.eval("cloffle", "benchmark-subs");
        symbolEvalFn = context.eval("cloffle", "benchmark-symbol");
        guestStr2LengthFn = guestFn("guest-str2-length");
        guestStr3LengthFn = guestFn("guest-str3-length");

        // Keep the context entered so timed IFn.invoke calls bypass Polyglot Value.execute.
        context.enter();
        CAPTURED_GUEST_VALUES.remove();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    @Benchmark
    public TruffleString symbolToTruffleString() {
        return testSym.toTruffleString();
    }

    @Benchmark
    public TruffleString truffleStringSubstring() {
        return testTruffleStr.substringUncached(5, 5, TruffleString.Encoding.UTF_16, true);
    }

    @Benchmark
    public Value clojureSubs() {
        return strSubsFn.execute("0123456789abcdefghijklmnopqrstuvwxyz");
    }

    @Benchmark
    public Value clojureStrJoin() {
        return strJoinFn.execute(context.eval("cloffle", joinItemsSource));
    }

    @Benchmark
    public Value clojureStrSplit() {
        return strSplitFn.execute("foo,bar,baz,qux,alpha,beta,gamma");
    }

    @Benchmark
    public Value clojureSymbolCreation() {
        return symbolEvalFn.execute("my.namespace/my-symbol");
    }

    @Benchmark
    public Object guestStr2Length() {
        return guestStr2LengthFn.invoke(strA, strB);
    }

    @Benchmark
    public Object guestStr3Length() {
        return guestStr3LengthFn.invoke(strA, strB, strC);
    }
}
