package com.sellerops.review.judgement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one judgement is compared with today's readers before any screen reads it (2026-10-28, §3-4). */
class ReviewJudgementCompareTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    @DisplayName("a five-star the AI hands over and the word tier calls 참고 is counted as a disagreement")
    void aDisagreementIsCounted() {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review();
        review.setId(reviewId);
        review.setOrgId(ORG);
        review.setRating(5);
        review.setBody("디자인이 예뻐요");
        ReviewJudgement j = new ReviewJudgement();
        j.setOrgId(ORG);
        j.setReviewId(reviewId);
        j.setJudgedAt(Instant.now());
        j.setSource("REPLY_AGENT");
        j.setCategory("P");
        j.setActions("PERSON_REVIEW");
        j.setAnswered(true);
        j.setNeedsResponse(true);
        j.setHandoffReason("PERSON_REVIEW");
        j.setPoints("디자인 칭찬\n아이 손가락이 끼임");
        ReviewJudgementRepository judgements = mock(ReviewJudgementRepository.class);
        when(judgements.findTop200ByOrgIdOrderByJudgedAtDesc(ORG)).thenReturn(List.of(j));
        ReviewRepository reviews = mock(ReviewRepository.class);
        when(reviews.findByIdAndOrgId(reviewId, ORG)).thenReturn(Optional.of(review));

        List<ReviewJudgementController.Compared> rows = new ReviewJudgementController(judgements, reviews)
                .compare(new AuthPrincipal(UUID.randomUUID(), ORG, "seller@example.com"));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.screenTier()).isEqualTo("FYI");
            assertThat(r.agreesWithScreenTier()).isFalse();
            assertThat(r.agreesWithDraftingAhead()).as("no stored verdict is 미평가, never a call").isNull();
            assertThat(r.points()).containsExactly("디자인 칭찬", "아이 손가락이 끼임");
        });
    }
}
