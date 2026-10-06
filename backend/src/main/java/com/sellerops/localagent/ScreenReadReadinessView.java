package com.sellerops.localagent;

/**
 * Whether 지금 수집 can be offered for one account and data type.
 *
 * @param supported whether the product knows how to read this channel's screen for this kind of data at all —
 *                  a capability fact, true or false regardless of whose desk it is
 * @param state     {@link LocalAgentRunState#READY} or {@link LocalAgentRunState#UNPAIRED}: whether there is a
 *                  helper on this organisation's desk to ask
 */
public record ScreenReadReadinessView(boolean supported, LocalAgentRunState state) {
}
