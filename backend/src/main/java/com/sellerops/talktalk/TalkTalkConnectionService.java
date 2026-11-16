package com.sellerops.talktalk;

import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connect, rotate, disconnect, and the live-readiness checklist (lane contract §3). The send key is sealed with the
 * vault key ring and never returned; the webhook URL is returned once, at creation or rotation.
 */
@Service
public class TalkTalkConnectionService {

    private static final Pattern PARTNER = Pattern.compile("[A-Za-z0-9_\\-]{3,64}");

    private final TalkTalkConnectionRepository connections;
    private final TalkTalkAccess access;
    private final LocalSecretSealer sealer;
    private final TalkTalkSettings settings;
    private final TalkTalkTransport transport;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkClock clock;

    public TalkTalkConnectionService(TalkTalkConnectionRepository connections, TalkTalkAccess access,
                                     LocalSecretSealer sealer, TalkTalkSettings settings, TalkTalkTransport transport,
                                     TalkTalkOutboundRepository outbound, TalkTalkClock clock) {
        this.connections = connections;
        this.access = access;
        this.sealer = sealer;
        this.settings = settings;
        this.transport = transport;
        this.outbound = outbound;
        this.clock = clock;
    }

    /** One readiness item: what must be true before a real TalkTalk account may be connected live. */
    public record ReadinessItem(String code, boolean met, String label) {
    }

    public record Readiness(boolean ready, List<ReadinessItem> items, String residualRisk) {
    }

    public record ConnectResult(TalkTalkConnection connection, String webhookUrl) {
        @Override
        public String toString() {
            return "ConnectResult(connection=" + connection + ", webhookUrl=<shown once>)";
        }
    }

    @Transactional
    public ConnectResult connect(UUID orgId, UUID userId, String partnerId, String sendKey) {
        access.owner(orgId, userId);
        String partner = partnerId == null ? "" : partnerId.strip();
        if (!PARTNER.matcher(partner).matches()) {
            throw ApiException.badRequest("파트너 아이디를 확인해 주세요. (영문·숫자, 예: wc1234)");
        }
        if (sendKey == null || sendKey.isBlank() || sendKey.strip().length() > 200) {
            throw ApiException.badRequest("보내기 API 인증 키를 붙여 넣어 주세요.");
        }
        if (!sealer.available()) {
            throw ApiException.conflict("VAULT_UNAVAILABLE", "암호화 키가 설정되지 않아 인증 키를 저장할 수 없어요.");
        }
        LocalSecretSealer.Sealed sealed = sealer.seal(sendKey.strip().getBytes(StandardCharsets.UTF_8));
        String token = TalkTalkHashes.newWebhookToken();
        TalkTalkConnection c = connections.lockByOrgId(orgId).orElseGet(TalkTalkConnection::new);
        if (c.getId() != null) {
            newGeneration(c, "CONNECTION_CHANGED");
        }
        c.setOrgId(orgId);
        c.setPartnerId(partner);
        c.setSendKeySealed(sealed.envelope());
        c.setSendKeyKeyId(sealed.keyId());
        c.setWebhookTokenHash(TalkTalkHashes.sha256(token));
        c.setTransportMode(settings.newConnectionMode());
        c.setStatus(TalkTalkConnection.Status.CONNECTED);
        c.setConnectedBy(userId);
        c.setDisconnectedAt(null);
        c.setFirstTrustedEventAt(null);
        TalkTalkConnection saved = connections.save(c);
        return new ConnectResult(saved, webhookUrl(token));
    }

    @Transactional
    public ConnectResult rotateWebhook(UUID orgId, UUID userId) {
        access.owner(orgId, userId);
        TalkTalkConnection c = connections.lockByOrgId(orgId)
                .filter(x -> x.getStatus() == TalkTalkConnection.Status.CONNECTED)
                .orElseThrow(() -> ApiException.conflict("NOT_CONNECTED", "톡톡이 연동되어 있지 않아요."));
        newGeneration(c, "WEBHOOK_ROTATED");
        String token = TalkTalkHashes.newWebhookToken();
        c.setWebhookTokenHash(TalkTalkHashes.sha256(token));
        return new ConnectResult(connections.save(c), webhookUrl(token));
    }

    /**
     * A new generation: older events are ignored, older queued sends are cancelled and the ingress proof is gone.
     * Called under the connection row lock, so no dispatcher can be between its recheck and its commit meanwhile.
     */
    private void newGeneration(TalkTalkConnection c, String code) {
        c.setGeneration(c.getGeneration() + 1);
        c.setFirstTrustedEventAt(null);
        outbound.cancelAllQueuedForOrg(c.getOrgId(), code, clock.now());
    }

    @Transactional
    public TalkTalkConnection disconnect(UUID orgId, UUID userId) {
        access.owner(orgId, userId);
        TalkTalkConnection c = connections.lockByOrgId(orgId)
                .orElseThrow(() -> ApiException.notFound("톡톡 연동이 없어요."));
        c.setStatus(TalkTalkConnection.Status.DISCONNECTED);
        c.setDisconnectedAt(clock.now());
        // A new token is never shown: a disconnected connection accepts no webhook until it is connected again.
        c.setWebhookTokenHash(TalkTalkHashes.sha256(TalkTalkHashes.newWebhookToken()));
        newGeneration(c, "DISCONNECTED");
        return connections.save(c);
    }

    @Transactional(readOnly = true)
    public Optional<TalkTalkConnection> find(UUID orgId) {
        return connections.findByOrgId(orgId);
    }

    /** The connected row, locked — the first lock of every lane write that may lead to a send. */
    TalkTalkConnection lockConnected(UUID orgId) {
        return connections.lockByOrgId(orgId).filter(c -> c.getStatus() == TalkTalkConnection.Status.CONNECTED)
                .orElseThrow(() -> ApiException.conflict("NOT_CONNECTED", "톡톡이 연동되어 있지 않아요."));
    }

    TalkTalkConnection connected(UUID orgId) {
        return connections.findByOrgId(orgId).filter(c -> c.getStatus() == TalkTalkConnection.Status.CONNECTED)
                .orElseThrow(() -> ApiException.conflict("NOT_CONNECTED", "톡톡이 연동되어 있지 않아요."));
    }

    /** The send key, opened for one request. Empty when the key that sealed it is gone (treated as not connected). */
    Optional<String> sendKey(TalkTalkConnection c) {
        return sealer.open(c.getSendKeyKeyId(), c.getSendKeySealed())
                .map(b -> new String(b, StandardCharsets.UTF_8));
    }

    public Readiness readiness(TalkTalkConnection c) {
        List<ReadinessItem> items = new ArrayList<>();
        items.add(new ReadinessItem("CONNECTION_LIVE_MODE",
                c != null && c.getTransportMode() == TalkTalkConnection.Mode.LIVE
                        && transport.mode() == TalkTalkConnection.Mode.LIVE,
                "실제 전송 방식으로 연결됨 (지금은 모의 전송)"));
        items.add(new ReadinessItem("LIVE_SEND_FLAG", settings.liveSendEnabled(), "서버에서 실제 전송을 켬"));
        items.add(new ReadinessItem("HTTPS_PUBLIC_URL", settings.httpsPublicUrl(), "정식 인증서의 HTTPS 운영 주소"));
        items.add(new ReadinessItem("SOURCE_ALLOWLIST", settings.sourceAllowlistEnforced(),
                "톡톡 공식 문서의 출발 IP 대역만 받기"));
        items.add(new ReadinessItem("SEND_KEY", c != null && c.getSendKeySealed() != null
                && sealer.open(c.getSendKeyKeyId(), c.getSendKeySealed()).isPresent(), "보내기 API 인증 키 저장"));
        items.add(new ReadinessItem("INGRESS_OBSERVED", c != null && c.getFirstTrustedEventAt() != null
                && c.getTransportMode() == TalkTalkConnection.Mode.LIVE,
                "허용 대역에서 비밀 주소로 들어온 첫 이벤트 확인"));
        boolean ready = items.stream().allMatch(ReadinessItem::met);
        return new Readiness(ready, List.copyOf(items),
                "톡톡은 수신 요청 서명을 제공하지 않아요. 비밀 주소와 출발 IP 대역이 신뢰 경계이며, 주소가 새거나 대역 안에서 위조되면 막을 수 없어요.");
    }

    String webhookUrl(String token) {
        String base = settings.publicBaseUrl() == null ? "" : settings.publicBaseUrl().replaceAll("/+$", "");
        return base + "/api/talktalk/webhook/" + token;
    }
}
