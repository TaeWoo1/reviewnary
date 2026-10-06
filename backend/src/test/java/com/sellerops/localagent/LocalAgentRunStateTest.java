package com.sellerops.localagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The local agent's lifecycle, in the words the seller's screen uses.</b>
 *
 * <p>UNPAIRED → READY → RUNNING → SUCCESS / PARTIAL / AUTH_REQUIRED / FAILED. The first two are about the desk
 * and the rest about one job, and the translation is here so two screens cannot disagree about what a row means.
 *
 * <p>The load-bearing test is {@link #readableIsNotDelivered}: a marketplace page that was read but not proved
 * to be this store stored nothing, and a screen calling that SUCCESS would be reporting an import that does not
 * exist. The store fence is upstream; this is the place that must not undo it.
 */
class LocalAgentRunStateTest {

    @Test
    @DisplayName("an idle desk is READY with a linked helper and UNPAIRED without one")
    void theDeskIsItsOwnState() {
        assertThat(LocalAgentRunState.idle(true)).isEqualTo(LocalAgentRunState.READY);
        assertThat(LocalAgentRunState.idle(false)).isEqualTo(LocalAgentRunState.UNPAIRED);
    }

    @Test
    @DisplayName("queued and claimed are one wait: RUNNING")
    void waitingIsRunning() {
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.QUEUED, null, null, null)))
                .isEqualTo(LocalAgentRunState.RUNNING);
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.CLAIMED, null, null, null)))
                .isEqualTo(LocalAgentRunState.RUNNING);
    }

    @Test
    @DisplayName("a proved, bounded delivery is SUCCESS; one that cannot rule out a gap is PARTIAL")
    void deliveredReadsSayHowFarTheyReached() {
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                IdentityVerdict.MATCH, SourceCompleteness.BOUNDED)))
                .isEqualTo(LocalAgentRunState.SUCCESS);
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                IdentityVerdict.MATCH, SourceCompleteness.PARTIAL)))
                .isEqualTo(LocalAgentRunState.PARTIAL);
        // A review read's coverage is bounded by construction and sets no word; that is still a success.
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                IdentityVerdict.MATCH, null)))
                .isEqualTo(LocalAgentRunState.SUCCESS);
    }

    @Test
    @DisplayName("a readable page is not a delivered one: an unproved store is FAILED, never SUCCESS")
    void readableIsNotDelivered() {
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                IdentityVerdict.MISMATCH, null)))
                .isEqualTo(LocalAgentRunState.FAILED);
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                IdentityVerdict.UNRESOLVED, null)))
                .isEqualTo(LocalAgentRunState.FAILED);
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.OBSERVED,
                null, null)))
                .isEqualTo(LocalAgentRunState.FAILED);
    }

    @Test
    @DisplayName("the channel's sign-in wall keeps its own state — it is the one the seller can act on")
    void authRequiredSurvivesAsItself() {
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, AsideJobOutcome.AUTH_REQUIRED,
                null, null)))
                .isEqualTo(LocalAgentRunState.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("every other ending is one FAILED, because the seller's next move is the same")
    void everythingElseIsOneBucket() {
        for (AsideJobOutcome outcome : new AsideJobOutcome[] {AsideJobOutcome.STORE_UNRESOLVED,
                AsideJobOutcome.SURFACE_UNREADABLE, AsideJobOutcome.EXECUTOR_UNAVAILABLE,
                AsideJobOutcome.REFUSED}) {
            assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.SETTLED, outcome, null, null)))
                    .as("%s", outcome)
                    .isEqualTo(LocalAgentRunState.FAILED);
        }
        // Nobody took it before it expired: it never ran, which is not a timeout the seller can read.
        assertThat(LocalAgentRunState.of(job(ScheduledAsideJobStatus.EXPIRED, null, null, null)))
                .isEqualTo(LocalAgentRunState.FAILED);
    }

    private ScheduledAsideJob job(ScheduledAsideJobStatus status, AsideJobOutcome outcome,
                                  IdentityVerdict verdict, SourceCompleteness coverage) {
        ScheduledAsideJob job = new ScheduledAsideJob();
        job.setRecipe(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        job.setStatus(status);
        job.setOutcome(outcome);
        job.setIdentityVerdict(verdict);
        job.setDeliveryCompleteness(coverage);
        job.setExpiresAt(Instant.parse("2026-10-07T04:10:00Z"));
        return job;
    }
}
