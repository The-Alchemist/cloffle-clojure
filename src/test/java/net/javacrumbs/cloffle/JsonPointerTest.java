package net.javacrumbs.cloffle;

import net.javacrumbs.cloffle.bytecode.JsonPointer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JsonPointerTest {

    private static JsonPointer.Token[] tokens(String pointer) {
        return JsonPointer.parse(pointer).tokens;
    }

    @Test
    public void emptyPointerSelectsWholeDocument() {
        assertEquals(0, JsonPointer.parse("").size());
    }

    @Test
    public void singleSlashSelectsTheEmptyName() {
        JsonPointer.Token[] tokens = tokens("/");
        assertEquals(1, tokens.length);
        assertEquals("", tokens[0].name);
        assertFalse(tokens[0].isIndex());
    }

    @Test
    public void splitsOnSlash() {
        JsonPointer.Token[] tokens = tokens("/data/attributes/title");
        assertEquals(3, tokens.length);
        assertEquals("data", tokens[0].name);
        assertEquals("attributes", tokens[1].name);
        assertEquals("title", tokens[2].name);
    }

    @Test
    public void decodesEscapes() {
        assertEquals("a/b", tokens("/a~1b")[0].name);
        assertEquals("m~n", tokens("/m~0n")[0].name);
        // "~01" decodes to "~1", not to "/", because decoding is a single left-to-right pass.
        assertEquals("~1", tokens("/~01")[0].name);
    }

    @Test
    public void rejectsInvalidEscapes() {
        for (String bad : new String[] {"/a~b", "/a~", "/~2"}) {
            try {
                JsonPointer.parse(bad);
                fail("expected a parse failure for " + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void rejectsPointersThatDoNotStartWithSlash() {
        try {
            JsonPointer.parse("data/id");
            fail("expected a parse failure");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void classifiesNumericTokensAsBothNameAndIndex() {
        JsonPointer.Token zero = tokens("/0")[0];
        assertTrue(zero.isIndex());
        assertEquals(0, zero.index);
        assertEquals("0", zero.name);

        JsonPointer.Token big = tokens("/123")[0];
        assertTrue(big.isIndex());
        assertEquals(123, big.index);
    }

    @Test
    public void leadingZeroAndMinusAndOverflowAreNamesOnly() {
        assertFalse(tokens("/01")[0].isIndex());
        assertFalse(tokens("/-")[0].isIndex());
        assertFalse(tokens("/1e3")[0].isIndex());
        assertFalse(tokens("/99999999999")[0].isIndex());
    }

    @Test
    public void mixesNamesAndIndexes() {
        JsonPointer.Token[] tokens = tokens("/statuses/0/user/screen_name");
        assertEquals(4, tokens.length);
        assertFalse(tokens[0].isIndex());
        assertTrue(tokens[1].isIndex());
        assertEquals(0, tokens[1].index);
        assertEquals("screen_name", tokens[3].name);
    }
}
