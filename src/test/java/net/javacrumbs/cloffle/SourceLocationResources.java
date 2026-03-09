package net.javacrumbs.cloffle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared access to Clojure source files used by both SourceLocationDemo and SourceLocationTest.
 * Resources live in src/test/resources/ and are copied to the test classpath during build.
 */
public final class SourceLocationResources {

    private SourceLocationResources() {}

    public static String read(String fileName) throws IOException {
        try (InputStream in = SourceLocationResources.class.getClassLoader()
                .getResourceAsStream(fileName)) {
            if (in == null) {
                throw new IOException("Resource not found: " + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
