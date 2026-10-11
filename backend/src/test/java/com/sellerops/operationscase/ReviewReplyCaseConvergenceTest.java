package com.sellerops.operationscase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.attention.reply.ReviewReplyOutcomeRepository;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.AnswerDeliveryTruthReader;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.publish.InquiryVerificationRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.publish.ReviewDeliveryTruthReader;
import com.sellerops.review.publish.ReviewExecutionLane;
import com.sellerops.review.publish.ReviewExecutionReason;
import com.sellerops.review.publish.ReviewExecutionStatus;
import com.sellerops.review.publish.ReviewExecutionVerification;
import com.sellerops.review.publish.ReviewReplyExecution;
import com.sellerops.review.publish.ReviewReplyExecutionRepository;
import com.sellerops.review.publish.ReviewReplySettledEvent;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>「보냈다」와 「카드가 그만 묻는다」가 같은 분 안에 일어난다</b> — the review half of the convergence the
 * inquiry lane has had since its own event existed.
 *
 * <p>The derivation itself is {@link OperationsCaseReconciler}'s and is proven elsewhere; what is asserted here is
 * that the reply lane's own record moving is enough to move the card <em>now</em>, and that the three states which
 * look like progress without being it still move nothing. Real repositories, because the whole question is what
 * the rows say.
 */
@DataJpaTest
@Import(ReviewReplyCaseConvergenceTest.Wiring.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:review_case_converge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ReviewReplyCaseConvergenceTest {

    private static final String FP = "a".repeat(64);

    @Autowired OperationsCaseRepository cases;
    @Autowired OperationsCaseEventRepository events;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired ReviewRepository reviews;
    @Autowired SellerAccountRepository accounts;
    @Autowired InquiryExecutionRepository inquiryExecutions;
    @Autowired InquiryVerificationRepository verifications;
    @Autowired ReviewReplyExecutionRepository executions;
    @Autowired ReviewReplyOutcomeRepository outcomes;

    /**
     * <b>The container's own bean, not a hand-built one.</b> The properties this class asserts are its
     * annotations — {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)} and
     * {@code @Transactional(REQUIRES_NEW)} — and a listener constructed with {@code new} has none of them.
     */
    @Autowired ReviewReplyCaseConvergence convergence;
    @Autowired CountingReconciler counting;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager txManager;

    private UUID org;

    @BeforeEach
    void setUp() {
        org = UUID.randomUUID();
        counting.converges.set(0);
    }

    /** The reconciler and the listener, wired as the application wires them. */
    @TestConfiguration
    static class Wiring {

        @Bean
        CountingReconciler reconciler(OperationsCaseRepository cases, OperationsCaseEventRepository events,
                                      InquiryRepository inquiries, InquiryWorkItemRepository workItems,
                                      ReviewRepository reviews, SellerAccountRepository accounts,
                                      InquiryExecutionRepository inquiryExecutions,
                                      InquiryVerificationRepository verifications,
                                      ReviewReplyExecutionRepository executions,
                                      ReviewReplyOutcomeRepository outcomes) {
            return new CountingReconciler(cases, events, inquiries, workItems, reviews, accounts,
                    new AnswerDeliveryTruthReader(inquiryExecutions, verifications),
                    new ReviewDeliveryTruthReader(executions, outcomes));
        }

        @Bean
        ReviewReplyCaseConvergence convergence(CountingReconciler reconciler) {
            return new ReviewReplyCaseConvergence(reconciler);
        }
    }

    /**
     * The real derivation, counting how many times it was asked.
     *
     * <p>«The card did not move» can be true because nothing ran or because something ran and its write rolled
     * back with everything else. Only the first is the property: a derivation that runs before the record is
     * durable reads the state it is replacing, and whether that costs a wrong conclusion or merely a silent
     * no-op depends on propagation — which is not a thing to leave to luck.
     */
    static final class CountingReconciler extends OperationsCaseReconciler {

        final java.util.concurrent.atomic.AtomicInteger converges = new java.util.concurrent.atomic.AtomicInteger();

        CountingReconciler(OperationsCaseRepository cases, OperationsCaseEventRepository events,
                           InquiryRepository inquiries, InquiryWorkItemRepository workItems, ReviewRepository reviews,
                           SellerAccountRepository accounts, AnswerDeliveryTruthReader deliveries,
                           ReviewDeliveryTruthReader replyDeliveries) {
            super(cases, events, inquiries, workItems, reviews, accounts, deliveries, replyDeliveries,
                    Clock.systemUTC());
        }

        @Override
        public boolean converge(UUID orgId, OperationsSubjectKind kind, UUID subjectId) {
            converges.incrementAndGet();
            return super.converge(orgId, kind, subjectId);
        }
    }

    @Test
    @DisplayName("reviewnary posted the approved reply and verified it — the card stops asking at once, with the lane's own tokens quoted")
    void averifiedSendSettlesTheCardNow() {
        Review review = review();
        OperationsCase card = openCase(review);
        execution(review, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED, ReviewExecutionVerification.VERIFIED);

        convergence.onReplySettled(new ReviewReplySettledEvent(org, review.getId()));

        OperationsCase settled = cases.findById(card.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(OperationsCaseStatus.ACTED);
        assertThat(settled.getResolutionReason()).isEqualTo(CaseResolution.SELLER_ACTED);
        assertThat(settled.getActedAt()).isNotNull();
        assertThat(events.findByOrgIdAndCaseIdOrderByCreatedAtAsc(org, card.getId()))
                .filteredOn(e -> e.getKind() == CaseEventKind.SELLER_ACTED)
                .singleElement().satisfies(e -> {
                    assertThat(e.getProvenance()).contains("REVIEW_REPLY_EXECUTED");
                    assertThat(e.getProvenance()).contains("verification=VERIFIED");
                    assertThat(e.getRunId()).as("this did not happen in a run").isNull();
                });
        // The channel's own word is untouched: an execution is reviewnary's act, replyState is an observation.
        assertThat(reviews.findById(review.getId()).orElseThrow().getReplyState())
                .isEqualTo(ReviewReplyState.PENDING);
    }

    @Test
    @DisplayName("the collector saw the seller submit in their own browser — the guided lane settles the card the same way")
    void anObservedSubmissionSettlesTheCard() {
        Review review = review();
        OperationsCase card = openCase(review);
        execution(review, ReviewExecutionLane.GUIDED, ReviewExecutionStatus.SELLER_SUBMISSION_OBSERVED,
                ReviewExecutionVerification.SELLER_SUBMISSION_OBSERVED);

        convergence.onReplySettled(new ReviewReplySettledEvent(org, review.getId()));

        assertThat(cases.findById(card.getId()).orElseThrow().getStatus()).isEqualTo(OperationsCaseStatus.ACTED);
    }

    @Test
    @DisplayName("a gate's refusal and a filled composer sent nothing — the review is still the seller's to decide")
    void whatSentNothingMovesNothing() {
        Review refused = review();
        OperationsCase refusedCard = openCase(refused);
        execution(refused, ReviewExecutionLane.API, ReviewExecutionStatus.REFUSED,
                ReviewExecutionVerification.UNVERIFIABLE);
        Review filled = review();
        OperationsCase filledCard = openCase(filled);
        execution(filled, ReviewExecutionLane.GUIDED, ReviewExecutionStatus.COMPOSER_FILLED,
                ReviewExecutionVerification.COMPOSER_FILLED);

        convergence.onReplySettled(new ReviewReplySettledEvent(org, refused.getId()));
        convergence.onReplySettled(new ReviewReplySettledEvent(org, filled.getId()));

        assertThat(cases.findById(refusedCard.getId()).orElseThrow().getStatus())
                .as("a gate stopped it: not one byte left").isEqualTo(OperationsCaseStatus.PREPARED);
        assertThat(cases.findById(filledCard.getId()).orElseThrow().getStatus())
                .as("the body is in the composer; only the seller presses submit")
                .isEqualTo(OperationsCaseStatus.PREPARED);
    }

    @Test
    @DisplayName("the seller reported posting it by hand — that is the third thing that settles a card; an abort is not")
    void theOperatorsOwnReportSettlesTheCard_andAnAbortDoesNot() {
        Review posted = review();
        OperationsCase postedCard = openCase(posted);
        operatorReport(posted, com.sellerops.attention.reply.OperatorOutcome.OPERATOR_REPORTED_SUBMITTED);
        Review abandoned = review();
        OperationsCase abandonedCard = openCase(abandoned);
        operatorReport(abandoned, com.sellerops.attention.reply.OperatorOutcome.SUBMISSION_ABORTED);

        convergence.onReplySettled(new ReviewReplySettledEvent(org, posted.getId()));
        convergence.onReplySettled(new ReviewReplySettledEvent(org, abandoned.getId()));

        assertThat(cases.findById(postedCard.getId()).orElseThrow().getStatus())
                .isEqualTo(OperationsCaseStatus.ACTED);
        assertThat(cases.findById(abandonedCard.getId()).orElseThrow().getStatus())
                .as("they decided not to post: the review stays fully in the worklist")
                .isEqualTo(OperationsCaseStatus.PREPARED);
    }

    @Test
    @DisplayName("an event about a review this organisation has no open card for changes nothing and raises nothing")
    void anEventWithNoCardIsHarmless() {
        Review review = review();
        execution(review, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED, ReviewExecutionVerification.VERIFIED);

        convergence.onReplySettled(new ReviewReplySettledEvent(org, review.getId()));
        convergence.onReplySettled(new ReviewReplySettledEvent(UUID.randomUUID(), review.getId()));

        assertThat(cases.findAll()).noneMatch(c -> org.equals(c.getOrgId()));
    }


    /* ─────────────── when, not just whether ─────────────── */

    @Test
    @DisplayName("a card moves only after the record that moved it is durable — a rolled-back reply leaves it exactly where it was")
    void theCardMovesOnlyAfterTheRecordCommits() {
        Review review = review();
        OperationsCase card = openCase(review);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            execution(review, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED,
                    ReviewExecutionVerification.VERIFIED);
            publisher.publishEvent(new ReviewReplySettledEvent(org, review.getId()));
            throw new IllegalStateException("the write did not land");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(executions.findAll()).as("the reply record rolled back").noneMatch(
                row -> review.getId().equals(row.getReviewId()));
        assertThat(cases.findById(card.getId()).orElseThrow().getStatus())
                .as("so the card is untouched: a card settled from a record that never landed is a conclusion "
                        + "about nothing, and the next run would contradict it")
                .isEqualTo(OperationsCaseStatus.PREPARED);
        assertThat(counting.converges.get())
                .as("and the derivation was never even asked — not asked and rolled back are different properties")
                .isZero();

        tx.executeWithoutResult(status -> {
            execution(review, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED,
                    ReviewExecutionVerification.VERIFIED);
            publisher.publishEvent(new ReviewReplySettledEvent(org, review.getId()));
        });

        assertThat(counting.converges.get()).as("asked once, after the commit").isEqualTo(1);
        assertThat(cases.findById(card.getId()).orElseThrow().getStatus())
                .as("the same two writes, committed: the card settles now rather than at the next window")
                .isEqualTo(OperationsCaseStatus.ACTED);
    }

    @Test
    @DisplayName("published with no transaction at all, the card still settles — the two writers hold none, and a dropped event would be no convergence")
    void withNoTransactionTheCardStillSettles() {
        Review review = review();
        OperationsCase card = openCase(review);
        execution(review, ReviewExecutionLane.API, ReviewExecutionStatus.POSTED,
                ReviewExecutionVerification.VERIFIED);

        publisher.publishEvent(new ReviewReplySettledEvent(org, review.getId()));

        assertThat(cases.findById(card.getId()).orElseThrow().getStatus()).isEqualTo(OperationsCaseStatus.ACTED);
    }

    private Review review() {
        Review review = new Review();
        review.setOrgId(org);
        review.setChannelId(UUID.randomUUID());
        review.setRating(1);
        review.setBody("한 달 만에 떨어졌어요.");
        review.setNegative(true);
        review.setReceivedAt(Instant.now());
        review.setReplyState(ReviewReplyState.PENDING);
        return reviews.save(review);
    }

    private OperationsCase openCase(Review review) {
        OperationsCase c = new OperationsCase();
        c.setOrgId(org);
        c.setResponsibilityId(UUID.randomUUID());
        c.setOriginRunId(UUID.randomUUID());
        c.setLastRunId(c.getOriginRunId());
        c.setCaseKind(OperationsCaseKind.CUSTOMER_WORK);
        c.setSubjectKind(OperationsSubjectKind.REVIEW);
        c.setSubjectId(review.getId());
        c.setSignature(OperationsSignal.signature(org, OperationsSubjectKind.REVIEW, review.getId(),
                OperationsSignal.reviewState(review), null));
        c.setSourceState(OperationsSignal.reviewState(review));
        c.setStatus(OperationsCaseStatus.PREPARED);
        c.setPriority(CasePriority.HIGH);
        c.setReason(CaseReason.REVIEW_NEEDS_ATTENTION);
        c.setReasonNote(CaseReason.REVIEW_NEEDS_ATTENTION.noteKo());
        c.setDisposition(CaseDisposition.NEEDS_DECISION);
        c.setRequiredAuthority(RequiredAuthority.HUMAN);
        c.setPreparedAction(CasePreparedAction.RECOMMENDATION_ONLY);
        c.setDecidedBy(CaseDecider.RULE);
        return cases.saveAndFlush(c);
    }

    private void operatorReport(Review review, com.sellerops.attention.reply.OperatorOutcome outcome) {
        com.sellerops.attention.reply.ReviewReplyOutcome row =
                new com.sellerops.attention.reply.ReviewReplyOutcome();
        row.setOrgId(org);
        row.setReviewId(review.getId());
        row.setSubmissionRef(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setRecordedVersion(1);
        row.setRecordedFingerprint(FP);
        row.setFingerprintAlgorithm("sha256");
        row.setOperatorOutcome(outcome);
        row.setVerification(com.sellerops.attention.reply.VerificationState.UNVERIFIED);
        row.setCommandId("cmd-" + UUID.randomUUID());
        row.setRecordedBy("SELLER:" + UUID.randomUUID());
        outcomes.saveAndFlush(row);
    }

    private void execution(Review review, ReviewExecutionLane lane, ReviewExecutionStatus status,
                           ReviewExecutionVerification verification) {
        ReviewReplyExecution row = new ReviewReplyExecution();
        row.setOrgId(org);
        row.setReviewId(review.getId());
        row.setSellerAccountId(UUID.randomUUID());
        row.setChannelCode("CAFE24");
        row.setLane(lane);
        row.setApprovedVersion(1);
        row.setApprovedFingerprint(FP);
        row.setCommandId("cmd-" + UUID.randomUUID());
        row.setStatus(status);
        row.setVerification(verification);
        row.setObservedAt(Instant.now());
        row.setRecordedBy("SELLER:" + UUID.randomUUID());
        if (status == ReviewExecutionStatus.REFUSED) {
            row.setReason(ReviewExecutionReason.EXECUTION_DISABLED);
        }
        executions.saveAndFlush(row);
    }
}
