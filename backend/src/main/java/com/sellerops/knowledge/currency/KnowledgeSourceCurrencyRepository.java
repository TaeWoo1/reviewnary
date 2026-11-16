package com.sellerops.knowledge.currency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSourceCurrencyRepository extends JpaRepository<KnowledgeSourceCurrency, UUID> {

    List<KnowledgeSourceCurrency> findAllByOrgId(UUID orgId);

    Optional<KnowledgeSourceCurrency> findByOrgIdAndSourceId(UUID orgId, UUID sourceId);
}
