package com.sellerops.review.safety;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewSafetyFeedbackRepository extends JpaRepository<ReviewSafetyFeedback, UUID> {

    List<ReviewSafetyFeedback> findByOrgIdAndReviewId(UUID orgId, UUID reviewId);
}
