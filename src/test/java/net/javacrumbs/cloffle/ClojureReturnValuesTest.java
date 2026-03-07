package net.javacrumbs.cloffle;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests against real Clojure (via mikera.cljutils) to document what each
 * expression actually returns -- the ground truth that Cloffle should match.
 */
public class ClojureReturnValuesTest {

    private Object eval(String expression) {
        return mikera.cljutils.Clojure.eval(expression);
    }

    @Test
    public void defReturnsVar() {
        Object result = eval("(def myval-ret 42)");
        assertThat(result).isInstanceOf(clojure.lang.Var.class);
        assertThat(result.toString()).contains("myval-ret");
    }

    @Test
    public void defnReturnsVar() {
        Object result = eval("(defn myfn-ret [x] x)");
        assertThat(result).isInstanceOf(clojure.lang.Var.class);
        assertThat(result.toString()).contains("myfn-ret");
    }

    @Test
    public void defVarIsDereffable() {
        Object result = eval("(def myval-deref 42)");
        assertThat(result).isInstanceOf(clojure.lang.Var.class);
        clojure.lang.Var var = (clojure.lang.Var) result;
        assertThat(var.deref()).isEqualTo(42L);
    }

    @Test
    public void defnVarIsDereffable() {
        Object result = eval("(defn myfn-deref [x y] (+ x y))");
        assertThat(result).isInstanceOf(clojure.lang.Var.class);
        clojure.lang.Var var = (clojure.lang.Var) result;
        assertThat(var.deref()).isInstanceOf(clojure.lang.IFn.class);
    }

    @Test
    public void nilLiteral() {
        Object result = eval("nil");
        assertThat(result).isNull();
    }

    @Test
    public void ifReturningNil() {
        Object result = eval("(if false 1)");
        assertThat(result).isNull();
    }

    @Test
    public void letReturnsLastExpr() {
        Object result = eval("(let [a 5] a)");
        assertThat(result).isEqualTo(5L);
    }

    @Test
    public void doReturnsLastExpr() {
        Object result = eval("(do 1 2 3)");
        assertThat(result).isEqualTo(3L);
    }

    @Test
    public void fnReturnsFunction() {
        Object result = eval("(fn [x] x)");
        assertThat(result).isInstanceOf(clojure.lang.IFn.class);
    }

    @Test
    public void anonymousFnCall() {
        Object result = eval("((fn [x y] (+ x y)) 3 4)");
        assertThat(result).isEqualTo(7L);
    }

    @Test
    public void loopRecur() {
        Object result = eval("(loop [sum 0 cnt 5] (if (= cnt 0) sum (recur (+ cnt sum) (dec cnt))))");
        assertThat(result).isEqualTo(15L);
    }

    @Test
    public void instanceMethod() {
        Object result = eval("(.toUpperCase \"hello\")");
        assertThat(result).isEqualTo("HELLO");
    }

    @Test
    public void staticField() {
        Object result = eval("Math/PI");
        assertThat(result).isEqualTo(Math.PI);
    }
}
