package com.sellerops.vendor.review;

import com.sellerops.vendor.auth.BearerAuthFilter.DelegatePrincipal;
import com.sellerops.vendor.store.Stores;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A delegate uploads a store's review export — contract §3.1. Only to a store assigned to them now; any other store
 * id is the same 404 as one that does not exist. The file is read in memory and dropped; the answer is counts, or a
 * refusal that names what to fix.
 */
@RestController
public class ReviewUploadController {

    private final Stores stores;
    private final StoreReviews reviews;

    public ReviewUploadController(Stores stores, StoreReviews reviews) {
        this.stores = stores;
        this.reviews = reviews;
    }

    @PostMapping("/v1/delegate/stores/{storeId}/review-uploads")
    public ResponseEntity<?> upload(@AuthenticationPrincipal DelegatePrincipal delegate, @PathVariable UUID storeId,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        if (!stores.isAssigned(storeId, delegate.delegateId())) {
            return ResponseEntity.notFound().build();
        }
        try {
            var lines = NaverReviewExport.read(file.getBytes());
            return ResponseEntity.status(HttpStatus.CREATED).body(reviews.importExport(storeId, delegate.delegateId(),
                    lines));
        } catch (NaverReviewExport.Refused e) {
            return switch (e.reason()) {
                case NOT_XLSX -> ResponseEntity.badRequest().body(Map.of("error", "not_xlsx"));
                case HEADER_MISMATCH -> ResponseEntity.unprocessableEntity().body(Map.of("error", "header_mismatch",
                        "missing", e.missing(), "unexpected", e.unexpected()));
                case INVALID_ROWS -> ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid_rows",
                        "rows", e.rows(), "rowCount", e.rowCount()));
            };
        }
    }
}
