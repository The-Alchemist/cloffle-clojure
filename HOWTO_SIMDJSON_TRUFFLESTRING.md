# HOWTO: Benchmark the simdjson-java TruffleString parser from Cloffle

This document is written for an agent that will wire the experimental
[simdjson-java](https://github.com/The-Alchemist/simdjson-java) schema-based JSON parser into
Cloffle's JMH harness and measure it. Everything in the "Setup" and "Smoke test" sections was
verified end to end on this machine (macOS aarch64, GraalVM CE 25.0.4.1, Truffle 25.3.4.1) before
this file was written. The "Writing the benchmark" section is a recommended design, not a verified
run — the JMH fork flags in particular need confirming on your first run.

## Why this measurement is worth making

simdjson-java has an experiment branch whose schema-based parser can populate record fields typed
as `com.oracle.truffle.api.strings.TruffleString` instead of `java.lang.String`. JSON is UTF-8 on the
wire and `TruffleString` can hold UTF-8 natively, so that path skips the UTF-8 to UTF-16 transcode
that `new String(bytes, UTF_8)` performs.

That experiment concluded with an open question it could not answer on its own. Measured inside
simdjson-java's own benchmarks, `TruffleString` won on a string-heavy schema (+13.4% throughput,
-55.5% allocation) and did nothing on a string-light schema (-1.6% throughput, inside a ±876 ops/s
error bar). The reason is that a plain-Java benchmark has to convert the `TruffleString` back to a
`java.lang.String` to do anything with it, which pays back the transcode it just saved.

Cloffle is the caller that does not have to pay that back. Clojure-on-Truffle already represents
guest strings as `TruffleString`, so a JSON document parsed straight into `TruffleString` fields can
flow into guest code with no conversion at all. Measuring that is the point of this exercise: it is
the only configuration where the `TruffleString` return type can show its full value.

## Versions must match

Cloffle's `deps.edn` pins Truffle at `25.3.4.1`, and the simdjson-java experiment branch builds
against the same `25.3.4.1`. Keep them equal. If you bump one, bump both, or you will get
`LinkageError` or `NoSuchMethodError` from the `TruffleString` node classes rather than a clean
dependency conflict.

The parser also requires the incubating Vector API and a GraalVM JDK. Cloffle already runs on
`/opt/homebrew/opt/graalvm`, so no change is needed there.

## Setup

### 1. Build and install the parser

The experiment lives in a git worktree next to the main simdjson-java checkout:

```
cd /Users/karl-medplum/Development/digital-alchemy/simdjson-java-trufflestring
./gradlew publishToMavenLocal
```

This installs into `~/.m2/repository`. The version string is derived from the git branch by the
axion-release plugin, so the branch `experiment/trufflestring` produces:

```
org.simdjson/simdjson-java {:mvn/version "0.4.1-experiment-trufflestring-SNAPSHOT"}
```

Confirm what actually landed before writing it into `deps.edn`, because a different branch produces
a different version:

```
ls ~/.m2/repository/org/simdjson/simdjson-java/
```

Do not pass `-x sign` to that Gradle command. There is no `sign` task in this project and Gradle
fails with `Task 'sign' not found`; the signing block is conditional and already inert locally.

### 2. Add the dependency

`tools.deps` reads `~/.m2/repository` as its local cache, so the locally-installed snapshot resolves
with no `:mvn/local-repo` or `:local/root` trickery. Verified:

```
clojure -Sdeps '{:deps {org.simdjson/simdjson-java {:mvn/version "0.4.1-experiment-trufflestring-SNAPSHOT"}}}' -Spath
```

Add it to the `:benchmark` alias in `deps.edn`:

```clojure
:benchmark {:extra-paths ["src/benchmark/java" "src/benchmark/resources"]
            :extra-deps {org.openjdk.jmh/jmh-core {:mvn/version "1.37"}
                         org.openjdk.jmh/jmh-generator-annprocess {:mvn/version "1.37"}
                         org.simdjson/simdjson-java {:mvn/version "0.4.1-experiment-trufflestring-SNAPSHOT"}}}
```

On the experiment branch `truffle-api` is a compile-scope (`implementation`) dependency rather than
`compileOnly`, because `TruffleString` appears in the parser's public API. It therefore arrives
transitively at the same `25.3.4.1` Cloffle already pins, which is why the versions matching matters.

### 3. JVM flags

Two flags are mandatory at runtime, on top of the flags Cloffle already passes:

| Flag | Why |
| --- | --- |
| `--add-modules=jdk.incubator.vector` | Required. The parser's stage-1 classes reference `jdk.incubator.vector`, which is not resolved by default. Without it you get `NoClassDefFoundError: jdk/incubator/vector/ByteVector` on first parse. |
| `-Dorg.simdjson.species=128` | Selects the 128-bit vector width. Read once in a `VectorUtils` static initializer, so it must be set at JVM launch, not from inside a benchmark. |

`org.simdjson.species` accepts `preferred` (the default), `128`, `256`, or `512`. Anything else
throws `IllegalArgumentException` from the static initializer. On aarch64 (NEON) `preferred` already
resolves to 128-bit, so setting `128` explicitly is what makes the run reproducible and comparable
against an x86 machine where `preferred` would pick 256 or 512. Set it explicitly and record it in
any results you write up.

Cloffle's existing `cloffle-jvm-opts` (`-Xss4m`, `--enable-native-access=ALL-UNNAMED`,
`--sun-misc-unsafe-memory-access=allow`, `-Dpolyglotimpl.AttachLibraryFailureAction=throw`) are all
compatible with the parser; nothing there needs removing.

### 4. Heap

Each `SimdJsonParser` instance eagerly allocates roughly 200 MB at construction: an `int[capacity]`
index array (34M ints, about 136 MB) plus two `byte[capacity]` buffers of 34 MB each, where
`capacity` defaults to 34 MiB. Three parser instances in one `@State` object is about 600 MB before
you have parsed anything.

For benchmarking, use the sizing constructor with a capacity that fits your document rather than the
default:

```java
new SimdJsonParser(1 << 22, 1024, TruffleStringMode.COPY)  // 4 MiB capacity
```

Otherwise raise `-Xmx` and be aware the allocation shows up in any `-prof gc` numbers you take
across `@Setup`.

## Smoke test

Run this before writing any benchmark. It is the exact command that was verified working, and it
isolates classpath and flag problems from benchmark problems.

```java
// /tmp/sjprobe/Probe.java
import com.oracle.truffle.api.strings.TruffleString;
import org.simdjson.SimdJsonParser;
import org.simdjson.TruffleStringMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Probe {
    public record User(TruffleString name, TruffleString screen_name) {}
    public record Status(User user) {}
    public record Twitter(List<Status> statuses) {}

    public static void main(String[] args) {
        String json = "{\"statuses\":[{\"user\":{\"name\":\"caf\\u00e9 \\ud83d\\ude00\",\"screen_name\":\"karl\"}}]}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[raw.length + 64];
        System.arraycopy(raw, 0, padded, 0, raw.length);

        for (TruffleStringMode mode : TruffleStringMode.values()) {
            Twitter t = new SimdJsonParser(mode).parse(padded, raw.length, Twitter.class);
            TruffleString name = t.statuses().get(0).user().name();
            System.out.println(mode + " -> '" + name.toJavaStringUncached()
                + "' codeRange=" + name.getCodeRangeUncached(TruffleString.Encoding.UTF_8));
        }
    }
}
```

Compile it against the Cloffle benchmark classpath and run it with the flags from above. Expected
output, with both modes producing a `VALID` code range on a payload that exercises a multi-byte
character, a surrogate pair, and a `\u` escape:

```
COPY -> 'café 😀' codeRange=VALID
LAZY -> 'café 😀' codeRange=VALID
```

Cloffle's benchmark sources compile with `--release 25` (`src/build/03-benchmark.clj`), matching the
runtime in `src/build/02-compile.clj`, so benchmark code may use any Java 25 platform API.

## The API you are measuring

### Schema

The parser is schema-based: you hand it a `Class` and it drives construction from that. Use Java
records whose components are typed `TruffleString` wherever you want the UTF-8 path. Field names must
match the JSON keys. Nested records and `List<T>` both work.

### Modes

`org.simdjson.TruffleStringMode` selects how string fields are materialized. Benchmark all three
legs — `String`, `COPY`, and `LAZY` — so the comparison is self-contained.

`TruffleStringMode.COPY` copies the string bytes into a fresh `TruffleString`. It is the safe default
and it is the mode that won on the string-heavy schema. It places no requirement on the input buffer.

`TruffleStringMode.LAZY` returns zero-copy `TruffleString` views into the input buffer for unescaped
strings, falling back to a `TruffleStringBuilder` for strings containing escapes. It has two
consequences you must respect:

1. **The input buffer must carry at least 64 bytes of padding past `len`.** The returned strings are
   views into that buffer, so the parser cannot transparently copy into its internal padded buffer
   the way it does otherwise. If the padding is missing, `parse` throws `JsonParsingException` with a
   message naming the padding requirement. Allocate `new byte[len + 64]` as in the smoke test.
2. **The buffer must not be mutated or reused while the parsed objects are alive.** A benchmark that
   reuses one input array across iterations is fine because the bytes never change; a harness that
   refills the array is not.

For a `String`-typed baseline, use the released parser from the main checkout, or simply declare the
record components as `String` — the same parser handles both, and the mode setting only affects
`TruffleString`-typed components.

### Reference numbers

From simdjson-java's own `StringMaterializationBenchmark` on `twitter.json`, for orientation only.
These came off a different harness than Cloffle's, so treat them as the shape of the expected result
rather than a target to reproduce.

| Schema | Leg | Throughput | Allocation |
| --- | --- | --- | --- |
| String-heavy (~10 string fields per status) | `String` | 3,317 ± 163 ops/s | 291,752 B/op |
| String-heavy | `COPY` | 3,761 ± 98 ops/s | 129,800 B/op |
| String-heavy | `LAZY` | 3,638 ± 176 ops/s | 143,408 B/op |
| String-light | `String` | 4,227 ± 251 ops/s | 11,088 B/op |
| String-light | `COPY` | 4,160 ± 876 ops/s | 12,688 B/op |
| String-light | `LAZY` | 3,870 ± 181 ops/s | 9,608 B/op |

The string-heavy row is the one Cloffle should be able to beat, because Cloffle does not convert the
result back to `java.lang.String`.

### Numbers, for completeness

Do not route numeric parsing through `TruffleString.ParseIntNode` or `ParseDoubleNode`. simdjson's
`NumberParser` works directly on the byte buffer and never builds a string, so using the Truffle
nodes means materializing a string first. Measured per-op nanoseconds: `NumberParser.parseInt` 4.41
vs. `ParseIntNode` 9.16 including materialization; `parseDouble` 10.57 vs. 11.56. `ParseDoubleNode`
is faster (7.69) only when the string already exists, which in this pipeline it does not.

## Writing the benchmark

Put it at `src/benchmark/java/net/javacrumbs/cloffle/benchmark/SimdJsonTruffleStringBenchmark.java`
and follow the conventions of the existing benchmarks in that directory.

The thing that makes this measurement Cloffle-specific, and the thing to get right: **do not
`toJavaString` the result.** Consume the `TruffleString` the way guest Clojure code would, so the
benchmark reflects the no-conversion path. Blackhole the `TruffleString` itself, or feed it into the
guest operation you actually care about (a keyword lookup, a map assoc, a `str` concat). If you
convert to `java.lang.String` to check the result, you have rebuilt simdjson-java's own benchmark and
will measure its result, not Cloffle's.

A worthwhile end-to-end leg, beyond the three parse-only legs, is JSON bytes to a guest Clojure data
structure: parse into `TruffleString`-typed records and then build the guest map, versus doing the
same through `String`. That is the number that would justify the dependency.

### Fork flags

Cloffle's harness builds its fork JVM arguments in `src/build/03-benchmark.clj` from
`cloffle-jvm-opts` plus `jmh-system-opts`, and neither includes the two flags this parser needs. You
have two options:

The lower-friction one, which avoids editing the build, is to declare them on the benchmark class:

```java
@Fork(value = 1, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Dorg.simdjson.species=128"})
```

This is the recommended starting point but was **not** verified. The risk is that JMH's host JVM —
not just the forked one — may need to load your benchmark class, which transitively references
simdjson classes, in which case the host would also need `--add-modules`. If you see
`NoClassDefFoundError: jdk/incubator/vector/ByteVector` before any fork starts, that is what
happened.

The robust option is to add both flags to `jmh-system-opts` in `src/build/03-benchmark.clj`, which
puts them on the host and gets them inherited by forks. `--add-modules` is harmless for Cloffle's
other benchmarks; `-Dorg.simdjson.species` is ignored by anything that does not read it. Prefer this
if the annotation route gives you any trouble.

### Running

```
clj -T:build compile-benchmarks
clj -T:build run-benchmarks :args '["SimdJsonTruffleStringBenchmark"]'
```

Add `-prof gc` to the args for allocation numbers, which for this comparison matter as much as
throughput — the string-heavy allocation drop was the largest and most reliable effect in the
original experiment.

## Troubleshooting

These are the failures actually hit while verifying this document.

**`NoClassDefFoundError: jdk/incubator/vector/ByteVector`** — `--add-modules=jdk.incubator.vector`
is missing from the JVM that is running the parse. See the fork flags discussion above.

**`NoClassDefFoundError: Could not initialize class com.oracle.truffle.api.Truffle`, with a stack
through `TruffleString` node classes** — this is a Truffle runtime initialization failure surfacing
at the first `TruffleString` operation, and the real cause is hidden. Provoke it directly to see it:

```java
try { System.out.println(com.oracle.truffle.api.Truffle.getRuntime().getName()); }
catch (Throwable t) { t.printStackTrace(); }
```

When this was hit, the root cause was `ClassNotFoundException: org.graalvm.home.Version` — a
hand-assembled classpath that had `truffle-api` and `truffle-runtime` but was missing the
`org.graalvm.polyglot/polyglot` and `org.graalvm.sdk` artifacts that `truffle-runtime` needs. Going
through `deps.edn` resolution instead of a hand-built `-cp` fixes it, since those arrive
transitively. If you are scripting a bare `java -cp`, get the classpath from
`clj -A:benchmark -Spath` rather than assembling it yourself.

Note also that `truffle-api` alone, with no `truffle-runtime`, is enough to use `TruffleString`
correctly — that is how simdjson-java's own tests run. It gives you the interpreter-only fallback
runtime, which is fine for correctness but wrong for benchmarking. Cloffle brings
`truffle-runtime` and sets `AttachLibraryFailureAction=throw` so a degraded runtime fails loudly
instead of silently costing you performance. Keep that flag on.

**`JsonParsingException` mentioning 64 bytes of padding** — you used `TruffleStringMode.LAZY` with an
unpadded input buffer. See the modes section.

**`Task 'sign' not found`** — you passed `-x sign` to `publishToMavenLocal`. Drop it.

**Truffle banner text interleaved into JMH's iteration output** — Cloffle already redirects engine
logs to `target/truffle-jmh.log` via `-Dpolyglot.log.file`; make sure you are going through
`run-benchmarks` rather than invoking `java` directly.

## Further reading

- `TRUFFLESTRING_EXPERIMENT.md` in the simdjson-java worktree — the full experiment writeup, its
  measurements, and why it stopped short of this measurement.
- `HOWTO_SEAFOAM.md` and `PARTIAL_ESCAPE_ANALYSIS.md` — both repos carry copies; use these if you
  want to check whether the parser's allocations are being scalar-replaced inside a Cloffle
  compilation unit.
- [TruffleString docs](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/TruffleStrings/)

## Cloffle fixed-projection backend experiment (2026-09-18)

The experiment described above now also has a Cloffle-native backend. It does not construct Java
records: simdjson-java stage 1 builds the structural index, then a fixed schema writes primitives
and raw string ranges directly into Cloffle's `TypedScanResult`. Cloffle's existing decoder remains
responsible for Java strings, lazy UTF-8 `TruffleString` views, escaped strings, and
`:cloffle/materialize`.

The simdjson-java work is on `experiment/cloffle-projection` at:

```
/Users/karl-medplum/Development/digital-alchemy/simdjson-java
```

Its locally published coordinate is:

```clojure
org.simdjson/simdjson-java
{:mvn/version "0.4.1-experiment-cloffle-projection-SNAPSHOT"}
```

Use the backend explicitly:

```clojure
(cloffle.json/project body schema {:cloffle/backend :simdjson})
(cloffle.json/project body schema
                      {:cloffle/backend :simdjson :cloffle/strings :truffle})
(cloffle.json/select body pointers {:cloffle/backend :simdjson})
```

The fast path accepts `byte[]`, zero-offset heap `ByteBuffer`, and managed materialized UTF-8
`TruffleString` with a zero-offset internal array. Other inputs, offset windows, dynamic vectors,
and container-valued `ANY` leaves retry Cloffle's custom scanner. A thread-local parser owns bounded
1 MiB index/scratch buffers; override that limit with
`-Dcloffle.json.simdjson.capacity=<bytes>`. The Vector API module is enabled by Cloffle's
tools.build JVM options and benchmarks pin `-Dorg.simdjson.species=128`.

### Measured result

JMH used one fork, two one-second warmups, three one-second measurements, and `-prof gc`. Times are
nanoseconds/op; allocations are bytes/op.

| Workload | Custom | SIMD projection | Direct simdjson record |
| --- | ---: | ---: | ---: |
| GitHub full | 1548 / 456 | 2137 / 520 | 2441 / 264 |
| GitHub early | 82 / 304 | 1586 / 368 | n/a |
| GitHub late | 1879 / 264 | 1965 / 328 | n/a |
| JSON:API | 261 / 632 | 296 / 696 | n/a |
| Doubles | 285 / 512 | 406 / 528 | n/a |
| Twitter first | 1252 / 2416 | 186203 / 2494 | 259008 / 137572 |

Truffle output reduced selected-string allocation as intended: GitHub was 496 B/op and Twitter-first
was about 1046 B/op. It did not overcome stage 1's full-document cost. In particular, Twitter-first
cannot exploit Cloffle's early exit, so it scans the entire large fixture. The separately gated
`-Dcloffle.json.simdjson.pe-scan=true` variant made GitHub-early worse (1775 ns/op, 824 B/op);
leave it off.

### PEA analysis

Per [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md), `gc.alloc.rate.norm` above is the gate; the graph only
says what allocated and why. Keep the default `-wi 3 -i 2`: a run shortened to `1/1` truncates the
final-tier compilation, and the named guest root disappears from the dump.

```bash
clojure -T:build explain-allocations \
  :benchmark '"JsonParserBenchmark.guestSimdjsonGithubEarlyBytes"' \
  :guest true :dump-path '"/tmp/cloffle-simdjson-dumps"'
```

The report for `guest-simdjson-github-early-bytes` and for the custom control
`guest-typed-github-early-bytes` is **identical**, down to node ids and source chains: 7 objects
scalar replaced, 3 surviving, and 5 cold low-tier stubs. The survivors are a `TruffleString` and a
`java.lang.String` merged across branches in `JsonTypedProjectPlan#decode`, plus a
`PersistentShapeMap` from `JsonTypedProject#buildShapeMap` that is loop-carried on the Bytecode DSL
dispatch loop — the pattern in `FIXME_shape_map_alloc.md`.

That identity is the finding. Backend selection happens inside `scanBoundary`, which is
`@TruffleBoundary`, so the guest compilation cannot see simdjson at all and the graph cannot
distinguish the two backends. The 64 B/op that separates them lives entirely behind the boundary, in
stage 1's padded copy and structural index. Graph analysis therefore offers no optimization lever
here, and the three survivors are pre-existing decode/shape-map costs shared by every backend.

The backend therefore remains experimental. Its useful result is that direct range projection gets
allocation close to the custom scanner, but the fixed cost of whole-document SIMD indexing loses to
Cloffle's schema-directed scalar scanner on these HTTP-sized and early-exit workloads.
