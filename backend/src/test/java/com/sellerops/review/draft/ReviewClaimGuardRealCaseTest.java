package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.InquiryClaimGuard;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Two failures the first real-model round found</b> (2026-10-31, {@code docs/standing/reply_agent_v1.md} §8),
 * reproduced in synthetic sentences — no customer's words are in this file.
 *
 * <ul>
 *   <li><b>C06 — a grant that leaves out its condition.</b> The policy allowed a return within 7 days for an unopened
 *       product only; the draft said 「7일 이내 반품 가능」. Every word of it is in the policy, so every check the guard
 *       had passed a reply that grants a return for an opened product.</li>
 *   <li><b>C03 — saying back is not promising.</b> The customer wrote that they nearly returned it; the draft said 「반품까지
 *       고민하게 해 드린 점」 and the noun check refused it as an unsupported return, three drafts running, until the
 *       review went to a person with nothing but 「계속 실패」.</li>
 * </ul>
 */
class ReviewClaimGuardRealCaseTest {

    private static final String POLICY = "교환·반품은 상품 수령 후 7일 이내, 미개봉 상품에 한해 가능합니다.";
    private static final String DROPPED = "수령 후 7일 이내 반품 가능합니다.";

    @Test
    void theOldChecksAllPassedAGrantWithItsConditionLeftOut() {
        List<String> policy = List.of(POLICY);
        assertThat(ReviewClaimGuard.unsupportedClaims(DROPPED, policy)).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions(DROPPED, policy, "언제까지 반품되나요")).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises(DROPPED, policy)).isEmpty();
        assertThat(ReviewClaimGuard.undecidedPromises(DROPPED, policy)).isEmpty();

        assertThat(ReviewClaimGuard.droppedConditions(DROPPED, policy, "언제까지 반품되나요")).containsExactly("미개봉");
    }

    @Test
    void theConditionIsKeptWhereverTheDraftSaysIt() {
        List<String> policy = List.of(POLICY);
        assertThat(ReviewClaimGuard.droppedConditions("미개봉 상품은 수령 후 7일 이내 반품 가능합니다.", policy, ""))
                .isEmpty();
        assertThat(ReviewClaimGuard.droppedConditions(
                "수령 후 7일 이내 반품 가능합니다. 단, 개봉하지 않은 상품만 해당돼요.", policy, "")).isEmpty();
    }

    @Test
    void aCustomerWhoSaidTheirsIsUnopenedNeedsNoConditionRepeated() {
        List<String> policy = List.of(POLICY);
        assertThat(ReviewClaimGuard.droppedConditions(DROPPED, policy, "아직 포장 안 뜯었는데 반품되나요?")).isEmpty();
        assertThat(ReviewClaimGuard.droppedConditions(DROPPED, policy, "개봉했는데 반품되나요?"))
                .as("an opened product is exactly the case the condition is for").containsExactly("미개봉");
    }

    @Test
    void aCustomerWhoSaidTheirsIsOpenedIsNeverToldYesOnTheCondition() {
        // Seller decision 2026-11-01: 「이미 개봉했다고 밝혔다면 교환 가능으로 단정하지 않는다」.
        List<String> policy = List.of(POLICY);
        String opened = "어제 받아서 개봉했는데 색이 생각과 달라요. 교환되나요?";
        assertThat(ReviewClaimGuard.leftStates(opened)).containsExactly("미개봉");
        assertThat(ReviewClaimGuard.droppedConditions("네, 수령 후 7일 이내 교환 가능합니다. 미개봉 상품 기준입니다.",
                policy, opened)).as("the state said beside a yes is still a yes to them").containsExactly("미개봉");
        assertThat(ReviewClaimGuard.droppedConditions("교환은 수령 후 7일 이내, 미개봉 상품에 한해 가능합니다.",
                policy, opened)).as("the policy read to them is not a yes").isEmpty();
        assertThat(ReviewClaimGuard.droppedConditions(
                "개봉하신 상품은 교환이 어렵습니다. 다만 불량이라면 7일 이내 교환 가능합니다.", policy, opened))
                .as("an exception the evidence does not have").containsExactly("미개봉");
        assertThat(ReviewClaimGuard.droppedConditions("불량이라면 30일 이내 교환 가능합니다.",
                List.of(POLICY, "불량 상품은 수령 후 30일 이내 교환 가능합니다."), opened))
                .as("another reason the evidence does have is the claim check's to read").isEmpty();
        assertThat(ReviewDraftComposer.refusalOf("수령 후 7일 이내 교환 가능합니다. 단, 미개봉 상품만 해당돼요.", policy,
                opened)).isEqualTo(ReviewDraftComposer.DROPPED_CONDITION);
        assertThat(InquiryClaimGuard.unsupportedClaims("수령 후 7일 이내 교환 가능합니다. 단, 미개봉 상품만 해당돼요.",
                policy, List.of(), opened)).extracting(InquiryClaimGuard.Violation::kind)
                .containsExactly(InquiryClaimGuard.Kind.CONDITION);
    }

    @Test
    void notOpenedIsNotOpened() {
        for (String kept : List.of("아직 안 뜯었어요", "개봉 안 했는데 교환되나요", "포장 그대로예요", "한 번도 안 썼어요")) {
            assertThat(ReviewClaimGuard.leftStates(kept)).as(kept).isEmpty();
        }
        assertThat(ReviewClaimGuard.leftStates("하나는 뜯었고 하나는 안 뜯었어요")).containsExactly("미개봉");
        assertThat(ReviewClaimGuard.leftStates("한 번 입어봤는데 사이즈가 커요")).containsExactly("미착용");
        assertThat(ReviewClaimGuard.leftStates("언제까지 교환되나요")).isEmpty();
    }

    @Test
    void whatTheCheckLeavesToTheModel() {
        // One grant without the state: which case the draft is about is the claim check's to read, not this list's.
        assertThat(ReviewClaimGuard.droppedConditions(DROPPED,
                List.of(POLICY, "불량 상품은 수령 후 30일 이내 반품 가능합니다."), "")).isEmpty();
        // A refusal grants nothing to leave a condition out of.
        assertThat(ReviewClaimGuard.droppedConditions("개봉한 상품은 반품이 어렵습니다.", List.of(POLICY), "")).isEmpty();
    }

    @Test
    void bothLanesRefuseTheDraft() {
        // An exchange: the seller's own service, so its unopened limit is the seller's policy to keep.
        String exchange = "수령 후 7일 이내 교환 가능합니다.";
        assertThat(ReviewDraftComposer.refusalOf(exchange, List.of(POLICY), "언제까지 교환되나요"))
                .isEqualTo(ReviewDraftComposer.DROPPED_CONDITION);
        assertThat(InquiryClaimGuard.unsupportedClaims(exchange, List.of(POLICY), List.of(), "언제까지 교환되나요"))
                .extracting(InquiryClaimGuard.Violation::kind).containsExactly(InquiryClaimGuard.Kind.CONDITION);
        assertThat(InquiryClaimGuard.unsupportedClaims("미개봉 상품은 수령 후 7일 이내 교환 가능합니다.", List.of(POLICY),
                List.of(), "언제까지 교환되나요")).isEmpty();
        // The 2026-10-31 C06 return itself: refused before fidelity is read — see theLawComesBeforeThePolicy.
        assertThat(ReviewDraftComposer.refusalOf(DROPPED, List.of(POLICY), "언제까지 반품되나요"))
                .isEqualTo(ReviewDraftComposer.LAW_CONFLICT);
    }

    @Test
    void theLawComesBeforeThePolicy() {
        // 2026-11-01 review: following the seller's 「미개봉 상품에 한해」 is one check; whether it may be applied is another.
        // 전자상거래법 제17조 제2항 제1호 단서 — opening the packaging to see the contents does not end the right to withdraw.
        List<String> policy = List.of(POLICY);
        assertThat(ReviewClaimGuard.lawConflicts("반품은 미개봉 상품에 한해 가능합니다.", policy, ""))
                .containsExactly(ReviewClaimGuard.LAW_WITHDRAWAL);
        assertThat(ReviewClaimGuard.lawConflicts("개봉하신 상품은 반품이 어렵습니다.", List.of(), ""))
                .as("refused for opening, whatever the evidence says").containsExactly(ReviewClaimGuard.LAW_WITHDRAWAL);
        assertThat(ReviewClaimGuard.lawConflicts("수령 후 7일 이내 반품 신청이 가능합니다.", policy, ""))
                .as("a return answer standing on an unopened-only policy is the seller's to confirm")
                .containsExactly(ReviewClaimGuard.LAW_WITHDRAWAL);
        assertThat(ReviewClaimGuard.lawConflicts("수령 후 7일 이내 반품 신청이 가능합니다.",
                List.of("반품은 수령 후 7일 이내 가능합니다."), "")).isEmpty();
        assertThat(ReviewClaimGuard.lawConflicts("교환은 수령 후 7일 이내, 미개봉 상품에 한해 가능합니다.", policy, ""))
                .as("an exchange for change of mind is the seller's own service").isEmpty();
        assertThat(ReviewClaimGuard.lawConflicts("반품까지 고민하게 해 드린 점 죄송합니다.", policy, NEARLY_RETURNED))
                .as("saying back applies nothing").isEmpty();
        assertThat(InquiryClaimGuard.unsupportedClaims("반품은 미개봉 상품에 한해 가능합니다.", policy, List.of(), ""))
                .extracting(InquiryClaimGuard.Violation::kind).contains(InquiryClaimGuard.Kind.LAW);
    }

    @Test
    void aFaultIsNotHeldToChangeOfMindTerms() {
        // 제17조 제3항: goods unlike their listing, or not as contracted, may be withdrawn within 3 months.
        List<String> policy = List.of(POLICY);
        String defect = "받아 보니 불량이에요. 교환되나요?";
        assertThat(ReviewClaimGuard.lawConflicts("교환은 미개봉 상품에 한해 가능합니다.", policy, defect))
                .containsExactly(ReviewClaimGuard.LAW_FAULT);
        assertThat(ReviewClaimGuard.lawConflicts("단순 변심이 아니어도 개봉하셨다면 교환이 어렵습니다.", policy,
                "다른 상품이 왔어요")).contains(ReviewClaimGuard.LAW_FAULT);
        assertThat(ReviewClaimGuard.droppedConditions("불량 상품은 교환 신청하실 수 있습니다.", policy, defect))
                .as("the seller's change-of-mind condition does not govern a fault").isEmpty();
        assertThat(ReviewClaimGuard.lawConflicts("불량 상품은 수령 후 7일 이내 반품 신청하실 수 있습니다.", policy, defect))
                .as("a fault is not held to the unopened-only return rule").isEmpty();
        assertThat(ReviewDraftComposer.refusalOf("교환은 미개봉 상품에 한해 가능합니다.", policy, defect))
                .isEqualTo(ReviewDraftComposer.LAW_CONFLICT);
    }

    private static final String NEARLY_RETURNED = "처음엔 잘 안 나와서 반품하려다가 그냥 쓰기로 했어요.";

    @Test
    void aRemedyTheCustomerNamedAndTheDraftOnlySaysBackIsNoPromise() {
        String draft = "처음에 잘 나오지 않아 반품까지 고민하게 해 드린 점 죄송합니다. 지금은 잘 쓰신다니 다행입니다.";
        assertThat(ReviewClaimGuard.unsupportedClaims(draft, List.of()))
                .as("the noun check alone — what refused C03").containsExactly("반품");
        assertThat(ReviewClaimGuard.unsupportedClaims(draft, List.of(), NEARLY_RETURNED)).isEmpty();
        assertThat(ReviewDraftComposer.refusalOf(draft, List.of(), NEARLY_RETURNED)).isNull();
        assertThat(ReviewDraftComposer.refusalOf("반품을 생각하셨을 만큼 불편하셨을 텐데 계속 써 주셔서 감사합니다.",
                List.of(), NEARLY_RETURNED)).isNull();
    }

    @Test
    void theCustomerSayingTheWordLicensesNoOffer() {
        assertThat(ReviewDraftComposer.refusalOf("반품을 고민하셨다면 무료 반품해 드리겠습니다.", List.of(), NEARLY_RETURNED))
                .isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        assertThat(ReviewDraftComposer.refusalOf("반품 신청은 주문 내역에서 하실 수 있어요.", List.of(), NEARLY_RETURNED))
                .isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        assertThat(ReviewDraftComposer.refusalOf(
                "반품까지 고민하게 해 드려 죄송합니다. 원하시면 반품 도와드리겠습니다.", List.of(), NEARLY_RETURNED))
                .as("one sentence saying back does not cover another offering").isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        assertThat(ReviewDraftComposer.refusalOf("반품까지 고민하게 해 드린 점 죄송합니다.", List.of(),
                "처음엔 잘 안 나왔어요.")).as("a remedy the customer never named is not theirs to say back")
                .isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        assertThat(ReviewDraftComposer.refusalOf("환불까지 고민하게 해 드린 점 죄송합니다.", List.of(), NEARLY_RETURNED))
                .as("the customer named a return, not a refund").isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
    }

    @Test
    void aFollowUpWithWordsBetweenIsStillAFollowUp() {
        // 2026-10-31 second round: three of six saved drafts promised a later answer in shapes the first pattern missed.
        for (String draft : List.of("판매자 문의로 남겨 주시면 확인해 안내드리겠습니다.",
                "문의로 알려 주시면 확인한 뒤 처리 방법을 안내드리겠습니다.",
                "연락 주시면 상황을 확인해 안내해 드리겠습니다.")) {
            assertThat(InquiryClaimGuard.unauthorizedFollowUps(draft, List.of())).as(draft).isNotEmpty();
        }
        // Not a seller's later answer: the customer is asked to check, or told where to look.
        assertThat(InquiryClaimGuard.unauthorizedFollowUps("부착면 상태를 확인해 주세요. 안내에 따라 다시 붙여 주세요.",
                List.of())).isEmpty();
        assertThat(InquiryClaimGuard.unauthorizedFollowUps("확인해 보시고 플랫폼 문의하기로 남겨 주세요.", List.of()))
                .isEmpty();
        // The seller's own sentence still authorizes it.
        assertThat(InquiryClaimGuard.unauthorizedFollowUps("확인해 안내드리겠습니다.",
                List.of("사진을 받아 확인해 안내드리겠습니다."))).isEmpty();
    }

    @Test
    void theStatutesOwnWordingForADefectIsNotAnUnopenedTermPutOnIt() {
        // 2026-11-01 fifth round: the reply quoted the store's new policy to a customer whose item arrived broken.
        List<String> policy = List.of("불량이거나 표시·광고 또는 계약 내용과 다른 상품을 받은 경우에는 개봉 여부와 관계없이, 상품을 받은 날부터 "
                + "3개월 이내, 그 사실을 안 날 또는 알 수 있었던 날부터 30일 이내에 교환·반품을 신청할 수 있습니다. 이 경우 반품에 드는 "
                + "비용은 판매자가 부담합니다.");
        String reply = "불량 상품은 개봉 여부와 관계없이 받은 날부터 3개월 이내 교환·반품을 신청하실 수 있고, 반품 비용은 판매자가 부담합니다.";
        String broken = "깨져서 왔어요";
        assertThat(ReviewClaimGuard.lawConflicts(reply, policy, broken)).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions(reply, policy, broken)).as("반품 비용 = 반품에 드는 비용").isEmpty();
        // Applying it is still caught.
        assertThat(ReviewClaimGuard.lawConflicts("미개봉 상품만 교환·반품이 가능합니다.", policy, broken))
                .contains(ReviewClaimGuard.LAW_FAULT);
    }
}
