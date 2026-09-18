package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.core.filter.TokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson {@link ObjectMapper} / {@link JsonFactory} and pointer helpers for suites that need them.
 */
final class JsonParserJacksonSupport {

    final ObjectMapper mapper = new ObjectMapper();
    final JsonFactory factory = mapper.getFactory();

    static JsonPointer[] compilePointers(String... pointers) {
        JsonPointer[] compiled = new JsonPointer[pointers.length];
        for (int i = 0; i < pointers.length; i++) {
            compiled[i] = JsonPointer.compile(pointers[i]);
        }
        return compiled;
    }

    String filteredPointer(byte[] json, JsonPointer pointer) throws Exception {
        try (com.fasterxml.jackson.core.JsonParser raw = factory.createParser(json);
             com.fasterxml.jackson.core.JsonParser parser = new FilteringParserDelegate(
                     raw, new JsonPointerBasedFilter(pointer), TokenFilter.Inclusion.ONLY_INCLUDE_ALL,
                     false)) {
            JsonToken token = parser.nextToken();
            return token == null ? null : parser.getValueAsString();
        }
    }
}
