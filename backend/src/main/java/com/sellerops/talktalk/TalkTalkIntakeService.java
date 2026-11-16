package com.sellerops.talktalk;

import com.sellerops.credential.LocalSecretSealer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Webhook intake: store first, answer 200 only after the row is committed, do the work later</b> (lane contract
 * §3, §4). The checks run in a fixed order and none of them reads or stores more than it needs; the response never
 * carries a message (no synchronous reply), so every send goes through the outbox and the gate.
 */
@Service
public class TalkTalkIntakeService {

    private static final Logger log = LoggerFactory.getLogger(TalkTalkIntakeService.class);

    /** What the controller turns into a status code. Labels only. */
    public enum Result {
        ACCEPTED(200), UNKNOWN_TOKEN(404), DISCONNECTED(404), SOURCE_REFUSED(403), PARTNER_MISMATCH(403),
        TOO_LARGE(413), UNREADABLE(400), VAULT_UNAVAILABLE(503);

        public final int status;

        Result(int status) {
            this.status = status;
        }
    }

    private final TalkTalkConnectionRepository connections;
    private final TalkTalkEventRepository events;
    private final LocalSecretSealer sealer;
    private final TalkTalkSettings settings;
    private final TalkTalkClock clock;
    private final ApplicationEventPublisher publisher;

    public TalkTalkIntakeService(TalkTalkConnectionRepository connections, TalkTalkEventRepository events,
                                 LocalSecretSealer sealer, TalkTalkSettings settings, TalkTalkClock clock,
                                 ApplicationEventPublisher publisher) {
        this.connections = connections;
        this.events = events;
        this.sealer = sealer;
        this.settings = settings;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * @param token      the path token of the URL the webhook was registered with
     * @param sourceIp   the request's source address as this server sees it (the immediate peer)
     * @param forwarded  the {@code X-Forwarded-For} header — read only when the peer is a configured trusted proxy
     */
    @Transactional
    public Result accept(String token, String sourceIp, String forwarded, byte[] body) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return Result.UNKNOWN_TOKEN;
        }
        String hash = TalkTalkHashes.sha256(token);
        Optional<UUID> orgId = connections.orgIdByTokenHash(hash);
        if (orgId.isEmpty()) {
            return Result.UNKNOWN_TOKEN;
        }
        // The first load of the row is the locked one: a rotation or reconnect that committed meanwhile changed the
        // token hash, and nothing below may write an event or the ingress proof against a state it did not see.
        TalkTalkConnection c = connections.lockByOrgId(orgId.get())
                .filter(x -> hash.equals(x.getWebhookTokenHash()))
                .orElse(null);
        if (c == null) {
            return Result.UNKNOWN_TOKEN;
        }
        if (c.getStatus() != TalkTalkConnection.Status.CONNECTED) {
            return Result.DISCONNECTED;
        }
        String origin = origin(sourceIp, forwarded);
        boolean trusted = TalkTalkSourceAllowlist.documented(origin);
        if (settings.sourceAllowlistEnforced() && !trusted) {
            log.info("talktalk_webhook refused=SOURCE orgId={}", c.getOrgId());
            return Result.SOURCE_REFUSED;
        }
        if (body == null || body.length == 0) {
            return Result.UNREADABLE;
        }
        if (body.length > settings.maxBodyBytes()) {
            return Result.TOO_LARGE;
        }
        TalkTalkEventParser.Parsed p;
        try {
            p = TalkTalkEventParser.parse(body);
        } catch (TalkTalkEventParser.Unreadable e) {
            log.info("talktalk_webhook refused=UNREADABLE code={} orgId={}", e.getMessage(), c.getOrgId());
            return Result.UNREADABLE;
        }
        if (p.partner() != null && !p.partner().equals(c.getPartnerId())) {
            log.info("talktalk_webhook refused=PARTNER_MISMATCH orgId={}", c.getOrgId());
            return Result.PARTNER_MISMATCH;
        }
        Instant now = clock.now();
        TalkTalkEvent e = new TalkTalkEvent();
        e.setOrgId(c.getOrgId());
        e.setConnectionId(c.getId());
        e.setConnectionGeneration(c.getGeneration());
        e.setEventType(TalkTalkEventParser.KNOWN.contains(p.event()) ? p.event() : "unknown");
        e.setBodyHash(TalkTalkHashes.sha256(body));
        if (p.user() != null && !p.user().isBlank()) {
            if (!sealer.available()) {
                return Result.VAULT_UNAVAILABLE;
            }
            LocalSecretSealer.Sealed sealed = sealer.seal(p.user().getBytes(StandardCharsets.UTF_8));
            e.setUserHash(TalkTalkHashes.userHash(c.getOrgId(), p.user()));
            e.setUserSealed(sealed.envelope());
            e.setUserKeyId(sealed.keyId());
        }
        e.setTextContent(p.text());
        e.setInputType(p.inputType());
        e.setStandby(p.standby());
        e.setSourceId(p.sourceId());
        e.setThreadOwnerId(p.threadOwnerId());
        e.setHandoverControl(p.control());
        e.setHandoverTarget(p.targetId());
        e.setProductName(p.productName());
        e.setTrustedSource(trusted);
        e.setState(TalkTalkEvent.State.ACCEPTED);
        e.setReceivedAt(now);
        events.save(e);
        c.setLastEventAt(now);
        if (trusted && c.getFirstTrustedEventAt() == null && c.getTransportMode() == TalkTalkConnection.Mode.LIVE) {
            c.setFirstTrustedEventAt(now);
        }
        connections.save(c);
        publisher.publishEvent(new TalkTalkWorkSignal());
        return Result.ACCEPTED;
    }

    /** The client address: the peer, or — only when the peer is a configured proxy — the last hop it reports. */
    String origin(String peer, String forwarded) {
        if (forwarded == null || forwarded.isBlank() || settings.trustedProxyCidrs().isEmpty()
                || !TalkTalkSourceAllowlist.within(peer, settings.trustedProxyCidrs())) {
            return peer;
        }
        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].strip();
    }
}
