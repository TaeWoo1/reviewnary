package com.sellerops.intakealert;

import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Intake alerts: find, compose, send</b> — three steps, each safe to run twice, on two workers, or after a crash.
 *
 * <ol>
 *   <li><b>Reconcile</b> ({@link #reconcile}). The source of truth is the reviews and inquiries tables, not the ingest
 *       event: every pass reads this org's rows that arrived since {@code watchFrom} (and within
 *       {@link #recoveryWindow}) and have no item for this recipient yet, and writes one. The ingest event only makes the
 *       pass start sooner. An event swallowed after the ingest committed, a restart, a worker that died — the next pass
 *       still finds the row. Each item is its own small transaction against
 *       {@code uq_intake_alert_items_subject (org, recipient, kind, original)}; a second writer loses on the constraint,
 *       which is caught, so a re-collection, a repeated event or two workers can never make a second item.</li>
 *   <li><b>Compose</b> ({@link #compose}). Default (owner, 2026-11-16 night): one message per item, at once, at any
 *       hour. A safety report always goes at once. Opt-in only: DIGEST mode (one message per interval) and a receiving
 *       window (items outside it wait; two or more that waited are told together when it opens). Volume never changes
 *       the shape: a big sync still gets one message per item, at most {@link #perPassCap} per recipient per pass, and
 *       the rest stay PENDING for the next pass (every {@code tick-ms}) — a rate limit, not a merge. An item is claimed into a delivery by a conditional update
 *       (PENDING → INCLUDED), so it is in at most one message.</li>
 *   <li><b>Dispatch</b> ({@link #dispatch}). A PREPARED delivery is claimed (→ SENDING) and committed, then sent
 *       <b>outside any transaction</b>, then its outcome recorded. SENDING left behind by a crash becomes UNKNOWN
 *       ({@link #STALE_SENDING}) and is never resent automatically.</li>
 * </ol>
 *
 * <p><b>No history flood.</b> {@code watchFrom} is reset to "now" whenever alerts are turned on, so nothing that arrived
 * before is ever told. A row that arrives after but was written by the customer long before (a one-year upload) is
 * recorded DROPPED_OLD — counted, never sent ({@link #oldAfter}).
 */
@Component
public class IntakeAlertEngine {

    private static final Logger log = LoggerFactory.getLogger(IntakeAlertEngine.class);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Duration STALE_SENDING = Duration.ofMinutes(2);
    static final int PAGE = 200;

    private final IntakeAlertSettingRepository settings;
    private final IntakeAlertItemRepository items;
    private final IntakeAlertDeliveryRepository deliveries;
    private final IntakeAlertSubjects subjects;
    private final IntakeAlertSender sender;
    private final OrganizationRepository orgs;
    private final LocalSecretSealer sealer;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final String publicBaseUrl;
    private final Duration recoveryWindow;
    private final Duration oldAfter;
    private final int perPassCap;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public IntakeAlertEngine(IntakeAlertSettingRepository settings, IntakeAlertItemRepository items,
                             IntakeAlertDeliveryRepository deliveries, IntakeAlertSubjects subjects,
                             IntakeAlertSender sender, OrganizationRepository orgs, LocalSecretSealer sealer,
                             PlatformTransactionManager txManager,
                             @Value("${sellerops.public-base-url:http://localhost:5173}") String publicBaseUrl,
                             @Value("${sellerops.intake-alert.recovery-hours:48}") long recoveryHours,
                             @Value("${sellerops.intake-alert.old-after-hours:72}") long oldAfterHours,
                             @Value("${sellerops.intake-alert.per-pass-cap:5}") int perPassCap) {
        this.settings = settings;
        this.items = items;
        this.deliveries = deliveries;
        this.subjects = subjects;
        this.sender = sender;
        this.orgs = orgs;
        this.sealer = sealer;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.recoveryWindow = Duration.ofHours(recoveryHours);
        this.oldAfter = Duration.ofHours(oldAfterHours);
        this.perPassCap = Math.max(1, perPassCap);
    }

    /** Test seam. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public IntakeAlertSender sender() {
        return sender;
    }

    /** One full pass for one org: reconcile + compose for each enabled recipient, then send what is ready. */
    public void runOrg(UUID orgId) {
        for (IntakeAlertSetting s : settings.findByOrgIdAndEnabledTrue(orgId)) {
            runSetting(s);
        }
        dispatch();
    }

    /** One full pass for every enabled recipient. */
    public void runAll() {
        for (IntakeAlertSetting s : settings.findByEnabledTrue()) {
            runSetting(s);
        }
        dispatch();
    }

    private void runSetting(IntakeAlertSetting s) {
        try {
            reconcile(s);
            compose(s);
        } catch (RuntimeException e) {
            log.warn("intake alert: pass failed setting={} {}", s.getId(), e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- reconcile

    /** @return items written by this pass */
    public int reconcile(IntakeAlertSetting s) {
        if (!s.isEnabled() || s.getWatchFrom() == null) {
            return 0;
        }
        Instant now = clock.instant();
        int written = 0;
        if (s.isNotifyReviews()) {
            Instant from = later(s.fromFor(IntakeAlertItem.Kind.REVIEW), now.minus(recoveryWindow));
            List<UUID> ids = items.missingReviews(s.getOrgId(), s.getId(), from, now, PageRequest.of(0, PAGE));
            written += write(s, subjects.reviews(s.getOrgId(), ids), now);
        }
        if (s.isNotifyInquiries()) {
            Instant from = later(s.fromFor(IntakeAlertItem.Kind.INQUIRY), now.minus(recoveryWindow));
            List<UUID> ids = items.missingInquiries(s.getOrgId(), s.getId(), from, now, PageRequest.of(0, PAGE));
            written += write(s, subjects.inquiries(s.getOrgId(), ids), now);
        }
        return written;
    }

    private int write(IntakeAlertSetting s, Map<UUID, IntakeAlertSubjects.Fact> facts, Instant now) {
        int written = 0;
        List<IntakeAlertSubjects.Fact> ordered = new ArrayList<>(facts.values());
        ordered.sort(Comparator.comparing(IntakeAlertSubjects.Fact::ingestedAt));
        for (IntakeAlertSubjects.Fact f : ordered) {
            IntakeAlertItem item = new IntakeAlertItem();
            item.setOrgId(s.getOrgId());
            item.setSettingId(s.getId());
            item.setSubjectKind(f.kind());
            item.setSubjectId(f.id());
            item.setChannelCode(f.channelCode());
            item.setReason(f.reason());
            item.setIssueLabel(f.issueLabel());
            item.setUrgent(f.urgent());
            item.setSourceIngestedAt(f.ingestedAt());
            item.setStatus(statusOf(f));
            item.setSettingGeneration(s.getGeneration());
            try {
                txNew.executeWithoutResult(st -> items.saveAndFlush(item));
                written++;
            } catch (DataIntegrityViolationException e) {
                // Another writer made it first — the constraint is the guarantee, not this check.
            }
        }
        return written;
    }

    IntakeAlertItem.Status statusOf(IntakeAlertSubjects.Fact f) {
        if (f.receivedAt() == null || f.receivedAt().isBefore(f.ingestedAt().minus(oldAfter))) {
            return IntakeAlertItem.Status.DROPPED_OLD;
        }
        if (f.handled()) {
            return IntakeAlertItem.Status.DROPPED_HANDLED;
        }
        return IntakeAlertItem.Status.PENDING;
    }

    // ---------------------------------------------------------------- compose

    /** @return deliveries prepared by this pass */
    public int compose(IntakeAlertSetting s) {
        Instant now = clock.instant();
        List<IntakeAlertItem> pending = new ArrayList<>(items.findBySettingIdAndStatus(s.getId(),
                IntakeAlertItem.Status.PENDING));
        if (pending.isEmpty()) {
            return 0;
        }
        List<UUID> stale = pending.stream().filter(i -> i.getSettingGeneration() != s.getGeneration()
                || !kindOn(s, i.getSubjectKind())).map(IntakeAlertItem::getId).toList();
        if (!stale.isEmpty()) {
            // Belt and braces: the settings change already closed these in its own transaction.
            tx.executeWithoutResult(st -> items.findAllById(stale).forEach(i -> {
                if (i.getStatus() == IntakeAlertItem.Status.PENDING) {
                    i.setStatus(IntakeAlertItem.Status.CANCELLED);
                    i.setCancelReason(IntakeAlertItem.CancelReason.STALE_SETTINGS);
                    items.save(i);
                }
            }));
            pending.removeIf(i -> stale.contains(i.getId()));
        }
        dropHandled(s, pending, now);
        if (pending.isEmpty()) {
            return 0;
        }
        pending.sort(Comparator.comparing(IntakeAlertItem::getSourceIngestedAt));
        String store = orgs.findById(s.getOrgId()).map(Organization::getName).orElse(null);
        int made = 0;
        List<IntakeAlertItem> ordinary = new ArrayList<>();
        for (IntakeAlertItem i : pending) {
            if (i.isUrgent()) {
                made += prepare(s, IntakeAlertDelivery.Kind.SINGLE, List.of(i), store, now);
            } else {
                ordinary.add(i);
            }
        }
        if (ordinary.isEmpty()) {
            return made;
        }
        ZonedDateTime local = now.atZone(SEOUL);
        if (s.isQuietHoursEnabled() && !inWindow(s, local)) {
            return made;
        }
        List<IntakeAlertItem> fresh = ordinary;
        if (s.isQuietHoursEnabled()) {
            Instant opened = local.toLocalDate().atStartOfDay(SEOUL).plusMinutes(s.getStartMinute()).toInstant();
            List<IntakeAlertItem> waited = ordinary.stream().filter(i -> i.getCreatedAt().isBefore(opened)).toList();
            if (waited.size() >= 2) {
                made += prepare(s, IntakeAlertDelivery.Kind.DIGEST, waited, store, now);
                fresh = ordinary.stream().filter(i -> !waited.contains(i)).toList();
            }
        }
        if (fresh.isEmpty()) {
            return made;
        }
        if (s.getDeliveryMode() == IntakeAlertSetting.DeliveryMode.DIGEST) {
            Instant dueBefore = now.minus(Duration.ofMinutes(s.getDigestMinutes()));
            Integer slot = tx.execute(st -> settings.takeDigestSlot(s.getId(), now, dueBefore));
            if (slot != null && slot == 1) {
                made += prepare(s, IntakeAlertDelivery.Kind.DIGEST, fresh, store, now);
            }
            return made;
        }
        int budget = perPassCap;
        for (IntakeAlertItem i : fresh) {
            if (budget <= 0) {
                break; // the rest stay PENDING, each still its own message on a later pass
            }
            int one = prepare(s, IntakeAlertDelivery.Kind.SINGLE, List.of(i), store, now);
            made += one;
            budget -= one;
        }
        return made;
    }

    private void dropHandled(IntakeAlertSetting s, List<IntakeAlertItem> pending, Instant now) {
        List<UUID> handled = new ArrayList<>();
        for (IntakeAlertItem.Kind kind : IntakeAlertItem.Kind.values()) {
            List<UUID> ids = pending.stream().filter(i -> i.getSubjectKind() == kind).map(IntakeAlertItem::getSubjectId)
                    .toList();
            Map<UUID, IntakeAlertSubjects.Fact> facts = subjects.of(s.getOrgId(), kind, ids);
            for (IntakeAlertItem i : pending) {
                if (i.getSubjectKind() != kind) {
                    continue;
                }
                IntakeAlertSubjects.Fact f = facts.get(i.getSubjectId());
                // Gone from the org (deleted, or never this org's) or already handled: never told.
                if (f == null || f.handled()) {
                    handled.add(i.getId());
                }
            }
        }
        if (!handled.isEmpty()) {
            tx.executeWithoutResult(st -> items.dropHandled(handled, now));
            pending.removeIf(i -> handled.contains(i.getId()));
        }
    }

    static boolean inWindow(IntakeAlertSetting s, ZonedDateTime local) {
        DayOfWeek d = local.getDayOfWeek();
        int bit = 1 << (d.getValue() - 1);
        int minute = local.getHour() * 60 + local.getMinute();
        return (s.getDaysMask() & bit) != 0 && minute >= s.getStartMinute() && minute < s.getEndMinute();
    }

    /** One delivery over {@code group}; 0 when another worker already took every item. */
    private int prepare(IntakeAlertSetting s, IntakeAlertDelivery.Kind kind, List<IntakeAlertItem> group, String store,
                        Instant now) {
        Integer made = tx.execute(st -> {
            IntakeAlertDelivery d = new IntakeAlertDelivery();
            d.setOrgId(s.getOrgId());
            d.setSettingId(s.getId());
            d.setKind(kind);
            d.setSettingGeneration(s.getGeneration());
            d.setMode(sender.mode());
            d.setStatus(IntakeAlertDelivery.Status.PREPARED);
            d.setMessageText("-");
            d.setLinkPath("/notice/pending");
            d = deliveries.saveAndFlush(d);
            UUID id = d.getId();
            int claimed = items.claim(group.stream().map(IntakeAlertItem::getId).toList(), id, now);
            if (claimed == 0) {
                st.setRollbackOnly();
                return 0;
            }
            List<IntakeAlertItem> mine = items.findByDeliveryIdOrderByCreatedAtAsc(id);
            IntakeAlertDelivery fresh = deliveries.findById(id).orElseThrow();
            fill(fresh, mine, store);
            deliveries.save(fresh);
            return 1;
        });
        return made == null ? 0 : made;
    }

    static void fill(IntakeAlertDelivery d, List<IntakeAlertItem> mine, String store) {
        d.setReviewCount((int) mine.stream().filter(i -> i.getSubjectKind() == IntakeAlertItem.Kind.REVIEW).count());
        d.setInquiryCount((int) mine.stream().filter(i -> i.getSubjectKind() == IntakeAlertItem.Kind.INQUIRY).count());
        d.setUrgentCount((int) mine.stream().filter(IntakeAlertItem::isUrgent).count());
        if (mine.size() == 1) {
            d.setKind(IntakeAlertDelivery.Kind.SINGLE);
        }
        d.setMessageText(render(d, mine, store).text());
        d.setLinkPath("/notice/" + d.getId());
    }

    static IntakeAlertMessage.Rendered render(IntakeAlertDelivery d, List<IntakeAlertItem> mine, String store) {
        return d.getKind() == IntakeAlertDelivery.Kind.SINGLE && mine.size() == 1
                ? IntakeAlertMessage.single(store, mine.get(0))
                : IntakeAlertMessage.digest(store, mine);
    }

    // ---------------------------------------------------------------- dispatch

    /** @return deliveries handed to the sender by this pass */
    public int dispatch() {
        Instant now = clock.instant();
        Integer stale = tx.execute(st -> deliveries.markStaleUnknown(now.minus(STALE_SENDING), now));
        if (stale != null && stale > 0) {
            log.warn("intake alert: {}건이 보내는 중에 멈춰 결과 확인 필요(UNKNOWN)로 남김 — 자동 재전송 없음", stale);
        }
        int sent = 0;
        for (IntakeAlertDelivery d : deliveries.findByStatusOrderByCreatedAtAsc(IntakeAlertDelivery.Status.PREPARED,
                PageRequest.of(0, 20))) {
            Integer claimed = tx.execute(st -> deliveries.claimForSend(d.getId(), now));
            if (claimed == null || claimed != 1) {
                continue;
            }
            IntakeAlertSender.Outcome outcome = sendOutsideTransaction(d);
            tx.executeWithoutResult(st -> {
                if (outcome.status() == IntakeAlertDelivery.Status.CANCELLED) {
                    items.cancelInDeliveries(List.of(d.getId()), IntakeAlertItem.CancelReason.STALE_SETTINGS,
                            clock.instant());
                }
                IntakeAlertDelivery fresh = deliveries.findById(d.getId()).orElseThrow();
                fresh.setStatus(outcome.status());
                fresh.setProviderMessageId(outcome.providerMessageId());
                fresh.setFailureCode(clip(outcome.failureCode(), 60));
                fresh.setFinishedAt(clock.instant());
                deliveries.save(fresh);
            });
            sent++;
        }
        return sent;
    }

    private IntakeAlertSender.Outcome sendOutsideTransaction(IntakeAlertDelivery d) {
        try {
            IntakeAlertSetting s = settings.findById(d.getSettingId()).orElse(null);
            List<IntakeAlertItem> mine = items.findByDeliveryIdOrderByCreatedAtAsc(d.getId());
            String mismatch = mismatch(s, d, mine);
            if (mismatch != null) {
                return new IntakeAlertSender.Outcome(IntakeAlertDelivery.Status.CANCELLED, null, mismatch);
            }
            if (d.getMode() != sender.mode()) {
                // Prepared under another sender (a restart changed the mode): never promote a mock to a live send.
                return IntakeAlertSender.Outcome.blocked("MODE_CHANGED");
            }
            String phone = null;
            if (sender.needsNumber()) {
                if (s.getPhoneConsentAt() == null || s.getPhoneSealed() == null) {
                    return IntakeAlertSender.Outcome.blocked("NO_RECIPIENT_NUMBER");
                }
                phone = sealer.open(s.getPhoneKeyId(), s.getPhoneSealed())
                        .map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
                if (phone == null) {
                    return IntakeAlertSender.Outcome.blocked("NUMBER_KEY_UNAVAILABLE");
                }
            }
            String store = orgs.findById(d.getOrgId()).map(Organization::getName).orElse(null);
            IntakeAlertMessage.Rendered r = render(d, mine, store);
            return sender.send(new OutgoingAlert(d.getId(), phone, r.variables(), r.text(),
                    publicBaseUrl + d.getLinkPath()));
        } catch (RuntimeException e) {
            log.warn("intake alert: 보내기 전 단계 실패 delivery={} {}", d.getId(), e.getClass().getSimpleName());
            return new IntakeAlertSender.Outcome(IntakeAlertDelivery.Status.FAILED, null, "PREPARE");
        }
    }

    /**
     * The check right before a send: the delivery must still match the CURRENT settings — same org, on, the same
     * generation (no off/on, number change or consent withdrawal since), every item's kind still chosen, every item
     * arrived after the current {@code watchFrom}. Any difference closes it CANCELLED with the reason; it is never
     * re-aimed at the new settings.
     */
    static String mismatch(IntakeAlertSetting s, IntakeAlertDelivery d, List<IntakeAlertItem> mine) {
        if (s == null || !s.getOrgId().equals(d.getOrgId())) {
            return "SETTING_GONE";
        }
        if (!s.isEnabled()) {
            return "SETTING_OFF";
        }
        if (s.getGeneration() != d.getSettingGeneration()) {
            return "SETTING_CHANGED";
        }
        if (mine.isEmpty()) {
            return "NO_ITEMS";
        }
        for (IntakeAlertItem i : mine) {
            if (i.getSettingGeneration() != s.getGeneration() || !i.getOrgId().equals(s.getOrgId())) {
                return "SETTING_CHANGED";
            }
            if (!kindOn(s, i.getSubjectKind())) {
                return "KIND_OFF";
            }
            Instant from = s.fromFor(i.getSubjectKind());
            if (from == null || i.getSourceIngestedAt().isBefore(from)) {
                return "BEFORE_WATCH_FROM";
            }
        }
        return null;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    static boolean kindOn(IntakeAlertSetting s, IntakeAlertItem.Kind kind) {
        return kind == IntakeAlertItem.Kind.REVIEW ? s.isNotifyReviews() : s.isNotifyInquiries();
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
