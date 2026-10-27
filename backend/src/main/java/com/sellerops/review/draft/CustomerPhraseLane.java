package com.sellerops.review.draft;

import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import com.sellerops.knowledge.CustomerPhrases;
import com.sellerops.knowledge.KnowledgeBlanks;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The guidance a review asks for in the customer's own words</b> — 「고객은 이렇게 말해요」 (owner decision 2026-10-27).
 *
 * <p>The seller writes, beside a piece of guidance, how customers say the problem it answers. A review that says one of
 * those expressions is answered with that guidance, ahead of whatever the lexical lanes found: the lexical scorer found
 * the registered adhesion note for 19 of 79 reviews that complained about exactly it, because 「떨어져요」 shares no word
 * with 「접착력이 약할 때」 (FINDINGS B33). Nothing is scored and nothing is guessed — the seller's list, matched as
 * {@link CustomerPhrases} reads it.
 *
 * <p><b>Only what the seller registered.</b> The corpus is active product documents of the review's own product and
 * the company's active rules; a proposal waiting in 확인 필요 is not a document and is never read, and a document that
 * still carries a 【확인: …】 blank is skipped ({@link KnowledgeBlanks}). A document written about one 규격 is left to the
 * lexical lane, which knows how to caution about a variant this review never named.
 */
@Component
public class CustomerPhraseLane {

    /** A hit is the seller's own say-so; it ranks above anything the scorer found. */
    static final double HIT_SCORE = 1.0;

    private final ProductKnowledgeSourceRepository productSources;
    private final OrgKnowledgeSourceRepository orgSources;

    public CustomerPhraseLane(ProductKnowledgeSourceRepository productSources,
                              OrgKnowledgeSourceRepository orgSources) {
        this.productSources = productSources;
        this.orgSources = orgSources;
    }

    /** The guidance {@code reviewBody} names in a customer's words — product documents first, then company rules. */
    @Transactional(readOnly = true)
    public List<ScopedPassage> hits(UUID orgId, UUID productId, String reviewBody) {
        if (orgId == null || reviewBody == null || reviewBody.isBlank()) {
            return List.of();
        }
        List<ScopedPassage> found = new ArrayList<>();
        if (productId != null) {
            productSources.findAllByOrgIdAndProductIdAndActiveTrueAndCustomerPhrasesIsNotNull(orgId, productId)
                    .stream()
                    .filter(s -> s.getVariantId() == null && !KnowledgeBlanks.carries(s.getBody()))
                    .filter(s -> CustomerPhrases.firstHit(reviewBody, CustomerPhrases.parse(s.getCustomerPhrases()))
                            .isPresent())
                    .sorted(Comparator.comparing(ProductKnowledgeSource::getTitle))
                    .forEach(s -> found.add(new ScopedPassage(KnowledgeScope.PRODUCT, s.getTitle(), s.getBody(),
                            s.getId(), null, InquiryEvidenceRetriever.productLocator(s.getSourceType(),
                                    s.getAuthorName()), HIT_SCORE, s.getAuthoredOrigin())));
        }
        orgSources.findAllByOrgIdAndActiveTrueAndCustomerPhrasesIsNotNull(orgId).stream()
                .filter(s -> !KnowledgeBlanks.carries(s.getBody()))
                .filter(s -> CustomerPhrases.firstHit(reviewBody, CustomerPhrases.parse(s.getCustomerPhrases()))
                        .isPresent())
                .sorted(Comparator.comparing(OrgKnowledgeSource::getTitle))
                .forEach(s -> found.add(new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, s.getTitle(), s.getBody(),
                        s.getId(), null, InquiryEvidenceRetriever.policyLocator(s.getKnowledgeType(),
                                s.getAuthorName(), s.getVersion()), HIT_SCORE)));
        return List.copyOf(found);
    }
}
