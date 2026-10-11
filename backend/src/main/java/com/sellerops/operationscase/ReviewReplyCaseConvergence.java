package com.sellerops.operationscase;

import com.sellerops.review.publish.ReviewReplySettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * <b>The review half of {@link InquiryAnswerCaseConvergence} — «the seller sent it» and «the card stops asking»,
 * in the same minute.</b>
 *
 * <p>Review Delivery Truth Spine v1 taught {@link OperationsCaseReconciler#deriveReview} to read the reply lane's
 * own record, so a reply reviewnary posted and verified by hash, or a submission the collector saw, already counts
 * as the seller having acted. What it could not change is WHEN that is noticed: the derivation ran only inside a
 * responsibility run, on fixed two-hour Asia/Seoul windows. So the defect that spine closed at the level of the
 * rule stayed open at the level of the moment — a seller who approved a reply and watched it go out at 10:05 kept
 * seeing that review under 확인할 일, indistinguishable from work nobody had touched, until the window turned.
 * The inquiry lane has not had that gap since its own event existed; this is the same seam for the other lane.
 *
 * <p><b>Not a second opinion.</b> It re-derives one subject through {@link OperationsCaseReconciler#converge} —
 * the run's own {@code derive} and {@code apply} — so an event that arrives when the case should NOT move moves
 * nothing: a {@code REFUSED} execution, a filled composer, an aborted submission. Those are exactly the states
 * {@code sellerActed()} answers no to, and this class does not know their names.
 *
 * <p><b>No scheduler semantics change.</b> The cadence, the lease, the page size and the run's visit to this case
 * are untouched; the run simply finds it already settled.
 *
 * <p><b>It cannot affect the send.</b> The execution row is written — and on the API lane a public reply has
 * already left for the channel — before this runs, and a failure here is logged and dropped: a card that is late
 * is a smaller defect than an answer whose recorded outcome depends on a screen's bookkeeping succeeding.
 *
 * <p><b>AFTER_COMMIT, and the fallback is not optional.</b> The two writers hold no transaction of their own, so
 * the row is committed before the event is raised and this runs immediately — which is why
 * {@code fallbackExecution = true} is there: without it an event raised outside a transaction is silently
 * DROPPED, and the convergence would simply stop happening on exactly the path that has it today. The phase is
 * what makes the invariant hold if a caller ever wraps a write in a transaction: a card may only move after the
 * record that moved it is durable, and a rollback must leave the card exactly where it was. The derivation reads
 * rows, so a card settled from a record that never landed would be a conclusion about nothing — and the run two
 * hours later would silently contradict it.
 *
 * <p>{@code REQUIRES_NEW} because the transaction that carried the reply is gone by now: the two writes
 * {@code converge} makes (the case and its event) belong together, and after commit there is no unit left to
 * join. It cannot roll the reply back — there is nothing of the reply in it.
 */
@Component
public class ReviewReplyCaseConvergence {

    private static final Logger log = LoggerFactory.getLogger(ReviewReplyCaseConvergence.class);

    private final OperationsCaseReconciler reconciler;

    public ReviewReplyCaseConvergence(OperationsCaseReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReplySettled(ReviewReplySettledEvent event) {
        try {
            reconciler.converge(event.orgId(), OperationsSubjectKind.REVIEW, event.reviewId());
        } catch (RuntimeException e) {
            // The reply is already recorded; this is the card catching up. The scheduled run will reach the same
            // conclusion from the same rows.
            log.warn("responsibility: review case 즉시 수렴 실패 org={} review={} 사유={}",
                    event.orgId(), event.reviewId(), e.getClass().getSimpleName());
        }
    }
}
