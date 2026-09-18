# JSON benchmark fixtures

- `github-clojure-repo.json` — snapshot of
  `https://api.github.com/repos/clojure/clojure` downloaded 2026-09-17.
- `twitter.json` — Twitter API response from simdjson's public benchmark corpus:
  `https://raw.githubusercontent.com/simdjson/simdjson/master/jsonexamples/twitter.json`.
- `jsonplaceholder-post-1.json` — snapshot of
  `https://jsonplaceholder.typicode.com/posts/1` downloaded 2026-09-18.

The fixtures are checked in so benchmark inputs do not change between runs and benchmarks do not
require network access.
