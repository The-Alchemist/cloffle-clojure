.PHONY: repl run cloffle-repl cloffle-demo cloffle-run cloffle-main-repl \
	clj-compile test clj-test clj-jar clj-clean source-location-demo \
	docker-build-cloffle-repl docker-build-cloffle-repl-jlink docker-run-cloffle-repl-jlink \
	docker-build-cloffle-repl-graalpy docker-run-cloffle-repl-graalpy \
	docker-test-cloffle-repl-graalpy-rich-arm64

# =============================================================================
# CLOFFLE (Truffle-based Clojure implementation)
# =============================================================================

# Cloffle REPL (CloffleREPL). Run java directly so stdin is inherited for
# interactive use (tools.build's b/process uses PIPE for stdin and hangs).
define runtime_cp
$$(clj -Spath -M:test-built | tr ':' '\n' | rg -v '(^|/)src/clj$$' | paste -sd ':' -)
endef

cloffle-repl: clj-compile
	java --enable-native-access=ALL-UNNAMED -cp "$(runtime_cp)" net.javacrumbs.cloffle.CloffleRepl

# Convenience alias: "make repl" -> Cloffle REPL (primary dev target)
repl: cloffle-repl

cloffle-demo:
	clj -T:build cloffle-repl :args '["--demo"]'

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
	java --enable-native-access=ALL-UNNAMED -cp "$(runtime_cp)" net.javacrumbs.cloffle.CloffleMain -r

# =============================================================================
# BUILD (shared)
# =============================================================================

clj-compile:
	clj -T:build compile-all

# Run tests (Clojure example + generative + Cloffle JUnit)
test clj-test:
	clj -T:build run-tests

clj-jar:
	clj -T:build jar

clj-clean:
	clj -T:build clean

# =============================================================================
# DOCKER (Cloffle)
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

# Build GraalPy image for arm64 and run rich-print test (validates GraalPy + rich in container)
docker-test-cloffle-repl-graalpy-rich-arm64:
	docker build --platform linux/arm64 -f Dockerfile.graalpy -t cloffle-repl:graalpy-arm64 .
	docker run --rm -it --platform linux/arm64 --entrypoint bash cloffle-repl:graalpy-arm64

