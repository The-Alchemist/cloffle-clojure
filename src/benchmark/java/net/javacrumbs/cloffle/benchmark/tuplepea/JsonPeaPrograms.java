package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.Keyword;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;

import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.arg;
import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.nth;
import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.parseBytes;
import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.parseBytesBoundary;
import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.projectTyped;
import static net.javacrumbs.cloffle.benchmark.tuplepea.Pea.valAt;

/**
 * {@code json:...} programs. Argument 0 is {@code byte[]}. Keywords / plans are
 * {@code @CompilationFinal} on the AST (not Cloffle constants).
 */
public final class JsonPeaPrograms {

    private static final Keyword DATA = Keyword.intern("data");
    private static final Keyword ATTRIBUTES = Keyword.intern("attributes");
    private static final Keyword TITLE = Keyword.intern("title");
    private static final Keyword EMAIL = Keyword.intern("email");
    private static final Keyword NAME = Keyword.intern("name");

    private JsonPeaPrograms() {
    }

    public static RootNode createRoot(TuplePeaLanguage language, String source) {
        TuplePeaNodes.Expr body = build(source);
        String name = source.contains(":") ? source.substring(0, source.indexOf(':')) : source;
        return new TuplePeaRootNode(language, "json:" + name, body);
    }

    private static TuplePeaNodes.Expr build(String source) {
        String[] parts = source.split(":", 2);
        String name = parts[0];
        String fixture = parts.length == 2 ? parts[1] : null;
        TuplePeaNodes.Expr json = arg(0);
        return switch (name) {
            case "parseBytes" -> parseBytes(json);
            case "parseBytesBoundary" -> parseBytesBoundary(json);
            case "parseLookupJsonapi" -> valAt(valAt(valAt(parseBytes(json), DATA), ATTRIBUTES), TITLE);
            case "parseLookupJsonapiBoundary" ->
                    valAt(valAt(valAt(parseBytesBoundary(json), DATA), ATTRIBUTES), TITLE);
            case "parseLookupEntity16" -> valAt(parseBytes(json), EMAIL);
            case "parseLookupRows" -> valAt(nth(parseBytes(json), 3), NAME);
            case "parseLookupPlaceholder" -> valAt(parseBytes(json), TITLE);
            case "projectTyped" -> projectTyped(json, plan(fixture));
            default -> throw new IllegalArgumentException("Unknown json pea program: " + source);
        };
    }

    private static JsonTypedProjectPlan plan(String fixture) {
        if (fixture == null) {
            throw new IllegalArgumentException("json:projectTyped requires a fixture name");
        }
        Object schema = switch (fixture) {
            case "placeholder" -> JsonTypedSchemas.placeholder();
            case "jsonapi" -> JsonTypedSchemas.jsonapi();
            case "github" -> JsonTypedSchemas.github();
            case "twitterFirst" -> JsonTypedSchemas.twitterFirst();
            case "twitterNested" -> JsonTypedSchemas.twitterNested();
            case "popularApis" -> JsonTypedSchemas.popularApis();
            default -> throw new IllegalArgumentException("Unknown json fixture: " + fixture);
        };
        return JsonTypedProjectPlan.compile(null, schema);
    }
}
