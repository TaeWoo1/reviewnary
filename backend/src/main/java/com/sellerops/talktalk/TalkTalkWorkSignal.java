package com.sellerops.talktalk;

/** Published inside a transaction that made lane work (an accepted event, a queued send); acted on after commit. */
public record TalkTalkWorkSignal() {
}
