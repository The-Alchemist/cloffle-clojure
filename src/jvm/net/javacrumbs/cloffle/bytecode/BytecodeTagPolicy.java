package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler.DefExpr;
import clojure.lang.Compiler.Expr;
import clojure.lang.Compiler.FnExpr;
import clojure.lang.Compiler.TheVarExpr;
import clojure.lang.Compiler.VarExpr;

/**
 * Centralizes debugger tagging decisions for the bytecode backend.
 *
 * <p>Truffle line breakpoints match nodes with {@code StatementTag}. To mimic Java/Python/JS
 * debugger UX where definitions are skipped and only runtime statements halt, this policy
 * classifies each {@link Expr} and decides whether it should carry {@code StatementTag}
 * at the top level.
 *
 * <p>All tag-policy questions — "is this a definition?", "should this inhibit nested tags?",
 * "is this a runtime statement?" — should be answered here rather than inline in
 * {@link ExprToBytecode} or AST node {@code hasTag} methods.
 */
public final class BytecodeTagPolicy {

    private BytecodeTagPolicy() {}

    /**
     * Broad classification of a top-level form for debugger purposes.
     */
    public enum FormKind {
        /** {@code (defn f [x] ...)} or {@code (defmacro m [x] ...)} — function/macro installation. */
        FN_DEFINITION,
        /** {@code (def x 10)} — simple var binding with a non-fn init or no init. */
        SIMPLE_DEF,
        /** {@code (f arg)}, {@code (.method obj)}, {@code (new Foo)} — runtime invocations. */
        CALL,
        /** {@code let}, {@code do}, {@code if}, {@code try}, {@code case}, etc. */
        COMPOUND,
        /** Constants, nil, keywords, collections — pure values with no side effects. */
        LITERAL
    }

    /**
     * Classify an analyzed {@link Expr} into a {@link FormKind}.
     */
    public static FormKind classify(Expr expr) {
        if (expr instanceof DefExpr de) {
            if (de.initProvided && de.init instanceof FnExpr) {
                return FormKind.FN_DEFINITION;
            }
            return FormKind.SIMPLE_DEF;
        }
        if (expr instanceof clojure.lang.Compiler.InvokeExpr
                || expr instanceof clojure.lang.Compiler.StaticInvokeExpr
                || expr instanceof clojure.lang.Compiler.StaticMethodExpr
                || expr instanceof clojure.lang.Compiler.InstanceMethodExpr
                || expr instanceof clojure.lang.Compiler.KeywordInvokeExpr
                || expr instanceof clojure.lang.Compiler.NewExpr
                || expr instanceof clojure.lang.Compiler.NewInstanceExpr) {
            return FormKind.CALL;
        }
        if (expr instanceof clojure.lang.Compiler.LetExpr
                || expr instanceof clojure.lang.Compiler.BodyExpr
                || expr instanceof clojure.lang.Compiler.IfExpr
                || expr instanceof clojure.lang.Compiler.TryExpr
                || expr instanceof clojure.lang.Compiler.CaseExpr
                || expr instanceof clojure.lang.Compiler.LetFnExpr
                || expr instanceof clojure.lang.Compiler.RecurExpr
                || expr instanceof clojure.lang.Compiler.ThrowExpr) {
            return FormKind.COMPOUND;
        }
        return FormKind.LITERAL;
    }

    /**
     * Whether a top-level form should be a debugger "statement" — i.e., whether a line
     * breakpoint on that line should fire and step-over should stop there.
     *
     * <p>Returns {@code false} for {@link FormKind#FN_DEFINITION} and {@link FormKind#LITERAL},
     * matching Java/Python/JS where function definitions do not halt the debugger at load time
     * and bare literals are not steppable.
     */
    public static boolean isRuntimeStatement(Expr expr) {
        FormKind kind = classify(expr);
        return kind != FormKind.FN_DEFINITION && kind != FormKind.LITERAL;
    }

    /**
     * Whether a {@link DefExpr}'s head section should carry {@code StatementTag} in the
     * bytecode root. Returns {@code true} for simple defs ({@code (def x 10)}), {@code false}
     * for defn/defmacro shapes where the init is a {@link FnExpr}.
     */
    public static boolean defHeadIsStatement(DefExpr de) {
        return !(de.initProvided && de.init instanceof FnExpr);
    }

    /**
     * Whether nested tags should be inhibited for a def's init expression.
     * For simple defs whose init sits on the same line as the {@code (def} head,
     * we suppress inner {@code StatementTag} so the whole def is one debugger step.
     * For {@code (def x (fn* ...))} the fn body should keep its own tags.
     */
    public static boolean inhibitDefInitTags(DefExpr de) {
        return de.initProvided && !(de.init instanceof FnExpr);
    }

    /**
     * Whether an expression's tags should be suppressed when it appears as the
     * callee or argument of an {@code InvokeExpr}. Prevents duplicate line-breakpoint
     * halts from a {@link VarExpr} / {@link TheVarExpr} that shares a source line
     * with the outer invoke.
     */
    public static boolean inhibitCalleeArgTags(Expr expr) {
        return expr instanceof VarExpr || expr instanceof TheVarExpr;
    }
}
