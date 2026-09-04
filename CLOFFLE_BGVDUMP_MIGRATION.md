# Cloffle: convert MRI `seafoam` CLI → in-process `BgvDump`

This file is a self-contained handoff for an agent working in the **Cloffle** repo. You do not need to change the Seafoam gem or `java/seafoam-jruby` unless you find a real API gap. Do not keep shelling out to `seafoam` for automated PEA / scalar-replacement checks once `BgvDump` is wired. Do not add Graphviz / `cfg` / `render` / `edges`. Do not parse MRI CLI stdout.

Cloffle today starts one MRI `seafoam` process per subcommand (`list`, `describe`, `search`, occasionally `props`). The Java facade parses the `.bgv` once and answers the same queries in-process.

## Dependency

Maven coordinates (current jar version in this tree):

```xml
<dependency>
  <groupId>com.github.the-alchemist</groupId>
  <artifactId>seafoam-jruby</artifactId>
  <version>0.20</version>
</dependency>
```

Package: `com.github.thealchemist`.

The gem is embedded via JRuby. Cloffle does **not** need a Ruby install or Graphviz for these queries. JVM: `--enable-native-access=ALL-UNNAMED` (JRuby), same as seafoam-jruby’s Surefire `argLine`.

`BgvDump` is **not thread-safe**. One instance per thread. Always `close()` (try-with-resources). Open **once per dump file**, then run list / describe / search / props on that handle — that replaces N CLI processes.

```java
import com.github.thealchemist.BgvDump;
import com.github.thealchemist.DescribeResult;
import com.github.thealchemist.GraphInfo;
import com.github.thealchemist.SearchHit;
import com.github.thealchemist.SeafoamException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

try (BgvDump dump = BgvDump.open(Path.of(bgvPath))) {
  // queries below
}
```

`BgvDump.open(byte[])` accepts uncompressed BGV or gzip bytes (useful if the dump is already in memory).

## Command → API map

| Cloffle used to run | Call this | Notes |
|---------------------|-----------|--------|
| `seafoam file.bgv list` | `dump.listGraphs()` | `List<GraphInfo>`; no `file.bgv:N` prefix |
| `seafoam file.bgv:N describe` | `dump.describe(N)` | `DescribeResult` |
| `seafoam file.bgv:N search TERM` | `dump.search(N, TERM)` | empty list if no match |
| `seafoam file.bgv search TERM` | `dump.search(TERM)` | dump-wide; human / pick-dump workflow |
| `seafoam file.bgv:N:ID props` | `dump.nodeProps(N, ID)` | `Map<String, Object>` |

Do **not** implement `file.bgv:N:ID search`. The MRI CLI rejects a node in the spec for `search`; graph-scoped Java search is `search(graphIndex, term)` only.

Leave `cfg`, `render`, `edges`, and `seafoam --version` unused.

## Types

- `GraphInfo(int index, String name)` — `name` is the slash-joined phase path (what CLI `list` prints after `file.bgv:`). Substring-match it. Do not parse MRI line format.
- `DescribeResult(String summary, Map<String, Integer> nodeCounts)` — `summary` is the CLI describe **first line**. `nodeCounts()` keys are **simple Graal class names** (`AddNode`, `IfNode`, `CommitAllocationNode`). Count `0` ⇒ **key absent**, not `0`.
- `SearchHit(int graphIndex, Integer nodeId, String edge, String snippet)` — `nodeId` is `null` for header/edge hits. `snippet` keeps **original dump casing** (e.g. `MethodCallTarget`, `new_instance_or_null`). Matching is case-insensitive; results are not lowercased.
- Failures: `SeafoamException`. Message contains MRI `ArgumentError` text: `graph not found`, `node not found`. Invalid index / missing node **throws**. Unknown search term does **not**.

## How to replace each Cloffle pipeline

### `list` / `inspect-bgv` / `pick-richest-bgv`

Stop splitting CLI lines. Use:

```java
List<GraphInfo> listed = dump.listGraphs();
int richness = listed.size();
boolean failedCompilation = listed.stream().anyMatch(g -> g.name().contains("Exception"));
```

Phase pickers (last match wins; **your** helper, not an API):

- last `name` containing `After parsing`
- last containing `FinalPartialEscapePhase`
- last containing `After low tier`, else last containing `/After phase jdk.graal.compiler.core.phases.LowTier`

Do **not** hard-code graph index `0` as parsing or `4` as low tier. Those indices happen to hold on a **dump-level-1** fib (five graphs). A dump-level-3 Java compilation lists dozens of `Before phase` / `After phase` graphs first; `After parsing` can be index `6` or later, and graph `0` can be an empty `Before phase …PhaseSuite%s` with **0 nodes**. Always `findLastContaining`.

If a needle is absent, the finder returns `null`. Do not treat that as `SeafoamException`. Fib-like dumps have no PEA name; Cloffle dumps usually do.

`BgvDump` does **not** special-case `Exception` in names. You still detect it.

### `describe` (parsing / PEA / low-tier)

```java
DescribeResult desc = dump.describe(graph.index());
String summary = desc.summary();           // e.g. contains "nodes"
Map<String, Integer> counts = desc.nodeCounts();
boolean hasCommitAlloc = counts.containsKey("CommitAllocationNode");
```

Cloffle used to grep **describe stdout** for alloc markers:

`CommitAllocationNode`, `CommitAllocation`, `NewInstanceNode`, `NewArrayNode`, `new_instance_or_null`, `new_array_or_null`, `TruffleNew`, `AllocatingBoxNode`, `BoxNode$AllocatingBox`

Map those as follows:

- Node **class** names → `nodeCounts().containsKey("CommitAllocationNode")` (and `NewInstanceNode`, `NewArrayNode`, …). Missing key = not present.
- **Descriptors** `new_instance_or_null` / `new_array_or_null` are **not** `nodeCounts()` keys. Low-tier remaining allocations are `ForeignCallNode`s whose **property JSON** holds those stub names. You **must** `search` for them. `describe` alone cannot fail the checker.

Manual docs that grepped describe for `CallNode`, `Virtual`, `ForeignCallNode`, `TruffleNew` should grep `nodeCounts()` keys and/or `search` the same terms. Do not parse `summary` for class names beyond the first-line features (`nodes`, branches, calls).

`describe` runs Seafoam passes on a **copy**. `search` / `nodeProps` use the **raw** parsed graph. Do not assume describe node ids equal search node ids for the same phase.

### `search` (PEA + low-tier automation)

CLI: no match ⇒ exit 0, empty stdout. Java: empty `List`, never throw.

Per-phase terms (run on FinalPartialEscapePhase and After low tier):

```java
List<String> terms = List.of(
    "new_instance_or_null", "new_array_or_null",
    "CommitAllocation", "NewInstanceNode", "NewArrayNode",
    "TruffleNew", "AllocatingBox");
List<SearchHit> searchHits = new ArrayList<>();
for (String term : terms) {
  searchHits.addAll(dump.search(low.index(), term));
}
```

Search case-insensitive-substrings the JSON of header, node, and edge props. That is how `new_instance_or_null` is found. Pass Graal/Truffle **simple** spellings (`NewInstanceNode`, `new_instance_or_null`, `MethodCallTarget`); snippets come back with dump casing.

Do **not** search for `org.graalvm.compiler.*` FQNs. GraalVM 25 dumps use `jdk.graal.compiler.*` in `node_class` JSON (e.g. `jdk.graal.compiler.nodes.java.MethodCallTargetNode`). Simple names still match both eras. `nodeCounts()` keys are already simple names (`AddNode`, `IfNode`, `CommitAllocationNode`) — those did not pick up the package rename.

Dump-wide (docs / find the right file): `dump.search("PersistentShapeMap")`, `InvokeVar2`, `ForeignCallNode`. Every hit has `graphIndex()`. Prefer a hit with non-null `nodeId()` before `nodeProps`.

Fib low-tier on GraalVM 25 uses `InvokeNode` / `HotSpotDirectCallTargetNode`, not `MethodCallTargetNode` (that name is still present at **After parsing**). `CallNode` as a search term may miss; use `InvokeNode`, `MethodCallTarget`, or `ForeignCallNode` as appropriate. Hit **counts** are not stable across Graal versions — assert non-empty / distinct `graphIndex()`, not `size() == 4`.

### `props` (after a search hit)

```java
Integer nodeId = hit.nodeId();
if (nodeId == null) {
  // header or edge hit; skip or search another hit
} else {
  Map<String, Object> props = dump.nodeProps(hit.graphIndex(), nodeId);
  Map<?, ?> source = (Map<?, ?>) props.get("nodeSourcePosition");
  // e.g. origin like InvokeVar2.doClojureClosure
}
```

Missing node ⇒ `SeafoamException` (`node not found`), same as CLI error.

## Checker (keep this logic in Cloffle)

A dump **passes** scalar-replacement iff:

1. no listed graph `name` contains `Exception`
2. a low-tier graph exists (`After low tier` or the `LowTier` FQN above)
3. the union of describe-marker hits and search hits for that low-tier graph is empty

Reference shape:

```java
List<GraphInfo> listed = dump.listGraphs();
boolean exception = listed.stream().anyMatch(g -> g.name().contains("Exception"));
GraphInfo parsing = findLastContaining(listed, "After parsing");
GraphInfo pea = findLastContaining(listed, "FinalPartialEscapePhase");
GraphInfo low = or(
    findLastContaining(listed, "After low tier"),
    findLastContaining(listed, "/After phase jdk.graal.compiler.core.phases.LowTier"));

DescribeResult lowDesc = low == null ? null : dump.describe(low.index());
List<SearchHit> searchHits = new ArrayList<>();
if (low != null) {
  for (String term : terms) {
    searchHits.addAll(dump.search(low.index(), term));
  }
}
boolean ok = !exception && low != null && noAllocMarkers(lowDesc, searchHits);
```

`findLastContaining` / `noAllocMarkers` / `rg`-style filtering stay in Cloffle. No new Seafoam methods.

For `noAllocMarkers`: treat `nodeCounts()` keys that match class-name markers as describe hits; treat non-empty `searchHits` as search hits. Descriptor-only markers only appear in search.

## What to delete in Cloffle

- Process builders / `sh` / babashka that invoke `seafoam … list|describe|search|props`
- Parsers for `path.bgv:N name` list lines and multi-line describe stdout
- Assumptions that empty search is a non-zero exit
- MRI gem / `bundle exec seafoam` as a runtime dependency for this check (keep only if humans still want the CLI)

Keep generating Graal `.bgv` dumps the way you do today. Only the **inspect** side moves in-process. On GraalVM 25 the dump system properties are `-Djdk.graal.Dump=:N` and `-Djdk.graal.PrintGraphWithSchedule=true` (legacy `-Dgraal.Dump` is deprecated). BGV magic is still `7.0`.

## Out of scope / do not do

- Byte-identical CLI stdout
- Graphviz, render, cfg, edges
- Changing Seafoam unless `search` cannot see property text (descriptors). If that happens, it is an API gap; file it against seafoam-jruby rather than grepping CLI again.
- Requiring Graphviz on CI for PEA checks

## GraalVM 25 dump shape (observed)

Reference Java dumps in this repo: `examples/graalvm-java25/` (top-level `examples/fib-java.bgv.gz` and `examples/java/` are symlinks). Generated with local `JAVA_HOME` and `-Djdk.graal.Dump`.

**Phase names** in `GraphInfo.name()` look like:

```
20:Fib.fib(int)/After parsing
20:Fib.fib(int)/Before phase jdk.graal.compiler.phases.common.HighTierLoweringPhase%s
20:Fib.fib(int)/After high tier
20:Fib.fib(int)/After mid tier
20:Fib.fib(int)/After low tier
```

Notes for substring pickers:

- `After parsing` / `After high tier` / `After mid tier` / `After low tier` still exist. Prefer those labels over old FQNs such as `org.graalvm.compiler.phases.common.LoweringPhase` (now `jdk.graal.compiler.phases.common.HighTierLoweringPhase`).
- Some `Before phase …` names append a literal `%s` (IGV name-template leftover). `contains("After parsing")` is unaffected; if you match a `Before phase` FQN, allow an optional `%s` suffix.
- The numeric prefix (`20:` vs older `17:`) is a compilation id. Do not assert it.

**Compile-only fib** (`-Djdk.graal.Dump=:1`, `-XX:CompileOnly=Fib::fib`): five graphs, parsing = index `0`, low tier = index `4`. **Do not generalize that.** `JavaExamples` at dump level `:3` emits a long phase list; `After parsing` is not first.

**Search JSON** for a parse-tier call target (fib):

`...node_class":"jdk.graal.compiler.nodes.java.MethodCallTargetNode"...`

Seafoam `describe` / `nodeCounts()` still histogram by **simple** class name. Passes treat both `org.graalvm.compiler` and `jdk.graal.compiler` node FQNs.

seafoam-jruby tests load `examples/graalvm-java25/fib-java.bgv.gz`. They find parsing / low-tier by `name().contains(...)`, not by index `0`/`4` except where a test already knows this fib file.

## Sanity check against seafoam-jruby tests

Contracts are locked in seafoam-jruby `BgvDumpTest` on GraalVM 25 `fib-java.bgv.gz` (no Cloffle dump required): empty search, `nodeCounts` omit zeros, property-text search, `SeafoamException` on missing node/graph, search → `nodeProps`. Cloffle tests should use a **Cloffle** `.bgv` and the checker above, not fib. Do not copy seafoam-jruby’s old assumptions (exactly 5 graphs, method id `17:`, exactly 4 dump-wide `MethodCallTarget` hits, `nodeProps(0, 13)`).
