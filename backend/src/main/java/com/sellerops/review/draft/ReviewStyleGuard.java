package com.sellerops.review.draft;

import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.knowledge.style.AvoidPhrase;
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
 *
 * <p><b>The door opens onto a second list since the owner approved the split (2026-10-23):</b> 「되도록 피할 표현」 and
 * what to write instead. It is a preference, never a check — a draft carrying one is kept and may be approved; the
 * panel marks it and offers the replacement ({@link #findings}).
 *
 * <p><b>And onto the manner since 2026-10-27</b> — the owner: 「판매자가 답변 템플릿과 말투를 지정할 수 있게」. The tone,
 * length, emoji, greeting, closing, address and required phrases the seller set in 답변 스타일 now reach a review reply
 * as the section our code renders ({@link #manner}), the same one the inquiry lane sends. Still through this file alone,
 * and still never a check: style is how it is said, and the evidence rules outrank it in the system turn.
 */
@Component
public class ReviewStyleGuard {

    private final AnswerStyleService styles;

    public ReviewStyleGuard(AnswerStyleService styles) {
        this.styles = styles;
    }

    /**
     * The seller's 답변 스타일 as a review request carries it, or null when they set none (2026-10-27).
     *
     * <p>Rendered without the forbidden list: a review request quotes that list in its own section, narrowed by
     * {@link #enforced} to leave the bare topic words out, and saying it twice — once with 「환불」 in it — would undo
     * the narrowing.
     */
    public String manner(UUID orgId) {
        return manner(styles.profileFor(orgId));
    }

    static String manner(AnswerStyleProfile profile) {
        if (profile == null) {
            return null;
        }
        return AnswerStyleInstruction.of(new AnswerStyleProfile(profile.tone(), profile.length(), profile.emoji(),
                profile.greeting(), profile.closing(), profile.customerAddress(), profile.requiredPhrases(),
                List.of(), profile.unknownFallback(), profile.version(), profile.avoidPhrases()));
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

    /** One marked phrase in a reply: an absolute ban to fix, or a preference with its replacement. */
    public record Finding(Kind kind, String phrase, String replacement, String sentence) {}

    public enum Kind { FORBIDDEN, AVOID }

    /**
     * What the panel marks in {@code body}: each enforced forbidden phrase, then each 「되도록 피할 표현」 it carries,
     * with the first of the reply's own sentences that holds it.
     */
    public List<Finding> findings(String body, UUID orgId) {
        return findings(body, styles.profileFor(orgId));
    }

    static List<Finding> findings(String body, AnswerStyleProfile profile) {
        if (body == null || body.isBlank() || profile == null) {
            return List.of();
        }
        List<Finding> out = new java.util.ArrayList<>();
        for (String phrase : forbiddenIn(body, profile)) {
            out.add(new Finding(Kind.FORBIDDEN, phrase, null, firstSentence(body, phrase)));
        }
        String folded = fold(body);
        for (AvoidPhrase pair : profile.avoidPhrases()) {
            if (folded.contains(fold(pair.phrase()))) {
                out.add(new Finding(Kind.AVOID, pair.phrase(), pair.replacement(), firstSentence(body, pair.phrase())));
            }
        }
        return List.copyOf(out);
    }

    private static String firstSentence(String body, String phrase) {
        List<String> found = ReviewDraftRecheck.sentencesWith(body, List.of(phrase));
        return found.isEmpty() ? null : found.get(0);
    }

    private static String fold(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Exactly a topic word, spacing and case aside. */
    static boolean topicWord(String phrase) {
        String folded = phrase.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return ReviewClaimGuard.COMMITMENT_TERMS.contains(folded);
    }
}
