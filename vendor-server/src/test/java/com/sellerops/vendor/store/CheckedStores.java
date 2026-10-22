package com.sellerops.vendor.store;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test setup for the many tests about what a working delegate does: records both store checks (V9) directly, as an
 * admin would have. The checks themselves — who may record them and what they open — are {@code StoreChecksTest}'s.
 */
public final class CheckedStores {

    private CheckedStores() {
    }

    public static void checked(JdbcClient jdbc, UUID storeId, UUID delegateId) {
        jdbc.sql("update stores set owner_checked_by = ?, owner_checked_at = current_timestamp, "
                        + "owner_check_basis = 'SELLER_CENTER_SCREEN' where id = ? and owner_checked_at is null")
                .params(delegateId, storeId).update();
        jdbc.sql("update store_assignments set checked_by = ?, checked_at = current_timestamp "
                        + "where store_id = ? and delegate_id = ? and ended_at is null and checked_at is null")
                .params(delegateId, storeId, delegateId).update();
    }
}
