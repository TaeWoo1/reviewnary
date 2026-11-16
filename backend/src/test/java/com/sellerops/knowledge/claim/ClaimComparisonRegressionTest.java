package com.sellerops.knowledge.claim;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.currency.KnowledgeState;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The regression cases of {@code DESIGN_conflict.md} §4, fixed before the comparison was trusted</b> (2026-11-15).
 *
 * <p>The old comparison read every figure of a document against every figure of another with the same unit: 배송 1~2일
 * against 반품 7일 was a conflict, 편도 3,000원 against 왕복 6,000원 was a conflict, and two documents that agreed on
 * delivery hid that they disagreed on the return window. Each case below is read the way the product reads it — from the
 * documents' own sentences, through {@link ClaimReader} and {@link KnowledgeState}.
 */
class ClaimComparisonRegressionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static KnowledgeState.Source org(String title, String body) {
        return new KnowledgeState.Source(UUID.nameUUIDFromBytes(title.getBytes()), "ORG", null, "ORG", title, 1, null,
                ClaimReader.read(body));
    }

    private static KnowledgeState stateOf(KnowledgeState.Source... sources) {
        return KnowledgeState.of(List.of(sources), List.of(), TODAY, null);
    }

    @Test
    @DisplayName("배송 1~2일 against 반품 신청 7일 is not a comparison at all")
    void deliveryDaysAreNotAReturnWindow() {
        KnowledgeState s = stateOf(org("배송", "출고 후 보통 1~2일 안에 도착합니다."),
                org("반품", "상품 수령 후 7일 이내에 반품을 신청할 수 있습니다."));
        assertThat(s.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("반품비 편도 3,000원 against 왕복 6,000원 is the same amount (q06)")
    void oneWayTwiceIsTheRoundTrip() {
        KnowledgeState s = stateOf(org("교환·반품 안내", "단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다."),
                org("옛 공지", "단순 변심 반품 시 반품 배송비는 편도 3,000원입니다."));
        assertThat(s.conflicts()).isEmpty();
        KnowledgeClaim one = ClaimReader.read("단순 변심 반품 시 반품 배송비는 편도 3,000원입니다.").get(0);
        KnowledgeClaim round = ClaimReader.read("단순 변심 반품은 왕복 배송비 6,000원입니다.").get(0);
        assertThat(ClaimRelation.of(one, round)).isEqualTo(ClaimRelation.EQUIVALENT);
        KnowledgeClaim four = ClaimReader.read("단순 변심 반품 시 반품 배송비는 편도 4,000원입니다.").get(0);
        assertThat(ClaimRelation.of(four, round)).as("legs that do not add up are not lined up")
                .isEqualTo(ClaimRelation.NOT_COMPARED);
    }

    @Test
    @DisplayName("two documents that agree on delivery and disagree on the return window disagree")
    void anAgreementElsewhereDoesNotHideADisagreement() {
        KnowledgeState s = stateOf(org("A", "출고 후 2일 안에 도착합니다. 상품 수령 후 7일 이내에 반품을 신청해 주세요."),
                org("B", "출고 후 2일 안에 도착합니다. 반품은 수령 후 14일 이내에 신청해 주세요."));
        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.attribute()).isEqualTo(ClaimAttribute.RETURN_WINDOW);
            assertThat(c.relation()).isEqualTo(ClaimRelation.DIFFERENT);
            assertThat(c.unresolved()).isTrue();
        });
    }

    @Test
    @DisplayName("ranges: same is same, contained and overlapping are different promises")
    void rangeRelations() {
        KnowledgeClaim oneTwo = delivery("1~2일");
        assertThat(ClaimRelation.of(oneTwo, delivery("1~2일"))).isEqualTo(ClaimRelation.SAME);
        assertThat(ClaimRelation.of(delivery("1~3일"), delivery("2일"))).isEqualTo(ClaimRelation.CONTAINS);
        assertThat(ClaimRelation.of(oneTwo, delivery("2~3일"))).isEqualTo(ClaimRelation.OVERLAP);
        assertThat(ClaimRelation.of(oneTwo, delivery("4~5일"))).isEqualTo(ClaimRelation.DIFFERENT);
        assertThat(ClaimRelation.OVERLAP.conflicts()).as("overlap is not sameness").isTrue();
        assertThat(ClaimRelation.CONTAINS.conflicts()).as("a customer told 2일 and one told 1~3일 heard different things")
                .isTrue();
    }

    private static KnowledgeClaim delivery(String days) {
        return ClaimReader.read("출고 후 " + days + " 안에 도착합니다.").get(0);
    }

    @Test
    @DisplayName("an exchange-window conflict is no conflict for a return question (q06 · q14)")
    void aConflictOnAnotherAttributeIsNotTheQuestions() {
        KnowledgeState s = stateOf(org("교환·반품 안내", "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다."),
                org("옛 공지", "단순 변심 반품 시 반품 배송비는 편도 3,000원입니다. 교환은 수령 후 14일 이내에 신청해 주세요."));
        assertThat(s.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.attribute()).isEqualTo(ClaimAttribute.EXCHANGE_WINDOW));
        assertThat(s.unresolvedAmong(ClaimReader.asked("침구 단순 변심 반품 배송비? 며칠 안에?"))).isEmpty();
        assertThat(s.unresolvedAmong(ClaimReader.asked("교환은 받고 며칠 안에 신청해야 하나요?")))
                .containsOnlyKeys(ClaimAttribute.EXCHANGE_WINDOW);
    }

    @Test
    @DisplayName("the held item's mark names its item, and the same sentence's other item is said to be undisputed (q06 real)")
    void theMarkNamesItsItem() {
        KnowledgeState.Source current = org("교환·반품 안내", "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.");
        KnowledgeState s = stateOf(current,
                org("옛 공지", "단순 변심 반품 시 반품 배송비는 편도 3,000원입니다. 교환은 수령 후 14일 이내에 신청해 주세요."));
        KnowledgeState.Mark m = s.markFor(current.id(), "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.");
        assertThat(m.unresolved()).containsOnlyKeys(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(m.notes()).anySatisfy(n -> assertThat(n).startsWith("[확인 필요] 교환 신청 기한")
                .contains("교환 신청 기한(으)로는 이 값을"));
        assertThat(m.notes()).anySatisfy(n -> assertThat(n).startsWith("[다툼 없음] 이 자료의 반품 신청 기한 7일"));
        assertThat(m.notes()).noneSatisfy(n -> assertThat(n).startsWith("[다툼 없음] 이 자료의 교환 신청 기한"));
        // A passage with nothing held carries nothing new.
        KnowledgeState quiet = stateOf(current);
        assertThat(quiet.markFor(current.id(), "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a remedy asked for something missing or broken does not ask the window; 「며칠」 still does (q12 real)")
    void aFaultRemedyDoesNotAskTheWindow() {
        assertThat(ClaimReader.asked("받은 상품에 뚜껑 패킹이 빠져 있으니 누락된 패킹을 보내주거나 교환 등 조치를 해주세요."))
                .doesNotContain(ClaimAttribute.EXCHANGE_WINDOW, ClaimAttribute.RETURN_WINDOW);
        assertThat(ClaimReader.asked("받았는데 마음이 바뀌었어요, 반품되나요?")).contains(ClaimAttribute.RETURN_WINDOW);
        assertThat(ClaimReader.asked("교환되나요?")).contains(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(ClaimReader.asked("불량인데 며칠 안에 교환 신청해야 하나요?")).contains(ClaimAttribute.EXCHANGE_WINDOW);
    }

    @Test
    @DisplayName("a window asked in elapsed days is a window question, a fault or not (B166); a fault remedy still is not")
    void aWindowAskedInElapsedDays() {
        assertThat(ClaimReader.asked("불량이라 교환하려는데 받은 지 10일 지났어도 신청할 수 있나요?"))
                .contains(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(ClaimReader.asked("배송받은 지 오래됐는데 불량 교환 되나요?")).contains(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(ClaimReader.asked("수령한 지 일주일 넘었는데 반품 가능한가요?")).contains(ClaimAttribute.RETURN_WINDOW);
        assertThat(ClaimReader.asked("받은 지 2주 됐는데 반품돼요?")).contains(ClaimAttribute.RETURN_WINDOW);
        assertThat(ClaimReader.asked("10일째인데 반품 신청 가능해요?")).contains(ClaimAttribute.RETURN_WINDOW);
        // Not taken back: a fault remedy with no time in it asks no window.
        assertThat(ClaimReader.asked("받은 상품에 뚜껑 패킹이 빠져 있으니 누락된 패킹을 보내주거나 교환 등 조치를 해주세요."))
                .doesNotContain(ClaimAttribute.EXCHANGE_WINDOW, ClaimAttribute.RETURN_WINDOW);
        assertThat(ClaimReader.asked("파손돼서 왔어요 교환해 주세요")).doesNotContain(ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(ClaimReader.asked("불량인데 교환 신청할 수 있나요?")).doesNotContain(ClaimAttribute.EXCHANGE_WINDOW);
    }

    @Test
    @DisplayName("a replacement for one attribute leaves the same document's other rules standing")
    void aReplacementIsScopedToItsAttribute() {
        KnowledgeState.Source current = org("배송 안내 새판", "출고 후 1~2일 안에 도착합니다.");
        KnowledgeState.Source old = org("옛 배송 공지", "출고 후 3~4일 안에 도착합니다. 반품은 수령 후 7일 이내에 신청해 주세요.");
        KnowledgeState.Source returns = org("반품 안내", "반품은 수령 후 14일 이내에 신청해 주세요.");
        KnowledgeState s = KnowledgeState.of(List.of(current, old, returns), List.of(new KnowledgeState.Replacement(
                        UUID.randomUUID(), current.id(), old.id(), ClaimAttribute.DELIVERY_DAYS, ClaimCondition.GENERAL)),
                TODAY, null);
        assertThat(s.conflicts()).anySatisfy(c -> {
            assertThat(c.attribute()).isEqualTo(ClaimAttribute.DELIVERY_DAYS);
            assertThat(c.unresolved()).isFalse();
            assertThat(c.current().sourceId()).isEqualTo(current.id());
        });
        assertThat(s.conflicts()).as("the old notice's return rule is not replaced by a delivery replacement")
                .anySatisfy(c -> {
                    assertThat(c.attribute()).isEqualTo(ClaimAttribute.RETURN_WINDOW);
                    assertThat(c.unresolved()).isTrue();
                });
    }

    @Test
    @DisplayName("values in a table row are per product: one product's row is not another product's")
    void tableRowsArePerProduct() {
        UUID table = UUID.randomUUID();
        UUID bedding = UUID.randomUUID();
        KnowledgeState s = KnowledgeState.of(List.of(
                new KnowledgeState.Source(UUID.randomUUID(), "PRODUCT", table, "PRODUCT:" + table, "테이블", 4, null,
                        ClaimReader.read("출고 후 2~3일 안에 도착합니다.")),
                new KnowledgeState.Source(UUID.randomUUID(), "PRODUCT", bedding, "PRODUCT:" + bedding, "침구", 4, null,
                        ClaimReader.read("출고 후 1~2일 안에 도착합니다.")),
                org("배송", "출고 후 보통 1~2일 안에 도착합니다.")), List.of(), TODAY, null);
        assertThat(s.conflicts()).as("a product's own rule is its exception, not a disagreement with the company's")
                .isEmpty();
    }

    @Test
    @DisplayName("a cost per condition is two rules: 단순 변심 6,000원 and 불량 0원")
    void costsPerCondition() {
        List<KnowledgeClaim> claims = ClaimReader.read("단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다. "
                + "상품 불량·오배송은 배송비 없이 교환 또는 환불해 드립니다.");
        assertThat(claims).extracting(KnowledgeClaim::condition)
                .containsExactly(ClaimCondition.CHANGE_OF_MIND, ClaimCondition.DEFECT);
        assertThat(ClaimRelation.of(claims.get(0), claims.get(1))).isEqualTo(ClaimRelation.NOT_COMPARED);
        KnowledgeState s = stateOf(org("A", "단순 변심 반품 배송비는 왕복 6,000원입니다."),
                org("B", "불량 상품은 배송비 없이 반품해 드립니다."));
        assertThat(s.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("one document's revision (5만원 → 7만원) is not a disagreement, and the old value is no current fact")
    void aRevisionInsideOneDocument() {
        String body = "배송비는 3,000원이며, 7만원 이상 구매 시 무료입니다. (2026-11-01부터 적용. 그 전에는 5만원 이상 무료였습니다.)";
        List<KnowledgeClaim> claims = ClaimReader.read(body);
        assertThat(claims).filteredOn(c -> c.attribute() == ClaimAttribute.FREE_SHIPPING_THRESHOLD)
                .extracting(KnowledgeClaim::low, KnowledgeClaim::historical)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(70_000L, false),
                        org.assertj.core.groups.Tuple.tuple(50_000L, true));
        KnowledgeState s = stateOf(org("배송 안내", body));
        assertThat(s.conflicts()).isEmpty();
        assertThat(s.unsettledIn("5만원 이상 구매하시면 무료배송이에요.")).singleElement()
                .satisfies(u -> assertThat(u.kind()).isEqualTo(KnowledgeState.Unsettled.REPLACED));
        assertThat(s.unsettledIn("7만원 이상 구매하시면 무료배송이에요.")).isEmpty();
        assertThat(s.unsettledIn("11월 1일 전에는 5만원 이상 무료였어요.")).as("a reply saying what used to be").isEmpty();
    }

    @Test
    @DisplayName("documents stating the same values agree")
    void theSameValuesAgree() {
        KnowledgeState s = stateOf(org("A", "평일 오후 3시 이전 결제 완료 주문은 당일 출고합니다. 출고 후 1~2일 안에 도착합니다."),
                org("B", "오후 3시 이전에 결제하시면 당일 출고하고, 출고 후 1~2일 안에 도착해요."));
        assertThat(s.conflicts()).isEmpty();
        assertThat(ClaimReader.read("평일 오후 3시 이전 결제 완료 주문은 당일 출고합니다.")).singleElement()
                .satisfies(c -> {
                    assertThat(c.attribute()).isEqualTo(ClaimAttribute.DISPATCH_CUTOFF);
                    assertThat(c.low()).isEqualTo(15);
                });
    }

    @Test
    @DisplayName("two policies with 적용일: the later one is current once begun, for an order placed on or after it")
    void twoPoliciesWithEffectiveDates() {
        KnowledgeState.Source now = withDate(org("새 정책", "반품은 수령 후 7일 이내에 신청해 주세요."), LocalDate.of(2026, 11, 1));
        KnowledgeState.Source old = withDate(org("옛 정책", "반품은 수령 후 14일 이내에 신청해 주세요."), LocalDate.of(2025, 6, 1));
        KnowledgeState.Conflict known = KnowledgeState.of(List.of(old, now), List.of(), TODAY,
                LocalDate.of(2026, 11, 10)).conflicts().get(0);
        assertThat(known.unresolved()).isFalse();
        assertThat(known.current().title()).isEqualTo("새 정책");
        KnowledgeState.Conflict before = KnowledgeState.of(List.of(old, now), List.of(), TODAY,
                LocalDate.of(2026, 10, 20)).conflicts().get(0);
        assertThat(before.current().title()).as("an order placed before the revision was sold under the old rule")
                .isEqualTo("옛 정책");
        KnowledgeState.Conflict unknown = KnowledgeState.of(List.of(old, now), List.of(), TODAY, null)
                .conflicts().get(0);
        assertThat(unknown.unresolved()).as("without the order date the 적용일 alone does not settle it").isTrue();
        assertThat(unknown.reason()).isEqualTo(KnowledgeState.Reason.ORDER_DATE_UNKNOWN);
        KnowledgeState.Source future = withDate(org("다음 달 정책", "반품은 수령 후 10일 이내에 신청해 주세요."),
                LocalDate.of(2026, 12, 1));
        KnowledgeState.Conflict notYet = KnowledgeState.of(List.of(now, future), List.of(), TODAY, null)
                .conflicts().get(0);
        assertThat(notYet.current().title()).as("a 적용일 that has not begun leaves the earlier one current")
                .isEqualTo("새 정책");
    }

    @Test
    @DisplayName("two policies without 적용일 stay unresolved — whichever was saved last does not win")
    void twoPoliciesWithoutDatesStayUnresolved() {
        KnowledgeState s = stateOf(org("교환·반품 안내", "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다."),
                org("옛 공지", "교환은 수령 후 14일 이내에 신청해 주세요."));
        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.unresolved()).isTrue();
            assertThat(c.reason()).isEqualTo(KnowledgeState.Reason.NONE);
        });
        assertThat(s.unsettledIn("교환은 14일 안에 가능합니다.")).singleElement()
                .satisfies(u -> assertThat(u.kind()).isEqualTo(KnowledgeState.Unsettled.UNRESOLVED));
        assertThat(s.unsettledIn("반품은 수령 후 7일 안에 신청해 주세요.")).as("the return window is settled").isEmpty();
    }

    @Test
    @DisplayName("a figure the closed list does not name is read as nothing")
    void figuresOutsideTheListAreNotRead() {
        assertThat(ClaimReader.read("보온 6시간, 보냉 12시간(실온 20도 기준, 자체 측정).")).isEmpty();
        assertThat(ClaimReader.read("2026-11-25 입고 예정입니다. 11월 25일에 들어와요.")).isEmpty();
        assertThat(ClaimReader.read("제작 전(결제 후 24시간 이내)에는 취소할 수 있습니다.")).isEmpty();
    }

    @Test
    @DisplayName("an item the documents leave unsettled has no current value, so a weaker source is not 'replaced by' one")
    void noCurrentValueWhileTheDocumentsDisagree() {
        KnowledgeState.Source past = new KnowledgeState.Source(UUID.randomUUID(), "ORG", null, "ORG", "답변", 5, null,
                ClaimReader.read("교환은 수령 후 7일 안에 신청해 주세요."));
        KnowledgeState s = KnowledgeState.of(List.of(org("교환·반품 안내", "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다."),
                org("옛 공지", "교환은 수령 후 14일 이내에 신청해 주세요."), past), List.of(), TODAY, null);
        assertThat(s.conflicts()).as("only the documents' own disagreement, unresolved").singleElement()
                .satisfies(c -> assertThat(c.unresolved()).isTrue());
        KnowledgeState plain = KnowledgeState.of(List.of(org("옛 공지", "교환은 수령 후 14일 이내에 신청해 주세요."), past),
                List.of(), TODAY, null);
        assertThat(plain.conflicts()).as("with one document, it still outranks a past answer").singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(KnowledgeState.Reason.AUTHORITY));
    }

    private static KnowledgeState.Source withDate(KnowledgeState.Source s, LocalDate from) {
        return new KnowledgeState.Source(s.id(), s.kind(), s.productId(), s.scopeKey(), s.title(), s.rank(), from,
                s.claims());
    }
}
