package com.sellerops.vendor.delegate;

import com.sellerops.vendor.auth.BearerAuthFilter.DelegatePrincipal;
import com.sellerops.vendor.store.Stores;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a signed-in delegate sees of themselves — contract §2: who they are and the stores currently assigned to
 * them. Nothing else about a store is shown here; reviews and tasks arrive with their own lanes, gated on the same
 * assignment.
 */
@RestController
public class DelegateController {

    public record Me(String email, String role, int unusedRecoveryCodes, List<Stores.View> stores) {}

    private final Delegates delegates;
    private final Stores stores;

    public DelegateController(Delegates delegates, Stores stores) {
        this.delegates = delegates;
        this.stores = stores;
    }

    @GetMapping("/v1/delegate/me")
    public ResponseEntity<Me> me(@AuthenticationPrincipal DelegatePrincipal delegate) {
        return delegates.active(delegate.delegateId())
                .map(d -> ResponseEntity.ok(new Me(d.email(), d.role().name(),
                        delegates.unusedRecoveryCodes(d.id()), stores.assignedTo(d.id()))))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
