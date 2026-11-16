package com.sellerops.intakealert;

import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes one user's intake-alert settings, and closes the queue a change invalidates <b>in the same
 * transaction</b>.
 *
 * <ul>
 *   <li>Off → on: {@code watchFrom} = now (nothing earlier is ever told) and the generation goes up.</li>
 *   <li>On → off: the generation goes up and every queued item and prepared message is closed TURNED_OFF.</li>
 *   <li>A kind turned off: its queued items and every prepared message carrying it are closed KIND_OFF. A kind turned
 *       back on starts from now ({@code reviewsFrom}/{@code inquiriesFrom}).</li>
 *   <li>Number changed or removed, consent withdrawn: the generation goes up and the queue is closed NUMBER_CHANGED /
 *       CONSENT_WITHDRAWN — a message queued for the old number is never sent to the new one.</li>
 * </ul>
 * Separately, every send re-checks the delivery against the current settings ({@link IntakeAlertEngine#mismatch}).
 */
@Service
public class IntakeAlertSettingsService {

    private final IntakeAlertSettingRepository settings;
    private final IntakeAlertItemRepository items;
    private final IntakeAlertDeliveryRepository deliveries;
    private final IntakeAlertSubjects subjects;
    private final IntakeAlertSender sender;
    private final LocalSecretSealer sealer;
    private final IntakeAlertViews.Limits limits;
    private Clock clock = Clock.systemUTC();

    public IntakeAlertSettingsService(IntakeAlertSettingRepository settings, IntakeAlertItemRepository items,
                                      IntakeAlertDeliveryRepository deliveries, IntakeAlertSubjects subjects,
                                      IntakeAlertSender sender, LocalSecretSealer sealer,
                                      @Value("${sellerops.intake-alert.recovery-hours:48}") long recoveryHours,
                                      @Value("${sellerops.intake-alert.old-after-hours:72}") long oldAfterHours,
                                      @Value("${sellerops.intake-alert.per-pass-cap:5}") int perPassCap,
                                      @Value("${sellerops.intake-alert.tick-ms:30000}") long tickMs) {
        this.settings = settings;
        this.items = items;
        this.deliveries = deliveries;
        this.subjects = subjects;
        this.sender = sender;
        this.sealer = sealer;
        this.limits = new IntakeAlertViews.Limits(recoveryHours, oldAfterHours, Math.max(1, perPassCap),
                Math.max(1, tickMs / 1000));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IntakeAlertViews.SettingsView get(UUID orgId, UUID userId) {
        return view(settings.findByOrgIdAndUserId(orgId, userId).orElseGet(() -> blank(orgId, userId)));
    }

    @Transactional
    public IntakeAlertViews.SettingsView update(UUID orgId, UUID userId, IntakeAlertViews.SettingsRequest req) {
        Instant now = clock.instant();
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseGet(() -> blank(orgId, userId));
        boolean wasOn = s.isEnabled();
        boolean wasReviews = s.isNotifyReviews();
        boolean wasInquiries = s.isNotifyInquiries();

        validateShape(req);
        if (req.deliveryMode() != null) {
            s.setDeliveryMode(IntakeAlertSetting.DeliveryMode.valueOf(req.deliveryMode()));
        }
        if (req.digestMinutes() != null) s.setDigestMinutes(req.digestMinutes());
        if (req.quietHoursEnabled() != null) s.setQuietHoursEnabled(req.quietHoursEnabled());
        if (req.daysMask() != null) s.setDaysMask(req.daysMask());
        if (req.startMinute() != null) s.setStartMinute(req.startMinute());
        if (req.endMinute() != null) s.setEndMinute(req.endMinute());
        if (s.getStartMinute() >= s.getEndMinute()) {
            throw bad("알림 받을 시작 시각은 끝 시각보다 빨라야 해요.");
        }
        if (req.notifyReviews() != null) s.setNotifyReviews(req.notifyReviews());
        if (req.notifyInquiries() != null) s.setNotifyInquiries(req.notifyInquiries());

        // Number and consent.
        IntakeAlertItem.CancelReason numberReason = null;
        if (Boolean.FALSE.equals(req.phoneConsent()) && s.getPhoneSealed() != null) {
            clearPhone(s);
            numberReason = IntakeAlertItem.CancelReason.CONSENT_WITHDRAWN;
        } else if (Boolean.TRUE.equals(req.removePhone()) && s.getPhoneSealed() != null) {
            clearPhone(s);
            numberReason = IntakeAlertItem.CancelReason.NUMBER_CHANGED;
        } else if (req.phone() != null && !req.phone().isBlank()) {
            String digits = PhoneNumbers.normalize(req.phone())
                    .orElseThrow(() -> bad("휴대폰 번호 형식이 아니에요. 010으로 시작하는 번호를 적어 주세요."));
            if (!Boolean.TRUE.equals(req.phoneConsent())) {
                throw bad("번호를 저장하려면 알림 수신과 번호 저장에 동의해 주세요.");
            }
            if (!sealer.available()) {
                throw ApiException.conflict("이 서버에 암호화 키가 없어 번호를 저장할 수 없어요. 모의 알림은 번호 없이 볼 수 있어요.");
            }
            boolean changed = !samePhone(s, digits);
            if (changed) {
                LocalSecretSealer.Sealed sealed = sealer.seal(digits.getBytes(StandardCharsets.UTF_8));
                if (s.getPhoneSealed() != null) {
                    numberReason = IntakeAlertItem.CancelReason.NUMBER_CHANGED;
                }
                s.setPhoneSealed(sealed.envelope());
                s.setPhoneKeyId(sealed.keyId());
                s.setPhoneLast4(PhoneNumbers.last4(digits));
            }
            s.setPhoneConsentAt(s.getPhoneConsentAt() == null || changed ? now : s.getPhoneConsentAt());
        }

        if (req.enabled() != null) s.setEnabled(req.enabled());
        boolean turnedOn = !wasOn && s.isEnabled();
        boolean turnedOff = wasOn && !s.isEnabled();
        if (turnedOn) {
            s.setWatchFrom(now);
            s.setReviewsFrom(now);
            s.setInquiriesFrom(now);
            s.setLastDigestAt(null);
        }
        if (!wasReviews && s.isNotifyReviews()) s.setReviewsFrom(now);
        if (!wasInquiries && s.isNotifyInquiries()) s.setInquiriesFrom(now);

        IntakeAlertItem.CancelReason whole = turnedOff ? IntakeAlertItem.CancelReason.TURNED_OFF
                : numberReason != null ? numberReason
                : turnedOn ? IntakeAlertItem.CancelReason.STALE_SETTINGS : null;
        if (whole != null) {
            s.setGeneration(s.getGeneration() + 1);
        }
        s = settings.saveAndFlush(s);
        if (whole != null) {
            cancel(s.getId(), null, whole, now);
        } else {
            if (wasReviews && !s.isNotifyReviews()) cancel(s.getId(), IntakeAlertItem.Kind.REVIEW,
                    IntakeAlertItem.CancelReason.KIND_OFF, now);
            if (wasInquiries && !s.isNotifyInquiries()) cancel(s.getId(), IntakeAlertItem.Kind.INQUIRY,
                    IntakeAlertItem.CancelReason.KIND_OFF, now);
        }
        return view(settings.findById(s.getId()).orElseThrow());
    }

    /** Close queued items and not-yet-sent messages (of one kind, or all). Sent and sending ones stay as recorded. */
    void cancel(UUID settingId, IntakeAlertItem.Kind kind, IntakeAlertItem.CancelReason reason, Instant now) {
        List<UUID> prepared = deliveries.preparedIds(settingId);
        if (!prepared.isEmpty()) {
            List<UUID> target = prepared;
            if (kind != null) {
                List<UUID> withKind = items.deliveryIdsOf(settingId, kind);
                target = prepared.stream().filter(withKind::contains).toList();
            }
            if (!target.isEmpty()) {
                deliveries.cancelPrepared(target, "CANCELLED_" + reason.name(), now);
                items.cancelInDeliveries(target, reason, now);
            }
        }
        items.cancelQueued(settingId, kind, reason, now);
    }

    @Transactional(readOnly = true)
    public List<IntakeAlertViews.DeliveryRow> deliveries(UUID orgId, UUID userId, int limit) {
        return settings.findByOrgIdAndUserId(orgId, userId)
                .map(s -> deliveries.findBySettingIdOrderByCreatedAtDesc(s.getId(),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream().map(this::row).toList())
                .orElse(List.of());
    }

    /** Only the recipient's own delivery, in the recipient's org: anything else is 404. */
    @Transactional(readOnly = true)
    public IntakeAlertViews.DeliveryDetail detail(UUID orgId, UUID userId, UUID deliveryId) {
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow(IntakeAlertSettingsService::notFound);
        IntakeAlertDelivery d = deliveries.findByIdAndOrgIdAndSettingId(deliveryId, orgId, s.getId())
                .orElseThrow(IntakeAlertSettingsService::notFound);
        List<IntakeAlertItem> mine = items.findByDeliveryIdOrderByCreatedAtAsc(d.getId());
        Map<IntakeAlertItem.Kind, List<UUID>> byKind = mine.stream().collect(Collectors.groupingBy(
                IntakeAlertItem::getSubjectKind, Collectors.mapping(IntakeAlertItem::getSubjectId, Collectors.toList())));
        Map<UUID, IntakeAlertSubjects.Fact> facts = new java.util.HashMap<>();
        byKind.forEach((k, ids) -> facts.putAll(subjects.of(orgId, k, ids)));
        List<IntakeAlertViews.DeliveryItem> out = new ArrayList<>();
        for (IntakeAlertItem i : mine) {
            IntakeAlertSubjects.Fact f = facts.get(i.getSubjectId());
            String path = f == null ? null : i.getSubjectKind() == IntakeAlertItem.Kind.REVIEW
                    ? "/reviews/reply/" + i.getSubjectId() : "/inquiries/" + i.getSubjectId();
            out.add(new IntakeAlertViews.DeliveryItem(i.getSubjectKind().name(), i.getSubjectId(), i.getChannelCode(),
                    i.getReason().name(), i.getIssueLabel(), i.isUrgent(), i.getStatus().name(),
                    i.getCancelReason() == null ? null : i.getCancelReason().name(), f != null && !f.handled(), path,
                    i.getSourceIngestedAt(), f == null ? null : f.receivedAt()));
        }
        return new IntakeAlertViews.DeliveryDetail(row(d), out);
    }

    private IntakeAlertViews.DeliveryRow row(IntakeAlertDelivery d) {
        return new IntakeAlertViews.DeliveryRow(d.getId(), d.getKind().name(), d.getMode().name(), d.getStatus().name(),
                d.getReviewCount(), d.getInquiryCount(), d.getUrgentCount(), d.getMessageText(), d.getLinkPath(),
                d.getFailureCode(), d.getCreatedAt(), d.getFinishedAt());
    }

    private IntakeAlertViews.SettingsView view(IntakeAlertSetting s) {
        IntakeAlertViews.Counts counts = s.getId() == null ? new IntakeAlertViews.Counts(0, 0, 0, 0, 0)
                : new IntakeAlertViews.Counts(
                        items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.PENDING),
                        items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.INCLUDED),
                        items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.DROPPED_HANDLED),
                        items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.DROPPED_OLD),
                        items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.CANCELLED));
        String reason = sender instanceof BlockedIntakeAlertSender b ? b.reason() : null;
        return new IntakeAlertViews.SettingsView(s.isEnabled(), s.isNotifyReviews(), s.isNotifyInquiries(),
                s.getDeliveryMode().name(), s.getDigestMinutes(), s.isQuietHoursEnabled(), s.getDaysMask(),
                s.getStartMinute(), s.getEndMinute(), PhoneNumbers.masked(s.getPhoneLast4()),
                s.getPhoneConsentAt() != null && s.getPhoneSealed() != null, sealer.available(), s.getWatchFrom(),
                new IntakeAlertViews.SenderView(sender.mode().name(), reason), limits, counts);
    }

    private void validateShape(IntakeAlertViews.SettingsRequest req) {
        if (req.deliveryMode() != null && !List.of("EACH", "DIGEST").contains(req.deliveryMode())) {
            throw bad("알림 방식은 EACH 또는 DIGEST예요.");
        }
        if (req.digestMinutes() != null && (req.digestMinutes() < 15 || req.digestMinutes() > 1440)) {
            throw bad("묶음 간격은 15분에서 24시간 사이로 정해 주세요.");
        }
        if (req.daysMask() != null && (req.daysMask() < 1 || req.daysMask() > 127)) {
            throw bad("알림 받을 요일을 하루 이상 골라 주세요.");
        }
        if (req.startMinute() != null && (req.startMinute() < 0 || req.startMinute() > 1439)) {
            throw bad("시작 시각이 올바르지 않아요.");
        }
        if (req.endMinute() != null && (req.endMinute() < 1 || req.endMinute() > 1440)) {
            throw bad("끝 시각이 올바르지 않아요.");
        }
    }

    private boolean samePhone(IntakeAlertSetting s, String digits) {
        if (s.getPhoneSealed() == null) {
            return false;
        }
        return sealer.open(s.getPhoneKeyId(), s.getPhoneSealed())
                .map(b -> new String(b, StandardCharsets.UTF_8).equals(digits)).orElse(false);
    }

    private static void clearPhone(IntakeAlertSetting s) {
        s.setPhoneSealed(null);
        s.setPhoneKeyId(null);
        s.setPhoneLast4(null);
        s.setPhoneConsentAt(null);
    }

    private static IntakeAlertSetting blank(UUID orgId, UUID userId) {
        IntakeAlertSetting s = new IntakeAlertSetting();
        s.setOrgId(orgId);
        s.setUserId(userId);
        return s;
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest(message);
    }

    private static ApiException notFound() {
        return ApiException.notFound("알림을 찾을 수 없어요.");
    }
}
