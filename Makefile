.PHONY: repl demo run clean test clj-repl clj-test docker-repl docker-repl-test

# --- Clojure CLI (deps.edn + tools.build) ---
# Run java directly (not via clj -T:build) so stdin is inherited for interactive REPL.
# tools.build's b/process uses PIPE for stdin by default, causing input to hang.
repl: clj-compile
	java --enable-native-access=ALL-UNNAMED -cp "$$(clj -Spath -M:test-built)" net.javacrumbs.cloffle.CloffleREPL

demo:
	clj -T:build run-repl :args '["--demo"]'

run:
	@test -n "$(FILE)" || (echo "Usage: make run FILE=path/to/script.clj" && exit 1)
	clj -T:build run-repl :args '["$(FILE)"]'

# Standard Clojure REPL (clojure.main). Compile first so version.properties
# and classpath are correct (avoids NumberFormatException from Maven placeholder).
clj-repl: clj-compile
	clj -M:test-built

# Build: compile Java + AOT Clojure (tools.build)
clj-compile:
	clj -T:build compile-all

# Run Clojure tests (tools.build: compile then run)
test clj-test: clj-compile
	clj -T:build run-tests

# Create JAR (tools.build)
clj-jar:
	clj -T:build jar

# Clean (tools.build)
clj-clean:
	clj -T:build clean

# --- Docker ---
# Build minimal GraalVM 25 image for Cloffle REPL (multi-stage)
docker-repl:
	docker build -t cloffle-repl:latest .

# Test: echo "(+ 1 2)" into REPL, expect 3
docker-repl-test: docker-repl
	@echo '(+ 1 2)' | docker run -i --rm cloffle-repl:latest | grep -q 3 && echo "PASS: (+ 1 2) => 3" || (echo "FAIL: expected 3"; exit 1)
