package com.sellerops.talktalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Digests, tokens and aliases for the TalkTalk lane. Pure; no secret ever leaves through these. */
public final class TalkTalkHashes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TalkTalkHashes() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A webhook path token: 32 random bytes, URL-safe. Only its hash is stored. */
    public static String newWebhookToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The lookup key of a TalkTalk user inside one organization — never the user value itself. */
    public static String userHash(UUID orgId, String user) {
        return sha256(orgId + ":" + user);
    }

    /** 「고객 A1B2」 — what a screen calls a conversation. */
    public static String alias(String userHash) {
        return "고객 " + userHash.substring(0, 4).toUpperCase(Locale.ROOT);
    }

    /** The fingerprint of a reply text — what a send is bound to. */
    public static String replyFingerprint(String text) {
        return sha256("talktalk-reply:v1\n" + (text == null ? "" : text));
    }

    /** A partner id as a screen may show it: the first three characters and a mask. */
    public static String maskPartner(String partnerId) {
        if (partnerId == null || partnerId.isBlank()) {
            return "—";
        }
        String p = partnerId.strip();
        return p.length() <= 3 ? "***" : p.substring(0, 3) + "***";
    }
}
