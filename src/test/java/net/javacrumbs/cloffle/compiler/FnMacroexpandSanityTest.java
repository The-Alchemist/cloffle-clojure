package net.javacrumbs.cloffle.compiler;

import clojure.lang.Compiler;
import clojure.lang.Compiler.C;
import clojure.lang.IFn;
import clojure.lang.RT;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Minimal checks that {@link RT#var RT.var("clojure.core", "macroexpand")} turns user {@code (fn ...)} forms
 * into analyzable {@code fn*} (symbol params only). The bootstrap {@code fn} in {@code core.clj} is a stub
 * that only {@code (cons 'fn* decl)}; after {@link RT#init()} the real {@code defmacro fn} (destructuring)
 * must be installed — otherwise {@code (fn [[x]] ...)} hits {@code FnMethod.parse} with non-symbol params.
 */
public class FnMacroexpandSanityTest {

    private static IFn macroexpand;

    @BeforeClass
    public static void initCore() {
        RT.init();
        macroexpand = RT.var("clojure.core", "macroexpand");
    }

    @Test
    public void macroexpandFnWithVectorParamThenAnalyze() {
        Object form = RT.readString("(fn [[h]] h)");
        Object expanded = macroexpand.invoke(form);
        Compiler.analyze(C.EVAL, expanded);
    }

    @Test
    public void macroexpandNamedFnWithDestructuringThenAnalyze() {
        Object form = RT.readString("(fn f [[[h] & r]] (if h (cons h (f r)) nil))");
        Object expanded = macroexpand.invoke(form);
        Compiler.analyze(C.EVAL, expanded);
    }
}
