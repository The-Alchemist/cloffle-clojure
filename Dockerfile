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


COPY deps.edn build.clj ./
COPY src ./src

RUN --mount=type=cache,target=/root/.m2/repository \
    clojure -T:build jar

RUN --mount=type=cache,target=/root/.m2/repository \
    mkdir -p /build/app && \
    cp "$(cat target/jar-artifact.txt)" /build/app/cloffle.jar && \
    cp -r /root/.m2/repository /build/repository

# Classpath: app jar + repository only (no test dir - CDS cannot dump with directory entries).
# Use /app/cloffle.jar — paths are for runtime (stage 2 WORKDIR /app), not the builder.
# java.args: launcher @argfile (JDK 9+); -cp and its value are separate lines so the classpath
# is one argument and stays out of the shell ENTRYPOINT (avoids cmdline length limits).
RUN --mount=type=cache,target=/root/.m2/repository \
    echo '/app/cloffle.jar' > /build/classpath.txt && \
    clojure -Spath -M:repl | tr ':' '\n' | sed 's|^/root/.m2/repository/|repository/|' | grep '^repository/' >> /build/classpath.txt && \
    { echo '--enable-native-access=ALL-UNNAMED'; \
      echo '--sun-misc-unsafe-memory-access=allow'; \
      echo '-cp'; \
      paste -sd: /build/classpath.txt; \
    } > /build/java.args

# --- Stage 2: Runtime ---
FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /app

# :cloffle-java classpath includes src/clj + src/resources + etc/empty-cp (see deps.edn); copy from builder workspace
COPY --from=builder /workspace/src /app/src

# Copy built artifacts from builder
COPY --from=builder /build/app/cloffle.jar /app/cloffle.jar
COPY --from=builder /build/repository /app/repository
COPY --from=builder /build/classpath.txt /app/classpath.txt
COPY --from=builder /build/java.args /app/java.args

# Run REPL; JVM flags and -cp come from @argfile (see /app/java.args)
ENTRYPOINT ["java", "@/app/java.args", "net.javacrumbs.cloffle.CloffleRepl"]
