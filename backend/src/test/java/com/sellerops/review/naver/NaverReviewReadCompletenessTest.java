package com.sellerops.review.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「기간을 전부 읽었다」고 말할 수 있는 조건 — 넷이 모두 맞을 때만.</b>
 *
 * <p>이전 규칙은 비교 하나였다: {@code rows < rowCapacity}. 그것은 우리 천장에 대한 진술이고, 그것을 coverage로
 * 읽은 것이 2026-10-08에 「45행 / 천장 500」을 「그 7일은 메워졌다」로 만든 경로다. 천장에 닿지 않았다는 사실은
 * 행을 잃는 한 가지 방식을 배제할 뿐이고, 화면이 그 기간을 보여주고 있었다는 것을 세우지 않는다.
 *
 * <p>그래서 서로 다른 출처의 네 사실이 일치해야 하고, 그중 하나라도 읽히지 않았다는 것 자체가 경계를 두고 갈
 * 이유가 된다. 아래 각 테스트가 그 넷 중 하나다.
 */
class NaverReviewReadCompletenessTest {

    private static NaverReviewObservationRequest request(Integer labelledTotal, Integer pageSize, String mode) {
        return new NaverReviewObservationRequest(List.of(), 7, "2026-09-03", "2026-09-09", 500,
                labelledTotal, pageSize, mode, 2);
    }

    @Test
    @DisplayName("넷이 모두 맞으면 COMPLETE다 — 42행, 화면도 총 42개, 한 페이지 500, 모델에서 읽음")
    void allFourAgree() {
        assertThat(NaverReviewObservationService.incompleteReason(request(42, 500, "MODEL"), 42, 500)).isNull();
    }

    @Test
    @DisplayName("화면이 말한 총계가 받은 행과 다르면 PARTIAL — 모델이 46 중 42만 올린 경우는 여기서만 잡힌다")
    void theScreensOwnTotalIsTheIndependentWitness() {
        assertThat(NaverReviewObservationService.incompleteReason(request(46, 500, "MODEL"), 42, 500))
                .isEqualTo("TOTAL_DISAGREES");
    }

    @Test
    @DisplayName("총계를 읽지 못했으면 PARTIAL — 「화면이 말하지 않았다」는 「다 읽었다」가 아니다")
    void anUnreadTotalIsNotCompleteness() {
        assertThat(NaverReviewObservationService.incompleteReason(request(null, 500, "MODEL"), 42, 500))
                .isEqualTo("TOTAL_UNREADABLE");
    }

    @Test
    @DisplayName("총계가 선택된 페이지 크기보다 크면 PARTIAL — 무엇을 올렸든 한 페이지일 수 없다")
    void aTotalAbovveThePageSizeCannotHaveBeenOnOnePage() {
        // 300건인데 목록이 「50개씩」으로 맞춰져 있던 경우. 옛 규칙(300 < 500)은 이것을 통과시켰고, 판매자는
        // 그 기간의 6분의 1만 본 화면을 「완전함」으로 기록하게 된다.
        assertThat(NaverReviewObservationService.incompleteReason(request(300, 50, "MODEL"), 300, 500))
                .isEqualTo("TOTAL_ABOVE_PAGE_SIZE");
        assertThat(NaverReviewObservationService.incompleteReason(request(300, null, "MODEL"), 300, 500))
                .isEqualTo("PAGE_SIZE_UNREADABLE");
    }

    @Test
    @DisplayName("DOM을 훑어 얻은 행은 완전함을 주장하지 못한다 — 목록은 DOM에 15행만 들고 돌려쓴다")
    void onlyTheRowModelProvesTheWholePeriod() {
        // 「더 이상 새 행이 안 보인다」는 끝의 증거가 아니다. 이 lane은 grid의 row model을 읽고 모든 node가
        // 적재됐는지 확인하므로, 그 사실을 기록에 남겨 두고 다른 방식으로 얻은 행과 구별한다.
        assertThat(NaverReviewObservationService.incompleteReason(request(42, 500, "DOM"), 42, 500))
                .isEqualTo("READ_MODE_UNPROVEN");
        assertThat(NaverReviewObservationService.incompleteReason(request(42, 500, null), 42, 500))
                .isEqualTo("READ_MODE_UNPROVEN");
    }

    @Test
    @DisplayName("우리 천장은 여전히 시험이다 — 닿았거나, 모르면 PARTIAL")
    void ourOwnCeilingStillCounts() {
        assertThat(NaverReviewObservationService.incompleteReason(request(500, 500, "MODEL"), 500, 500))
                .isEqualTo("CAPACITY_REACHED");
        assertThat(NaverReviewObservationService.incompleteReason(request(42, 500, "MODEL"), 42, null))
                .isEqualTo("CAPACITY_UNKNOWN");
    }

    @Test
    @DisplayName("한 건도 없는 기간도 전부 읽은 기간이다 — 0행과 총 0개는 일치한다")
    void anEmptyPeriodIsStillAReadPeriod() {
        assertThat(NaverReviewObservationService.incompleteReason(request(0, 500, "MODEL"), 0, 500)).isNull();
    }

    @Test
    @DisplayName("이 증거가 없던 helper의 읽기는 아무 주장도 하지 않는다")
    void aHelperOlderThanTheEvidenceClaimsNothing() {
        NaverReviewObservationRequest old =
                new NaverReviewObservationRequest(List.of(), 7, "2026-09-03", "2026-09-09", 500);
        assertThat(NaverReviewObservationService.incompleteReason(old, 42, 500)).isEqualTo("READ_MODE_UNPROVEN");
    }
}
