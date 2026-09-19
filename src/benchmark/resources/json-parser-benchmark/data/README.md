# JSON benchmark fixtures

- `jsonapi.json` — small JSON:API-style article document (inline benchmarks).
- `entity16.json` — sixteen-field user record for shape-map / lookup benches.
- `rows.json` — eight-row array for indexed lookup benches.
- `escaped.json` — string with newline and `\u263a` escape exercises.
- `github-clojure-repo.json` — snapshot of
  `https://api.github.com/repos/clojure/clojure` downloaded 2026-09-17.
- `twitter.json` — Twitter API response from simdjson's public benchmark corpus:
  `https://raw.githubusercontent.com/simdjson/simdjson/master/jsonexamples/twitter.json`.
- `jsonplaceholder-post-1.json` — snapshot of
  `https://jsonplaceholder.typicode.com/posts/1` downloaded 2026-09-18.
- `popular-apis-composite.json` — synthetic webhook-style document mixing shapes seen in Stripe
  events, JSON:API `data`, GitHub repository counters, geolocation doubles, and indexed line items.

The fixtures are checked in so benchmark inputs do not change between runs and benchmarks do not
require network access.

## Allocation attribution

`gc.alloc.rate.norm` gives a per-op total; these two answer where it goes.

```bash
# Stage the pipeline so scan / decode / materialize fall out by subtraction
clojure -T:build run-json-parser-benchmarks :profile :staged-alloc

# Attribute by class and allocating frame (JFR, scoped to the measurement window)
clojure -T:build run-alloc-profile
clojure -T:build run-alloc-profile \
  :benchmark '"JsonTypedStagedAllocBenchmark.scanDecodeAndMaterialize"' \
  :params '{"fixture" "twitterLate"}'
```

## Running benchmarks

The JSON suite is split across five JMH classes (~150 `@Benchmark` methods). A regex like
`JsonParser.*` with the default 2×1s warmup/measurement often takes **20–40+ minutes** because
each Cloffle guest pays Truffle compilation during warmup.

Prefer scoped runs:

```bash
# GitHub-only smoke (~20s)
clojure -T:build run-json-parser-benchmarks

# Five parity fixtures: typed extract vs Jackson *ShapeMap (~2 min); 2×1s + gc.alloc.rate.norm
clojure -T:build run-json-parser-benchmarks :profile :typed-pairs

# ~5–10 min: all Cloffle guests + Jackson streaming fairness controls (quick 500ms iterations)
clojure -T:build run-json-parser-benchmarks :profile :cloffle

# ~10–15 min: add full-parse lookup guests vs Jackson/cloffle Java baselines
clojure -T:build run-json-parser-benchmarks :profile :fairness

# Full third-party baselines (Cheshire, simdjson, …) — use only when you need publishable numbers
clojure -T:build run-json-parser-benchmarks :profile :full

# Single method + GC (repeat with :compile false after the first run)
clojure -T:build run-benchmarks :args '["JsonParserCloffleExtractBenchmark.guestExtract" "-p" "guest=guestTypedGithubBytes" "-prof" "gc" "-wi" "1" "-i" "1" "-w" "500ms" "-r" "500ms"]'
```

Suite roles (compare like with like):

| Class | Cloffle API | Baseline |
|-------|-------------|----------|
| `JsonParserBenchmark` | `(json/project …)` + accessor (full document) | Jackson `readTree` + navigate; Java `JsonParser.parseBytes` |
| `JsonParserCloffleProjectBenchmark` | full `project` then manual map | Jackson `readTree` + `ObjectNode` |
| `JsonParserCloffleSelectBenchmark` | `json/select` (one scan) | Jackson `readTree` + `JsonNode.at` per pointer |
| `JsonParserCloffleExtractBenchmark` | typed `json/project` | `JsonParserJacksonStreamingBenchmark` (hand-written streaming) |
