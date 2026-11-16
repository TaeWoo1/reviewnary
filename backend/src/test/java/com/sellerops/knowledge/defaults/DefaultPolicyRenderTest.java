package com.sellerops.knowledge.defaults;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.claim.KnowledgeClaim;
import com.sellerops.talktalk.TalkTalkGuidance;
import com.sellerops.talktalk.TalkTalkGuidanceRules;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sentences the questionnaire writes are read back by the same claim reader the 자료끼리 다른 값 check uses, as
 * the value the seller typed — otherwise an uploaded manual that says 14일 would never be found to disagree with a
 * confirmed 7일.
 */
class DefaultPolicyRenderTest {

    private static List<KnowledgeClaim> claims(String text, ClaimAttribute attribute) {
        return ClaimReader.read(text).stream().filter(c -> c.attribute() == attribute).toList();
    }

    @Test
    @DisplayName("교환·반품 기간: 반품·교환 기한 둘 다 7일로 읽힌다")
    void windowReadsAsSevenDays() {
        String s = DefaultPolicyItem.RETURN_WINDOW.render(7, null);
        assertThat(claims(s, ClaimAttribute.RETURN_WINDOW)).extracting(KnowledgeClaim::low).containsExactly(7L);
        assertThat(claims(s, ClaimAttribute.EXCHANGE_WINDOW)).extracting(KnowledgeClaim::low).containsExactly(7L);
    }

    @Test
    @DisplayName("변심 반품 배송비: 6,000원, 고객 부담으로 읽힌다")
    void returnFeeReadsAsCustomerPays() {
        String s = DefaultPolicyItem.RETURN_FEE.render(6000, null);
        assertThat(s).contains("6,000원");
        List<KnowledgeClaim> fee = claims(s, ClaimAttribute.RETURN_SHIPPING_FEE);
        assertThat(fee).extracting(KnowledgeClaim::low).containsExactly(6000L);
        assertThat(fee.get(0).payer()).isEqualTo(KnowledgeClaim.Payer.CUSTOMER);
    }

    @Test
    @DisplayName("도착 기간: 2일로 읽힌다")
    void deliveryReadsAsTwoDays() {
        String s = DefaultPolicyItem.DELIVERY_DAYS.render(2, null);
        assertThat(claims(s, ClaimAttribute.DELIVERY_DAYS)).extracting(KnowledgeClaim::low).containsExactly(2L);
    }

    @Test
    @DisplayName("출고 마감은 톡톡 배송 기준의 문장 그대로라 같은 시각으로 읽히고, 휴무일 날짜는 날 수로 읽히지 않는다")
    void sharedSentencesFromGuidance() {
        TalkTalkGuidance g = new TalkTalkGuidance();
        g.setCutoffHour(14);
        g.setCutoffMinute(0);
        g.setDispatchDays("MON,TUE,WED,THU,FRI");
        g.setClosedDates("2026-12-25|성탄절\n2026-11-01|지난 휴무\n");
        g.setConfirmedThrough(LocalDate.of(2027, 1, 31));
        LocalDate today = LocalDate.of(2026, 11, 17);

        String cutoff = DefaultPolicyService.sharedSentence(DefaultPolicyItem.DISPATCH_CUTOFF, g, today);
        assertThat(cutoff).isEqualTo(TalkTalkGuidanceRules.ruleSentence(g));
        assertThat(claims(cutoff, ClaimAttribute.DISPATCH_CUTOFF)).extracting(KnowledgeClaim::low).containsExactly(14L);

        String holidays = DefaultPolicyService.sharedSentence(DefaultPolicyItem.HOLIDAYS, g, today);
        assertThat(holidays).contains("12월 25일").contains("성탄절").contains("2027년 1월 31일까지")
                .doesNotContain("11월 1일");
        assertThat(ClaimReader.read(holidays)).isEmpty();
    }

    @Test
    @DisplayName("글로 적는 항목은 비면 문장이 없고, 숫자 항목은 숫자가 없으면 메모만으로 문장이 되지 않는다")
    void emptyAnswersRenderNothing() {
        assertThat(DefaultPolicyItem.COURIER.render(null, "  ")).isNull();
        assertThat(DefaultPolicyItem.RETURN_WINDOW.render(null, "메모만")).isNull();
        assertThat(DefaultPolicyItem.COURIER.render(null, "CJ대한통운")).isEqualTo("택배사: CJ대한통운");
        assertThat(DefaultPolicyItem.RETURN_WINDOW.warning(5)).contains("7일");
        assertThat(DefaultPolicyItem.RETURN_WINDOW.warning(14)).isNull();
    }

    @Test
    @DisplayName("법정 기준은 질문 옆 안내일 뿐 문서 문장에 들어가지 않는다")
    void legalHintIsNeverRendered() {
        for (DefaultPolicyItem item : DefaultPolicyItem.values()) {
            if (item.legal() == null || item.shared()) {
                continue;
            }
            String s = item.kind() == DefaultPolicyItem.Kind.TEXT ? item.render(null, "답") : item.render(7, null);
            assertThat(s).doesNotContain("제17조").doesNotContain("제18조").doesNotContain("법");
        }
    }
}
