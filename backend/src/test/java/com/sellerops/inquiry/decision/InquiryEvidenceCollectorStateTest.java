package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunk;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.schedule.KnowledgeScheduleService;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.ProductFactRepository;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>q14's candidate miss, fixed where the pool is collected</b> (2026-11-15, B150 evidence (나)): a document that states
 * the asked value is a candidate whatever words the question used, and every candidate carries what its values are now.
 */
class InquiryEvidenceCollectorStateTest {

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final OrgKnowledgeChunkRepository orgChunks = mock(OrgKnowledgeChunkRepository.class);
    private final KnowledgeCurrencyService currency = mock(KnowledgeCurrencyService.class);
    private final KnowledgeScheduleRepository scheduleRows = mock(KnowledgeScheduleRepository.class);
    private final InquiryEvidenceCollector collector = new InquiryEvidenceCollector(
            mock(InquiryEvidenceRetriever.class), mock(ProductKnowledgeSourceRepository.class),
            mock(ProductKnowledgeChunkRepository.class), mock(OrgKnowledgeSourceRepository.class), orgChunks,
            mock(ProductFactRepository.class), mock(ProductVariantRepository.class), mock(ChannelProductRepository.class),
            mock(ChannelRepository.class), mock(AnswerMemoryRepository.class), mock(InquiryRepository.class));

    private static final String CURRENT = "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.";
    private static final String OLD = "교환은 수령 후 14일 이내에 신청해 주세요.";
    private static final String SHIPPING = "출고 후 보통 1~2일 안에 도착합니다.";

    private OrgKnowledgeChunk chunk(UUID source, String text, int ordinal) {
        OrgKnowledgeChunk c = new OrgKnowledgeChunk();
        c.setId(UUID.randomUUID());
        c.setSourceId(source);
        c.setContent(text);
        c.setOrdinal(ordinal);
        c.setOrgId(org);
        return c;
    }

    private Inquiry inquiry(String body) {
        Inquiry i = new Inquiry();
        i.setOrgId(org);
        i.setProductId(productId);
        i.setBody(body);
        return i;
    }

    @Test
    @DisplayName("q14: both documents stating the exchange window reach the judge, each marked 「확인 필요」")
    void bothSidesReachTheJudge() {
        UUID current = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID shipping = UUID.randomUUID();
        // More than a small library, so nothing is read whole: only the asked attribute brings the two in.
        List<OrgKnowledgeChunk> chunks = new java.util.ArrayList<>(List.of(chunk(current, CURRENT, 0),
                chunk(old, OLD, 0), chunk(shipping, SHIPPING, 0)));
        for (int i = 0; i < InquiryEvidenceCollector.WHOLE_LIBRARY_PASSAGES; i++) {
            chunks.add(chunk(UUID.randomUUID(), "기타 안내 " + i, i));
        }
        when(orgChunks.findAllByOrgId(org)).thenReturn(chunks);
        KnowledgeState state = KnowledgeState.of(List.of(
                new KnowledgeState.Source(current, "ORG", null, "ORG", "교환·반품 안내", 1, null, ClaimReader.read(CURRENT)),
                new KnowledgeState.Source(old, "ORG", null, "ORG", "옛 공지", 1, null, ClaimReader.read(OLD)),
                new KnowledgeState.Source(shipping, "ORG", null, "ORG", "배송 안내", 1, null, ClaimReader.read(SHIPPING))),
                List.of(), LocalDate.of(2026, 11, 14), null);
        when(currency.stateFor(eq(org), eq(productId), any(), any())).thenReturn(state);
        collector.setCurrency(currency, scheduleRows, null);

        InquiryDecisionEngine.Pool pool = collector.collect(org, inquiry("교환은 받고 며칠 안에 신청해야 하나요?"), productId,
                null, null, null, List.of(new InquiryNeed("N1", "운영 기준 안내", NeedType.POLICY, "운영 기준 안내")));

        assertThat(pool.evidence()).extracting(EvidenceCandidate::label).containsExactly("교환·반품 안내", "옛 공지");
        assertThat(pool.evidence()).allSatisfy(c -> {
            assertThat(c.mark().line()).startsWith("[확인 필요] 교환 신청 기한");
            assertThat(c.mark().unresolved()).containsKey(com.sellerops.knowledge.claim.ClaimAttribute.EXCHANGE_WINDOW);
        });
    }

    @Test
    @DisplayName("a schedule question gets the product's current lines; a past line is history and is not offered")
    void schedulesForAScheduleQuestion() {
        KnowledgeSchedule planned = new KnowledgeSchedule();
        planned.setId(UUID.randomUUID());
        planned.setOrgId(org);
        planned.setProductId(productId);
        planned.setOptionName("그레이");
        planned.setKind(KnowledgeSchedule.Kind.INBOUND);
        planned.setStatus(KnowledgeSchedule.Status.PLANNED);
        planned.setScheduleDate(LocalDate.of(2099, 11, 25));
        KnowledgeSchedule past = new KnowledgeSchedule();
        past.setId(UUID.randomUUID());
        past.setOrgId(org);
        past.setProductId(productId);
        past.setKind(KnowledgeSchedule.Kind.INBOUND);
        past.setStatus(KnowledgeSchedule.Status.CONFIRMED);
        past.setScheduleDate(LocalDate.of(2020, 10, 20));
        when(scheduleRows.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(planned, past));
        when(currency.stateFor(eq(org), eq(productId), any(), any())).thenReturn(KnowledgeState.EMPTY);
        KnowledgeScheduleService names = mock(KnowledgeScheduleService.class);
        when(names.nameOf(org, productId)).thenReturn("면 침구 세트(퀸)");
        collector.setCurrency(currency, scheduleRows, names);

        InquiryDecisionEngine.Pool pool = collector.collect(org, inquiry("그레이 재입고 언제? 날짜 알려주세요"), productId,
                null, null, null, List.of(new InquiryNeed("N1", "재입고 일정", NeedType.CATALOGUE_AVAILABILITY, "재입고")));
        assertThat(pool.evidence()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(EvidenceCandidate.Kind.SCHEDULE);
            assertThat(c.text()).contains("면 침구 세트(퀸) · 그레이").contains("입고 예정").contains("바뀔 수 있습니다")
                    .contains("주문이 출고·배송됐다는 사실이 아닙니다");
            assertThat(c.scope().kind()).isEqualTo(EvidenceScope.Kind.PRODUCT);
        });

        InquiryDecisionEngine.Pool other = collector.collect(org, inquiry("식기세척기 돼요?"), productId, null, null, null,
                List.of(new InquiryNeed("N1", "식기세척기 사용", NeedType.PRODUCT_USAGE, "식기세척기")));
        assertThat(other.evidence()).as("a question no schedule answers gets none").isEmpty();
    }
}
