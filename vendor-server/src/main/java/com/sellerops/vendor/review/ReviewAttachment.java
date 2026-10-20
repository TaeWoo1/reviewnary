package com.sellerops.vendor.review;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * <b>The export's one attachment address, kept only when it points where NAVER's review images live</b> — contract
 * §3.1, D5.
 *
 * <p>The {@code 포토/영상} cell is the address of the review's <i>first</i> attachment as plain text, on
 * {@code phinf.pstatic.net} over https. Anything else — another host, another scheme, a port, user info, an
 * over-long string — is not stored, and the upload reports how many were dropped. The address opens without a
 * login, so it is treated like the review text itself: retained by the same rule (§6) and never logged.
 *
 * <p>The kind is read from the path as observed on 2026-10-17 and is not a rule NAVER guarantees:
 * {@code /checkout.phinf/…} is a photo, {@code /image.nmv/…} is a video's still frame, and any other path is just
 * an image.
 */
public record ReviewAttachment(String url, Kind kind) {

    public enum Kind { PHOTO, VIDEO_STILL, IMAGE }

    static final String HOST = "phinf.pstatic.net";
    static final int MAX_LENGTH = 1000;

    /** The screened attachment; empty for an empty cell and for a refused address alike — see {@link #refused}. */
    public static Optional<ReviewAttachment> screen(String raw) {
        if (raw == null || raw.isEmpty() || refused(raw)) {
            return Optional.empty();
        }
        String path = URI.create(raw).getRawPath();
        Kind kind = path.startsWith("/checkout.phinf/") ? Kind.PHOTO
                : path.startsWith("/image.nmv/") ? Kind.VIDEO_STILL : Kind.IMAGE;
        return Optional.of(new ReviewAttachment(raw, kind));
    }

    /** A non-empty cell that is not an address this server keeps. */
    public static boolean refused(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.length() > MAX_LENGTH) {
            return true;
        }
        try {
            URI uri = new URI(raw);
            return !"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawPath() == null || uri.getRawPath().length() < 2;
        } catch (URISyntaxException e) {
            return true;
        }
    }
}
