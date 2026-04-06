# syntax=docker/dockerfile:1.7
# Multi-stage build for Cloffle REPL
# Stage 1: GraalVM JDK 25 + Clojure CLI; deps.edn :build supplies tools.build
# Stage 2: Minimal GraalVM 25 runtime (matches deps.edn truffle 25.0.2)

# --- Stage 1: Builder ---
FROM ghcr.io/graalvm/jdk-community:25 AS builder

# Oracle Linux 10: curl + git for Clojure CLI install and deps; tools.build comes from :build alias
RUN microdnf install -y curl git gzip tar findutils \
    && microdnf clean all \
    && curl -fsSL -o /tmp/linux-install.sh https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x /tmp/linux-install.sh \
    && /tmp/linux-install.sh \
    && rm -f /tmp/linux-install.sh

WORKDIR /workspace

# Copy project files
COPY deps.edn build.clj ./
COPY etc ./etc
COPY src ./src
COPY test ./test

# Compile Java, then dump per-namespace `.bc` caches into target/classes (same as `clj -T:build jar`
# without packaging). REPL classpath uses target/classes — without this step only `.clj` on src/clj loads.
RUN clojure -T:build dump-bytecode-cache

# Prepare runtime layout: copy built artifacts and deps to /build
RUN mkdir -p /build/app && \
    cp -r target /build/app/ && \
    mkdir -p /build/app/target/test-classes && \
    cp -r test /build/app/ && \
    cp -r /root/.m2/repository /build/repository

# Classpath must match Makefile `runtime_cp` (`clj -Spath -M:cloffle-java`): target/classes, src paths,
# empty-cp for stock clojure JAR only, Truffle deps, and explicit spec.alpha / core.specs.alpha JARs.
RUN clojure -Spath -M:cloffle-java | sed 's|/root/.m2/repository|/app/repository|g' | sed 's|/workspace|/app|g' > /build/classpath.txt

# --- Stage 2: Runtime ---
FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /app

# :cloffle-java classpath includes src/clj + src/resources + etc/empty-cp (see deps.edn); copy from builder workspace
COPY --from=builder /workspace/src /app/src
COPY --from=builder /workspace/etc /app/etc

# Copy built artifacts from builder
COPY --from=builder /build/app/target /app/target
COPY --from=builder /build/app/test /app/test
COPY --from=builder /build/repository /app/repository
COPY --from=builder /build/classpath.txt /app/classpath.txt

# Run REPL; JVM flags match Makefile `cloffle_java`
ENTRYPOINT ["/bin/sh", "-c", "exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp \"$(cat /app/classpath.txt)\" net.javacrumbs.cloffle.CloffleRepl \"$@\"", "--"]
