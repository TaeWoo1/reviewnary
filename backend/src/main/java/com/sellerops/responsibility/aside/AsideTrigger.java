package com.sellerops.responsibility.aside;

/**
 * <b>Who asked for this helper job, and therefore what authorises it.</b>
 *
 * <p>The local agent runs one kind of work — a published, bounded, read-only recipe — and two kinds of caller
 * ask for it. The difference is not what runs; it is who is accountable for the run having been asked for, and
 * that difference is what each value below names. Everything else about a job (single-use, leased, one per
 * device, TTL-bounded, no URL/prompt/script/credential column) is identical whichever value it carries.
 *
 * <p>This enum is why the substrate is no longer a Responsibility-only feature. It used to be: the only
 * producer of a job was a responsibility run, so the only way to read a seller's own screen was to let an
 * unattended lane do it on a schedule. The recipe, the workflow and every fence were already general — only the
 * door was narrow.
 */
public enum AsideTrigger {

    /**
     * <b>The seller asked, in the product, for their own account.</b>
     *
     * <p>The authorisation IS the press: an authenticated session, their own organisation, an account that
     * organisation owns, and a read-only recipe the server chose for that account's channel. No deployment
     * allow-list is consulted, because nobody has to vouch for a seller reading their own store while watching
     * the screen they pressed — that is the product working, not an unattended lane.
     *
     * <p>No chat phrase, no manifest and no second confirmation belongs on this path. A development proof-run
     * driven by an assistant keeps its own, separate ceremony
     * ({@code docs/sellerops_live_approval_contract.md} §3); the two must not be mixed, because a product
     * button that asked for a ceremony would be teaching sellers to perform one.
     */
    OPERATOR,

    /**
     * <b>The seller's own standing product setting asked, on a timer, for their own account.</b>
     *
     * <p>The authorisation is a setting the seller owns: 「새 리뷰를 자동으로 확인합니다」, on by default for a
     * channel they connected, off the moment they turn it off ({@code review_auto_check}). No deployment
     * allow-list is consulted, for the same reason {@link #OPERATOR} does not consult one — this is a seller's
     * own store, read read-only, by a program on their own machine, because they asked for it to be kept
     * current. What the deployment still owns is whether the lane runs at all (the scheduler's flag).
     *
     * <p>Two differences from {@link #OPERATOR}, and both are the reason this value exists rather than a press
     * being simulated. First, <b>nobody is watching</b>: so a read on this lane must name the period it intends
     * to read, and a windowless dispatch is refused ({@link AsideDispatch}) — a read that takes whatever period
     * the screen happens to be showing is not a statement anyone can check afterwards. Second, <b>a person
     * outranks it</b>: a seller pressing 지금 확인 is never refused because this lane was mid-read, and this lane
     * never cancels or pre-empts work a person started.
     *
     * <p>Not {@link #RESPONSIBILITY}. That lane's conditions answer a different question — whether a deployment
     * vouches for an organisation whose job makes model calls and sends mail — and they are neither widened nor
     * reused here.
     */
    SCHEDULED,

    /**
     * <b>A responsibility run asked, with nobody watching.</b>
     *
     * <p>Here a press does not exist, so the deployment has to vouch for the store in advance: the lane's flag,
     * the organisation named, and the seller account named ({@link AsideMarketplaceAccess}, off by default, no
     * wildcard). Those conditions are unchanged by the operator lane's existence and are not weakened by it —
     * they answer a different question.
     */
    RESPONSIBILITY
}
