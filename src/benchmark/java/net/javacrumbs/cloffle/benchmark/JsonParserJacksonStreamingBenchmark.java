package net.javacrumbs.cloffle.benchmark;

import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentTuple;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
/**
 * Jackson pull-parser streaming baselines (fairness controls for Cloffle typed extract).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserJacksonStreamingBenchmark extends JsonParserBenchmarkBase {

    static void assertTypedParity(JsonParserBenchmarkBase b) throws Exception {
        JsonParserJacksonStreamingBenchmark ref = new JsonParserJacksonStreamingBenchmark();
        copyBenchmarkState(b, ref);
        assertEquiv("placeholder", b.guestTypedPlaceholderBytes.invoke(), ref.jacksonStreamingPlaceholderShapeMap());
        assertEquiv("jsonapi", b.guestTypedJsonapiBytes.invoke(), ref.jacksonStreamingJsonapiShapeMap());
        assertEquiv("github", b.guestTypedGithubBytes.invoke(), ref.jacksonStreamingGithubShapeMap());
        assertEquiv("twitter", b.guestTypedTwitterFirstBytes.invoke(), ref.jacksonStreamingTwitterFirstShapeMap());
        assertEquiv("popular-apis", b.guestTypedPopularApisBytes.invoke(), ref.jacksonStreamingPopularApisShapeMap());
    }

    private static void copyBenchmarkState(JsonParserBenchmarkBase from, JsonParserBenchmarkBase to)
            throws Exception {
        for (var field : JsonParserBenchmarkBase.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            field.set(to, field.get(from));
        }
    }

    @Benchmark
    public ObjectNode jacksonStreamingProjectGithubBytes() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    continue;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "full_name" -> fullName = parser.getValueAsString();
                    case "stargazers_count" -> stars = parser.getIntValue();
                    case "open_issues_count" -> issues = parser.getIntValue();
                    case "login" -> {
                        if (login == null) {
                            login = parser.getValueAsString();
                        }
                    }
                    default -> {
                        if (value == JsonToken.START_ARRAY) {
                            parser.skipChildren();
                        }
                    }
                }
            }
        }
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.put("login", login);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("full_name", fullName);
        out.put("stargazers_count", stars);
        out.put("open_issues_count", issues);
        out.set("owner", owner);
        return out;
    }

    @Benchmark
    public String jacksonStreamingGithubEarlyBytes() throws Exception {
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            return jacksonStreamingRootString(parser, "full_name");
        }
    }

    @Benchmark
    public int jacksonStreamingGithubLateBytes() throws Exception {
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            return jacksonStreamingRootInt(parser, "network_count");
        }
    }

    @Benchmark
    public ObjectNode jacksonStreamingGithubFirstWinsBytes() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        int remaining = 4;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "full_name" -> {
                        fullName = parser.getValueAsString();
                        remaining--;
                    }
                    case "stargazers_count" -> {
                        stars = parser.getIntValue();
                        remaining--;
                    }
                    case "open_issues_count" -> {
                        issues = parser.getIntValue();
                        remaining--;
                    }
                    case "owner" -> {
                        login = jacksonStreamingObjectString(parser, "login");
                        if (login != null) {
                            remaining--;
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.put("login", login);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("full_name", fullName);
        out.put("stargazers_count", stars);
        out.put("open_issues_count", issues);
        out.set("owner", owner);
        return out;
    }

    /**
     * Streaming counterpart to {@code guestTypedJsonapiBytes}: data.id, data.attributes.title,
     * meta.request-id, first-key-wins.
     */
    @Benchmark
    public ObjectNode jacksonStreamingJsonapiBytes() throws Exception {
        String id = null;
        String title = null;
        String requestId = null;
        int remaining = 2;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(jsonapiBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "data" -> {
                        String[] pair = jacksonStreamingJsonapiData(parser);
                        id = pair[0];
                        title = pair[1];
                        remaining--;
                    }
                    case "meta" -> {
                        requestId = jacksonStreamingObjectString(parser, "request-id");
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode attributes = jacksonMapper.createObjectNode();
        attributes.put("title", title);
        ObjectNode data = jacksonMapper.createObjectNode();
        data.put("id", id);
        data.set("attributes", attributes);
        ObjectNode meta = jacksonMapper.createObjectNode();
        meta.put("request-id", requestId);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("data", data);
        out.set("meta", meta);
        return out;
    }

    /**
     * Streaming counterpart to {@code guestTypedPlaceholderBytes}: id, userId, title, first-key-wins.
     */
    @Benchmark
    public ObjectNode jacksonStreamingPlaceholderBytes() throws Exception {
        int id = 0;
        int userId = 0;
        String title = null;
        int remaining = 3;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(placeholderBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        id = parser.getIntValue();
                        remaining--;
                    }
                    case "userId" -> {
                        userId = parser.getIntValue();
                        remaining--;
                    }
                    case "title" -> {
                        title = parser.getValueAsString();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("id", id);
        out.put("userId", userId);
        out.put("title", title);
        return out;
    }

    // ── Fractional literals: the Eisel-Lemire fast path against Jackson ──────────────

    @Benchmark
    public double jacksonStreamingDoublesBytes() throws Exception {
        double sum = 0;
        int remaining = 8;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(doublesBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "lat", "lon", "altitude", "speed", "heading", "accuracy", "temp_c",
                         "humidity" -> {
                        sum += parser.getDoubleValue();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return sum;
    }

    // ── simdjson-java: SIMD structural index plus schema-directed walk ────────────────

    @Benchmark
    public ObjectNode jacksonStreamingTwitterFirstBytes() throws Exception {
        long id = 0L;
        String text = null;
        String screenName = null;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(twitterBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("statuses".equals(field)) {
                    long[] idSlot = new long[1];
                    String[] strings = jacksonStreamingFirstStatus(parser, idSlot);
                    id = idSlot[0];
                    text = strings[0];
                    screenName = strings[1];
                    break;
                }
                parser.skipChildren();
            }
        }
        ObjectNode user = jacksonMapper.createObjectNode();
        user.put("screen_name", screenName);
        ObjectNode status = jacksonMapper.createObjectNode();
        status.put("id", id);
        status.put("text", text);
        status.set("user", user);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.putArray("statuses").add(status);
        return out;
    }



    
    @Benchmark
    public long jacksonStreamingGithubConsume() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        int remaining = 4;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "full_name" -> {
                        fullName = parser.getValueAsString();
                        remaining--;
                    }
                    case "stargazers_count" -> {
                        stars = parser.getIntValue();
                        remaining--;
                    }
                    case "open_issues_count" -> {
                        issues = parser.getIntValue();
                        remaining--;
                    }
                    case "owner" -> {
                        login = jacksonStreamingObjectString(parser, "login");
                        if (login != null) {
                            remaining--;
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return mix(mix(mix(mix(1L, fullName), stars), issues), login);
    }

    @Benchmark
    public PersistentShapeMap jacksonStreamingGithubShapeMap() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        int remaining = 4;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "full_name" -> {
                        fullName = parser.getValueAsString();
                        remaining--;
                    }
                    case "stargazers_count" -> {
                        stars = parser.getIntValue();
                        remaining--;
                    }
                    case "open_issues_count" -> {
                        issues = parser.getIntValue();
                        remaining--;
                    }
                    case "owner" -> {
                        login = jacksonStreamingObjectString(parser, "login");
                        if (login != null) {
                            remaining--;
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return sm(githubRootShape, fullName, stars, issues,
                sm(githubOwnerShape, login, null, null, null, null, null, null, null),
                null, null, null, null);
    }

    @Benchmark
    public long jacksonStreamingJsonapiConsume() throws Exception {
        String id = null;
        String title = null;
        String requestId = null;
        int remaining = 2;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(jsonapiBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "data" -> {
                        String[] pair = jacksonStreamingJsonapiData(parser);
                        id = pair[0];
                        title = pair[1];
                        remaining--;
                    }
                    case "meta" -> {
                        requestId = jacksonStreamingObjectString(parser, "request-id");
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return mix(mix(mix(1L, id), title), requestId);
    }

    @Benchmark
    public PersistentShapeMap jacksonStreamingJsonapiShapeMap() throws Exception {
        String id = null;
        String title = null;
        String requestId = null;
        int remaining = 2;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(jsonapiBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "data" -> {
                        String[] pair = jacksonStreamingJsonapiData(parser);
                        id = pair[0];
                        title = pair[1];
                        remaining--;
                    }
                    case "meta" -> {
                        requestId = jacksonStreamingObjectString(parser, "request-id");
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return sm(jsonapiRootShape,
                sm(jsonapiDataShape, id,
                        sm(jsonapiAttrShape, title, null, null, null, null, null, null, null),
                        null, null, null, null, null, null),
                sm(jsonapiMetaShape, requestId, null, null, null, null, null, null, null),
                null, null, null, null, null, null);
    }

    @Benchmark
    public long jacksonStreamingPlaceholderConsume() throws Exception {
        int id = 0;
        int userId = 0;
        String title = null;
        int remaining = 3;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(placeholderBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        id = parser.getIntValue();
                        remaining--;
                    }
                    case "userId" -> {
                        userId = parser.getIntValue();
                        remaining--;
                    }
                    case "title" -> {
                        title = parser.getValueAsString();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return mix(mix(mix(1L, id), userId), title);
    }

    @Benchmark
    public PersistentShapeMap jacksonStreamingPlaceholderShapeMap() throws Exception {
        int id = 0;
        int userId = 0;
        String title = null;
        int remaining = 3;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(placeholderBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        id = parser.getIntValue();
                        remaining--;
                    }
                    case "userId" -> {
                        userId = parser.getIntValue();
                        remaining--;
                    }
                    case "title" -> {
                        title = parser.getValueAsString();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return sm(placeholderShape, id, userId, title, null, null, null, null, null);
    }

    @Benchmark
    public ObjectNode jacksonStreamingPopularApisBytes() throws Exception {
        String id = null;
        boolean livemode = false;
        long created = 0L;
        String dataType = null;
        String dataId = null;
        String title = null;
        int amountCents = 0;
        double feeRate = 0.0;
        String fullName = null;
        int stargazers = 0;
        boolean repoPrivate = false;
        double lat = 0.0;
        double lon = 0.0;
        String sku0 = null;
        String sku1 = null;
        int qty0 = 0;
        int qty1 = 0;
        double unit0 = 0.0;
        double unit1 = 0.0;
        String requestId = null;
        String version = null;
        int remaining = 8;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(popularApisBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        id = parser.getValueAsString();
                        remaining--;
                    }
                    case "livemode" -> {
                        livemode = parser.getBooleanValue();
                        remaining--;
                    }
                    case "created" -> {
                        created = parser.getLongValue();
                        remaining--;
                    }
                    case "data" -> {
                        String[] data = jacksonStreamingPopularApisData(parser);
                        dataType = data[0];
                        dataId = data[1];
                        title = data[2];
                        amountCents = Integer.parseInt(data[3]);
                        feeRate = Double.parseDouble(data[4]);
                        remaining--;
                    }
                    case "repository" -> {
                        String[] repo = jacksonStreamingPopularApisRepository(parser);
                        fullName = repo[0];
                        stargazers = Integer.parseInt(repo[1]);
                        repoPrivate = Boolean.parseBoolean(repo[2]);
                        remaining--;
                    }
                    case "geo" -> {
                        double[] coords = jacksonStreamingPopularApisGeo(parser);
                        lat = coords[0];
                        lon = coords[1];
                        remaining--;
                    }
                    case "line_items" -> {
                        String[][] items = jacksonStreamingPopularApisLineItems(parser);
                        sku0 = items[0][0];
                        qty0 = Integer.parseInt(items[0][1]);
                        unit0 = Double.parseDouble(items[0][2]);
                        sku1 = items[1][0];
                        qty1 = Integer.parseInt(items[1][1]);
                        unit1 = Double.parseDouble(items[1][2]);
                        remaining--;
                    }
                    case "meta" -> {
                        String[] metaFields = jacksonStreamingPopularApisMeta(parser);
                        requestId = metaFields[0];
                        version = metaFields[1];
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode attributes = jacksonMapper.createObjectNode();
        attributes.put("title", title);
        attributes.put("amount_cents", amountCents);
        attributes.put("fee_rate", feeRate);
        ObjectNode data = jacksonMapper.createObjectNode();
        data.put("type", dataType);
        data.put("id", dataId);
        data.set("attributes", attributes);
        ObjectNode repository = jacksonMapper.createObjectNode();
        repository.put("full_name", fullName);
        repository.put("stargazers_count", stargazers);
        repository.put("private", repoPrivate);
        ObjectNode geo = jacksonMapper.createObjectNode();
        geo.put("lat", lat);
        geo.put("lon", lon);
        ObjectNode item0 = jacksonMapper.createObjectNode();
        item0.put("sku", sku0);
        item0.put("quantity", qty0);
        item0.put("unit_amount", unit0);
        ObjectNode item1 = jacksonMapper.createObjectNode();
        item1.put("sku", sku1);
        item1.put("quantity", qty1);
        item1.put("unit_amount", unit1);
        ObjectNode meta = jacksonMapper.createObjectNode();
        meta.put("request_id", requestId);
        meta.put("version", version);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("id", id);
        out.put("livemode", livemode);
        out.put("created", created);
        out.set("data", data);
        out.set("repository", repository);
        out.set("geo", geo);
        out.putArray("line_items").add(item0).add(item1);
        out.set("meta", meta);
        return out;
    }

    @Benchmark
    public long jacksonStreamingPopularApisConsume() throws Exception {
        fillPopularApis();
        PopularLocals s = popularLocals;
        long h = 1L;
        h = mix(h, s.id);
        h = mix(h, s.livemode);
        h = mix(h, s.created);
        h = mix(h, s.dataType);
        h = mix(h, s.dataId);
        h = mix(h, s.title);
        h = mix(h, s.amountCents);
        h = mix(h, s.feeRate);
        h = mix(h, s.fullName);
        h = mix(h, s.stargazers);
        h = mix(h, s.repoPrivate);
        h = mix(h, s.lat);
        h = mix(h, s.lon);
        h = mix(h, s.sku0);
        h = mix(h, s.qty0);
        h = mix(h, s.unit0);
        h = mix(h, s.sku1);
        h = mix(h, s.qty1);
        h = mix(h, s.unit1);
        h = mix(h, s.requestId);
        h = mix(h, s.version);
        return h;
    }

    @Benchmark
    public PersistentShapeMap jacksonStreamingPopularApisShapeMap() throws Exception {
        fillPopularApis();
        PopularLocals s = popularLocals;
        return sm(popularRootShape,
                s.id, s.livemode, s.created,
                sm(popularDataShape, s.dataType, s.dataId,
                        sm(popularAttrShape, s.title, s.amountCents, s.feeRate,
                                null, null, null, null, null),
                        null, null, null, null, null),
                sm(popularRepoShape, s.fullName, s.stargazers, s.repoPrivate,
                        null, null, null, null, null),
                sm(popularGeoShape, s.lat, s.lon, null, null, null, null, null, null),
                PersistentTuple.create(
                        sm(popularItemShape, s.sku0, s.qty0, s.unit0, null, null, null, null, null),
                        sm(popularItemShape, s.sku1, s.qty1, s.unit1, null, null, null, null, null)),
                sm(popularMetaShape, s.requestId, s.version, null, null, null, null, null, null));
    }

    @Benchmark
    public long jacksonStreamingTwitterFirstConsume() throws Exception {
        long id = 0L;
        String text = null;
        String screenName = null;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(twitterBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("statuses".equals(field)) {
                    long[] idSlot = new long[1];
                    String[] strings = jacksonStreamingFirstStatus(parser, idSlot);
                    id = idSlot[0];
                    text = strings[0];
                    screenName = strings[1];
                    break;
                }
                parser.skipChildren();
            }
        }
        return mix(mix(mix(1L, id), text), screenName);
    }

    @Benchmark
    public PersistentShapeMap jacksonStreamingTwitterFirstShapeMap() throws Exception {
        long id = 0L;
        String text = null;
        String screenName = null;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(twitterBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("statuses".equals(field)) {
                    long[] idSlot = new long[1];
                    String[] strings = jacksonStreamingFirstStatus(parser, idSlot);
                    id = idSlot[0];
                    text = strings[0];
                    screenName = strings[1];
                    break;
                }
                parser.skipChildren();
            }
        }
        return sm(twitterRootShape,
                PersistentTuple.create(sm(twitterStatusShape, id, text,
                        sm(twitterUserShape, screenName, null, null, null, null, null, null, null),
                        null, null, null, null, null)),
                null, null, null, null, null, null, null);
    }

    private static String[] jacksonStreamingPopularApisData(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String type = null;
        String id = null;
        String title = null;
        int amountCents = 0;
        double feeRate = 0.0;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null, null, "0", "0.0"};
        }
        int remaining = 3;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "type" -> {
                    type = parser.getValueAsString();
                    remaining--;
                }
                case "id" -> {
                    id = parser.getValueAsString();
                    remaining--;
                }
                case "attributes" -> {
                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                        int attrRemaining = 3;
                        while (attrRemaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                            String attr = parser.currentName();
                            parser.nextToken();
                            switch (attr) {
                                case "title" -> {
                                    title = parser.getValueAsString();
                                    attrRemaining--;
                                }
                                case "amount_cents" -> {
                                    amountCents = parser.getIntValue();
                                    attrRemaining--;
                                }
                                case "fee_rate" -> {
                                    feeRate = parser.getDoubleValue();
                                    attrRemaining--;
                                }
                                default -> parser.skipChildren();
                            }
                        }
                        skipRestOfObject(parser);
                    } else {
                        parser.skipChildren();
                    }
                    remaining--;
                }
                default -> parser.skipChildren();
            }
            if (remaining == 0) {
                skipRestOfObject(parser);
                break;
            }
        }
        return new String[] {
                type, id, title, Integer.toString(amountCents), Double.toString(feeRate)};
    }

    private static String[] jacksonStreamingPopularApisRepository(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String fullName = null;
        int stargazers = 0;
        boolean repoPrivate = false;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, "0", "false"};
        }
        int remaining = 3;
        while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "full_name" -> {
                    fullName = parser.getValueAsString();
                    remaining--;
                }
                case "stargazers_count" -> {
                    stargazers = parser.getIntValue();
                    remaining--;
                }
                case "private" -> {
                    repoPrivate = parser.getBooleanValue();
                    remaining--;
                }
                default -> parser.skipChildren();
            }
        }
        skipRestOfObject(parser);
        return new String[] {fullName, Integer.toString(stargazers), Boolean.toString(repoPrivate)};
    }

    private static double[] jacksonStreamingPopularApisGeo(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        double lat = 0.0;
        double lon = 0.0;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new double[] {0.0, 0.0};
        }
        int remaining = 2;
        while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "lat" -> {
                    lat = parser.getDoubleValue();
                    remaining--;
                }
                case "lon" -> {
                    lon = parser.getDoubleValue();
                    remaining--;
                }
                default -> parser.skipChildren();
            }
        }
        skipRestOfObject(parser);
        return new double[] {lat, lon};
    }

    private static String[][] jacksonStreamingPopularApisLineItems(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String[][] items = {
                new String[] {null, "0", "0.0"},
                new String[] {null, "0", "0.0"}
        };
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return items;
        }
        int index = 0;
        while (index < 2 && parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            int remaining = 3;
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "sku" -> {
                        items[index][0] = parser.getValueAsString();
                        remaining--;
                    }
                    case "quantity" -> {
                        items[index][1] = Integer.toString(parser.getIntValue());
                        remaining--;
                    }
                    case "unit_amount" -> {
                        items[index][2] = Double.toString(parser.getDoubleValue());
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
            skipRestOfObject(parser);
            index++;
        }
        parser.skipChildren();
        return items;
    }

    private static String[] jacksonStreamingPopularApisMeta(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String requestId = null;
        String version = null;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        int remaining = 2;
        while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "request_id" -> {
                    requestId = parser.getValueAsString();
                    remaining--;
                }
                case "version" -> {
                    version = parser.getValueAsString();
                    remaining--;
                }
                default -> parser.skipChildren();
            }
        }
        skipRestOfObject(parser);
        return new String[] {requestId, version};
    }

    private static void skipRestOfObject(com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.END_OBJECT || token == null) {
            return;
        }
        if (token == JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (token != JsonToken.END_OBJECT && token != null) {
            if (token == JsonToken.FIELD_NAME) {
                parser.nextToken();
                parser.skipChildren();
            }
            token = parser.nextToken();
        }
    }

    private void fillPopularApis() throws Exception {
        PopularLocals s = popularLocals;
        s.id = null;
        s.livemode = false;
        s.created = 0L;
        s.dataType = null;
        s.dataId = null;
        s.title = null;
        s.amountCents = 0;
        s.feeRate = 0.0;
        s.fullName = null;
        s.stargazers = 0;
        s.repoPrivate = false;
        s.lat = 0.0;
        s.lon = 0.0;
        s.sku0 = null;
        s.sku1 = null;
        s.qty0 = 0;
        s.qty1 = 0;
        s.unit0 = 0.0;
        s.unit1 = 0.0;
        s.requestId = null;
        s.version = null;
        int remaining = 8;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(popularApisBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        s.id = parser.getValueAsString();
                        remaining--;
                    }
                    case "livemode" -> {
                        s.livemode = parser.getBooleanValue();
                        remaining--;
                    }
                    case "created" -> {
                        s.created = parser.getLongValue();
                        remaining--;
                    }
                    case "data" -> {
                        fillPopularApisData(parser, s);
                        remaining--;
                    }
                    case "repository" -> {
                        fillPopularApisRepository(parser, s);
                        remaining--;
                    }
                    case "geo" -> {
                        fillPopularApisGeo(parser, s);
                        remaining--;
                    }
                    case "line_items" -> {
                        fillPopularApisLineItems(parser, s);
                        remaining--;
                    }
                    case "meta" -> {
                        fillPopularApisMeta(parser, s);
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
    }

    private static void fillPopularApisData(com.fasterxml.jackson.core.JsonParser parser,
                                            PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "type" -> s.dataType = parser.getValueAsString();
                case "id" -> s.dataId = parser.getValueAsString();
                case "attributes" -> fillPopularApisAttributes(parser, s);
                default -> parser.skipChildren();
            }
        }
    }

    private static void fillPopularApisAttributes(com.fasterxml.jackson.core.JsonParser parser,
                                                  PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "title" -> s.title = parser.getValueAsString();
                case "amount_cents" -> s.amountCents = parser.getIntValue();
                case "fee_rate" -> s.feeRate = parser.getDoubleValue();
                default -> parser.skipChildren();
            }
        }
    }

    private static void fillPopularApisRepository(com.fasterxml.jackson.core.JsonParser parser,
                                                  PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "full_name" -> s.fullName = parser.getValueAsString();
                case "stargazers_count" -> s.stargazers = parser.getIntValue();
                case "private" -> s.repoPrivate = parser.getBooleanValue();
                default -> parser.skipChildren();
            }
        }
    }

    private static void fillPopularApisGeo(com.fasterxml.jackson.core.JsonParser parser,
                                           PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "lat" -> s.lat = parser.getDoubleValue();
                case "lon" -> s.lon = parser.getDoubleValue();
                default -> parser.skipChildren();
            }
        }
    }

    private static void fillPopularApisLineItems(com.fasterxml.jackson.core.JsonParser parser,
                                                 PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        int index = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    parser.skipChildren();
                    continue;
                }
                String field = parser.currentName();
                parser.nextToken();
                if (index == 0) {
                    switch (field) {
                        case "sku" -> s.sku0 = parser.getValueAsString();
                        case "quantity" -> s.qty0 = parser.getIntValue();
                        case "unit_amount" -> s.unit0 = parser.getDoubleValue();
                        default -> parser.skipChildren();
                    }
                } else if (index == 1) {
                    switch (field) {
                        case "sku" -> s.sku1 = parser.getValueAsString();
                        case "quantity" -> s.qty1 = parser.getIntValue();
                        case "unit_amount" -> s.unit1 = parser.getDoubleValue();
                        default -> parser.skipChildren();
                    }
                } else {
                    parser.skipChildren();
                }
            }
            index++;
        }
    }

    private static void fillPopularApisMeta(com.fasterxml.jackson.core.JsonParser parser,
                                            PopularLocals s) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "request_id" -> s.requestId = parser.getValueAsString();
                case "version" -> s.version = parser.getValueAsString();
                default -> parser.skipChildren();
            }
        }
    }

    private static long mix(long h, Object x) {
        return h * 31 + (x == null ? 0 : x.hashCode());
    }

    private static PersistentShapeMap sm(MapShape shape,
                                         Object v0, Object v1, Object v2, Object v3,
                                         Object v4, Object v5, Object v6, Object v7) {
        return new PersistentShapeMap(null, shape, v0, v1, v2, v3, v4, v5, v6, v7);
    }

    private static String jacksonStreamingRootString(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IllegalStateException("expected object");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                return parser.getValueAsString();
            }
            parser.skipChildren();
        }
        return null;
    }

    private static int jacksonStreamingRootInt(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IllegalStateException("expected object");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                return parser.getIntValue();
            }
            parser.skipChildren();
        }
        return 0;
    }

    private static String jacksonStreamingObjectString(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                String value = parser.getValueAsString();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    parser.nextToken();
                    parser.skipChildren();
                }
                return value;
            }
            parser.skipChildren();
        }
        return null;
    }

    private static String[] jacksonStreamingJsonapiData(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String id = null;
        String title = null;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        int remaining = 2;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "id" -> {
                    id = parser.getValueAsString();
                    remaining--;
                }
                case "attributes" -> {
                    title = jacksonStreamingObjectString(parser, "title");
                    remaining--;
                }
                default -> parser.skipChildren();
            }
            if (remaining == 0) {
                // Leave the parser on this object's END_OBJECT so the caller resumes at a sibling.
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    parser.nextToken();
                    parser.skipChildren();
                }
                break;
            }
        }
        return new String[] {id, title};
    }

    private static String[] jacksonStreamingFirstStatus(
            com.fasterxml.jackson.core.JsonParser parser, long[] idSlot) throws Exception {
        String text = null;
        String screenName = null;
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        int remaining = 3;
        while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "id" -> {
                    idSlot[0] = parser.getLongValue();
                    remaining--;
                }
                case "text" -> {
                    text = parser.getValueAsString();
                    remaining--;
                }
                case "user" -> {
                    screenName = jacksonStreamingObjectString(parser, "screen_name");
                    remaining--;
                }
                default -> parser.skipChildren();
            }
        }
        if (parser.currentToken() == JsonToken.FIELD_NAME) {
            parser.skipChildren();
        }
        parser.skipChildren();
        return new String[] {text, screenName};
    }



    
}
