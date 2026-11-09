package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A figure's ground, kept at save and read again at recheck (2026-11-01 review). The synthetic case the review gave: an
 * answer 「수령 후 7일 이내 교환 가능합니다」 over a policy then changed to 14일 must not stand — and 「the clause was
 * rewritten but the number is still there」 must not be read as either 「still right」 or 「now wrong」.
 */
class InquiryFigureGroundsTest {

    private static final UUID POLICY = UUID.randomUUID();
    private static final String SEVEN = "교환 · 반품 안내\n수령 후 7일 이내 교환 가능합니다. 왕복 배송비는 6,000원입니다.";

    private static InquiryDraftFigure only(String body, String material, String order, String asked) {
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground(body,
                List.of(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, POLICY, null, material)),
                order, asked, s -> s.text(), List.of());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void aPolicyFigureKeepsItsClauseAndSource() {
        InquiryDraftFigure row = only("수령 후 7일 이내에 교환하실 수 있어요.", SEVEN, null, "교환 되나요");
        assertThat(row.getGroundKind()).isEqualTo("ORG_POLICY");
        assertThat(row.getSourceId()).isEqualTo(POLICY);
        assertThat(row.getValue()).isEqualTo("7");
        assertThat(row.getUnit()).isEqualTo("일");
        assertThat(row.getGroundClause()).isEqualTo("수령 후 7일 이내 교환 가능합니다");
    }

    @Test
    void theReviewsCaseIsNowWrong() {
        InquiryDraftFigure row = only("수령 후 7일 이내 교환 가능합니다.", SEVEN, null, "교환 되나요");
        String fourteen = "교환 · 반품 안내\n수령 후 14일 이내 교환 가능합니다. 왕복 배송비는 6,000원입니다.";
        assertThat(InquiryFigureGrounds.verdict(row, fourteen)).isEqualTo(InquiryFigureGrounds.Verdict.CHANGED);
    }

    @Test
    void aLongerNumberIsNotTheSameClause() {
        InquiryDraftFigure row = only("7일 이내 교환 가능합니다.", "7일 이내 교환 가능합니다.", null, "교환 되나요");
        assertThat(InquiryFigureGrounds.verdict(row, "17일 이내 교환 가능합니다."))
                .isEqualTo(InquiryFigureGrounds.Verdict.CHANGED);
    }

    @Test
    void anEditElsewhereLeavesItStanding() {
        InquiryDraftFigure row = only("수령 후 7일 이내 교환 가능합니다.", SEVEN, null, "교환 되나요");
        String cheaper = "교환 · 반품 안내\n수령 후 7일 이내 교환 가능합니다. 왕복 배송비는 5,000원입니다.";
        assertThat(InquiryFigureGrounds.verdict(row, cheaper)).isEqualTo(InquiryFigureGrounds.Verdict.STANDS);
    }

    @Test
    void aRewrittenClauseWithTheNumberStillThereIsUnverified() {
        InquiryDraftFigure row = only("수령 후 7일 이내 교환 가능합니다.", SEVEN, null, "교환 되나요");
        String split = "교환 · 반품 안내\n단순 변심 교환은 수령 후 14일 이내, 불량은 7일 이내 접수해 주세요.";
        assertThat(InquiryFigureGrounds.verdict(row, split)).isEqualTo(InquiryFigureGrounds.Verdict.UNVERIFIED);
        assertThat(InquiryFigureGrounds.verdict(row, null)).as("the policy is gone or retired")
                .isEqualTo(InquiryFigureGrounds.Verdict.UNVERIFIED);
    }

    @Test
    void anOrderOrAQuestionFigureIsNotTheSellersKnowledge() {
        InquiryDraftFigure order = only("3일 이내 출고 예정입니다.", SEVEN, "주문은 3일 이내 출고 예정입니다.", "언제 오나요");
        assertThat(order.getGroundKind()).isEqualTo("ORDER");
        assertThat(order.getGroundClause()).as("the order sentence is not kept").isNull();
        assertThat(InquiryFigureGrounds.verdict(order, null)).isEqualTo(InquiryFigureGrounds.Verdict.STANDS);

        InquiryDraftFigure asked = only("말씀하신 2개 기준으로 안내드려요.", SEVEN, null, "2개 사면 되나요");
        assertThat(asked.getGroundKind()).isEqualTo("QUESTION");
        assertThat(asked.getGroundClause()).as("the customer's text is not kept").isNull();
    }

    @Test
    void theClauseIsTakenThroughTheRecheckReader() {
        // Shown as the decision wrote it; kept as the reader the recheck will use reads it.
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground("용량은 500ml예요.",
                List.of(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.PRODUCT_FACTS, null, UUID.randomUUID(),
                        "상품 등록 정보: 용량 500 ml")),
                null, "용량이 얼마예요", s -> "capacity: 500 ml\n색상: 화이트", List.of());
        assertThat(rows.get(0).getGroundClause()).isEqualTo("capacity: 500 ml");
        assertThat(InquiryFigureGrounds.verdict(rows.get(0), "capacity: 500 ml\n색상: 화이트"))
                .isEqualTo(InquiryFigureGrounds.Verdict.STANDS);
    }

    // ── the claim check's link (2026-11-01 third review) ─────────────────────────────────────────────────────────────

    private static final UUID SHIPPING = UUID.randomUUID();
    private static final UUID EXCHANGE = UUID.randomUUID();

    private static List<InquiryFigureGrounds.Shown> twoPolicies() {
        return java.util.Arrays.asList(
                new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, SHIPPING, null,
                        "배송 안내\n결제 후 7일 이내 출고됩니다."),
                null, // a past answer: shown to the drafter, never a ground
                new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, EXCHANGE, null,
                        "교환 안내\n수령 후 7일 이내 교환 가능합니다."));
    }

    private static InquiryFigureGrounds.Link link(String sentence, String verdict, Integer passage, String source,
                                                  String about) {
        return new InquiryFigureGrounds.Link("7", "일", sentence, verdict, passage, source, about, null);
    }

    @Test
    void theNamedPassageIsTheGroundNotTheFirstThatStatesTheNumber() {
        String reply = "교환은 수령 후 7일 이내 가능합니다.";
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground(reply, twoPolicies(), null, "교환 되나요",
                s -> s.text(), List.of(link(reply, "SUPPORTED", 3, "근거", "교환")));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getSourceId()).isEqualTo(EXCHANGE);
            assertThat(r.getGroundClause()).isEqualTo("수령 후 7일 이내 교환 가능합니다");
            assertThat(r.getAbout()).isEqualTo("교환");
            assertThat(r.getVoice()).isEqualTo("SELLER");
            assertThat(r.getLinkedBy()).isEqualTo("MODEL");
        });
        // The exchange policy moves to 14일 while shipping stays at 7일: the answer is now wrong, not 「still stated」.
        assertThat(InquiryFigureGrounds.verdict(rows.get(0), "교환 안내\n수령 후 14일 이내 교환 가능합니다."))
                .isEqualTo(InquiryFigureGrounds.Verdict.CHANGED);
    }

    @Test
    void withoutALinkTwoPlacesStatingTheNumberGroundNeither() {
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground("교환은 수령 후 7일 이내 가능합니다.", twoPolicies(),
                null, "교환 되나요", s -> s.text(), List.of());
        assertThat(rows.get(0).getGroundKind()).isEqualTo("NONE");
        assertThat(InquiryFigureGrounds.verdict(rows.get(0), "anything")).isEqualTo(InquiryFigureGrounds.Verdict.UNVERIFIED);
    }

    @Test
    void aLinkToALineThatIsNotTheSellersMaterialGroundsNothing() {
        String reply = "교환은 수령 후 7일 이내 가능합니다.";
        assertThat(InquiryFigureGrounds.ground(reply, twoPolicies(), null, "교환 되나요", s -> s.text(),
                List.of(link(reply, "SUPPORTED", 2, "근거", "교환"))).get(0).getGroundKind()).isEqualTo("NONE");
        assertThat(InquiryFigureGrounds.ground(reply, twoPolicies(), null, "교환 되나요", s -> s.text(),
                List.of(link(reply, "SUPPORTED", 9, "근거", "교환"))).get(0).getGroundKind()).isEqualTo("NONE");
    }

    @Test
    void twoClausesInTheNamedPassageAreBothKeptUnlessWhatItIsAboutSinglesOneOut() {
        List<InquiryFigureGrounds.Shown> one = List.of(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY,
                POLICY, null, "배송 · 교환 안내\n결제 후 7일 이내 출고됩니다. 수령 후 7일 이내 교환 가능합니다."));
        String reply = "수령하신 뒤 7일 안에 교환하실 수 있어요.";
        InquiryDraftFigure both = InquiryFigureGrounds.ground(reply, one, null, "교환 되나요", s -> s.text(),
                List.of(link(reply, "SUPPORTED", 1, "근거", "교환 기한"))).get(0);
        assertThat(both.getGroundClause()).isEqualTo("결제 후 7일 이내 출고됩니다\n수령 후 7일 이내 교환 가능합니다");
        // Both must still stand: shipping moving to 5일 leaves the exchange answer unproven, never 「still right」.
        assertThat(InquiryFigureGrounds.verdict(both, "배송 · 교환 안내\n결제 후 5일 이내 출고됩니다. 수령 후 7일 이내 교환 가능합니다."))
                .isEqualTo(InquiryFigureGrounds.Verdict.UNVERIFIED);

        InquiryDraftFigure named = InquiryFigureGrounds.ground(reply, one, null, "교환 되나요", s -> s.text(),
                List.of(link(reply, "SUPPORTED", 1, "근거", "교환"))).get(0);
        assertThat(named.getGroundClause()).isEqualTo("수령 후 7일 이내 교환 가능합니다");
    }

    @Test
    void aNumberSaidBackIsTheCustomersWord() {
        String reply = "문의 주신 20가닥 기준으로 말씀드리면, 한 번에 넣기는 어렵습니다.";
        InquiryDraftFigure row = InquiryFigureGrounds.ground(reply, List.of(), null, "20가닥 사면 되나요",
                s -> s.text(), List.of(new InquiryFigureGrounds.Link("20", "가닥", reply, "SAID_BACK", null, "문의",
                        "가닥 수", null))).get(0);
        assertThat(row.getGroundKind()).isEqualTo("QUESTION");
        assertThat(row.getVoice()).isEqualTo("CUSTOMER");
        assertThat(row.getGroundClause()).as("the customer's text is not kept").isNull();
    }
}
