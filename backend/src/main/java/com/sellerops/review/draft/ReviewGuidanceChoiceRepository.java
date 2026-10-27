package com.sellerops.review.draft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewGuidanceChoiceRepository extends JpaRepository<ReviewGuidanceChoice, UUID> {

    List<ReviewGuidanceChoice> findAllByOrgIdAndReviewIdOrderByDecidedAtAsc(UUID orgId, UUID reviewId);

    Optional<ReviewGuidanceChoice> findByOrgIdAndReviewIdAndSourceId(UUID orgId, UUID reviewId, UUID sourceId);
}
