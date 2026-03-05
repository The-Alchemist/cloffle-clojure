package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that exercise the Polyglot Context in a REPL-like fashion:
 * a single long-lived context evaluating multiple expressions sequentially.
 */
public class CloffleREPLTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    private Object eval(String expression) {
        Value result = context.eval("cloffle", expression);
        if (result.isHostObject()) {
            Object host = result.asHostObject();
            if (host instanceof net.javacrumbs.cloffle.nodes.value.NilNode.Nil) {
                return null;
            }
            return host;
        }
        return result.as(Object.class);
    }

    @Test
    public void simpleAddition() {
        assertThat(eval("(+ 1 2)")).isEqualTo(3L);
    }

    @Test
    public void stringResult() {
        assertThat(eval("(.toUpperCase \"hello\")")).isEqualTo("HELLO");
    }

    @Test
    public void ifExpression() {
        assertThat(eval("(if true 42 99)")).isEqualTo(42L);
    }

    @Test
    public void letBinding() {
        assertThat(eval("(let [x 10] (+ x 5))")).isEqualTo(15L);
    }

    @Test
    public void nestedLet() {
        assertThat(eval("(let [a 3.0] (+ (let [a 2] a) a))")).isEqualTo(5.0);
    }

    @Test
    public void doBlock() {
        assertThat(eval("(do (+ 1 1) (+ 2 3))")).isEqualTo(5L);
    }

    @Test
    public void anonymousFn() {
        assertThat(eval("((fn [a b] (+ a b)) 3 4)")).isEqualTo(7L);
    }

    @Test
    public void defnReturnsVar() {
        Value result = context.eval("cloffle", "(defn myadd [x y] (+ x y))");
        assertThat(result.toString()).contains("myadd");
    }

    @Test
    public void defReturnsVar() {
        Value result = context.eval("cloffle", "(def myval 42)");
        assertThat(result.toString()).contains("myval");
    }

    @Test
    public void defnThenCall() {
        assertThat(eval("(do (defn myfn [x y] (+ x y)) (myfn 3 7))")).isEqualTo(10L);
    }

    @Test
    public void defThenUse() {
        assertThat(eval("(do (def myval true) (if myval 1 2))")).isEqualTo(1L);
    }

    @Test
    public void fibonacci() {
        assertThat(eval("(do (defn fib [n] (if (< n 3) 1 (+ (fib (- n 1)) (fib (- n 2))))) (fib 10))")).isEqualTo(55L);
    }

    @Test
    public void loopRecur() {
        assertThat(eval(
            "(loop [sum 0 cnt 10] (if (= cnt 0) sum (recur (+ cnt sum) (dec cnt))))"
        )).isEqualTo(55L);
    }

    @Test
    public void staticField() {
        assertThat(eval("(Math/PI)")).isEqualTo(Math.PI);
    }

    @Test
    public void instanceMethod() {
        assertThat(eval("(.toUpperCase \"fred\")")).isEqualTo("FRED");
    }

    @Test
    public void nilLiteral() {
        assertThat(eval("(if nil 2.0 3.0)")).isEqualTo(3.0);
    }

    @Test
    public void fnWithClosure() {
        assertThat(eval("(let [a 5] ((fn [b] (+ a b)) 2))")).isEqualTo(7L);
    }

    @Test
    public void multipleArityFn() {
        assertThat(eval("((fn [a b c] (+ a b c)) 2 4 6)")).isEqualTo(12L);
    }

    @Test
    public void doubleArithmetic() {
        assertThat(eval("(+ 1 2 3.0)")).isEqualTo(6.0);
    }

    // --- Cross-eval tests (REPL-style: define in one eval, use in another) ---

    @Test
    public void defnThenCallAcrossEvals() {
        eval("(defn hello [] \"hello\")");
        assertThat(eval("(hello)")).isEqualTo("hello");
    }

    @Test
    public void defThenUseAcrossEvals() {
        eval("(def myconst 42)");
        assertThat(eval("myconst")).isEqualTo(42L);
    }

    @Test
    public void defnWithArgsAcrossEvals() {
        eval("(defn add3 [a b c] (+ a b c))");
        assertThat(eval("(add3 10 20 30)")).isEqualTo(60L);
    }

    @Test
    public void multipleDefsThenUse() {
        eval("(def x 10)");
        eval("(def y 20)");
        assertThat(eval("(+ x y)")).isEqualTo(30L);
    }

    @Test
    public void redefineVar() {
        eval("(def v 1)");
        assertThat(eval("v")).isEqualTo(1L);
        eval("(def v 2)");
        assertThat(eval("v")).isEqualTo(2L);
    }

    @Test
    public void defnCallsAnotherDefnAcrossEvals() {
        eval("(defn double-it [x] (+ x x))");
        eval("(defn quad [x] (double-it (double-it x)))");
        assertThat(eval("(quad 5)")).isEqualTo(20L);
    }

    // --- Keyword invoke on maps ---

    @Test
    public void keywordInvokeOnMapSameEval() {
        assertThat(eval("(do (def m {:a 1 :b 2}) (:a m))")).isEqualTo(1L);
    }

    @Test
    public void keywordInvokeOnMapAcrossEvals() {
        eval("(def m {:a 1 :b 2})");
        assertThat(eval("(:a m)")).isEqualTo(1L);
    }

    @Test
    public void keywordInvokeOtherKey() {
        eval("(def m {:a 1 :b 2})");
        assertThat(eval("(:b m)")).isEqualTo(2L);
    }
}
