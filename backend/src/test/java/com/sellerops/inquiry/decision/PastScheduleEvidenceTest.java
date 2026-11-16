package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.knowledge.schedule.PastScheduleTestTexts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The judge is not offered a schedule that is all history as ground for the plan</b> (2026-11-16 §7.57, q04 S2: the
 * October table was kept as partial ground for 「그레이 재입고 언제?」 and its 10/20 「확정」 reached the draft).
 */
class PastScheduleEvidenceTest {

    static final LocalDate RECEIVED = LocalDate.parse("2026-11-14");

    private static EvidenceCandidate doc(EvidenceCandidate.Kind kind, String label, String text) {
        return new EvidenceCandidate(null, kind, label, text, UUID.randomUUID(), null, null);
    }

    private static List<EvidenceCandidate> pool() {
        return new ArrayList<>(List.of(
                doc(EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE, "21_옛일정_10월", PastScheduleTestTexts.OCTOBER),
                doc(EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE, "11_침구세트_상세", "- 색상: 아이보리, 그레이."),
                doc(EvidenceCandidate.Kind.ORG_KNOWLEDGE, "01_공통_배송정책", PastScheduleTestTexts.POLICY)));
    }

    private static Inquiry asking(String body) {
        Inquiry q = new Inquiry();
        q.setBody(body);
        return q;
    }

    private static List<String> left(String body, List<InquiryNeed> needs, LocalDate reference) {
        return InquiryEvidenceCollector.withoutPastSchedules(pool(), asking(body), needs, reference).stream()
                .map(EvidenceCandidate::label).toList();
    }

    @Test
    @DisplayName("a question about the plan: the all-past table goes, the rest stays")
    void thePlanIsNotReadFromHistory() {
        assertThat(left("그레이 재입고 언제? 날짜 알려주세요", List.of(), RECEIVED))
                .containsExactly("11_침구세트_상세", "01_공통_배송정책");
        assertThat(left("그레이 색상 있나요?", List.of(new InquiryNeed("N1", "그레이 재입고 시기", NeedType.CATALOGUE_AVAILABILITY,
                "재입고")), RECEIVED)).as("a need about availability is a plan question too").doesNotContain("21_옛일정_10월");
        assertThat(InquiryEvidenceCollector.withoutPastSchedules(new ArrayList<>(List.of(doc(EvidenceCandidate.Kind.ORG_KNOWLEDGE,
                "20_입고출고일정", PastScheduleTestTexts.NOVEMBER))), asking("그레이 재입고 언제?"), List.of(), RECEIVED))
                .as("a table with a row ahead is the plan").hasSize(1);
    }

    @Test
    @DisplayName("kept: a question about before, a question that is not about a schedule, no received date")
    void historyIsStillReadable() {
        assertThat(left("지난번엔 그레이가 언제 들어왔었나요?", List.of(), RECEIVED)).contains("21_옛일정_10월");
        assertThat(left("배송비 얼마예요?", List.of(), RECEIVED)).contains("21_옛일정_10월", "01_공통_배송정책");
        assertThat(left("그레이 재입고 언제?", List.of(), null)).hasSize(3);
    }
}
