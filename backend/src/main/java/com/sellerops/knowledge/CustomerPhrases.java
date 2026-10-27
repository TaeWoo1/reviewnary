package com.sellerops.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 「고객은 이렇게 말해요」 — the expressions a seller writes beside one piece of guidance, and the one way they are read
 * (owner decision 2026-10-27).
 *
 * <p><b>Why it exists.</b> The lexical retriever matches words, and a customer's words are rarely the guidance's words:
 * on the owner's store a registered adhesion note was found for 19 of 79 reviews that complained about exactly it
 * (FINDINGS B33). The seller knows how their customers say it — 「떨어져요」, 「들떠요」, 「접착력 약해요」 — so they write
 * it down, and a review that says one of those is answered with that guidance. No model and no score: the seller's own
 * list, matched as written.
 *
 * <p><b>Stored as one phrase per line.</b> Commas split too, because that is how people type a list into one cell of a
 * spreadsheet. Matching ignores spaces and letter case, so 「접착력 약해요」 also finds 「접착력약해요」.
 *
 * <p><b>One guard, and only one.</b> A phrase right after 「안」 or a few syllables before 「지 않」 is a denial —
 * 「안 떨어져요」, 「떨어지지 않아요」, 「잘 떨어지지 않고」 — and is not a hit. A phrase that itself starts with 안 (「안 붙어요」) is read as written. Nothing else
 * is guessed: a seller whose phrase still catches praise makes the phrase longer.
 */
public final class CustomerPhrases {

    /**
     * More than this and the list is no longer the way customers say one thing. Fifty, not twenty: the adhesion note
     * on the owner's store needed 40 expressions to reach 70 of its 79 reviews, and a cap of 20 silently kept the first
     * 20 of them (measured 2026-10-27, 45 of 79).
     */
    public static final int MAX_PHRASES = 50;

    /** A phrase is a few words a customer writes, not a sentence. */
    public static final int MAX_PHRASE_CHARS = 40;

    /** One character is any word's syllable; two is the shortest expression worth matching (「들떠」). */
    static final int MIN_PHRASE_CHARS = 2;

    private CustomerPhrases() {
    }

    /** The phrases in a stored or typed value — trimmed, deduplicated, bounded; empty for null. */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String part : raw.split("[\\r\\n,，、]+")) {
            String phrase = part.strip().replaceAll("\\s+", " ");
            if (phrase.startsWith("「") && phrase.endsWith("」")) {
                phrase = phrase.substring(1, phrase.length() - 1).strip();
            }
            if (normalize(phrase).length() < MIN_PHRASE_CHARS || phrase.length() > MAX_PHRASE_CHARS) {
                continue;
            }
            kept.add(phrase);
            if (kept.size() == MAX_PHRASES) {
                break;
            }
        }
        return List.copyOf(kept);
    }

    /** Whether {@code raw} names more phrases than are kept — said to the seller, never silently dropped. */
    public static boolean overflows(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        long named = java.util.Arrays.stream(raw.split("[\\r\\n,，、]+")).map(String::strip)
                .filter(p -> normalize(p).length() >= MIN_PHRASE_CHARS && p.length() <= MAX_PHRASE_CHARS)
                .distinct().count();
        return named > MAX_PHRASES;
    }

    /** The column value for {@code phrases}: one per line, or null when there are none. */
    public static String store(List<String> phrases) {
        List<String> clean = parse(phrases == null ? null : String.join("\n", phrases));
        return clean.isEmpty() ? null : String.join("\n", clean);
    }

    /** The same value normalized through {@link #parse} — what a write path stores for a typed list. */
    public static String normalized(String raw) {
        return store(parse(raw));
    }

    /** The first of {@code phrases} that {@code text} says, read as {@link CustomerPhrases} describes, or empty. */
    public static Optional<String> firstHit(String text, List<String> phrases) {
        if (text == null || text.isBlank() || phrases == null || phrases.isEmpty()) {
            return Optional.empty();
        }
        String haystack = normalize(text);
        for (String phrase : phrases) {
            String needle = normalize(phrase);
            if (needle.length() < MIN_PHRASE_CHARS) {
                continue;
            }
            for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
                if (!denied(haystack, at, needle)) {
                    return Optional.of(phrase);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean denied(String haystack, int at, String needle) {
        boolean before = at > 0 && haystack.charAt(at - 1) == '안' && needle.charAt(0) != '안';
        // The few syllables after the phrase, so 「잘 떨어」 + 「지지 않고」 is a denial as 「떨어지」 + 「지 않고」 is.
        String after = haystack.substring(at + needle.length(), Math.min(haystack.length(),
                at + needle.length() + DENIAL_WINDOW));
        return before || after.contains("지않") || after.contains("진않") || after.contains("지는않");
    }

    /** How far after a phrase a 「지 않」 still belongs to it: 「떨어」 + 「지지않」. */
    static final int DENIAL_WINDOW = 5;

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
