# Multi-stage build for Cloffle REPL
# Stage 1: Build with Clojure CLI + tools.build
# Stage 2: Minimal GraalVM 25 runtime (matches deps.edn truffle 25.0.2)

# --- Stage 1: Builder ---
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /workspace

# Copy project files
COPY deps.edn build.clj ./
COPY src ./src
COPY test ./test

# Compile Java + AOT Clojure (tools.build)
RUN clj -T:build compile-all

# Prepare runtime layout: copy built artifacts and deps to /build
RUN mkdir -p /build/app && \
    cp -r target /build/app/ && \
    mkdir -p /build/app/target/test-classes && \
    cp -r test /build/app/ && \
    cp -r /root/.m2/repository /build/repository

# Generate classpath for runtime (paths valid in final image at /app)
RUN clj -Spath -M:test-built | sed 's|/root/.m2/repository|/app/repository|g' | sed 's|/workspace|/app|g' > /build/classpath.txt

# --- Stage 2: Runtime ---
FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /app

# Copy built artifacts from builder
COPY --from=builder /build/app/target /app/target
COPY --from=builder /build/app/test /app/test
COPY --from=builder /build/repository /app/repository
COPY --from=builder /build/classpath.txt /app/classpath.txt

# Run REPL; use shell to expand classpath from file (stdin inherited for interactive use)
ENTRYPOINT ["/bin/sh", "-c", "exec java --enable-native-access=ALL-UNNAMED -cp \"$(cat /app/classpath.txt)\" net.javacrumbs.cloffle.CloffleREPL \"$@\"", "--"]
