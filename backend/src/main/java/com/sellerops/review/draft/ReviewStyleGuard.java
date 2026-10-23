package com.sellerops.review.draft;

import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <b>The company's forbidden phrases, as review replies obey them</b> (product-owner decision 2026-10-23, finding
 * B24: reviews had never been checked against them).
 *
 * <p><b>The one door from the review lane into 답변 스타일, and it opens onto one list.</b> A review reply is still not
 * written in the inquiry style — no tone, length, emoji, greeting, closing, address or required phrase reaches it,
 * and nothing here renders a style instruction for a model. Only the forbidden phrases cross, and only as a check:
 * {@code AnswerStyleFenceTest} K holds every other review file away from the style and this one away from everything
 * but that list.
 *
 * <p>The list is an absolute ban here as it is for inquiries: a model draft carrying one is not used, a phone edit
 * carrying one is flagged before approval, and an approved reply that carries one once the ban is added is held. One
 * exception, by the same decision: <b>a bare topic word is not a phrasing</b>. 「환불」 or 「교환」 alone names what the
 * customer asked about, and banning it would forbid a reply from saying what it is answering; whether a reply may
 * promise one is {@link ReviewClaimGuard}'s question, answered from the evidence. So an entry that is exactly one of
 * {@link ReviewClaimGuard#COMMITMENT_TERMS} is left out of the review check. The inquiry lane is unchanged.
 */
@Component
public class ReviewStyleGuard {

    private final AnswerStyleService styles;

    public ReviewStyleGuard(AnswerStyleService styles) {
        this.styles = styles;
    }

    /** The forbidden phrases this check enforces on {@code orgId}'s review replies. */
    public List<String> enforced(UUID orgId) {
        return enforced(styles.profileFor(orgId));
    }

    /** Which enforced forbidden phrases {@code body} carries, in the order the seller listed them. */
    public List<String> forbiddenIn(String body, UUID orgId) {
        return forbiddenIn(body, styles.profileFor(orgId));
    }

    static List<String> enforced(AnswerStyleProfile profile) {
        if (profile == null) {
            return List.of();
        }
        return profile.forbiddenPhrases().stream().filter(p -> p != null && !p.isBlank() && !topicWord(p)).toList();
    }

    static List<String> forbiddenIn(String body, AnswerStyleProfile profile) {
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
