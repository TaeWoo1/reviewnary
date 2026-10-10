package com.sellerops.opportunity;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.opportunity.dto.OpportunityDraftRequest;
import com.sellerops.opportunity.dto.OpportunityOutcomeView;
import com.sellerops.opportunity.dto.OpportunityView;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Improvement opportunities — READ of derived objects, and the seller's decisions about them.
 *
 * <p>Identity in the path is {@code (issueId, kind)}: an opportunity has no id of its own. Every
 * mutation re-derives before it writes, so a decision can only be recorded about an opportunity the
 * evidence supports right now. Nothing here sends, publishes, or approves anything.
 *
 * <p>Each mutation also appends one row to the opportunity's decision trail, which rides back on the
 * view — there is no separate history endpoint, because a screen that can render the decision can
 * always render how it got there and should never be able to show one without the other.
 */
@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {

    private final OpportunityService service;

    public OpportunityController(OpportunityService service) {
        this.service = service;
    }

    @GetMapping
    public List<OpportunityView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                      @RequestParam(required = false) UUID productId,
                                      @RequestParam(required = false) UUID issueId,
                                      @RequestParam(defaultValue = "false") boolean includeDismissed,
                                      @RequestParam(required = false) LocalDate referenceDate) {
        return service.list(principal.orgId(), orToday(referenceDate), productId, issueId, includeDismissed);
    }

    @PostMapping("/{issueId}/{kind}/accept")
    public OpportunityView accept(@AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID issueId, @PathVariable OpportunityKind kind,
                                  @RequestParam(required = false) LocalDate referenceDate) {
        return service.accept(principal.orgId(), principal.userId(), issueId, kind, orToday(referenceDate));
    }

    @PostMapping("/{issueId}/{kind}/dismiss")
    public OpportunityView dismiss(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID issueId, @PathVariable OpportunityKind kind,
                                   @RequestParam(required = false) LocalDate referenceDate) {
        return service.dismiss(principal.orgId(), principal.userId(), issueId, kind, orToday(referenceDate));
    }

    @PostMapping("/{issueId}/{kind}/restore")
    public OpportunityView restore(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID issueId, @PathVariable OpportunityKind kind,
                                   @RequestParam(required = false) LocalDate referenceDate) {
        return service.restore(principal.orgId(), principal.userId(), issueId, kind, orToday(referenceDate));
    }

    /**
     * 적용했습니다 — the seller carried the prepared action out.
     *
     * <p>A POST the seller's own press causes, immediately after the save that put the text in their library. It
     * records the act, anchors the four-week window the result will be read in, and — when the application was
     * itself a change in the seller's own records — records the remediation on the problem.
     *
     * <p>Nothing is sent anywhere. {@code artifact} says what it landed in and {@code ref} names that row when
     * there is one; {@code SELLER_DECLARED} is the seller's own word and is recorded as that.
     */
    @PostMapping("/{issueId}/{kind}/apply")
    public OpportunityService.Applied apply(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID issueId, @PathVariable OpportunityKind kind,
                                            @RequestParam(required = false) LocalDate referenceDate,
                                            @RequestBody ApplyRequest request) {
        return service.apply(principal.orgId(), principal.userId(), issueId, kind, orToday(referenceDate),
                request == null ? null : request.artifact(), request == null ? null : request.ref());
    }

    /**
     * 한 일과 그 결과 — every anchored result for one repeated problem, newest first.
     *
     * <p>A read of its own rather than a field on the opportunity list, because the two have different
     * lifetimes: an opportunity stops being derived once the issue resolves or the library starts mentioning the
     * aspect, and the record of what the seller did must outlive that. Same rows, read by the issue instead of
     * by the suggestion.
     */
    @GetMapping("/outcomes")
    public List<OpportunityOutcomeView> outcomes(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @RequestParam UUID issueId) {
        return service.outcomes(principal.orgId(), issueId);
    }

    /**
     * Read every outcome window that has closed — the same pass the post-ingest listener runs, by hand.
     *
     * <p>Here for the same reason {@code POST /api/review-issues/lifecycle-pass} is: a product whose analysis
     * advances only when data arrives cannot be demonstrated, and an operator looking at a four-week-old
     * application needs to be able to ask. Idempotent: a second call on the same day reads nothing, because a
     * settled verdict is never selected again.
     */
    @PostMapping("/outcomes/read")
    public int readOutcomes(@AuthenticationPrincipal AuthPrincipal principal,
                            @RequestParam(required = false) LocalDate referenceDate) {
        return service.readOutcomes(principal.orgId(), orToday(referenceDate));
    }

    /** What the applied draft landed in. {@code ref} is the id of that row, or null. */
    public record ApplyRequest(AppliedArtifact artifact, UUID ref) {
    }

    @PutMapping("/{issueId}/{kind}/draft")
    public OpportunityView updateDraft(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID issueId, @PathVariable OpportunityKind kind,
                                       @RequestParam(required = false) LocalDate referenceDate,
                                       @RequestBody OpportunityDraftRequest request) {
        return service.updateDraft(principal.orgId(), principal.userId(), issueId, kind, orToday(referenceDate), request);
    }

    private static LocalDate orToday(LocalDate date) {
        return date != null ? date : LocalDate.now(ZoneOffset.UTC);
    }
}
