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
     * <b>A responsibility run asked, with nobody watching.</b>
     *
     * <p>Here a press does not exist, so the deployment has to vouch for the store in advance: the lane's flag,
     * the organisation named, and the seller account named ({@link AsideMarketplaceAccess}, off by default, no
     * wildcard). Those conditions are unchanged by the operator lane's existence and are not weakened by it —
     * they answer a different question.
     */
    RESPONSIBILITY
}
