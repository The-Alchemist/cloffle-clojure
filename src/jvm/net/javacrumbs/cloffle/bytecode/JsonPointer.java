package net.javacrumbs.cloffle.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * An RFC 6901 JSON Pointer, parsed once into tokens.
 *
 * <p>Each token keeps both its member name and, when the text also denotes a valid array index,
 * that index. Which of the two applies is a property of the JSON being traversed, not of the
 * pointer, so the decision is deferred to the scanner. This mirrors RapidJSON's
 * {@code Token { name, length, index }} with its {@code kPointerInvalidIndex} sentinel.
 */
public final class JsonPointer {

    /** Index value for a token that cannot be an array index. */
    public static final int NOT_AN_INDEX = -1;

    public static final class Token {
        public final String name;
        public final int index;

        Token(String name, int index) {
            this.name = name;
            this.index = index;
        }

        public boolean isIndex() {
            return index != NOT_AN_INDEX;
        }

        @Override
        public String toString() {
            return isIndex() ? name + "(#" + index + ")" : name;
        }
    }

    private static final Token[] NO_TOKENS = new Token[0];

    public final String source;
    public final Token[] tokens;

    private JsonPointer(String source, Token[] tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    /** Number of steps; zero means the pointer selects the whole document. */
    public int size() {
        return tokens.length;
    }

    public static JsonPointer parse(String pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("JSON Pointer must not be null");
        }
        if (pointer.isEmpty()) {
            return new JsonPointer(pointer, NO_TOKENS);
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "JSON Pointer must be empty or start with '/': " + pointer);
        }
        List<Token> tokens = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        int i = 1;
        int length = pointer.length();
        while (true) {
            if (i == length || pointer.charAt(i) == '/') {
                tokens.add(token(name.toString()));
                name.setLength(0);
                if (i == length) {
                    break;
                }
                i++;
                continue;
            }
            char c = pointer.charAt(i++);
            if (c == '~') {
                if (i == length) {
                    throw new IllegalArgumentException(
                            "JSON Pointer has a trailing '~': " + pointer);
                }
                char escaped = pointer.charAt(i++);
                if (escaped == '0') {
                    c = '~';
                } else if (escaped == '1') {
                    c = '/';
                } else {
                    throw new IllegalArgumentException(
                            "JSON Pointer escape must be ~0 or ~1, got ~" + escaped
                                    + " in " + pointer);
                }
            }
            name.append(c);
        }
        return new JsonPointer(pointer, tokens.toArray(new Token[0]));
    }

    /**
     * A token denotes an array index only when it is a run of digits with no leading zero. "01"
     * and "-" are member names; "-" in particular only means "append" in RFC 6901's mutating
     * operations, and this pointer is read-only.
     */
    private static Token token(String name) {
        int length = name.length();
        if (length == 0 || length > 10) {
            return new Token(name, NOT_AN_INDEX);
        }
        if (name.charAt(0) == '0' && length > 1) {
            return new Token(name, NOT_AN_INDEX);
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return new Token(name, NOT_AN_INDEX);
            }
            value = value * 10 + (c - '0');
        }
        if (value > Integer.MAX_VALUE) {
            return new Token(name, NOT_AN_INDEX);
        }
        return new Token(name, (int) value);
    }

    @Override
    public String toString() {
        return source;
    }
}
