package com.sellerops.knowledge.foldersync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeFolderFileRepository extends JpaRepository<KnowledgeFolderFile, UUID> {

    List<KnowledgeFolderFile> findAllByOrgId(UUID orgId);

    Optional<KnowledgeFolderFile> findByOrgIdAndPath(UUID orgId, String path);

    Optional<KnowledgeFolderFile> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<KnowledgeFolderFile> findFirstByOrgIdAndSourceId(UUID orgId, UUID sourceId);
}
