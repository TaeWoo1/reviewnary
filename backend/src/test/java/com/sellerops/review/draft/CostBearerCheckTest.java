package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.InquiryClaimGuard;
import com.sellerops.review.draft.CostBearerCheck.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who pays the shipping (owner, 2026-11-10, F5). The rehearsal's three cases — no ground, a ground that agrees, a ground
 * that says the other way — in both directions, a ground for another case, a case the reply leaves open, and the two
 * lanes' own checks. The customer's question and the reply are never a ground.
 */
class CostBearerCheckTest {

    private static final String SELLER_ON_DEFECT = "포장이 파손돼 교환하는 경우 교환 배송비는 판매자가 부담해요.";
    private static final String CUSTOMER_ALWAYS = "교환 배송비는 고객이 부담해요.";
    private static final String CUSTOMER_ON_DEFECT = "포장이 파손돼 교환하는 경우에도 교환 배송비는 고객이 부담해요.";
    private static final String CUSTOMER_ON_CHANGE = "단순 변심으로 교환할 때 교환 배송비는 고객이 부담해요.";
    private static final String BY_CASE = "교환·반품 안내\n- 단순 변심: 왕복 배송비 6,000원 고객 부담\n- 상품 불량·오배송: 배송비 판매자 부담";
    private static final String NO_KIND = "배송비는 고객 부담이에요.";

    private static final String SAYS_SELLER = "교환 배송비는 포장 파손으로 인한 교환이라 저희가 부담해요.";
    private static final String SAYS_CUSTOMER = "포장 파손으로 교환해 드리지만 교환 배송비는 고객님이 부담해 주셔야 해요.";
    private static final String SAYS_SELLER_NO_CASE = "교환 배송비는 저희가 부담해요.";
    private static final String SAYS_CUSTOMER_ON_CHANGE = "단순 변심 교환은 교환 배송비를 고객님이 부담해 주셔야 해요.";

    private static Verdict verdict(String reply, String... policies) {
        List<CostBearerCheck.Source> rules = java.util.Arrays.stream(policies)
                .map(p -> new CostBearerCheck.Source("[데모] 교환 배송비 기준", p)).toList();
        List<CostBearerCheck.Finding> found = CostBearerCheck.judge(reply, List.of(), rules);
        assertThat(found).as("one bearer statement in 「%s」", reply).hasSize(1);
        return found.get(0).verdict();
    }

    @Test
    void aFollowingCostSentenceKeepsTheRemedyAndCase() {
        String reply = "단순 변심 반품은 수령 후 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 저희가 부담합니다.";
        String policy = "단순 변심 반품의 왕복 배송비 6,000원은 고객님이 부담합니다.";
        assertThat(verdict(reply, policy)).isEqualTo(Verdict.CONFLICT);
        assertThat(verdict(reply.replace("저희가", "고객님이"), policy)).isEqualTo(Verdict.BACKED);
        assertThat(verdict(reply)).isEqualTo(Verdict.UNBACKED);
        assertThat(verdict("파손 교환을 접수해 주세요. 교환 배송비는 고객님이 부담합니다.", SELLER_ON_DEFECT))
                .isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void aPayerNamedAsTheBuyerIsNotANewTopic() {
        // 「구매자」 · 「주문자」 are who pays, not an order sentence (2026-11-15, r4 review): the case is still carried.
        for (String payer : List.of("구매자", "주문자")) {
            assertThat(verdict("불량 반품은 사진을 보내 주세요. 반품 배송비는 " + payer + " 부담입니다.", BY_CASE))
                    .as(payer).isEqualTo(Verdict.CONFLICT);
            assertThat(verdict("단순 변심 반품은 7일 안에 신청해 주세요. 반품 배송비는 " + payer + " 부담입니다.", BY_CASE))
                    .as(payer).isEqualTo(Verdict.BACKED);
        }
    }

    @Test
    void aCostSentenceForOneOfTheNamedRemediesKeepsTheCase() {
        // The earlier sentence named both; the cost sentence names one of them — the same case still applies.
        assertThat(verdict("단순 변심 교환·반품은 7일 안에 신청해 주세요. 반품 배송비 6,000원은 저희가 부담합니다.", BY_CASE))
                .isEqualTo(Verdict.CONFLICT);
        // The other way round (a narrower sentence, then a wider cost) is not carried.
        assertThat(verdict("단순 변심 반품은 7일 안에 신청해 주세요. 교환·반품 배송비는 저희가 부담합니다.", BY_CASE))
                .isEqualTo(Verdict.UNCLEAR_CASE);
    }

    @Test
    void aFollowingCostSentenceCanStateItsOwnCase() {
        assertThat(verdict("단순 변심 교환을 안내해 드립니다. 파손 교환 배송비는 저희가 부담해요.", SELLER_ON_DEFECT))
                .isEqualTo(Verdict.BACKED);
        assertThat(verdict("파손 교환을 안내해 드립니다. 단순 변심 교환 배송비는 고객님이 부담해요.", CUSTOMER_ON_CHANGE))
                .isEqualTo(Verdict.BACKED);
    }

    @Test
    void anotherTopicOrParagraphDoesNotBorrowTheEarlierCase() {
        for (String boundary : List.of("주문은 오늘 출고됩니다. ", "확인 후 안내하겠습니다. ", "\n\n")) {
            assertThat(verdict("파손 교환을 안내해 드립니다. " + boundary + "교환 배송비는 저희가 부담해요.", SELLER_ON_DEFECT))
                    .as(boundary).isEqualTo(Verdict.UNCLEAR_CASE);
        }
    }

    @Test
    void anotherRemedyOrMixedCasesDoNotBorrowTheEarlierCase() {
        assertThat(verdict("파손 교환을 접수해 주세요. 반품 배송비는 저희가 부담해요.",
                "파손 반품 배송비는 고객이 부담해요.")).isEqualTo(Verdict.UNCLEAR_CASE);
        assertThat(verdict("파손과 단순 변심 교환을 안내해 드립니다. 교환 배송비는 저희가 부담해요.", SELLER_ON_DEFECT))
                .isEqualTo(Verdict.UNCLEAR_CASE);
    }

    @Test
    void aDocumentsEarlierConditionDoesNotTurnItsGeneralRuleIntoASpecificOne() {
        String policy = "파손 교환은 사진을 보내 주세요. 교환 배송비는 고객이 부담해요.";
        assertThat(verdict(SAYS_CUSTOMER_ON_CHANGE, policy)).isEqualTo(Verdict.BACKED);
    }

    @Test
    void theRehearsalsThreeCases() {
        assertThat(verdict(SAYS_SELLER)).as("no ground").isEqualTo(Verdict.UNBACKED);
        assertThat(verdict(SAYS_SELLER, SELLER_ON_DEFECT)).as("a ground that agrees").isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_SELLER, CUSTOMER_ALWAYS)).as("a ground that says the other way").isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void theOtherDirection() {
        assertThat(verdict(SAYS_CUSTOMER)).isEqualTo(Verdict.UNBACKED);
        assertThat(verdict(SAYS_CUSTOMER, CUSTOMER_ON_DEFECT)).isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_CUSTOMER, CUSTOMER_ALWAYS)).as("a ground for every case").isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_CUSTOMER, SELLER_ON_DEFECT)).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void aGroundForAnotherCaseIsNeitherAMatchNorAConflict() {
        assertThat(verdict(SAYS_SELLER, CUSTOMER_ON_CHANGE)).isEqualTo(Verdict.OTHER_CASE);
        assertThat(verdict(SAYS_CUSTOMER_ON_CHANGE, SELLER_ON_DEFECT)).isEqualTo(Verdict.OTHER_CASE);
    }

    @Test
    void aPolicyThatSetsCasesApartDecidesOnlyTheCaseTheReplyNames() {
        assertThat(verdict(SAYS_SELLER, BY_CASE)).as("파손 → 판매자").isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_CUSTOMER, BY_CASE)).as("파손 → 판매자, said 고객").isEqualTo(Verdict.CONFLICT);
        assertThat(verdict(SAYS_CUSTOMER_ON_CHANGE, BY_CASE)).as("변심 → 고객").isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_SELLER_NO_CASE, BY_CASE)).as("which case is not said").isEqualTo(Verdict.UNCLEAR_CASE);
        // A specific statement of the reply's own case wins over the document's general one.
        assertThat(verdict(SAYS_SELLER, CUSTOMER_ALWAYS + " 단, 포장이 파손된 교환은 교환 배송비를 판매자가 부담해요."))
                .isEqualTo(Verdict.BACKED);
    }

    @Test
    void anOrdersOwnShippingFeeIsNotABearerStatement() {
        // 2026-11-15 real eval q10: an answer to 「5만원 넘으면 무료배송이죠?」 was held as 「배송비 판매자 부담」 unclear.
        String policy = "배송비는 3,000원이며, 7만원 이상 구매 시 무료입니다. (2026-11-01부터 적용. 그 전에는 5만원 이상 무료였습니다.)";
        assertThat(CostBearerCheck.judge("11월 1일부터 7만원 이상 구매하시면 배송비는 무료이고, 그보다 적으면 배송비 3,000원을 "
                + "고객님이 부담하십니다.", List.of(policy), List.of())).isEmpty();
        // A reply that speaks of a return still has its cost sentences read — even one that does not name the remedy.
        List<CostBearerCheck.Finding> inAReturn = CostBearerCheck.judge(
                "단순 변심 반품은 7일 안에 신청해 주세요. 감사합니다. 배송비는 저희가 부담할게요.", List.of(policy), List.of());
        assertThat(inAReturn).singleElement().satisfies(f -> assertThat(f.verdict()).isNotEqualTo(Verdict.BACKED));
    }

    @Test
    void aGroundThatNamesNoKindIsNotForcedEitherWay() {
        assertThat(verdict(SAYS_SELLER, NO_KIND)).isEqualTo(Verdict.UNCLEAR_CASE);
        assertThat(verdict(SAYS_CUSTOMER, NO_KIND)).isEqualTo(Verdict.UNCLEAR_CASE);
    }

    @Test
    void neitherTheQuestionNorTheReplyIsAGround() {
        assertThat(CostBearerCheck.judge(SAYS_SELLER, List.of(), List.of()))
                .extracting(CostBearerCheck.Finding::verdict).containsExactly(Verdict.UNBACKED);
        // The inquiry guard is given the question and the seller's standing sentences; neither backs who pays.
        List<InquiryClaimGuard.Violation> claims = InquiryClaimGuard.unsupportedClaims(SAYS_SELLER, List.of(),
                List.of("교환 배송비는 판매자가 부담해요."), "교환 배송비는 판매자가 부담하나요?");
        assertThat(claims).extracting(InquiryClaimGuard.Violation::kind).contains(InquiryClaimGuard.Kind.COST);
    }

    @Test
    void whoPaysIsReadFromTheWordsAround() {
        assertThat(CostBearerCheck.statements("교환 배송비는 고객님은 부담하지 않으셔도 돼요."))
                .extracting(CostBearerCheck.Statement::bearer).containsExactly(CostBearerCheck.Bearer.SELLER);
        assertThat(CostBearerCheck.statements("판매자 사유로 교환하는 경우 교환 배송비는 고객님이 부담하지 않아요."))
                .singleElement().satisfies(s -> {
                    assertThat(s.bearer()).isEqualTo(CostBearerCheck.Bearer.SELLER);
                    assertThat(s.cause()).isEqualTo(CostBearerCheck.Cause.DEFECT);
                });
        assertThat(CostBearerCheck.statements("반품 시 배송비 6,000원을 차감하고 환불해 드려요."))
                .extracting(CostBearerCheck.Statement::bearer).containsExactly(CostBearerCheck.Bearer.CUSTOMER);
        assertThat(CostBearerCheck.statements("교환 배송비는 무료예요."))
                .extracting(CostBearerCheck.Statement::bearer).containsExactly(CostBearerCheck.Bearer.SELLER);
        assertThat(CostBearerCheck.statements("배송비는 상황에 따라 달라요.")).as("no bearer, nothing stated").isEmpty();
        assertThat(CostBearerCheck.statements("교환 배송비는 판매자와 고객이 나눠 부담해요.")).as("both named").isEmpty();
    }

    @Test
    void theReviewTermCheckLeavesABearerClauseToThisReading() {
        List<String> grant = List.of("배송 중 포장이 파손된 경우 사진을 확인한 뒤 교환해 드려요.");
        assertThat(ReviewClaimGuard.unsupportedConditions(SAYS_SELLER, grant, ""))
                .containsExactly("파손·불량 시 교환 배송비 판매자 부담").doesNotContain("배송비");
        assertThat(ReviewClaimGuard.unsupportedConditions(SAYS_SELLER, List.of(grant.get(0), SELLER_ON_DEFECT), ""))
                .isEmpty();
        // A cost word with no bearer is still the term check's.
        assertThat(ReviewClaimGuard.unsupportedConditions("교환 시 왕복 배송비가 들어요. 교환해 드려요.", grant, ""))
                .contains("배송비");
    }

    @Test
    void bothRechecksSettleAgainstEveryDocumentInUse() {
        List<String> license = List.of("배송 중 포장이 파손된 경우 사진을 확인한 뒤 교환해 드려요.");
        List<String> promises = ReviewClaimGuard.unsupportedConditions(SAYS_SELLER, license, "");
        ReviewDraftRecheck.Rule agrees = new ReviewDraftRecheck.Rule("ORG_POLICY", "[데모] 교환 배송비 기준", SELLER_ON_DEFECT);
        ReviewDraftRecheck.Rule says = new ReviewDraftRecheck.Rule("ORG_POLICY", "[데모] 교환 배송비 기준", CUSTOMER_ALWAYS);

        ReviewDraftRecheck.CostReading backed = ReviewDraftRecheck.settleCosts(promises, SAYS_SELLER, license, List.of(agrees));
        assertThat(backed.promises()).as("an uncited policy in use agrees").isEmpty();
        assertThat(backed.against()).isEmpty();

        ReviewDraftRecheck.CostReading conflict = ReviewDraftRecheck.settleCosts(promises, SAYS_SELLER, license, List.of(says));
        assertThat(conflict.against()).singleElement().satisfies(a -> {
            assertThat(a.word()).isEqualTo("파손·불량 시 교환 배송비 판매자 부담");
            assertThat(a.title()).isEqualTo("[데모] 교환 배송비 기준");
        });

        ReviewDraftRecheck.CostReading none = ReviewDraftRecheck.settleCosts(promises, SAYS_SELLER, license, List.of());
        assertThat(none.promises()).containsExactly("파손·불량 시 교환 배송비 판매자 부담");
        assertThat(none.against()).isEmpty();
    }

    /**
     * B134 (2026-11-10): a ground that states one case is not the ground of a promise for every case. A reply that names
     * no case is backed — or contradicted — only by a ground that applies whatever the case: one stated for no case, or
     * one that states every case the same way. Anything narrower is the unclear case's exception decision.
     */
    @Test
    void aGroundForOneCaseDoesNotBackAPromiseForEveryCase() {
        String sellerOnDamage = "파손 교환의 배송비는 판매자가 부담해요.";
        String saysCustomerNoCase = "교환 배송비는 고객님이 부담해 주셔야 해요.";
        assertThat(verdict(SAYS_SELLER_NO_CASE, sellerOnDamage)).as("B134 as reproduced").isEqualTo(Verdict.UNCLEAR_CASE);
        assertThat(verdict(SAYS_SELLER_NO_CASE, SELLER_ON_DEFECT)).isEqualTo(Verdict.UNCLEAR_CASE);
        assertThat(verdict(saysCustomerNoCase, SELLER_ON_DEFECT)).as("nor contradicted by it").isEqualTo(Verdict.UNCLEAR_CASE);

        // What a named case was already: kept.
        assertThat(verdict(SAYS_SELLER, sellerOnDamage)).isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_CUSTOMER, sellerOnDamage)).isEqualTo(Verdict.CONFLICT);
        assertThat(verdict(SAYS_CUSTOMER_ON_CHANGE, sellerOnDamage)).isEqualTo(Verdict.OTHER_CASE);

        // A ground that applies whatever the case still decides a reply that names none.
        assertThat(verdict(SAYS_SELLER_NO_CASE, "교환 배송비는 판매자가 부담해요.")).isEqualTo(Verdict.BACKED);
        assertThat(verdict(SAYS_SELLER_NO_CASE, CUSTOMER_ALWAYS)).isEqualTo(Verdict.CONFLICT);
        String everyCaseSeller = "단순 변심으로 교환할 때도 교환 배송비는 판매자가 부담해요. 파손 교환도 교환 배송비는 판매자가 부담해요.";
        assertThat(verdict(SAYS_SELLER_NO_CASE, everyCaseSeller)).as("every case the same way").isEqualTo(Verdict.BACKED);
        assertThat(verdict(saysCustomerNoCase, everyCaseSeller)).isEqualTo(Verdict.CONFLICT);
    }
}
