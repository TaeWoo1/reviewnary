package com.sellerops.knowledge.foldersync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeFolderLinkRepository extends JpaRepository<KnowledgeFolderLink, UUID> {

    Optional<KnowledgeFolderLink> findByOrgId(UUID orgId);
}
