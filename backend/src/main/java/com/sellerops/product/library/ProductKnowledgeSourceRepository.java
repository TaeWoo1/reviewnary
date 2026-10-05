package com.sellerops.product.library;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductKnowledgeSourceRepository extends JpaRepository<ProductKnowledgeSource, UUID> {

    List<ProductKnowledgeSource> findAllByOrgIdAndProductIdOrderByCreatedAtAsc(UUID orgId, UUID productId);

    /**
     * Every product fact this company has written, across every product.
     *
     * <p>The knowledge workspace's read. It exists because the only org-wide view of this corpus was
     * a COUNT: a seller could be told they had written ten product facts and had no screen that would
     * show them the ten. Reading it per product would be one query per product in the catalogue.
     */
    List<ProductKnowledgeSource> findAllByOrgId(UUID orgId);

    /** Org-scoped by id — a knowledge document is never reachable across a tenant boundary. */
    Optional<ProductKnowledgeSource> findByIdAndOrgId(UUID id, UUID orgId);

    /** Every source that came from an uploaded file — the 자료 list, product side. */
    List<ProductKnowledgeSource> findAllByOrgIdAndDocumentNameIsNotNull(UUID orgId);

    long countByOrgIdAndProductId(UUID orgId, UUID productId);

    /**
     * The same count, narrowed to what is actually usable — a retired source cannot ground a reply,
     * so a workspace that counted it would tell the seller they have knowledge the drafter cannot see.
     */
    long countByOrgIdAndProductIdAndActiveTrue(UUID orgId, UUID productId);

    /**
     * How many product facts a person wrote — the 상품 지식 number on 「알고 있는 정보」.
     *
     * <p>Documents are excluded because they are counted as 자료. The three numbers on that screen
     * PARTITION the two corpora, so nothing is counted twice and the seller can add them up.
     */
    long countByOrgIdAndDocumentNameIsNull(UUID orgId);

    /** How many uploaded files landed in the product corpus — the 자료 number, product side. */
    long countByOrgIdAndDocumentNameIsNotNull(UUID orgId);

    /** The documents for ONE product — the 자료 list on the product screen. */
    List<ProductKnowledgeSource> findAllByOrgIdAndProductIdAndDocumentNameIsNotNull(UUID orgId, UUID productId);
}
