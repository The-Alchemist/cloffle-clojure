package net.javacrumbs.cloffle;

import org.cloffle.trufflejson.JsonScan;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.PersistentShapeMap;
import clojure.lang.RT;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JsonTypedProjectTest {
    private static final Keyword ID = Keyword.intern("id");
    private static final Keyword PRICE = Keyword.intern("price");
    private static final Keyword NAME = Keyword.intern("name");

    @BeforeClass
    public static void loadJsonNs() throws Exception {
        RT.init();
        RT.load("cloffle/json");
        RT.var("cloffle.json", "project").rearmLoweringRoot();
    }

    private static List<String> instructions(String form) throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> roots =
                BytecodeDslTestSupport.compileRootNodes(form, "jsonTypedProject");
        List<String> names = new ArrayList<>();
        for (CloffleBytecodeRootNode root : roots.getNodes()) {
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
        }
        return names;
    }

    @Test
    public void singlePassNumberParsingDirectlyDecodesIntegersAndDoubles() {
        String json = "{\"id\":-1234567890123,\"price\":42,\"rating\":3.5,\"overflow\":999999999999999999999999999999}";
        IPersistentMap res = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json.replace("\"", "\\\"") + "\" "
                        + "[:map [:id :long] [:price :int] [:rating :double] [:overflow :double]])");
        assertEquals(-1234567890123L, res.valAt(ID));
        assertEquals(42, res.valAt(PRICE));
        assertEquals(3.5, (Double) res.valAt(Keyword.intern("rating")), 0.0001);
        assertEquals(1e30, (Double) res.valAt(Keyword.intern("overflow")), 1e25);

        // Also test root primitive decode
        Object rootLong = eval("(cloffle.json/project \"12345\" :long)");
        assertEquals(12345L, rootLong);
        Object rootInt = eval("(cloffle.json/project \"54321\" :int)");
        assertEquals(54321, rootInt);
    }

    private static Object eval(String form) {
        return BytecodeDslTestSupport.evalBytecode(form);
    }

    private static void assertFails(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            fail("Expected " + type.getName());
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause != null && !type.isInstance(cause)) {
                cause = cause.getCause();
            }
            if (cause == null) {
                if (t instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new AssertionError(t);
            }
        }
    }

    @Test
    public void constantSchemaLowersToTypedOperation() throws Exception {
        List<String> names = instructions(
                "(cloffle.json/project \"{\\\"id\\\":7}\" [:map [:id :long]])");
        assertTrue(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
    }

    @Test
    public void rootScalarsUseTypedSpecializations() {
        assertEquals(42L, eval("(cloffle.json/project \"42\" :long)"));
        assertEquals(42, eval("(cloffle.json/project \"42\" :int)"));
        assertEquals(42.5d, (Double) eval(
                "(cloffle.json/project \"42.5\" :double)"), 0.0d);
        assertEquals(Boolean.TRUE, eval(
                "(cloffle.json/project \"true\" :boolean)"));
    }

    @Test
    public void dynamicSchemaUsesEquivalentFallback() throws Exception {
        List<String> names = instructions(
                "(let [schema [:map [:id :long]]]"
                        + " (cloffle.json/project \"{\\\"id\\\":7}\" schema))");
        assertFalse(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
        IPersistentMap value = (IPersistentMap) eval(
                "(let [schema [:map [:id :long]]]"
                        + " (cloffle.json/project \"{\\\"id\\\":7}\" schema))");
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void extractsTypedNestedMap() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project "
                        + "\"{\\\"id\\\":7,\\\"price\\\":12.5,\\\"active\\\":true,"
                        + "\\\"profile\\\":{\\\"name\\\":\\\"Ada\\\"}}\" "
                        + "[:map [:id :long] [:price :double] [:active :boolean]"
                        + " [:profile [:map [:name :string]]]])");
        assertTrue(value instanceof PersistentShapeMap);
        assertEquals(7L, value.valAt(ID));
        assertEquals(12.5d, (Double) value.valAt(PRICE), 0.0d);
        assertEquals(Boolean.TRUE, value.valAt(Keyword.intern("active")));
        IPersistentMap profile =
                (IPersistentMap) value.valAt(Keyword.intern("profile"));
        assertEquals("Ada", profile.valAt(NAME));
    }

    @Test
    public void optionalDefaultNullableAndDuplicateAreExplicit() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project "
                        + "\"{\\\"id\\\":1,\\\"id\\\":2,\\\"note\\\":null}\" "
                        + "[:map [:id :long] [:missing {:optional true} :string]"
                        + " [:count {:default 9} :int] [:note [:maybe :string]]])");
        assertEquals(1L, value.valAt(ID));
        assertFalse(value.containsKey(Keyword.intern("missing")));
        assertEquals(9L, value.valAt(Keyword.intern("count")));
        assertTrue(value.containsKey(Keyword.intern("note")));
        assertEquals(null, value.valAt(Keyword.intern("note")));
    }

    @Test
    public void supportsTupleSparseIndexesAndHomogeneousVectors() {
        IPersistentVector tuple = (IPersistentVector) eval(
                "(cloffle.json/project \"[1,\\\"two\\\",3.5]\""
                        + " [:tuple :long :string :double])");
        assertEquals(1L, tuple.nth(0));
        assertEquals("two", tuple.nth(1));
        assertEquals(3.5d, (Double) tuple.nth(2), 0.0d);

        IPersistentVector sparse = (IPersistentVector) eval(
                "(cloffle.json/project \"[1,2,3,4]\""
                        + " [:cloffle/indexes [0 :long] [3 :long]])");
        assertEquals(1L, sparse.nth(0));
        assertEquals(null, sparse.nth(1));
        assertEquals(4L, sparse.nth(3));

        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"values\\\":[1,2,3]}\""
                        + " [:map [:values [:vector :long]]])");
        IPersistentVector values =
                (IPersistentVector) value.valAt(Keyword.intern("values"));
        assertEquals(RT.vector(1L, 2L, 3L), values);
    }

    @Test
    public void onDemandModeIgnoresInvalidUnselectedScalar() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7,\\\"ignored\\\":wat}\""
                        + " [:map [:id :long]])");
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void selectedTypeAndRequiredFieldsAreChecked() {
        assertFails(JsonParser.ParseException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":\\\"7\\\"}\""
                        + " [:map [:id :long]])"));
        assertFails(IllegalArgumentException.class, () -> eval(
                "(cloffle.json/project \"{}\" [:map [:id :long]])"));
    }

    @Test
    public void explicitTruffleStringCanBeViewOrMaterialized() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"name\\\":\\\"Ada\\\"}\""
                        + " [:map [:name :cloffle/truffle-string]])");
        Object raw = value.valAt(NAME);
        assertTrue(raw instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) raw).toJavaStringUncached());

        IPersistentMap escaped = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"name\\\":\\\"A\\\\u0064a\\\"}\""
                        + " [:map [:name {:cloffle/materialize true}"
                        + " :cloffle/truffle-string]])");
        Object decoded = escaped.valAt(NAME);
        assertTrue(decoded instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) decoded).toJavaStringUncached());

        IPersistentMap escapedJava = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"name\\\":\\\"雪\\\\n"
                        + "\\\\uD83D\\\\uDE03\\\"}\" [:map [:name :string]])");
        assertEquals("雪\n😃", escapedJava.valAt(NAME));
    }

    @Test
    public void dynamicTypedStringsDecodeEscapesWithoutJavaStringIntermediates() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"values\\\":[\\\"A\\\\n雪\\\","
                        + "\\\"\\\\uD83D\\\\uDE03\\\"]}\""
                        + " [:map [:values [:vector :cloffle/truffle-string]]])");
        IPersistentVector values =
                (IPersistentVector) value.valAt(Keyword.intern("values"));
        assertEquals("A\n雪", ((TruffleString) values.nth(0)).toJavaStringUncached());
        assertEquals("😃", ((TruffleString) values.nth(1)).toJavaStringUncached());
    }

    @Test
    public void truffleStringInputIsScannedWithoutAByteArrayCopy() {
        Keyword valuesKey = Keyword.intern("values");
        Keyword escapedKey = Keyword.intern("escaped-key");
        Object schema = RT.vector(
                Keyword.intern("map"),
                RT.vector(ID, Keyword.intern("long")),
                RT.vector(NAME, Keyword.intern("cloffle", "truffle-string")),
                RT.vector(valuesKey, RT.vector(
                        Keyword.intern("vector"),
                        Keyword.intern("cloffle", "truffle-string"))),
                RT.vector(escapedKey, Keyword.intern("string")));
        String json = "{\"id\":7,\"name\":\"雪\\n😃\",\"values\":[\"A\\n雪\","
                + "\"\\uD83D\\uDE03\"],\"escaped\\u002dkey\":\"ok\"}";
        byte[] padded = ("xx" + json + "yy").getBytes(StandardCharsets.UTF_8);
        TruffleString source = TruffleString.fromByteArrayUncached(
                padded, 2, padded.length - 4, TruffleString.Encoding.UTF_8, false);

        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema);
        JsonScan.TypedScanResult scan = plan.scan(source);
        InternalByteArray internal = TruffleString.GetInternalByteArrayNode.getUncached()
                .execute(source, TruffleString.Encoding.UTF_8);
        assertSame(internal.getArray(), scan.source);
        assertEquals(internal.getOffset(), scan.truffleOffset);
        assertTrue(scan.truffleSource != null);

        IPersistentMap value = (IPersistentMap) JsonTypedProjectPlan.project(source, schema);
        assertEquals(7L, value.valAt(ID));
        assertEquals("雪\n😃",
                ((TruffleString) value.valAt(NAME)).toJavaStringUncached());
        IPersistentVector values = (IPersistentVector) value.valAt(valuesKey);
        assertEquals("A\n雪",
                ((TruffleString) values.nth(0)).toJavaStringUncached());
        assertEquals("😃",
                ((TruffleString) values.nth(1)).toJavaStringUncached());
        assertEquals("ok", value.valAt(escapedKey));
    }

    @Test
    public void projectionOptionMakesStringLeavesTruffleStrings() throws Exception {
        String form =
                "(cloffle.json/project \"{\\\"name\\\":\\\"Ada\\\"}\""
                        + " [:map [:name :string]] {:cloffle/strings :truffle})";
        List<String> names = instructions(form);
        assertTrue(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
        IPersistentMap value = (IPersistentMap) eval(form);
        Object name = value.valAt(NAME);
        assertTrue(name instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) name).toJavaStringUncached());

        assertFails(IllegalArgumentException.class, () -> eval(
                "(cloffle.json/project \"{\\\"name\\\":\\\"Ada\\\"}\""
                        + " [:map [:name :string]] {:cloffle/strings :unknown})"));
    }

    @Test
    public void heapByteBufferIsScannedWithoutChangingItsPosition() {
        IPersistentMap value = (IPersistentMap) eval(
                "(let [b (java.nio.ByteBuffer/wrap"
                        + " (.getBytes \"{\\\"id\\\":7}\" \"UTF-8\"))]"
                        + " (cloffle.json/project b [:map [:id :long]]))");
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void malliFrontendExpandsToSameOperation() throws Exception {
        RT.load("cloffle/json/malli");
        List<String> names = instructions(
                "(cloffle.json.malli/project \"{\\\"id\\\":7}\""
                        + " [:map [:id :long]])");
        assertTrue(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
    }

    @Test
    public void unschemedProjectUsesJsonNativeTypes() {
        assertEquals(42L, eval("(cloffle.json/project \"42\")"));
        assertEquals(1.5d, (Double) eval("(cloffle.json/project \"1.5\")"), 0.0d);
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7,\\\"name\\\":\\\"Ada\\\"}\")");
        assertEquals(7L, value.valAt(ID));
        assertEquals("Ada", value.valAt(NAME));
    }

    @Test
    public void unschemedProjectDoesNotLowerToTypedOperation() throws Exception {
        List<String> names = instructions("(cloffle.json/project \"{\\\"id\\\":7}\")");
        assertFalse(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
    }

    @Test
    public void lastWinsRequiresExplicitOption() {
        IPersistentMap first = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":1,\\\"id\\\":2}\" [:map [:id :long]])");
        assertEquals(1L, first.valAt(ID));
        IPersistentMap last = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":1,\\\"id\\\":2}\" [:map [:id :long]]"
                        + " {:cloffle/duplicates :last})");
        assertEquals(2L, last.valAt(ID));
    }

    @Test
    public void firstWinsStopsBeforeMalformedTail() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]])");
        assertEquals(7L, value.valAt(ID));
        assertFails(JsonParser.ParseException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]] {:cloffle/duplicates :last})"));
    }

    @Test
    public void jsonSchemaCompilesToSameSelectionAndTypes() {
        IPersistentMap malli = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"data\\\":{\\\"id\\\":\\\"a\\\",\\\"n\\\":3}}\""
                        + " [:map [:data [:map [:id :string] [:n :long]]]])");
        IPersistentMap schema = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"data\\\":{\\\"id\\\":\\\"a\\\",\\\"n\\\":3}}\""
                        + " {:type \"object\" :required [\"data\"]"
                        + "  :properties {:data {:type \"object\""
                        + "    :required [\"id\" \"n\"]"
                        + "    :properties {:id {:type \"string\"}"
                        + "                 :n {:type \"integer\"}}}}})");
        IPersistentMap data = (IPersistentMap) malli.valAt(Keyword.intern("data"));
        IPersistentMap schemaData = (IPersistentMap) schema.valAt(Keyword.intern("data"));
        assertEquals("a", data.valAt(ID));
        assertEquals(3L, data.valAt(Keyword.intern("n")));
        assertEquals(data.valAt(ID), schemaData.valAt(ID));
        assertEquals(data.valAt(Keyword.intern("n")), schemaData.valAt(Keyword.intern("n")));
    }

    @Test
    public void jsonSchemaFrontendExpandsToSameOperation() throws Exception {
        RT.load("cloffle/json/json_schema");
        List<String> names = instructions(
                "(cloffle.json.json-schema/project \"{\\\"id\\\":7}\""
                        + " {:type \"object\" :required [\"id\"]"
                        + "  :properties {:id {:type \"integer\"}}})");
        assertTrue(names.toString(),
                names.stream().anyMatch(name -> name.contains("JsonTypedProject")));
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json.json-schema/project \"{\\\"id\\\":7}\""
                        + " {:type \"object\" :required [\"id\"]"
                        + "  :properties {:id {:type \"integer\"}}})");
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void jsonSchemaPropertiesAreOptionalUnlessRequired() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{}\""
                        + " {:type \"object\" :properties {:id {:type \"integer\""
                        + " :default 9}}})");
        assertEquals(9L, value.valAt(ID));
    }

    @Test
    public void jsonSchemaPrefixItemsAndHomogeneousItems() {
        IPersistentVector tuple = (IPersistentVector) eval(
                "(cloffle.json/project \"[1,\\\"two\\\"]\""
                        + " {:type \"array\" :prefixItems [{:type \"integer\"}"
                        + " {:type \"string\"}]})");
        assertEquals(1L, tuple.nth(0));
        assertEquals("two", tuple.nth(1));
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"values\\\":[1,2,3]}\""
                        + " {:type \"object\" :required [\"values\"]"
                        + "  :properties {:values {:type \"array\""
                        + "    :items {:type \"integer\"}}}})");
        assertEquals(RT.vector(1L, 2L, 3L), value.valAt(Keyword.intern("values")));
    }

    @Test
    public void missingOptionalStillScansToEnd() {
        assertFails(JsonParser.ParseException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long] [:note {:optional true} :string]])"));
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7,\\\"note\\\":\\\"hi\\\", this is not json\""
                        + " [:map [:id :long] [:note {:optional true} :string]])");
        assertEquals(7L, value.valAt(ID));
        assertEquals("hi", value.valAt(Keyword.intern("note")));
    }

    @Test
    public void jacksonBackendMatchesCustomNestedProjection() {
        String schema =
                "[:map [:id :long] [:price :double] [:active :boolean]"
                        + " [:profile [:map [:name :string]]]"
                        + " [:values [:vector :int]]]";
        String json =
                "{\\\"id\\\":7,\\\"price\\\":12.5,\\\"active\\\":true,"
                        + "\\\"profile\\\":{\\\"name\\\":\\\"A\\\\u0064a\\\"},"
                        + "\\\"values\\\":[1,2,3]}";
        Object custom = eval("(cloffle.json/project \"" + json + "\" " + schema + ")");
        Object jackson = eval("(cloffle.json/project \"" + json + "\" " + schema
                + " {:cloffle/backend :jackson})");
        assertEquals(custom, jackson);
    }

    @Test
    public void jacksonBackendMatchesJsonSchemaAndDynamicFallback() {
        String schema =
                "{:type \"object\" :required [\"id\" \"values\"]"
                        + " :properties {:id {:type \"integer\"}"
                        + " :values {:type \"array\" :items {:type \"number\"}}}}";
        String json = "{\\\"id\\\":7,\\\"values\\\":[1,2.5,3]}";
        Object custom = eval("(cloffle.json/project \"" + json + "\" " + schema + ")");
        Object jackson = eval("(cloffle.json/project \"" + json + "\" " + schema
                + " {:cloffle/backend :jackson})");
        assertEquals(custom, jackson);
        Object dynamic = eval("(let [schema " + schema + "]"
                + " (cloffle.json/project \"" + json + "\" schema"
                + " {:cloffle/backend :jackson}))");
        assertEquals(custom, dynamic);
    }

    @Test
    public void jacksonBackendSupportsAllInputRepresentations() {
        String body = "{\\\"id\\\":7}";
        String schema = "[:map [:id :long]]";
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"" + body + "\" \"UTF-8\") "
                        + schema + " {:cloffle/backend :jackson})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (StringBuilder. \"" + body + "\") "
                        + schema + " {:cloffle/backend :jackson})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (java.nio.ByteBuffer/wrap"
                        + " (.getBytes \"" + body + "\" \"UTF-8\")) "
                        + schema + " {:cloffle/backend :jackson})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(let [raw (.getBytes \"" + body + "\" \"UTF-8\")"
                        + " b (java.nio.ByteBuffer/allocateDirect (alength raw))]"
                        + " (.put b raw) (.flip b)"
                        + " (cloffle.json/project b " + schema
                        + " {:cloffle/backend :jackson}))")).valAt(ID));

        TruffleString truffle = TruffleString.fromJavaStringUncached(
                "{\"id\":7}", TruffleString.Encoding.UTF_8);
        Object truffleValue = JsonTypedProjectPlan.project(
                truffle,
                RT.vector(Keyword.intern("map"), RT.vector(ID, Keyword.intern("long"))),
                RT.map(Keyword.intern("cloffle", "backend"), Keyword.intern("jackson")));
        assertEquals(7L, ((IPersistentMap) truffleValue).valAt(ID));
    }

    @Test
    public void jacksonBackendPreservesDuplicatePolicies() {
        String json = "{\\\"id\\\":1,\\\"id\\\":2}";
        String schema = "[:map [:id :long]]";
        IPersistentMap first = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json + "\" " + schema
                        + " {:cloffle/backend :jackson})");
        assertEquals(1L, first.valAt(ID));
        IPersistentMap last = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json + "\" " + schema
                        + " {:cloffle/backend :jackson :cloffle/duplicates :last})");
        assertEquals(2L, last.valAt(ID));
    }

    @Test
    public void jacksonBackendFallsBackForPermissiveSkippedScalar() {
        String form =
                "(cloffle.json/project \"{\\\"ignored\\\":wat,\\\"id\\\":7}\""
                        + " [:map [:id :long]] {:cloffle/backend :jackson})";
        IPersistentMap value = (IPersistentMap) eval(form);
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void jacksonBackendPreservesUnreadMalformedTail() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]] {:cloffle/backend :jackson})");
        assertEquals(7L, value.valAt(ID));
        assertFails(JsonParser.ParseException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]]"
                        + " {:cloffle/backend :jackson :cloffle/duplicates :last})"));
    }

    @Test
    public void jacksonBackendRetainsCustomTruffleStringPath() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"name\\\":\\\"Ada\\\"}\""
                        + " [:map [:name :string]]"
                        + " {:cloffle/backend :jackson :cloffle/strings :truffle})");
        Object name = value.valAt(NAME);
        assertTrue(name instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) name).toJavaStringUncached());
    }

    @Test
    public void jacksonBackendOptionIsValidated() {
        assertFails(IllegalArgumentException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":7}\" [:map [:id :long]]"
                        + " {:cloffle/backend :unknown})"));
    }

    @Test
    public void jackson3BackendMatchesCustomNestedProjection() {
        String schema =
                "[:map [:id :long] [:price :double] [:active :boolean]"
                        + " [:profile [:map [:name :string]]]"
                        + " [:values [:vector :int]]]";
        String json =
                "{\\\"id\\\":7,\\\"price\\\":12.5,\\\"active\\\":true,"
                        + "\\\"profile\\\":{\\\"name\\\":\\\"A\\\\u0064a\\\"},"
                        + "\\\"values\\\":[1,2,3]}";
        Object custom = eval("(cloffle.json/project \"" + json + "\" " + schema + ")");
        Object jackson = eval("(cloffle.json/project \"" + json + "\" " + schema
                + " {:cloffle/backend :jackson3})");
        assertEquals(custom, jackson);
    }

    @Test
    public void jackson3BackendMatchesJsonSchemaAndDynamicFallback() {
        String schema =
                "{:type \"object\" :required [\"id\" \"values\"]"
                        + " :properties {:id {:type \"integer\"}"
                        + " :values {:type \"array\" :items {:type \"number\"}}}}";
        String json = "{\\\"id\\\":7,\\\"values\\\":[1,2.5,3]}";
        Object custom = eval("(cloffle.json/project \"" + json + "\" " + schema + ")");
        Object jackson = eval("(cloffle.json/project \"" + json + "\" " + schema
                + " {:cloffle/backend :jackson3})");
        assertEquals(custom, jackson);
        Object dynamic = eval("(let [schema " + schema + "]"
                + " (cloffle.json/project \"" + json + "\" schema"
                + " {:cloffle/backend :jackson3}))");
        assertEquals(custom, dynamic);
    }

    @Test
    public void jackson3BackendSupportsByteAndTruffleInput() {
        String body = "{\\\"id\\\":7}";
        String schema = "[:map [:id :long]]";
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"" + body + "\" \"UTF-8\") "
                        + schema + " {:cloffle/backend :jackson3})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (StringBuilder. \"" + body + "\") "
                        + schema + " {:cloffle/backend :jackson3})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (java.nio.ByteBuffer/wrap"
                        + " (.getBytes \"" + body + "\" \"UTF-8\")) "
                        + schema + " {:cloffle/backend :jackson3})")).valAt(ID));

        TruffleString truffle = TruffleString.fromJavaStringUncached(
                "{\"id\":7}", TruffleString.Encoding.UTF_8);
        Object truffleValue = JsonTypedProjectPlan.project(
                truffle,
                RT.vector(Keyword.intern("map"), RT.vector(ID, Keyword.intern("long"))),
                RT.map(Keyword.intern("cloffle", "backend"), Keyword.intern("jackson3")));
        assertEquals(7L, ((IPersistentMap) truffleValue).valAt(ID));
    }

    @Test
    public void jackson3BackendPreservesDuplicatePolicies() {
        String json = "{\\\"id\\\":1,\\\"id\\\":2}";
        String schema = "[:map [:id :long]]";
        IPersistentMap first = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json + "\" " + schema
                        + " {:cloffle/backend :jackson3})");
        assertEquals(1L, first.valAt(ID));
        IPersistentMap last = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json + "\" " + schema
                        + " {:cloffle/backend :jackson3 :cloffle/duplicates :last})");
        assertEquals(2L, last.valAt(ID));
    }

    @Test
    public void jackson3BackendFallsBackForPermissiveSkippedScalar() {
        String form =
                "(cloffle.json/project \"{\\\"ignored\\\":wat,\\\"id\\\":7}\""
                        + " [:map [:id :long]] {:cloffle/backend :jackson3})";
        IPersistentMap value = (IPersistentMap) eval(form);
        assertEquals(7L, value.valAt(ID));
    }

    @Test
    public void jackson3BackendPreservesUnreadMalformedTail() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]] {:cloffle/backend :jackson3})");
        assertEquals(7L, value.valAt(ID));
        assertFails(JsonParser.ParseException.class, () -> eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\""
                        + " [:map [:id :long]]"
                        + " {:cloffle/backend :jackson3 :cloffle/duplicates :last})"));
    }

    @Test
    public void jackson3BackendZeroCopyTruffleStrings() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"name\\\":\\\"Ada\\\"}\" \"UTF-8\")"
                        + " [:map [:name :string]]"
                        + " {:cloffle/backend :jackson3 :cloffle/strings :truffle})");
        Object name = value.valAt(NAME);
        assertTrue(name instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) name).toJavaStringUncached());

        IPersistentMap escaped = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"name\\\":\\\"A\\\\u0064a\\\"}\" \"UTF-8\")"
                        + " [:map [:name {:cloffle/materialize true} :string]]"
                        + " {:cloffle/backend :jackson3 :cloffle/strings :truffle})");
        assertEquals("Ada", ((TruffleString) escaped.valAt(NAME)).toJavaStringUncached());
    }

    @Test
    public void jackson3BackendAcceptsUnvalidatedUtf8InSelectedString() {
        byte[] json = new byte[] {
                '{', '"', 'n', 'a', 'm', 'e', '"', ':', '"',
                (byte) 0xC0, (byte) 0x80, '"', '}'
        };
        IPersistentMap value = (IPersistentMap) JsonTypedProjectPlan.project(
                json,
                RT.vector(Keyword.intern("map"),
                        RT.vector(NAME, Keyword.intern("string"))),
                RT.map(Keyword.intern("cloffle", "backend"), Keyword.intern("jackson3")));
        assertEquals(2, ((String) value.valAt(NAME)).length());
    }

    @Test
    public void simdjsonBackendMatchesCustomNestedProjection() {
        String schema =
                "[:map [:id :long] [:price :double] [:active :boolean]"
                        + " [:profile [:map [:name :string]]]"
                        + " [:values [:vector :int]]]";
        String json =
                "{\\\"id\\\":7,\\\"price\\\":12.5,\\\"active\\\":true,"
                        + "\\\"profile\\\":{\\\"name\\\":\\\"A\\\\u0064a\\\"},"
                        + "\\\"values\\\":[1,2,3]}";
        Object custom = eval("(cloffle.json/project \"" + json + "\" " + schema + ")");
        Object simd = eval("(cloffle.json/project (.getBytes \"" + json + "\" \"UTF-8\") "
                + schema + " {:cloffle/backend :simdjson})");
        assertEquals(custom, simd);
    }

    @Test
    public void simdjsonBackendSupportsFallbackAndByteBackedInputs() {
        String body = "{\\\"id\\\":7}";
        String schema = "[:map [:id :long]]";
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"" + body + "\" \"UTF-8\") "
                        + schema + " {:cloffle/backend :simdjson})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (StringBuilder. \"" + body + "\") "
                        + schema + " {:cloffle/backend :simdjson})")).valAt(ID));
        assertEquals(7L, ((IPersistentMap) eval(
                "(cloffle.json/project (java.nio.ByteBuffer/wrap"
                        + " (.getBytes \"" + body + "\" \"UTF-8\")) "
                        + schema + " {:cloffle/backend :simdjson})")).valAt(ID));
    }

    @Test
    public void simdjsonBackendPreservesDuplicateAndMalformedFallbackSemantics() {
        String schema = "[:map [:id :long]]";
        IPersistentMap first = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"id\\\":1,\\\"id\\\":2}\" \"UTF-8\") "
                        + schema + " {:cloffle/backend :simdjson})");
        IPersistentMap last = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"id\\\":1,\\\"id\\\":2}\" \"UTF-8\") "
                        + schema
                        + " {:cloffle/backend :simdjson :cloffle/duplicates :last})");
        assertEquals(1L, first.valAt(ID));
        assertEquals(2L, last.valAt(ID));

        IPersistentMap permissive = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"ignored\\\":wat,\\\"id\\\":7}\" "
                        + schema + " {:cloffle/backend :simdjson})");
        assertEquals(7L, permissive.valAt(ID));
        IPersistentMap malformedTail = (IPersistentMap) eval(
                "(cloffle.json/project \"{\\\"id\\\":7, this is not json\" "
                        + schema + " {:cloffle/backend :simdjson})");
        assertEquals(7L, malformedTail.valAt(ID));
    }

    @Test
    public void simdjsonBackendProducesTruffleStringViewsAndMaterializedEscapes() {
        IPersistentMap value = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"name\\\":\\\"Ada\\\"}\" \"UTF-8\")"
                        + " [:map [:name :string]]"
                        + " {:cloffle/backend :simdjson :cloffle/strings :truffle})");
        Object name = value.valAt(NAME);
        assertTrue(name instanceof TruffleString);
        assertEquals("Ada", ((TruffleString) name).toJavaStringUncached());

        IPersistentMap escaped = (IPersistentMap) eval(
                "(cloffle.json/project (.getBytes \"{\\\"name\\\":\\\"A\\\\u0064a\\\"}\" \"UTF-8\")"
                        + " [:map [:name {:cloffle/materialize true} :string]]"
                        + " {:cloffle/backend :simdjson :cloffle/strings :truffle})");
        assertEquals("Ada", ((TruffleString) escaped.valAt(NAME)).toJavaStringUncached());
    }

    @Test
    public void simdjsonBackendReadsManagedTruffleInput() {
        TruffleString truffle = TruffleString.fromJavaStringUncached(
                "{\"name\":\"雪\"}", TruffleString.Encoding.UTF_8);
        Object value = JsonTypedProjectPlan.project(
                truffle,
                RT.vector(Keyword.intern("map"), RT.vector(NAME, Keyword.intern("string"))),
                RT.map(Keyword.intern("cloffle", "backend"), Keyword.intern("simdjson"),
                        Keyword.intern("cloffle", "strings"), Keyword.intern("truffle")));
        Object name = ((IPersistentMap) value).valAt(NAME);
        assertTrue(name instanceof TruffleString);
        assertEquals("雪", ((TruffleString) name).toJavaStringUncached());
    }

    @Test
    public void simdjsonBackendFallsBackForOffsetTruffleInput() {
        String json = "{\"name\":\"Ada\"}";
        TruffleString base = TruffleString.fromJavaStringUncached(
                "xx" + json + "yy", TruffleString.Encoding.UTF_8);
        TruffleString input = TruffleString.SubstringByteIndexNode.getUncached().execute(
                base, 2, json.getBytes(StandardCharsets.UTF_8).length,
                TruffleString.Encoding.UTF_8, true);
        Object value = JsonTypedProjectPlan.project(
                input,
                RT.vector(Keyword.intern("map"), RT.vector(NAME, Keyword.intern("string"))),
                RT.map(Keyword.intern("cloffle", "backend"), Keyword.intern("simdjson"),
                        Keyword.intern("cloffle", "strings"), Keyword.intern("truffle")));
        assertEquals("Ada", ((TruffleString) ((IPersistentMap) value).valAt(NAME))
                .toJavaStringUncached());
    }

    @Test
    public void subtreeEarlyExitSkipsNestedObjectRemainder() {
        // :owner only needs :login. Trailing fields inside :owner (including nested structures)
        // should be skipped without error, and :repo after :owner should still be read.
        String json = "{\"owner\":{\"login\":\"clojure\",\"extra\":{\"nested\":[1,2,3]},\"id\":317875},\"repo\":\"core\"}";
        String schema = "[:map [:owner [:map [:login :string]]] [:repo :string]]";
        IPersistentMap result = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json.replace("\"", "\\\"") + "\" " + schema + ")");
        IPersistentMap owner = (IPersistentMap) result.valAt(Keyword.intern("owner"));
        assertEquals("clojure", owner.valAt(Keyword.intern("login")));
        assertEquals("core", result.valAt(Keyword.intern("repo")));
    }

    @Test
    public void subtreeEarlyExitSkipsNestedArrayRemainder() {
        // :items only needs index 0. Trailing array elements should be skipped, and :count read.
        String json = "{\"items\":[{\"name\":\"first\"},{\"name\":\"second\"},{\"name\":\"third\"}],\"count\":42}";
        String schema = "[:map [:items [:tuple [:map [:name :string]]]] [:count :long]]";
        IPersistentMap result = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json.replace("\"", "\\\"") + "\" " + schema + ")");
        IPersistentVector items = (IPersistentVector) result.valAt(Keyword.intern("items"));
        assertEquals(1, items.count());
        IPersistentMap first = (IPersistentMap) items.nth(0);
        assertEquals("first", first.valAt(Keyword.intern("name")));
        assertEquals(42L, result.valAt(Keyword.intern("count")));
    }

    @Test
    public void intQuadKeyComparisonMatchesVariousKeyLengths() {
        // Keys of length 1 ("a"), 2 ("id"), 3 ("url"), 4 ("name"), 5 ("price"), 9 ("full_name")
        String json = "{\"a\":1,\"id\":2,\"url\":\"u\",\"name\":\"n\",\"price\":10.5,\"full_name\":\"full\"}";
        String schema = "[:map [:a :long] [:id :long] [:url :string] [:name :string] [:price :double] [:full_name :string]]";
        IPersistentMap result = (IPersistentMap) eval(
                "(cloffle.json/project \"" + json.replace("\"", "\\\"") + "\" " + schema + ")");
        assertEquals(1L, result.valAt(Keyword.intern("a")));
        assertEquals(2L, result.valAt(Keyword.intern("id")));
        assertEquals("u", result.valAt(Keyword.intern("url")));
        assertEquals("n", result.valAt(Keyword.intern("name")));
        assertEquals(10.5, result.valAt(Keyword.intern("price")));
        assertEquals("full", result.valAt(Keyword.intern("full_name")));
    }
}
