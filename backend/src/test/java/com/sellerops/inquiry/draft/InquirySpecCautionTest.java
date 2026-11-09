package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Asking which 규격 only when the seller's evidence leaves it open</b> (2026-11-01 ninth review: 「모든 옵션의 길이는
 * 2.4m」라는 근거라면 바로 답할 수 있다 — 「옵션 등록 0건」만으로 옵션이 없다고 판단해서는 안 된다).
 */
class InquirySpecCautionTest {

    @Test
    @DisplayName("a passage that states its value for every option, with the number and the property asked")
    void everyOptionIsStatedBySeller() {
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 공통으로 한 줄 길이는 2.4m입니다."), "길이")).isTrue();
        assertThat(SpecApplicability.coversEveryOption(List.of("길이는 옵션과 관계없이 2.4m입니다."), "길이")).isTrue();
        assertThat(SpecApplicability.coversEveryOption(List.of("전 규격 두께 8mm"), "두께")).isTrue();
        assertThat(SpecApplicability.coversEveryOption(List.of("사이즈 공통: 1.2m"), null)).isTrue();
    }

    @Test
    @DisplayName("one value with no such statement, another property, no number, or a passage that varies by option: ask")
    void anythingLessStillAsks() {
        assertThat(SpecApplicability.coversEveryOption(List.of("한 줄의 길이는 2.4m입니다."), "길이")).isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 3만 원 이상 무료배송"), "길이")).isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션이 같은 길이입니다."), "길이")).isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 공통 길이 2.4m", "두께는 옵션별로 다릅니다."),
                "길이")).isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("이전 옵션 길이 2.4m"), "길이")).isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of(), "길이")).isFalse();
    }

    @Test
    @DisplayName("one sentence carries the scope, the number and the property; another value for it settles nothing "
            + "(tenth review)")
    void theSentenceCarriesItAndNoOtherValueDisagrees() {
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 3만 원 이상 무료배송입니다. 한 줄 길이는 2.4m입니다."),
                "길이")).as("the shipping sentence's scope is not the length's").isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 공통 길이 2.4m", "한 줄의 길이는 2.0m입니다."),
                "길이")).as("another passage says 2.0m").isFalse();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 공통 길이 2.4m", "길이 2.4m · 두께 8mm"), "길이"))
                .as("the same value said again").isTrue();
        assertThat(SpecApplicability.coversEveryOption(List.of("모든 옵션 공통 길이 2,4m", "길이는 2.4m"), "길이")).isTrue();
    }

    @Test
    @DisplayName("the line lifts the question back only for an unresolved question, typed figures and no per-option passage")
    void theLineChangesOnlyWhereItShould() {
        SpecApplicability.Applicability open = SpecApplicability.Applicability.VARIANT_UNRESOLVED;
        assertThat(open.messageKo(true, false, true)).isEqualTo(SpecApplicability.EVERY_OPTION_LINE);
        assertThat(SpecApplicability.Applicability.asksBack(open.messageKo(true, false, true))).isFalse();
        assertThat(SpecApplicability.Applicability.asksBack(open.messageKo(false, false, true))).as("read from a picture")
                .isTrue();
        assertThat(open.messageKo(true, true, true)).as("some evidence is about one 규격").contains("되물으세요");
        assertThat(SpecApplicability.Applicability.NOT_VARIANT_SENSITIVE.messageKo(true, false, true)).isEqualTo("(해당 없음)");
    }
}
