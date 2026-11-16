package com.sellerops.talktalk;

/** {@code transport=live} without {@code live-send-enabled}: every send is recorded BLOCKED and nothing leaves. */
public class BlockedTalkTalkTransport implements TalkTalkTransport {

    private final String reason;

    public BlockedTalkTalkTransport(String reason) {
        this.reason = reason;
    }

    @Override
    public TalkTalkConnection.Mode mode() {
        return TalkTalkConnection.Mode.LIVE;
    }

    @Override
    public Outcome send(String sendKey, String jsonBody) {
        return new Outcome(TalkTalkOutbound.Status.BLOCKED, null, reason);
    }
}
