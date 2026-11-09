package com.sellerops.agent.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code sellerops.agent.quota.*} — the Agent's daily ceiling for one org.
 *
 * <p><b>Every number here is deployment policy, so none of them is a constant in a service.</b> A demo
 * org, a pilot seller and a production tenant do not want the same ceiling, and a limit compiled into
 * source is a limit that needs a release to change — which in practice means it gets removed instead
 * of tuned. Bind with {@code SELLEROPS_AGENT_QUOTA_DAILY_RUNS_PER_ORG} and friends.
 *
 * <p><b>Metering and enforcement are two switches, not one.</b> {@code enabled} decides whether this
 * subsystem records what was spent; {@code enforced} decides whether it may refuse. A local machine,
 * a manual-QA sitting and a benchmark all want the first and not the second — and the shape that was
 * here before made turning the ceiling off ALSO turn the meter off, so the sittings that most need
 * usage, latency and cost numbers were exactly the ones that produced none. Production and pilot keep
 * {@code enforced=true}; nothing about their behaviour changes.
 *
 * <p><b>{@code trust-actor-header} is OFF everywhere by default, and that is the safe direction.</b>
 * It decides whether this deployment will believe a caller that says «this call is QA, not the
 * seller». Where it is false — production, pilot, and any host nobody configured — every call is the
 * seller's however it is labelled, so the header cannot be used to walk around a budget. Local, QA
 * and benchmark hosts turn it on, and their non-USER calls are metered and never enforced against.
 *
 * <p><b>On by default, and the defaults are generous.</b> The failure mode being prevented is runaway
 * cost, not ordinary use: a seller asking thirty questions in a day never meets this; a loop asking
 * three thousand meets it within minutes. Defaulting the quota OFF would mean the protection exists
 * only where someone remembered to configure it.
 */
@Component
public class AgentQuotaProperties {

    private final boolean enabled;
    private final boolean enforced;
    private final boolean trustActorHeader;
    private final int dailyRunsPerOrg;
    private final int dailyLlmCallsPerOrg;
    private final int dailyItemsPerOrg;
    private final int dailySellerReserveCalls;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentQuotaProperties(
            @Value("${sellerops.agent.quota.enabled:true}") boolean enabled,
            @Value("${sellerops.agent.quota.enforced:true}") boolean enforced,
            @Value("${sellerops.agent.quota.trust-actor-header:false}") boolean trustActorHeader,
            @Value("${sellerops.agent.quota.daily-runs-per-org:200}") int dailyRunsPerOrg,
            @Value("${sellerops.agent.quota.daily-llm-calls-per-org:1000}") int dailyLlmCallsPerOrg,
            @Value("${sellerops.agent.quota.daily-items-per-org:300}") int dailyItemsPerOrg,
            @Value("${sellerops.agent.quota.daily-seller-reserve-calls:300}") int dailySellerReserveCalls) {
        this.enabled = enabled;
        this.enforced = enforced;
        this.trustActorHeader = trustActorHeader;
        this.dailyRunsPerOrg = dailyRunsPerOrg;
        this.dailyLlmCallsPerOrg = dailyLlmCallsPerOrg;
        this.dailyItemsPerOrg = dailyItemsPerOrg;
        this.dailySellerReserveCalls = Math.max(0, dailySellerReserveCalls);
    }

    /** The six-argument shape tests build by hand: no seller reserve, so automatic work meets the ceiling itself. */
    public AgentQuotaProperties(boolean enabled, boolean enforced, boolean trustActorHeader, int dailyRunsPerOrg,
                                int dailyLlmCallsPerOrg, int dailyItemsPerOrg) {
        this(enabled, enforced, trustActorHeader, dailyRunsPerOrg, dailyLlmCallsPerOrg, dailyItemsPerOrg, 0);
    }

    /** The five-argument shape older callers and tests use: the item limit at its default, no seller reserve. */
    public AgentQuotaProperties(boolean enabled, boolean enforced, boolean trustActorHeader, int dailyRunsPerOrg,
                                int dailyLlmCallsPerOrg) {
        this(enabled, enforced, trustActorHeader, dailyRunsPerOrg, dailyLlmCallsPerOrg, 300, 0);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * May the ceiling refuse a call, or only count it?
     *
     * <p>Enforcement without metering is impossible by construction — a deployment that records
     * nothing has nothing to compare a limit against — so this is read only where {@code enabled}
     * already holds.
     */
    public boolean isTrustActorHeader() {
        return trustActorHeader;
    }

    public boolean isEnforced() {
        return enforced;
    }

    public int runs() {
        return dailyRunsPerOrg;
    }

    /**
     * <b>The org's actual AI ceiling</b> (owner, 2026-10-29): every model call of the day by the seller or by a run no
     * seller started — judgements, drafts, critiques, revisions, retries, fact checks, the triage classifier.
     */
    public int llmCalls() {
        return dailyLlmCallsPerOrg;
    }

    /** The seller-facing count's limit: pieces of work the seller asked for, however many calls each took. */
    public int items() {
        return dailyItemsPerOrg;
    }

    /**
     * <b>The calls automatic work leaves for the seller</b> (sixth outside review, 2026-10-29). Judgements, drafting
     * ahead and background inquiry drafts stop at {@code llmCalls() - reserve}; the seller's own presses may use the whole
     * ceiling. The default (300 of 1,000) is provisional: it is to be set from the calls a piece of work actually takes,
     * measured in the real-model evaluation.
     */
    public int sellerReserve() {
        return Math.min(dailySellerReserveCalls, Math.max(0, dailyLlmCallsPerOrg));
    }

    /** Where automatic work stops: the ceiling less the seller's reserve. */
    public int automaticCalls() {
        return Math.max(0, dailyLlmCallsPerOrg - sellerReserve());
    }
}
