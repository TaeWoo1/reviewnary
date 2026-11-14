package com.sellerops.agent.reply;

import com.sellerops.auth.runtoken.ReplyRunTokenService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * <b>Every reply-agent run starts here</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md} §2) — a seller's press,
 * a sync, the automatic check alike: mint the run and its token, hand them to the runtime, close the run if the runtime
 * did not. The runtime never receives a seller's login.
 */
@Service
public class ReplyRunStarter {

    private final ReplyRunTokenService tokens;
    private final AgentRuntimeClient runtime;
    private final ReplyAgentService agent;
    private final com.sellerops.auth.runtoken.ReplyAgentRunRepository runs;

    public ReplyRunStarter(ReplyRunTokenService tokens, AgentRuntimeClient runtime, ReplyAgentService agent,
                           com.sellerops.auth.runtoken.ReplyAgentRunRepository runs) {
        this.tokens = tokens;
        this.runtime = runtime;
        this.agent = agent;
        this.runs = runs;
    }

    /**
     * Whether a run already handed this review to a person (review 2026-10-28). Drafting ahead does not run it again — a
     * safety review re-judged on every check could one day be read as something else and drafted over, and a review that
     * still failed after a revision would cost a run's calls on every check. A seller's own press still runs.
     */
    public boolean handedOff(UUID orgId, UUID reviewId) {
        return runs.existsByOrgIdAndSubjectIdAndOutcome(orgId, reviewId, "HANDOFF");
    }

    /**
     * Why the latest run of this review handed it to a person, or empty when that run ended otherwise or none ran
     * (2026-11-09). The reply panel keeps the reason in view and does not seed the template over it.
     */
    public Optional<String> handoffReason(UUID orgId, UUID reviewId) {
        return runs.findFirstByOrgIdAndSubjectIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(run -> "HANDOFF".equals(run.getOutcome()))
                .map(run -> run.getHandoffReason() == null ? "PERSON_REVIEW" : run.getHandoffReason());
    }

    /** Whether a run can start for this org: the capability is on and a runtime is configured. */
    public boolean available(UUID orgId) {
        return runtime.isConfigured() && agent.isEnabledFor(orgId);
    }

    /**
     * Whether review replies may only come from the reply agent here (an install, {@link AgentRuntimeClient#isRequired}).
     * Then a run that cannot start is a draft that is not written — never the single-call drafter's draft instead.
     */
    public boolean required() {
        return runtime.isRequired();
    }

    /** Run the reply agent for one review. Empty when it could not run; the report otherwise. */
    public Optional<AgentRuntimeClient.RunReport> startReview(UUID orgId, UUID reviewId, String trigger, UUID startedBy) {
        return startReviewRun(orgId, reviewId, trigger, startedBy).flatMap(Started::report);
    }

    /** A run that started: its id — so an evaluation reads this run's version, not the review's latest — and its report. */
    public record Started(UUID runId, Optional<AgentRuntimeClient.RunReport> report) {
    }

    /**
     * Whether a run of this review is still drafting (2026-11-09). The press that started it may have stopped waiting on
     * the screen while the run goes on and saves; a second press must see that run, not start another.
     */
    public boolean running(UUID orgId, UUID reviewId) {
        return runs.existsByOrgIdAndSubjectIdAndFinishedAtIsNullAndExpiresAtAfter(orgId, reviewId, java.time.Instant.now());
    }

    public Optional<Started> startReviewRun(UUID orgId, UUID reviewId, String trigger, UUID startedBy) {
        if (!available(orgId)) {
            return Optional.empty();
        }
        // Never two runs of one review at once: drafting ahead and a second press both stop here while one is open. The
        // look and the mint are one locked step (§B126) — two presses arriving together cannot both find none.
        Optional<ReplyRunTokenService.Minted> minted = tokens.mintIfIdle(orgId, reviewId, trigger, startedBy);
        if (minted.isEmpty()) {
            return Optional.empty();
        }
        ReplyRunTokenService.Minted run = minted.get();
        Optional<AgentRuntimeClient.RunReport> report = runtime.runReply(run.runId(), run.token());
        // A runtime that did not finish the run leaves it open until the token expires; close it now.
        tokens.finish(run.runId(), report.map(AgentRuntimeClient.RunReport::outcome).orElse("FAILED"));
        return Optional.of(new Started(run.runId(), report));
    }
}
