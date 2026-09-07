package clojure.lang;

import net.javacrumbs.cloffle.benchmark.ClojureClasspathResources;

/**
 * Shared helpers for StreamSeq tests: snippet catalog and code loading.
 * Mirrors SnippetBenchmarkSupport by storing Clojure test forms in dedicated resource files.
 */
public final class StreamSeqTestSupport {

    public static final String TRANSDUCER_CHAINING = "transducer-chaining";
    public static final String COMPOSITION_IS_STREAM_SEQ = "composition-is-stream-seq";
    public static final String COMPOSITION_SAME_SOURCE = "composition-same-source";
    public static final String STEPPING_AND_MEMOIZATION = "stepping-and-memoization";
    public static final String EMPTY_COLLECTIONS = "empty-collections";
    public static final String INFINITE_SEQUENCES = "infinite-sequences";
    public static final String MIXED_PULL_THEN_REDUCE = "mixed-pull-then-reduce";
    public static final String COMPLETION_ARITY_FLUSH = "completion-arity-flush";
    public static final String BINARY_ONLY_RF = "binary-only-rf";

    public static final String[] SAMPLE_NAMES = {
            TRANSDUCER_CHAINING,
            COMPOSITION_IS_STREAM_SEQ,
            COMPOSITION_SAME_SOURCE,
            STEPPING_AND_MEMOIZATION,
            EMPTY_COLLECTIONS,
            INFINITE_SEQUENCES,
            MIXED_PULL_THEN_REDUCE,
            COMPLETION_ARITY_FLUSH,
            BINARY_ONLY_RF
    };

    private StreamSeqTestSupport() {}

    public static String codeFor(String name) {
        if (!isKnownSample(name)) {
            throw new IllegalArgumentException("Unknown snippet: " + name);
        }
        return ClojureClasspathResources.read("stream-seq/" + name + ".clj");
    }

    private static boolean isKnownSample(String name) {
        for (String sample : SAMPLE_NAMES) {
            if (sample.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
