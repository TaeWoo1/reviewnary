package com.sellerops.review.draft;

import com.sellerops.review.triage.ReviewTriageRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>The parts of a review that say what went wrong</b> — the question a review's knowledge is searched with (2026-10-27).
 *
 * <p>A review is cut at its sentence ends and after its concessions (「…편한데, 정말 접착력이 약하네요」 → 「…편한데」 ·
 * 「정말 접착력이 약하네요」), and a part is kept when it carries one of the triage rule's reservation words — the words
 * that already keep a 3★ in 지켜보기. The concessions themselves are not evidence of a problem in the part that follows
 * them, so they and the softeners (조금 · 좀 · 약간) do not count here. No model; the cut is a reviewable pattern.
 */
final class ReviewComplaintClauses {

    /** How many parts one review is searched with — the query keeps two slots for its whole-review forms. */
    static final int MAX = 2;
    /** Shorter than this is a fragment, not a complaint (「ㅠ」, 「좀」). */
    static final int MIN_CHARS = 4;

    private static final Pattern CUT = Pattern.compile(
            "[.!?~。｡\\n]+|(?<=지만|데|다만|하지만|그러나|으나|빼고는|빼곤|빼고)[,\\s]+");
    /** Reservation words that mark a turn or soften one rather than name a problem. */
    private static final Set<String> NOT_A_PROBLEM = Set.of("다만", "하지만", "지만", "는데", "은데", "근데", "그런데",
            "그러나", "으나", "데", "던지", "던져", "조금", "좀", "약간", "...");
    private static final List<String> PROBLEM_WORDS = ReviewTriageRules.RESERVATION_MARKERS.stream()
            .filter(w -> !NOT_A_PROBLEM.contains(w)).toList();

    private ReviewComplaintClauses() {
    }

    static List<String> of(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : CUT.split(body)) {
            String clause = part.replaceAll("\\s+", " ").strip();
            if (clause.length() < MIN_CHARS || out.contains(clause)) {
                continue;
            }
            if (PROBLEM_WORDS.stream().anyMatch(clause::contains)) {
                out.add(clause);
                if (out.size() == MAX) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }
}
