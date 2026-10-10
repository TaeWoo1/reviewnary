package com.sellerops.review.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.OperatorOutcome;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyOutcome;
import com.sellerops.attention.reply.ReviewReplyOutcomeRepository;
import com.sellerops.attention.reply.VerificationState;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.DataOrigin;
import com.sellerops.inquiry.draft.DraftAuthorKind;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.memory.ReviewAnswerMemoryHook;
import com.sellerops.review.publish.ReviewDeliveryTruthReader.ReviewDeliveryTruth;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Review Delivery Truth Spine v1 — the merge, its three questions, and what Answer Memory is allowed to learn
 * from it.
 *
 * <p>Repositories are mocked because what is under test is the JUDGEMENT, not a query: which of this lane's nine
 * execution states mean the seller acted, which single one means the channel was proven to hold the approved
 * text, and that the guided lane's ceiling keeps an unverifiable sentence out of the company's memory.
 */
class ReviewDeliveryTruthSpineTest {

    private static final String FP = "a".repeat(64);

    private final UUID org = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private ReviewReplyExecutionRepository executions;
    private ReviewReplyOutcomeRepository outcomes;
    private ReviewDeliveryTruthReader reader;

    @BeforeEach
    void setUp() {
        executions = mock(ReviewReplyExecutionRepository.class);
        outcomes = mock(ReviewReplyOutcomeRepository.class);
        reader = new ReviewDeliveryTruthReader(executions, outcomes);
        when(executions.findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(outcomes.findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
    }

    /* ───────────────────────────── the merge ───────────────────────────── */

    @Test
    @DisplayName("neither half recorded anything → empty, not a delivery state nobody wrote")
    void emptyWhenNothingRecorded() {
        assertThat(reader.observe(org, reviewId)).isEmpty();
    }

    @Test
    @DisplayName("an API execution alone is the whole truth — the shape Cafe24 produces")
    void executionOnly() {
        givenExecution(ReviewExecutionLane.API, ReviewExecutionStatus.POSTED,
                ReviewExecutionVerification.VERIFIED, null, 2);

        ReviewDeliveryTruth truth = reader.observe(org, reviewId).orElseThrow();

        assertThat(truth.lane()).isEqualTo("API");
        assertThat(truth.status()).isEqualTo("POSTED");
        assertThat(truth.verification()).isEqualTo("VERIFIED");
        assertThat(truth.operatorOutcome()).isNull();
        assertThat(truth.approvedVersion()).isEqualTo(2);
        assertThat(truth.sellerActed()).isTrue();
        assertThat(truth.verifiedDelivery()).isTrue();
    }

    @Test
    @DisplayName("an operator report alone is the whole truth — every guided submission recorded before V84")
    void outcomeOnly() {
        givenOutcome(OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, 1);

        ReviewDeliveryTruth truth = reader.observe(org, reviewId).orElseThrow();

        assertThat(truth.lane()).isNull();
        assertThat(truth.status()).isNull();
        assertThat(truth.operatorOutcome()).isEqualTo("OPERATOR_REPORTED_SUBMITTED");
        assertThat(truth.approvedVersion()).isEqualTo(1);
        assertThat(truth.sellerActed()).isTrue();
        // The operator's word is trusted and recorded; it is not a read-back.
        assertThat(truth.verifiedDelivery()).isFalse();
    }

    @Test
    @DisplayName("both halves merge, and the quoted tokens carry each one's own vocabulary")
    void bothHalves() {
        givenExecution(ReviewExecutionLane.GUIDED, ReviewExecutionStatus.SELLER_SUBMISSION_OBSERVED,
                ReviewExecutionVerification.SELLER_SUBMISSION_OBSERVED, null, 3);
        givenOutcome(OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, 3);

        ReviewDeliveryTruth truth = reader.observe(org, reviewId).orElseThrow();

        assertThat(truth.quoted())
                .isEqualTo(";lane=GUIDED;status=SELLER_SUBMISSION_OBSERVED"
                        + ";verification=SELLER_SUBMISSION_OBSERVED;operatorOutcome=OPERATOR_REPORTED_SUBMITTED");
    }

    /* ─────────────────── what counts as the seller having acted ─────────────────── */

    @Test
    @DisplayName("a refused execution is not an action: nothing left for the channel")
    void refusedIsNotAnAction() {
        givenExecution(ReviewExecutionLane.API, ReviewExecutionStatus.REFUSED, null,
                ReviewExecutionReason.EXECUTION_DISABLED, 1);

        ReviewDeliveryTruth truth = reader.observe(org, reviewId).orElseThrow();

        assertThat(truth.sellerActed()).isFalse();
        assertThat(truth.verifiedDelivery()).isFalse();
        assertThat(truth.quoted()).contains(";reason=EXECUTION_DISABLED");
    }

    @Test
    @DisplayName("a filled composer is not an action: only the seller can press submit")
    void composerFilledIsNotAnAction() {
        givenExecution(ReviewExecutionLane.GUIDED, ReviewExecutionStatus.COMPOSER_FILLED,
                ReviewExecutionVerification.COMPOSER_FILLED, null, 1);

        assertThat(reader.observe(org, reviewId).orElseThrow().sellerActed()).isFalse();
    }

    @Test
    @DisplayName("an aborted submission is not an action: the review stays fully in the worklist")
    void abortedIsNotAnAction() {
        givenOutcome(OperatorOutcome.SUBMISSION_ABORTED, 1);

        assertThat(reader.observe(org, reviewId).orElseThrow().sellerActed()).isFalse();
    }

    @Test
    @DisplayName("DELIVERY_UNKNOWN is an action — it may have arrived, and there was no second attempt")
    void deliveryUnknownIsAnAction() {
        givenExecution(ReviewExecutionLane.API, ReviewExecutionStatus.DELIVERY_UNKNOWN,
                ReviewExecutionVerification.DELIVERY_UNKNOWN, null, 1);

        ReviewDeliveryTruth truth = reader.observe(org, reviewId).orElseThrow();

        assertThat(truth.sellerActed()).isTrue();
        assertThat(truth.verifiedDelivery()).isFalse();
    }

    @Test
    @DisplayName("VERIFIED is the only state that claims the channel holds the approved text")
    void onlyVerifiedProvesContent() {
        for (ReviewExecutionVerification value : ReviewExecutionVerification.values()) {
            ReviewDeliveryTruth truth = new ReviewDeliveryTruth(reviewId, 1, "API", "POSTED", value.name(),
                    null, null, Instant.now());
            assertThat(truth.verifiedDelivery())
                    .as("verifiedDelivery for %s", value)
                    .isEqualTo(value == ReviewExecutionVerification.VERIFIED);
        }
    }

    /* ───────────────────────────── the batch ───────────────────────────── */

    @Test
    @DisplayName("the batch unions both halves, and leaves out the rows that sent nothing")
    void deliveredVersionsUnionsBothHalves() {
        UUID other = UUID.randomUUID();
        UUID refusedOnly = UUID.randomUUID();
        when(outcomes.findReportedSubmissionVersions(eq(org), any()))
                .thenReturn(List.<Object[]>of(new Object[] {other, 4}));
        when(executions.findAllByOrgIdAndReviewIdIn(eq(org), any())).thenReturn(List.of(
                execution(reviewId, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED,
                        ReviewExecutionVerification.VERIFIED, null, 2),
                execution(refusedOnly, ReviewExecutionLane.API, ReviewExecutionStatus.REFUSED, null,
                        ReviewExecutionReason.EXECUTION_DISABLED, 1)));

        assertThat(reader.deliveredVersions(org, List.of(reviewId, other, refusedOnly)))
                .containsExactlyInAnyOrder(reviewId + ":2", other + ":4");
    }

    @Test
    @DisplayName("no reviews, no queries")
    void deliveredVersionsShortCircuits() {
        assertThat(reader.deliveredVersions(org, List.of())).isEmpty();
        verify(executions, never()).findAllByOrgIdAndReviewIdIn(any(), any());
    }

    /* ─────────────────── what Answer Memory may learn from it ─────────────────── */

    @Test
    @DisplayName("an approval is remembered as USER_APPROVED, with the review as its origin")
    void approvalBecomesUserApproved() {
        AnswerMemoryService memory = mock(AnswerMemoryService.class);
        ReviewAnswerMemoryHook hook = hook(memory);

        hook.rememberApproved(review(), draft(3, DraftAuthorKind.MODEL), actor);

        AnswerMemoryService.RememberCommand command = captured(memory);
        assertThat(command.strength()).isEqualTo(AnswerMemoryStrength.USER_APPROVED);
        assertThat(command.originReviewId()).isEqualTo(reviewId);
        assertThat(command.originRef()).isEqualTo("review-approved:" + reviewId + ":3");
        // Not an inquiry answer, and nothing may make it look like one.
        assertThat(command.originInquiryId()).isNull();
        assertThat(command.originWorkItemId()).isNull();
        assertThat(command.originDraftVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("a verified delivery is remembered as EXECUTOR_SENT_VERIFIED")
    void verifiedBecomesExecutorSentVerified() {
        AnswerMemoryService memory = mock(AnswerMemoryService.class);
        ReviewAnswerMemoryHook hook = hook(memory);

        hook.rememberVerified(review(), draft(3, DraftAuthorKind.SELLER), actor);

        AnswerMemoryService.RememberCommand command = captured(memory);
        assertThat(command.strength()).isEqualTo(AnswerMemoryStrength.EXECUTOR_SENT_VERIFIED);
        assertThat(command.originRef()).isEqualTo("review-verified:" + reviewId + ":3");
    }

    @Test
    @DisplayName("a RULE draft is the org's own template — approving it decides this review and teaches nothing")
    void templateIsNotRemembered() {
        AnswerMemoryService memory = mock(AnswerMemoryService.class);
        ReviewAnswerMemoryHook hook = hook(memory);

        hook.rememberApproved(review(), draft(1, DraftAuthorKind.RULE), actor);

        verify(memory, never()).remember(any());
    }

    @Test
    @DisplayName("the customer's sentence is read for the signature and never stored")
    void customerTextIsNotStored() {
        AnswerMemoryService memory = mock(AnswerMemoryService.class);
        ReviewAnswerMemoryHook hook = hook(memory);

        hook.rememberApproved(review(), draft(1, DraftAuthorKind.MODEL), actor);

        AnswerMemoryService.RememberCommand command = captured(memory);
        // `question` is the one field the service reads and drops; the stored body is the seller's reply alone.
        assertThat(command.answerBody()).isEqualTo("승인된 답글입니다.");
        assertThat(command.answerBody()).doesNotContain("배송이 늦었어요");
    }

    /* ───────────────────────────── fixtures ───────────────────────────── */

    private ReviewAnswerMemoryHook hook(AnswerMemoryService memory) {
        ChannelRepository channels = mock(ChannelRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        when(channels.findById(any())).thenReturn(Optional.empty());
        when(products.findById(any())).thenReturn(Optional.empty());
        return new ReviewAnswerMemoryHook(memory, channels, products);
    }

    private static AnswerMemoryService.RememberCommand captured(AnswerMemoryService memory) {
        ArgumentCaptor<AnswerMemoryService.RememberCommand> captor =
                ArgumentCaptor.forClass(AnswerMemoryService.RememberCommand.class);
        verify(memory).remember(captor.capture());
        return captor.getValue();
    }

    private Review review() {
        Review review = new Review();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setOrgId(org);
        review.setRating(2);
        review.setBody("배송이 늦었어요");
        review.setDataOrigin(DataOrigin.REAL);
        return review;
    }

    private ReviewReplyDraft draft(int version, DraftAuthorKind authorKind) {
        ReviewReplyDraft draft = new ReviewReplyDraft();
        draft.setOrgId(org);
        draft.setReviewId(reviewId);
        draft.setVersion(version);
        draft.setBody("승인된 답글입니다.");
        draft.setContentFingerprint(FP);
        draft.setAuthorKind(authorKind.name());
        return draft;
    }

    private void givenExecution(ReviewExecutionLane lane, ReviewExecutionStatus status,
                                ReviewExecutionVerification verification, ReviewExecutionReason reason,
                                int version) {
        when(executions.findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(org, reviewId))
                .thenReturn(Optional.of(execution(reviewId, lane, status, verification, reason, version)));
    }

    private ReviewReplyExecution execution(UUID review, ReviewExecutionLane lane, ReviewExecutionStatus status,
                                           ReviewExecutionVerification verification,
                                           ReviewExecutionReason reason, int version) {
        ReviewReplyExecution row = new ReviewReplyExecution();
        row.setOrgId(org);
        row.setReviewId(review);
        row.setSellerAccountId(UUID.randomUUID());
        row.setChannelCode("CAFE24");
        row.setLane(lane);
        row.setStatus(status);
        row.setVerification(verification);
        row.setReason(reason);
        row.setApprovedVersion(version);
        row.setApprovedFingerprint(FP);
        row.setCommandId("cmd-" + UUID.randomUUID());
        row.setRecordedBy("SELLER:" + actor);
        row.setCreatedAt(Instant.now());
        return row;
    }

    private void givenOutcome(OperatorOutcome operatorOutcome, int version) {
        ReviewReplyOutcome row = new ReviewReplyOutcome();
        row.setOrgId(org);
        row.setReviewId(reviewId);
        row.setSubmissionRef("0123456789abcdef");
        row.setRecordedVersion(version);
        row.setRecordedFingerprint(FP);
        row.setFingerprintAlgorithm("sha256");
        row.setOperatorOutcome(operatorOutcome);
        row.setVerification(VerificationState.UNVERIFIED);
        row.setCommandId("cmd-" + UUID.randomUUID());
        row.setRecordedBy("SELLER:" + actor);
        row.setCreatedAt(Instant.now());
        when(outcomes.findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(org, reviewId)).thenReturn(Optional.of(row));
    }
}
