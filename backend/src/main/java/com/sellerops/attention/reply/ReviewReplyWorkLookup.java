package com.sellerops.attention.reply;

import com.sellerops.attention.VocItemRef;
import com.sellerops.attention.triage.ReviewTriage;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.review.triage.ReviewTriageChannelCapability;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether — and under which address — one review can carry reply work, for a surface that is not the
 * attention drill-down (product assembly A6: the 리뷰 screen's detail panel).
 *
 * <p>The reply flow ({@link ReviewReplyService}) is addressed by {@link VocItemRef}, which clients round-trip
 * and never mint. A surface that shows a review by its own id therefore needs the server to hand it the
 * ref, together with the two facts the panel mounts on: the operator's current decision and whether work
 * (a draft or an approval) already exists. Those are exactly the fields the drill-down row carries
 * ({@code OperatorVocItem.actionRef / triageDisposition / hasReplyPreparation}), computed here for one
 * review from the same repositories.
 *
 * <p><b>Capability-gated on the reply FLOW, not on the send.</b> Empty for every channel with no seller
 * reply flow at all ({@link ReviewTriageChannelCapability#replyFlowExists()} — Coupang, policy gate D8):
 * that surface renders no reply control, and a client that guessed the ref would still be refused by the
 * reply endpoints' own checks, which gate on the same predicate ({@code ReviewReplyService.requireReplyFlow}).
 *
 * <p><b>It used to ask a second question and that was the defect.</b> The gate read {@code replySupported}
 * — the triage contract's older NAVER-only column — and opened Cafe24 only when the account's execution
 * capability was already {@code API_EXECUTION}. So drafting, editing and approving a Cafe24 reply required
 * the marketplace WRITE to be configured first: with the write lane off, 확인할 일 listed the review as
 * 「초안 필요」 (that queue reads {@code replyFlowExists}) and the workspace refused to hand out a ref for
 * it. Two surfaces, two columns, one contradiction the seller could see.
 *
 * <p>Preparation and sending are separate rights and are now separately gated. This class answers «may a
 * draft, an edit and an approval exist for this review» — a question about the channel. Whether the
 * approved text may then leave for the marketplace is {@code ReviewExecutionCapability}'s answer, checked
 * where the send happens ({@code ReviewReplyExecutionService.execute} refuses anything that is not
 * {@code API_EXECUTION}) and reported to the screen as its own field. Nothing here widens that.
 */
@Component
public class ReviewReplyWorkLookup {

    /** The address and state of one review's reply work. */
    public record ReplyWorkRef(String actionRef, String triageDisposition, boolean hasReplyPreparation) {
    }

    private final ReviewTriageRepository triages;
    private final ReviewReplyDraftRepository drafts;
    private final ReviewReplyApprovalRepository approvals;

    public ReviewReplyWorkLookup(ReviewTriageRepository triages, ReviewReplyDraftRepository drafts,
                                 ReviewReplyApprovalRepository approvals) {
        this.triages = triages;
        this.drafts = drafts;
        this.approvals = approvals;
    }

    /**
     * The reply work one review can carry, or empty when its channel has no reply flow at all.
     *
     * <p>Takes no execution capability, and must not: a channel's send configuration is not what decides
     * whether the seller may write and approve an answer. The overload that took one existed only to open
     * Cafe24, which {@code replyFlowExists()} already answers for every caller.
     */
    public Optional<ReplyWorkRef> forReview(UUID orgId, String channelCode, UUID reviewId) {
        if (!ReviewTriageChannelCapability.of(channelCode).replyFlowExists()) {
            return Optional.empty();
        }
        String disposition = triages.findByOrgIdAndReviewId(orgId, reviewId)
                .map(ReviewTriage::getDisposition)
                .map(Enum::name)
                .orElse(null);
        List<UUID> one = List.of(reviewId);
        boolean prepared = !drafts.findReviewIdsWithDraft(orgId, one).isEmpty()
                || !approvals.findReviewIdsWithApproval(orgId, one).isEmpty();
        return Optional.of(new ReplyWorkRef(VocItemRef.forReview(reviewId), disposition, prepared));
    }
}
