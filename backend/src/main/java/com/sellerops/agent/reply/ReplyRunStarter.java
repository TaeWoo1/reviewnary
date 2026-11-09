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

    public Optional<Started> startReviewRun(UUID orgId, UUID reviewId, String trigger, UUID startedBy) {
        if (!available(orgId)) {
            return Optional.empty();
        }
        ReplyRunTokenService.Minted run = tokens.mint(orgId, "REVIEW", reviewId, trigger, startedBy);
        Optional<AgentRuntimeClient.RunReport> report = runtime.runReply(run.runId(), run.token());
        // A runtime that did not finish the run leaves it open until the token expires; close it now.
        tokens.finish(run.runId(), report.map(AgentRuntimeClient.RunReport::outcome).orElse("FAILED"));
        return Optional.of(new Started(run.runId(), report));
    }
}
