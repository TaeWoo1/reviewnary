package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.reviewimport.ReviewImportSegment;
import com.sellerops.reviewimport.ReviewImportSegmentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>어떤 읽기가 경계를 밀 수 있는가 — 넷이 모두 맞을 때만.</b>
 *
 * <p>네 조건은 각각 「2026-10-08 실행이 잘못됐는데도 잘못 보이지 않을 수 있었던 길」이다. 로그인 벽에서 끝난
 * 읽기, 가게를 증명하지 못한 페이지, 기간을 말하지 않은 읽기, 그리고 자기 천장에 닿은 읽기.
 */
class ReviewCoverageCursorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private final ReviewImportSegmentRepository segments = mock(ReviewImportSegmentRepository.class);
    private final ChannelRepository channels = mock(ChannelRepository.class);
    private final ScheduledAsideJobRepository screenReads = mock(ScheduledAsideJobRepository.class);

    private static ScheduledAsideJob read(String start, String end, Integer observed, Integer capacity,
                                          AsideJobOutcome outcome, IdentityVerdict verdict,
                                          SourceCompleteness completeness) {
        ScheduledAsideJob job = new ScheduledAsideJob();
        job.setOrgId(ORG);
        job.setRecipe(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        job.setOutcome(outcome);
        job.setIdentityVerdict(verdict);
        job.setObservedCount(observed);
        job.setObservedCapacity(capacity);
        job.setDeliveryCompleteness(completeness);
        if (start != null) {
            job.setWindowStart(LocalDate.parse(start));
            job.setWindowEnd(LocalDate.parse(end));
        }
        return job;
    }

    private static ScheduledAsideJob good(String start, String end) {
        return read(start, end, 45, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MATCH,
                SourceCompleteness.BOUNDED);
    }

    @Test
    @DisplayName("BOUNDED가 「그 창을 전부 읽었다」다 — COMPLETE는 이 칸에 쓸 수 없는 단어였다")
    void theWordIsBounded() {
        // 2026-10-09 라이브: 읽기는 성공했는데(42건, 창 검증됨, 화면 총계 일치) 저장이 거부됐다. 이 칼럼의
        // check constraint는 BOUNDED 또는 PARTIAL만 허용하고, COMPLETE는 「이 수집에서 출처가 내준 것의 끝까지
        // 갔다」는 뜻이라 창 읽기에는 애초에 맞지 않는 말이었다. BOUNDED가 「기록된 경계에서 멈췄다」다.
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read("2026-10-02", "2026-10-08", 45, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MATCH,
                        SourceCompleteness.COMPLETE)))
                .as("COMPLETE는 이 칸에 저장될 수 없으므로 경계를 밀지도 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("완전한 창만 경계를 민다")
    void onlyACompleteWindowMovesTheBoundary() {
        assertThat(ReviewCoverageCursor.provesItsWindow(good("2026-10-02", "2026-10-08"))).isTrue();
    }

    @Test
    @DisplayName("PARTIAL로 판정된 읽기는 밀지 못한다 — 행은 남고, 경계는 그대로다")
    void aPartialDeliveryDoesNotMoveTheBoundary() {
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read("2026-10-02", "2026-10-08", 500, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MATCH,
                        SourceCompleteness.PARTIAL)))
                .isFalse();
    }

    @Test
    @DisplayName("완전함을 판정한 적이 없는 읽기도 밀지 못한다 — 모르는 것은 「완전함」이 아니다")
    void anUnjudgedDeliveryDoesNotMoveTheBoundary() {
        // 이 증거가 생기기 전에 만들어진 helper가 보낸 읽기가 여기에 해당한다. 그 읽기의 행은 진짜로 읽은
        // 것이므로 보관되지만, 어느 기간을 전부 보았다는 주장은 성립하지 않는다.
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read("2026-10-02", "2026-10-08", 45, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MATCH, null)))
                .isFalse();
    }

    @Test
    @DisplayName("읽지 않은 읽기, 가게를 증명하지 못한 읽기, 기간을 말하지 않은 읽기는 모두 밀지 못한다")
    void theOtherThreeWays() {
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read("2026-10-02", "2026-10-08", 0, 500, AsideJobOutcome.AUTH_REQUIRED, null,
                        SourceCompleteness.BOUNDED))).isFalse();
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read("2026-10-02", "2026-10-08", 45, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MISMATCH,
                        SourceCompleteness.BOUNDED)))
                .isFalse();
        assertThat(ReviewCoverageCursor.provesItsWindow(
                read(null, null, 45, 500, AsideJobOutcome.OBSERVED, IdentityVerdict.MATCH,
                        SourceCompleteness.BOUNDED))).isFalse();
    }

    @Test
    @DisplayName("두 lane의 증거가 한 답으로 합쳐지고, 밀지 못하는 읽기는 그 답에 들어오지 않는다")
    void bothLanesAndNeitherGuess() {
        Channel naver = new Channel();
        UUID channelId = UUID.randomUUID();
        naver.setId(channelId);
        naver.setCode("NAVER");
        when(channels.findById(channelId)).thenReturn(Optional.of(naver));
        when(segments.findCoveredForChannel(ORG, channelId)).thenReturn(List.of(
                segment("2026-07-01", "2026-07-31"), segment("2026-08-01", "2026-08-22"),
                segment("2026-08-20", "2026-09-02")));
        when(screenReads.findWindowedReads(eq(ORG), any())).thenReturn(List.of(
                good("2026-10-02", "2026-10-08"),
                // 로그인 벽에서 끝난 10-07 시도도 기간을 들고 있을 수 있다. 그것이 경계를 밀면 하루가 공짜로
                // 메워진다.
                read("2026-10-07", "2026-10-07", 0, 500, AsideJobOutcome.AUTH_REQUIRED, null,
                        SourceCompleteness.BOUNDED)));

        ReviewCoverage coverage = new ReviewCoverageCursor(segments, channels, screenReads)
                .of(ORG, channelId, TODAY);

        assertThat(coverage.coverageFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(coverage.coverageThrough()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(coverage.verified()).hasSize(2);
        assertThat(coverage.gaps()).hasSize(1);
        assertThat(coverage.gaps().get(0).days()).isEqualTo(29);
    }

    @Test
    @DisplayName("화면 읽기 lane이 없는 배포에서도 답은 import 증거만으로 성립한다")
    void aDeploymentWithoutTheLane() {
        Channel cafe24 = new Channel();
        UUID channelId = UUID.randomUUID();
        cafe24.setId(channelId);
        cafe24.setCode("CAFE24");
        when(channels.findById(channelId)).thenReturn(Optional.of(cafe24));
        when(segments.findCoveredForChannel(ORG, channelId)).thenReturn(List.of(segment("2026-09-01", "2026-09-30")));

        ReviewCoverage coverage = new ReviewCoverageCursor(segments, channels, null).of(ORG, channelId, TODAY);
        assertThat(coverage.coverageThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    private static ReviewImportSegment segment(String start, String end) {
        ReviewImportSegment s = new ReviewImportSegment();
        s.setOrgId(ORG);
        s.setSegmentStart(LocalDate.parse(start));
        s.setSegmentEnd(LocalDate.parse(end));
        return s;
    }
}
