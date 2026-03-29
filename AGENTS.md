# AGENTS.md

## Cursor Cloud specific instructions

### Overview

Cloffle is a Truffle/GraalVM-based implementation of Clojure. It is a single-project Java/Clojure repo (not a monorepo) using `deps.edn` + `clojure.tools.build` as the primary build system. See `readme-cloffle.md` for command quick-reference and `CLOFFLE_NOTES.md` for architecture details.

### System dependencies

- **GraalVM CE 25.0.2** (JDK 25): installed at `/usr/lib/jvm/graalvm-community-openjdk-25.0.2+10.1`. `JAVA_HOME` and `PATH` are set via `/etc/profile.d/graalvm.sh` and `~/.bashrc`. Unlike OpenJDK, GraalVM provides full Truffle JIT compilation — no "fallback runtime" warnings.
- **Clojure CLI tools**: installed via `sudo bash linux-install-1.12.0.1530.sh` (bundled in repo root)
- **rlwrap**: `sudo apt-get install -y rlwrap` (for interactive `clj` REPL)

### Build, Test, Run

All primary commands use `clj -T:build <task>` (see `Makefile` for convenience aliases):

| Task | Command |
|------|---------|
| Compile | `clj -T:build compile-all` |
| JUnit tests | `clj -T:build run-tests` |
| Clojure test suite | `clj -T:build run-clj-tests` |
| REPL (interactive) | `make repl` (wraps `clj -T:build cloffle-repl` with stdin) |
| Run script | `clj -T:build cloffle-repl :args '["script.clj"]'` |
| Non-interactive eval | `clj -T:build cloffle-main :args '["-e" "(expression)"]'` |
| List all tasks | `clj -T:build help` |

### Caveats

- **GraalVM JVMCI deprecation warnings**: You may see `WARNING: sun.misc.Unsafe::objectFieldOffset` warnings from `truffle-runtime`. These are harmless deprecation notices from the Truffle runtime and do not affect functionality.
- **Interactive REPL**: `clj -T:build cloffle-repl` requires inherited stdin. Use `make repl` or `make cloffle-main-repl` for interactive sessions. `cloffle-main` via `clj -T:build` does not support interactive stdin well.
- **First compile downloads deps**: The first `clj -T:build compile-all` downloads all Maven dependencies to `~/.m2/repository`. Subsequent compiles are fast.
- **Test duration**: `run-tests` takes ~90s, `run-clj-tests` takes longer (runs the full Clojure test suite through the Truffle interpreter).
- **`run-tests` defaults to `:fresh true`**: This cleans `target/` before each run. Pass `:fresh false` for incremental test runs: `clj -T:build run-tests :fresh false`.
