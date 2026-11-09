package com.sellerops.ingest;

import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.operationscase.OperationsCaseRules;
import com.sellerops.operationscase.investigation.CaseDraftPreparer;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.response.ReviewDraftPreparer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * <b>The AI judgement and the drafts written ahead, started by the sync itself</b> (owner, 2026-10-28: 「리뷰 연동할 때,
 * api가 문의 연동할 때마다를 기점으로」).
 *
 * <p>Reviews go to {@link ReviewDraftPreparer#prepareAll}, which has the AI judge read each one and drafts in the
 * seller's 리뷰 응대 기준 order. Inquiries are drafted through {@link CaseDraftPreparer}, the production proposal and
 * draft path the automatic check uses, for exactly the inquiries that check would work on
 * ({@link OperationsCaseRules#forInquiry}: unanswered, active, not a thread copy), within the same 15-day reach, with
 * no draft yet, inside half the day's AI budget.
 *
 * <p><b>After the commit, and off the sync's thread.</b> A file upload of a year of reviews must not wait for a model:
 * the event is handled after the ingest commits, on one background thread, and every step is best-effort — a sync is
 * never failed or slowed by what follows it. A sync of old rows costs nothing here: the reach drops them before
 * anything is asked. At most {@link #MAX_PER_SYNC} of each kind are taken from one sync; the automatic check, when it
 * runs, reaches the rest.
 */
@Component
public class AheadWorkListener {

    /** How many rows of one kind one sync may start work on. */
    static final int MAX_PER_SYNC = 50;

    private static final Logger log = LoggerFactory.getLogger(AheadWorkListener.class);

    private final ReviewRepository reviews;
    private final ReviewDraftPreparer reviewDrafts;
    private final InquiryRepository inquiries;
    private final InquiryWorkItemRepository workItems;
    private final InquiryReplyDraftRepository inquiryDrafts;
    private final CaseDraftPreparer inquiryPreparer;
    private final AgentQuotaService quota;
    private final Clock clock;
    private final Executor executor;

    @Autowired
    public AheadWorkListener(ReviewRepository reviews, ReviewDraftPreparer reviewDrafts, InquiryRepository inquiries,
                             InquiryWorkItemRepository workItems, InquiryReplyDraftRepository inquiryDrafts,
                             CaseDraftPreparer inquiryPreparer, AgentQuotaService quota) {
        this(reviews, reviewDrafts, inquiries, workItems, inquiryDrafts, inquiryPreparer, quota, Clock.systemUTC(),
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ahead-work");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** Test seam: a clock and an executor (a direct one runs the work on the caller's thread). */
    AheadWorkListener(ReviewRepository reviews, ReviewDraftPreparer reviewDrafts, InquiryRepository inquiries,
                      InquiryWorkItemRepository workItems, InquiryReplyDraftRepository inquiryDrafts,
                      CaseDraftPreparer inquiryPreparer, AgentQuotaService quota, Clock clock, Executor executor) {
        this.reviews = reviews;
        this.reviewDrafts = reviewDrafts;
        this.inquiries = inquiries;
        this.workItems = workItems;
        this.inquiryDrafts = inquiryDrafts;
        this.inquiryPreparer = inquiryPreparer;
        this.quota = quota;
        this.clock = clock;
        this.executor = executor;
    }

    private com.sellerops.review.judgement.ReviewJudgeService judges;

    @Autowired(required = false)
    public void setJudges(com.sellerops.review.judgement.ReviewJudgeService judges) {
        this.judges = judges;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNewWork(NewWorkIngestedEvent event) {
        try {
            executor.execute(() -> handle(event));
        } catch (RuntimeException e) {
            log.warn("ahead work: 예약 실패 org={} kind={}: {}", event.orgId(), event.kind(), e.toString());
        }
    }

    void handle(NewWorkIngestedEvent event) {
        try {
            Instant now = clock.instant();
            List<java.util.UUID> ids = event.ids().stream().limit(MAX_PER_SYNC).toList();
            if (event.kind() == NewWorkIngestedEvent.Kind.REVIEW) {
                List<Review> rows = reviews.findAllById(ids).stream()
                        .filter(r -> event.orgId().equals(r.getOrgId())).toList();
                // Every new review in reach is judged first, whatever its channel, rating or the seller's rule — the
                // analysis is not the drafting (§3-4). Drafting ahead then reads those judgements.
                List<Review> inReach = rows.stream().filter(r -> r.getReceivedAt() != null
                        && !r.getReceivedAt().isBefore(now.minus(ReviewDraftPreparer.REACH))).toList();
                int judged = judges == null ? 0 : judges.judgeAll(event.orgId(), inReach, "INGEST");
                int written = reviewDrafts.prepareAll(event.orgId(), rows, now, () -> false, "INGEST");
                log.info("ahead work: 리뷰 org={} 받은={} 판단={} 미리쓴초안={}", event.orgId(), rows.size(), judged, written);
            } else {
                int written = 0;
                for (Inquiry inquiry : inquiries.findAllById(ids)) {
                    if (draftInquiryAhead(event.orgId(), inquiry, now)) {
                        written++;
                    }
                }
                log.info("ahead work: 문의 org={} 받은={} 미리쓴초안={}", event.orgId(), ids.size(), written);
            }
        } catch (RuntimeException e) {
            log.warn("ahead work: 실패 org={} kind={}: {}", event.orgId(), event.kind(), e.getClass().getSimpleName());
        }
    }

    /** @return whether a draft was written */
    boolean draftInquiryAhead(java.util.UUID orgId, Inquiry inquiry, Instant now) {
        if (!orgId.equals(inquiry.getOrgId())
                || !OperationsCaseRules.forInquiry(inquiry.getStatus(), inquiry.getOperationalState(),
                        inquiry.getThreadRole()).needsInvestigation()
                || inquiry.getReceivedAt() == null || inquiry.getReceivedAt().isBefore(now.minus(ReviewDraftPreparer.REACH))) {
            return false;
        }
        InquiryWorkItem item = workItems.findByInquiryId(inquiry.getId())
                .filter(w -> orgId.equals(w.getOrgId()) && w.getPhase() == InquiryWorkItemPhase.OPEN)
                .orElse(null);
        if (item == null || inquiryDrafts.countByWorkItemId(item.getId()) > 0 || !inquiryPreparer.enabledFor(orgId)
                || !ReviewDraftPreparer.withinBudget(quota.status(orgId), 2)) {
            return false;
        }
        return inquiryPreparer.prepare(orgId, item.getId()).written();
    }
}
