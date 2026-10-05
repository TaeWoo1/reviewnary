package com.sellerops.knowledge.inventory;

import com.sellerops.inquiry.draft.InquiryDraftEvidenceRepository;
import com.sellerops.review.draft.ReviewDraftEvidenceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Which of this company's knowledge has actually grounded a draft.</b>
 *
 * <p><b>Two queries for a whole workspace, and that bound is the design.</b> Both lanes are read
 * grouped by document, so a screen listing every rule and every product fact costs the same two
 * reads whether the company wrote four documents or four hundred. Asking per document would be one
 * query per row — the shape the catalogue ordering work removed from the product list, and the one
 * this class exists so nobody reintroduces here.
 *
 * <p><b>It composes nothing.</b> The two lanes' counts are added and the later timestamp kept; no
 * weighting, no decay, no score. See {@link KnowledgeUsage} for what the number may and may not be
 * read as.
 */
@Service
public class KnowledgeUsageReader {

    private final InquiryDraftEvidenceRepository inquiryEvidence;
    private final ReviewDraftEvidenceRepository reviewEvidence;

    public KnowledgeUsageReader(InquiryDraftEvidenceRepository inquiryEvidence,
                                ReviewDraftEvidenceRepository reviewEvidence) {
        this.inquiryEvidence = inquiryEvidence;
        this.reviewEvidence = reviewEvidence;
    }

    /**
     * Every citation this company's drafts have recorded, one row per document per kind.
     *
     * <p>Flat rather than already folded into a map, because the caller folds it two different ways
     * and neither fold is this class's decision: one by document (what each document has grounded),
     * one by kind (how many ORG_POLICY citations no longer match a rule that exists).
     */
    @Transactional(readOnly = true)
    public List<KnowledgeCitation> citations(UUID orgId) {
        List<KnowledgeCitation> all = new ArrayList<>();
        add(all, inquiryEvidence.citationsBySource(orgId));
        add(all, reviewEvidence.citationsBySource(orgId));
        return all;
    }

    private static void add(List<KnowledgeCitation> into, List<Object[]> rows) {
        for (Object[] row : rows) {
            into.add(new KnowledgeCitation(
                    (UUID) row[0], (String) row[1], ((Number) row[2]).longValue(), (Instant) row[3]));
        }
    }
}
