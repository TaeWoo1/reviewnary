package com.sellerops.inquiry.sellercheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.authority.AuthorityFence;
import com.sellerops.inquiry.authority.CapabilityId;
import com.sellerops.inquiry.authority.ResolutionState;
import com.sellerops.inquiry.decision.EvidenceCandidate;
import com.sellerops.inquiry.decision.EvidenceScope;
import com.sellerops.inquiry.decision.InquiryNeed;
import com.sellerops.inquiry.decision.NeedResult;
import com.sellerops.inquiry.decision.NeedStatus;
import com.sellerops.inquiry.decision.NeedType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two rules the seller's record lives under, without a database (2026-11-02 seller-check review). */
class SellerCheckRulesTest {

    @Test
    @DisplayName("only a record of DONE lets a reply call the action finished; 접수 is not a finished action")
    void finishedOnlyWhenRecordedFinished() {
        String said = "요청하신 환불은 접수되었습니다. 카드 결제를 취소해 환불했습니다.";
        assertThat(SellerCheckGuard.unrecordedCompletions(said, SellerCheckOutcome.DONE)).isEmpty();
        assertThat(SellerCheckGuard.unrecordedCompletions(said, SellerCheckOutcome.IN_PROGRESS))
                .containsExactly("환불했습니다");
        assertThat(SellerCheckGuard.unrecordedCompletions("현금영수증 발급을 완료했습니다.", SellerCheckOutcome.CANNOT))
                .containsExactly("발급을 완료했");
        assertThat(SellerCheckGuard.unrecordedCompletions("요청하신 환불은 접수되었고 현재 처리 중입니다.",
                SellerCheckOutcome.IN_PROGRESS)).isEmpty();
        assertThat(SellerCheckGuard.unrecordedCompletions("환불 처리가 어렵습니다.", SellerCheckOutcome.CANNOT)).isEmpty();
        // No record, no rule: a reply with no seller check is the claim guard's alone.
        assertThat(SellerCheckGuard.unrecordedCompletions(said, null)).isEmpty();
    }

    @Test
    @DisplayName("the fence: the seller's record closes an order action under the SELLER's name, never a policy need")
    void theSellerClosesWhatOnlyTheSellerCouldCheck() {
        EvidenceCandidate check = new EvidenceCandidate("E1", EvidenceCandidate.Kind.SELLER_CHECK, "판매자 확인 · 처리 완료",
                "판매자가 판매자센터에서 직접 확인한 이 문의의 결과: 처리 완료 — 카드 결제 취소로 환불", UUID.randomUUID(), null,
                "DONE", EvidenceScope.order("inquiry:" + UUID.randomUUID()));
        InquiryNeed action = new InquiryNeed("N1", "주문 환불 요청", NeedType.ORDER_ACTION, "환불");
        NeedResult closed = AuthorityFence.apply(List.of(new NeedResult(action, NeedStatus.FULL, List.of(check),
                List.of(), null, null, false)), null).get(0);
        assertThat(closed.status()).isEqualTo(NeedStatus.FULL);
        assertThat(closed.resolution().capability()).isEqualTo(CapabilityId.SELLER);
        assertThat(closed.resolution().state()).isEqualTo(ResolutionState.RESOLVED);

        // The same need with nothing from the seller stays an action nobody executed.
        NeedResult open = AuthorityFence.apply(List.of(new NeedResult(action, NeedStatus.NONE, List.of(),
                List.of(), null, null, false)), null).get(0);
        assertThat(open.resolution().state()).isEqualTo(ResolutionState.CAPABILITY_GAP);

        // One customer's result is not the store's rule.
        InquiryNeed policy = new InquiryNeed("N2", "환불 기준", NeedType.POLICY, "환불 기준");
        NeedResult notARule = AuthorityFence.apply(List.of(new NeedResult(policy, NeedStatus.FULL, List.of(check),
                List.of(), null, null, false)), null).get(0);
        assertThat(notARule.status().covered()).isFalse();
    }
}
