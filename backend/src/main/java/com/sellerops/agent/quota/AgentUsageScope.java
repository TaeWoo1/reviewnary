package com.sellerops.agent.quota;

import java.util.function.Supplier;

/**
 * <b>Who a call deep inside a search is for</b> (2026-10-31, {@code docs/standing/reply_agent_v1.md} §8 ②). The search
 * helpers — question intent, evidence eligibility, embeddings — are reached through every lane's search, and no search
 * signature carries who asked. The draft that starts the search does know; it names the actor here for the length of
 * its own work, and a helper's call is charged to that actor.
 *
 * <p>Nothing named is {@link AgentUsageActor#USER}, for the reason that enum gives: a call this product cannot attribute
 * is charged to the seller. Nested scopes restore the outer one when they end.
 */
public final class AgentUsageScope {

    private static final ThreadLocal<AgentUsageActor> CURRENT = new ThreadLocal<>();

    private AgentUsageScope() {
    }

    /** The actor named by the innermost open scope on this thread, or USER. */
    public static AgentUsageActor current() {
        AgentUsageActor actor = CURRENT.get();
        return actor == null ? AgentUsageActor.USER : actor;
    }

    /** Run {@code work} with {@code actor} named; the previous one comes back afterwards, whatever happened. */
    public static <T> T as(AgentUsageActor actor, Supplier<T> work) {
        AgentUsageActor outer = CURRENT.get();
        CURRENT.set(actor);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(outer);
            }
        }
    }
}
