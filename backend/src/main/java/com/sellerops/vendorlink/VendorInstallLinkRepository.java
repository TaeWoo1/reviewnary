package com.sellerops.vendorlink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface VendorInstallLinkRepository extends JpaRepository<VendorInstallLink, UUID> {

    Optional<VendorInstallLink> findByOrgId(UUID orgId);

    /** Its own transaction when the caller has none, like the inherited {@code deleteAll} it replaced. */
    @Transactional
    void deleteByOrgId(UUID orgId);
}
