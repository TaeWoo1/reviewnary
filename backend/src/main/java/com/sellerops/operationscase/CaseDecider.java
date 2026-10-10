package com.sellerops.operationscase;

/**
 * Which layer concluded the case. The model is only ever reached for what the rules could not settle, and since
 * Seller-declared Operations Policy v1 not even then if the seller has already decided how this problem is handled.
 *
 * <p>Three layers, in the order they speak. {@code RULE} settles what is obvious from fields the canonical record
 * already holds. {@code SELLER} is the seller's own standing rule for a repeated problem — it is offered only what
 * the rule could not settle, so it never contradicts one, and where it answers no model is asked. {@code AGENT} is
 * the last resort and the only one that costs a vendor call.
 *
 * <p>Before the third value existed, a case the seller had pre-decided read as {@code RULE} — indistinguishable
 * from one the rating settled, which made «our own rule decided this» unsayable on a screen and unqueryable in the
 * record.
 */
public enum CaseDecider {
    RULE,
    SELLER,
    AGENT
}
