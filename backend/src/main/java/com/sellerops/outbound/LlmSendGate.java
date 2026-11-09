package com.sellerops.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>On a seller's PC, what goes to a model provider is checked on this machine first</b> — proposal §17.9
 * (AI 답변 모델, 구현 경계 1–3), scope lock v1.14 ⑤.
 *
 * <p>Both LLM transports ({@code JdkAgentLlmTransport}, {@code JdkLlmHttpClient}) pass the exact outgoing JSON
 * through {@link #prepare} and send only {@link Prepared#body()}; {@code LlmSendGateFenceTest} keeps it that way.
 * Standing at the transport is the point: inquiries, reviews, past answers and retrieved knowledge all arrive here
 * already assembled, so one check covers every capability instead of each builder remembering its own.
 *
 * <p>Two steps, in the order §17.9 draws them:
 * <ol>
 *   <li><b>Remove identifiers that have an unambiguous shape</b> — mobile and landline numbers, e-mail addresses,
 *       long number runs (order, tracking, account, card and resident-registration numbers all land here). Each is
 *       replaced with a bracketed label in place; nothing is restored on the way back (boundary 4).</li>
 *   <li><b>Hold what cannot be removed safely</b> — a postal address, any image the caller has not declared to be
 *       a product image, and a body this class cannot read. A held request is not sent at all.</li>
 * </ol>
 *
 * <p><b>What this does not claim.</b> A name or an address written freely in Korean prose has no reliable shape;
 * finding none of the patterns here is not proof the text is clean. The field floors of each payload (author, ids,
 * timestamps never sent) remain the first boundary, and this gate is the second — not a replacement for either.
 *
 * <p><b>On in a seller-PC install</b> (when {@link OutboundGate} is armed) <b>and on the Reviewnary server</b>
 * ({@link #arm}, from {@code sellerops.llm.send-gate.enabled}, default true — {@code LlmSendGateConfiguration}):
 * the server holds every seller's customers' text, so the same check stands at its transports
 * ({@code docs/standing/server_operation_boundary_v1.md} §4.1-1, 2026-10-31). Off only where a profile turns it off on
 * purpose — the hermetic test profile, so every payload-floor test still sees its body byte-for-byte unchanged.
 */
public final class LlmSendGate {

    /** What a check found. Labels only — a finding never carries the text it matched. */
    public enum Finding {
        PHONE, EMAIL, LONG_NUMBER,
        /** Held: a postal address. */
        ADDRESS,
        /** Held: an image not declared as a product image. */
        IMAGE,
        /** Held: the body is not JSON this class can walk. */
        UNREADABLE
    }

    /** Whether the request carries images, and if so whose. */
    public enum Images {
        /** No image is expected; any image found is held. */
        NONE,
        /** The caller sends public images from the seller's own product page (product detail facts). */
        PRODUCT
    }

    /** What may be sent. {@link #body()} is meaningful only when {@link #sendable()}. */
    public record Prepared(String body, Set<Finding> removed, Set<Finding> held) {

        public boolean sendable() {
            return held.isEmpty();
        }

        /** The transport's status-0 marker for a held request: the finding labels, sorted, and nothing else. */
        public String heldMarker() {
            return "held:" + String.join(",", held.stream().map(Enum::name).sorted().toList());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<Finding> HOLDING = EnumSet.of(Finding.ADDRESS, Finding.IMAGE, Finding.UNREADABLE);

    private static final Set<String> IMAGE_PART_TYPES = Set.of("image", "image_url", "input_image");

    /** Korean mobile numbers, with or without separators. */
    private static final Pattern MOBILE =
            Pattern.compile("(?<![0-9])01[016789][-. ]?[0-9]{3,4}[-. ]?[0-9]{4}(?![0-9])");
    /**
     * Area-code landlines and 050x/070 numbers. Nationwide representative numbers ({@code 1588-…}) are left alone:
     * they belong to businesses, and the seller's own one often has to appear in an answer.
     */
    private static final Pattern LANDLINE =
            Pattern.compile("(?<![0-9])0(?:2|[3-6][1-5]|50[0-9]|70)[-. ][0-9]{3,4}[-. ][0-9]{4}(?![0-9])");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** Twelve or more digits in one run — order, tracking, card and registration numbers. */
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{12,}(?![0-9])");
    /** Hyphen-grouped numbers of ten digits or more in total — bank accounts, registration numbers. */
    private static final Pattern GROUPED = Pattern.compile("(?<![0-9-])[0-9]{2,6}(?:-[0-9]{1,8}){1,4}(?![0-9-])");

    /**
     * Road-name address after a city/county/district: {@code …구 테헤란로 123}. Not a quantity in policy prose, which has
     * the same letters (2026-11-02, case-eval-r12: a claim check was held for 「구매 시 기준으로 20박스」 — 「교환 시
     * 수령일로 7일」 has the shape too): no road name ends in 「…으로」, and a building number is not followed by a unit.
     *
     * <p>A unit is a whole word, not the first letter of one (2026-11-02 review: 「테헤란로 123 원룸」 was let through as
     * 123원): a one-letter unit sits right against the number, and any unit ends there or runs into a particle — 「7일
     * 이내」 · 「3개입니다」 · 「20박스이상」 are quantities, 「123 원룸」 · 「123원룸」 · 「12 장미아파트」 are buildings.
     * A thousands separator is a price, not a building number (「택배로 5,000원」).
     */
    private static final String UNIT_END = "(?=$|[^가-힣]|(?:이내|이상|이하|미만|초과|까지|부터|짜리|정도|가량|입니다|이에요|예요"
            + "|이며|이고|안|내|째|간|당|씩|전|후|이|가|은|는|을|를|의|에|도|만|과|와|로))";
    private static final Pattern ROAD = Pattern.compile("(?:시|군|구)\\s+[가-힣0-9]{1,20}(?<!으)(?:로|길)\\s?[0-9]{1,5}"
            + "(?![0-9]|,[0-9]{3}|\\s?(?:영업일|개월|시간|박스|세트|가닥|번째|퍼센트)" + UNIT_END
            + "|(?:일|개|분|원|장|매|회|주|년|배|명)" + UNIT_END + "|\\s?(?:%|cm|mm|kg|ml)(?![A-Za-z]))");
    /**
     * A road-name address whose building number runs into a building's name — 「테헤란로 123장안빌라」, 「중앙대로
     * 12개나리빌라」, 「테헤란로 123원이빌딩」: held even though the number is followed by what reads as a unit (123장 + the
     * particle 안). The quantity exception above never undoes this (2026-11-02 review).
     *
     * <p>What makes it clear is the building, not the district (2026-11-02, second review): a district read as a place's
     * name held 「교환시 택배로 3개 보내주세요」 and 「구매시 택배로 3개 배송됩니다」, because 구매시 · 교환시 have the letters
     * of 성남시. A quantity is followed by prose; an address by a building-type word, up to ten letters on. An address
     * whose number runs into a unit-shaped name with no building word after it (「…로 12장안」) is not caught here.
     */
    private static final Pattern ROAD_BUILDING = Pattern.compile("(?:시|군|구)\\s+[가-힣0-9]{1,20}(?<!으)(?:로|길)\\s?"
            + "[0-9]{1,5}\\s?[가-힣]{0,10}?(?:빌라|빌딩|맨션|아파트|오피스|하이츠|원룸|주택|상가|타워|프라자|플라자|팰리스|캐슬"
            + "|빌리지|레지던스|타운)");
    /** {@code …번길 12}. */
    private static final Pattern ROAD_BRANCH = Pattern.compile("[가-힣0-9]{1,20}번길\\s?[0-9]{1,5}");
    /** Lot-number address: {@code 123-4번지}. */
    private static final Pattern LOT = Pattern.compile("[0-9]{1,5}(?:-[0-9]{1,5})?\\s?번지");
    /** Apartment unit: {@code 101동 1203호}. */
    private static final Pattern UNIT = Pattern.compile("[0-9]{1,4}\\s?동\\s?[0-9]{1,5}\\s?호");

    private static volatile boolean armed;

    private LlmSendGate() {
    }

    /** Armed on the server by {@code LlmSendGateConfiguration}; the seller-PC install arms it through OutboundGate. */
    public static void arm(boolean on) {
        armed = on;
    }

    /** Whether the check runs: on the server by its own setting, on a seller PC with the outbound gate. */
    public static boolean armed() {
        return armed || OutboundGate.armed();
    }

    /** The one call each LLM transport makes before sending; identity when the gate is not armed. */
    public static Prepared prepare(String jsonBody, Images images) {
        if (!armed()) {
            return new Prepared(jsonBody, Set.of(), Set.of());
        }
        return inspect(jsonBody, images);
    }

    /** The check itself, armed or not, so a test can read it without arming the process. */
    static Prepared inspect(String jsonBody, Images images) {
        EnumSet<Finding> found = EnumSet.noneOf(Finding.class);
        JsonNode root;
        try {
            root = jsonBody == null ? null : MAPPER.readTree(jsonBody);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !(root.isObject() || root.isArray())) {
            return new Prepared(null, Set.of(), EnumSet.of(Finding.UNREADABLE));
        }
        walk(root, images, found);
        EnumSet<Finding> held = EnumSet.noneOf(Finding.class);
        EnumSet<Finding> removed = EnumSet.noneOf(Finding.class);
        for (Finding f : found) {
            (HOLDING.contains(f) ? held : removed).add(f);
        }
        if (!held.isEmpty()) {
            return new Prepared(null, removed, held);
        }
        String body = removed.isEmpty() ? jsonBody : root.toString();
        return new Prepared(body, removed, held);
    }

    private static void walk(JsonNode node, Images images, EnumSet<Finding> found) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (IMAGE_PART_TYPES.contains(object.path("type").asText(""))) {
                if (images != Images.PRODUCT) {
                    found.add(Finding.IMAGE);
                }
                return; // an image part's strings are encoded bytes or an address, not prose
            }
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    String cleaned = clean(value.asText(), images, found);
                    if (cleaned != null) {
                        field.setValue(TextNode.valueOf(cleaned));
                    }
                } else {
                    walk(value, images, found);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) {
                    String cleaned = clean(value.asText(), images, found);
                    if (cleaned != null) {
                        array.set(i, TextNode.valueOf(cleaned));
                    }
                } else {
                    walk(value, images, found);
                }
            }
        }
    }

    /** The cleaned text, or {@code null} when nothing changed. */
    private static String clean(String text, Images images, EnumSet<Finding> found) {
        if (text.startsWith("data:image/") || text.startsWith("data:video/")) {
            if (images != Images.PRODUCT) {
                found.add(Finding.IMAGE);
            }
            return null;
        }
        if (ROAD_BUILDING.matcher(text).find() || ROAD.matcher(text).find() || ROAD_BRANCH.matcher(text).find()
                || LOT.matcher(text).find() || UNIT.matcher(text).find()) {
            found.add(Finding.ADDRESS);
        }
        String out = text;
        out = replace(out, EMAIL, "[이메일]", Finding.EMAIL, found);
        out = replace(out, MOBILE, "[전화번호]", Finding.PHONE, found);
        out = replace(out, LANDLINE, "[전화번호]", Finding.PHONE, found);
        out = replace(out, DIGIT_RUN, "[번호]", Finding.LONG_NUMBER, found);
        out = replaceGrouped(out, found);
        return out.equals(text) ? null : out;
    }

    private static String replace(String text, Pattern pattern, String label, Finding finding,
                                  EnumSet<Finding> found) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        found.add(finding);
        return m.replaceAll(Matcher.quoteReplacement(label));
    }

    /** Hyphen groups count only at ten digits or more, so dates ({@code 2026-08-26}) and sizes pass. */
    private static String replaceGrouped(String text, EnumSet<Finding> found) {
        Matcher m = GROUPED.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            long digits = m.group().chars().filter(Character::isDigit).count();
            if (digits >= 10) {
                m.appendReplacement(out, Matcher.quoteReplacement("[번호]"));
                changed = true;
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        if (!changed) {
            return text;
        }
        m.appendTail(out);
        found.add(Finding.LONG_NUMBER);
        return out.toString();
    }
}
