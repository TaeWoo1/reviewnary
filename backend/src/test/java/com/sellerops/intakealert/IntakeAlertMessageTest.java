package com.sellerops.intakealert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntakeAlertMessageTest {

    static IntakeAlertItem item(IntakeAlertItem.Kind kind, IntakeAlertItem.Reason reason, String label) {
        IntakeAlertItem i = new IntakeAlertItem();
        i.setSubjectKind(kind);
        i.setReason(reason);
        i.setIssueLabel(label);
        i.setChannelCode("CAFE24");
        return i;
    }

    @Test
    @DisplayName("한 건 알림: [리뷰너리] · 채널 · 유형 · 주제 · 안내, 템플릿 변수는 닫힌 값만")
    void single() {
        IntakeAlertMessage.Rendered r = IntakeAlertMessage.single("데모 가게",
                item(IntakeAlertItem.Kind.INQUIRY, IntakeAlertItem.Reason.NEW_INQUIRY, "배송"));
        assertThat(r.text()).startsWith("[리뷰너리] 새로 확인된 문의가 있어요")
                .contains("가게: 데모 가게", "카페24 새 문의 · 주제 배송", "안내: 답변 초안을 확인하고 승인해 주세요.",
                        "승인과 등록은 서비스에서만");
        assertThat(r.variables()).containsOnlyKeys("제목", "가게", "내용", "안내");
        assertThat(r.text().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("기타 주제는 적지 않고, 묶음은 건수만")
    void otherAndDigest() {
        assertThat(IntakeAlertMessage.single(null,
                item(IntakeAlertItem.Kind.REVIEW, IntakeAlertItem.Reason.LOW_RATING, "기타")).text())
                .contains("가게: 내 가게", "카페24 별점 낮은 리뷰").doesNotContain("주제");
        assertThat(IntakeAlertMessage.digest("가게", List.of(
                item(IntakeAlertItem.Kind.REVIEW, IntakeAlertItem.Reason.LOW_RATING, null),
                item(IntakeAlertItem.Kind.REVIEW, IntakeAlertItem.Reason.NEW_REVIEW, null),
                item(IntakeAlertItem.Kind.INQUIRY, IntakeAlertItem.Reason.NEW_INQUIRY, null))).text())
                .contains("확인할 일이 3건 있어요", "새 리뷰 2건, 새 문의 1건 (별점 낮은 리뷰 1건)");
    }

    @Test
    @DisplayName("휴대폰 번호 정규화·마스킹")
    void phone() {
        assertThat(PhoneNumbers.normalize("010-1234-5678")).contains("01012345678");
        assertThat(PhoneNumbers.normalize("02-123-4567")).isEmpty();
        assertThat(PhoneNumbers.normalize("010 1234 567")).isEmpty();
        assertThat(PhoneNumbers.normalize("011-123-4567")).contains("0111234567");
        assertThat(PhoneNumbers.masked("5678")).isEqualTo("***-****-5678");
    }
}
