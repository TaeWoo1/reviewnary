package com.sellerops.operations.board;

import com.sellerops.attention.reply.ReviewReplyApproval;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.attention.reply.ReviewReplyOutcome;
import com.sellerops.attention.reply.ReviewReplyWorkDismissal;
import com.sellerops.auth.runtoken.ReplyAgentRun;
import com.sellerops.common.ApiException;
import com.sellerops.common.DeliveryMode;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.publish.InquiryApproval;
import com.sellerops.inquiry.publish.InquiryExecution;
import com.sellerops.inquiry.publish.InquiryExecutionStatus;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.review.Review;
import com.sellerops.review.publish.ReviewExecutionLane;
import com.sellerops.review.publish.ReviewExecutionStatus;
import com.sellerops.review.publish.ReviewExecutionVerification;
import com.sellerops.review.publish.ReviewReplyExecution;
import com.sellerops.sync.SyncJob;
import com.sellerops.common.VocPreviewSanitizer;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>최근 활동 — only what a record says happened</b> (2026-11-09, demo handoff §7.12 item 4).
 *
 * <p>Each line is one row of an existing ledger, at the confirmation level that ledger holds: an approval is not a
 * registration, a channel that took the request is not a registration confirmed, a seller's report that they posted
 * in the seller center is a report, and a dismissal is not an answer. Nothing is counted together and no line is
 * promoted past what its row proves. A review reply on NAVER has no channel read-back, so no line here ever says
 * one was registered — only the Cafe24 API lane's own read-back can.
 *
 * <p><b>Mock, real or unknown is the row's own fact</b> ({@link DeliveryMode}, V187): a registration line carries the
 * route its execution row recorded when it was created. Today's mock list is never consulted, so a row from before
 * V187 stays {@code UNKNOWN}.
 *
 * <p>Titles are the masked previews the Home's other rows use ({@link VocPreviewSanitizer}); no body leaves.
 */
@Service
public class OperationsActivityService {

    static final int MAX_LIMIT = 200;
    static final Duration MAX_SPAN = Duration.ofDays(7);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record Subject(String kind, UUID id, String title, String to) {
    }

    /**
     * One event. {@code result} is the level the ledger proves; {@code deliveryMode} is set only on lines about a
     * registration through an execution row, and is that row's recorded route.
     */
    public record Activity(Instant at, String kind, Subject subject, String result, String reason,
                           LocalDate nextCheckOn, Integer count, String channelCode, DeliveryMode deliveryMode) {
    }

    public record ActivityPage(Instant asOf, Instant since, List<Activity> rows, boolean truncated) {
    }

    private final EntityManager em;
    private Clock clock = Clock.systemUTC();

    public OperationsActivityService(EntityManager em) {
        this.em = em;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActivityPage read(UUID orgId, String sinceParam, int limit) {
        Instant now = clock.instant();
        Instant since = sinceOf(sinceParam, now);
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Activity> all = new ArrayList<>();

        // ---- inquiries: approval, registration, the seller's own check ----
        List<InquiryApproval> approvals = query("select a from InquiryApproval a where a.orgId = :o and "
                + "a.createdAt >= :s order by a.createdAt desc", InquiryApproval.class, orgId, since, cap);
        List<InquiryExecution> executions = query("select e from InquiryExecution e where e.orgId = :o and "
                + "e.updatedAt >= :s and e.status <> :pending order by e.updatedAt desc", InquiryExecution.class,
                orgId, since, cap, Map.of("pending", InquiryExecutionStatus.ACTION_PENDING));
        List<InquirySellerCheck> checks = query("select c from InquirySellerCheck c where c.orgId = :o and "
                + "c.recordedAt >= :s order by c.recordedAt desc", InquirySellerCheck.class, orgId, since, cap);

        Map<UUID, InquiryExecution> latestExecution = new HashMap<>();
        if (!approvals.isEmpty()) {
            for (InquiryExecution e : em.createQuery("select e from InquiryExecution e where e.orgId = :o and "
                            + "e.workItemId in :w order by e.createdAt asc", InquiryExecution.class)
                    .setParameter("o", orgId)
                    .setParameter("w", approvals.stream().map(InquiryApproval::getWorkItemId).distinct().toList())
                    .getResultList()) {
                latestExecution.put(e.getWorkItemId(), e);
            }
        }
        Map<UUID, Subject> inquirySubjects = inquirySubjects(orgId, union(
                approvals.stream().map(InquiryApproval::getWorkItemId).toList(),
                executions.stream().map(InquiryExecution::getWorkItemId).toList(),
                checks.stream().map(InquirySellerCheck::getWorkItemId).toList()));

        for (InquiryApproval a : approvals) {
            InquiryExecution e = latestExecution.get(a.getWorkItemId());
            all.add(new Activity(a.getCreatedAt(), "INQUIRY_APPROVED", inquirySubjects.get(a.getWorkItemId()),
                    "REGISTRATION_REQUESTED", null, null, null, null,
                    e == null ? DeliveryMode.UNKNOWN : e.getDeliveryMode()));
        }
        for (InquiryExecution e : executions) {
            all.add(new Activity(e.getUpdatedAt(), "INQUIRY_REGISTRATION", inquirySubjects.get(e.getWorkItemId()),
                    registrationResult(e.getStatus()), e.getFailureReason(), null, null, null, e.getDeliveryMode()));
        }
        for (InquirySellerCheck c : checks) {
            all.add(new Activity(c.getRecordedAt(), "SELLER_CHECK_RECORDED", inquirySubjects.get(c.getWorkItemId()),
                    c.getOutcome(), null, c.getNextCheckOn(), null, null, null));
        }

        // ---- reviews: the seller's 확정, their own report, the API lane's execution, the agent, a dismissal ----
        List<ReviewReplyApproval> reviewApprovals = query("select a from ReviewReplyApproval a where a.orgId = :o "
                        + "and a.decidedAt >= :s and a.state = :approved order by a.decidedAt desc",
                ReviewReplyApproval.class, orgId, since, cap, Map.of("approved", ReviewReplyApprovalState.APPROVED));
        List<ReviewReplyOutcome> reports = query("select r from ReviewReplyOutcome r where r.orgId = :o and "
                + "r.createdAt >= :s order by r.createdAt desc", ReviewReplyOutcome.class, orgId, since, cap);
        List<ReviewReplyExecution> reviewExecutions = query("select x from ReviewReplyExecution x where x.orgId = :o "
                        + "and x.createdAt >= :s and x.lane = :api order by x.createdAt desc",
                ReviewReplyExecution.class, orgId, since, cap, Map.of("api", ReviewExecutionLane.API));
        List<ReplyAgentRun> runs = query("select r from ReplyAgentRun r where r.orgId = :o and r.finishedAt >= :s "
                + "and r.subjectKind = 'REVIEW' order by r.finishedAt desc", ReplyAgentRun.class, orgId, since, cap);
        List<ReviewReplyWorkDismissal> dismissals = query("select d from ReviewReplyWorkDismissal d where "
                + "d.orgId = :o and d.dismissedAt >= :s order by d.dismissedAt desc", ReviewReplyWorkDismissal.class,
                orgId, since, cap);

        Map<UUID, Subject> reviewSubjects = reviewSubjects(orgId, union(
                reviewApprovals.stream().map(ReviewReplyApproval::getReviewId).toList(),
                reports.stream().map(ReviewReplyOutcome::getReviewId).toList(),
                reviewExecutions.stream().map(ReviewReplyExecution::getReviewId).toList(),
                runs.stream().map(ReplyAgentRun::getSubjectId).toList(),
                dismissals.stream().map(ReviewReplyWorkDismissal::getReviewId).toList()));

        for (ReviewReplyApproval a : reviewApprovals) {
            // 확정 is the seller's decision about the text. It never says the reply reached the channel.
            all.add(new Activity(a.getDecidedAt(), "REVIEW_REPLY_APPROVED", reviewSubjects.get(a.getReviewId()),
                    "APPROVED_NOT_REGISTERED", null, null, null, null, null));
        }
        for (ReviewReplyOutcome r : reports) {
            all.add(new Activity(r.getCreatedAt(), "REVIEW_REPLY_REPORTED", reviewSubjects.get(r.getReviewId()),
                    r.getOperatorOutcome() == null ? null : r.getOperatorOutcome().name(),
                    r.getVerification() == null ? null : r.getVerification().name(), null, null, null, null));
        }
        for (ReviewReplyExecution x : reviewExecutions) {
            all.add(new Activity(x.getCreatedAt(), "REVIEW_REPLY_EXECUTED", reviewSubjects.get(x.getReviewId()),
                    reviewExecutionResult(x.getStatus(), x.getVerification()),
                    x.getReason() == null ? null : x.getReason().name(), null, null, x.getChannelCode(),
                    x.getDeliveryMode()));
        }
        for (ReplyAgentRun r : runs) {
            all.add(new Activity(r.getFinishedAt(), "AGENT_RUN_FINISHED", reviewSubjects.get(r.getSubjectId()),
                    r.getOutcome(), "HANDOFF".equals(r.getOutcome()) ? r.getHandoffReason() : null, null, null, null,
                    null));
        }
        for (ReviewReplyWorkDismissal d : dismissals) {
            all.add(new Activity(d.getDismissedAt(), "REVIEW_DISMISSED", reviewSubjects.get(d.getReviewId()),
                    "NO_REPLY_NEEDED", null, null, null, null, null));
        }

        // ---- collection: what was read in, never a claim it is current ----
        List<SyncJob> jobs = query("select j from SyncJob j where j.orgId = :o and j.finishedAt >= :s "
                + "order by j.finishedAt desc", SyncJob.class, orgId, since, cap);
        for (SyncJob j : jobs) {
            all.add(new Activity(j.getFinishedAt(), "COLLECTED", new Subject("SOURCE", j.getSellerAccountId(),
                    null, null), j.getStatus(), j.getDataType(), null,
                    j.getInsertedRows() != null ? j.getInsertedRows() : j.getSuccessRows(), null, null));
        }

        List<Activity> ordered = all.stream().filter(a -> a.at() != null)
                .sorted(Comparator.comparing(Activity::at).reversed()
                        .thenComparing(Activity::kind)
                        .thenComparing(a -> a.subject() == null || a.subject().id() == null ? ""
                                : a.subject().id().toString()))
                .toList();
        boolean truncated = ordered.size() > cap;
        return new ActivityPage(now, since, ordered.stream().limit(cap).toList(), truncated);
    }

    static String registrationResult(InquiryExecutionStatus status) {
        return switch (status) {
            case COMPLETED -> "REGISTERED_CONFIRMED";          // the channel read-back found our answer
            case EXECUTED -> "REQUEST_ACCEPTED_UNCONFIRMED";   // the channel took the request; not yet seen there
            case DELIVERY_UNKNOWN -> "DELIVERY_UNKNOWN";       // may or may not have arrived — never re-sent
            case DISPATCHING -> "SENDING";
            case FAILED -> "FAILED";
            case ACTION_PENDING -> "WAITING_TO_SEND";
        };
    }

    static String reviewExecutionResult(ReviewExecutionStatus status, ReviewExecutionVerification verification) {
        if (status == ReviewExecutionStatus.POSTED) {
            return verification == ReviewExecutionVerification.VERIFIED ? "REGISTERED_CONFIRMED"
                    : "REQUEST_ACCEPTED_UNCONFIRMED";
        }
        return status.name();
    }

    // ---- subjects, batch-read and masked ----

    private Map<UUID, Subject> inquirySubjects(UUID orgId, Collection<UUID> workItemIds) {
        if (workItemIds.isEmpty()) {
            return Map.of();
        }
        List<InquiryWorkItem> items = em.createQuery("select w from InquiryWorkItem w where w.orgId = :o and "
                        + "w.id in :ids", InquiryWorkItem.class).setParameter("o", orgId)
                .setParameter("ids", workItemIds).getResultList();
        Map<UUID, Inquiry> inquiries = items.isEmpty() ? Map.of()
                : em.createQuery("select i from Inquiry i where i.orgId = :o and i.id in :ids", Inquiry.class)
                        .setParameter("o", orgId)
                        .setParameter("ids", items.stream().map(InquiryWorkItem::getInquiryId).toList())
                        .getResultList().stream().collect(Collectors.toMap(Inquiry::getId, Function.identity()));
        Map<UUID, Subject> out = new HashMap<>();
        for (InquiryWorkItem w : items) {
            Inquiry q = inquiries.get(w.getInquiryId());
            String title = q == null ? null : masked(q.getTitle() == null || q.getTitle().isBlank() ? q.getBody()
                    : q.getTitle());
            out.put(w.getId(), new Subject("INQUIRY", w.getInquiryId(), title, "/inquiries/" + w.getInquiryId()));
        }
        return out;
    }

    private Map<UUID, Subject> reviewSubjects(UUID orgId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select r from Review r where r.orgId = :o and r.id in :ids", Review.class)
                .setParameter("o", orgId).setParameter("ids", reviewIds).getResultList().stream()
                .collect(Collectors.toMap(Review::getId, r -> new Subject("REVIEW", r.getId(), masked(r.getBody()),
                        "/reviews/reply/" + r.getId())));
    }

    private static String masked(String raw) {
        var preview = VocPreviewSanitizer.sanitize(raw); // the sanitizer reads markup itself (VocMarkupBoundaryTest)
        return preview == null || preview.text() == null || preview.text().isBlank() ? null : preview.text();
    }

    // ---- plumbing ----

    static Instant sinceOf(String raw, Instant now) {
        Instant floor = now.minus(MAX_SPAN);
        if (raw == null || raw.isBlank() || "today".equals(raw)) {
            return now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        }
        try {
            Instant at = Instant.parse(raw.trim());
            return at.isBefore(floor) ? floor : at;
        } catch (RuntimeException unreadable) {
            throw ApiException.badRequest("since는 today 또는 ISO 시각이에요 (최대 7일 전).");
        }
    }

    private <T> List<T> query(String jpql, Class<T> type, UUID orgId, Instant since, int cap) {
        return query(jpql, type, orgId, since, cap, Map.of());
    }

    private <T> List<T> query(String jpql, Class<T> type, UUID orgId, Instant since, int cap,
                              Map<String, Object> extra) {
        var q = em.createQuery(jpql, type).setParameter("o", orgId).setParameter("s", since).setMaxResults(cap + 1);
        extra.forEach(q::setParameter);
        return q.getResultList();
    }

    @SafeVarargs
    private static List<UUID> union(List<UUID>... lists) {
        Map<UUID, Boolean> seen = new LinkedHashMap<>();
        for (List<UUID> l : lists) {
            l.stream().filter(java.util.Objects::nonNull).forEach(id -> seen.put(id, true));
        }
        return new ArrayList<>(seen.keySet());
    }
}
