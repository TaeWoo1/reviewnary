package com.sellerops.attention.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sellerops.attention.VocItemRef;
import com.sellerops.review.publish.ReviewReplySettledEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guided barrier's own half of «the reply record for this review moved»: an operator report is one of the three
 * things that mean the seller acted, so a surface deriving from the record is told the moment it is written — and
 * told nothing when a replay wrote nothing. What the report MEANS stays with
 * {@code ReviewDeliveryTruthReader}; this class only proves who is told and when.
 */
class ReviewReplyOutcomeAnnouncementTest {

    private static final String FP = "a".repeat(64);
    private static final String REF = "0123456789abcdef";

    private final UUID org = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final List<ReviewReplySettledEvent> announced = new ArrayList<>();

    private ReviewReplyOutcomeRepository outcomes;
    private ReviewReplyOutcomeWriter writer;
    private ReviewReplyOutcomeService service;

    @BeforeEach
    void setUp() {
        ReviewReplySubmissionRefRepository refs = mock(ReviewReplySubmissionRefRepository.class);
        outcomes = mock(ReviewReplyOutcomeRepository.class);
        writer = mock(ReviewReplyOutcomeWriter.class);
        service = new ReviewReplyOutcomeService(refs, outcomes, writer);
        service.setSettledEvents(event -> {
            if (event instanceof ReviewReplySettledEvent settled) {
                announced.add(settled);
            }
        });
        when(outcomes.findByOrgIdAndCommandId(eq(org), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a recorded report announces the record moved, carrying the organisation and the review and nothing else")
    void aRecordedReportIsAnnounced() {
        service.record(org, reviewId, VocItemRef.forReview(reviewId), REF, 1, FP, "sha256",
                OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, null, "cmd-1", "SELLER:qa");

        assertThat(announced).singleElement().satisfies(event -> {
            assertThat(event.orgId()).isEqualTo(org);
            assertThat(event.reviewId()).isEqualTo(reviewId);
        });
    }

    @Test
    @DisplayName("a replay wrote no row, so nothing is announced")
    void aReplayAnnouncesNothing() {
        ReviewReplyOutcome prior = new ReviewReplyOutcome();
        prior.setOrgId(org);
        prior.setReviewId(reviewId);
        prior.setSubmissionRef(REF);
        prior.setOperatorOutcome(OperatorOutcome.OPERATOR_REPORTED_SUBMITTED);
        when(outcomes.findByOrgIdAndCommandId(org, "cmd-1")).thenReturn(Optional.of(prior));

        service.record(org, reviewId, VocItemRef.forReview(reviewId), REF, 1, FP, "sha256",
                OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, null, "cmd-1", "SELLER:qa");

        assertThat(announced).isEmpty();
    }

    @Test
    @DisplayName("a listener that throws cannot fail a report about a reply the seller already posted by hand")
    void aFailingListenerCannotFailTheReport() {
        service.setSettledEvents(event -> {
            throw new IllegalStateException("a surface failed to catch up");
        });

        assertThat(service.record(org, reviewId, VocItemRef.forReview(reviewId), REF, 1, FP, "sha256",
                OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, null, "cmd-1", "SELLER:qa").recorded()).isTrue();
        assertThat(announced).isEmpty();
    }

    @Test
    @DisplayName("nothing about the append changes: the writer is still asked exactly once, with what it was given")
    void theAppendIsUnchanged() {
        service.record(org, reviewId, VocItemRef.forReview(reviewId), REF, 1, FP, "sha256",
                OperatorOutcome.SUBMISSION_ABORTED, "  ", "cmd-1", "SELLER:qa");

        verify(writer).appendOutcome(org, reviewId, REF, 1, FP, "sha256",
                OperatorOutcome.SUBMISSION_ABORTED, null, "cmd-1", "SELLER:qa");
        verifyNoMoreInteractions(writer);
        assertThat(announced).hasSize(1);
    }
}
