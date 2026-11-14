package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A document the seller turned off stands for nothing — product or company alike</b> (2026-11-14 manual eval F3).
 *
 * <p>The inquiry recheck read a company policy's 「사용 안 함」 and only whether a product document still existed, so a
 * draft whose product manual had been retired was approved as if it still stood on it. The review recheck has read both
 * all along. Each case here runs twice, once per kind, and the two must agree; a seller's edit, which has no citations
 * of its own, is read against the version it was edited from and must agree as well.
 */
class InquiryDraftRecheckRetiredSourceTest {

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID productDocId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
    private final AnswerStyleService styles = mock(AnswerStyleService.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private final ProductKnowledgeSource manual = new ProductKnowledgeSource();
    private final OrgKnowledgeSource policy = new OrgKnowledgeSource();
    private InquiryDraftRecheck recheck;

    private static final String REPLY = "식기세척기 사용은 권하지 않아요. 손세척해 주세요.";

    @BeforeEach
    void setUp() {
        recheck = new InquiryDraftRecheck(inquiries, evidence, snippets, orgSources, productSources, styles);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        Inquiry inquiry = new Inquiry();
        inquiry.setOrgId(org);
        inquiry.setProductId(productId);
        inquiry.setBody("식기세척기에 넣어도 되나요?");
        when(inquiries.findById(item.getInquiryId())).thenReturn(Optional.of(inquiry));
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());

        manual.setId(productDocId);
        manual.setOrgId(org);
        manual.setProductId(productId);
        manual.setTitle("텀블러 안내");
        manual.setBody("식기세척기 사용은 권하지 않습니다. 손세척해 주세요.");
        manual.setActive(true);
        when(productSources.findById(productDocId)).thenReturn(Optional.of(manual));
        when(productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(org, productId)).thenReturn(List.of(manual));

        policy.setId(policyId);
        policy.setOrgId(org);
        policy.setTitle("세척 안내");
        policy.setBody("식기세척기 사용은 권하지 않습니다. 손세척해 주세요.");
        policy.setActive(true);
        when(orgSources.findById(policyId)).thenReturn(Optional.of(policy));
        when(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(policy));

        // The screen's reader is the snippets bean; here it reports every passage as still readable, so what the recheck
        // concludes comes from its own reading of the documents' state.
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView("X", "x", "x", null, null, null, "본문", false));
        // v2 is the seller's edit: no citations of its own.
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(eq(item.getId()), eq(2))).thenReturn(List.of());
    }

    private void cites(String kind, UUID sourceId, String title) {
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(kind);
        row.setSourceId(sourceId);
        row.setTitle(title);
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), 1)).thenReturn(List.of(row));
    }

    private void citesTheManual() {
        cites(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE, productDocId, "텀블러 안내");
    }

    private void citesThePolicy() {
        cites(InquiryDraftEvidence.KIND_ORG_POLICY, policyId, "세척 안내");
    }

    @Test
    @DisplayName("while the cited document is in use, neither kind is a changed citation")
    void aDocumentInUseStands() {
        citesTheManual();
        assertThat(recheck.check(item, 1, REPLY, "MODEL").codes()).doesNotContain(ReviewDraftRecheck.SOURCE_CHANGED,
                ReviewDraftRecheck.SOURCE_RETIRED);
        citesThePolicy();
        assertThat(recheck.check(item, 1, REPLY, "MODEL").codes()).doesNotContain(ReviewDraftRecheck.SOURCE_CHANGED);
    }

    @Test
    @DisplayName("a retired product manual is a changed citation, exactly as a retired company policy is")
    void aRetiredProductDocumentIsChangedAsAPolicyIs() {
        citesTheManual();
        manual.setActive(false);
        InquiryDraftRecheck.Result product = recheck.check(item, 1, REPLY, "MODEL");
        manual.setActive(true);

        citesThePolicy();
        policy.setActive(false);
        InquiryDraftRecheck.Result company = recheck.check(item, 1, REPLY, "MODEL");

        assertThat(product.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED);
        assertThat(product.changed()).containsExactly("텀블러 안내");
        assertThat(product.retired()).containsExactly("텀블러 안내");
        assertThat(company.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED);
        assertThat(company.changed()).containsExactly("세척 안내");
        assertThat(company.retired()).containsExactly("세척 안내");
    }

    @Test
    @DisplayName("a seller's edit of a draft whose product manual was retired is read against it and held alike")
    void aSellersEditInheritsTheRetirement() {
        citesTheManual();
        manual.setActive(false);
        InquiryDraftRecheck.Result edited = recheck.check(item, 2, "식기세척기는 권하지 않아요. 손으로 씻어 주세요.", "SELLER");
        assertThat(edited.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED);
        assertThat(edited.changed()).containsExactly("텀블러 안내");
        assertThat(edited.retired()).containsExactly("텀블러 안내");
    }

    @Test
    @DisplayName("a deleted product manual is still a changed citation (unchanged behaviour)")
    void aDeletedProductDocumentIsChanged() {
        citesTheManual();
        when(productSources.findById(productDocId)).thenReturn(Optional.empty());
        InquiryDraftRecheck.Result deleted = recheck.check(item, 1, REPLY, "MODEL");
        assertThat(deleted.changed()).containsExactly("텀블러 안내");
        assertThat(deleted.retired()).as("deleted is withdrawn too").containsExactly("텀블러 안내");
    }

    @Test
    @DisplayName("a document whose wording changed (its passage re-cut) is changed but not withdrawn")
    void anEditedDocumentIsChangedNotWithdrawn() {
        citesTheManual();
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView("PRODUCT_KNOWLEDGE", "상품 정보", "텀블러 안내", null, productDocId, null, null, true));
        InquiryDraftRecheck.Result edited = recheck.check(item, 1, REPLY, "MODEL");
        assertThat(edited.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED)
                .doesNotContain(ReviewDraftRecheck.SOURCE_RETIRED);
        assertThat(edited.retired()).isEmpty();
    }

    @Test
    @DisplayName("each withdrawal carries its moment, so turning a document back on and off again is a new one")
    void aWithdrawalCarriesItsMoment() {
        citesTheManual();
        manual.setActive(false);
        manual.setUpdatedAt(java.time.Instant.parse("2026-11-14T10:00:00Z"));
        InquiryDraftRecheck.Result first = recheck.check(item, 1, REPLY, "MODEL");
        manual.setUpdatedAt(java.time.Instant.parse("2026-11-14T11:00:00Z"));
        InquiryDraftRecheck.Result second = recheck.check(item, 1, REPLY, "MODEL");
        assertThat(first.withdrawals()).containsExactly(productDocId + "@2026-11-14T10:00:00Z");
        assertThat(second.withdrawals()).containsExactly(productDocId + "@2026-11-14T11:00:00Z");
        assertThat(first.retired()).isEqualTo(second.retired());

        when(productSources.findById(productDocId)).thenReturn(Optional.empty());
        assertThat(recheck.check(item, 1, REPLY, "MODEL").withdrawals()).containsExactly(productDocId + "@deleted");
    }
}
