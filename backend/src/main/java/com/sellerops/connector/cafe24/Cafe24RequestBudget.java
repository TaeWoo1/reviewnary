package com.sellerops.connector.cafe24;

import java.net.URI;
import java.util.Map;

/**
 * <b>A Cafe24 HTTP boundary that counts every request and refuses the one past its limit</b> (2026-11-08, open-inquiry
 * recheck, inquiry_operational_truth_v1 §10.3).
 *
 * <p>The count is of HTTP calls that actually go out — the token refresh, each article re-read, each comment-discovery
 * call, each comment read, any further page or retry — never of inquiries or of "one lookup". A call is counted before
 * it is sent, so a call that then fails is still counted: it reached the channel.
 *
 * <p>Past the limit nothing is sent: the call throws {@link Exhausted}, and {@link #refusals()} says it happened, so a
 * caller that planned its batches correctly can prove it never needed to refuse, and one that did can throw that batch's
 * results away instead of recording them as failed looks. A 429 is remembered ({@link #rateLimitedResponses()}) even when
 * the client that received it swallowed it.
 *
 * <p><b>A second gate that outlives the run</b> (2026-11-08): an {@link Allowance} is asked before every request, after
 * this run's own limit — the recheck uses it for the account's daily budget and its run lock, both in the database. It
 * may refuse ({@link Refusal}); nothing is sent then either.
 *
 * <p>Read only: a JSON write is refused outright, whatever the budget.
 */
public final class Cafe24RequestBudget implements Cafe24HttpClient {

    /** Why a request was not sent. */
    public enum Refusal {
        /** This run's own limit. */
        RUN_LIMIT,
        /** The account's budget for the day. */
        DAILY_LIMIT,
        /** This run no longer holds the account: its lease ran out or another run took it over. */
        LEASE_LOST
    }

    /** A gate asked before every request, after the run's own limit. Must not send anything itself. */
    public interface Allowance {
        /** Null to let this request go out, already counted; otherwise why not. */
        Refusal take();

        /** How many more requests it would let out, as far as it knows now. */
        int remaining();
    }

    /** The request that would have gone past the limit. Nothing was sent. */
    public static final class Exhausted extends RuntimeException {
        private final Refusal refusal;

        Exhausted(Refusal refusal) {
            super("카페24 재확인 요청 한도에 도달해 요청을 보내지 않았습니다.");
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    private final Cafe24HttpClient delegate;
    private final int limit;
    private final Allowance allowance;
    private int used;
    private int refusals;
    private int rateLimited;
    private Refusal lastRefusal;

    public Cafe24RequestBudget(Cafe24HttpClient delegate, int limit) {
        this(delegate, limit, null);
    }

    public Cafe24RequestBudget(Cafe24HttpClient delegate, int limit, Allowance allowance) {
        if (delegate == null || limit < 0) {
            throw new IllegalArgumentException("요청 한도가 올바르지 않습니다.");
        }
        this.delegate = delegate;
        this.limit = limit;
        this.allowance = allowance;
    }

    @Override
    public synchronized Response postForm(URI uri, Map<String, String> headers, Map<String, String> form) {
        take();
        return observe(delegate.postForm(uri, headers, form));
    }

    @Override
    public synchronized Response get(URI uri, Map<String, String> headers) {
        take();
        return observe(delegate.get(uri, headers));
    }

    @Override
    public Response postJson(URI uri, Map<String, String> headers, String jsonBody) {
        throw new IllegalStateException("재확인은 읽기만 합니다.");
    }

    private void take() {
        Refusal refusal = used >= limit ? Refusal.RUN_LIMIT : allowance == null ? null : allowance.take();
        if (refusal != null) {
            refusals++;
            lastRefusal = refusal;
            throw new Exhausted(refusal);
        }
        used++;
    }

    private Response observe(Response response) {
        if (response != null && response.statusCode() == 429) {
            rateLimited++;
        }
        return response;
    }

    public synchronized int used() {
        return used;
    }

    /** What this run may still send: its own limit, and the allowance's when there is one. */
    public synchronized int remaining() {
        int own = limit - used;
        return allowance == null ? own : Math.min(own, Math.max(0, allowance.remaining()));
    }

    /** The reason of the last refusal, or null if nothing was refused. */
    public synchronized Refusal lastRefusal() {
        return lastRefusal;
    }

    public synchronized int refusals() {
        return refusals;
    }

    public synchronized int rateLimitedResponses() {
        return rateLimited;
    }

    public int limit() {
        return limit;
    }
}
