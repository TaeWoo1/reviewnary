package com.sellerops.knowledge.currency;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.schedule.KnowledgeScheduleService;
import com.sellerops.user.User;
import com.sellerops.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>자료끼리 다른 값 — where the seller sees them and settles them</b> (2026-11-15, owner decision 1): the disagreements
 * the company's material holds now, the replacements the seller chose, each document's 적용일, and the schedule lines.
 *
 * <p>Org-scoped from the JWT on every route. Reads compute the state at that moment; nothing here reaches a channel or a
 * model.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeCurrencyController {

    private final KnowledgeCurrencyService currency;
    private final KnowledgeScheduleService schedules;
    private final UserRepository users;

    public KnowledgeCurrencyController(KnowledgeCurrencyService currency, KnowledgeScheduleService schedules,
                                       UserRepository users) {
        this.currency = currency;
        this.schedules = schedules;
        this.users = users;
    }

    /** One document on one side of a disagreement. */
    public record SideView(UUID sourceId, String title, String value, LocalDate effectiveFrom) {
    }

    public record ConflictView(String attribute, String subject, String condition, String status, String reason,
                               String sentence, String scope, UUID productId, SideView current, SideView other,
                               UUID replacementId) {
    }

    public record ReplacementView(UUID id, UUID newerSourceId, String newerTitle, UUID olderSourceId,
                                  String olderTitle, String attribute, String subject, String condition,
                                  String createdByName, Instant createdAt) {
    }

    public record CurrencyView(List<ConflictView> conflicts, List<ReplacementView> replacements,
                               Map<UUID, LocalDate> effectiveDates) {
    }

    public record EffectiveFromRequest(LocalDate effectiveFrom) {
    }

    public record SupersedeRequest(UUID newerSourceId, UUID olderSourceId, ClaimAttribute attribute,
                                   ClaimCondition condition) {
    }

    @GetMapping("/conflicts")
    public CurrencyView conflicts(@AuthenticationPrincipal AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        KnowledgeState state = currency.overview(orgId);
        List<ConflictView> conflicts = new ArrayList<>();
        for (KnowledgeState.Conflict c : state.conflicts()) {
            KnowledgeState.Source source = state.source(c.a().sourceId());
            conflicts.add(new ConflictView(c.attribute().name(), c.subjectKo(), c.condition().name(), c.status().name(),
                    c.reason().name(), c.sentenceKo(), source == null ? null : source.kind(),
                    source == null ? null : source.productId(), side(c.a()), side(c.b()), c.replacementId()));
        }
        List<ReplacementView> replacements = new ArrayList<>();
        for (KnowledgeSupersession s : currency.supersessions(orgId)) {
            String subject = s.getCondition() == ClaimCondition.GENERAL ? s.getAttribute().labelKo()
                    : s.getCondition().labelKo() + " " + s.getAttribute().labelKo();
            replacements.add(new ReplacementView(s.getId(), s.getNewerSourceId(),
                    currency.titleOf(orgId, s.getNewerSourceId()), s.getOlderSourceId(),
                    currency.titleOf(orgId, s.getOlderSourceId()), s.getAttribute().name(), subject,
                    s.getCondition().name(), s.getCreatedByName(), s.getCreatedAt()));
        }
        Map<UUID, LocalDate> dates = new java.util.LinkedHashMap<>();
        currency.effectiveDates(orgId).forEach((k, v) -> {
            if (v != null) {
                dates.put(k, v);
            }
        });
        return new CurrencyView(conflicts, replacements, dates);
    }

    /** A document's 적용일; {@code null} clears it. */
    @PutMapping("/sources/{sourceId}/effective-from")
    public EffectiveFromRequest setEffectiveFrom(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable UUID sourceId,
                                                 @RequestBody EffectiveFromRequest request) {
        return new EffectiveFromRequest(currency.setEffectiveFrom(principal.orgId(), sourceId,
                request == null ? null : request.effectiveFrom(), actorName(principal)));
    }

    @PostMapping("/supersessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplacementView supersede(@AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestBody SupersedeRequest request) {
        UUID orgId = principal.orgId();
        KnowledgeSupersession s = currency.supersede(orgId, request.newerSourceId(), request.olderSourceId(),
                request.attribute(), request.condition(), actorName(principal));
        String subject = s.getCondition() == ClaimCondition.GENERAL ? s.getAttribute().labelKo()
                : s.getCondition().labelKo() + " " + s.getAttribute().labelKo();
        return new ReplacementView(s.getId(), s.getNewerSourceId(), currency.titleOf(orgId, s.getNewerSourceId()),
                s.getOlderSourceId(), currency.titleOf(orgId, s.getOlderSourceId()), s.getAttribute().name(), subject,
                s.getCondition().name(), s.getCreatedByName(), s.getCreatedAt());
    }

    @DeleteMapping("/supersessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSupersession(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        currency.removeSupersession(principal.orgId(), id);
    }

    @GetMapping("/schedules")
    public List<KnowledgeScheduleService.ScheduleView> schedules(@AuthenticationPrincipal AuthPrincipal principal) {
        return schedules.list(principal.orgId());
    }

    @PostMapping("/schedules/{id}/active")
    public KnowledgeScheduleService.ScheduleView setScheduleActive(@AuthenticationPrincipal AuthPrincipal principal,
                                                                   @PathVariable UUID id,
                                                                   @RequestParam boolean active) {
        return schedules.setActive(principal.orgId(), id, active);
    }

    private static SideView side(KnowledgeState.Side s) {
        return new SideView(s.sourceId(), s.title(), s.valueKo(), s.effectiveFrom());
    }

    private String actorName(AuthPrincipal principal) {
        return users.findById(principal.userId()).map(User::getName).orElse(null);
    }
}
