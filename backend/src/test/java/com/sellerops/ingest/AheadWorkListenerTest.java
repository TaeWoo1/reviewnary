package com.sellerops.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryOperationalState;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.operationscase.investigation.CaseDraftPreparer;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.response.ReviewDraftPreparer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * <b>The sync starts the AI judgement and the drafts written ahead</b> (owner, 2026-10-28: 「리뷰 연동할 때, api가 문의
 * 연동할 때마다를 기점으로」) — reviews through the review preparer, inquiries through the production draft path, and
 * only the inquiries the automatic check would work on.
 */
class AheadWorkListenerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private ReviewRepository reviews;
    private ReviewDraftPreparer reviewDrafts;
    private InquiryRepository inquiries;
    private InquiryWorkItemRepository workItems;
    private InquiryReplyDraftRepository inquiryDrafts;
    private CaseDraftPreparer inquiryPreparer;
    private AgentQuotaService quota;
    private AheadWorkListener listener;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewRepository.class);
        reviewDrafts = mock(ReviewDraftPreparer.class);
        inquiries = mock(InquiryRepository.class);
        workItems = mock(InquiryWorkItemRepository.class);
        inquiryDrafts = mock(InquiryReplyDraftRepository.class);
        inquiryPreparer = mock(CaseDraftPreparer.class);
        quota = mock(AgentQuotaService.class);
        listener = new AheadWorkListener(reviews, reviewDrafts, inquiries, workItems, inquiryDrafts, inquiryPreparer,
                quota, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        when(inquiryPreparer.enabledFor(ORG)).thenReturn(true);
        when(quota.status(ORG)).thenReturn(new AgentQuotaService.AgentQuotaStatus(true, true,
                LocalDate.of(2026, 10, 28), 0, 0, 0, 100));
        when(inquiryPreparer.prepare(eq(ORG), any())).thenReturn(new CaseDraftPreparer.Prepared(true, 1, "GROUNDED", 1, null));
    }

    @Test
    @DisplayName("a review sync hands this org's new reviews, and only them, to the review preparer")
    @SuppressWarnings("unchecked")
    void aReviewSyncHandsItsReviewsToThePreparer() {
        Review mine = review(ORG);
        Review other = review(UUID.randomUUID());
        when(reviews.findAllById(any())).thenReturn(List.of(mine, other));

        listener.onNewWork(new NewWorkIngestedEvent(ORG, NewWorkIngestedEvent.Kind.REVIEW, List.of(mine.getId(), other.getId())));

        ArgumentCaptor<Collection<Review>> passed = ArgumentCaptor.forClass(Collection.class);
        verify(reviewDrafts).prepareAll(eq(ORG), passed.capture(), eq(NOW), any(), eq("INGEST"));
        assertThat(passed.getValue()).containsExactly(mine);
    }

    @Test
    @DisplayName("an inquiry sync drafts the unanswered, active, recent inquiries that have no draft yet")
    void anInquirySyncDraftsOnlyWhatTheCheckWouldWorkOn() {
        Inquiry fresh = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, null, NOW.minusSeconds(3600));
        Inquiry answered = inquiry("ANSWERED", InquiryOperationalState.ACTIVE, null, NOW);
        Inquiry threadCopy = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, "REPLY", NOW);
        Inquiry old = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, null,
                NOW.minus(ReviewDraftPreparer.REACH).minusSeconds(1));
        for (Inquiry i : List.of(fresh, answered, threadCopy, old)) {
            workItem(i, InquiryWorkItemPhase.OPEN, 0);
        }

        assertThat(listener.draftInquiryAhead(ORG, fresh, NOW)).isTrue();
        assertThat(listener.draftInquiryAhead(ORG, answered, NOW)).isFalse();
        assertThat(listener.draftInquiryAhead(ORG, threadCopy, NOW)).isFalse();
        assertThat(listener.draftInquiryAhead(ORG, old, NOW)).isFalse();
    }

    @Test
    @DisplayName("an inquiry that already has a draft, or has moved on, or with half the budget spent, is not drafted")
    void anInquiryWithADraftOrNoBudgetIsNotDrafted() {
        Inquiry drafted = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, null, NOW);
        workItem(drafted, InquiryWorkItemPhase.OPEN, 1);
        Inquiry proposed = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, null, NOW);
        workItem(proposed, InquiryWorkItemPhase.PROPOSED, 0);
        assertThat(listener.draftInquiryAhead(ORG, drafted, NOW)).isFalse();
        assertThat(listener.draftInquiryAhead(ORG, proposed, NOW)).isFalse();

        Inquiry fresh = inquiry("UNANSWERED", InquiryOperationalState.ACTIVE, null, NOW);
        workItem(fresh, InquiryWorkItemPhase.OPEN, 0);
        when(quota.status(ORG)).thenReturn(new AgentQuotaService.AgentQuotaStatus(true, true,
                LocalDate.of(2026, 10, 28), 0, 0, 49, 100));
        assertThat(listener.draftInquiryAhead(ORG, fresh, NOW)).isFalse();
        verify(inquiryPreparer, never()).prepare(any(), any());
    }

    @Test
    @DisplayName("a failure after the sync never reaches the sync")
    void aFailureIsSwallowed() {
        when(reviews.findAllById(any())).thenThrow(new IllegalStateException("db down"));
        listener.onNewWork(new NewWorkIngestedEvent(ORG, NewWorkIngestedEvent.Kind.REVIEW, List.of(UUID.randomUUID())));
        verify(reviewDrafts, never()).prepareAll(any(), anyCollection(), any(), any(), any());
    }

    private static Review review(UUID org) {
        Review r = new Review();
        r.setId(UUID.randomUUID());
        r.setOrgId(org);
        r.setRating(1);
        r.setReceivedAt(NOW);
        return r;
    }

    private static Inquiry inquiry(String status, InquiryOperationalState state, String threadRole, Instant receivedAt) {
        Inquiry i = new Inquiry();
        i.setId(UUID.randomUUID());
        i.setOrgId(ORG);
        i.setStatus(status);
        i.setOperationalState(state);
        i.setThreadRole(threadRole);
        i.setReceivedAt(receivedAt);
        return i;
    }

    private void workItem(Inquiry inquiry, InquiryWorkItemPhase phase, long drafts) {
        InquiryWorkItem w = new InquiryWorkItem();
        w.setId(UUID.randomUUID());
        w.setOrgId(ORG);
        w.setPhase(phase);
        when(workItems.findByInquiryId(inquiry.getId())).thenReturn(Optional.of(w));
        when(inquiryDrafts.countByWorkItemId(w.getId())).thenReturn(drafts);
    }
}
