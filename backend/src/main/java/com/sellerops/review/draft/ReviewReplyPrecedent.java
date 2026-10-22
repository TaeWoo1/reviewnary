package com.sellerops.review.draft;

import com.sellerops.knowledge.memory.AnswerMemory;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Which past answers may stand behind a review reply</b> (2026-10-22 external review: 「배송이 빨라요」 and
 * 「배송이 너무 느려요」 are not the same reply).
 *
 * <p>Only a reply the seller stood behind for <b>another review in the same rating band</b> — 1–2, 3 or 4–5 stars.
 * The band is the one sentiment fact the record holds without guessing: a word list would call 「안 빨라요」 fast,
 * and a model would be a second classifier nobody can review. A thank-you to a five-star review is never offered
 * for a one-star complaint about the same topic, and an apology is never offered for praise.
 *
 * <p>An answer to an inquiry is not a review reply's precedent at all: it was written privately to one customer, and
 * a public reply that borrows its shape borrows a register the seller did not choose for the page. Memories with
 * no origin review are left out for that reason.
 *
 * <p>What a past answer is then FOR is decided elsewhere and does not change here: wording and shape, never facts
 * ({@code ReviewDraftComposer}, {@code ReviewClaimGuard}).
 */
@Component
public class ReviewReplyPrecedent {

    private final AnswerMemoryRepository memories;
    private final ReviewRepository reviews;

    public ReviewReplyPrecedent(AnswerMemoryRepository memories, ReviewRepository reviews) {
        this.memories = memories;
        this.reviews = reviews;
    }

    /** The band a rating falls in: 1–2, 3, 4–5. Null and anything outside 1–5 is its own band. */
    static int band(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            return -1;
        }
        return rating <= 2 ? 0 : rating == 3 ? 1 : 2;
    }

    /** The memories a reply to a review rated {@code rating} may draw on. */
    @Transactional(readOnly = true)
    public Predicate<AnswerMemory> forReviewRated(UUID orgId, Integer rating) {
        int wanted = band(rating);
        Map<UUID, UUID> reviewOfMemory = new HashMap<>();
        for (AnswerMemory m : memories.findAllByOrgId(orgId)) {
            if (m.getOriginReviewId() != null) {
                reviewOfMemory.put(m.getId(), m.getOriginReviewId());
            }
        }
        Set<UUID> sameBand = new HashSet<>();
        for (Review r : reviews.findAllById(new HashSet<>(reviewOfMemory.values()))) {
            if (orgId.equals(r.getOrgId()) && band(r.getRating()) == wanted) {
                sameBand.add(r.getId());
            }
        }
        Set<UUID> admitted = new HashSet<>();
        reviewOfMemory.forEach((memory, review) -> {
            if (sameBand.contains(review)) {
                admitted.add(memory);
            }
        });
        return m -> admitted.contains(m.getId());
    }
}
