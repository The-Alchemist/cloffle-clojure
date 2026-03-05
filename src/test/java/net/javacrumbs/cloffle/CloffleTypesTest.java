package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that Cloffle correctly handles high-priority Clojure types
 * through the Polyglot boundary.
 */
public class CloffleTypesTest {

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

    private Value eval(String expression) {
        return context.eval("cloffle", expression);
    }

    // --- Keywords ---

    @Test
    public void keywordLiteral() {
        Value result = eval(":foo");
        assertThat(result.isString()).isTrue();
        assertThat(result.asString()).isEqualTo(":foo");
    }

    @Test
    public void namespacedKeyword() {
        Value result = eval(":my.ns/bar");
        assertThat(result.isString()).isTrue();
        assertThat(result.asString()).isEqualTo(":my.ns/bar");
    }

    // --- Vectors ---

    @Test
    public void vectorLiteral() {
        Value result = eval("[1 2 3]");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(3);
        assertThat(result.getArrayElement(0).asLong()).isEqualTo(1L);
        assertThat(result.getArrayElement(1).asLong()).isEqualTo(2L);
        assertThat(result.getArrayElement(2).asLong()).isEqualTo(3L);
    }

    @Test
    public void emptyVector() {
        Value result = eval("[]");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(0);
    }

    @Test
    public void vectorOfStrings() {
        Value result = eval("[\"a\" \"b\" \"c\"]");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(3);
        assertThat(result.getArrayElement(0).asString()).isEqualTo("a");
    }

    @Test
    public void vectorOfKeywords() {
        Value result = eval("[:a :b :c]");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(3);
        assertThat(result.getArrayElement(0).asString()).isEqualTo(":a");
    }

    @Test
    public void vectorToString() {
        Value result = eval("[1 2 3]");
        assertThat(result.toString()).isEqualTo("[1 2 3]");
    }

    // --- Maps ---

    @Test
    public void mapLiteral() {
        Value result = eval("{:a 1 :b 2}");
        assertThat(result.hasHashEntries()).isTrue();
        assertThat(result.getHashSize()).isEqualTo(2);
    }

    @Test
    public void emptyMap() {
        Value result = eval("{}");
        assertThat(result.hasHashEntries()).isTrue();
        assertThat(result.getHashSize()).isEqualTo(0);
    }

    @Test
    public void mapToString() {
        Value result = eval("{:a 1}");
        assertThat(result.toString()).isEqualTo("{:a 1}");
    }

    // --- Sets ---

    @Test
    public void setLiteral() {
        Value result = eval("#{1 2 3}");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(3);
    }

    @Test
    public void emptySet() {
        Value result = eval("#{}");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(0);
    }

    // --- Characters ---

    @Test
    public void charLiteral() {
        Value result = eval("\\a");
        assertThat(result.as(Object.class)).isEqualTo('a');
    }

    // --- Nested structures ---

    @Test
    public void nestedVectorInVector() {
        Value result = eval("[[1 2] [3 4]]");
        assertThat(result.hasArrayElements()).isTrue();
        assertThat(result.getArraySize()).isEqualTo(2);
        Value inner = result.getArrayElement(0);
        assertThat(inner.hasArrayElements()).isTrue();
        assertThat(inner.getArrayElement(0).asLong()).isEqualTo(1L);
    }

    @Test
    public void mapInVector() {
        Value result = eval("[{:a 1}]");
        assertThat(result.hasArrayElements()).isTrue();
        Value inner = result.getArrayElement(0);
        assertThat(inner.hasHashEntries()).isTrue();
    }
}
