package net.javacrumbs.cloffle.benchmark.tuplepea;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class JsonPeaLanguageTest {

    private static final byte[] JSONAPI = """
            {"data":{"id":"1","attributes":{"title":"Hello"}},"meta":{"request-id":"abc"}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTITY = """
            {"email":"a@b.c","name":"x"}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ROWS = """
            [{"name":"a"},{"name":"b"},{"name":"c"},{"name":"d"}]
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLACEHOLDER = """
            {"id":1,"userId":1,"title":"post"}
            """.getBytes(StandardCharsets.UTF_8);

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder(TuplePeaLanguage.ID).allowAllAccess(true).build();
        context.enter();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    private CallTarget parse(String program) throws Exception {
        context.parse(Source.newBuilder(TuplePeaLanguage.ID, program, program + ".pea").build());
        return TuplePeaLanguage.takeLastParsed();
    }

    @Test
    public void parseLookupMatchesHostJsonParser() throws Exception {
        Object host = ((IPersistentMap) ((IPersistentMap) ((IPersistentMap)
                JsonParser.parseBytes(JSONAPI)).valAt(Keyword.intern("data")))
                .valAt(Keyword.intern("attributes"))).valAt(Keyword.intern("title"));
        assertEquals(host, parse("json:parseLookupJsonapi").call(JSONAPI));
        assertEquals(host, parse("json:parseLookupJsonapiBoundary").call(JSONAPI));
        assertEquals("a@b.c", parse("json:parseLookupEntity16").call(ENTITY));
        assertEquals("d", parse("json:parseLookupRows").call(ROWS));
        assertEquals("post", parse("json:parseLookupPlaceholder").call(PLACEHOLDER));
    }

    @Test
    public void projectTypedMatchesHostPlan() throws Exception {
        Object pe = parse("json:projectTyped:placeholder").call(PLACEHOLDER);
        assertEquals("post", ((IPersistentMap) pe).valAt(Keyword.intern("title")));
    }

    @Test
    public void projectTypedTwitterNestedMatchesHostPlan() throws Exception {
        byte[] json = """
                {"statuses":[{},{"id":505874922023837696,"created_at":"Sun Aug 31 00:29:14 +0000 2014",
                "text":"RT @KATANA77: x","user":{"screen_name":"yuttari1998","name":"RT",
                "followers_count":95,"verified":false,"entities":{"url":{"urls":[
                {"expanded_url":"http://twpf.jp/yuttari1998"}]}}},
                "entities":{"user_mentions":[{"screen_name":"KATANA77","id":77915997}],
                "media":[{"media_url_https":"https://pbs.twimg.com/media/x.jpg","expanded_url":"http://t.co/m",
                "type":"photo","sizes":{"large":{"w":765,"h":432}}}]},
                "retweeted_status":{"id":505864943636197376,"text":"えっ","favorite_count":42,
                "user":{"screen_name":"KATANA77","name":"(有)刀","entities":{"description":{"urls":[
                {"expanded_url":"http://www.pixiv.net/member.php?id=4776"}]}}},
                "entities":{"media":[{"media_url_https":"https://pbs.twimg.com/media/x.jpg",
                "expanded_url":"http://twitter.com/KATANA77/status/1/photo/1","type":"photo",
                "sizes":{"large":{"w":765,"h":432}}}]}}}]}
                """.getBytes(StandardCharsets.UTF_8);
        Object pe = parse("json:projectTyped:twitterNested").call(json);
        assertEquals(JsonTypedProjectPlan.compile(null, JsonTypedSchemas.twitterNested()).project(json), pe);

        IPersistentMap status = (IPersistentMap) ((IPersistentVector)
                ((IPersistentMap) pe).valAt(Keyword.intern("statuses"))).nth(1);
        IPersistentMap user = (IPersistentMap) status.valAt(Keyword.intern("user"));
        IPersistentMap rt = (IPersistentMap) status.valAt(Keyword.intern("retweeted_status"));
        IPersistentMap media0 = (IPersistentMap) ((IPersistentVector)
                ((IPersistentMap) status.valAt(Keyword.intern("entities")))
                        .valAt(Keyword.intern("media"))).nth(0);
        IPersistentMap bioUrl = (IPersistentMap) ((IPersistentVector)
                ((IPersistentMap) ((IPersistentMap) ((IPersistentMap)
                        rt.valAt(Keyword.intern("user")))
                        .valAt(Keyword.intern("entities")))
                        .valAt(Keyword.intern("description")))
                        .valAt(Keyword.intern("urls"))).nth(0);
        assertEquals(505874922023837696L, status.valAt(Keyword.intern("id")));
        assertEquals("yuttari1998", user.valAt(Keyword.intern("screen_name")));
        assertEquals("http://twpf.jp/yuttari1998",
                ((IPersistentMap) ((IPersistentVector) ((IPersistentMap) ((IPersistentMap)
                        user.valAt(Keyword.intern("entities")))
                        .valAt(Keyword.intern("url")))
                        .valAt(Keyword.intern("urls"))).nth(0))
                        .valAt(Keyword.intern("expanded_url")));
        assertEquals(765, ((IPersistentMap) ((IPersistentMap)
                media0.valAt(Keyword.intern("sizes"))).valAt(Keyword.intern("large")))
                .valAt(Keyword.intern("w")));
        assertEquals("KATANA77", ((IPersistentMap) rt.valAt(Keyword.intern("user")))
                .valAt(Keyword.intern("screen_name")));
        assertEquals("http://www.pixiv.net/member.php?id=4776",
                bioUrl.valAt(Keyword.intern("expanded_url")));
    }
}
