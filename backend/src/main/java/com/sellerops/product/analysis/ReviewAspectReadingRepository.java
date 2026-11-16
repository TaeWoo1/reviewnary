package com.sellerops.product.analysis;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAspectReadingRepository extends JpaRepository<ReviewAspectReading, UUID> {

    List<ReviewAspectReading> findAllByOrgIdAndReaderVersionAndBodySha256In(UUID orgId, String readerVersion,
                                                                           Collection<String> bodySha256);
}
