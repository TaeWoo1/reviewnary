package com.sellerops.vendorlink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorStoreBindingRepository extends JpaRepository<VendorStoreBinding, UUID> {

    Optional<VendorStoreBinding> findByVendorStoreId(UUID vendorStoreId);

    List<VendorStoreBinding> findAllByOrgIdOrderByCreatedAt(UUID orgId);
}
