package com.sellerops.inquiry.followup;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckService;
import com.sellerops.inquiry.sellercheck.SellerCheckOutcome;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>What the seller is still waiting on after deciding a reply</b> (2026-11-03 sixth review).
 *
 * <p><b>Answering the customer and finishing the action are two facts.</b> A reply that tells the customer a refund is in
 * progress can be registered while the refund is still open, so a registered reply does not close the inquiry for the
 * seller. Three things can still be open, each read from a record that already exists:
 * <ul>
 *   <li>{@link #REGISTRATION} — the reply is approved and its registration is not confirmed yet (phase {@code APPROVED} ·
 *       {@code ACTION_PENDING} · {@code EXECUTED}; {@code COMPLETED} is the verified read-back);</li>
 *   <li>{@link #ACTION} — the reply is registered and the seller's current record says 「접수·진행 중」, with the day they
 *       said they would look again;</li>
 *   <li>{@link #CUSTOMER} — the reply is registered and the record says 「고객 정보가 더 필요함」.</li>
 * </ul>
 * A record of 「처리 완료」 or 「처리 불가」 with a confirmed registration is finished and appears nowhere.
 *
 * <p><b>A next-check day arriving hands a check back to the seller, nothing else.</b> On or after that day the row moves
 * to {@code due}, which the Home draws among the things to check. This is a read: it writes no draft, no approval, no
 * hold and no message, and nothing in the product sends a reply because a date passed. The date is the seller's own task,
 * not a promise to the customer, and it is not part of what a draft is written from.
 *
 * <p>An inquiry whose reply is still the seller's to write ({@code OPEN} · {@code PROPOSED}) is not a follow-up: it is in
 * the inquiry queue, as work.
 */
@Service
public class InquiryFollowUpService {

    public static final String REGISTRATION = "REGISTRATION";
    public static final String ACTION = "ACTION";
    public static final String CUSTOMER = "CUSTOMER";
    /**
     * The answer is registered and the record now says 「처리 완료」, but the answer was written from another record — the
     * customer was told less (2026-11-05 tenth review). Always a check today, until the seller closes it for that record
     * with 「따로 안내함」 or 「안내 필요 없음」. Nothing is sent, ever.
     */
    public static final String TOLD_BEHIND = "TOLD_BEHIND";

    /** Rows per list; the totals are never the drawn count. */
    static final int MAX_ROWS = 50;

    private static final Set<InquiryWorkItemPhase> REGISTERING =
            EnumSet.of(InquiryWorkItemPhase.APPROVED, InquiryWorkItemPhase.ACTION_PENDING, InquiryWorkItemPhase.EXECUTED);
    private static final Set<String> STILL_OPEN =
            Set.of(SellerCheckOutcome.IN_PROGRESS.name(), SellerCheckOutcome.NEEDS_CUSTOMER_INFO.name());

    /**
     * @param waitingOn   {@link #REGISTRATION} · {@link #ACTION} · {@link #CUSTOMER}
     * @param due         the next-check day has come: the row is a check for the seller today
     * @param outcome     the current record's outcome, null when there is none
     * @param nextCheckOn the seller's next-check day, for an action in progress
     * @param since       when the row began waiting — the record's time, or the work item's last change for a registration
     * @param to          the inquiry's own screen, where the approved reply and the record are
     */
    public record FollowUpRow(UUID workItemId, UUID inquiryId, String subject, String channelCode, String waitingOn,
                              boolean due, String outcome, String outcomeKo, String note, LocalDate nextCheckOn,
                              String phase, Instant since, String to, String toldOutcome, String toldOutcomeKo,
                              UUID checkId) {
        public FollowUpRow(UUID workItemId, UUID inquiryId, String subject, String channelCode, String waitingOn,
                           boolean due, String outcome, String outcomeKo, String note, LocalDate nextCheckOn,
                           String phase, Instant since, String to) {
            this(workItemId, inquiryId, subject, channelCode, waitingOn, due, outcome, outcomeKo, note, nextCheckOn,
                    phase, since, to, null, null, null);
        }
    }

    /**
     * @param due     checks whose day has come, earliest day first
     * @param waiting everything else still open: registrations, then actions by next-check day, then customer replies
     * @param today   the seller's day the split was made on
     */
    public record FollowUps(List<FollowUpRow> due, int dueTotal, List<FollowUpRow> waiting, int waitingTotal,
                            LocalDate today) {
    }

    /** What one work item is still waiting on, or empty when nothing is open after its reply. */
    record Open(String waitingOn, boolean due) {
    }

    private final InquiryWorkItemRepository workItems;
    private final InquirySellerCheckRepository checks;
    private final InquirySellerCheckService sellerChecks;
    private final InquiryRepository inquiries;
    private final ChannelRepository channels;
    private org.springframework.beans.factory.ObjectProvider<
            com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService> answers;

    public InquiryFollowUpService(InquiryWorkItemRepository workItems, InquirySellerCheckRepository checks,
                                  InquirySellerCheckService sellerChecks, InquiryRepository inquiries,
                                  ChannelRepository channels) {
        this.workItems = workItems;
        this.checks = checks;
        this.sellerChecks = sellerChecks;
        this.inquiries = inquiries;
        this.channels = channels;
    }

    /** What each registered answer told the customer, for {@link #TOLD_BEHIND}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAnswers(org.springframework.beans.factory.ObjectProvider<
            com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService> answers) {
        this.answers = answers;
    }

    /**
     * The one rule, on the facts alone. {@code check} is the current record, or null.
     */
    static Optional<Open> classify(InquiryWorkItemPhase phase, InquirySellerCheck check, LocalDate today) {
        boolean registering = REGISTERING.contains(phase);
        if (!registering && phase != InquiryWorkItemPhase.COMPLETED) {
            return Optional.empty();
        }
        SellerCheckOutcome outcome = check == null ? null : check.outcomeValue();
        // No day at all (a record from before V176) is a check now, never a wait with nothing to bring it back.
        boolean due = outcome == SellerCheckOutcome.IN_PROGRESS
                && (check.getNextCheckOn() == null || !check.getNextCheckOn().isAfter(today));
        if (registering) {
            return Optional.of(new Open(REGISTRATION, due));
        }
        if (outcome == SellerCheckOutcome.IN_PROGRESS) {
            return Optional.of(new Open(ACTION, due));
        }
        if (outcome == SellerCheckOutcome.NEEDS_CUSTOMER_INFO) {
            return Optional.of(new Open(CUSTOMER, false));
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public FollowUps followUps(UUID orgId) {
        return followUps(orgId, sellerChecks.today());
    }

    @Transactional(readOnly = true)
    public FollowUps followUps(UUID orgId, LocalDate today) {
        // Two bounded reads: replies in registration, and records still open. A registered reply with a finished record
        // or no record is never read at all.
        Map<UUID, InquiryWorkItem> items = new LinkedHashMap<>();
        for (InquiryWorkItem w : workItems.findByOrgIdAndPhaseIn(orgId, REGISTERING)) {
            items.put(w.getId(), w);
        }
        Map<UUID, InquirySellerCheck> current = new HashMap<>();
        List<InquirySellerCheck> open = checks.findByOrgIdAndSupersededAtIsNullAndOutcomeIn(orgId, STILL_OPEN);
        for (InquirySellerCheck c : open) {
            current.put(c.getWorkItemId(), c);
        }
        List<UUID> missing = open.stream().map(InquirySellerCheck::getWorkItemId).filter(id -> !items.containsKey(id))
                .toList();
        for (InquiryWorkItem w : workItems.findAllById(missing)) {
            if (orgId.equals(w.getOrgId())) {
                items.put(w.getId(), w);
            }
        }
        // A registration's record may be finished (or absent) and so not among the open ones; read it for the row's line.
        List<UUID> unread = items.keySet().stream().filter(id -> !current.containsKey(id)).toList();
        if (!unread.isEmpty()) {
            for (InquirySellerCheck c : checks.findByWorkItemIdInAndSupersededAtIsNull(unread)) {
                if (orgId.equals(c.getOrgId())) {
                    current.put(c.getWorkItemId(), c);
                }
            }
        }

        Map<UUID, Inquiry> byInquiry = inquiries.findAllById(items.values().stream().map(InquiryWorkItem::getInquiryId)
                .toList()).stream().collect(Collectors.toMap(Inquiry::getId, Function.identity()));
        Map<UUID, String> codes = channels.findAllById(items.values().stream().map(InquiryWorkItem::getChannelId)
                .distinct().toList()).stream().collect(Collectors.toMap(Channel::getId, Channel::getCode));

        List<FollowUpRow> due = new ArrayList<>();
        List<FollowUpRow> waiting = new ArrayList<>();
        com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService told = answers == null ? null
                : answers.getIfAvailable();
        java.util.Set<UUID> behind = new java.util.HashSet<>();
        if (told != null) {
            List<com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.ToldBehind> rows = told.toldBehind(orgId);
            Map<UUID, Inquiry> asked = inquiries.findAllById(rows.stream()
                    .map(com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.ToldBehind::inquiryId).toList())
                    .stream().collect(Collectors.toMap(Inquiry::getId, Function.identity()));
            Map<UUID, InquiryWorkItem> owners = new HashMap<>();
            workItems.findAllById(rows.stream()
                    .map(com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.ToldBehind::workItemId).toList())
                    .forEach(w -> owners.put(w.getId(), w));
            Map<UUID, String> codesToo = channels.findAllById(owners.values().stream().map(InquiryWorkItem::getChannelId)
                    .distinct().toList()).stream().collect(Collectors.toMap(Channel::getId, Channel::getCode));
            for (com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.ToldBehind t : rows) {
                Inquiry q = asked.get(t.inquiryId());
                InquiryWorkItem w = owners.get(t.workItemId());
                if (q == null || w == null || q.getDataOrigin() != com.sellerops.common.DataOrigin.REAL) {
                    continue;
                }
                behind.add(w.getId());
                String subject = q.getTitle() == null || q.getTitle().isBlank() ? null : q.getTitle();
                due.add(new FollowUpRow(w.getId(), w.getInquiryId(), subject, codesToo.get(w.getChannelId()),
                        TOLD_BEHIND, true, SellerCheckOutcome.DONE.name(), SellerCheckOutcome.DONE.labelKo(), t.note(),
                        null, w.getPhase().name(), t.recordedAt(), "/inquiries/" + w.getInquiryId(), t.toldOutcome(),
                        t.toldOutcomeKo(), t.checkId()));
            }
        }
        for (InquiryWorkItem w : items.values()) {
            if (behind.contains(w.getId())) {
                continue;
            }
            InquirySellerCheck c = current.get(w.getId());
            Optional<Open> state = classify(w.getPhase(), c, today);
            if (state.isEmpty()) {
                continue;
            }
            Inquiry q = byInquiry.get(w.getInquiryId());
            // The queue's own rule: only the seller's real inquiries are work, never a row the product made itself.
            if (q == null || q.getDataOrigin() != com.sellerops.common.DataOrigin.REAL) {
                continue;
            }
            String subject = q == null || q.getTitle() == null || q.getTitle().isBlank() ? null : q.getTitle();
            Instant since = state.get().waitingOn().equals(REGISTRATION) || c == null ? w.getUpdatedAt()
                    : c.getRecordedAt();
            FollowUpRow row = new FollowUpRow(w.getId(), w.getInquiryId(), subject, codes.get(w.getChannelId()),
                    state.get().waitingOn(), state.get().due(), c == null ? null : c.getOutcome(),
                    c == null ? null : c.outcomeValue().labelKo(), c == null ? null : c.getNote(),
                    c == null ? null : c.getNextCheckOn(), w.getPhase().name(), since, "/inquiries/" + w.getInquiryId());
            (row.due() ? due : waiting).add(row);
        }
        due.sort(Comparator.comparing(FollowUpRow::nextCheckOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FollowUpRow::since, Comparator.nullsLast(Comparator.naturalOrder())));
        waiting.sort(Comparator.comparingInt((FollowUpRow r) -> List.of(REGISTRATION, ACTION, CUSTOMER).indexOf(r.waitingOn()))
                .thenComparing(FollowUpRow::nextCheckOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FollowUpRow::since, Comparator.nullsLast(Comparator.naturalOrder())));
        return new FollowUps(due.stream().limit(MAX_ROWS).toList(), due.size(),
                waiting.stream().limit(MAX_ROWS).toList(), waiting.size(), today);
    }
}
