package com.sellerops.product.analysis;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「AI로 읽기」 on the analysis screen, and the named-review press the accuracy evaluation uses. Each is a seller's
 * press: nothing here runs on a schedule, and every model call goes through the capability's one door.
 */
@RestController
@RequestMapping("/api/products")
public class ProductAspectReadingController {

    private final ProductAspectReadingService service;

    public ProductAspectReadingController(ProductAspectReadingService service) {
        this.service = service;
    }

    /** Whether the button is offered at all. */
    @GetMapping("/analysis/ai-read")
    public Map<String, Boolean> available(@AuthenticationPrincipal AuthPrincipal p) {
        return Map.of("enabled", service.isEnabledFor(p.orgId()));
    }

    @PostMapping("/{productId}/analysis/ai-read")
    public ProductAspectReadingService.ReadSummary readProduct(@AuthenticationPrincipal AuthPrincipal p,
                                                               @PathVariable UUID productId,
                                                               @RequestParam(required = false) Integer days,
                                                               @RequestParam(required = false) String referenceDate) {
        int period;
        try {
            period = ProductAnalysisService.days(days);
        } catch (IllegalArgumentException bad) {
            throw ApiException.badRequest(bad.getMessage());
        }
        try {
            return service.readProduct(p.orgId(), productId, period, date(referenceDate));
        } catch (IllegalArgumentException notFound) {
            throw ApiException.notFound(notFound.getMessage());
        }
    }

    public record NamedRequest(List<UUID> reviewIds) {
    }

    @PostMapping("/analysis/ai-read/reviews")
    public ProductAspectReadingService.NamedReadSummary readReviews(@AuthenticationPrincipal AuthPrincipal p,
                                                                    @RequestBody NamedRequest request) {
        return service.readReviews(p.orgId(), request == null ? List.of() : request.reviewIds());
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("기준일 형식이 올바르지 않아요.");
        }
    }
}
