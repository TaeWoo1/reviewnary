package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.coverage.CoveredWindow.CoverageSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「어디까지 빠짐없이 확인했는가」 — 성공 시각이 아니라 경계.</b>
 *
 * <p>이 파일이 지키는 것은 하나다: <b>새로운 성공이 오래된 공백을 덮지 않는다.</b> 2026-10-08 네이버 읽기는
 * 성공했고 10-02~10-08을 덮었다. 그 성공 하나로 freshness는 10-08이 되었고, 9/2 이후 29일은 그대로였다.
 * 두 숫자가 같은 칸에 있었다면 둘 중 하나는 거짓이었을 것이다.
 */
class ReviewCoverageTest {

    private static final LocalDate D0902 = LocalDate.of(2026, 9, 2);
    private static final LocalDate D1008 = LocalDate.of(2026, 10, 8);

    private static CoveredWindow w(String start, String end) {
        return new CoveredWindow(LocalDate.parse(start), LocalDate.parse(end), CoverageSource.GUIDED_IMPORT);
    }

    private static CoveredWindow read(String start, String end) {
        return new CoveredWindow(LocalDate.parse(start), LocalDate.parse(end), CoverageSource.SCREEN_READ);
    }

    @Test
    @DisplayName("이 조직의 실제 증거: 07-01~09-02 세 구간과 10-02~10-08 한 읽기")
    void theRealEvidenceOfThisStore() {
        // review_import_segment의 COVERED 세 줄과, 10-08 화면 읽기 하나.
        ReviewCoverage coverage = ReviewCoverage.of(
                List.of(w("2026-07-01", "2026-07-31"), w("2026-08-01", "2026-08-22"), w("2026-08-20", "2026-09-02"),
                        read("2026-10-02", "2026-10-08")),
                D1008);

        // 07-31과 08-01 사이에 읽지 않은 날은 없다 — 두 저녁에 돌린 두 import가 공백을 만들지는 않는다.
        assertThat(coverage.coverageFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(coverage.coverageThrough()).isEqualTo(D0902);
        // 그리고 10-08 성공은 이 경계를 10-08로 밀지 않는다. 그게 이 클래스가 존재하는 이유다.
        assertThat(coverage.coverageThrough()).isNotEqualTo(D1008);
        assertThat(coverage.gaps()).containsExactly(
                new CoveredWindow(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 1), CoverageSource.MERGED));
        assertThat(coverage.completeThrough(D1008)).isFalse();
    }

    @Test
    @DisplayName("최근 성공이 있어도 중간 공백은 보존된다")
    void arecentSuccessDoesNotFillWhatCameBefore() {
        ReviewCoverage coverage = ReviewCoverage.of(
                List.of(w("2026-01-01", "2026-01-31"), read("2026-10-02", "2026-10-08")), D1008);

        assertThat(coverage.coverageThrough()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(coverage.gaps()).hasSize(1);
        assertThat(coverage.gaps().get(0).start()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(coverage.gaps().get(0).end()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("증거가 없으면 경계도 없다 — 날짜를 지어내지 않는다")
    void noEvidenceIsNotADate() {
        ReviewCoverage coverage = ReviewCoverage.of(List.of(), D1008);
        assertThat(coverage.coverageThrough()).isNull();
        assertThat(coverage.coverageFrom()).isNull();
        assertThat(coverage.gaps()).isEmpty();
        // 「경계 없음」과 「공백 없음」은 다르다. 후자를 말하면 아무것도 읽지 않은 채널이 완전해 보인다.
        assertThat(coverage.completeThrough(D1008)).isFalse();
    }

    @Test
    @DisplayName("오늘까지 이어지면 공백이 없다")
    void contiguousToTodayHasNoGap() {
        ReviewCoverage coverage = ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-08")), D1008);
        assertThat(coverage.coverageThrough()).isEqualTo(D1008);
        assertThat(coverage.gaps()).isEmpty();
        assertThat(coverage.completeThrough(D1008)).isTrue();
    }

    @Test
    @DisplayName("겹치는 구간과 맞닿은 구간은 한 구간이고, 내일은 공백이 아니다")
    void mergingAndTheFuture() {
        assertThat(ReviewCoverage.merge(List.of(w("2026-09-01", "2026-09-10"), w("2026-09-05", "2026-09-20"))))
                .containsExactly(new CoveredWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20),
                        CoverageSource.GUIDED_IMPORT));
        // 서로 다른 lane이 맞닿으면 출처는 MERGED — 한쪽 lane의 이름으로 양쪽을 말하지 않는다.
        assertThat(ReviewCoverage.merge(List.of(w("2026-09-01", "2026-09-10"), read("2026-09-11", "2026-09-20"))))
                .containsExactly(new CoveredWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20),
                        CoverageSource.MERGED));
        // 하루 떨어져 있으면 두 구간이다.
        assertThat(ReviewCoverage.merge(List.of(w("2026-09-01", "2026-09-10"), w("2026-09-12", "2026-09-20"))))
                .hasSize(2);
        // 오늘까지 읽었으면 그 뒤는 공백이 아니다 — 아직 일어나지 않은 날이다.
        assertThat(ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-08")), D1008).gaps()).isEmpty();
    }
}
