package com.sellerops.vendorlink;

import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.outbound.OutboundGate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Each organisation's registration with the vendor server</b> — contract {@code docs/vendor_server_contract_v1.md}
 * §2, and the public-IP answer that registration unlocks (§7).
 *
 * <p><b>One registration per organisation, and an organisation only ever uses its own</b> (V161;
 * {@code docs/standing/server_operation_boundary_v1.md} §1). Every method takes the organisation it acts for; there is
 * no "this backend's token". Until 2026-10-31 there was — one row for the whole backend — which was true on a seller PC
 * and would have handed one seller's server account to every organisation on a shared backend.
 *
 * <p>The PC side of the device grant: {@link #start} makes a fresh X25519 key pair and asks the server for a code;
 * the seller approves that code on the vendor server while logged in; {@link #poll} redeems it and seals the install
 * token and private key under the vault key ring. From then on {@link #publicIp} asks the server what address this
 * PC calls from — the value NAVER's call-IP registration needs.
 *
 * <p><b>Two modes</b> ({@code sellerops.vendor-link.mode}):
 * <ul>
 *   <li>{@link Mode#INSTALL} (default) — a seller PC. Off unless all three hold: a seller-PC install
 *       ({@code sellerops.local-seller.enabled}), a configured vendor server ({@code sellerops.vendor-link.base-url}),
 *       and a vault key to seal with. The seller approves this PC once; its install token is sealed here.</li>
 *   <li>{@link Mode#WORKSPACE} — the Reviewnary workspace server ({@code docs/standing/server_operation_boundary_v1.md}
 *       §5, owner decision 2026-10-31). The seller has one account, this one: there is nothing to approve and no
 *       vendor-server sign-in. This server proves itself with one service token
 *       ({@code sellerops.vendor-link.service-token}) and names the organisation on every call; the vendor server
 *       answers only for organisations an operator linked to a seller account there. An organisation is "linked" here
 *       exactly when the vendor server lists it. Off unless the server and the token are both configured.</li>
 * </ul>
 * Off is a status, not an error: the connection screen keeps its manual call-IP guidance (§7, proposal §17.9).
 */
@Service
public class VendorLinkService {

    /** How long one public-IP answer is reused — the connection screen asks on every render. */
    static final Duration PUBLIC_IP_REUSE = Duration.ofSeconds(60);

    public enum State { OFF, UNLINKED, PENDING, CODE_EXPIRED, LINKED }

    public enum OffReason { NOT_LOCAL_SELLER, NOT_CONFIGURED, NO_VAULT_KEY }

    /** A seller PC that links itself, or the workspace server whose organisations an operator links. */
    public enum Mode { INSTALL, WORKSPACE }

    /** How long the vendor server's list of linked organisations is reused (workspace mode). */
    static final Duration ORGANISATIONS_REUSE = Duration.ofSeconds(30);

    /**
     * What the screen may know. Never the token, the device code or a key. {@code approvalUrl} is the vendor
     * server's approval page with the user code filled in, for the screen to open in the seller's browser — the
     * same PC, so the seller does not move to a phone and copy a code (owner decision 2026-10-17).
     */
    public record Status(State state, OffReason offReason, String userCode, Instant codeExpiresAt,
                         Integer pollIntervalSeconds, String approvalUrl, String deviceName, Instant linkedAt,
                         Mode mode) {

        static Status off(OffReason reason, Mode mode) {
            return new Status(State.OFF, reason, null, null, null, null, null, null, mode);
        }

        static Status of(State state, Mode mode) {
            return new Status(state, null, null, null, null, null, null, null, mode);
        }
    }

    /** Why there is no address to show. Closed values the screen maps to its own guidance. */
    public enum NoIpReason { NOT_LINKED, IPV6_ONLY, NOT_AUTHORIZED, UNREACHABLE }

    public record PublicIpView(String ipv4, NoIpReason reason) {}

    private record PendingGrant(String deviceCode, String userCode, Instant expiresAt, int interval,
                                InstallKeyPair keys, String deviceName) {}

    private record CachedIp(UUID linkId, Instant at, PublicIpView view) {}

    private record CachedOrganisations(Instant at, List<UUID> orgs) {}

    private final VendorInstallLinkRepository links;
    private final LocalSecretSealer sealer;
    private final VendorServerClient client;
    private final boolean localSeller;
    private final Mode mode;
    private final Clock clock;
    private CachedOrganisations organisations;
    private final Map<UUID, PendingGrant> pending = new HashMap<>();
    private final Map<UUID, CachedIp> cachedIp = new HashMap<>();

    @Autowired
    public VendorLinkService(VendorInstallLinkRepository links, LocalSecretSealer sealer,
                             @Value("${sellerops.local-seller.enabled:false}") boolean localSeller,
                             @Value("${sellerops.vendor-link.base-url:}") String baseUrl,
                             @Value("${sellerops.vendor-link.mode:INSTALL}") Mode mode,
                             @Value("${sellerops.vendor-link.service-token:}") String serviceToken) {
        this(links, sealer, client(baseUrl, mode, serviceToken), localSeller, mode, Clock.systemUTC());
    }

    VendorLinkService(VendorInstallLinkRepository links, LocalSecretSealer sealer, VendorServerClient client,
                      boolean localSeller, Clock clock) {
        this(links, sealer, client, localSeller, Mode.INSTALL, clock);
    }

    VendorLinkService(VendorInstallLinkRepository links, LocalSecretSealer sealer, VendorServerClient client,
                      boolean localSeller, Mode mode, Clock clock) {
        this.links = links;
        this.sealer = sealer;
        this.client = client;
        this.localSeller = localSeller;
        this.mode = Objects.requireNonNull(mode);
        this.clock = clock;
    }

    /** No client without a server; in workspace mode, none without the service token either. */
    private static VendorServerClient client(String baseUrl, Mode mode, String serviceToken) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        URI base = URI.create(baseUrl.trim());
        if (mode == Mode.WORKSPACE) {
            return serviceToken == null || serviceToken.isBlank() ? null : new JdkVendorServerClient(base, serviceToken);
        }
        return new JdkVendorServerClient(base);
    }

    public Mode mode() {
        return mode;
    }

    @Transactional(readOnly = true)
    public synchronized Status status(UUID orgId) {
        Objects.requireNonNull(orgId);
        Optional<OffReason> off = offReason();
        if (off.isPresent()) {
            return Status.off(off.get(), mode);
        }
        if (mode == Mode.WORKSPACE) {
            // Nothing to approve: an operator links the organisation on the vendor server, or has not yet.
            return Status.of(linkedOrganisations().contains(orgId) ? State.LINKED : State.UNLINKED, mode);
        }
        Optional<VendorInstallLink> link = links.findByOrgId(orgId);
        if (link.isPresent()) {
            return new Status(State.LINKED, null, null, null, null, null, link.get().getDeviceName(),
                    link.get().getCreatedAt(), mode);
        }
        PendingGrant grant = pending.get(orgId);
        if (grant != null && grant.expiresAt().isAfter(clock.instant())) {
            return new Status(State.PENDING, null, grant.userCode(), grant.expiresAt(), grant.interval(),
                    client.approvalPage(grant.userCode()).toString(), grant.deviceName(), null, mode);
        }
        return Status.of(State.UNLINKED, mode);
    }

    /** Asks the server for a code to approve. A second start replaces the organisation's first pending code. */
    public synchronized Status start(UUID orgId) {
        Objects.requireNonNull(orgId);
        Optional<OffReason> off = offReason();
        if (off.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "공급자 서버 연결을 사용할 수 없는 설치입니다.");
        }
        noApprovalOnTheWorkspaceServer();
        if (links.findByOrgId(orgId).isPresent()) {
            throw ApiException.conflict("이미 공급자 서버에 연결된 PC입니다. 연결을 해제한 뒤 다시 시도하세요.");
        }
        InstallKeyPair keys = InstallKeyPair.generate();
        String deviceName = deviceName();
        VendorServerClient.Code code = call(() -> client.requestCode(deviceName, null, keys.publicKeyBase64()));
        pending.put(orgId, new PendingGrant(code.deviceCode(), code.userCode(), code.expiresAt(),
                code.intervalSeconds(), keys, deviceName));
        return status(orgId);
    }

    /** One poll of the organisation's pending grant. Linking stores the sealed token and key and ends the grant. */
    @Transactional
    public synchronized Status poll(UUID orgId) {
        Objects.requireNonNull(orgId);
        PendingGrant grant = pending.get(orgId);
        if (offReason().isPresent() || mode == Mode.WORKSPACE || grant == null) {
            return status(orgId);
        }
        if (!grant.expiresAt().isAfter(clock.instant())) {
            pending.remove(orgId);
            return Status.of(State.CODE_EXPIRED, mode);
        }
        VendorServerClient.Poll poll;
        try {
            poll = client.poll(grant.deviceCode());
        } catch (VendorServerClient.Unreachable e) {
            return status(orgId);
        }
        if (poll instanceof VendorServerClient.Expired) {
            pending.remove(orgId);
            return Status.of(State.CODE_EXPIRED, mode);
        }
        if (poll instanceof VendorServerClient.Linked linked) {
            LocalSecretSealer.Sealed token = sealer.seal(linked.token().getBytes(StandardCharsets.UTF_8));
            LocalSecretSealer.Sealed key = sealer.seal(grant.keys().privateKey());
            if (!token.keyId().equals(key.keyId())) {
                throw new IllegalStateException("vault key changed while linking");
            }
            links.deleteByOrgId(orgId);
            links.flush();
            VendorInstallLink link = new VendorInstallLink();
            link.setOrgId(orgId);
            link.setInstallId(linked.installId());
            link.setDeviceName(grant.deviceName());
            link.setTokenEnc(token.envelope());
            link.setPrivateKeyEnc(key.envelope());
            link.setEncryptionKeyId(token.keyId());
            link.setTokenExpiresAt(linked.expiresAt());
            links.save(link);
            pending.remove(orgId);
            cachedIp.remove(orgId);
        }
        return status(orgId);
    }

    /** Drops the organisation's registration — on the server if it can be reached, here in any case. */
    @Transactional
    public synchronized Status unlink(UUID orgId) {
        Objects.requireNonNull(orgId);
        noApprovalOnTheWorkspaceServer();
        pending.remove(orgId);
        cachedIp.remove(orgId);
        Optional<VendorInstallLink> link = links.findByOrgId(orgId);
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
        links.deleteByOrgId(orgId);
        return status(orgId);
    }

    /** The public IPv4 the vendor server sees the organisation's registration call from, or why there is none. */
    @Transactional(readOnly = true)
    public synchronized PublicIpView publicIp(UUID orgId) {
        Objects.requireNonNull(orgId);
        if (offReason().isPresent() || mode == Mode.WORKSPACE) {
            // The workspace server's address is fixed and registered by the operator, not learned per seller.
            return new PublicIpView(null, NoIpReason.NOT_LINKED);
        }
        Optional<VendorInstallLink> link = links.findByOrgId(orgId);
        if (link.isEmpty() || !link.get().getTokenExpiresAt().isAfter(clock.instant())) {
            return new PublicIpView(null, NoIpReason.NOT_LINKED);
        }
        Instant now = clock.instant();
        CachedIp cached = cachedIp.get(orgId);
        if (cached != null && cached.linkId().equals(link.get().getId())
                && cached.at().plus(PUBLIC_IP_REUSE).isAfter(now)) {
            return cached.view();
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
        cachedIp.put(orgId, new CachedIp(link.get().getId(), now, view));
        return view;
    }

    /**
     * The organisation's install token while it is linked and the token unexpired; empty otherwise (off, unlinked,
     * expired, or the vault key cannot open it). For the relays in this package — it never leaves the process except
     * as the bearer of a request to the vendor server, and only for {@code orgId}'s own work.
     */
    @Transactional(readOnly = true)
    synchronized Optional<String> installToken(UUID orgId) {
        Objects.requireNonNull(orgId);
        if (offReason().isPresent()) {
            return Optional.empty();
        }
        if (mode == Mode.WORKSPACE) {
            // The client carries the service token; what it is handed per call is the organisation it speaks for.
            return linkedOrganisations().contains(orgId) ? Optional.of(orgId.toString()) : Optional.empty();
        }
        return links.findByOrgId(orgId)
                .filter(l -> l.getTokenExpiresAt().isAfter(clock.instant()))
                .flatMap(this::openToken);
    }

    /** The organisations with an unexpired registration — what the scheduled pull and the arrival watch follow. */
    @Transactional(readOnly = true)
    synchronized List<UUID> linkedOrganisations() {
        if (offReason().isPresent()) {
            return List.of();
        }
        Instant now = clock.instant();
        if (mode == Mode.WORKSPACE) {
            if (organisations == null || !organisations.at().plus(ORGANISATIONS_REUSE).isAfter(now)) {
                try {
                    organisations = new CachedOrganisations(now, List.copyOf(client.workspaceOrganisations()));
                } catch (VendorServerClient.Refused e) {
                    organisations = new CachedOrganisations(now, List.of());
                } catch (VendorServerClient.Unreachable | OutboundGate.OutboundRefusedException e) {
                    // Unreachable: keep what was last known, so a blip does not unlink every organisation's screen.
                    return organisations == null ? List.of() : organisations.orgs();
                }
            }
            return organisations.orgs();
        }
        return links.findAll().stream()
                .filter(l -> l.getTokenExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(VendorInstallLink::getCreatedAt))
                .map(VendorInstallLink::getOrgId)
                .toList();
    }

    /** Whether the link is off for this whole backend (not a seller install, no server, or no vault key). */
    boolean off() {
        return offReason().isPresent();
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

    /** The workspace server has no PC to approve or unlink; an operator links its organisations. */
    private void noApprovalOnTheWorkspaceServer() {
        if (mode == Mode.WORKSPACE) {
            throw ApiException.conflict("이 서비스는 PC 연결이 없습니다. 담당자 리뷰는 운영팀이 계정에 연결합니다.");
        }
    }

    private Optional<OffReason> offReason() {
        if (mode == Mode.WORKSPACE) {
            return client == null ? Optional.of(OffReason.NOT_CONFIGURED) : Optional.empty();
        }
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
