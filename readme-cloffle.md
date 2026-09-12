# Cloffle README

Cloffle is a Truffle-based implementation of Clojure, with a goal of strong behavioral compatibility with JVM Clojure.

For detailed architecture notes, change logs, compatibility status, and implementation history, see `CLOFFLE_NOTES.md`.

## Quick Start

- List build tasks: `clj -T:build help`
- Start Cloffle REPL: `clj -T:build cloffle-repl`
- Run Cloffle JUnit tests: `clj -T:build run-tests`
- Run Clojure `test_clojure` through Cloffle: `clj -T:build run-clj-tests`
- Stock-vs-Cloffle semantic probes: `clj -T:build audit-compat`
- External library parity: `clj -T:build compat-test`

Semantic catalog (intentional vs bug vs 1.12 baseline): `COMPAT_DIFFS.md`.
Risk audit history: `COMPATIBILITY_RISK_AUDIT.md`.

`run-tests` and `run-clj-tests` default to `:fresh true` (clean `target/` first).

## Makefile Convenience Targets

| Target | What it runs |
|--------|--------------|
| `make help` | List `build.clj` tasks (`clj -T:build help`) |
| `make repl` or `make cloffle-repl` | Cloffle REPL (Truffle-based) |
| `make cloffle-main-repl` | Cloffle main REPL (`clojure.main`-compatible) |
| `make bytecode-repl` | Truffle bytecode backend REPL |
| `make clojure-repl` | Plain JVM `clojure.main` (this repo’s compiled Clojure) |
| `make test` or `make clj-test` | Cloffle JUnit tests |
| `make test-clj` | Clojure `test_clojure` suite through Cloffle |
| `make compat-test` | External-project compatibility checks |
| `make audit-compat` | Stock 1.12.0 vs Cloffle differential probes |
| `make clean` or `make clj-clean` | `clj -T:build clean` |
| `make jar` or `make clj-jar` | Build the versioned JAR |
| `make cloffle-run FILE=script.clj` | Run a script under Cloffle |
| `make cloffle-demo` | Cloffle demo mode |

## Canonical Documentation Split

- `readme-cloffle.md` (this file): quick orientation and command entry points.
- `CLOFFLE_NOTES.md`: comprehensive technical notes and historical record.