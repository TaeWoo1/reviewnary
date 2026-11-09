package com.sellerops.connector.cafe24;

/**
 * <b>A Cafe24 board read that came back unusable, with the kind of failure kept</b> (2026-11-08, open-inquiry recheck).
 *
 * <p>Until now a failed board read was a plain {@link IllegalStateException}, and the only way to tell a refused token
 * from a broken article was to parse the message. A caller that spaces out retries per inquiry needs exactly that
 * distinction: a 401 or 403 says the <em>account's</em> access was refused, and must not be counted against an
 * article. So the HTTP status travels as a value, and {@link #kind()} names what it means.
 *
 * <p>Still an {@code IllegalStateException}, with the same messages as before, so every existing caller behaves as it
 * did. Status only: no body, no URI, no article or mall id — a board response carries customers' words.
 */
public class Cafe24ReadFailedException extends IllegalStateException {

    public enum Kind {
        /** 401 or 403: the token or its permission was refused. About the account, never one article. */
        AUTH_REJECTED,
        /** 404 on one article's resource. */
        NOT_FOUND,
        /** 500 on one article's resource. */
        SERVER_ERROR,
        /** 502, 503 or 504: the channel itself is not answering. */
        UNAVAILABLE,
        /** 200, but the body could not be read. */
        UNREADABLE_BODY,
        /** Any other status — not known to mean anything about one article. */
        UNEXPECTED_STATUS
    }

    private final Kind kind;
    private final int statusCode;

    Cafe24ReadFailedException(Kind kind, int statusCode, String message) {
        super(message);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    /** A non-200, non-429 response. */
    static Cafe24ReadFailedException forStatus(int statusCode, String message) {
        return new Cafe24ReadFailedException(kindOf(statusCode), statusCode, message);
    }

    static Kind kindOf(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> Kind.AUTH_REJECTED;
            case 404 -> Kind.NOT_FOUND;
            case 500 -> Kind.SERVER_ERROR;
            case 502, 503, 504 -> Kind.UNAVAILABLE;
            default -> Kind.UNEXPECTED_STATUS;
        };
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }
}
