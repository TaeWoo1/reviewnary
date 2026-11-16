package com.sellerops.knowledge.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.claim.ClaimRelation;
import com.sellerops.knowledge.claim.KnowledgeClaim;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The 2026-11-15 review's remaining state cases: replacements through a chain and round a cycle, a replacement's own
 * attribute and condition, 편도 · 왕복 never saying the payer, and a planned date judged in its own clause against the line
 * it is about.
 */
class KnowledgeStateChainTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

    private static KnowledgeState.Source org(String title, String body) {
        return new KnowledgeState.Source(UUID.randomUUID(), "ORG", null, "ORG", title, 1, null,
                ClaimReader.read(body, title));
    }

    private static KnowledgeState.Replacement over(KnowledgeState.Source newer, KnowledgeState.Source older) {
        return over(newer, older, ClaimAttribute.EXCHANGE_WINDOW);
    }

    private static KnowledgeState.Replacement over(KnowledgeState.Source newer, KnowledgeState.Source older,
                                                   ClaimAttribute attribute) {
        return new KnowledgeState.Replacement(UUID.randomUUID(), newer.id(), older.id(), attribute,
                ClaimCondition.GENERAL);
    }

    /** Per document and attribute, 「현행」 never meets 「대체됨」 or 「확인 필요」 in the marks a passage carries. */
    private static void noContradictoryMarks(KnowledgeState s) {
        for (KnowledgeState.Source src : s.sources()) {
            KnowledgeState.Mark m = s.markFor(src.id(), String.join(" ",
                    src.claims().stream().map(KnowledgeClaim::clause).toList()));
            for (ClaimAttribute attribute : ClaimAttribute.values()) {
                String subject = attribute.labelKo();
                List<String> mine = m.notes().stream().filter(n -> n.contains(subject)).toList();
                boolean current = mine.stream().anyMatch(n -> n.startsWith("[현행]"));
                boolean replaced = mine.stream().anyMatch(n -> n.startsWith("[대체됨]"));
                boolean unsettled = mine.stream().anyMatch(n -> n.startsWith("[확인 필요]"));
                assertThat(current && (replaced || unsettled)).as(src.title() + " " + mine).isFalse();
            }
        }
    }

    @Test
    @DisplayName("A > B > C with C saying what A says: C's 7일 is not 「replaced」 — a reply's 7일 passes")
    void aChainBackToTheSameValue() {
        var a = org("A", "교환은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내에 신청해 주세요.");
        var c = org("C", "교환은 수령 후 7일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b, c), List.of(over(a, b), over(b, c)), DAY, null);
        assertThat(s.unsettledIn("교환은 7일 안에 신청해 주세요.")).isEmpty();
        assertThat(s.settledFigures("교환은 7일 안에 신청해 주세요.")).containsExactly("7일");
        assertThat(s.unsettledIn("교환은 14일 안에 신청해 주세요.")).isNotEmpty();
        noContradictoryMarks(s);
    }

    @Test
    @DisplayName("A replaces B while A and C are unsettled: A is not marked current, B stays replaced")
    void currentAndUnsettled() {
        var a = org("A", "교환은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내에 신청해 주세요.");
        var c = org("C", "교환은 수령 후 10일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b, c), List.of(over(a, b)), DAY, null);
        noContradictoryMarks(s);
        assertThat(s.markFor(b.id(), "교환은 수령 후 14일 이내에 신청해 주세요.").replaced())
                .contains(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(s.unsettledIn("교환은 7일 안에 신청해 주세요.")).isNotEmpty();
    }

    @Test
    @DisplayName("A replaces B, B replaces C: A is current over both, and B against C is not 「확인 필요」")
    void aChain() {
        var a = org("A", "교환은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내에 신청해 주세요.");
        var c = org("C", "교환은 수령 후 10일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b, c), List.of(over(a, b), over(b, c)), DAY, null);
        assertThat(s.conflicts()).allMatch(x -> !x.unresolved());
        assertThat(s.conflicts()).filteredOn(x -> x.touches(a.id())).allMatch(x -> x.current().sourceId().equals(a.id()));
        noContradictoryMarks(s);
        assertThat(s.unsettledIn("교환은 7일 안에 신청해 주세요.")).isEmpty();
        assertThat(s.unsettledIn("교환은 10일 안에 신청해 주세요.")).extracting(KnowledgeState.Unsettled::kind)
                .containsOnly(KnowledgeState.Unsettled.REPLACED);
        assertThat(s.settledFigures("교환은 7일 안에 신청해 주세요.")).containsExactly("7일");
    }

    @Test
    @DisplayName("A→B→C→A decides nothing: every pair is unsettled, no document is called current")
    void aCycle() {
        var a = org("A", "교환은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내에 신청해 주세요.");
        var c = org("C", "교환은 수령 후 10일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b, c), List.of(over(a, b), over(b, c), over(c, a)), DAY, null);
        assertThat(s.conflicts()).hasSize(3).allMatch(KnowledgeState.Conflict::unresolved)
                .allMatch(x -> x.reason() == KnowledgeState.Reason.CYCLE);
        assertThat(s.conflicts().get(0).sentenceKo()).contains("서로 돌고");
        noContradictoryMarks(s);
        assertThat(s.unsettledIn("교환은 7일 안에 신청해 주세요.")).isNotEmpty();
        assertThat(s.settledFigures("교환은 7일 안에 신청해 주세요.")).isEmpty();
    }

    @Test
    @DisplayName("A replacement holds for its own attribute only: the return window between the same two stays unsettled")
    void perAttribute() {
        var a = org("A", "교환은 수령 후 7일 이내, 반품은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내, 반품은 수령 후 14일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b), List.of(over(a, b)), DAY, null);
        Map<ClaimAttribute, Boolean> unresolved = s.conflicts().stream().collect(Collectors.toMap(
                KnowledgeState.Conflict::attribute, KnowledgeState.Conflict::unresolved));
        assertThat(unresolved).containsEntry(ClaimAttribute.EXCHANGE_WINDOW, false)
                .containsEntry(ClaimAttribute.RETURN_WINDOW, true);
        assertThat(s.unsettledIn("반품은 7일 안에 신청해 주세요.")).extracting(KnowledgeState.Unsettled::kind)
                .containsExactly(KnowledgeState.Unsettled.UNRESOLVED);
        noContradictoryMarks(s);
    }

    @Test
    @DisplayName("A replaces B and C; B against C is moot and no longer marks either 「확인 필요」")
    void twoLosers() {
        var a = org("A", "교환은 수령 후 7일 이내에 신청해 주세요.");
        var b = org("B", "교환은 수령 후 14일 이내에 신청해 주세요.");
        var c = org("C", "교환은 수령 후 10일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(a, b, c), List.of(over(a, b), over(a, c)), DAY, null);
        assertThat(s.conflicts()).noneMatch(KnowledgeState.Conflict::unresolved);
        noContradictoryMarks(s);
    }

    @Test
    @DisplayName("편도 3,000원 against 왕복 6,000원 is the same amount — but not when they name different payers")
    void legsNeverSayThePayer() {
        KnowledgeClaim round = ClaimReader.read("단순 변심 반품은 왕복 배송비 6,000원을 고객님이 부담합니다.").get(0);
        KnowledgeClaim oneWay = ClaimReader.read("단순 변심 반품 시 반품 배송비는 편도 3,000원입니다.").get(0);
        KnowledgeClaim sellerPays = ClaimReader.read("단순 변심 반품 시 반품 배송비 편도 3,000원은 판매자가 부담합니다.").get(0);
        assertThat(round.payer()).isEqualTo(KnowledgeClaim.Payer.CUSTOMER);
        assertThat(oneWay.payer()).isNull();
        assertThat(sellerPays.payer()).isEqualTo(KnowledgeClaim.Payer.SELLER);
        assertThat(ClaimRelation.of(round, oneWay)).isEqualTo(ClaimRelation.EQUIVALENT);
        assertThat(ClaimRelation.of(round, sellerPays)).isEqualTo(ClaimRelation.DIFFERENT);
        var x = new KnowledgeState.Source(UUID.randomUUID(), "ORG", null, "ORG", "교환·반품 안내", 1, null, List.of(round));
        var y = new KnowledgeState.Source(UUID.randomUUID(), "ORG", null, "ORG", "새 공지", 1, null, List.of(sellerPays));
        KnowledgeState s = KnowledgeState.of(List.of(x, y), List.of(), DAY, null);
        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.unresolved()).isTrue();
            assertThat(c.sentenceKo()).contains("고객 부담").contains("판매자 부담");
        });
    }

    @Test
    @DisplayName("a reply's sentence goes on about the one before it: the 단순 변심 return fee, not the order's shipping fee")
    void aReplyReadsOn() {
        List<KnowledgeClaim> said = ClaimReader.readReply(
                "단순 변심 반품은 받으신 뒤 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 고객님이 부담합니다.");
        assertThat(said).extracting(KnowledgeClaim::attribute)
                .containsExactly(ClaimAttribute.RETURN_WINDOW, ClaimAttribute.RETURN_SHIPPING_FEE);
        assertThat(said.get(1).condition()).isEqualTo(ClaimCondition.CHANGE_OF_MIND);
        assertThat(ClaimReader.readReply("반품은 7일 안에 신청해 주세요. 주문 배송비는 3,000원입니다."))
                .extracting(KnowledgeClaim::attribute)
                .containsExactly(ClaimAttribute.RETURN_WINDOW, ClaimAttribute.SHIPPING_FEE);
        // 「구매자」 · 「주문자」 name who pays, not an order: the subject is still carried (2026-11-15, r4 review).
        for (String payer : List.of("구매자 부담입니다.", "주문자 부담입니다.")) {
            List<KnowledgeClaim> carried = ClaimReader.readReply("단순 변심 반품은 받으신 뒤 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 " + payer);
            assertThat(carried).as(payer).extracting(KnowledgeClaim::attribute)
                    .containsExactly(ClaimAttribute.RETURN_WINDOW, ClaimAttribute.RETURN_SHIPPING_FEE);
            assertThat(carried.get(1).condition()).as(payer).isEqualTo(ClaimCondition.CHANGE_OF_MIND);
        }
    }

    private static KnowledgeSchedule line(KnowledgeSchedule.Kind kind, KnowledgeSchedule.Status status, String option,
                                          LocalDate date) {
        KnowledgeSchedule s = new KnowledgeSchedule();
        s.setId(UUID.randomUUID());
        s.setActive(true);
        s.setKind(kind);
        s.setStatus(status);
        s.setOptionName(option);
        s.setScheduleDate(date);
        return s;
    }

    @Test
    @DisplayName("a planned date is judged in its own clause: a hedge in the next clause does not cover it")
    void aHedgeElsewhere() {
        LocalDate d = LocalDate.of(2026, 11, 25);
        KnowledgeState s = KnowledgeState.of(List.of(), List.of(), DAY, null,
                List.of(line(KnowledgeSchedule.Kind.INBOUND, KnowledgeSchedule.Status.PLANNED, "그레이", d)), Map.of());
        assertThat(s.unsettledIn("11월 25일에 입고됩니다, 배송 일정은 확인 후 안내하겠습니다.")).isNotEmpty();
        assertThat(s.unsettledIn("11월 25일에 입고되며 배송 일정은 바뀔 수 있습니다.")).isNotEmpty();
        assertThat(s.unsettledIn("11월 25일에 입고되고 배송은 확인 중입니다.")).isNotEmpty();
        assertThat(s.unsettledIn("11월 25일 입고 예정이에요, 바뀌면 다시 알려 드릴게요.")).isEmpty();
        assertThat(s.unsettledIn("입고 예정일은 11월 25일이지만 바뀔 수 있어요.")).isEmpty();
        assertThat(s.unsettledIn("11월 25일에 입고될 예정이며, 확정되면 안내드릴게요.")).isEmpty();
    }

    @Test
    @DisplayName("the same day's other line: a confirmed 출고 or another option is not the planned 입고")
    void theSameDaysOtherLine() {
        LocalDate d = LocalDate.of(2026, 11, 25);
        KnowledgeState s = KnowledgeState.of(List.of(), List.of(), DAY, null, List.of(
                line(KnowledgeSchedule.Kind.INBOUND, KnowledgeSchedule.Status.PLANNED, "그레이", d),
                line(KnowledgeSchedule.Kind.INBOUND, KnowledgeSchedule.Status.CONFIRMED, "아이보리", d),
                line(KnowledgeSchedule.Kind.OUTBOUND, KnowledgeSchedule.Status.CONFIRMED, "그레이", d)), Map.of());
        assertThat(s.unsettledIn("아이보리는 11월 25일에 입고됩니다.")).as("the confirmed option").isEmpty();
        assertThat(s.unsettledIn("주문하신 상품은 11월 25일에 출고됩니다.")).as("the confirmed 출고").isEmpty();
        assertThat(s.unsettledIn("그레이는 11월 25일에 입고됩니다.")).as("the planned one").isNotEmpty();
        assertThat(s.unsettledIn("11월 25일에 입고됩니다.")).as("naming neither option: read as the planned").isNotEmpty();
        assertThat(s.unsettledIn("그레이와 아이보리 모두 11월 25일에 입고됩니다.")).as("both named").isNotEmpty();
    }

    @Test
    @DisplayName("the path a chain follows: direct first, then through other documents, never round")
    void paths() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var ab = new KnowledgeState.Replacement(UUID.randomUUID(), a, b, ClaimAttribute.EXCHANGE_WINDOW,
                ClaimCondition.GENERAL);
        var bc = new KnowledgeState.Replacement(UUID.randomUUID(), b, c, ClaimAttribute.EXCHANGE_WINDOW,
                ClaimCondition.GENERAL);
        var ca = new KnowledgeState.Replacement(UUID.randomUUID(), c, a, ClaimAttribute.EXCHANGE_WINDOW,
                ClaimCondition.GENERAL);
        assertThat(KnowledgeState.path(List.of(ab, bc), a, c)).isEqualTo(ab);
        assertThat(KnowledgeState.reaches(List.of(ab, bc), c, a)).isFalse();
        assertThat(KnowledgeState.reaches(List.of(ab, bc, ca), a, a)).isTrue();
        assertThat(Set.of(KnowledgeState.reaches(List.of(ab, bc, ca), b, a))).containsExactly(true);
    }
}
