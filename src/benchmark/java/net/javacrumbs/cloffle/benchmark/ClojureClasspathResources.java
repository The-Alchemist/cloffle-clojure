package net.javacrumbs.cloffle.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads Clojure source from the classpath. Used by snippet catalogs and guest-benchmark setup.
 */
public final class ClojureClasspathResources {

    private ClojureClasspathResources() {}

    public static String read(String resourcePath) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream in = context != null ? context.getResourceAsStream(resourcePath) : null;
        if (in == null) {
            in = ClojureClasspathResources.class.getClassLoader().getResourceAsStream(resourcePath);
        }
        if (in == null) {
            throw new IllegalStateException("Clojure resource not found: " + resourcePath);
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Clojure resource: " + resourcePath, e);
        }
    }
}
