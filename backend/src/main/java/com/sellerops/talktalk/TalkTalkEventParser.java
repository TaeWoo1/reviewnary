package com.sellerops.talktalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;

/**
 * Reads the fields of a webhook body the lane keeps (README 「이벤트 명세」, handover_v1.md). Everything else in the body
 * — referer, thumbnail URLs, prices, menu payloads — is not kept.
 */
public final class TalkTalkEventParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Recorded as the input type of a message whose content is not text (README imageContent / compositeContent). */
    public static final String NON_TEXT_IMAGE = "image";
    public static final String NON_TEXT_COMPOSITE = "composite";

    public static boolean nonText(String inputType) {
        return NON_TEXT_IMAGE.equals(inputType) || NON_TEXT_COMPOSITE.equals(inputType);
    }

    public static final Set<String> KNOWN = Set.of("open", "leave", "friend", "send", "echo", "action",
            "persistentMenu", "handover");

    private TalkTalkEventParser() {
    }

    public record Parsed(String event, String user, String partner, String text, String inputType, boolean standby,
                         Long sourceId, Long threadOwnerId, String echoedEvent, String control, Long targetId,
                         String productName) {
        @Override
        public String toString() {
            return "Parsed(event=" + event + ", inputType=" + inputType + ", standby=" + standby + ")";
        }
    }

    public static final class Unreadable extends Exception {
        public Unreadable(String code) {
            super(code);
        }
    }

    public static Parsed parse(byte[] body) throws Unreadable {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new Unreadable("NOT_JSON");
        }
        if (root == null || !root.isObject()) {
            throw new Unreadable("NOT_OBJECT");
        }
        String event = text(root, "event");
        if (event == null || event.isBlank() || event.length() > 24) {
            throw new Unreadable("NO_EVENT");
        }
        JsonNode options = root.path("options");
        JsonNode tc = root.path("textContent");
        String productName = null;
        JsonNode product = options.path("product");
        if (product.isObject()) {
            productName = clip(text(product, "name"), 300);
        }
        // A photo or a card carries no text this lane keeps (no attachment storage): its kind is recorded so the
        // screen can say 「사진을 보냈어요 — 파트너센터에서 확인」 instead of showing nothing.
        String inputType = tc.isObject() ? clip(text(tc, "inputType"), 24)
                : root.path("imageContent").isObject() ? NON_TEXT_IMAGE
                : root.path("compositeContent").isObject() ? NON_TEXT_COMPOSITE : null;
        return new Parsed(event, clip(text(root, "user"), 200), clip(text(root, "partner"), 64),
                tc.isObject() ? clip(text(tc, "text"), 10_000) : null,
                inputType,
                root.path("standby").asBoolean(false),
                number(options, "sourceId"), number(options, "threadOwnerId"),
                clip(text(root, "echoedEvent"), 24),
                clip(text(options, "control"), 24), number(options, "targetId"), productName);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    private static Long number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual() && v.asText().matches("\\d{1,18}")) {
            return Long.parseLong(v.asText());
        }
        return null;
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
