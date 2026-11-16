package com.sellerops.talktalk;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the transport once at start: {@code fake} (default) → {@link FakeTalkTalkTransport}; {@code live} without
 * {@code live-send-enabled} → {@link BlockedTalkTalkTransport}; {@code live} with it → {@link HttpTalkTalkTransport},
 * whose destination is pinned to the documented endpoint. An unknown value is blocked, never fake and never live.
 * Even the live transport sends only after the dispatcher's full readiness check (lane contract §3).
 */
@Configuration
public class TalkTalkTransportConfiguration {

    @Bean
    public TalkTalkTransport talkTalkTransport(TalkTalkSettings settings) {
        return choose(settings);
    }

    static TalkTalkTransport choose(TalkTalkSettings settings) {
        return switch (settings.transport()) {
            case "fake" -> new FakeTalkTalkTransport();
            case "live" -> settings.liveSendEnabled() ? new HttpTalkTalkTransport()
                    : new BlockedTalkTalkTransport("LIVE_OFF");
            default -> new BlockedTalkTalkTransport("UNKNOWN_TRANSPORT");
        };
    }
}
