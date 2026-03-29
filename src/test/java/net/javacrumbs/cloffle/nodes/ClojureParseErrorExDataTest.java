package net.javacrumbs.cloffle.nodes;

import clojure.lang.Compiler;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.Symbol;
import com.oracle.truffle.api.source.Source;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClojureParseErrorExDataTest {

    @Test
    public void getDataUsesInnermostCompilerExceptionPhaseAndSymbol() {
        Source truffleSource = Source.newBuilder("cloffle", "(ns x)", "macro.clj").build();
        Compiler.CompilerException inner = new Compiler.CompilerException(
                "macro.clj", 2, 1,
                Symbol.intern("user", "bad-macro"),
                Compiler.CompilerException.PHASE_MACROEXPANSION,
                new ArithmeticException("/ by zero"));
        ClojureParseError err = new ClojureParseError(
                truffleSource, 2, 1, 5, false, "outer message", inner);

        IPersistentMap d = err.getData();
        assertThat(d.valAt(Keyword.intern("clojure.error", "phase")))
                .isEqualTo(Compiler.CompilerException.PHASE_MACROEXPANSION);
        assertThat(d.valAt(Keyword.intern("clojure.error", "symbol")))
                .isEqualTo(Symbol.intern("user", "bad-macro"));
        assertThat(d.valAt(Keyword.intern("clojure.error", "class")))
                .isEqualTo(Symbol.intern(ArithmeticException.class.getName()));
    }

    @Test
    public void macroStackCollectsCompilerExceptionSymbolsAlongCauseChain() {
        Source truffleSource = Source.newBuilder("cloffle", "(ns y)", "stack.clj").build();
        Compiler.CompilerException inner = new Compiler.CompilerException(
                "stack.clj", 3, 1,
                Symbol.intern("user", "inner-m"),
                Compiler.CompilerException.PHASE_MACROEXPANSION,
                new RuntimeException("boom"));
        Compiler.CompilerException outer = new Compiler.CompilerException(
                "stack.clj", 1, 1,
                Symbol.intern("user", "outer-m"),
                Compiler.CompilerException.PHASE_MACROEXPANSION,
                inner);

        ClojureParseError err = new ClojureParseError(
                truffleSource, 1, 1, 3, false, "wrapped", outer);

        IPersistentMap d = err.getData();
        Object stack = d.valAt(Keyword.intern("clojure.error", "macro-stack"));
        assertThat(stack).isNotNull();
        assertThat(stack.toString()).contains("outer-m");
        assertThat(stack.toString()).contains("inner-m");
    }
}
