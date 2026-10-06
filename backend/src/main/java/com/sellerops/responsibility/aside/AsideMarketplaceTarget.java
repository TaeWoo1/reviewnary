package com.sellerops.responsibility.aside;

import java.util.Optional;
import java.util.UUID;

/**
 * <b>Which of this organisation's stores a marketplace recipe reads, and the two facts the helper needs to read
 * it safely.</b>
 *
 * <p>An interface, implemented outside this package, for the same reason {@code ResponsibilityRunFollowUp} is
 * one: the scheduled-job package must not learn how to open a credential or how a store identity is derived.
 * It asks a question and is handed an answer whose parts it cannot construct.
 *
 * <p><b>Why these two fields and no others.</b> The job wire's standing rule is that it carries no URL, prompt,
 * script or credential, and neither of these is any of those:
 *
 * <ul>
 *   <li>{@code accountSlot} is the opaque per-account identifier the existing review handoff is already keyed by,
 *   and which this same helper already receives on the seller-pressed lane. Without it a run could read a page
 *   and then have no route to hand its reading back.</li>
 *   <li>{@code expectedStoreFingerprint} is a <b>digest</b> of the account's own vendor code — not the code, and
 *   never the credential it may have been derived from. It exists so the helper can refuse to read a store that
 *   is not the one this binding belongs to. Absent ({@code null}) is not a pass: the identity assertion answers
 *   {@code UNRESOLVED} and the rows are dropped unread.</li>
 * </ul>
 *
 * <p>Neither field can name a target. The route is the recipe's own bound workflow on the helper side, screened
 * locally before anything opens; nothing here can redirect it.
 */
public interface AsideMarketplaceTarget {

    /**
     * The store a recipe reads for this organisation.
     *
     * @param sellerAccountId which account's store — the one the deployment named, never «the org's first»
     */
    record Target(UUID sellerAccountId, String accountSlot, String expectedStoreFingerprint) {
    }

    /**
     * Resolve the target, or empty when there is none to read.
     *
     * <p>Empty is the honest answer in every case that is not «exactly one named, connected account of this
     * recipe's channel»: no account, an account the deployment did not name, more than one candidate. A run that
     * cannot say which store it would read does not read one.
     */
    Optional<Target> resolve(UUID orgId, AsideRecipe recipe);

    /**
     * The target for ONE named store — the shape a seller's press needs.
     *
     * <p>{@link #resolve} answers «which store did the deployment name», which is the right question for work
     * nobody is watching and the wrong one for a seller pressing 지금 수집 on the account in front of them. This
     * one answers «is THIS account a store this recipe can read for this organisation», and the deployment
     * allow-list is not part of the answer: a seller looking at their own screen is not an unattended lane.
     *
     * <p>Empty for every reason it should be: another organisation's account, an account on a different channel
     * than the recipe reads, a file-upload account with no screen, or a recipe this resolver does not serve. A
     * resolver that cannot say «yes, this store» says nothing, and nothing reads no store.
     *
     * <p><b>The default cannot widen anything.</b> It answers only for the store {@link #resolve} would have
     * named anyway, so a resolver that has not thought about the operator lane supports exactly the account the
     * deployment named and no other — a resolver that forgets to override this refuses presses rather than
     * granting them. Every real resolver overrides it, because answering only for the deployment-named account
     * is precisely what the operator lane exists not to do.
     */
    default Optional<Target> resolveFor(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
        if (sellerAccountId == null) {
            return Optional.empty();
        }
        return resolve(orgId, recipe).filter(t -> sellerAccountId.equals(t.sellerAccountId()));
    }

    /**
     * Every published resolver asked in turn; the first that names a store answers.
     *
     * <p>Each resolver answers only for its own channel's recipe and is empty for every other, so the order does
     * not choose between competing answers — there are none. It exists because there is now more than one
     * resolver, and a single injected one would either be ambiguous to the container or quietly answer for one
     * channel only.
     */
    static AsideMarketplaceTarget firstOf(java.util.List<AsideMarketplaceTarget> resolvers) {
        java.util.List<AsideMarketplaceTarget> fixed = java.util.List.copyOf(resolvers);
        return new AsideMarketplaceTarget() {
            @Override
            public Optional<Target> resolve(UUID orgId, AsideRecipe recipe) {
                for (AsideMarketplaceTarget resolver : fixed) {
                    Optional<Target> answer = resolver.resolve(orgId, recipe);
                    if (answer.isPresent()) {
                        return answer;
                    }
                }
                return Optional.empty();
            }

            @Override
            public Optional<Target> resolveFor(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
                for (AsideMarketplaceTarget resolver : fixed) {
                    Optional<Target> answer = resolver.resolveFor(orgId, sellerAccountId, recipe);
                    if (answer.isPresent()) {
                        return answer;
                    }
                }
                return Optional.empty();
            }
        };
    }
}
