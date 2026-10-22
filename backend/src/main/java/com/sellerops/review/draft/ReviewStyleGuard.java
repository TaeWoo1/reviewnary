package com.sellerops.review.draft;

import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import java.util.List;
import java.util.Locale;

/**
 * <b>The company's forbidden phrases, as review replies obey them</b> (product-owner decision 2026-10-23, finding
 * B24: reviews had never been checked against them).
 *
 * <p>The list the seller keeps in 답변 스타일 is an absolute ban here as it is for inquiries: a model draft carrying
 * one is not used, a phone edit carrying one is flagged before approval, and an approved reply that carries one once
 * the ban is added is held. One exception, by the same decision: <b>a bare topic word is not a phrasing</b>. 「환불」
 * or 「교환」 alone names what the customer asked about, and banning it would forbid a reply from saying what it is
 * answering; whether a reply may promise one is {@link ReviewClaimGuard}'s question, answered from the evidence. So
 * an entry that is exactly one of {@link ReviewClaimGuard#COMMITMENT_TERMS} is left out of the review check. The
 * inquiry lane is unchanged.
 */
public final class ReviewStyleGuard {

    private ReviewStyleGuard() {
    }

    /** The forbidden phrases this check enforces on review replies: the list, less the bare topic words. */
    public static List<String> enforced(AnswerStyleProfile profile) {
        if (profile == null) {
            return List.of();
        }
        return profile.forbiddenPhrases().stream().filter(p -> p != null && !p.isBlank() && !topicWord(p)).toList();
    }

    /** Which enforced forbidden phrases {@code body} carries, in the order the seller listed them. */
    public static List<String> forbiddenIn(String body, AnswerStyleProfile profile) {
        if (profile == null) {
            return List.of();
        }
        return AnswerStyleInstruction.forbiddenPresentIn(body, profile).stream().filter(p -> !topicWord(p)).toList();
    }

    /** Exactly a topic word, spacing and case aside. */
    static boolean topicWord(String phrase) {
        String folded = phrase.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return ReviewClaimGuard.COMMITMENT_TERMS.contains(folded);
    }
}
