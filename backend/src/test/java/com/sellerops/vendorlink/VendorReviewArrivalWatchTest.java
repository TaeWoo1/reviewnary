package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final UUID ORG = UUID.fromString("6f1c2a3b-0000-4000-8000-00000000a001");
    private static final UUID OTHER_ORG = UUID.fromString("6f1c2a3b-0000-4000-8000-00000000b002");

    private final VendorReviewPullService pulls = mock(VendorReviewPullService.class);
    private final StepClock clock = new StepClock();
    private final VendorReviewArrivalWatch watch = new VendorReviewArrivalWatch(pulls, clock);

    @Test
    void aReadyStoreIsPulledAndTheNextQuestionGoesOutAtOnce() {
        when(pulls.receivingCursors(ORG)).thenReturn(Map.of(STORE, 10L), Map.of(STORE, 5010L));
        when(pulls.waitForNews(ORG, Map.of(STORE, 10L))).thenReturn(List.of(STORE));

        assertThat(watch.step(ORG)).isZero();
        verify(pulls).pullBound(ORG);
    }

    @Test
    void aPullThatMovesNothingRestsInsteadOfAskingAgain() {
        when(pulls.receivingCursors(ORG)).thenReturn(Map.of(STORE, 10L));
        when(pulls.waitForNews(eq(ORG), any())).thenReturn(List.of(STORE));

        assertThat(watch.step(ORG)).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls).pullBound(ORG);
    }

    @Test
    void aHoldThatRanOutAsksAgainAtOnceButAFastEmptyAnswerRests() {
        when(pulls.receivingCursors(ORG)).thenReturn(Map.of(STORE, 10L));
        when(pulls.waitForNews(eq(ORG), any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(25));
            return List.of();
        });
        assertThat(watch.step(ORG)).isZero();

        when(pulls.waitForNews(eq(ORG), any())).thenAnswer(i -> {
            clock.advance(Duration.ofMillis(40));
            return List.of();
        });
        assertThat(watch.step(ORG)).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).pullBound(ORG);
    }

    @Test
    void notLinkedUnreachableOrRefusedRestsWithoutPulling() {
        when(pulls.receivingCursors(ORG)).thenReturn(Map.of());
        assertThat(watch.step(ORG)).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).waitForNews(any(), any());

        when(pulls.receivingCursors(ORG)).thenReturn(Map.of(STORE, 10L));
        doThrow(new VendorServerClient.Unreachable()).when(pulls).waitForNews(any(), any());
        assertThat(watch.step(ORG)).isEqualTo(VendorReviewArrivalWatch.REST);
        doThrow(new VendorServerClient.Refused()).when(pulls).waitForNews(any(), any());
        assertThat(watch.step(ORG)).isEqualTo(VendorReviewArrivalWatch.REST);
        verify(pulls, never()).pullBound(ORG);
    }

    /** One organisation's question names its stores only, and its news pulls that organisation only. */
    @Test
    void aQuestionAndItsPullBelongToOneOrganisation() {
        when(pulls.receivingCursors(ORG)).thenReturn(Map.of(STORE, 10L), Map.of(STORE, 5010L));
        when(pulls.waitForNews(ORG, Map.of(STORE, 10L))).thenReturn(List.of(STORE));

        watch.step(ORG);
        verify(pulls).pullBound(ORG);
        verify(pulls, never()).pullBound(OTHER_ORG);
        verify(pulls, never()).pullBound();
        verify(pulls, never()).waitForNews(eq(OTHER_ORG), any());
    }

    /** A watcher for each linked organisation; none when nothing is linked. */
    @Test
    void eachLinkedOrganisationGetsItsOwnWatcher() {
        when(pulls.linkedOrganisations()).thenReturn(List.of());
        assertThat(watch.ensureWatchers()).isEmpty();

        when(pulls.linkedOrganisations()).thenReturn(List.of(ORG, OTHER_ORG));
        assertThat(watch.ensureWatchers()).containsExactly(ORG, OTHER_ORG);
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
