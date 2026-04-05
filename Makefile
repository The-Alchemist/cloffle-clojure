# Cloffle Makefile — quick entrypoints (full list: make help)
#
# Common:  make repl | make test | make test-clj | make clojure-repl | make clean
# Cloffle: make cloffle-run FILE=... | make cloffle-demo | make bytecode-repl
# Docker:   see targets under ## DOCKER

.PHONY: repl run help clean jar \
	cloffle-repl cloffle-demo cloffle-run cloffle-main-repl bytecode-repl \
	cloffle-dap cloffle-dap-repl \
	clj-compile test clj-test test-clj clojure-repl compat-test \
	clj-jar clj-clean source-location-demo \
	docker-build-cloffle-repl docker-build-cloffle-repl-jlink docker-run-cloffle-repl-jlink \
	docker-build-cloffle-repl-graalpy docker-run-cloffle-repl-graalpy \
	docker-test-cloffle-repl-graalpy-rich-arm64

# =============================================================================
# CLOFFLE (Truffle-based Clojure implementation)
# =============================================================================

# Classpath for direct `java` launches via `clj -Spath -M:cloffle-java` (deps.edn).
# `target/classes` is listed before `src/clj`, so compiled output wins when both exist.
define runtime_cp
$$(clj -Spath -M:cloffle-java)
endef

# JVM used for Cloffle* classes (REPL, Main, DAP). Same -cp everywhere.
define cloffle_java
java --enable-native-access=ALL-UNNAMED -cp "$(runtime_cp)" --sun-misc-unsafe-memory-access=allow
endef

cloffle-repl:
	$(cloffle_java) net.javacrumbs.cloffle.CloffleRepl

# Convenience alias: "make repl" -> Cloffle REPL (primary dev target)
repl: cloffle-repl

# Truffle bytecode backend REPL (clojure.main; CloffleCompiler is bytecode-only).
bytecode-repl:
	clj -T:build bytecode-repl

# Run SourceLocationDemo (per-expression source line/column in stack traces)
source-location-demo:
	clj -T:build source-location-demo

cloffle-run:
	@test -n "$(FILE)" || (echo "Usage: make cloffle-run FILE=path/to/script.clj (or make run FILE=...)" && exit 1)
	clj -T:build cloffle-repl :args '["$(FILE)"]'

# Convenience alias: make run FILE=... -> cloffle-run
run: cloffle-run

# CloffleMain REPL (clojure.main-compatible via Truffle). Run java directly so
# stdin is inherited (tools.build's b/process ignores :in, so run-main hangs).
cloffle-main-repl: clj-compile
	$(cloffle_java) net.javacrumbs.cloffle.CloffleMain -r

# =============================================================================
# DAP DEBUGGING (Debug Adapter Protocol for VS Code)
# =============================================================================

# Run a Cloffle script with DAP enabled (default port 4711, suspends until debugger attaches).
# Usage: make cloffle-dap FILE=path/to/script.clj
#        make cloffle-dap FILE=path/to/script.clj DAP_PORT=4712
cloffle-dap: clj-compile
	@test -n "$(FILE)" || (echo "Usage: make cloffle-dap FILE=path/to/script.clj [DAP_PORT=4711]" && exit 1)
	$(cloffle_java) \
		net.javacrumbs.cloffle.CloffleDapMain \
		$(if $(DAP_PORT),--dap-port $(DAP_PORT)) $(if $(DAP_NOSUSPEND),--dap-no-suspend) \
		"$(FILE)"

# Run a Cloffle REPL with DAP enabled (for debugging interactive sessions).
cloffle-dap-repl: clj-compile
	$(cloffle_java) \
		net.javacrumbs.cloffle.CloffleDapMain \
		$(if $(DAP_PORT),--dap-port $(DAP_PORT)) $(if $(DAP_NOSUSPEND),--dap-no-suspend) \
		-r

# =============================================================================
# BUILD (shared) — wraps clojure -T:build
# =============================================================================

help:
	clj -T:build help

clj-compile:
	clj -T:build compile-all

# Cloffle JUnit tests only (Java test sources under test/ and src/test/java).
test clj-test:
	clj -T:build run-tests

# Clojure's test_clojure suite through Cloffle (Surefire harness).
test-clj:
	clj -T:build run-clj-tests

# External project compatibility checks (git submodules under src/external-projects).
compat-test:
	clj -T:build compat-test

# Plain JVM clojure.main REPL using this repo's compiled Clojure (target/classes).
clojure-repl: clj-compile
	clj -M:test-built -m clojure.main

clj-jar jar:
	clj -T:build jar

clj-clean clean:
	clj -T:build clean

# =============================================================================
# DOCKER (Cloffle) — optional; images for REPL / GraalPy experiments
# =============================================================================

docker-build-cloffle-repl:
	docker build -t cloffle-repl:latest .

docker-build-cloffle-repl-jlink:
	docker build -f Dockerfile.jlink -t cloffle-repl:jlink .

docker-run-cloffle-repl-jlink:
	docker run --rm -it cloffle-repl:jlink

docker-build-cloffle-repl-graalpy:
	docker build -f Dockerfile.graalpy -t cloffle-repl:graalpy .

docker-run-cloffle-repl-graalpy:
	docker run --rm -it cloffle-repl:graalpy

# Build GraalPy image for linux/arm64 and open a shell (rich-print / GraalPy smoke test in container).
docker-test-cloffle-repl-graalpy-rich-arm64:
	docker build --platform linux/arm64 -f Dockerfile.graalpy -t cloffle-repl:graalpy-arm64 .
	docker run --rm -it --platform linux/arm64 --entrypoint bash cloffle-repl:graalpy-arm64
