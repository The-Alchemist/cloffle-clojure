# Cloffle README

Cloffle is a Truffle-based implementation of Clojure, with a goal of strong behavioral compatibility with JVM Clojure.

For detailed architecture notes, change logs, compatibility status, and implementation history, see `CLOFFLE_NOTES.md`.

## Quick Start

- List build tasks: `clj -T:build help`
- Start Cloffle REPL: `clj -T:build cloffle-repl`
- Run Cloffle JUnit tests: `clj -T:build run-tests`
- Run Clojure `test_clojure` through Cloffle: `clj -T:build run-clj-tests`

`run-tests`, `run-clj-tests`, and `run-pprint-tests` default to `:fresh true` (clean `target/` first).

## Makefile Convenience Targets

| Target | What it runs |
|--------|--------------|
| `make repl` or `make cloffle-repl` | Cloffle REPL (Truffle-based) |
| `make cloffle-main-repl` | Cloffle main REPL (`clojure.main`-compatible) |
| `make clojure-repl` | Plain JVM Clojure REPL |
| `make cloffle-run FILE=script.clj` | Run a script under Cloffle |
| `make cloffle-demo` | Cloffle demo mode |

## Canonical Documentation Split

- `readme-cloffle.md` (this file): quick orientation and command entry points.
- `CLOFFLE_NOTES.md`: comprehensive technical notes and historical record.