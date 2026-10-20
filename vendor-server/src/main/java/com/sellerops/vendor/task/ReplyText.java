package com.sellerops.vendor.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <b>A reply's text: normalized, fingerprinted, and checked without knowledge</b> — contract §4.1.
 *
 * <p>The normalization and the fingerprint are the backend's {@code ReviewReplyValidation.normalize} and
 * {@code ReviewReplyFingerprint} ({@code review-reply-v1}: SHA-256 of {@code {"schema":"review-reply-v1","body":…}}
 * written by Jackson), ported because this build shares no code with the backend (D1). An approval binds to this
 * value, and the delegate's report is checked against it, so the PC and the server must agree on it —
 * {@code ReplyTextTest} pins vectors.
 *
 * <p>{@link #checks} is the server's only judgement of a draft and it knows nothing about the store: does the text
 * carry a phone number, an e-mail address or a link. They are shown beside the draft, never a reason to refuse it —
 * a store's own customer-service number is a fine thing to write. Knowledge-based checks are the PC's (§4.3
 * {@code pc-recheck}).
 */
public final class ReplyText {

    public static final String SCHEMA = "review-reply-v1";
    /** The backend's storage-sanity bound, not a marketplace limit (NAVER's is not verified). */
    public static final int BODY_MAX_BYTES = 4000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://|www\\.)\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("0\\d{1,2}[-. ]?\\d{3,4}[-. ]?\\d{4}");

    private ReplyText() {
    }

    /** CRLF and CR to LF, outer whitespace trimmed. */
    public static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace("\r", "\n").strip();
    }

    /** A normalized body that may be stored: not empty, at most {@link #BODY_MAX_BYTES}. */
    public static boolean storable(String normalized) {
        return !normalized.isEmpty() && normalized.getBytes(StandardCharsets.UTF_8).length <= BODY_MAX_BYTES;
    }

    public static String fingerprint(String normalizedBody) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schema", SCHEMA);
        canonical.put("body", normalizedBody);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // Never echo the content.
            throw new IllegalStateException("reply fingerprint unavailable");
        }
    }

    /** The knowledge-free flags, in a fixed order: {@code CONTACT_PHONE}, {@code CONTACT_EMAIL}, {@code URL}. */
    public static List<String> checks(String normalizedBody) {
        List<String> flags = new ArrayList<>();
        if (PHONE.matcher(normalizedBody).find()) {
            flags.add("CONTACT_PHONE");
        }
        if (EMAIL.matcher(normalizedBody).find()) {
            flags.add("CONTACT_EMAIL");
        }
        if (URL.matcher(normalizedBody).find()) {
            flags.add("URL");
        }
        return List.copyOf(flags);
    }
}
