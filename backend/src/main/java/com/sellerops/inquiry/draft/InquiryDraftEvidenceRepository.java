package com.sellerops.inquiry.draft;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryDraftEvidenceRepository extends JpaRepository<InquiryDraftEvidence, UUID> {

    List<InquiryDraftEvidence> findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(
            UUID workItemId, int draftVersion);

    /**
     * <b>How often each knowledge document has been cited by an inquiry draft, and when last.</b>
     *
     * <p>Grouped in the database because the screen that asks this asks it about EVERY document at
     * once: a per-document read would be one query per row of the knowledge workspace, which is the
     * shape this repository exists to avoid.
     *
     * <p><b>What the number means, exactly.</b> It counts stored evidence relations — the rows this
     * table already holds, one per cited passage per draft version. It is not a count of answers
     * sent, of customers reached, or of drafts approved: a draft that was rewritten three times
     * contributes the citations of all three versions, and a draft nobody ever sent contributes its
     * own. The screen's wording has to carry that, which is why the column is 「답변 근거」 rather
     * than 「사용」.
     *
     * <p>Rows whose {@code sourceId} is null are skipped rather than grouped under a null key: an
     * order fact cites no document ({@link InquiryDraftEvidence#KIND_ORDER_FACT}), and counting it
     * as an unattributable citation would put a number on a row that does not exist.
     *
     * <p><b>{@code kind} is in the grouping, and that costs nothing.</b> The screen needs two things
     * from this table — how often each document was cited, and how many ORG_POLICY citations name a
     * rule that is no longer in the list — and grouping one level finer answers both from the same
     * pass rather than from a second query that would read the same rows again.
     */
    @Query("""
            select e.sourceId, e.kind, count(e), max(e.createdAt)
            from InquiryDraftEvidence e
            where e.orgId = :orgId and e.sourceId is not null
            group by e.sourceId, e.kind
            """)
    List<Object[]> citationsBySource(@Param("orgId") UUID orgId);
}
