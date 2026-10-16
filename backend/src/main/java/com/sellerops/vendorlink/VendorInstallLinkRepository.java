package com.sellerops.vendorlink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorInstallLinkRepository extends JpaRepository<VendorInstallLink, UUID> {

    Optional<VendorInstallLink> findFirstByOrderByCreatedAtDesc();
}
