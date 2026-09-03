package net.javacrumbs.cloffle.trace;

import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.source.Source;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only JSONL execution trace for the VS Code Cloffle extension.
 *
 * <p>Enable with {@link #init(String)} (tests / CLI) before evaluating guest code.
 * Disabled ({@code writer == null}) is a no-op on the hot path.
 *
 * <p>Event kinds: {@code formEnter}, {@code formExit}, {@code bindingWrite}, {@code exception}.
 * See {@code tools/vscode-cloffle-extension} TraceRecord v1.
 */
public final class CloffleTracer {

    private static final Object LOCK = new Object();
    private static volatile Writer writer;
    private static final AtomicLong SEQ = new AtomicLong(1);
    private static final long RUN_START_MS = System.currentTimeMillis();

    private CloffleTracer() {}

    /** Open (or replace) the JSONL sink. {@code null} disables tracing and closes any open writer. */
    public static void init(String path) {
        synchronized (LOCK) {
            closeQuietly(writer);
            writer = null;
            if (path == null || path.isBlank()) {
                return;
            }
            try {
                Path p = Path.of(path);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                writer = new BufferedWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8));
                SEQ.set(1);
            } catch (IOException e) {
                System.err.println("CloffleTracer: failed to open " + path + ": " + e.getMessage());
            }
        }
    }

    public static boolean isEnabled() {
        return writer != null;
    }

    public static void formEnter(String uri, int line, int column, String form) {
        emit("formEnter", uri, line, column, form, null, null, null, null);
    }

    public static void formExit(String uri, int line, int column, String form, Object value) {
        emit("formExit", uri, line, column, form, null, formatValue(value), null, null);
    }

    public static void bindingWrite(String uri, int line, int column, String symbol,
                                    Object value, Object previousValue) {
        emit("bindingWrite", uri, line, column, null, symbol,
                formatValue(value), formatValue(previousValue), null);
    }

    public static void exception(String uri, int line, int column, Throwable t) {
        String msg = t != null ? t.getMessage() : null;
        if (msg == null && t != null) {
            msg = t.getClass().getName();
        }
        emit("exception", uri, line, column, null, null, null, null, msg);
    }

    /** Filesystem path for {@code file:} URIs; otherwise URI string or source name. */
    public static String uriOf(Source source) {
        if (source == null) {
            return null;
        }
        URI u = source.getURI();
        if (u != null) {
            if ("file".equals(u.getScheme())) {
                String path = u.getPath();
                return path != null && !path.isEmpty() ? path : u.toString();
            }
            return u.toString();
        }
        return source.getName();
    }

    public static String formatValue(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Var.Unbound) {
            // Extension fixture / hover treat first bind as previousValue "0".
            return "0";
        }
        if (value instanceof Var v) {
            return v.toString();
        }
        try {
            return RT.printString(value);
        } catch (Throwable t) {
            return String.valueOf(value);
        }
    }

    private static long emit(
            String kind,
            String uri,
            int line,
            int column,
            String form,
            String symbol,
            String value,
            String previousValue,
            String message) {

        Writer w = writer;
        if (w == null) {
            return 0;
        }
        if (uri != null && shouldSkipUri(uri)) {
            return 0;
        }

        long seq = SEQ.getAndIncrement();
        long ts = System.currentTimeMillis() - RUN_START_MS;

        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"v\":1,\"seq\":").append(seq)
          .append(",\"ts\":").append(ts)
          .append(",\"kind\":\"").append(kind).append('"');

        appendString(sb, "uri", uri);
        if (line > 0) {
            sb.append(",\"line\":").append(line);
        }
        if (column > 0) {
            sb.append(",\"column\":").append(column);
        }
        appendString(sb, "form", form);
        appendString(sb, "symbol", symbol);
        appendString(sb, "value", value);
        appendString(sb, "previousValue", previousValue);
        appendString(sb, "message", message);
        sb.append('}');

        synchronized (LOCK) {
            Writer out = writer;
            if (out == null) {
                return 0;
            }
            try {
                out.write(sb.toString());
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                System.err.println("CloffleTracer: write failed: " + e.getMessage());
            }
        }
        return seq;
    }

    private static boolean shouldSkipUri(String uri) {
        return uri.startsWith("truffle:")
                || uri.contains("clojure/core")
                || uri.contains("clojure\\core");
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        sb.append(",\"").append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void closeQuietly(Writer w) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
