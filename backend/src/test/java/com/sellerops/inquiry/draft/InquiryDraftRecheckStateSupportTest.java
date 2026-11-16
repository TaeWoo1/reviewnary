package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * <b>Reading a number is not checking it</b> (2026-11-15 review P1). The value-level state may set a word-level 「another
 * document says another number」 aside only for a figure it stands behind: a current value in use says it, and every value
 * that says otherwise lost to one that says it. A single current 7일 against a reply's 14일, a third value after a
 * replacement, nothing stated, another unit, another condition, a product document against the company's, and who pays —
 * each keeps the finding it had before the state was read. And the customer's order date the search knew is the one the
 * approval check reads (review P1/P2), never the receipt day or today in its place.
 */
class InquiryDraftRecheckStateSupportTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final KnowledgeCurrencyService currency = mock(KnowledgeCurrencyService.class);
    private final KnowledgeScheduleRepository schedules = mock(KnowledgeScheduleRepository.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private final List<OrgKnowledgeSource> orgDocs = new ArrayList<>();
    private final List<ProductKnowledgeSource> productDocs = new ArrayList<>();
    private final List<KnowledgeState.Source> stateSources = new ArrayList<>();
    private final List<KnowledgeState.Replacement> replacements = new ArrayList<>();
    private InquiryDraftRecheck recheck;

    @BeforeEach
    void setUp() {
        AnswerStyleService styles = mock(AnswerStyleService.class);
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        recheck = new InquiryDraftRecheck(inquiries, evidence, snippets, orgSources, productSources, styles);
        recheck.setCurrency(currency, schedules);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        Inquiry inquiry = new Inquiry();
        inquiry.setOrgId(org);
        inquiry.setProductId(productId);
        inquiry.setBody("교환은 받고 며칠 안에 신청해야 하나요?");
        inquiry.setReceivedAt(Instant.parse("2026-11-14T01:00:00Z"));
        when(inquiries.findById(item.getInquiryId())).thenReturn(Optional.of(inquiry));
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(any(), any(Integer.class))).thenReturn(List.of());
        when(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(orgDocs);
        when(productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(org, productId)).thenReturn(productDocs);
        when(currency.stateFor(eq(org), eq(productId), any(), any())).thenAnswer(inv -> KnowledgeState.of(
                List.copyOf(stateSources), List.copyOf(replacements), inv.getArgument(2), inv.getArgument(3)));
    }

    private UUID orgDoc(String title, String body) {
        return orgDoc(title, body, null);
    }

    private UUID orgDoc(String title, String body, LocalDate from) {
        OrgKnowledgeSource s = new OrgKnowledgeSource();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setTitle(title);
        s.setBody(body);
        orgDocs.add(s);
        stateSources.add(new KnowledgeState.Source(s.getId(), "ORG", null, "ORG", title, 1, from,
                ClaimReader.read(body, title)));
        return s.getId();
    }

    private void productDoc(String title, String body) {
        ProductKnowledgeSource s = new ProductKnowledgeSource();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setProductId(productId);
        s.setTitle(title);
        s.setBody(body);
        productDocs.add(s);
        stateSources.add(new KnowledgeState.Source(s.getId(), "PRODUCT", productId, "PRODUCT:" + productId, title, 1,
                null, ClaimReader.read(body, title)));
    }

    private List<String> withState(String reply) {
        recheck.setCurrency(currency, schedules);
        return recheck.check(item, 1, reply, "SELLER").codes();
    }

    private List<String> withoutState(String reply) {
        recheck.setCurrency(null, null);
        try {
            return recheck.check(item, 1, reply, "SELLER").codes();
        } finally {
            recheck.setCurrency(currency, schedules);
        }
    }

    @Test
    @DisplayName("one current policy says 7일 and the reply says 14일: POLICY_CONFLICT stays (the review's counter-example)")
    void aSingleCurrentPolicyStillConflicts() {
        orgDoc("현행 정책", "교환은 수령 후 7일 이내에 신청해 주세요.");
        String reply = "교환은 수령 후 14일 이내에 신청해 주세요.";
        assertThat(withoutState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState("교환은 수령 후 7일 이내에 신청해 주세요.")).doesNotContain(ReviewDraftRecheck.POLICY_CONFLICT,
                ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    void splitCostBearerConflictCannotBecomeAnException() {
        orgDoc("반품 배송비", "단순 변심 반품의 왕복 배송비 6,000원은 고객님이 부담합니다.");
        String reply = "단순 변심 반품은 수령 후 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 저희가 부담합니다.";
        assertThat(withoutState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    @DisplayName("after 7일 replaced 14일, a third value 21일 is a conflict, the replaced 14일 is held, 7일 passes")
    void aThirdValueAfterReplacement() {
        UUID now = orgDoc("교환·반품 안내", "교환은 수령 후 7일 이내에 신청해 주세요.");
        UUID old = orgDoc("옛 공지", "교환은 수령 후 14일 이내에 신청해 주세요.");
        replacements.add(new KnowledgeState.Replacement(UUID.randomUUID(), now, old, ClaimAttribute.EXCHANGE_WINDOW,
                ClaimCondition.GENERAL));
        assertThat(withState("교환은 수령 후 21일 안에 신청해 주세요.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState("교환은 수령 후 14일 안에 신청해 주세요.")).contains(ReviewDraftRecheck.UNSETTLED_VALUE);
        assertThat(withState("교환은 수령 후 7일 안에 신청해 주세요.")).doesNotContain(ReviewDraftRecheck.POLICY_CONFLICT,
                ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("nothing stated, another unit or another condition is never support: the old findings all stay")
    void noGroundIsNotSupport() {
        orgDoc("배송 안내", "출고 후 보통 1~2일 안에 도착합니다.");
        orgDoc("제작 안내", "교환은 수령 후 영업일 7일 이내에 신청해 주세요.");
        orgDoc("반품 배송비", "단순 변심 반품 배송비는 왕복 6,000원입니다. 불량 반품은 배송비 없이 처리합니다.");
        for (String reply : List.of("교환은 14일 안에 신청해 주세요.", "교환은 7일 안에 신청해 주세요.", "반품 배송비는 6,000원입니다.")) {
            assertThat(withState(reply)).as(reply).containsAll(withoutState(reply));
        }
    }

    @Test
    @DisplayName("a product document is not the company's exception: 14일 there and 7일 here hold either reply")
    void aProductDocumentIsNotAnException() {
        orgDoc("교환·반품 안내", "교환은 수령 후 7일 이내에 신청해 주세요.");
        productDoc("상품 안내", "이 상품은 교환을 수령 후 14일 이내에 신청할 수 있습니다.");
        assertThat(withState("교환은 수령 후 14일 이내에 신청해 주세요.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState("교환은 수령 후 7일 이내에 신청해 주세요.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    @DisplayName("who pays is never set aside: the right amount said as the seller's cost still conflicts")
    void whoPaysIsNotAnAmount() {
        orgDoc("교환·반품 안내", "단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다.");
        assertThat(withState("단순 변심 반품의 왕복 배송비 6,000원은 저희가 부담합니다."))
                .contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(withState("단순 변심 반품은 왕복 배송비 6,000원을 고객님이 부담하세요."))
                .doesNotContain(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    @DisplayName("the order date the search knew reaches the approval check; an unknown one stays unknown")
    void theOrderDateTravels() {
        orgDoc("새 정책", "교환은 수령 후 7일 이내에 신청해 주세요.", LocalDate.of(2026, 11, 1));
        orgDoc("옛 정책", "교환은 수령 후 14일 이내에 신청해 주세요.", LocalDate.of(2025, 6, 1));
        String reply = "교환은 수령 후 7일 안에 신청해 주세요.";
        assertThat(withState(reply)).as("no order date: 적용일 alone does not settle it")
                .contains(ReviewDraftRecheck.UNSETTLED_VALUE);
        ArgumentCaptor<LocalDate> order = ArgumentCaptor.forClass(LocalDate.class);
        verify(currency, atLeastOnce()).stateFor(eq(org), eq(productId), any(), order.capture());
        assertThat(order.getAllValues()).as("neither the receipt day nor today stands in").containsOnlyNulls();

        cite(InquiryDraftComposer.ORDERED + "2026-11-05");
        assertThat(withState(reply)).doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
        cite(InquiryDraftComposer.ORDERED + "2026-10-20");
        assertThat(withState(reply)).as("ordered before the new rule: 7일 is the replaced one for this order")
                .contains(ReviewDraftRecheck.UNSETTLED_VALUE);
        assertThat(withState("교환은 수령 후 14일 안에 신청해 주세요.")).doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("a check made after the 적용일 began: without an order date it holds what passed before; with one, it decides")
    void aLaterCheck() {
        LocalDate today = KnowledgeCurrencyService.today();
        orgDoc("옛 정책", "교환은 수령 후 7일 이내에 신청해 주세요.", LocalDate.of(2025, 6, 1));
        UUID next = orgDoc("새 정책", "교환은 수령 후 14일 이내에 신청해 주세요.", today.plusDays(1));
        String reply = "교환은 수령 후 7일 안에 신청해 주세요.";
        assertThat(withState(reply)).as("the new rule has not begun").doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
        // The same draft checked again once the new rule has begun.
        stateSources.replaceAll(s -> s.id().equals(next) ? new KnowledgeState.Source(s.id(), s.kind(), s.productId(),
                s.scopeKey(), s.title(), s.rank(), today.minusDays(1), s.claims()) : s);
        assertThat(withState(reply)).as("begun, order unknown").contains(ReviewDraftRecheck.UNSETTLED_VALUE);
        cite(InquiryDraftComposer.ORDERED + today.minusDays(3));
        assertThat(withState(reply)).as("ordered before it began").doesNotContain(ReviewDraftRecheck.UNSETTLED_VALUE);
        cite(InquiryDraftComposer.ORDERED + today);
        assertThat(withState(reply)).as("ordered after it began").contains(ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("the composer keeps the order's date on its citation, and nothing when the order names none")
    void theComposerKeepsTheDate() {
        com.sellerops.order.fact.OrderFact dated = new com.sellerops.order.fact.OrderFact(null, null, null, "NAVER", null,
                null, null, null, null, null, null, LocalDate.of(2026, 11, 5), null);
        assertThat(InquiryDraftComposer.orderedMark(dated)).isEqualTo("ordered:2026-11-05");
        com.sellerops.order.fact.OrderFact undated = new com.sellerops.order.fact.OrderFact(null, null, null, "NAVER",
                null, null, null, null, null, null, null, null, null);
        assertThat(InquiryDraftComposer.orderedMark(undated)).isNull();
        assertThat(InquiryDraftComposer.orderedMark(null)).isNull();
    }

    private void cite(String mark) {
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(InquiryDraftEvidence.KIND_ORDER_FACT);
        row.setTitle("주문 상태");
        row.setKnowledgeState(mark);
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), 1)).thenReturn(List.of(row));
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(new com.sellerops.inquiry.draft.dto
                .DraftEvidenceView(InquiryDraftEvidence.KIND_ORDER_FACT, "주문 상태", "주문 상태", null, null, null, null));
    }
}
