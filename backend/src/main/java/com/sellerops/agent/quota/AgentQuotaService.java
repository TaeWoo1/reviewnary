package com.sellerops.agent.quota;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much of today's Agent budget this org has left, and the refusal when it has none.
 *
 * <p><b>Atomic per org and day.</b> Counting, comparing and recording happen under a lock on the org's day
 * ({@link AgentDailyBudget}); a check-then-insert without it let two concurrent requests both take the last call
 * (outside review, 2026-10-29). The seller-facing count and the run limit are compared under the same lock.
 *
 * <p><b>Charged BEFORE the call, never after.</b> A ceiling enforced afterwards has already paid for
 * the thing it was meant to prevent — the same rule {@code OperatorBudget} states for a single run,
 * applied to a day.
 *
 * <p><b>Exhaustion is not an error.</b> It returns a refusal the existing seams already know how to
 * carry ({@code available=false}), so a run degrades exactly the way it degrades when the capability
 * is switched off for the org: deterministically, with a sentence, and with the dashboard untouched.
 * Nothing here can affect a marketplace call, because nothing here is on that path at all.
 *
 * <p><b>Counting and refusing are separate.</b> With {@code enforced=false} every call is still
 * recorded — the row is what usage, latency and cost reporting are built on — and no call is ever
 * refused. That is the posture a local machine, a manual-QA sitting and a benchmark want, and it is
 * the opposite of switching the subsystem off, which also stops the meter.
 *
 * <p><b>A run buys its slot once.</b> The Operator plans up to three times per run; charging a run
 * slot per iteration would make the daily run limit mean a third of what it says.
 */
@Service
public class AgentQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AgentQuotaService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AgentLlmUsageRepository usage;
    private final AgentQuotaProperties properties;

    private DevLlmBudget budget;

    /** An evaluation's spending ceiling; absent or off everywhere but a dev evaluation run. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBudget(DevLlmBudget budget) {
        this.budget = budget;
    }

    public AgentQuotaService(AgentLlmUsageRepository usage, AgentQuotaProperties properties) {
        this.usage = usage;
        this.properties = properties;
    }

    private AgentDailyBudgetRepository budgets;
    private AgentDailyBudgetOpener opener;

    /** The per-org-day lock (outside review, 2026-10-29). Absent only in tests that build the service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBudgets(AgentDailyBudgetRepository budgets, AgentDailyBudgetOpener opener) {
        this.budgets = budgets;
        this.opener = opener;
    }

    /**
     * Lock the org's day before counting: every charge for one org and one day queues here, so the count it compares
     * includes every call charged before it, and two charges that both saw one call left cannot both take it. Held until
     * the charge's own transaction commits — the usage row is written inside the same lock.
     */
    private void lockDay(UUID orgId, LocalDate day) {
        if (budgets == null) {
            return;
        }
        if (budgets.lockDay(orgId, day).isEmpty()) {
            try {
                opener.open(orgId, day);
            } catch (org.springframework.dao.DataIntegrityViolationException openedByAnother) {
                // Another charge opened the day first; lock that row.
            }
            budgets.lockDay(orgId, day).orElseThrow(() -> new IllegalStateException("budget row did not open"));
        }
    }

    /**
     * Charge one model call, or refuse.
     *
     * <p>Runs in its own transaction: the usage row must survive whether or not the caller's work
     * afterwards does. A call that was made and then rolled back is a call the vendor still billed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String runId) {
        return consume(orgId, kind, runId, AgentUsageActor.USER);
    }

    /**
     * The actor this request may be attributed to.
     *
     * <p>A header is only believed where the deployment says so ({@code quota.trust-actor-header},
     * false everywhere by default). On production and pilot the answer is always {@link
     * AgentUsageActor#USER}, so no caller can label its way past the budget.
     */
    public AgentUsageActor actorOf(String headerValue) {
        return properties.isTrustActorHeader() ? AgentUsageActor.parse(headerValue) : AgentUsageActor.USER;
    }

    /**
     * Charge one model call by a named actor, or refuse.
     *
     * <p><b>The actor decides whether the limits apply, never whether the row is written.</b> A QA or
     * benchmark call costs the deployment exactly what a seller's does, so it is metered identically;
     * what it must not do is spend the seller's day. Before this, a benchmark signing in as a real
     * account did precisely that — 1,211 runs against a limit of 200 on one org in a day.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String runId, AgentUsageActor actor) {
        return consume(orgId, kind, runId, actor, 0);
    }

    /**
     * As {@link #consume(UUID, AgentUsageKind, String, AgentUsageActor)}, for work that will need {@code ahead} more calls
     * to finish — an inquiry draft and its claim check. New work starts only when all of it fits, so a draft is not
     * written only to wait for the check it cannot afford (sixth outside review, 2026-10-29).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String runId, AgentUsageActor actor, int ahead) {
        return consumeCharge(orgId, kind, runId, actor, ahead).decision();
    }

    /**
     * As {@link #consume(UUID, AgentUsageKind, String, AgentUsageActor, int)}, with the row's id handed back so the call's
     * time and tokens can be written once it returns ({@link #recordOutcome}) — the inquiry drafter's own call left no
     * tokens before this (2026-11-01 case-eval-r10: 36 drafter rows, every count empty).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Charge consumeCharge(UUID orgId, AgentUsageKind kind, String runId, AgentUsageActor actor, int ahead) {
        boolean item = opensItem(orgId, runId);
        return charge(orgId, kind, runId, actor, null, null, item, item ? ahead : 0);
    }

    /** A call with no run is one piece of work; a run's calls are one piece of work, opened by its first row. */
    private boolean opensItem(UUID orgId, String runId) {
        return runId == null || !usage.existsByOrgIdAndUsageDateAndRunId(orgId, LocalDate.now(KST), runId);
    }

    /**
     * The one write path: the limits, then the row. Two limits, kept apart (owner, 2026-10-29):
     * <ul>
     *   <li><b>the actual AI ceiling</b> — every call today by the seller or by a run no seller started, whatever it is
     *   (a judgement, a draft, a critique, a revision, a retry, a fact check, the triage classifier). Reached, no call
     *   goes out, automatic or not;</li>
     *   <li><b>the seller-facing count</b> — the pieces of work the seller asked for ({@code item}: the first row of a
     *   run, a draft press, one conversation turn), however many calls each took. It refuses only the seller's new
     *   work; the steps of work already begun are bounded by the ceiling alone.</li>
     * </ul>
     * QA and benchmark rows are metered and never refused.
     *
     * <p><b>Automatic work leaves the seller's reserve</b> (sixth outside review, 2026-10-29): a call by a run no seller
     * started stops at {@link AgentQuotaProperties#automaticCalls()}; the seller's own may use the whole ceiling. And work
     * that is just starting asks for room to finish — {@code ahead} more calls — so a judgement-to-critique run or a draft
     * and its check does not begin on the last call and end waiting.
     */
    private Charge charge(UUID orgId, AgentUsageKind kind, String runId, AgentUsageActor actor, String step,
                          String trigger, boolean item, int ahead) {
        if (budget != null && budget.enabled() && usage != null) {
            // An evaluation's spending ceiling (DevLlmBudget): asked before every call, metered or not, and refused the
            // way the day's ceiling refuses — a check that cannot run keeps its draft unsaved, never skipped.
            lockDay(orgId, LocalDate.now(KST));
            if (!budget.allows(usage, orgId, step)) {
                log.info("평가 예산 관문: 호출을 시작하지 않습니다. org={} step={} spent={} worst={} budget={}", orgId, step,
                        String.format("%.4f", budget.spent(usage, orgId)), String.format("%.4f", budget.worstCase(step)),
                        budget.budgetUsd());
                return new Charge(QuotaDecision.exhausted(QuotaDecision.Reason.DAILY_LLM_CALLS, 0, 0), null);
            }
            if (properties == null || !properties.isEnabled()) {
                AgentLlmUsage row = new AgentLlmUsage();
                row.setOrgId(orgId);
                row.setUsageDate(LocalDate.now(KST));
                row.setKind(kind);
                row.setRunId(runId);
                row.setActor(actor);
                row.setStep(step);
                row.setTriggerKind(trigger);
                row.setCountsAsItem(item);
                return new Charge(QuotaDecision.pass(), usage.save(row).getId());
            }
        }
        if (properties == null || usage == null || !properties.isEnabled()) {
            return new Charge(QuotaDecision.pass(), null);
        }
        LocalDate today = LocalDate.now(KST);
        // Count, compare and record under one lock — not three steps another charge can slip between (2026-10-29).
        lockDay(orgId, today);
        boolean counted = actor == AgentUsageActor.USER || actor == AgentUsageActor.RUN;
        if (counted) {
            long calls = usage.countSellerAndRunCalls(orgId, today);
            boolean automatic = actor == AgentUsageActor.RUN;
            int limit = automatic ? properties.automaticCalls() : properties.llmCalls();
            if (calls + Math.max(0, ahead) >= limit) {
                log.info("에이전트 일일 AI 호출 상한에 도달했습니다. org={} used={} ahead={} limit={} automatic={} enforced={}",
                        orgId, calls, ahead, limit, automatic, properties.isEnforced());
                if (properties.isEnforced()) {
                    QuotaDecision.Reason reason = automatic && calls + Math.max(0, ahead) < properties.llmCalls()
                            ? QuotaDecision.Reason.AUTOMATIC_SHARE : QuotaDecision.Reason.DAILY_LLM_CALLS;
                    return new Charge(QuotaDecision.exhausted(reason, calls, limit), null);
                }
            }
        }
        if (item && actor.enforced()) {
            long items = usage.countSellerItems(orgId, today);
            if (items >= properties.items()) {
                log.info("판매자 일일 처리 건수 한도에 도달했습니다. org={} used={} limit={} enforced={}",
                        orgId, items, properties.items(), properties.isEnforced());
                if (properties.isEnforced()) {
                    return new Charge(QuotaDecision.exhausted(QuotaDecision.Reason.DAILY_ITEMS, items,
                            properties.items()), null);
                }
            }
        }
        boolean newRun = runId != null && !usage.existsByOrgIdAndUsageDateAndRunId(orgId, today, runId);
        if (newRun) {
            long runs = usage.countDistinctUserRuns(orgId, today);
            if (actor.enforced() && runs >= properties.runs()) {
                log.info("에이전트 일일 실행 한도에 도달했습니다. org={} used={} limit={} enforced={}",
                        orgId, runs, properties.runs(), properties.isEnforced());
                if (properties.isEnforced()) {
                    return new Charge(QuotaDecision.exhausted(QuotaDecision.Reason.DAILY_RUNS, runs, properties.runs()),
                            null);
                }
            }
        }

        AgentLlmUsage row = new AgentLlmUsage();
        row.setOrgId(orgId);
        row.setUsageDate(today);
        row.setKind(kind);
        row.setRunId(runId);
        row.setActor(actor);
        row.setStep(step);
        row.setTriggerKind(trigger);
        row.setCountsAsItem(item);
        AgentLlmUsage saved = usage.save(row);
        return new Charge(QuotaDecision.pass(), saved.getId());
    }

    /** Whether one more seller call fits under today's actual AI ceiling — for a step that should wait rather than fail. */
    @Transactional(readOnly = true)
    public boolean hasRoom(UUID orgId) {
        return hasRoom(orgId, AgentUsageActor.USER);
    }

    /** The same for this actor: automatic work's room ends where the seller's reserve begins. */
    @Transactional(readOnly = true)
    public boolean hasRoom(UUID orgId, AgentUsageActor actor) {
        if (properties == null || usage == null || !properties.isEnabled() || !properties.isEnforced()) {
            return true;
        }
        int limit = actor == AgentUsageActor.RUN ? properties.automaticCalls() : properties.llmCalls();
        return usage.countSellerAndRunCalls(orgId, LocalDate.now(KST)) < limit;
    }

    /**
     * <b>Charge one step of a reply-agent run</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md} §3) — the same
     * limits {@link #consume} applies, with the step and what started the run on the row, and the row's id handed back
     * so the call's time and tokens can be written once it returns ({@link #recordOutcome}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Charge consumeStep(UUID orgId, String runId, String step, String trigger, AgentUsageActor actor) {
        return consumeStep(orgId, runId, step, trigger, actor, 0);
    }

    /** As above, with the calls the run may still need after this one, asked for only when this step opens the run. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Charge consumeStep(UUID orgId, String runId, String step, String trigger, AgentUsageActor actor, int ahead) {
        boolean opens = runId != null && opensItem(orgId, runId);
        return charge(orgId, AgentUsageKind.REPLY, runId, actor, step, trigger, opens, opens ? ahead : 0);
    }

    /**
     * <b>Charge a step that belongs to work already counted</b> — a fact check on a draft, the triage classifier read
     * for drafting ahead. Counted against the actual ceiling like any call (owner, 2026-10-29), never as a new piece of
     * the seller's work. Refused when the ceiling is reached; the caller then waits (검토 대기) rather than skip it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Charge chargeCheck(UUID orgId, AgentUsageKind kind, String step, AgentUsageActor actor) {
        return charge(orgId, kind, null, actor, step, null, false, 0);
    }

    /** The fact check's step name on the meter. */
    public static final String CHECK_STEP = "FIGURE_CHECK";

    /** What one charged step took. Best-effort: a meter that cannot write never fails the call it measured. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(UUID usageId, long elapsedMs, Integer inputTokens, Integer outputTokens) {
        if (usageId == null) {
            return;
        }
        usage.findById(usageId).ifPresent(row -> {
            row.setElapsedMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs)));
            row.setInputTokens(inputTokens);
            row.setOutputTokens(outputTokens);
            usage.save(row);
        });
    }

    /** Today's calls by reply-agent runs no seller started — counted with the seller's when drafting ahead measures its share. */
    @Transactional(readOnly = true)
    public long runCallsToday(UUID orgId) {
        if (usage == null) {
            return 0;
        }
        return usage.countByOrgIdAndUsageDateAndActor(orgId, LocalDate.now(KST), AgentUsageActor.RUN);
    }

    /** One charge: the decision, and the usage row to complete, or null when nothing was written. */
    public record Charge(QuotaDecision decision, UUID usageId) {
    }

    /**
     * <b>One day's usage, by what it was</b> (owner, 2026-10-28: 「모니터링 잘 할 수 있도록만」) — for the AI 사용량 screen.
     * Counts and sums only; no content, no run ids.
     */
    @Transactional(readOnly = true)
    public UsageReport report(UUID orgId, LocalDate day) {
        java.util.List<UsageRow> rows = usage.breakdown(orgId, day).stream()
                .map(r -> new UsageRow(String.valueOf(r[0]), (String) r[1], (String) r[2], String.valueOf(r[3]),
                        ((Number) r[4]).longValue(), ((Number) r[5]).longValue(), ((Number) r[6]).longValue(),
                        ((Number) r[7]).longValue(), ((Number) r[8]).longValue()))
                .sorted(java.util.Comparator.comparing(UsageRow::kind)
                        .thenComparing(UsageRow::step, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(UsageRow::trigger, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .toList();
        return new UsageReport(day, properties.isEnabled(), properties.llmCalls(),
                usage.countSellerAndRunCalls(orgId, day), properties.items(), usage.countSellerItems(orgId, day), rows,
                properties.automaticCalls(), usage.countByOrgIdAndUsageDateAndActor(orgId, day, AgentUsageActor.RUN));
    }

    /** One group of one day's calls. {@code runs} counts distinct run ids (0 for calls without one). */
    public record UsageRow(String kind, String step, String trigger, String actor, long calls, long runs,
                           long elapsedMs, long inputTokens, long outputTokens) {
    }

    /** One day: the groups, the daily limit, and the calls the seller and no-seller runs spent together. */
    public record UsageReport(LocalDate date, boolean enabled, int llmCallsLimit, long sellerAndRunCalls,
                              int itemsLimit, long sellerItems, java.util.List<UsageRow> rows,
                              int automaticCallsLimit, long automaticCalls) {
    }

    /**
     * Today's spend, for the settings screen. Read-only; charges nothing.
     *
     * <p>The USED figures are the SELLER's, because they sit beside limits that only apply to the
     * seller — showing a benchmark's calls against the seller's ceiling is how the screen would claim
     * a budget was nearly spent by work the seller never did. The whole table is still there for
     * cost reporting, which asks a different question.
     */
    @Transactional(readOnly = true)
    public AgentQuotaStatus status(UUID orgId) {
        LocalDate today = LocalDate.now(KST);
        return new AgentQuotaStatus(properties.isEnabled(), properties.isEnforced(), today,
                usage.countDistinctUserRuns(orgId, today), properties.runs(),
                usage.countSellerAndRunCalls(orgId, today), properties.llmCalls(),
                usage.countSellerItems(orgId, today), properties.items());
    }

    /**
     * What today looks like. {@code llmCalls*} is the actual AI ceiling — every call by the seller and by runs no seller
     * started; {@code items*} is the seller-facing count of work asked for. Percentages are the screen's business.
     */
    public record AgentQuotaStatus(boolean enabled, boolean enforced, LocalDate date,
                                   long runsUsed, int runsLimit,
                                   long llmCallsUsed, int llmCallsLimit, long itemsUsed, int itemsLimit) {
        public AgentQuotaStatus(boolean enabled, boolean enforced, LocalDate date, long runsUsed, int runsLimit,
                                long llmCallsUsed, int llmCallsLimit) {
            this(enabled, enforced, date, runsUsed, runsLimit, llmCallsUsed, llmCallsLimit, 0, 0);
        }
    }
}
