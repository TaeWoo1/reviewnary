package com.sellerops.review.draft;

import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.attention.reply.ReviewReplyFingerprint;
import com.sellerops.attention.reply.ReviewReplyValidation;
import com.sellerops.common.BaseEntity;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.Review;
import java.time.Instant;
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

    /**
     * The finding, and what the seller is shown about it (owner decision 2026-10-23: a hold names the changed basis,
     * the sentence it touches and what to do). {@code changed}: the cited documents that were edited or taken out of
     * use; {@code promises}: the commitment words that lost their ground; {@code sentences}: the reply's own
     * sentences that carry them, at most {@value #MAX_SENTENCES}.
     */
    public record Result(boolean passed, List<String> codes, List<Changed> changed, List<String> promises,
                         List<String> sentences) {

        static Result of(List<String> codes, List<Changed> changed, List<String> promises, List<String> sentences) {
            return new Result(codes.isEmpty(), List.copyOf(codes), List.copyOf(changed), List.copyOf(promises),
                    List.copyOf(sentences));
        }
    }

    /** A document a version cited that is no longer as it was: edited after the version, or out of use. */
    public record Changed(String kind, String title, Instant changedAt, boolean removed) {}

    static final int MAX_SENTENCES = 3;
    static final int MAX_SENTENCE = 300;
    static final int MAX_CHANGED = 5;

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
        List<Changed> changed = written.isPresent() ? changedCitations(orgId, review.getId(), written.get())
                : List.of();
        if (!changed.isEmpty()) {
            codes.add(SOURCE_CHANGED);
        }
        List<String> license = composer.currentLicense(orgId, review, redactedBody);
        List<String> promises = ReviewClaimGuard.unsupportedClaims(body, license);
        if (!promises.isEmpty()) {
            codes.add(UNSUPPORTED_PROMISE);
        }
        return Result.of(codes, changed, promises, sentencesWith(body, promises));
    }

    /**
     * The reply's sentences that carry one of {@code promises}, in order and at most {@value #MAX_SENTENCES} — each
     * cut to {@value #MAX_SENTENCE} characters from its start, so it stays a piece of the reply's own text.
     */
    public static List<String> sentencesWith(String body, List<String> promises) {
        List<String> out = new java.util.ArrayList<>();
        if (promises.isEmpty()) {
            return out;
        }
        for (String sentence : body.split("(?<=[.!?。])\\s+|\\n+")) {
            String squeezed = sentence.replaceAll("\\s+", "");
            String trimmed = sentence.strip();
            if (!trimmed.isEmpty() && promises.stream().anyMatch(squeezed::contains)) {
                out.add(trimmed.length() > MAX_SENTENCE ? trimmed.substring(0, MAX_SENTENCE) : trimmed);
                if (out.size() == MAX_SENTENCES) {
                    break;
                }
            }
        }
        return out;
    }

    /** The cited product and company documents that were removed, put out of use, or edited after {@code version}. */
    private List<Changed> changedCitations(UUID orgId, UUID reviewId, ReviewReplyDraft version) {
        List<Changed> out = new java.util.ArrayList<>();
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
                if (out.size() < MAX_CHANGED) {
                    // A document deleted outright has no title left to show.
                    String title = source.map(ReviewDraftRecheck::title).orElse(null);
                    out.add(new Changed(row.getKind(), title == null ? "삭제된 문서" : title,
                            source.map(BaseEntity::getUpdatedAt).orElse(null), source.isEmpty() || !active));
                }
            }
        }
        return out;
    }

    private static String title(BaseEntity source) {
        String title = source instanceof ProductKnowledgeSource p ? p.getTitle()
                : source instanceof OrgKnowledgeSource o ? o.getTitle() : null;
        if (title == null || title.isBlank()) {
            return null;
        }
        String clean = title.strip().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
