package com.sellerops.agent.llm;

import java.util.function.Supplier;

/**
 * <b>What the model call inside a block cost</b>, for a caller whose seam returns the answer alone (2026-11-01
 * case-eval-r10). The inquiry drafter's {@code draft(...)} returns the draft and logs its tokens; the usage row the
 * composer charged for it stayed empty, so the drafter — the largest single call in an inquiry — was the one step the
 * meter could not price.
 *
 * <p>The seam is not widened because its signature is what every fake drafter in the tests overrides: a call the
 * fakes no longer see would silently test nothing. The drafter reports here; the composer reads it back. Metadata
 * only, like {@link AgentLlmCallMetrics} itself. Nested captures restore the outer one when they end.
 */
public final class AgentLlmCallCapture {

    private static final ThreadLocal<AgentLlmCallMetrics[]> OPEN = new ThreadLocal<>();

    private AgentLlmCallCapture() {
    }

    /** The block's result, and the last call reported inside it — null when nothing reported (a fake, a refusal). */
    public record Captured<T>(T value, AgentLlmCallMetrics metrics) {
    }

    /** Run {@code work}, keeping what the call it makes reports. */
    public static <T> Captured<T> capture(Supplier<T> work) {
        AgentLlmCallMetrics[] outer = OPEN.get();
        AgentLlmCallMetrics[] slot = new AgentLlmCallMetrics[1];
        OPEN.set(slot);
        try {
            T value = work.get();
            return new Captured<>(value, slot[0]);
        } finally {
            if (outer == null) {
                OPEN.remove();
            } else {
                OPEN.set(outer);
            }
        }
    }

    /** Said by the call (and by a test's fake drafter); heard only inside an open {@link #capture}. */
    public static void report(AgentLlmCallMetrics metrics) {
        AgentLlmCallMetrics[] slot = OPEN.get();
        if (slot != null) {
            slot[0] = metrics;
        }
    }
}
