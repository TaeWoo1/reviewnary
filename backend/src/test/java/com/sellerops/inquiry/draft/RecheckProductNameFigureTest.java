package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Approval and send read the product's own name as the generator does</b> (2026-11-16 B-plan q09 S4, Codex review):
 * the generator no longer asks for the 500 of 「스테인리스 텀블러 500ml」 and keeps no ground for it, so the recheck — which
 * reads every number of the text — must not bring it back as unproven: beside a figure that has its ground, or in the
 * seller's edit. The same exact-name rule; 500원 written elsewhere is still unproven.
 */
class RecheckProductNameFigureTest {

    private final UUID org = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final AnswerStyleService styles = mock(AnswerStyleService.class);
    private final InquiryFigureGrounds grounds = mock(InquiryFigureGrounds.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private final OrgKnowledgeSource policy = new OrgKnowledgeSource();
    private InquiryDraftRecheck recheck;

    static final String RULE = "수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.";

    @BeforeEach
    void setUp() {
        recheck = new InquiryDraftRecheck(inquiries, evidence, snippets, orgSources,
                mock(ProductKnowledgeSourceRepository.class), styles);
        recheck.setGrounds(grounds);
        recheck.setProducts(products);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        Inquiry inquiry = new Inquiry();
        inquiry.setOrgId(org);
        inquiry.setProductId(productId);
        inquiry.setBody("뚜껑만 따로 살 수 있나요? 교환은 며칠 안에 되나요?");
        when(inquiries.findById(item.getInquiryId())).thenReturn(Optional.of(inquiry));
        Product p = new Product();
        p.setOrgId(org);
        p.setName("[합성] 스테인리스 텀블러 500ml");
        when(products.findById(productId)).thenReturn(Optional.of(p));
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        policy.setId(policyId);
        policy.setOrgId(org);
        policy.setTitle("교환 안내");
        policy.setBody(RULE);
        policy.setActive(true);
        when(orgSources.findById(policyId)).thenReturn(Optional.of(policy));
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView("ORG_POLICY", "운영 정책", "교환 안내", null, policyId, null, "", false));
        InquiryDraftEvidence cited = new InquiryDraftEvidence();
        cited.setKind(InquiryDraftEvidence.KIND_ORG_POLICY);
        cited.setSourceId(policyId);
        cited.setTitle("교환 안내");
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), 1)).thenReturn(List.of(cited));
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(eq(item.getId()), eq(2))).thenReturn(List.of());
        when(grounds.of(any(), anyInt())).thenReturn(List.of());
        // v1's check grounded 7일 in the policy; nothing for the product's name (the generator never asked).
        InquiryDraftFigure seven = new InquiryDraftFigure();
        seven.setValue("7");
        seven.setUnit("일");
        seven.setReplyClause("교환은 수령 후 7일 이내에 신청하실 수 있습니다");
        seven.setGroundKind("ORG_POLICY");
        seven.setSourceId(policyId);
        seven.setGroundClause(RULE);
        when(grounds.of(item.getId(), 1)).thenReturn(List.of(seven));
        when(grounds.currentText(org, InquiryDraftFigure.Ground.ORG_POLICY, policyId, null)).thenReturn("교환 안내\n" + RULE);
    }

    @Test
    @DisplayName("a model draft naming the product beside a grounded 7일 passes the approval and send recheck")
    void theNameBesideAGroundedFigure() {
        InquiryDraftRecheck.Result r = recheck.check(item, 1,
                "스테인리스 텀블러 500ml 뚜껑은 따로 판매하지 않습니다. 교환은 수령 후 7일 이내에 신청하실 수 있습니다.", "MODEL");
        assertThat(r.figures()).isEmpty();
        assertThat(r.codes()).doesNotContain(InquiryDraftRecheck.FIGURE_UNVERIFIED, InquiryDraftRecheck.FIGURE_CHANGED);
    }

    @Test
    @DisplayName("the seller's edit naming the product — as recorded or without the [합성] marker — passes too")
    void theSellersEdit() {
        assertThat(recheck.check(item, 2, "[합성] 스테인리스 텀블러 500ml 뚜껑은 따로 판매하지 않아요. 교환은 수령 후 7일 이내에 신청하실 수 "
                + "있습니다.", "SELLER").figures()).isEmpty();
        assertThat(recheck.check(item, 2, "스테인리스 텀블러 500ml 뚜껑은 따로 판매하지 않아요.", "SELLER").figures()).isEmpty();
    }

    @Test
    @DisplayName("contrast: 500원 · 500일 written outside the name are unproven, in the model's draft and the seller's edit")
    void theSameNumberElsewhereStaysUnproven() {
        assertThat(recheck.check(item, 2, "스테인리스 텀블러 500ml 뚜껑은 500원에 따로 보내 드려요.", "SELLER").figures())
                .extracting(InquiryDraftRecheck.FigureNote::figure).containsExactly("500원");
        assertThat(recheck.check(item, 1, "스테인리스 텀블러 500ml는 500일 동안 보온을 보장합니다. 교환은 수령 후 7일 이내에 신청하실 수 "
                + "있습니다.", "MODEL").figures()).extracting(InquiryDraftRecheck.FigureNote::figure).containsExactly("500일");
    }

    @Test
    @DisplayName("contrast: with no product recorded, the name's 500ml is read as any number, as before")
    void noProductNoName() {
        recheck.setProducts(null);
        assertThat(recheck.check(item, 2, "스테인리스 텀블러 500ml 뚜껑은 따로 판매하지 않아요.", "SELLER").figures())
                .extracting(InquiryDraftRecheck.FigureNote::figure).containsExactly("500ml");
    }
}
