package com.sellerops.knowledge.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a reply may not state as fact, read against the material at that moment (2026-11-15, conflict step 4). */
class KnowledgeStateReplyTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final List<KnowledgeState.Source> sources = List.of(
            new KnowledgeState.Source(a, "ORG", null, "ORG", "교환·반품 안내", 1, null,
                    ClaimReader.read("상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.")),
            new KnowledgeState.Source(b, "ORG", null, "ORG", "옛 공지", 1, null,
                    ClaimReader.read("교환은 수령 후 14일 이내에 신청해 주세요.")));

    @Test
    @DisplayName("unresolved: either value stated as fact is refused, and saying nothing of it is not")
    void unresolved() {
        KnowledgeState s = KnowledgeState.of(sources, List.of(), LocalDate.of(2026, 11, 14), null);
        assertThat(s.unsettledIn("교환은 수령 후 14일 안에 신청해 주세요.")).singleElement()
                .satisfies(u -> assertThat(u.sentenceKo()).contains("7일").contains("14일").contains("「14일」"));
        assertThat(s.unsettledIn("교환·반품은 수령 후 7일 이내에 신청해 주세요.")).as("7일 for an exchange is not settled either")
                .isNotEmpty();
        assertThat(s.unsettledIn("교환을 원하시면 사진과 함께 문의해 주세요.")).isEmpty();
    }

    @Test
    @DisplayName("replaced: the replaced value is refused, the current one passes")
    void replaced() {
        KnowledgeState s = KnowledgeState.of(sources, List.of(new KnowledgeState.Replacement(UUID.randomUUID(), a, b,
                ClaimAttribute.EXCHANGE_WINDOW, ClaimCondition.GENERAL)), LocalDate.of(2026, 11, 14), null);
        assertThat(s.unsettledIn("교환은 14일 안에 가능합니다.")).singleElement()
                .satisfies(u -> assertThat(u.kind()).isEqualTo(KnowledgeState.Unsettled.REPLACED));
        assertThat(s.unsettledIn("교환은 수령 후 7일 안에 신청해 주세요.")).isEmpty();
    }

    @Test
    @DisplayName("a planned schedule date said without saying it is planned is refused; said as planned it passes")
    void plannedDates() {
        UUID product = UUID.randomUUID();
        KnowledgeSchedule planned = new KnowledgeSchedule();
        planned.setProductId(product);
        planned.setOptionName("그레이");
        planned.setKind(KnowledgeSchedule.Kind.INBOUND);
        planned.setStatus(KnowledgeSchedule.Status.PLANNED);
        planned.setScheduleDate(LocalDate.of(2026, 11, 25));
        KnowledgeSchedule confirmed = new KnowledgeSchedule();
        confirmed.setProductId(product);
        confirmed.setKind(KnowledgeSchedule.Kind.INBOUND);
        confirmed.setStatus(KnowledgeSchedule.Status.CONFIRMED);
        confirmed.setScheduleDate(LocalDate.of(2026, 11, 18));
        KnowledgeState s = KnowledgeState.of(List.of(), List.of(), LocalDate.of(2026, 11, 14), null,
                List.of(planned, confirmed), Map.of(product, "면 침구 세트(퀸)"));
        assertThat(s.unsettledIn("그레이는 11월 25일에 입고됩니다.")).singleElement().satisfies(u -> {
            assertThat(u.kind()).isEqualTo(KnowledgeState.Unsettled.PLANNED);
            assertThat(u.sentenceKo()).contains("「면 침구 세트(퀸) · 그레이」").contains("예정");
        });
        assertThat(s.unsettledIn("그레이는 11월 25일 입고 예정이며, 일정이 바뀔 수 있어요.")).isEmpty();
        assertThat(s.unsettledIn("민트는 11월 18일에 입고돼요.")).as("a confirmed date may be said").isEmpty();
    }
}
