package com.sellerops.review.response;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewResponseRuleRepository extends JpaRepository<ReviewResponseRule, UUID> {
}
