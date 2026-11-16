package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.draft.ReviewClaimGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The three residual defects of the B-plan run, each with its contrast</b> (2026-11-16, build k real run · RESULTS §25):
 * q09 S4 held for the 500 of the product's own name, q06 S1 · q14 S1 first drafts read as granting what they refused,
 * q13 adding one option's restock date to a delivery question. Offline only — whether the model now writes these drafts
 * through on the first try is unverified until a real run.
 */
class KbResidualFixesTest {

    static final String P02 = "[합성] 숲결리빙 교환·반품 안내 (기준일 2026-11-01)\n\n- 상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.\n"
            + "- 단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다.\n"
            + "- 상품 불량·오배송은 배송비 없이 교환 또는 환불해 드립니다. 사진을 함께 보내 주세요.\n"
            + "- 사용 흔적이 있거나 상품 가치가 훼손된 경우 교환·반품이 어렵습니다.\n"
            + "- 주문 제작 상품과 각인 상품은 단순 변심 교환·반품이 되지 않습니다. 상품별 안내를 확인해 주세요.";
    static final String P11 = "[합성] 면 침구 세트(퀸) 상품 안내\n\n- 재고 상품으로, 공통 배송 기준(평일 오후 3시 이전 결제 시 당일 출고)을 따릅니다.\n"
            + "- 구성: 이불 커버 1, 베개 커버 2, 매트리스 커버 1. 이불솜은 포함되지 않습니다.\n"
            + "- 세탁: 첫 세탁은 찬물 단독 세탁을 권합니다. 건조기 사용 시 3~5% 줄어들 수 있습니다.\n"
            + "- 세탁하거나 사용한 침구는 위생상 단순 변심 교환·반품이 되지 않습니다.\n- 색상: 아이보리, 그레이.";
    static final String LID = "[합성] 뚜껑 단품 구매\n- 뚜껑만 따로 판매하지 않습니다. 패킹이 빠지거나 망가졌으면 사진과 함께 문의해 주세요.";
    static final List<String> NAMES = List.of("[합성] 스테인리스 텀블러 500ml", "스테인리스 텀블러 500ml");

    // ── 1. a number inside the inquiry product's recorded name ───────────────────────────────────────────────────

    @Test
    @DisplayName("q09 S4: 「스테인리스 텀블러 500ml」 written as the product's name is not a figure — only with the name known")
    void theProductsOwnNameIsNotAFigure() {
        String draft = "안녕하세요. 스테인리스 텀블러 500ml에 관심 가져 주셔서 감사합니다. 문의하신 텀블러 뚜껑은 따로 판매하지 않는 점 양해 부탁드립니다.";
        assertThat(figures(draft, List.of())).as("unknown product: refused as before").containsExactly("500");
        assertThat(figures(draft, NAMES)).isEmpty();
        assertThat(InquiryClaimGuard.figuresToVerify(draft, List.of(LID), "뚜껑만 따로 살 수 있나요?", null, NAMES))
                .as("nor is it sent to the model as a figure to verify").isEmpty();
    }

    @Test
    @DisplayName("contrast: the same 500 anywhere outside the exact name — 500원 · 500일 · 500ml 증정 · 「500 ml 텀블러」 — is still refused")
    void theSameNumberElsewhereIsStillAClaim() {
        assertThat(figures("스테인리스 텀블러 500ml 뚜껑은 500원에 따로 보내 드립니다.", NAMES)).containsExactly("500");
        assertThat(figures("스테인리스 텀블러 500ml는 500일 동안 보온을 보장합니다.", NAMES)).containsExactly("500");
        assertThat(figures("구매하시면 500ml 보틀을 하나 더 드립니다.", NAMES)).containsExactly("500");
        assertThat(figures("스테인리스 500 ml 텀블러 뚜껑은 따로 판매하지 않습니다.", NAMES)).as("a near variant is not the name")
                .containsExactly("500");
        assertThat(figures("스테인리스 텀블러 350ml 뚜껑은 따로 판매하지 않습니다.", NAMES)).as("another size is another number")
                .containsExactly("350");
    }

    @Test
    @DisplayName("the 「문의 상품」 line drops only the synthetic/demo marker — 「[500ml]」 · 「[민트]」 stay")
    void theProductLineNamesItAsTheCustomerReadsIt() {
        assertThat(SpecApplicability.withoutNonIdentifyingTags("[합성] 스테인리스 텀블러 500ml")).isEqualTo("스테인리스 텀블러 500ml");
        assertThat(SpecApplicability.withoutNonIdentifyingTags("[데모][합성] 면 침구 세트(퀸)")).isEqualTo("면 침구 세트(퀸)");
        assertThat(SpecApplicability.withoutNonIdentifyingTags("[500ml] 텀블러")).isEqualTo("[500ml] 텀블러");
        assertThat(SpecApplicability.withoutNonIdentifyingTags("[합성] [민트] 텀블러")).isEqualTo("[민트] 텀블러");
        assertThat(InquiryDraftComposer.productLine(SpecApplicability.withoutNonIdentifyingTags("[합성] 스테인리스 텀블러 500ml")))
                .contains("문의 상품: 스테인리스 텀블러 500ml").doesNotContain("[합성]");
    }

    private static List<String> figures(String draft, List<String> names) {
        return InquiryClaimGuard.unsupportedClaims(draft, List.of(LID), List.of(), "뚜껑만 따로 살 수 있나요?", null, names)
                .stream().filter(v -> v.kind() == InquiryClaimGuard.Kind.FIGURE).map(InquiryClaimGuard.Violation::matched)
                .toList();
    }

    // ── 2. a refusal written with a courtesy tail ──────────────────────────────────────────────────────────────────

    static final String Q06 = "단순 변심 반품 배송비? 며칠 안에?";

    @Test
    @DisplayName("q06 S1 · q14 S1 first drafts: 「…반품이 어려운 점 참고 부탁드립니다」 · 「…교환이 되지 않는 점 참고 부탁드립니다」 refuse")
    void aRefusalWithACourtesyTailRefuses() {
        List<String> ev = List.of(P02, P11);
        String q06 = "단순 변심 반품은 상품 수령 후 7일 이내에 신청하실 수 있으며, 왕복 배송비 6,000원은 고객님께서 부담하십니다. "
                + "다만 면 침구 세트는 세탁하거나 사용한 경우 위생상 단순 변심 반품이 되지 않으며, 사용 흔적이 있거나 상품 가치가 "
                + "훼손된 경우에도 반품이 어려운 점 참고 부탁드립니다.";
        String q14 = "교환은 상품을 받으신 날로부터 7일 이내에 신청하실 수 있습니다. 단순 변심으로 교환하실 경우 왕복 배송비 6,000원은 "
                + "고객님 부담이며, 이 침구 세트는 세탁하거나 사용한 경우 위생상 단순 변심 교환이 되지 않는 점 참고 부탁드립니다.";
        assertThat(ReviewClaimGuard.contradictedPromises(q06, ev, Q06)).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises(q14, ev, "교환은 받고 며칠 안에 신청해야 하나요?")).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("반품이 어려운 점 양해 바랍니다.", ev, Q06)).isEmpty();
    }

    @Test
    @DisplayName("contrast: a grant with the same courtesy tail is still a grant — 세탁·사용 granted is still refused")
    void aGrantWithACourtesyTailStillGrants() {
        List<String> ev = List.of(P02, P11);
        assertThat(ReviewClaimGuard.contradictedPromises("세탁하신 침구도 반품이 가능한 점 참고 부탁드립니다.", ev, Q06))
                .containsExactly("반품");
        assertThat(ReviewClaimGuard.contradictedPromises("사용 흔적이 있어도 교환해 드리는 점 양해 부탁드립니다.", ev, Q06))
                .containsExactly("교환");
        assertThat(ReviewClaimGuard.contradictedPromises("세탁하셨어도 반품 가능합니다.", ev, Q06)).as("as before")
                .containsExactly("반품");
    }

    // ── 3. one option's stock in a reply to a question that asked nothing about stock ─────────────────────────────

    static final List<SpecApplicability.Option> NONE = List.of();
    static final List<SpecApplicability.Option> COLOURS = List.of(new SpecApplicability.Option(UUID.randomUUID(), "아이보리"),
            new SpecApplicability.Option(UUID.randomUUID(), "그레이"));

    @Test
    @DisplayName("q13 P2: a delivery question answered with 「그레이 옵션은 품절 · 11월 25일 입고 예정」 — that sentence is out of scope")
    void anUnaskedOptionScheduleIsOutOfScope() {
        String draft = "평일 오후 3시 이후 결제 주문은 다음 영업일에 출고하며, 주말과 공휴일에는 출고하지 않습니다. 참고로 면 침구 세트(퀸) 그레이 "
                + "옵션은 일정표상 품절·재입고 대기 상태이며, 11월 25일 입고 예정이나 공장 회신 전이라 날짜가 바뀔 수 있습니다. 감사합니다.";
        assertThat(InquiryDraftComposer.unaskedSchedule(draft, "배송 문의", "어제 오후 4시에 결제, 언제 받나요?", NONE))
                .singleElement().asString().contains("그레이");
        assertThat(InquiryDraftComposer.unaskedSchedule("반품 배송비는 왕복 6,000원입니다. 그레이 색상은 현재 품절입니다.", null,
                "단순 변심 반품 배송비?", COLOURS)).as("a policy question with a stock line mixed in").hasSize(1);
        assertThat(InquiryDraftComposer.unaskedSchedule("반품 배송비는 왕복 6,000원입니다. 아이보리는 다음 주 재입고됩니다.", null,
                "단순 변심 반품 배송비?", COLOURS)).as("a known option name without 「옵션」").hasSize(1);
    }

    @Test
    @DisplayName("contrast: a stock question is answered as far as the evidence goes — one option, a partial answer, a 「미정」")
    void aStockQuestionIsInScopeHoweverPartial() {
        assertThat(InquiryDraftComposer.unaskedSchedule("그레이 색상은 11월 25일 입고 예정이며 바뀔 수 있습니다.", null,
                "그레이 재입고 언제? 날짜 알려주세요", COLOURS)).isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("그레이 색상은 11월 25일 입고 예정입니다. 아이보리 색상의 입고 일정은 아직 정해지지 "
                + "않았습니다.", null, "아이보리랑 그레이 재고 있나요?", COLOURS)).as("only one option has a date").isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("그레이 색상은 11월 25일 입고 예정입니다.", null,
                "아이보리랑 그레이 언제 다시 들어와요?", COLOURS)).as("the other option left out").isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("민트 색상은 11월 18일 재입고 확정입니다.", null, "민트 살 수 있나요?",
                NONE)).isEmpty();
    }

    @Test
    @DisplayName("contrast: a delivery question about the option the customer named is answered by that option's stock")
    void theNamedOptionsStockAnswersADeliveryQuestion() {
        assertThat(InquiryDraftComposer.unaskedSchedule("민트 색상은 현재 품절이라 11월 25일 입고 예정이며, 입고 후 출고됩니다.", null,
                "민트 색상 오늘 출고되나요?", NONE)).isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("그레이는 품절이라 11월 25일 입고 뒤 출고됩니다.", null,
                "그레이로 주문했는데 언제 받나요?", COLOURS)).as("a known option named by the customer").isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("그레이는 품절이라 11월 25일 입고 뒤 출고됩니다. 아이보리 색상도 현재 품절입니다.",
                null, "그레이로 주문했는데 언제 받나요?", COLOURS)).as("…but another option beside it is not theirs")
                .singleElement().asString().contains("아이보리");
        assertThat(InquiryDraftComposer.unaskedSchedule("주문하신 상품은 재고 상품이라 오후 3시 이후 결제는 다음 영업일에 출고합니다.",
                null, "어제 오후 4시에 결제, 언제 받나요?", NONE)).as("why it ships when it does — not an option").isEmpty();
    }

    @Test
    @DisplayName("contrast: a shipping rule, a making time, 「재고 상품」 about the product, a policy — never read as an option's schedule")
    void whatIsNotAnOptionScheduleStays() {
        String q = "어제 오후 4시에 결제, 언제 받나요?";
        assertThat(InquiryDraftComposer.unaskedSchedule("면 침구 세트(퀸)는 재고 상품으로 공통 배송 기준을 따르며, 오후 3시 이후 결제 주문은 "
                + "다음 영업일에 출고합니다.", null, q, COLOURS)).isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("주문 후 제작하는 상품이라 제작에 영업일 기준 10~14일이 걸립니다.", null,
                "언제 받나요? 오늘 보내주나요?", NONE)).isEmpty();
        assertThat(InquiryDraftComposer.unaskedSchedule("색상은 아이보리, 그레이 두 가지이며 세탁 후 3~5% 줄어들 수 있습니다.", null,
                "세탁하면 줄어드나요?", COLOURS)).as("no stock said").isEmpty();
    }

    @Test
    @DisplayName("replay: of the 18 real B-plan drafts, only q13 P2 has an unasked option schedule")
    void everyBPlanDraftReplayed() throws IOException {
        List<String> hit = new ArrayList<>();
        int read = 0;
        for (String line : new String(getClass().getResourceAsStream("/inquiry/bplan-drafts-20261116.tsv").readAllBytes(),
                StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", 5);
            read++;
            if (!InquiryDraftComposer.unaskedSchedule(f[4], null, f[3], COLOURS).isEmpty()) {
                hit.add(f[0] + " " + f[1] + " " + f[2]);
            }
        }
        assertThat(read).isEqualTo(18);
        assertThat(hit).containsExactly("P2 q13 S0");
    }
}
