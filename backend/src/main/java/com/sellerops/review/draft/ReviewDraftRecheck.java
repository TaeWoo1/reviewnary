package com.sellerops.review.draft;

import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.attention.reply.ReviewReplyFingerprint;
import com.sellerops.attention.reply.ReviewReplyValidation;
import com.sellerops.common.BaseEntity;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.Review;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A sent-back reply checked again before anyone is asked</b> (product-owner answer to interview question 34,
 * 2026-10-12: 「재검증 방식」 — the AI checks first; a draft that still stands goes on, one that does not is held and
 * the seller is asked).
 *
 * <p>No model writes or judges anything. The check is what this PC can prove from its own rows and the review's own
 * retrieval ({@link ReviewDraftComposer#currentLicense} — the draft's one retrieval, which uses the embedding model
 * only where semantic search is turned on), and it is deliberately the strict reading of "still stands":
 *
 * <ol>
 *   <li><b>What it was written from is unchanged.</b> When the text is a version written here, every product or
 *       company document that version cited must still exist, be in use, and not have been edited since — else
 *       {@value #SOURCE_CHANGED}. A citation to a past reply is not checked: it was never a fact.</li>
 *   <li><b>It promises nothing the current evidence does not.</b> {@link ReviewClaimGuard} against the evidence the
 *       review finds now, and the seller's own wording for its kind of review — else {@value #UNSUPPORTED_PROMISE}.
 *       This is the only check a text written on the phone can get here: it has no citations of its own.</li>
 * </ol>
 *
 * <p>A seller's edit is never rewritten: the result is pass or hold, and the text is the text.
 */
@Service
public class ReviewDraftRecheck {

    public static final String SOURCE_CHANGED = "SOURCE_CHANGED";
    public static final String UNSUPPORTED_PROMISE = "UNSUPPORTED_PROMISE";

    public record Result(boolean passed, List<String> codes) {

        static Result of(List<String> codes) {
            return new Result(codes.isEmpty(), List.copyOf(codes));
        }
    }

    private final ReviewDraftComposer composer;
    private final ReviewReplyDraftRepository drafts;
    private final ReviewDraftEvidenceRepository evidence;
    private final ProductKnowledgeSourceRepository productSources;
    private final OrgKnowledgeSourceRepository orgSources;

    public ReviewDraftRecheck(ReviewDraftComposer composer, ReviewReplyDraftRepository drafts,
                              ReviewDraftEvidenceRepository evidence, ProductKnowledgeSourceRepository productSources,
                              OrgKnowledgeSourceRepository orgSources) {
        this.composer = composer;
        this.drafts = drafts;
        this.evidence = evidence;
        this.productSources = productSources;
        this.orgSources = orgSources;
    }

    /**
     * @param redactedBody the review body after the preview sanitizer, as the composer reads it
     * @param text         the reply as it stands on the server
     */
    @Transactional(readOnly = true)
    public Result check(UUID orgId, Review review, String redactedBody, String text) {
        List<String> codes = new java.util.ArrayList<>();
        String body = ReviewReplyValidation.normalize(text);
        Optional<ReviewReplyDraft> written = drafts.findTopByReviewIdAndContentFingerprintOrderByVersionDesc(
                review.getId(), ReviewReplyFingerprint.of(body));
        if (written.isPresent() && !citationsStand(orgId, review.getId(), written.get())) {
            codes.add(SOURCE_CHANGED);
        }
        List<String> license = composer.currentLicense(orgId, review, redactedBody);
        if (!ReviewClaimGuard.unsupportedClaims(body, license).isEmpty()) {
            codes.add(UNSUPPORTED_PROMISE);
        }
        return Result.of(codes);
    }

    private boolean citationsStand(UUID orgId, UUID reviewId, ReviewReplyDraft version) {
        for (ReviewDraftEvidence row : evidence.findByOrgIdAndReviewIdAndDraftVersionOrderByOrdinalAsc(orgId, reviewId,
                version.getVersion())) {
            Optional<? extends BaseEntity> source;
            boolean active;
            if (InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(row.getKind())) {
                var found = productSources.findByIdAndOrgId(row.getSourceId(), orgId);
                source = found;
                active = found.map(s -> s.isActive()).orElse(false);
            } else if (InquiryDraftEvidence.KIND_ORG_POLICY.equals(row.getKind())) {
                var found = orgSources.findByIdAndOrgId(row.getSourceId(), orgId);
                source = found;
                active = found.map(s -> s.isActive()).orElse(false);
            } else {
                continue;
            }
            if (source.isEmpty() || !active || source.get().getUpdatedAt().isAfter(version.getCreatedAt())) {
                return false;
            }
        }
        return true;
    }
}
