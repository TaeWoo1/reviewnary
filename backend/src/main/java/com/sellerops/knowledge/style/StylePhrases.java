package com.sellerops.knowledge.style;

import com.sellerops.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two bounded phrase lists, and the one rule that makes storing them in a text column honest.
 *
 * <p><b>A declared representation, not an encoding.</b> One phrase per line, a newline inside a
 * phrase refused at write time, and nothing anywhere recovers meaning from the prose by pattern
 * matching. That is the difference between this and 「[2호] 3~4가닥」, which was refused a package ago:
 * there, a RELATION was hidden in text a human wrote for other purposes; here the column holds a list
 * and every line is exactly one element of it.
 *
 * <p><b>The caps are product limits, not storage limits.</b> Five required phrases is already more
 * than a reply of two to four sentences can carry without reading like a form letter, and a style
 * that dictates most of the sentence has stopped being a style.
 */
public final class StylePhrases {

    public static final int MAX_REQUIRED = 5;
    public static final int MAX_FORBIDDEN = 10;
    public static final int MAX_PHRASE_CHARS = 40;
    public static final int MAX_AVOID = 10;

    private StylePhrases() {
    }

    /** Storage form to list. Never throws: a row already saved is read as it is. */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : stored.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    /** 「되도록 피할 표현」 storage form to list: a tab splits phrase from replacement. Never throws. */
    public static List<AvoidPhrase> parseAvoid(String stored) {
        List<AvoidPhrase> out = new ArrayList<>();
        for (String line : parse(stored)) {
            int tab = line.indexOf('\t');
            String phrase = (tab < 0 ? line : line.substring(0, tab)).strip();
            String replacement = tab < 0 ? "" : line.substring(tab + 1).strip();
            if (!phrase.isEmpty()) {
                out.add(new AvoidPhrase(phrase, replacement.isEmpty() ? null : replacement));
            }
        }
        return List.copyOf(out);
    }

    /** List to storage form, or null for an empty list. */
    public static String formatAvoid(List<AvoidPhrase> phrases) {
        return phrases == null || phrases.isEmpty() ? null : String.join("\n", phrases.stream()
                .map(a -> a.replacement() == null ? a.phrase() : a.phrase() + "\t" + a.replacement()).toList());
    }

    /**
     * Validate the 「되도록 피할 표현」 pairs: each side single-line and capped, no tab (the storage separator), the
     * phrase not repeated, the replacement not the phrase itself, and at most {@value #MAX_AVOID}.
     */
    public static List<AvoidPhrase> validateAvoid(String field, List<AvoidPhrase> pairs) {
        if (pairs == null) {
            return List.of();
        }
        List<AvoidPhrase> out = new ArrayList<>();
        for (AvoidPhrase raw : pairs) {
            if (raw == null || raw.phrase() == null || raw.phrase().isBlank()) {
                continue;
            }
            String phrase = validate(field, List.of(raw.phrase()), 1).get(0);
            List<String> replaced = validate(field, raw.replacement() == null ? List.of()
                    : List.of(raw.replacement()), 1);
            String replacement = replaced.isEmpty() ? null : replaced.get(0);
            if (phrase.contains("\t") || (replacement != null && replacement.contains("\t"))) {
                throw ApiException.badRequest("「" + field + "」에는 탭을 넣을 수 없습니다.");
            }
            if (phrase.equals(replacement)) {
                throw ApiException.badRequest("「" + field + "」의 「" + phrase + "」은 대신 쓸 표현이 같습니다.");
            }
            if (out.stream().noneMatch(a -> a.phrase().equals(phrase))) {
                out.add(new AvoidPhrase(phrase, replacement));
            }
        }
        if (out.size() > MAX_AVOID) {
            throw ApiException.badRequest("「" + field + "」은 최대 " + MAX_AVOID + "개까지 등록할 수 있습니다.");
        }
        return List.copyOf(out);
    }

    /** List to storage form, or null for an empty list. Null and "" must not be two empty states. */
    public static String format(List<String> phrases) {
        return phrases == null || phrases.isEmpty() ? null : String.join("\n", phrases);
    }

    /**
     * Validate one list on the way in: trimmed, de-duplicated, single-line, capped both ways.
     *
     * @param field the seller-facing field name, so a refusal names the box it came from
     */
    public static List<String> validate(String field, List<String> phrases, int max) {
        if (phrases == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : phrases) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String phrase = raw.strip();
            if (phrase.contains("\n") || phrase.contains("\r")) {
                throw ApiException.badRequest("「" + field + "」은 한 줄로 입력하세요.");
            }
            if (phrase.length() > MAX_PHRASE_CHARS) {
                throw ApiException.badRequest(
                        "「" + field + "」은 한 항목당 " + MAX_PHRASE_CHARS + "자를 넘을 수 없습니다.");
            }
            if (!out.contains(phrase)) {
                out.add(phrase);
            }
        }
        if (out.size() > max) {
            throw ApiException.badRequest("「" + field + "」은 최대 " + max + "개까지 등록할 수 있습니다.");
        }
        return List.copyOf(out);
    }

    /** Single-line, length-capped free text (greeting, closing, address). */
    public static String line(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.contains("\n") || text.contains("\r")) {
            throw ApiException.badRequest("「" + field + "」은 한 줄로 입력하세요.");
        }
        if (text.length() > max) {
            throw ApiException.badRequest("「" + field + "」은 " + max + "자를 넘을 수 없습니다.");
        }
        return text;
    }

    /** Multi-line, length-capped free text. Only the fallback sentence uses it. */
    public static String text(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.length() > max) {
            throw ApiException.badRequest("「" + field + "」은 " + max + "자를 넘을 수 없습니다.");
        }
        return normalized;
    }

    /**
     * Does this phrase assert a FACT rather than describe a manner?
     *
     * <p><b>Why a required phrase may not be a factual claim at all.</b> 「꼭 포함할 표현」 is an
     * unconditional instruction — put this sentence in every reply — and a fact is never
     * unconditionally true. 「당일 발송됩니다」 required into every draft is a shipping promise made on
     * the days nothing shipped, and no amount of evidence checking at draft time makes an
     * unconditional promise conditional. The seller's route for that sentence is 운영 정책, where it
     * is evidence and therefore subject to grounding — which is the Knowledge/Style split in one
     * decision: <b>Knowledge is what is true, Style is how it is said.</b>
     *
     * <p>Closed vocabulary, deliberately conservative, and deliberately not a classifier: it will
     * refuse some innocent phrasings, and a seller told which word was refused can rewrite it.
     */
    public static boolean assertsFact(String phrase) {
        if (phrase == null) {
            return false;
        }
        String text = phrase.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String marker : FACT_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** The first marker this phrase reaches, for the refusal message. Null when it reaches none. */
    public static String factMarker(String phrase) {
        if (phrase == null) {
            return null;
        }
        String text = phrase.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return FACT_MARKERS.stream().filter(text::contains).findFirst().orElse(null);
    }

    /**
     * Words that make a sentence a claim about this order, this stock, or this company's terms.
     *
     * <p>Every one of them names something that has an evidence lane of its own: an order fact, a
     * product knowledge document, or an operating policy. A style field is none of those.
     */
    private static final List<String> FACT_MARKERS = List.of(
            "발송", "출고", "배송", "도착", "당일", "익일", "영업일", "택배", "송장",
            "재고", "입고", "품절", "예약",
            "환불", "반품", "교환", "취소", "보상", "할인", "무료", "적립", "쿠폰");
}
