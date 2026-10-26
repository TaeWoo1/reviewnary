package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the watch does after each answer: pull when a store is ready and ask again at once while that moves the
 * cursor; ask again at once after a hold that ran out; and rest — never spin — after anything else (not linked, the
 * server unreachable or refusing, an empty answer that came too fast, a pull that moved nothing).
 */
class VendorReviewArrivalWatchTest {

    private static final UUID STORE = UUID.fromString("6f1c2a3b-0000-4000-8000-000000000001");

    private final VendorReviewPullService pulls = mock(VendorReviewPullService.class);
    private final StepClock clock = new StepClock();
    private final VendorReviewArrivalWatch watch = new VendorReviewArrivalWatch(pulls, clock);

    @Test
    void aReadyStoreIsPulledAndTheNextQuestionGoesOutAtOnce() {
        when(pulls.receivingCursors()).thenReturn(Map.of(STORE, 10L), Map.of(STORE, 5010L));
        when(pulls.waitForNews(Map.of(STORE, 10L))).thenReturn(List.of(STORE));

        assertThat(watch.step()).isZero();
        verify(pulls).pullBound();
    }

    @Test
    void aPullThatMovesNothingRestsInsteadOfAskingAgain() {
        when(pulls.receivingCursors()).thenReturn(Map.of(STORE, 10L));
        when(pulls.waitForNews(any())).thenReturn(List.of(STORE));

        assertThat(watch.step()).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls).pullBound();
    }

    @Test
    void aHoldThatRanOutAsksAgainAtOnceButAFastEmptyAnswerRests() {
        when(pulls.receivingCursors()).thenReturn(Map.of(STORE, 10L));
        when(pulls.waitForNews(any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(25));
            return List.of();
        });
        assertThat(watch.step()).isZero();

        when(pulls.waitForNews(any())).thenAnswer(i -> {
            clock.advance(Duration.ofMillis(40));
            return List.of();
        });
        assertThat(watch.step()).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).pullBound();
    }

    @Test
    void notLinkedUnreachableOrRefusedRestsWithoutPulling() {
        when(pulls.receivingCursors()).thenReturn(Map.of());
        assertThat(watch.step()).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).waitForNews(any());

        when(pulls.receivingCursors()).thenReturn(Map.of(STORE, 10L));
        doThrow(new VendorServerClient.Unreachable()).when(pulls).waitForNews(any());
        assertThat(watch.step()).isEqualTo(VendorReviewArrivalWatch.REST);
        doThrow(new VendorServerClient.Refused()).when(pulls).waitForNews(any());
        assertThat(watch.step()).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).pullBound();
    }

    /** A clock that moves only when told to. */
    private static final class StepClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
