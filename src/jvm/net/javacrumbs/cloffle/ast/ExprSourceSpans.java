package net.javacrumbs.cloffle.ast;

import clojure.lang.Compiler;
import clojure.lang.Compiler.*;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps Clojure compiler {@link Expr} positions and {@link Source} text to character spans.
 * Shared by the Truffle AST ({@link ExprToNode}) and bytecode ({@link net.javacrumbs.cloffle.bytecode.ExprToBytecode})
 * backends so stack traces and tooling see consistent ranges (balanced s-expressions when possible).
 */
public final class ExprSourceSpans {

    private ExprSourceSpans() {
    }

    /** Inclusive start offset in {@link Source#getCharacters()} and length in characters. */
    public record CharSpan(int start, int length) {
    }

    public static int charIndexForLineColumn(Source source, int line, int column) {
        if (source == null) {
            return -1;
        }
        try {
            int lineStart = source.getLineStartOffset(line);
            return lineStart + column - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Scans source text from the given char index to find the end of a
     * balanced s-expression (matching parens/brackets), respecting strings
     * and line comments. Returns the length including the closing
     * delimiter, or -1 if the form is not a paren/bracket/brace form.
     */
    public static int balancedFormLength(Source source, int start) {
        CharSequence text = source.getCharacters();
        if (start < 0 || start >= text.length()) {
            return -1;
        }
        char open = text.charAt(start);
        char close;
        if (open == '(') {
            close = ')';
        } else if (open == '[') {
            close = ']';
        } else if (open == '{') {
            close = '}';
        } else {
            return -1;
        }

        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                i++;
            } else if (c == ';') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i - start + 1;
                }
            }
        }
        return -1;
    }

    /**
     * Same rules as {@link ExprToNode}'s historical {@code applySourceFromExpr}: prefer a balanced
     * form starting at (line, column), otherwise a one-character section from Truffle {@link Source}.
     */
    public static Optional<CharSpan> computeCharSpanFromLineColumn(Source source, int line, int column) {
        if (source == null || line < 1 || column < 1) {
            return Optional.empty();
        }
        int charIndex = charIndexForLineColumn(source, line, column);
        if (charIndex < 0) {
            try {
                SourceSection ss = source.createSection(line, column, 1);
                return Optional.of(new CharSpan(ss.getCharIndex(), ss.getCharLength()));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        int len = balancedFormLength(source, charIndex);
        if (len > 0) {
            return Optional.of(new CharSpan(charIndex, len));
        }
        try {
            SourceSection ss = source.createSection(line, column, 1);
            return Optional.of(new CharSpan(ss.getCharIndex(), ss.getCharLength()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Span for the first line of a {@code def}/{@code defn} only (opening through end of that line).
     * <p>
     * {@link #computeCharSpanFromLineColumn(Source, int, int)} uses a balanced form from the opening
     * {@code (}; for a multi-line def that spans the entire form, so bytecode/DAP roots that use that
     * full span claim every body line and line breakpoints on e.g. the fn body jump to the def head.
     * Narrowing root wrappers to this head line matches {@code ExprToBytecode#emitDefExpr} and lets
     * inner per-expression sections win for breakpoint resolution.
     */
    public static Optional<SourceSection> defFormHeadSourceSection(Source source, DefExpr de) {
        if (source == null || de == null) {
            return Optional.empty();
        }
        int[] loc = extractLineColumn(de);
        if (loc[0] < 1 || loc[1] < 1) {
            return Optional.empty();
        }
        try {
            int lineLen = source.getLineLength(loc[0]);
            int headLen = Math.max(1, lineLen - loc[1] + 1);
            return Optional.of(source.createSection(loc[0], loc[1], headLen));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * {@link LocalBindingExpr} line/column often match the enclosing {@code let}/{@code fn} head because
     * reader metadata is not attached to bare symbols. When a source line is only {@code name} plus
     * closing parens (typical body tail), treat that as the reference site for debugger breakpoints.
     */
    public static Optional<int[]> localBindingReferenceLineColumn(Source source, LocalBindingExpr lbe) {
        int[] fb = extractLineColumn(lbe);
        if (source == null || lbe.b == null || lbe.b.sym == null) {
            return Optional.empty();
        }
        String name = lbe.b.sym.getName();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Pattern linePat = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\)+\\s*$");
        try {
            int max = source.getLineCount();
            for (int line = Math.max(1, fb[0]); line <= max; line++) {
                int lineStart = source.getLineStartOffset(line);
                int lineLen = source.getLineLength(line);
                CharSequence row = source.getCharacters().subSequence(lineStart, lineStart + lineLen);
                if (linePat.matcher(row).matches()) {
                    int idx = row.toString().indexOf(name);
                    int col = idx >= 0 ? idx + 1 : fb[1];
                    if (col >= 1) {
                        return Optional.of(new int[]{line, col});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public static int[] extractLineColumn(Expr expr) {
        if (expr instanceof InvokeExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof KeywordInvokeExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof IfExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof CaseExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof DefExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof FnExpr e) {
            return new int[]{e.line(), e.column()};
        }
        if (expr instanceof VarExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof LocalBindingExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof LetExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof LetFnExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof RecurExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof StaticMethodExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof StaticInvokeExpr sie && sie.args != null && sie.args.count() > 0) {
            return extractLineColumn((Expr) sie.args.nth(0));
        }
        if (expr instanceof InstanceMethodExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof InstanceFieldExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof StaticFieldExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof NewExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof TryExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof ThrowExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof MapExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof VectorExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof SetExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof ListExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof AssignExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof ImportExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof NewInstanceExpr e) {
            return new int[]{e.line(), e.column()};
        }
        if (expr instanceof MetaExpr me) {
            return extractLineColumn(me.expr);
        }
        if (expr instanceof InstanceOfExpr ioe) {
            return extractLineColumn(ioe.expr);
        }
        if (expr instanceof MonitorEnterExpr mee) {
            return extractLineColumn(mee.target);
        }
        if (expr instanceof MonitorExitExpr mee) {
            return extractLineColumn(mee.target);
        }
        if (expr instanceof QualifiedMethodExpr qme && qme.fieldOverload != null) {
            return extractLineColumn(qme.fieldOverload);
        }
        if (expr instanceof BodyExpr e && e.exprs().count() > 0) {
            return extractLineColumn((Expr) e.exprs().nth(0));
        }
        if (expr instanceof NumberExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof StringExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof KeywordExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof ConstantExpr e) {
            return new int[]{e.line, e.column};
        }
        if (expr instanceof EmptyExpr e) {
            return new int[]{e.line, e.column};
        }
        return extractFromExprValue(expr);
    }

    /**
     * For {@link Expr} types that do not expose line/column fields, use the compiler thread-local
     * {@code LINE_BEFORE} / {@code COLUMN_BEFORE} when available.
     */
    private static int[] extractFromExprValue(Expr expr) {
        try {
            int line = ((Number) Compiler.LINE_BEFORE.deref()).intValue();
            int column = ((Number) Compiler.COLUMN_BEFORE.deref()).intValue();
            if (line > 0 && column > 0) {
                return new int[]{line, column};
            }
        } catch (Exception ignored) {
        }
        return new int[]{-1, -1};
    }
}
