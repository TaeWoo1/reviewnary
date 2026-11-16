package com.sellerops.talktalk;

import com.sellerops.common.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned holiday / dispatch cut-off guidance and the owner's delegation of the automatic guidance to it
 * (lane contract §6-1, §6-2). Confirming a new version retires the old one; a delegation bound to the old one then
 * reads as off, and every queued automatic guidance of the org is cancelled.
 */
@Service
public class TalkTalkGuidanceService {

    private final TalkTalkGuidanceRepository guidance;
    private final TalkTalkDelegationRepository delegations;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkAccess access;
    private final TalkTalkClock clock;
    private final TalkTalkConnectionRepository connections;
    /** Who else writes the confirmed 출고 마감·휴무 somewhere (the 기본 설정 shipping document); empty in a bare test. */
    private List<TalkTalkGuidanceListener> listeners = List.of();

    public TalkTalkGuidanceService(TalkTalkGuidanceRepository guidance, TalkTalkDelegationRepository delegations,
                                   TalkTalkOutboundRepository outbound, TalkTalkAccess access, TalkTalkClock clock,
                                   TalkTalkConnectionRepository connections) {
        this.connections = connections;
        this.guidance = guidance;
        this.delegations = delegations;
        this.outbound = outbound;
        this.access = access;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setListeners(List<TalkTalkGuidanceListener> listeners) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    public record Input(Integer cutoffHour, Integer cutoffMinute, List<String> dispatchDays,
                        List<TalkTalkGuidanceRules.ClosedDate> closedDates, LocalDate confirmedThrough) {
    }

    /** The delegation as the gate reads it: on only when the latest row is on and still names the current version. */
    public record DelegationState(boolean active, String reason, TalkTalkDelegation row, TalkTalkGuidance guidance) {
    }

    @Transactional
    public TalkTalkGuidance saveDraft(UUID orgId, UUID userId, Input in) {
        access.owner(orgId, userId);
        validate(in, false);
        TalkTalkGuidance latest = guidance.findFirstByOrgIdOrderByVersionDesc(orgId).orElse(null);
        TalkTalkGuidance g;
        if (latest != null && latest.getStatus() == TalkTalkGuidance.Status.DRAFT) {
            g = latest;
        } else {
            g = new TalkTalkGuidance();
            g.setOrgId(orgId);
            g.setVersion(latest == null ? 1 : latest.getVersion() + 1);
            g.setStatus(TalkTalkGuidance.Status.DRAFT);
            g.setCreatedBy(userId);
        }
        g.setCutoffHour(in.cutoffHour());
        g.setCutoffMinute(in.cutoffMinute() == null ? 0 : in.cutoffMinute());
        g.setDispatchDays(TalkTalkGuidanceRules.daysCsv(days(in.dispatchDays())));
        g.setClosedDates(TalkTalkGuidanceRules.closedLines(in.closedDates() == null ? List.of() : in.closedDates()));
        g.setConfirmedThrough(in.confirmedThrough());
        g.setFingerprint(TalkTalkGuidanceRules.fingerprint(g));
        return guidance.save(g);
    }

    @Transactional
    public TalkTalkGuidance confirm(UUID orgId, UUID userId, UUID guidanceId, String expectedFingerprint) {
        access.owner(orgId, userId);
        connections.lockByOrgId(orgId);
        TalkTalkGuidance g = guidance.findByIdAndOrgId(guidanceId, orgId)
                .orElseThrow(() -> ApiException.notFound("배송 기준을 찾을 수 없어요."));
        if (g.getStatus() != TalkTalkGuidance.Status.DRAFT) {
            throw ApiException.conflict("NOT_DRAFT", "작성 중인 기준만 확정할 수 있어요.");
        }
        if (expectedFingerprint == null || !expectedFingerprint.equals(g.getFingerprint())) {
            throw ApiException.conflict("GUIDANCE_CHANGED", "기준 내용이 바뀌었어요. 다시 확인해 주세요.");
        }
        validate(new Input(g.getCutoffHour(), g.getCutoffMinute(),
                List.of(g.getDispatchDays().split(",")), TalkTalkGuidanceRules.closed(g.getClosedDates()),
                g.getConfirmedThrough()), true);
        Instant now = clock.now();
        guidance.findFirstByOrgIdAndStatus(orgId, TalkTalkGuidance.Status.CONFIRMED).ifPresent(old -> {
            old.setStatus(TalkTalkGuidance.Status.RETIRED);
            old.setRetiredAt(now);
            guidance.saveAndFlush(old);
        });
        g.setStatus(TalkTalkGuidance.Status.CONFIRMED);
        g.setConfirmedBy(userId);
        g.setConfirmedAt(now);
        TalkTalkGuidance saved = guidance.saveAndFlush(g);
        outbound.cancelQueuedAutoForOrg(orgId, "GUIDANCE_CHANGED", now);
        listeners.forEach(l -> l.guidanceChanged(orgId));
        return saved;
    }

    @Transactional
    public TalkTalkGuidance retire(UUID orgId, UUID userId, UUID guidanceId) {
        access.owner(orgId, userId);
        connections.lockByOrgId(orgId);
        TalkTalkGuidance g = guidance.findByIdAndOrgId(guidanceId, orgId)
                .orElseThrow(() -> ApiException.notFound("배송 기준을 찾을 수 없어요."));
        if (g.getStatus() != TalkTalkGuidance.Status.CONFIRMED) {
            throw ApiException.conflict("NOT_CONFIRMED", "확정된 기준만 내릴 수 있어요.");
        }
        g.setStatus(TalkTalkGuidance.Status.RETIRED);
        g.setRetiredAt(clock.now());
        outbound.cancelQueuedAutoForOrg(orgId, "GUIDANCE_RETIRED", clock.now());
        TalkTalkGuidance saved = guidance.saveAndFlush(g);
        listeners.forEach(l -> l.guidanceChanged(orgId));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<TalkTalkGuidance> current(UUID orgId) {
        return guidance.findFirstByOrgIdAndStatus(orgId, TalkTalkGuidance.Status.CONFIRMED);
    }

    @Transactional(readOnly = true)
    public List<TalkTalkGuidance> versions(UUID orgId) {
        return guidance.findByOrgIdOrderByVersionDesc(orgId);
    }

    @Transactional(readOnly = true)
    public Optional<TalkTalkGuidance> byId(UUID orgId, UUID id) {
        return id == null ? Optional.empty() : guidance.findByIdAndOrgId(id, orgId);
    }

    /**
     * Turn the automatic guidance on (bound to the version and preview the owner saw) or off. Always a new row; turning
     * it off or rebinding it cancels every automatic guidance still queued under the previous row.
     */
    @Transactional
    public TalkTalkDelegation delegate(UUID orgId, UUID userId, boolean enabled, UUID guidanceId,
                                       String guidanceFingerprint, String previewFingerprint) {
        access.owner(orgId, userId);
        connections.lockByOrgId(orgId);
        TalkTalkDelegation row = new TalkTalkDelegation();
        row.setOrgId(orgId);
        row.setActorUserId(userId);
        row.setEnabled(enabled);
        if (enabled) {
            TalkTalkGuidance g = current(orgId)
                    .orElseThrow(() -> ApiException.conflict("NO_CONFIRMED_GUIDANCE", "확정된 배송 기준이 먼저 있어야 해요."));
            if (!g.getId().equals(guidanceId) || !g.getFingerprint().equals(guidanceFingerprint)) {
                throw ApiException.conflict("GUIDANCE_CHANGED", "확인하신 기준과 지금 확정된 기준이 달라요. 다시 확인해 주세요.");
            }
            if (!TalkTalkGuidanceRules.previewFingerprint(g).equals(previewFingerprint)) {
                throw ApiException.conflict("PREVIEW_CHANGED", "안내 문장이 바뀌었어요. 미리보기를 다시 확인해 주세요.");
            }
            row.setGuidanceId(g.getId());
            row.setGuidanceFingerprint(g.getFingerprint());
            row.setPreviewFingerprint(previewFingerprint);
        }
        TalkTalkDelegation saved = delegations.save(row);
        outbound.cancelQueuedAutoForOrg(orgId, enabled ? "DELEGATION_CHANGED" : "DELEGATION_OFF", clock.now());
        return saved;
    }

    @Transactional(readOnly = true)
    public DelegationState delegation(UUID orgId) {
        TalkTalkDelegation row = delegations.findFirstByOrgIdOrderByCreatedAtDesc(orgId).orElse(null);
        if (row == null || !row.isEnabled()) {
            return new DelegationState(false, "DELEGATION_OFF", row, null);
        }
        TalkTalkGuidance g = current(orgId).orElse(null);
        if (g == null) {
            return new DelegationState(false, "NO_CONFIRMED_GUIDANCE", row, null);
        }
        if (!g.getId().equals(row.getGuidanceId()) || !g.getFingerprint().equals(row.getGuidanceFingerprint())
                || !TalkTalkGuidanceRules.previewFingerprint(g).equals(row.getPreviewFingerprint())) {
            return new DelegationState(false, "DELEGATION_STALE", row, g);
        }
        return new DelegationState(true, null, row, g);
    }

    @Transactional(readOnly = true)
    public List<TalkTalkDelegation> history(UUID orgId) {
        return delegations.findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, 20));
    }

    private static Set<DayOfWeek> days(List<String> in) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (in != null) {
            out.addAll(TalkTalkGuidanceRules.days(String.join(",", in)));
        }
        return out;
    }

    private void validate(Input in, boolean forConfirm) {
        List<String> problems = new ArrayList<>();
        if (in.cutoffHour() != null && (in.cutoffHour() < 0 || in.cutoffHour() > 23)) {
            problems.add("출고 마감 시각은 0~23시로 적어 주세요.");
        }
        if (in.cutoffMinute() != null && (in.cutoffMinute() < 0 || in.cutoffMinute() > 59)) {
            problems.add("분은 0~59로 적어 주세요.");
        }
        if (in.closedDates() != null) {
            for (TalkTalkGuidanceRules.ClosedDate c : in.closedDates()) {
                if (c.date() == null) {
                    problems.add("휴무일 날짜가 비어 있어요.");
                } else if (c.note() != null && c.note().length() > 40) {
                    problems.add("휴무일 메모는 40자까지 적을 수 있어요.");
                }
            }
            if (in.closedDates().size() > 120) {
                problems.add("휴무일은 120개까지 적을 수 있어요.");
            }
        }
        if (forConfirm) {
            if (in.cutoffHour() == null) {
                problems.add("확정하려면 출고 마감 시각이 있어야 해요.");
            }
            if (days(in.dispatchDays()).isEmpty()) {
                problems.add("확정하려면 출고 요일이 하나 이상 있어야 해요.");
            }
            LocalDate today = clock.now().atZone(TalkTalkGuidanceRules.KST).toLocalDate();
            if (in.confirmedThrough() == null || in.confirmedThrough().isBefore(today)) {
                problems.add("휴무일 목록을 언제까지 확인했는지(오늘 이후 날짜) 적어 주세요.");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest(String.join(" ", problems));
        }
    }
}
