package com.sellerops.replyhold;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRecheckBasisRepository extends JpaRepository<DraftRecheckBasis, UUID> {
}
