package com.sellerops.talktalk;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** {@code sellerops.talktalk.*} — names and defaults only; the send key lives per org in the database, sealed. */
@Component
public class TalkTalkSettings {

    /** {@code fake} (default) or {@code live}. New connections are stamped with the matching mode. */
    private final String transport;
    private final boolean liveSendEnabled;
    private final boolean sourceAllowlistEnforced;
    private final List<String> trustedProxyCidrs;
    private final String publicBaseUrl;
    private final int retentionDays;
    private final int maxBodyBytes;
    private final long duplicateWindowSeconds;
    private final long autoCooldownHours;
    private final long autoMaxAgeSeconds;
    private final long sendingStaleSeconds;

    public TalkTalkSettings(
            @Value("${sellerops.talktalk.transport:fake}") String transport,
            @Value("${sellerops.talktalk.live-send-enabled:false}") boolean liveSendEnabled,
            @Value("${sellerops.talktalk.ingress.source-allowlist-enforced:false}") boolean sourceAllowlistEnforced,
            @Value("${sellerops.talktalk.ingress.trusted-proxy-cidrs:}") String trustedProxyCidrs,
            @Value("${sellerops.public-base-url:http://localhost:5173}") String publicBaseUrl,
            @Value("${sellerops.talktalk.retention-days:90}") int retentionDays,
            @Value("${sellerops.talktalk.max-body-bytes:65536}") int maxBodyBytes,
            @Value("${sellerops.talktalk.duplicate-window-seconds:600}") long duplicateWindowSeconds,
            @Value("${sellerops.talktalk.auto-cooldown-hours:24}") long autoCooldownHours,
            @Value("${sellerops.talktalk.auto-max-age-seconds:600}") long autoMaxAgeSeconds,
            @Value("${sellerops.talktalk.sending-stale-seconds:120}") long sendingStaleSeconds) {
        this.transport = transport == null ? "fake" : transport.strip().toLowerCase(java.util.Locale.ROOT);
        this.liveSendEnabled = liveSendEnabled;
        this.sourceAllowlistEnforced = sourceAllowlistEnforced;
        this.trustedProxyCidrs = trustedProxyCidrs == null || trustedProxyCidrs.isBlank() ? List.of()
                : Arrays.stream(trustedProxyCidrs.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.publicBaseUrl = publicBaseUrl;
        this.retentionDays = Math.max(1, retentionDays);
        this.maxBodyBytes = maxBodyBytes;
        this.duplicateWindowSeconds = duplicateWindowSeconds;
        this.autoCooldownHours = autoCooldownHours;
        this.autoMaxAgeSeconds = autoMaxAgeSeconds;
        this.sendingStaleSeconds = sendingStaleSeconds;
    }

    /** Test defaults: fake transport, allowlist not enforced. */
    public static TalkTalkSettings defaults() {
        return new TalkTalkSettings("fake", false, false, "",
                "http://localhost:5173", 90, 65536, 600, 24, 600, 120);
    }

    public TalkTalkConnection.Mode newConnectionMode() {
        return "live".equals(transport) ? TalkTalkConnection.Mode.LIVE : TalkTalkConnection.Mode.FAKE;
    }

    public String transport() { return transport; }
    public boolean liveSendEnabled() { return liveSendEnabled; }
    public boolean sourceAllowlistEnforced() { return sourceAllowlistEnforced; }
    public List<String> trustedProxyCidrs() { return trustedProxyCidrs; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public int retentionDays() { return retentionDays; }
    public int maxBodyBytes() { return maxBodyBytes; }
    public long duplicateWindowSeconds() { return duplicateWindowSeconds; }
    public long autoCooldownHours() { return autoCooldownHours; }
    public long autoMaxAgeSeconds() { return autoMaxAgeSeconds; }
    public long sendingStaleSeconds() { return sendingStaleSeconds; }

    public boolean httpsPublicUrl() {
        return publicBaseUrl != null && publicBaseUrl.startsWith("https://");
    }
}
