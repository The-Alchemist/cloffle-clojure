# Motivation

The motivation of this project is a Truffle-based implementation of the Clojure language.
Forked from https://github.com/lukas-krecan/cloffle and https://github.com/clojure/clojure/

# History

Originally, the Cloffle code was at `src/main/java` and simply wrapped the Clojure code at `src/jvm/clojure`, trying to use the Clojure code by wrapping it in Truffle APIs.
The two codebases have been merged into a single source tree at `src/jvm`, with Truffle APIs injected into the Clojure code directly.

The goal is to be API compatible with Clojure.

# Build System

## REPL and run targets (Makefile)

| Target | What it runs |
|--------|--------------|
| `make repl` or `make cloffle-repl` | **Cloffle** REPL (Truffle-based) |
| `make cloffle-main-repl` | **Cloffle** main REPL (clojure.main-compatible) |
| `make clojure-repl` | **Plain Clojure** REPL (standard JVM) |
| `make cloffle-run FILE=script.clj` | Run a script under **Cloffle** |
| `make cloffle-demo` | **Cloffle** demo mode |

Cloffle = Truffle-based implementation. Clojure = standard JVM implementation.

## tools.build tasks (build.clj)

- `clj -T:build cloffle-repl` — Cloffle REPL (used by `make cloffle-demo`, `make cloffle-run`)
- `clj -T:build cloffle-main` — CloffleMain CLI (non-interactive)
- `clj -T:build run-tests` — All tests (Clojure + Cloffle)
- `clj -T:build compat-test` — Run suite under both Clojure and Cloffle, diff results

---

I don't understand how bootstraping works, and the original code used `pom.xml` and `build.xml` is a complex procedure to boostrap and build Clojure.

Now this is even more complicated because Cloffule needs Clojure to build and run, so the build order is confusion.

However, we're trying to move to a `tools.build`-based build system anyway, but we don't wanna modify the old Maven / Ant build system because we want to be able to merge upstream changes.  We want to exclusively use the `tools.build` system for our own use.