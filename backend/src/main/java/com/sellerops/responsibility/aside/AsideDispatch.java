package com.sellerops.responsibility.aside;

import com.sellerops.common.ApiException;
import java.util.UUID;

/**
 * <b>One request for the local agent to run one bounded recipe — whoever is asking.</b>
 *
 * <p>This is the shape both lanes hand to {@link ScheduledAsideJobService#dispatch}, and it exists so that
 * «operator pressed 지금 수집» and «a responsibility run came due» stop being two code paths to the same
 * browser. The recipe, the workflow, the store fence, the lease, the single-use claim and the one-job-per-device
 * rule are shared by construction rather than by careful copying.
 *
 * <p>What is NOT here is as deliberate as what is: no URL, no prompt, no script, no credential, no device. The
 * recipe name is the whole instruction and the device is «this organisation's linked helper», resolved by the
 * service rather than named by the caller — so no caller can point work at someone else's machine.
 *
 * @param orgId           the organisation, always from a validated session or run, never from a request body
 * @param sellerAccountId which of the organisation's stores, when the recipe reads one. Required for
 *                        {@link AsideTrigger#OPERATOR}: the press names a store, and the job records which
 * @param recipe          one published, read-only {@link AsideRecipe}
 * @param trigger         who is asking, which decides what authorises it ({@link AsideTrigger})
 * @param limits          the bound the row carries; one page, today and until a migration says otherwise
 * @param runId           the responsibility run this job serves, or null for an operator press
 * @param clientJobId     the caller's own id, which is what makes a retried ask idempotent instead of a second
 *                        job on someone's desk
 */
public record AsideDispatch(UUID orgId, UUID sellerAccountId, AsideRecipe recipe, AsideTrigger trigger,
                            AsideJobLimits limits, UUID runId, String clientJobId, UUID catchUpRunId,
                            java.time.LocalDate windowStart, java.time.LocalDate windowEnd) {

    /** A dispatch with no catch-up parent and no requested period — the shape every lane but catch-up asks for. */
    public AsideDispatch(UUID orgId, UUID sellerAccountId, AsideRecipe recipe, AsideTrigger trigger,
                         AsideJobLimits limits, UUID runId, String clientJobId) {
        this(orgId, sellerAccountId, recipe, trigger, limits, runId, clientJobId, null, null, null);
    }

    public AsideDispatch {
        if (orgId == null) {
            throw ApiException.badRequest("조직을 확인할 수 없습니다.");
        }
        if (recipe == null) {
            throw ApiException.badRequest("실행할 수 있는 작업이 아닙니다.");
        }
        if (trigger == null) {
            throw ApiException.badRequest("작업 요청자를 확인할 수 없습니다.");
        }
        if (clientJobId == null || clientJobId.isBlank() || clientJobId.length() > 64) {
            throw ApiException.badRequest("작업 식별자가 필요합니다.");
        }
        if (limits == null) {
            limits = AsideJobLimits.ONE_PAGE;
        }
        if (trigger != AsideTrigger.RESPONSIBILITY && sellerAccountId == null) {
            // A press is about a store the seller is looking at, and a scheduled check is about the store whose
            // setting is on. Without one there is nothing to read and nothing to fence the reading against.
            // Only the responsibility lane leaves the account to be resolved, because there the deployment — not
            // the caller — is the one that named it.
            throw ApiException.badRequest("확인할 판매 계정이 필요합니다.");
        }
        if ((windowStart == null) != (windowEnd == null)) {
            // Half a period is not a period. Either the job knows which days to read or it reads the screen.
            throw ApiException.badRequest("확인할 기간이 올바르지 않습니다.");
        }
        if (windowStart != null && windowEnd.isBefore(windowStart)) {
            throw ApiException.badRequest("확인할 기간이 올바르지 않습니다.");
        }
        if (catchUpRunId != null && windowStart == null) {
            // A catch-up child exists to read one named period. Without one it would read the screen's own
            // and the parent would count a window it never covered.
            throw ApiException.badRequest("확인할 기간이 필요합니다.");
        }
        if (trigger != AsideTrigger.RESPONSIBILITY && runId != null) {
            // The row's shape says which lane produced it; a press — or a scheduled check — that carried a run
            // id would make the audit trail claim a responsibility run asked for it.
            throw ApiException.badRequest("판매자 요청 작업은 실행 기록에 묶이지 않습니다.");
        }
        if (trigger == AsideTrigger.SCHEDULED && windowStart == null) {
            // <b>An unattended read names its period.</b> A windowless read takes whatever period the screen is
            // showing, which is a setting on the marketplace that nobody here chose and that can change between
            // two runs — so «what did the automatic check cover last night» would have no answer. A person
            // pressing may still read the screen as it stands: they are looking at it.
            throw ApiException.badRequest("자동 확인은 확인할 기간을 지정해야 합니다.");
        }
    }

    /** A seller pressing 지금 수집 on one of their own accounts. */
    public static AsideDispatch operator(UUID orgId, UUID sellerAccountId, AsideRecipe recipe, String clientJobId) {
        return new AsideDispatch(orgId, sellerAccountId, recipe, AsideTrigger.OPERATOR, AsideJobLimits.ONE_PAGE,
                null, clientJobId);
    }

    /**
     * One window of a catch-up, carrying the trigger of whoever started the walk.
     *
     * <p>The trigger is a parameter rather than a constant because the walk is the same program either way and
     * its provenance is not: a window read because a seller pressed is {@link AsideTrigger#OPERATOR}, and one
     * read because their automatic check found a gap is {@link AsideTrigger#SCHEDULED}. Deriving it here from
     * anything other than the parent would make the audit trail of a child disagree with its own run.
     *
     * <p>The period is named in both cases, because a child that read the screen's own period would have the
     * parent counting days nobody looked at.
     */
    public static AsideDispatch catchUpWindow(UUID orgId, UUID sellerAccountId, AsideRecipe recipe,
                                              AsideTrigger trigger, String clientJobId, UUID catchUpRunId,
                                              java.time.LocalDate windowStart, java.time.LocalDate windowEnd) {
        return new AsideDispatch(orgId, sellerAccountId, recipe, trigger, AsideJobLimits.ONE_PAGE,
                null, clientJobId, catchUpRunId, windowStart, windowEnd);
    }

    /**
     * <b>One named period, read because the seller's automatic check came due.</b>
     *
     * <p>Used for the day that has not closed yet — {@code today … today} — which is the whole of what an
     * automatic check does once history is closed. It belongs to no walk: a day that cannot be claimed as
     * covered has no boundary to advance, so there is nothing for a parent to count and the read is simply a
     * read. What it produces is rows and a time we looked, which is what freshness is made of.
     */
    public static AsideDispatch scheduled(UUID orgId, UUID sellerAccountId, AsideRecipe recipe,
                                          String clientJobId, java.time.LocalDate windowStart,
                                          java.time.LocalDate windowEnd) {
        return new AsideDispatch(orgId, sellerAccountId, recipe, AsideTrigger.SCHEDULED, AsideJobLimits.ONE_PAGE,
                null, clientJobId, null, windowStart, windowEnd);
    }

    /**
     * A responsibility run's own observation. The account is left for the service to resolve, because on this
     * lane the store is the one the DEPLOYMENT named, not one a caller chose.
     */
    public static AsideDispatch responsibility(UUID orgId, UUID runId, String clientJobId, AsideRecipe recipe) {
        return new AsideDispatch(orgId, null, recipe, AsideTrigger.RESPONSIBILITY, AsideJobLimits.ONE_PAGE,
                runId, clientJobId);
    }
}
