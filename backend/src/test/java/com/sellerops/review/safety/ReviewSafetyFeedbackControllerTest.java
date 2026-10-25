package com.sellerops.review.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/** 「표시가 맞지 않음」 keeps the seller's word with the mark they saw, and touches nothing else (mockup ②, 2026-10-25). */
class ReviewSafetyFeedbackControllerTest {

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewSafetyFeedbackRepository feedback = mock(ReviewSafetyFeedbackRepository.class);
    private final ReviewSafetyFeedbackController controller = new ReviewSafetyFeedbackController(reviews, feedback);
    private final AuthPrincipal seller = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), "s@example.com");

    @Test
    void theSellersWordIsKeptWithTheMarkTheySaw() {
        UUID id = UUID.randomUUID();
        Review review = new Review();
        review.setBody("<p>조립하다가 아이가 손을 베였어요</p>");
        when(reviews.findByIdAndOrgId(id, seller.orgId())).thenReturn(Optional.of(review));

        assertThat(controller.wrongMark(seller, id).getStatusCode().value()).isEqualTo(204);

        ArgumentCaptor<ReviewSafetyFeedback> saved = ArgumentCaptor.forClass(ReviewSafetyFeedback.class);
        verify(feedback).save(saved.capture());
        assertThat(saved.getValue()).satisfies(f -> {
            assertThat(f.getOrgId()).isEqualTo(seller.orgId());
            assertThat(f.getReviewId()).isEqualTo(id);
            assertThat(f.getKind()).isEqualTo("INJURY");
            assertThat(f.getReading()).isEqualTo("OCCURRENCE");
            assertThat(f.getTerm()).isEqualTo("베였");
            assertThat(f.getCreatedBy()).isEqualTo("SELLER:" + seller.userId());
        });
    }

    @Test
    void anotherOrganisationsReviewIsNone_andAReviewWithoutAMarkHasNothingToDispute() {
        UUID elsewhere = UUID.randomUUID();
        when(reviews.findByIdAndOrgId(elsewhere, seller.orgId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.wrongMark(seller, elsewhere)).isInstanceOf(ApiException.class);

        UUID calm = UUID.randomUUID();
        Review review = new Review();
        review.setBody("배송이 빨라요");
        when(reviews.findByIdAndOrgId(calm, seller.orgId())).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> controller.wrongMark(seller, calm)).isInstanceOf(ApiException.class);
        verify(feedback, never()).save(ArgumentMatchers.any());
    }
}
