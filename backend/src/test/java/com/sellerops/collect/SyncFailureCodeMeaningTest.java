package com.sellerops.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.sync.SyncJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The line between "we never asked" and "the channel answered badly".</b>
 *
 * <p>{@link SyncJob#FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT} is read by the two freshness surfaces as a licence
 * to look straight through a run. That is safe for exactly as long as every code in it means no request left
 * the process — so this test pins both halves: the pre-attempt codes are the ones in the set, and every other
 * code {@code failureCodeOf} can produce is outside it.
 *
 * <p>The second half is the one worth having. Widening the set is a one-word edit, and a code that reached a
 * marketplace would then be able to hide a dead credential behind an older success — the regression the
 * narrow spelling of this rule was written to prevent in the first place.
 */
class SyncFailureCodeMeaningTest {

    @Test
    @DisplayName("a missing live approval is the one condition that yields CONFIGURATION_REQUIRED")
    void approvalMissingIsTheOnlySourceOfConfigurationRequired() {
        assertThat(code(false, true, false, false, false, false, 0))
                .isEqualTo(SyncJob.FAILURE_CONFIGURATION_REQUIRED);
        // Every other single condition, and the no-condition case, names something else.
        assertThat(code(true, false, false, false, false, false, 0)).isEqualTo("AUTH_REQUIRED");
        assertThat(code(false, false, true, false, false, false, 0)).isEqualTo("RATE_LIMITED");
        assertThat(code(false, false, false, true, false, false, 0)).isEqualTo("PAGE_LIMIT_REACHED");
        // A timeout is a kind of mid-run error, so it carries both flags — timedOut alone says nothing.
        assertThat(code(false, false, false, false, true, true, 0)).isEqualTo("TIMEOUT");
        assertThat(code(false, false, false, false, false, true, 0)).isEqualTo("EXECUTION_FAILED");
        assertThat(code(false, false, false, false, false, false, 3)).isEqualTo("EXECUTION_FAILED");
        assertThat(code(false, false, false, false, false, false, 0)).isNull();
    }

    @Test
    @DisplayName("an auth verdict outranks a missing approval — a rejected credential is still an answer")
    void authWinsOverApprovalMissing() {
        assertThat(code(true, true, false, false, false, false, 0)).isEqualTo("AUTH_REQUIRED");
        assertThat(SyncJob.FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT).doesNotContain("AUTH_REQUIRED");
    }

    @Test
    @DisplayName("the pre-attempt set holds the two gates and nothing a channel ever answered")
    void onlyPreAttemptCodesAreTransparent() {
        assertThat(SyncJob.FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT)
                .containsExactlyInAnyOrder(SyncJob.FAILURE_CONNECTOR_UNAVAILABLE,
                        SyncJob.FAILURE_CONFIGURATION_REQUIRED);
        // The codes a run can only carry after the request went out stay visible, each one of them.
        assertThat(SyncJob.FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT)
                .doesNotContain("AUTH_REQUIRED", "RATE_LIMITED", "PAGE_LIMIT_REACHED", "TIMEOUT",
                        "EXECUTION_FAILED");
    }

    private static String code(boolean auth, boolean approvalMissing, boolean rateLimited, boolean pageLimited,
                               boolean timedOut, boolean errored, int failedRows) {
        return SyncRunExecutor.failureCodeOf(auth, approvalMissing, rateLimited, pageLimited, timedOut,
                errored, failedRows);
    }
}
