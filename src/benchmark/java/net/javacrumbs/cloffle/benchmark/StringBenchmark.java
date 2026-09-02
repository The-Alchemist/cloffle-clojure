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
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.strings.TruffleString;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class StringBenchmark {

    private Context context;
    private Value strJoinFn;
    private Value strSplitFn;
    private Value strSubsFn;
    private Value symbolEvalFn;

    private Symbol testSym;
    private TruffleString testTruffleStr;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
            .allowAllAccess(true)
            .build();

        testSym = Symbol.intern("clojure.core", "defn");
        testTruffleStr = TruffleString.fromJavaStringUncached("clojure.core/defn", TruffleString.Encoding.UTF_16);

        context.eval("cloffle", "(require '[clojure.string :as str])");
        context.eval("cloffle", "(defn benchmark-join [items] (str/join \",\" items))");
        strJoinFn = context.eval("cloffle", "benchmark-join");

        context.eval("cloffle", "(defn benchmark-split [s] (str/split s #\",\"))");
        strSplitFn = context.eval("cloffle", "benchmark-split");

        context.eval("cloffle", "(defn benchmark-subs [s] (subs s 5 15))");
        strSubsFn = context.eval("cloffle", "benchmark-subs");

        context.eval("cloffle", "(defn benchmark-symbol [s] (symbol s))");
        symbolEvalFn = context.eval("cloffle", "benchmark-symbol");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
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
        return strJoinFn.execute(context.eval("cloffle", "[\"foo\" \"bar\" \"baz\" \"qux\"]"));
    }

    @Benchmark
    public Value clojureStrSplit() {
        return strSplitFn.execute("foo,bar,baz,qux,alpha,beta,gamma");
    }

    @Benchmark
    public Value clojureSymbolCreation() {
        return symbolEvalFn.execute("my.namespace/my-symbol");
    }
}
