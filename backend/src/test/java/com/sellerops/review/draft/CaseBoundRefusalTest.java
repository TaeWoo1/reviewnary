package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A refusal limited to a case answers that case only</b> (2026-11-15 review, q06). The bedding answer that reads the
 * policy back — 7일, 왕복 6,000원 고객 부담, 세탁 · 사용한 침구는 반품 불가 — was held as 「정책과 반대」 because 「세탁하거나 사용한
 * 침구는 … 되지 않습니다」 and 「주문 제작 · 각인 상품은 단순 변심 … 되지 않습니다」 were read as refusing every return. A grant in
 * every case, a grant about the refused case, and a grant to a customer who said theirs is in it are still held; a grant
 * that leaves out the state the policy limits it to (미개봉) is still a dropped condition, and who pays is still read.
 */
class CaseBoundRefusalTest {

    private static final String POLICY = "[합성] 숲결리빙 교환·반품 안내 (기준일 2026-11-01)\n\n"
            + "- 상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다.\n"
            + "- 단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다.\n"
            + "- 상품 불량·오배송은 배송비 없이 교환 또는 환불해 드립니다. 사진을 함께 보내 주세요.\n"
            + "- 사용 흔적이 있거나 상품 가치가 훼손된 경우 교환·반품이 어렵습니다.\n"
            + "- 주문 제작 상품과 각인 상품은 단순 변심 교환·반품이 되지 않습니다. 상품별 안내를 확인해 주세요.\n";
    private static final String BEDDING = "[합성] 면 침구 세트(퀸) 상품 안내\n\n"
            + "- 재고 상품으로, 공통 배송 기준(평일 오후 3시 이전 결제 시 당일 출고)을 따릅니다.\n"
            + "- 세탁: 첫 세탁은 찬물 단독 세탁을 권합니다. 건조기 사용 시 3~5% 줄어들 수 있습니다.\n"
            + "- 세탁하거나 사용한 침구는 위생상 단순 변심 교환·반품이 되지 않습니다.\n";
    private static final List<String> BOTH = List.of(POLICY, BEDDING);
    private static final List<ReviewDraftRecheck.Rule> RULES = List.of(
            new ReviewDraftRecheck.Rule("ORG_POLICY", "교환·반품 안내", POLICY),
            new ReviewDraftRecheck.Rule("PRODUCT_KNOWLEDGE", "면 침구 세트", BEDDING));
    private static final String ASKED = "단순 변심 반품 배송비? 며칠 안에?";

    private static List<ReviewDraftRecheck.Against> against(String reply, String asked) {
        return ReviewDraftRecheck.otherRulesAgainst(reply, BOTH, RULES, asked);
    }

    @Test
    @DisplayName("a refusal and a grant joined by 「…고,」 are two clauses: each is read against its own case (q14 S1 final)")
    void aConnectiveGoSplitsTheClauses() {
        String asked = "교환은 받고 며칠 안에 신청해야 하나요?";
        String joined = "교환 신청은 수령 후 7일 이내에 가능합니다. 다만 사용 흔적이 있거나 상품 가치가 훼손된 경우에는 "
                + "교환·반품이 어렵고, 불량이면 무상으로 교환해 드립니다.";
        assertThat(ReviewClaimGuard.contradictedPromises(joined, BOTH, asked)).isEmpty();
        assertThat(ReviewClaimGuard.undecidedPromises(joined, BOTH, asked)).isEmpty();
        // Not loosened: the refused case granted is still the policy's opposite, joined or alone.
        String wrong = "사용 흔적이 있어도 교환해 드리고, 상품 가치가 훼손되어도 반품이 가능합니다.";
        assertThat(ReviewClaimGuard.contradictedPromises(wrong, BOTH, asked)).isNotEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("세탁하셨어도 반품이 가능하고, 배송비는 고객님이 부담합니다.", BOTH,
                asked)).isNotEmpty();
    }

    @Test
    @DisplayName("q06's answer, the policy read back with its exception, contradicts nothing — together or one by one")
    void q06Passes() {
        String reply = "단순 변심 반품은 상품을 받으신 뒤 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 고객님이 부담합니다. "
                + "세탁하거나 사용한 침구는 반품이 되지 않아요.";
        assertThat(ReviewClaimGuard.contradictedPromises(reply, BOTH, ASKED)).isEmpty();
        assertThat(ReviewClaimGuard.undecidedPromises(reply, BOTH, ASKED)).isEmpty();
        assertThat(against(reply, ASKED)).isEmpty();
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of("반품"), reply, BOTH, ASKED)).isEmpty();
        assertThat(against("교환은 받으신 뒤 7일 안에 신청해 주세요.", ASKED)).as("a grant silent about the washed case").isEmpty();
    }

    @Test
    @DisplayName("S0: an old notice that only states the return fee does not answer the bedding's backed exception")
    void anOldNoticeAlongside() {
        String notice = "[합성] (옛 공지, 2025-06-01) 반품 배송비 안내\n\n"
                + "단순 변심 반품 시 반품 배송비는 편도 3,000원입니다. 교환은 수령 후 14일 이내에 신청해 주세요.";
        List<String> all = List.of(POLICY, BEDDING, notice);
        List<ReviewDraftRecheck.Rule> rules = List.of(RULES.get(0), RULES.get(1),
                new ReviewDraftRecheck.Rule("ORG_POLICY", "옛 공지", notice));
        String reply = "단순 변심 반품은 수령 후 7일 안에 신청해 주세요. 단순 변심이면 왕복 배송비 6,000원은 고객님 부담이에요. "
                + "세탁하거나 사용한 침구는 단순 변심 반품이 되지 않아요.";
        assertThat(ReviewDraftRecheck.otherRulesAgainst(reply, all, rules, ASKED))
                .extracting(ReviewDraftRecheck.Against::word).doesNotContain("반품");
        assertThat(ReviewClaimGuard.contradictedPromises(reply, all, ASKED)).isEmpty();
        assertThat(ReviewDraftRecheck.otherRulesAgainst("세탁한 침구도 단순 변심 반품이 됩니다.", all, rules, ASKED))
                .extracting(ReviewDraftRecheck.Against::word).contains("반품");
    }

    @Test
    @DisplayName("held as before: a grant in every case, a grant about the refused case, a customer who washed it")
    void stillHeld() {
        assertThat(against("어떤 경우든 반품 가능합니다.", ASKED)).isNotEmpty();
        assertThat(against("세탁하셨어도 단순 변심 반품 가능합니다.", ASKED)).extracting(ReviewDraftRecheck.Against::word)
                .contains("반품");
        assertThat(against("반품은 받으신 뒤 7일 안에 신청해 주세요.", "한 번 세탁했는데 반품되나요?"))
                .extracting(ReviewDraftRecheck.Against::word).contains("반품");
        assertThat(against("반품은 받으신 뒤 7일 안에 신청해 주세요.", "아직 세탁 안 했는데 반품되나요?"))
                .as("not washed: the refusal is not theirs").isEmpty();
        assertThat(against("주문 제작 상품도 단순 변심 반품이 가능합니다.", ASKED)).isNotEmpty();
    }

    @Test
    @DisplayName("a reply's own exception the material does not make is not set apart from the grant")
    void anExceptionNotInTheMaterial() {
        // Only a case the material refuses too sets a grant apart; 개봉 is not one here, so the reply's refusal is read
        // against the grant exactly as before this change (and its 개봉 is the condition check's to name).
        List<String> grant = List.of("상품 수령 후 7일 이내에 반품을 신청할 수 있습니다.");
        String reply = "반품은 받으신 뒤 7일 안에 신청해 주세요. 개봉한 상품은 반품이 되지 않습니다.";
        assertThat(ReviewClaimGuard.contradictedPromises(reply, grant, ASKED))
                .isEqualTo(ReviewClaimGuard.contradictedPromises(reply, grant));
        assertThat(ReviewClaimGuard.unsupportedConditions(reply, grant, ASKED)).isNotEmpty();
    }

    @Test
    @DisplayName("a dropped kept state and who pays are read exactly as before")
    void droppedAndCostStay() {
        List<String> unopened = List.of("미개봉 상품에 한해 수령 후 7일 이내 반품 가능합니다.");
        assertThat(ReviewClaimGuard.droppedConditions("수령 후 7일 이내 반품 가능합니다.", unopened, "반품 되나요?"))
                .containsExactly("미개봉");
        ReviewDraftRecheck.CostReading cost = ReviewDraftRecheck.settleCosts(List.of(),
                "단순 변심 반품의 왕복 배송비 6,000원은 저희가 부담합니다.", BOTH, RULES);
        assertThat(cost.against()).isNotEmpty();
    }
}
