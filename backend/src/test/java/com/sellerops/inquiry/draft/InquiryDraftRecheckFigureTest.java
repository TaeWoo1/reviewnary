package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The inquiry recheck reads figures (2026-11-01 review: it used to drop every figure finding, so an approved
 * 「수령 후 7일 이내 교환 가능합니다」 over a policy now saying 14일 would have been sent). And it reads a version whoever
 * wrote it: a seller's edit has no citations of its own and is read against the version it was edited from.
 */
class InquiryDraftRecheckFigureTest {

    private final UUID org = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final AnswerStyleService styles = mock(AnswerStyleService.class);
    private final InquiryFigureGrounds grounds = mock(InquiryFigureGrounds.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private InquiryDraftRecheck recheck;
    private final OrgKnowledgeSource policy = new OrgKnowledgeSource();

    @BeforeEach
    void setUp() {
        recheck = new InquiryDraftRecheck(mock(InquiryRepository.class), evidence, snippets, orgSources,
                mock(ProductKnowledgeSourceRepository.class), styles);
        recheck.setGrounds(grounds);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        policy.setId(policyId);
        policy.setOrgId(org);
        policy.setTitle("교환 안내");
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
    }

    private InquiryDraftFigure kept(String clause) {
        InquiryDraftFigure row = new InquiryDraftFigure();
        row.setValue("7");
        row.setUnit("일");
        row.setReplyClause("수령 후 7일 이내 교환 가능합니다");
        row.setGroundKind("ORG_POLICY");
        row.setSourceId(policyId);
        row.setGroundClause(clause);
        return row;
    }

    @Test
    void theReviewsSyntheticCaseIsHeldAsWrong() {
        policy.setBody("수령 후 14일 이내 교환 가능합니다.");
        when(grounds.of(item.getId(), 1)).thenReturn(List.of(kept("수령 후 7일 이내 교환 가능합니다")));
        when(grounds.currentText(org, InquiryDraftFigure.Ground.ORG_POLICY, policyId, null))
                .thenReturn("교환 안내\n" + policy.getBody());
        InquiryDraftRecheck.Result result = recheck.check(item, 1, "수령 후 7일 이내 교환 가능합니다.", "MODEL");
        assertThat(result.codes()).contains(InquiryDraftRecheck.FIGURE_CHANGED);
        assertThat(result.figures()).singleElement().satisfies(f -> {
            assertThat(f.figure()).isEqualTo("7일");
            assertThat(f.was()).isEqualTo("수령 후 7일 이내 교환 가능합니다");
        });
    }

    @Test
    void anOlderDraftWithoutGroundsIsUnverifiedOnceItsSourceMoved() {
        policy.setBody("수령 후 14일 이내 교환 가능합니다.");
        // Written before figure grounds were kept: nothing says where 7일 came from, and the policy it cited moved.
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView("ORG_POLICY", "운영 정책", "교환 안내", null, policyId, null, null, true));
        InquiryDraftRecheck.Result result = recheck.check(item, 1, "수령 후 7일 이내 교환 가능합니다.", "MODEL");
        assertThat(result.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED, InquiryDraftRecheck.FIGURE_UNVERIFIED);
    }

    @Test
    void anOlderDraftWhoseSourceDidNotMoveStands() {
        policy.setBody("수령 후 7일 이내 교환 가능합니다.");
        InquiryDraftRecheck.Result result = recheck.check(item, 1, "주문 후 3일 이내 출고됩니다.", "MODEL");
        assertThat(result.codes()).doesNotContain(InquiryDraftRecheck.FIGURE_UNVERIFIED,
                InquiryDraftRecheck.FIGURE_CHANGED);
    }

    @Test
    void aSellersEditKeepsTheCheckOnlyWhereItKeptTheClause() {
        policy.setBody("수령 후 7일 이내 교환 가능합니다.");
        when(grounds.of(item.getId(), 1)).thenReturn(List.of(kept("수령 후 7일 이내 교환 가능합니다")));
        when(grounds.currentText(org, InquiryDraftFigure.Ground.ORG_POLICY, policyId, null))
                .thenReturn("교환 안내\n" + policy.getBody());
        // v2 is the seller's: no citations or grounds of its own, so v1's are read — for the clause v1 checked.
        InquiryDraftRecheck.Result standing = recheck.check(item, 2,
                "주문해 주셔서 감사합니다. 수령 후 7일 이내 교환 가능합니다.", "SELLER");
        assertThat(standing.passed()).isTrue();
        // The same number in a clause the seller rewrote did not get v1's check (2026-11-01 third review).
        InquiryDraftRecheck.Result rewritten = recheck.check(item, 2, "7일 지나도 교환해 드려요.", "SELLER");
        assertThat(rewritten.codes()).contains(InquiryDraftRecheck.FIGURE_UNVERIFIED);
        assertThat(rewritten.figures()).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo(InquiryDraftRecheck.EDITED);
            assertThat(f.was()).isEqualTo("수령 후 7일 이내 교환 가능합니다");
        });
        InquiryDraftRecheck.Result typed = recheck.check(item, 2, "수령 후 30일 이내 교환 가능합니다.", "SELLER");
        assertThat(typed.codes()).contains(InquiryDraftRecheck.FIGURE_UNVERIFIED);
    }

    @Test
    void aCustomersNumberSaidBackIsNotTheProductsSpecOnceTheSellerRewordsIt() {
        // The review's second case: v1 said the customer's 20가닥 back; the seller turned it into a product fact.
        InquiryDraftFigure saidBack = new InquiryDraftFigure();
        saidBack.setValue("20");
        saidBack.setUnit("가닥");
        saidBack.setReplyClause("문의 주신 20가닥 기준으로 말씀드리면");
        saidBack.setGroundKind("QUESTION");
        saidBack.setVoice("CUSTOMER");
        when(grounds.of(item.getId(), 1)).thenReturn(List.of(saidBack));
        InquiryDraftRecheck.Result asWritten = recheck.check(item, 2,
                "문의 주신 20가닥 기준으로 말씀드리면, 한 번에 넣기는 어렵습니다.", "SELLER");
        assertThat(asWritten.codes()).doesNotContain(InquiryDraftRecheck.FIGURE_UNVERIFIED);
        InquiryDraftRecheck.Result promoted = recheck.check(item, 2, "이 상품은 20가닥으로 구성되어 있습니다.", "SELLER");
        assertThat(promoted.codes()).contains(InquiryDraftRecheck.FIGURE_UNVERIFIED);
    }
}
