package com.sellerops.review.memory;

import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.knowledge.spine.adapter.ReviewReplyAdapter;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>The two moments in a review reply's life that are worth remembering</b> — the review lane's half of Answer
 * Memory (Review Delivery Truth Spine v1).
 *
 * <p>Deliberately the same shape, the same two acts and the same two strengths as
 * {@code InquiryAnswerMemoryHook}, because the seller is doing the same two things on both screens. An APPROVAL is
 * the seller saying «this is what we say» — a preference, recorded as {@code USER_APPROVED}. A VERIFIED delivery is
 * that plus proof the channel holds exactly those words, recorded as {@code EXECUTOR_SENT_VERIFIED}. A draft, an
 * edit in progress, a refused execution and a filled composer write nothing: the first two are not the seller's
 * word yet, and the last two did not happen.
 *
 * <p><b>Why the verified half is API-lane-only, and why that is not a gap.</b> It asks the delivery truth
 * ({@code ReviewDeliveryTruth.verifiedDelivery()}), which is true for {@code VERIFIED} alone — a Cafe24 comment
 * read back and hashed against the approved draft. A NAVER guided submission can reach «a reply exists on this
 * review» and no further, because the export carries no reply body. The seller's own report that they posted it is
 * recorded, trusted and shown — it just cannot become a memory, because a memory is a claim about what the company
 * SAID, and only the hash proves what that was. Trusting the report here would let an edited-in-the-composer
 * sentence come back as the company's precedent under the strongest strength the product has.
 *
 * <p><b>The review body is read and dropped.</b> It goes in only as the question the topic signature is built
 * from, redacted first by {@link VocPreviewSanitizer}, exactly as the inquiry hook passes the inquiry's title and
 * body. Answer Memory stores no customer sentence, so a reply remembered here carries the seller's words and the
 * signature of what they were answering, and nothing of the customer's.
 *
 * <p><b>Best-effort, always.</b> The approval and the execution are the operations; remembering them is not. A
 * failure here is logged with ids and counts only and never fails the act that produced it — a seller must not be
 * told their approval failed because an index could not be updated. Nothing here contacts a channel, a model or an
 * approval, and nothing here writes {@code review.replyState}.
 */
@Component
public class ReviewAnswerMemoryHook {

    private static final Logger log = LoggerFactory.getLogger(ReviewAnswerMemoryHook.class);

    /** The act ids. Keyed on the version as well as the review, so a re-approval is a second act, not an overwrite. */
    static final String APPROVED_PREFIX = "review-approved:";
    static final String VERIFIED_PREFIX = "review-verified:";

    private final AnswerMemoryService memory;
    private final ChannelRepository channels;
    private final ProductRepository products;

    public ReviewAnswerMemoryHook(AnswerMemoryService memory, ChannelRepository channels,
                                  ProductRepository products) {
        this.memory = memory;
        this.channels = channels;
        this.products = products;
    }

    /**
     * The seller approved this exact reply version.
     *
     * <p>Keyed on the version as well as the review, so approving a revised reply after a first approval is a
     * second act rather than a silent overwrite of the first — and so the record can still answer which words the
     * seller stood behind in March when they stand behind different ones in April.
     */
    public void rememberApproved(Review review, ReviewReplyDraft approved, UUID sellerUserId) {
        if (review == null || approved == null) {
            return;
        }
        remember(review, approved, AnswerMemoryStrength.USER_APPROVED,
                APPROVED_PREFIX + review.getId() + ":" + approved.getVersion(), sellerUserId);
    }

    /**
     * The channel was read back and holds exactly this version.
     *
     * <p>Keyed on the version too, not on the review alone: unlike an inquiry — which is answered once — a review
     * can be replied to on one channel and have its reply superseded, and the version is which sentence the
     * read-back actually proved.
     */
    public void rememberVerified(Review review, ReviewReplyDraft delivered, UUID sellerUserId) {
        if (review == null || delivered == null) {
            return;
        }
        remember(review, delivered, AnswerMemoryStrength.EXECUTOR_SENT_VERIFIED,
                VERIFIED_PREFIX + review.getId() + ":" + delivered.getVersion(), sellerUserId);
    }

    private void remember(Review review, ReviewReplyDraft draft, AnswerMemoryStrength strength,
                          String originRef, UUID sellerUserId) {
        if (!isRememberable(draft.getAuthorKind())) {
            // Grounded Review Drafting v1: a RULE draft is the org's own reply template reproduced for this
            // review. Approving it decides this review and says nothing about how the company answers the topic,
            // so remembering it would make a content-free sentence come back as 과거 답변 precedent. The same
            // judgement ReviewReplyAdapter makes at retrieval time, asked here at write time.
            log.info("review answer-memory hook skipped org={} strength={} authorKind={}: not an answer",
                    review.getOrgId(), strength, draft.getAuthorKind());
            return;
        }
        try {
            // Read for the topic signature and then dropped; never stored. Redacted first, because the
            // signature builder is not the only thing that could ever see this string.
            String question = VocPreviewSanitizer.redactFullBody(review.getBody()).text();
            memory.remember(new AnswerMemoryService.RememberCommand(
                    review.getOrgId(), originRef, strength,
                    question == null ? "" : question,
                    title(review), draft.getBody(),
                    namedProductOrNull(review.getOrgId(), productIdOf(review, draft)),
                    channelCode(review.getChannelId()),
                    // No source subtype and no topic category: both are the inquiry lane's closed vocabularies
                    // (a 상품문의 versus a 배송문의, a rule-assigned summary category) and a review has neither.
                    // Guessing one from a star rating would put a made-up classification on the seller's words.
                    null, null,
                    // No inquiry and no work item — this reply answered a review.
                    null, null, draft.getVersion(), sellerUserId, null,
                    review.getDataOrigin(), null, review.getId()));
        } catch (RuntimeException e) {
            log.warn("review answer-memory hook skipped org={} strength={}: {}",
                    review.getOrgId(), strength, e.toString());
        }
    }

    /**
     * The product this reply is about.
     *
     * <p>The draft's own binding wins where it has one: {@code review_reply_draft.product_id} is what the composer
     * actually retrieved evidence for (V90), and a memory scoped to a different product than the sentence was
     * written against would be offered on the wrong catalogue. The review's binding is the fallback.
     */
    private static UUID productIdOf(Review review, ReviewReplyDraft draft) {
        return draft.getProductId() != null ? draft.getProductId() : review.getProductId();
    }

    /**
     * A reply has no title of its own, so the rating stands in as one — the same label
     * {@code ReviewReplyAdapter} puts on the entry it reads back, so one answer does not appear under two names
     * depending on which path surfaced it.
     */
    private static String title(Review review) {
        return review.getRating() == null ? "리뷰 답글" : "리뷰 답글 · 별점 " + review.getRating() + "점";
    }

    private UUID namedProductOrNull(UUID orgId, UUID productId) {
        if (productId == null) {
            return null;
        }
        return products.findById(productId)
                .filter(p -> orgId.equals(p.getOrgId()))
                .filter(p -> OperatorProductName.displayNameOrNull(p) != null)
                .map(p -> productId)
                .orElse(null);
    }

    private String channelCode(UUID channelId) {
        return channelId == null ? null : channels.findById(channelId).map(Channel::getCode).orElse(null);
    }

    /**
     * Only a sentence a person wrote or a model wrote FROM EVIDENCE is an answer worth remembering.
     *
     * <p>Delegates to {@code ReviewReplyAdapter.isAnswer}, which is where this lane already decided it: the
     * retrieval path and the write path asking the same question in two places is how they start disagreeing, and
     * the one that drifts is invisible because both still read 「판매자의 과거 답변」. A null kind is a row from
     * before V90 and is treated as the seller's own.
     */
    static boolean isRememberable(String authorKind) {
        return ReviewReplyAdapter.isAnswer(authorKind);
    }
}
