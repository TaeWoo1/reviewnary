package com.sellerops.responsibility.aside;

/**
 * <b>What a recipe may do to the surface it opens.</b>
 *
 * <p>One value, and the single value is the statement: nothing the local agent runs acts on a page. The
 * runtimes open a published route, read a model, and close — zero clicks, zero keystrokes, zero downloads, zero
 * writes, zero model calls.
 *
 * <p>A second value does not belong here casually. Adding one would mean a helper may change something on a
 * seller's marketplace with nobody's hand on it, which is the boundary
 * {@code docs/sellerops_live_approval_contract.md} exists to hold: a marketplace WRITE needs its own fresh,
 * single-use, mode-{@code WRITE} approval and a human performing the act. The reason this enum exists with one
 * member is so that the day somebody proposes the second, every gate that must object already asks.
 */
public enum AsideRecipeMode {
    READ_ONLY
}
