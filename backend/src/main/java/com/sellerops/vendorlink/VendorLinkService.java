package com.sellerops.vendorlink;

import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.outbound.OutboundGate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>This seller PC's registration with the vendor server</b> — contract {@code docs/vendor_server_contract_v1.md}
 * §2, and the public-IP answer that registration unlocks (§7).
 *
 * <p>The PC side of the device grant: {@link #start} makes a fresh X25519 key pair and asks the server for a code;
 * the seller approves that code on the vendor server while logged in; {@link #poll} redeems it and seals the install
 * token and private key under the vault key ring. From then on {@link #publicIp} asks the server what address this
 * PC calls from — the value NAVER's call-IP registration needs.
 *
 * <p><b>Off unless all three hold:</b> a seller-PC install ({@code sellerops.local-seller.enabled}), a configured
 * vendor server ({@code sellerops.vendor-link.base-url}), and a vault key to seal with. Off is a status, not an
 * error: the connection screen keeps its manual call-IP guidance (§7, proposal §17.9).
 */
@Service
public class VendorLinkService {

    /** How long one public-IP answer is reused — the connection screen asks on every render. */
    static final Duration PUBLIC_IP_REUSE = Duration.ofSeconds(60);

    public enum State { OFF, UNLINKED, PENDING, CODE_EXPIRED, LINKED }

    public enum OffReason { NOT_LOCAL_SELLER, NOT_CONFIGURED, NO_VAULT_KEY }

    /**
     * What the screen may know. Never the token, the device code or a key. {@code approvalUrl} is the vendor
     * server's approval page with the user code filled in, for the screen to open in the seller's browser — the
     * same PC, so the seller does not move to a phone and copy a code (owner decision 2026-10-17).
     */
    public record Status(State state, OffReason offReason, String userCode, Instant codeExpiresAt,
                         Integer pollIntervalSeconds, String approvalUrl, String deviceName, Instant linkedAt) {

        static Status off(OffReason reason) {
            return new Status(State.OFF, reason, null, null, null, null, null, null);
        }

        static Status of(State state) {
            return new Status(state, null, null, null, null, null, null, null);
        }
    }

    /** Why there is no address to show. Closed values the screen maps to its own guidance. */
    public enum NoIpReason { NOT_LINKED, IPV6_ONLY, NOT_AUTHORIZED, UNREACHABLE }

    public record PublicIpView(String ipv4, NoIpReason reason) {}

    private record PendingGrant(String deviceCode, String userCode, Instant expiresAt, int interval,
                                InstallKeyPair keys, String deviceName) {}

    private record CachedIp(java.util.UUID linkId, Instant at, PublicIpView view) {}

    private final VendorInstallLinkRepository links;
    private final LocalSecretSealer sealer;
    private final VendorServerClient client;
    private final boolean localSeller;
    private final Clock clock;
    private PendingGrant pending;
    private CachedIp cachedIp;

    @Autowired
    public VendorLinkService(VendorInstallLinkRepository links, LocalSecretSealer sealer,
                             @Value("${sellerops.local-seller.enabled:false}") boolean localSeller,
                             @Value("${sellerops.vendor-link.base-url:}") String baseUrl) {
        this(links, sealer, baseUrl == null || baseUrl.isBlank() ? null : new JdkVendorServerClient(URI.create(baseUrl.trim())),
                localSeller, Clock.systemUTC());
    }

    VendorLinkService(VendorInstallLinkRepository links, LocalSecretSealer sealer, VendorServerClient client,
                      boolean localSeller, Clock clock) {
        this.links = links;
        this.sealer = sealer;
        this.client = client;
        this.localSeller = localSeller;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public synchronized Status status() {
        Optional<OffReason> off = offReason();
        if (off.isPresent()) {
            return Status.off(off.get());
        }
        Optional<VendorInstallLink> link = links.findFirstByOrderByCreatedAtDesc();
        if (link.isPresent()) {
            return new Status(State.LINKED, null, null, null, null, null, link.get().getDeviceName(),
                    link.get().getCreatedAt());
        }
        if (pending != null && pending.expiresAt().isAfter(clock.instant())) {
            return new Status(State.PENDING, null, pending.userCode(), pending.expiresAt(), pending.interval(),
                    client.approvalPage(pending.userCode()).toString(), pending.deviceName(), null);
        }
        return Status.of(State.UNLINKED);
    }

    /** Asks the server for a code to approve. A second start replaces the first pending code. */
    public synchronized Status start() {
        Optional<OffReason> off = offReason();
        if (off.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "공급자 서버 연결을 사용할 수 없는 설치입니다.");
        }
        if (links.findFirstByOrderByCreatedAtDesc().isPresent()) {
            throw ApiException.conflict("이미 공급자 서버에 연결된 PC입니다. 연결을 해제한 뒤 다시 시도하세요.");
        }
        InstallKeyPair keys = InstallKeyPair.generate();
        String deviceName = deviceName();
        VendorServerClient.Code code = call(() -> client.requestCode(deviceName, null, keys.publicKeyBase64()));
        pending = new PendingGrant(code.deviceCode(), code.userCode(), code.expiresAt(), code.intervalSeconds(), keys,
                deviceName);
        return status();
    }

    /** One poll of the pending grant. Linking stores the sealed token and key and ends the grant. */
    @Transactional
    public synchronized Status poll() {
        if (offReason().isPresent() || pending == null) {
            return status();
        }
        if (!pending.expiresAt().isAfter(clock.instant())) {
            pending = null;
            return Status.of(State.CODE_EXPIRED);
        }
        VendorServerClient.Poll poll;
        try {
            poll = client.poll(pending.deviceCode());
        } catch (VendorServerClient.Unreachable e) {
            return status();
        }
        if (poll instanceof VendorServerClient.Expired) {
            pending = null;
            return Status.of(State.CODE_EXPIRED);
        }
        if (poll instanceof VendorServerClient.Linked linked) {
            LocalSecretSealer.Sealed token = sealer.seal(linked.token().getBytes(StandardCharsets.UTF_8));
            LocalSecretSealer.Sealed key = sealer.seal(pending.keys().privateKey());
            if (!token.keyId().equals(key.keyId())) {
                throw new IllegalStateException("vault key changed while linking");
            }
            links.deleteAll();
            VendorInstallLink link = new VendorInstallLink();
            link.setInstallId(linked.installId());
            link.setDeviceName(pending.deviceName());
            link.setTokenEnc(token.envelope());
            link.setPrivateKeyEnc(key.envelope());
            link.setEncryptionKeyId(token.keyId());
            link.setTokenExpiresAt(linked.expiresAt());
            links.save(link);
            pending = null;
            cachedIp = null;
        }
        return status();
    }

    /** Drops this PC's registration — on the server if it can be reached, here in any case. */
    @Transactional
    public synchronized Status unlink() {
        pending = null;
        cachedIp = null;
        Optional<VendorInstallLink> link = links.findFirstByOrderByCreatedAtDesc();
        if (link.isPresent() && client != null) {
            Optional<String> token = openToken(link.get());
            if (token.isPresent()) {
                try {
                    client.unlink(token.get());
                } catch (RuntimeException e) {
                    // Best effort: the seller can also revoke this PC from their install list on the server.
                }
            }
        }
        links.deleteAll();
        return status();
    }

    /** This PC's public IPv4 as the vendor server sees it, or why there is none. */
    @Transactional(readOnly = true)
    public synchronized PublicIpView publicIp() {
        if (offReason().isPresent()) {
            return new PublicIpView(null, NoIpReason.NOT_LINKED);
        }
        Optional<VendorInstallLink> link = links.findFirstByOrderByCreatedAtDesc();
        if (link.isEmpty() || !link.get().getTokenExpiresAt().isAfter(clock.instant())) {
            return new PublicIpView(null, NoIpReason.NOT_LINKED);
        }
        Instant now = clock.instant();
        if (cachedIp != null && cachedIp.linkId().equals(link.get().getId())
                && cachedIp.at().plus(PUBLIC_IP_REUSE).isAfter(now)) {
            return cachedIp.view();
        }
        Optional<String> token = openToken(link.get());
        if (token.isEmpty()) {
            return new PublicIpView(null, NoIpReason.NOT_LINKED);
        }
        PublicIpView view;
        try {
            VendorServerClient.PublicIp answer = client.publicIp(token.get());
            if (answer instanceof VendorServerClient.Ipv4 v4) {
                view = new PublicIpView(v4.address(), null);
            } else if (answer instanceof VendorServerClient.NoIpv4) {
                view = new PublicIpView(null, NoIpReason.IPV6_ONLY);
            } else {
                view = new PublicIpView(null, NoIpReason.NOT_AUTHORIZED);
            }
        } catch (RuntimeException e) {
            view = new PublicIpView(null, NoIpReason.UNREACHABLE);
        }
        cachedIp = new CachedIp(link.get().getId(), now, view);
        return view;
    }

    /**
     * This PC's install token while it is linked and the token unexpired; empty otherwise (off, unlinked, expired,
     * or the vault key cannot open it). For the relays in this package — it never leaves the process except as the
     * bearer of a request to the vendor server.
     */
    @Transactional(readOnly = true)
    synchronized Optional<String> installToken() {
        if (offReason().isPresent()) {
            return Optional.empty();
        }
        return links.findFirstByOrderByCreatedAtDesc()
                .filter(l -> l.getTokenExpiresAt().isAfter(clock.instant()))
                .flatMap(this::openToken);
    }

    VendorServerClient client() {
        return client;
    }

    /** A closed platform name — never the host name, which often carries the owner's name. */
    static String deviceName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String family = os.startsWith("mac") ? "macOS" : os.startsWith("windows") ? "Windows"
                : os.startsWith("linux") ? "Linux" : "PC";
        String arch = System.getProperty("os.arch", "").replaceAll("[^A-Za-z0-9_]", "");
        return arch.isEmpty() ? family : family + " (" + arch + ")";
    }

    private Optional<OffReason> offReason() {
        if (!localSeller) {
            return Optional.of(OffReason.NOT_LOCAL_SELLER);
        }
        if (client == null) {
            return Optional.of(OffReason.NOT_CONFIGURED);
        }
        if (!sealer.available()) {
            return Optional.of(OffReason.NO_VAULT_KEY);
        }
        return Optional.empty();
    }

    private Optional<String> openToken(VendorInstallLink link) {
        try {
            return sealer.open(link.getEncryptionKeyId(), link.getTokenEnc())
                    .map(b -> new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (VendorServerClient.Unreachable | OutboundGate.OutboundRefusedException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "공급자 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }
}
