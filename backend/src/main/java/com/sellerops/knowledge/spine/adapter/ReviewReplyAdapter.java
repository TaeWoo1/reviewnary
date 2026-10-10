package com.sellerops.knowledge.spine.adapter;

import com.sellerops.attention.reply.ReviewReplyApproval;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.inquiry.draft.DraftAuthorKind;
import com.sellerops.knowledge.KnowledgeText;
import com.sellerops.knowledge.spine.KnowledgeAuthority;
import com.sellerops.knowledge.spine.KnowledgeEntry;
import com.sellerops.knowledge.spine.KnowledgeSpineScope;
import com.sellerops.knowledge.spine.SourceRef;
import com.sellerops.knowledge.spine.SpineSourceType;
import com.sellerops.review.Review;
import com.sellerops.review.publish.ReviewDeliveryTruthReader;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <b>Past reviews and the reply the seller stood behind.</b>
 *
 * <p>A review reply becomes company knowledge at the same moment an inquiry answer becomes Answer Memory: when
 * the seller approves an exact version. That version is read from the append-only draft table, and it counts
 * only if its content fingerprint is the one the approval bound — a draft that is not the approved text is not
 * what the seller said. A withdrawn approval is not the company's word and is not read.
 *
 * <p><b>Not every approved sentence is knowledge.</b> A {@code RULE} draft is the org's own reply template
 * reproduced for this review; approving it decides this review, and says nothing about how the company answers
 * a topic. It is left out, the same judgement {@code InquiryAnswerMemoryHook} makes for inquiries.
 *
 * <p>The review is the raw source the entry points at. Its text is matched on and never returned.
 */
@Component
public class ReviewReplyAdapter implements KnowledgeSourceAdapter {

    /** Approved replies read per scope. Past answers are precedent, not a corpus to page through. */
    static final int MAX_REPLIES = 200;

    private final EntityManager em;
    /**
     * The merge of what reviewnary did and what the operator reported (Review Delivery Truth Spine v1).
     *
     * <p>This adapter used to answer «was this version registered?» from {@code review_reply_outcome} alone, which
     * made it blind to its own product's API lane: a Cafe24 reply reviewnary posted and verified by hash read back
     * to the seller as merely 「판매자가 승인한 답글」. The question is the same; the record that answers it is now
     * both halves.
     */
    private final ReviewDeliveryTruthReader deliveries;

    public ReviewReplyAdapter(EntityManager em, ReviewDeliveryTruthReader deliveries) {
        this.em = em;
        this.deliveries = deliveries;
    }

    @Override
    public List<Indexed> read(UUID orgId, UUID productId) {
        List<Object[]> rows = em.createQuery("""
                        select a, d, r from ReviewReplyApproval a, ReviewReplyDraft d, Review r
                        where a.orgId = :orgId and a.state = :approved
                          and d.orgId = :orgId and d.reviewId = a.reviewId and d.version = a.approvedVersion
                          and r.orgId = :orgId and r.id = a.reviewId
                          and (r.productId is null or r.productId = :productId)
                        order by a.decidedAt desc
                        """, Object[].class)
                .setParameter("orgId", orgId)
                .setParameter("approved", ReviewReplyApprovalState.APPROVED)
                // A random id matches no product, so an org-level read keeps only unbound reviews.
                .setParameter("productId", productId == null ? UUID.randomUUID() : productId)
                .setMaxResults(MAX_REPLIES)
                .getResultList();
        Set<String> reported = deliveredVersions(orgId, rows);
        List<Indexed> out = new java.util.ArrayList<>(rows.stream()
                .map(row -> indexed((ReviewReplyApproval) row[0], (ReviewReplyDraft) row[1], (Review) row[2],
                        reported))
                .filter(java.util.Objects::nonNull)
                .toList());
        Set<UUID> approved = new HashSet<>();
        rows.forEach(row -> approved.add(((Review) row[2]).getId()));
        out.addAll(channelReplies(orgId, productId, approved));
        return out;
    }

    /**
     * Replies the seller published on the channel, read onto the canonical review by the reply enrichment
     * ({@code NaverReviewReplyEnrichmentService}). A review whose approved reply is already an entry above is not
     * listed twice. Same authority as an approved reply: it is what the seller said, not what is currently true.
     */
    private List<Indexed> channelReplies(UUID orgId, UUID productId, Set<UUID> alreadyListed) {
        List<Review> replied = em.createQuery("""
                        select r from Review r
                        where r.orgId = :orgId and r.sellerReplyBody is not null
                          and (r.productId is null or r.productId = :productId)
                          and r.dataOrigin = :real
                        order by r.sellerReplyObservedAt desc
                        """, Review.class)
                .setParameter("orgId", orgId)
                .setParameter("productId", productId == null ? UUID.randomUUID() : productId)
                .setParameter("real", com.sellerops.common.DataOrigin.REAL)
                .setMaxResults(MAX_REPLIES)
                .getResultList();
        List<Indexed> out = new java.util.ArrayList<>();
        for (Review review : replied) {
            if (alreadyListed.contains(review.getId())) {
                continue;
            }
            UUID bound = review.getProductId();
            String question = VocPreviewSanitizer.redactFullBody(review.getBody()).text();
            out.add(new Indexed(new KnowledgeEntry(
                    SpineSourceType.REVIEW_REPLY + ":" + review.getId(),
                    SpineSourceType.REVIEW_REPLY,
                    bound == null ? KnowledgeSpineScope.ORG : KnowledgeSpineScope.PRODUCT, bound, null,
                    KnowledgeAuthority.PAST_SELLER_ANSWER,
                    review.getRating() == null ? "리뷰 답글" : "리뷰 답글 · 별점 " + review.getRating() + "점",
                    review.getSellerReplyBody(),
                    review.getSellerReplyAt() != null ? review.getSellerReplyAt() : review.getSellerReplyObservedAt(),
                    CHANNEL_REPLY_PROVENANCE,
                    List.of(SourceRef.of(SourceRef.Kind.REVIEW, review.getId()))),
                    KnowledgeText.normalize(question == null ? "" : question)
                            + KnowledgeText.normalize(review.getSellerReplyBody())));
        }
        return out;
    }

    /** The provenance sentence of a reply read off the channel. */
    public static final String CHANNEL_REPLY_PROVENANCE = "리뷰 답글 · 채널에 등록된 답글";

    private Indexed indexed(ReviewReplyApproval approval, ReviewReplyDraft draft, Review review, Set<String> reported) {
        if (!isAnswer(draft.getAuthorKind()) || approval.getApprovedFingerprint() == null
                || !approval.getApprovedFingerprint().equals(draft.getContentFingerprint())) {
            return null;
        }
        boolean submitted = reported.contains(review.getId() + ":" + draft.getVersion());
        UUID productId = review.getProductId();
        String question = VocPreviewSanitizer.redactFullBody(review.getBody()).text();
        return new Indexed(new KnowledgeEntry(
                SpineSourceType.REVIEW_REPLY + ":" + approval.getId(),
                SpineSourceType.REVIEW_REPLY,
                productId == null ? KnowledgeSpineScope.ORG : KnowledgeSpineScope.PRODUCT, productId, null,
                KnowledgeAuthority.PAST_SELLER_ANSWER,
                review.getRating() == null ? "리뷰 답글" : "리뷰 답글 · 별점 " + review.getRating() + "점",
                draft.getBody(), approval.getDecidedAt(),
                // Reworded with the record that now answers it: before Review Delivery Truth Spine v1 the only
                // registration this adapter could see was the operator's own report, so the sentence named the
                // operator. It now also covers a reply reviewnary posted itself, and 「기록된」 is doing real work
                // — the API lane's read-back can prove the channel holds the approved text, the guided lane's
                // cannot, and neither claim belongs in a provenance line that has to be true of both.
                submitted ? "리뷰 답글 · 채널에 등록된 것으로 기록된 답글" : "리뷰 답글 · 판매자가 승인한 답글",
                List.of(SourceRef.of(SourceRef.Kind.REVIEW_REPLY_APPROVAL, approval.getId()),
                        new SourceRef(SourceRef.Kind.REVIEW_REPLY_DRAFT, draft.getId(), "v" + draft.getVersion()),
                        SourceRef.of(SourceRef.Kind.REVIEW, review.getId()))),
                KnowledgeText.normalize(question == null ? "" : question) + KnowledgeText.normalize(draft.getBody()));
    }

    /**
     * {@code reviewId:version} for every approved version the record says actually went to the channel.
     *
     * <p>One batch for the whole page, as before. What changed is which record answers: both halves of the
     * delivery truth rather than the operator's self-report alone, so an API-lane POST counts as the registration
     * it is. The predicate is the truth's own {@code sellerActed()} — a refused execution and a filled-but-not-
     * submitted composer are not registrations, and this adapter does not restate that judgement.
     */
    private Set<String> deliveredVersions(UUID orgId, List<Object[]> rows) {
        List<UUID> reviewIds = rows.stream().map(row -> ((Review) row[2]).getId()).toList();
        return deliveries.deliveredVersions(orgId, reviewIds);
    }

    /** A sentence the seller wrote or a model wrote from evidence — never a template. Null is a manual save. */
    public static boolean isAnswer(String authorKind) {
        return authorKind == null || authorKind.isBlank()
                || DraftAuthorKind.SELLER.name().equals(authorKind)
                || DraftAuthorKind.MODEL.name().equals(authorKind);
    }
}
