package com.sellerops.vendor.store;

import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A seller's stores, seen by the seller (session) or by one of their PCs (install token) — contract §2. Only the
 * caller's own account; there is no route that names another account's store.
 */
@RestController
public class StoreController {

    private final Stores stores;

    public StoreController(Stores stores) {
        this.stores = stores;
    }

    @GetMapping("/v1/stores")
    public List<Stores.View> list(@AuthenticationPrincipal Principal caller) {
        return stores.ofAccount(caller.accountId());
    }
}
