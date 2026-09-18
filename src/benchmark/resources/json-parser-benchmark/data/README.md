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
