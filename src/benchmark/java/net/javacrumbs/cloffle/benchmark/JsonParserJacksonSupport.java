package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.core.filter.TokenFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import clojure.lang.Keyword;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.Map;

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

    /** Keywordize Jackson tree nodes so {@link JsonParserBenchmarkBase#assertEquiv} can compare fairly. */
    static Object toKeywordized(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            Object[] kvs = new Object[object.size() * 2];
            int i = 0;
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                kvs[i++] = Keyword.intern(e.getKey());
                kvs[i++] = toKeywordized(e.getValue());
            }
            return RT.map(kvs);
        }
        if (node.isArray()) {
            Object[] values = new Object[node.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = toKeywordized(node.get(i));
            }
            return RT.vector(values);
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? RT.T : RT.F;
        }
        return node.textValue();
    }
}
