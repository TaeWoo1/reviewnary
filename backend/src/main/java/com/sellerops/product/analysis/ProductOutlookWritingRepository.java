package com.sellerops.product.analysis;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOutlookWritingRepository extends JpaRepository<ProductOutlookWriting, UUID> {

    Optional<ProductOutlookWriting> findFirstByOrgIdAndProductIdAndInputFingerprintAndWriterVersion(
            UUID orgId, UUID productId, String inputFingerprint, String writerVersion);
}
