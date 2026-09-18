package org.cloffle.trufflejson;

/**
 * Malformed JSON during a typed scan. Hosts such as Cloffle wrap this as their own parse error.
 */
public final class JsonException extends RuntimeException {
    public final int position;
    public final String detail;

    public JsonException(String detail, int position) {
        super(detail + " at " + position);
        this.detail = detail;
        this.position = position;
    }
}
