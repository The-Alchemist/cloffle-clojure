package net.javacrumbs.cloffle.json;

import clojure.lang.Keyword;
import clojure.lang.RT;

/**
 * Shared Malli-style schema builders for typed JSON projection benchmarks and
 * the scanner-variant A/B harness. Guests in {@code setup.clj} must keep the
 * schema literal inline so the bytecode emitter sees a constant; this helper is
 * for host Java paths where the plan is compiled once in {@code @Setup}.
 */
public final class JsonTypedSchemas {

    private static final Keyword MAP = Keyword.intern("map");
    private static final Keyword INT = Keyword.intern("int");
    private static final Keyword LONG = Keyword.intern("long");
    private static final Keyword DOUBLE = Keyword.intern("double");
    private static final Keyword BOOLEAN = Keyword.intern("boolean");
    private static final Keyword STRING = Keyword.intern("string");
    private static final Keyword INDEXES = Keyword.intern("cloffle", "indexes");

    private JsonTypedSchemas() {
    }

    public static Object placeholder() {
        return map(entry(Keyword.intern("id"), INT),
                entry(Keyword.intern("userId"), INT),
                entry(Keyword.intern("title"), STRING));
    }

    public static Object jsonapi() {
        return map(
                entry(Keyword.intern("data"), map(
                        entry(Keyword.intern("id"), STRING),
                        entry(Keyword.intern("attributes"), map(
                                entry(Keyword.intern("title"), STRING))))),
                entry(Keyword.intern("meta"), map(
                        entry(Keyword.intern("request-id"), STRING))));
    }

    public static Object github() {
        return map(
                entry(Keyword.intern("full_name"), STRING),
                entry(Keyword.intern("stargazers_count"), INT),
                entry(Keyword.intern("open_issues_count"), INT),
                entry(Keyword.intern("owner"), map(
                        entry(Keyword.intern("login"), STRING))));
    }

    /** Early-exit control: statuses[0] only. */
    public static Object twitterFirst() {
        return map(entry(Keyword.intern("statuses"), RT.vector(
                INDEXES,
                RT.vector(0, map(
                        entry(Keyword.intern("id"), LONG),
                        entry(Keyword.intern("text"), STRING),
                        entry(Keyword.intern("user"), map(
                                entry(Keyword.intern("screen_name"), STRING))))))));
    }

    /**
     * Timeline-card extract on the first retweet ({@code statuses[1]}): author profile URL,
     * mention, photo dimensions, original tweet, and a bio URL nested under
     * {@code retweeted_status.user.entities.description.urls}.
     */
    public static Object twitterNested() {
        Object expanded = map(entry(Keyword.intern("expanded_url"), STRING));
        Object urls0 = indexes(0, expanded);
        Object media0 = map(
                entry(Keyword.intern("media_url_https"), STRING),
                entry(Keyword.intern("expanded_url"), STRING),
                entry(Keyword.intern("type"), STRING),
                entry(Keyword.intern("sizes"), map(
                        entry(Keyword.intern("large"), map(
                                entry(Keyword.intern("w"), INT),
                                entry(Keyword.intern("h"), INT))))));
        Object mention0 = map(
                entry(Keyword.intern("screen_name"), STRING),
                entry(Keyword.intern("id"), LONG));
        Object status = map(
                entry(Keyword.intern("id"), LONG),
                entry(Keyword.intern("text"), STRING),
                entry(Keyword.intern("created_at"), STRING),
                entry(Keyword.intern("user"), map(
                        entry(Keyword.intern("screen_name"), STRING),
                        entry(Keyword.intern("name"), STRING),
                        entry(Keyword.intern("followers_count"), INT),
                        entry(Keyword.intern("verified"), BOOLEAN),
                        entry(Keyword.intern("entities"), map(
                                entry(Keyword.intern("url"), map(
                                        entry(Keyword.intern("urls"), urls0))))))),
                entry(Keyword.intern("entities"), map(
                        entry(Keyword.intern("user_mentions"), indexes(0, mention0)),
                        entry(Keyword.intern("media"), indexes(0, media0)))),
                entry(Keyword.intern("retweeted_status"), map(
                        entry(Keyword.intern("id"), LONG),
                        entry(Keyword.intern("text"), STRING),
                        entry(Keyword.intern("favorite_count"), INT),
                        entry(Keyword.intern("user"), map(
                                entry(Keyword.intern("screen_name"), STRING),
                                entry(Keyword.intern("name"), STRING),
                                entry(Keyword.intern("entities"), map(
                                        entry(Keyword.intern("description"), map(
                                                entry(Keyword.intern("urls"), urls0))))))),
                        entry(Keyword.intern("entities"), map(
                                entry(Keyword.intern("media"), indexes(0, media0)))))));
        return map(entry(Keyword.intern("statuses"), indexes(1, status)));
    }

    /**
     * Full-traversal schema: last status plus search_metadata at the document
     * tail, so skipValueOnDemand and remainder-skip dominate.
     */
    public static Object twitterLate() {
        return map(
                entry(Keyword.intern("statuses"), RT.vector(
                        INDEXES,
                        RT.vector(99, map(
                                entry(Keyword.intern("id"), LONG),
                                entry(Keyword.intern("text"), STRING),
                                entry(Keyword.intern("user"), map(
                                        entry(Keyword.intern("screen_name"), STRING))))))),
                entry(Keyword.intern("search_metadata"), map(
                        entry(Keyword.intern("count"), INT),
                        entry(Keyword.intern("completed_in"), DOUBLE),
                        entry(Keyword.intern("query"), STRING))));
    }

    public static Object popularApis() {
        Object item = map(
                entry(Keyword.intern("sku"), STRING),
                entry(Keyword.intern("quantity"), INT),
                entry(Keyword.intern("unit_amount"), DOUBLE));
        return map(
                entry(Keyword.intern("id"), STRING),
                entry(Keyword.intern("livemode"), BOOLEAN),
                entry(Keyword.intern("created"), LONG),
                entry(Keyword.intern("data"), map(
                        entry(Keyword.intern("type"), STRING),
                        entry(Keyword.intern("id"), STRING),
                        entry(Keyword.intern("attributes"), map(
                                entry(Keyword.intern("title"), STRING),
                                entry(Keyword.intern("amount_cents"), INT),
                                entry(Keyword.intern("fee_rate"), DOUBLE))))),
                entry(Keyword.intern("repository"), map(
                        entry(Keyword.intern("full_name"), STRING),
                        entry(Keyword.intern("stargazers_count"), INT),
                        entry(Keyword.intern("private"), BOOLEAN))),
                entry(Keyword.intern("geo"), map(
                        entry(Keyword.intern("lat"), DOUBLE),
                        entry(Keyword.intern("lon"), DOUBLE))),
                entry(Keyword.intern("line_items"), RT.vector(
                        INDEXES,
                        RT.vector(0, item),
                        RT.vector(1, item))),
                entry(Keyword.intern("meta"), map(
                        entry(Keyword.intern("request_id"), STRING),
                        entry(Keyword.intern("version"), STRING))));
    }

    public static Object doubles() {
        return map(
                entry(Keyword.intern("lat"), DOUBLE),
                entry(Keyword.intern("lon"), DOUBLE),
                entry(Keyword.intern("altitude"), DOUBLE),
                entry(Keyword.intern("speed"), DOUBLE),
                entry(Keyword.intern("heading"), DOUBLE),
                entry(Keyword.intern("accuracy"), DOUBLE),
                entry(Keyword.intern("temp_c"), DOUBLE),
                entry(Keyword.intern("humidity"), DOUBLE));
    }

    public static Object escaped() {
        return map(entry(Keyword.intern("message"), STRING),
                entry(Keyword.intern("id"), INT));
    }

    private static Object indexes(int index, Object schema) {
        return RT.vector(INDEXES, RT.vector(index, schema));
    }

    private static Object map(Object... entries) {
        Object[] xs = new Object[entries.length + 1];
        xs[0] = MAP;
        System.arraycopy(entries, 0, xs, 1, entries.length);
        return RT.vector(xs);
    }

    private static Object entry(Keyword key, Object schema) {
        return RT.vector(key, schema);
    }
}
