package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunk;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeChunk;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A citation whose passage is gone says so</b> (2026-10-28 evaluation): a draft whose policy had been deleted sat
 * waiting for approval with nothing on screen saying the text it was written from no longer existed.
 */
class DraftEvidenceGoneTest {

    private final ProductKnowledgeChunkRepository productChunks = mock(ProductKnowledgeChunkRepository.class);
    private final OrgKnowledgeChunkRepository orgChunks = mock(OrgKnowledgeChunkRepository.class);
    private final AnswerMemoryRepository memories = mock(AnswerMemoryRepository.class);
    private final DraftEvidenceSnippets snippets = new DraftEvidenceSnippets(productChunks, orgChunks, memories);

    @Test
    @DisplayName("a policy passage that was deleted or re-cut since the draft is gone; one still there is not")
    void aPassageThatNoLongerExistsIsGone() {
        UUID kept = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        OrgKnowledgeChunk chunk = new OrgKnowledgeChunk();
        chunk.setContent("미개봉 상품은 수령 후 7일 안에 교환할 수 있어요.");
        when(orgChunks.findById(kept)).thenReturn(Optional.of(chunk));
        when(orgChunks.findById(deleted)).thenReturn(Optional.empty());

        DraftEvidenceView there = snippets.viewOf(InquiryDraftEvidence.KIND_ORG_POLICY, UUID.randomUUID(), kept, "교환", "loc");
        DraftEvidenceView missing = snippets.viewOf(InquiryDraftEvidence.KIND_ORG_POLICY, UUID.randomUUID(), deleted, "교환", "loc");

        assertThat(there.gone()).isFalse();
        assertThat(there.snippet()).contains("7일");
        assertThat(missing.gone()).isTrue();
        assertThat(missing.snippet()).isNull();
        // The title and locator are still a true record of what the drafter was shown.
        assertThat(missing.title()).isEqualTo("교환");
    }

    @Test
    @DisplayName("an order fact has no passage to lose, and a citation that never pointed at one is not gone")
    void nothingToLoseIsNeverGone() {
        assertThat(snippets.viewOf(InquiryDraftEvidence.KIND_ORDER_FACT, null, null, "주문", "loc").gone()).isFalse();
        assertThat(snippets.viewOf(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE, UUID.randomUUID(), null, "t", "l").gone())
                .isFalse();
    }

    @Test
    @DisplayName("a citation of a document the seller turned off is gone and shows no excerpt — product and company alike")
    void aRetiredDocumentIsGone() {
        ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
        OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
        snippets.setSources(productSources, orgSources);
        UUID manualId = UUID.randomUUID();
        UUID manualChunk = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID policyChunk = UUID.randomUUID();
        ProductKnowledgeChunk passage = new ProductKnowledgeChunk();
        passage.setContent("식기세척기 사용은 권하지 않습니다.");
        when(productChunks.findById(manualChunk)).thenReturn(Optional.of(passage));
        OrgKnowledgeChunk rule = new OrgKnowledgeChunk();
        rule.setContent("수령 후 7일 안에 교환할 수 있어요.");
        when(orgChunks.findById(policyChunk)).thenReturn(Optional.of(rule));
        ProductKnowledgeSource manual = new ProductKnowledgeSource();
        manual.setActive(true);
        OrgKnowledgeSource policy = new OrgKnowledgeSource();
        policy.setActive(true);
        when(productSources.findById(manualId)).thenReturn(Optional.of(manual));
        when(orgSources.findById(policyId)).thenReturn(Optional.of(policy));

        assertThat(snippets.viewOf(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE, manualId, manualChunk, "안내", "l").gone())
                .isFalse();
        assertThat(snippets.viewOf(InquiryDraftEvidence.KIND_ORG_POLICY, policyId, policyChunk, "교환", "l").gone())
                .isFalse();

        manual.setActive(false);
        policy.setActive(false);
        DraftEvidenceView product = snippets.viewOf(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE, manualId, manualChunk,
                "안내", "l");
        DraftEvidenceView company = snippets.viewOf(InquiryDraftEvidence.KIND_ORG_POLICY, policyId, policyChunk, "교환", "l");
        // The passages are still stored — reading them alone said 「current」 (2026-11-14 manual eval F3).
        assertThat(product.gone()).isTrue();
        assertThat(product.snippet()).isNull();
        assertThat(product.title()).isEqualTo("안내");
        assertThat(company.gone()).isTrue();
        assertThat(company.snippet()).isNull();
    }
}
