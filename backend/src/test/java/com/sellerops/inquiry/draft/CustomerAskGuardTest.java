package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.decision.InquiryNeed;
import com.sellerops.inquiry.decision.NeedDecision;
import com.sellerops.inquiry.decision.NeedResult;
import com.sellerops.inquiry.decision.NeedStatus;
import com.sellerops.inquiry.decision.NeedType;
import com.sellerops.inquiry.sellercheck.SellerCheckOutcome;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the deterministic ask guard reads, and when it reads nothing (2026-11-06 thirteenth review). */
class CustomerAskGuardTest {

    private static NeedDecision decision(NeedStatus status) {
        return new NeedDecision(NeedDecision.Outcome.DECIDED, List.of(new NeedResult(
                new InquiryNeed("N1", "교환할 수 있는지", NeedType.POLICY, "교환"), status, List.of(), List.of(), null,
                status == NeedStatus.CONDITIONAL_ON_CUSTOMER ? "어떤 옵션인지" : null, false)),
                AnswerBasisState.GROUNDED, null, 0, 0, null);
    }

    @Test
    @DisplayName("with nothing left for the customer, every sentence ending in a question mark is an unrequested ask")
    void nothingAllowed() {
        assertThat(CustomerAskGuard.unrequestedAsks("교환 접수되었습니다. 원하시는 색상을 알려주시겠어요? 감사합니다.",
                decision(NeedStatus.FULL), SellerCheckOutcome.IN_PROGRESS, null))
                .containsExactly("원하시는 색상을 알려주시겠어요?");
        assertThat(CustomerAskGuard.unrequestedAsks("교환 접수되었습니다.\n색상은요？", decision(NeedStatus.FULL), null, null))
                .containsExactly("색상은요？");
    }

    @Test
    @DisplayName("a closing that is not a question is not read")
    void aClosingIsNotAQuestion() {
        assertThat(CustomerAskGuard.unrequestedAsks("교환 접수되었습니다. 궁금한 점은 언제든 말씀해 주세요.",
                decision(NeedStatus.FULL), null, null)).isEmpty();
    }

    @Test
    @DisplayName("allowed — and left to the claim check — when the judgement asks the customer, the 규격 line says to ask, "
            + "or the seller recorded needing the customer; never without a need decision")
    void allowed() {
        String body = "어떤 옵션을 쓰시나요?";
        assertThat(CustomerAskGuard.unrequestedAsks(body, decision(NeedStatus.CONDITIONAL_ON_CUSTOMER), null, null)).isEmpty();
        assertThat(CustomerAskGuard.unrequestedAsks(body, decision(NeedStatus.FULL),
                SellerCheckOutcome.NEEDS_CUSTOMER_INFO, null)).isEmpty();
        assertThat(CustomerAskGuard.unrequestedAsks(body, decision(NeedStatus.FULL), null,
                "어떤 규격인지 확정되지 않았습니다. 규격에 따라 달라질 수 있음을 밝히고 어떤 규격을 쓰실지 되물으세요.")).isEmpty();
        assertThat(CustomerAskGuard.unrequestedAsks(body, null, null, null)).isEmpty();
        // The 규격 line that says NOT to ask allows nothing.
        assertThat(CustomerAskGuard.unrequestedAsks(body, decision(NeedStatus.FULL), null,
                "근거의 수치로 바로 답하고 규격을 되묻지 마세요.")).containsExactly(body);
    }
}
