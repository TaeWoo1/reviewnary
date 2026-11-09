package com.sellerops.review.judgement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewJudgementRepository extends JpaRepository<ReviewJudgement, UUID> {

    Optional<ReviewJudgement> findByOrgIdAndReviewId(UUID orgId, UUID reviewId);

    List<ReviewJudgement> findTop200ByOrgIdOrderByJudgedAtDesc(UUID orgId);
}
