package com.sellerops.coverage.catchup;

import com.sellerops.localagent.LocalAgentRunState;
import com.sellerops.localagent.ScreenReadService;
import com.sellerops.localagent.ScreenReadView;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import org.springframework.stereotype.Component;

/**
 * <b>A child of a catch-up answers for the walk, not for itself.</b>
 *
 * <p>The screen polls one job. If that job answered for itself, the first window would settle, the product
 * would say 「수집 완료」 and stop watching while five periods were still to be read — which is precisely the
 * shape of the defect this whole package exists to fix, one level up.
 *
 * <p>So the mapping is from the run's state, and it keeps the four stops apart because the seller's next move
 * differs for each: sign in, accept that a day holds more than a page, press again, or nothing.
 */
@Component
public class ReviewCatchUpStatus implements ScreenReadService.ReviewCatchUpReporter {

    private final ReviewCatchUpRunRepository runs;
    private final ReviewCatchUpOrchestrator orchestrator;

    public ReviewCatchUpStatus(ReviewCatchUpRunRepository runs, ReviewCatchUpOrchestrator orchestrator) {
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    /**
     * The press. Empty when this row has nothing behind it, and the caller then reads the screen's own period.
     *
     * <p>The first child's view already answers at the walk's level, so the screen that polls it keeps polling
     * while the remaining windows are read.
     */
    @Override
    public java.util.Optional<ScreenReadView> start(java.util.UUID orgId, java.util.UUID sellerAccountId,
                                                    java.util.UUID channelId, String dataType,
                                                    com.sellerops.responsibility.aside.AsideRecipe recipe,
                                                    String requestId) {
        return orchestrator.start(orgId, sellerAccountId, channelId, dataType, recipe, requestId)
                .flatMap(started -> {
                    ScheduledAsideJob child = started.firstJob();
                    if (child == null) {
                        // A press that re-found its own intent, or a resume with nothing left to read. There is
                        // no new child to report, so the caller falls back to its ordinary single read rather
                        // than returning a job id nobody queued.
                        return java.util.Optional.empty();
                    }
                    return java.util.Optional.of(describe(ScreenReadView.of(child, true), child));
                });
    }

    @Override
    public ScreenReadView describe(ScreenReadView jobLevel, ScheduledAsideJob job) {
        if (job.getCatchUpRunId() == null) {
            return jobLevel;
        }
        ReviewCatchUpRun run = runs.findById(job.getCatchUpRunId()).orElse(null);
        if (run == null) {
            return jobLevel;
        }
        return jobLevel.withCatchUp(stateOf(run), new ScreenReadView.CatchUp(
                run.getWindowsDone(), run.getRowsObserved(), run.getState().name(), run.getStopReason()));
    }

    /** The walk's state in the vocabulary the screen already speaks. */
    static LocalAgentRunState stateOf(ReviewCatchUpRun run) {
        return switch (run.getState()) {
            case RUNNING -> LocalAgentRunState.RUNNING;
            case PAUSED_AUTH -> LocalAgentRunState.AUTH_REQUIRED;
            case COMPLETE -> LocalAgentRunState.SUCCESS;
            // Stopped before the end, with real days read: the product's word for that is PARTIAL, and it is
            // the honest one — some of what was asked for is in hand and some is not.
            case STOPPED_SATURATED, STOPPED_LIMIT -> LocalAgentRunState.PARTIAL;
            case FAILED -> LocalAgentRunState.FAILED;
        };
    }
}
