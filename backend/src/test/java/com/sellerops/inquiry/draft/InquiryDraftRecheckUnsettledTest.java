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
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The server refuses an answer that states an unsettled value as fact</b> (2026-11-15, 설계 2절): read at approval and at
 * registration from the material as it stands then — never from what was attached when the draft was written.
 */
class InquiryDraftRecheckUnsettledTest {

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final KnowledgeCurrencyService currency = mock(KnowledgeCurrencyService.class);
    private final KnowledgeScheduleRepository schedules = mock(KnowledgeScheduleRepository.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private InquiryDraftRecheck recheck;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final List<KnowledgeState.Source> sources = List.of(
            new KnowledgeState.Source(a, "ORG", null, "ORG", "교환·반품 안내", 1, null,
                    ClaimReader.read("상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.")),
            new KnowledgeState.Source(b, "ORG", null, "ORG", "옛 공지", 1, null,
                    ClaimReader.read("교환은 수령 후 14일 이내에 신청해 주세요.")));

    @BeforeEach
    void setUp() {
        AnswerStyleService styles = mock(AnswerStyleService.class);
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        recheck = new InquiryDraftRecheck(inquiries, evidence, snippets, orgSources,
                mock(ProductKnowledgeSourceRepository.class), styles);
        recheck.setCurrency(currency, schedules);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        Inquiry inquiry = new Inquiry();
        inquiry.setOrgId(org);
        inquiry.setProductId(productId);
        inquiry.setBody("교환은 받고 며칠 안에 신청해야 하나요?");
        when(inquiries.findById(item.getInquiryId())).thenReturn(Optional.of(inquiry));
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(any(), any(Integer.class))).thenReturn(List.of());
    }

    private void now(KnowledgeState state) {
        when(currency.stateFor(eq(org), eq(productId), any(), any())).thenReturn(state);
    }

    @Test
    @DisplayName("while the exchange window is unsettled, 「14일」 is refused with both values named; leaving it out passes")
    void unsettledIsRefused() {
        now(KnowledgeState.of(sources, List.of(), LocalDate.of(2026, 11, 14), null));
        InquiryDraftRecheck.Result r = recheck.check(item, 1, "교환은 상품을 받으신 뒤 14일 안에 신청해 주세요.", "SELLER");
        assertThat(r.codes()).contains(ReviewDraftRecheck.UNSETTLED_VALUE);
        assertThat(r.unsettled()).singleElement().asString().contains("7일").contains("14일");
        assertThat(r.unsettledSaid()).containsExactly("14일");
        assertThat(recheck.check(item, 1, "교환을 원하시면 사진과 함께 남겨 주세요.", "SELLER").codes())
                .doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("recomputed at the moment: once the seller settles it, the same text with the current value passes")
    void recomputedAtApproval() {
        now(KnowledgeState.of(sources, List.of(new KnowledgeState.Replacement(UUID.randomUUID(), a, b,
                com.sellerops.knowledge.claim.ClaimAttribute.EXCHANGE_WINDOW,
                com.sellerops.knowledge.claim.ClaimCondition.GENERAL)), LocalDate.of(2026, 11, 14), null));
        assertThat(recheck.check(item, 1, "교환은 수령 후 7일 안에 신청해 주세요.", "SELLER").codes())
                .doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
        assertThat(recheck.check(item, 1, "교환은 수령 후 14일 안에 신청해 주세요.", "SELLER").codes())
                .as("the replaced value").contains(ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("a cited schedule line turned off is withdrawn, exactly as a document is")
    void aWithdrawnScheduleLine() {
        now(KnowledgeState.EMPTY);
        UUID line = UUID.randomUUID();
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(InquiryDraftEvidence.KIND_SCHEDULE);
        row.setSourceId(line);
        row.setTitle("입고·출고 일정");
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), 1)).thenReturn(List.of(row));
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView(InquiryDraftEvidence.KIND_SCHEDULE, "입고·출고 일정", "입고·출고 일정", null, line, null,
                        null, true));
        KnowledgeSchedule off = new KnowledgeSchedule();
        off.setActive(false);
        when(schedules.findById(line)).thenReturn(Optional.of(off));
        InquiryDraftRecheck.Result r = recheck.check(item, 1, "민트는 입고 일정에 맞춰 출고돼요.", "MODEL");
        assertThat(r.codes()).contains(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED);
        assertThat(r.retired()).containsExactly("입고·출고 일정");
    }

    @Test
    @DisplayName("approval agrees with the state the seller set: a replaced 14일 elsewhere is not a policy saying otherwise")
    void approvalAgreesWithTheState() {
        com.sellerops.knowledge.org.OrgKnowledgeSource current = new com.sellerops.knowledge.org.OrgKnowledgeSource();
        current.setId(a);
        current.setOrgId(org);
        current.setTitle("교환·반품 안내");
        current.setBody("상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.");
        com.sellerops.knowledge.org.OrgKnowledgeSource old = new com.sellerops.knowledge.org.OrgKnowledgeSource();
        old.setId(b);
        old.setOrgId(org);
        old.setTitle("옛 공지");
        old.setBody("교환은 수령 후 14일 이내에 신청해 주세요.");
        when(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(current, old));
        String reply = "교환은 상품을 받으신 뒤 7일 안에 신청해 주세요.";
        recheck.setCurrency(null, null);
        assertThat(recheck.check(item, 1, reply, "SELLER").codes()).as("word by word, the old notice says otherwise")
                .contains(ReviewDraftRecheck.POLICY_CONFLICT);
        recheck.setCurrency(currency, schedules);
        now(KnowledgeState.of(sources, List.of(new KnowledgeState.Replacement(UUID.randomUUID(), a, b,
                com.sellerops.knowledge.claim.ClaimAttribute.EXCHANGE_WINDOW,
                com.sellerops.knowledge.claim.ClaimCondition.GENERAL)), LocalDate.of(2026, 11, 14), null));
        assertThat(recheck.check(item, 1, reply, "SELLER").codes()).as("value by value, 7일 is current")
                .doesNotContain(ReviewDraftRecheck.POLICY_CONFLICT, ReviewDraftRecheck.UNSETTLED_VALUE);
    }
}
