package com.sellerops.agent.quota;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>A spending ceiling for a model evaluation</b> (2026-11-05, eleventh review: 「다음 호출의 최대 출력 비용까지 고려해 시작
 * 여부를 결정」). Off unless {@code sellerops.dev.llm-budget-usd} is above zero. When on, every model call is asked before it
 * is made: the dollars spent since {@code sellerops.dev.llm-budget-since} — real input and output tokens of the calls that
 * returned, plus the worst case of any call charged but not yet returned — and the worst case of this call must stay within
 * the budget, or the call is refused the same way the day's AI ceiling refuses it. Nothing is skipped by it: a refused check
 * keeps its draft unchecked and unsaved (검토 대기), exactly as at the ceiling.
 *
 * <p>The worst case of a call is its step's input estimate at the input price plus its output cap at the output price.
 * Output is capped by configuration, so that half is a bound; input is not capped, so its figure is an estimate (above the
 * largest seen) — an overrun is the one call's, and the next call is asked against the real total.
 */
@Component
public class DevLlmBudget {

    private final double budgetUsd;
    private final Instant since;
    private final double inPerToken;
    private final double outPerToken;
    private final int planOut;
    private final int judgeOut;
    private final int draftOut;
    private final int checkOut;

    /**
     * The output caps are the capabilities' own, read from their property objects — never their keys, so no file reads two
     * capabilities' flags (AgentDraftBoundaryTest). A capability absent from the context falls back to its default cap.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DevLlmBudget(@Value("${sellerops.dev.llm-budget-usd:0}") double budgetUsd,
                        @Value("${sellerops.dev.llm-budget-since:}") String since,
                        @Value("${sellerops.dev.llm-price-in-per-mtok:4}") double inPerMtok,
                        @Value("${sellerops.dev.llm-price-out-per-mtok:20}") double outPerMtok,
                        org.springframework.beans.factory.ObjectProvider<
                                com.sellerops.inquiry.decision.InquiryDecisionProperties> decision,
                        org.springframework.beans.factory.ObjectProvider<
                                com.sellerops.agent.llm.AgentDraftProperties> draft) {
        this(budgetUsd, since, inPerMtok, outPerMtok,
                decision.getIfAvailable() == null ? 800 : decision.getIfAvailable().planMaxOutputTokens(),
                decision.getIfAvailable() == null ? 2400 : decision.getIfAvailable().judgeMaxOutputTokens(),
                draft.getIfAvailable() == null ? 4000 : draft.getIfAvailable().maxOutputTokens(),
                draft.getIfAvailable() == null ? 4000 : draft.getIfAvailable().checkMaxOutputTokens());
    }

    public DevLlmBudget(double budgetUsd, String since, double inPerMtok, double outPerMtok, int planOut, int judgeOut,
                        int draftOut) {
        this(budgetUsd, since, inPerMtok, outPerMtok, planOut, judgeOut, draftOut, draftOut);
    }

    public DevLlmBudget(double budgetUsd, String since, double inPerMtok, double outPerMtok, int planOut, int judgeOut,
                        int draftOut, int checkOut) {
        this.budgetUsd = budgetUsd;
        this.since = since == null || since.isBlank() ? null : Instant.parse(since.strip());
        this.inPerToken = inPerMtok / 1_000_000d;
        this.outPerToken = outPerMtok / 1_000_000d;
        this.planOut = planOut;
        this.judgeOut = judgeOut;
        this.draftOut = draftOut;
        this.checkOut = checkOut;
        if (budgetUsd > 0 && this.since == null) {
            throw new IllegalArgumentException("sellerops.dev.llm-budget-since is required with a budget");
        }
    }

    public boolean enabled() {
        return budgetUsd > 0;
    }

    /** The worst case of one call on {@code step}, in dollars. */
    public double worstCase(String step) {
        if ("NEED_PLAN".equals(step)) {
            return 2_000 * inPerToken + planOut * outPerToken;
        }
        if ("NEED_JUDGE".equals(step)) {
            return 6_000 * inPerToken + judgeOut * outPerToken;
        }
        if ("FIGURE_CHECK".equals(step) || "REDRAFT_CHECK".equals(step) || "CLAIM_RECHECK".equals(step)) {
            return 10_000 * inPerToken + checkOut * outPerToken;
        }
        // A draft or its rewrite — and any step not named above, at the larger of the two caps.
        return 10_000 * inPerToken + Math.max(draftOut, checkOut) * outPerToken;
    }

    /** Whether a call on {@code step} may start, given what was spent; logs nothing, decides only. */
    public boolean allows(AgentLlmUsageRepository usage, UUID orgId, String step) {
        if (!enabled()) {
            return true;
        }
        return spent(usage, orgId) + worstCase(step) <= budgetUsd;
    }

    /** A sum over no rows is null in SQL (2026-11-05: the first call of an evaluation failed on it, before any call). */
    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    /** Dollars spent since the start, at the configured prices, counting a call not yet returned at its worst case. */
    public double spent(AgentLlmUsageRepository usage, UUID orgId) {
        java.util.List<Object[]> rows = usage.spentSince(orgId, since);
        Object[] t = rows == null || rows.isEmpty() ? new Object[3] : rows.get(0);
        return count(t[0]) * inPerToken + count(t[1]) * outPerToken + count(t[2]) * worstCase(null);
    }
}
