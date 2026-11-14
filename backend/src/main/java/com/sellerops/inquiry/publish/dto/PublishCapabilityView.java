package com.sellerops.inquiry.publish.dto;

import java.util.List;

/**
 * Read-only publish-capability status. Reports whether the inquiry reply-send path is
 * enabled and which channels currently have a reply adapter registered. Carries no
 * secret, token, or credential — only the execution flag and static channel codes — so
 * an orchestration client can assert the send path is fail-closed (disabled) before it
 * acts. On the default configuration {@code executionEnabled} is false and
 * {@code replyAdapterChannelCodes} is empty.
 */
public record PublishCapabilityView(
        boolean executionEnabled,
        List<String> replyAdapterChannelCodes,
        /*
         * Channels served only by a development simulation (2026-11-09) — a subset of replyAdapterChannelCodes, empty
         * outside {@code sellerops.dev.fake-channel}. executionEnabled stays false beside it: nothing real can be sent.
         */
        List<String> simulatedChannelCodes) {

    /** The real-execution shape: no simulated channel. */
    public PublishCapabilityView(boolean executionEnabled, List<String> replyAdapterChannelCodes) {
        this(executionEnabled, replyAdapterChannelCodes, List.of());
    }
}
