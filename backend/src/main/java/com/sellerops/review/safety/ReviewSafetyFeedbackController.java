package com.sellerops.review.safety;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.common.MarkupText;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.ReviewSafetySignal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「표시가 맞지 않음」 on a review's safety mark (mockup ②, 2026-10-25): the seller's word is kept, with the mark they
 * saw, and the mark stays as it is — the screen says 「의견 남김」. Only a review of the caller's organisation; any
 * other is the same 404 as none.
 */
@RestController
public class ReviewSafetyFeedbackController {

    private final ReviewRepository reviews;
    private final ReviewSafetyFeedbackRepository feedback;

    public ReviewSafetyFeedbackController(ReviewRepository reviews, ReviewSafetyFeedbackRepository feedback) {
        this.reviews = reviews;
        this.feedback = feedback;
    }

    @PostMapping("/api/reviews/{reviewId}/safety-feedback")
    public ResponseEntity<Void> wrongMark(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID reviewId) {
        var review = reviews.findByIdAndOrgId(reviewId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("해당 리뷰를 찾을 수 없습니다."));
        ReviewSafetySignal.Finding seen = ReviewSafetySignal.find(MarkupText.toPlainText(review.getBody()))
                .orElseThrow(() -> ApiException.conflict("이 리뷰에는 안전 표시가 없습니다."));
        ReviewSafetyFeedback row = new ReviewSafetyFeedback();
        row.setOrgId(principal.orgId());
        row.setReviewId(reviewId);
        row.setKind(seen.kind().name());
        row.setReading(seen.reading().name());
        row.setTerm(seen.term());
        row.setCreatedBy("SELLER:" + principal.userId());
        feedback.save(row);
        return ResponseEntity.noContent().build();
    }
}
