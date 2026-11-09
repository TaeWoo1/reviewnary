package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.vendorlink.VendorLinkService.NoIpReason;
import com.sellerops.vendorlink.VendorLinkService.OffReason;
import com.sellerops.vendorlink.VendorLinkService.State;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A seller PC links to the vendor server once, keeps only sealed secrets, and learns its public IPv4</b> —
 * contract {@code docs/vendor_server_contract_v1.md} §2 · §7. The server is a fake here;
 * {@code JdkVendorServerClientTest} holds the wire shapes and the transport.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class VendorLinkServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32});
    private static final String TOKEN = "rvi_synthetic-install-token-for-tests";
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @Autowired
    private VendorInstallLinkRepository links;

    private FakeServer server;
    private MovableClock clock;
    private VendorLinkService service;

    @BeforeEach
    void setUp() {
        links.deleteAll();
        server = new FakeServer();
        clock = new MovableClock(Instant.parse("2026-10-17T00:00:00Z"));
        service = new VendorLinkService(links, sealer(KEY), server, true, clock);
    }

    private static LocalSecretSealer sealer(String key) {
        return new LocalSecretSealer(new VaultKeyRing(key, "test-key-1", ""));
    }

    @Test
    void offUnlessSellerPcConfiguredServerAndVaultKeyAllHold() {
        assertThat(new VendorLinkService(links, sealer(KEY), server, false, clock).status(ORG).offReason())
                .isEqualTo(OffReason.NOT_LOCAL_SELLER);
        assertThat(new VendorLinkService(links, sealer(KEY), null, true, clock).status(ORG).offReason())
                .isEqualTo(OffReason.NOT_CONFIGURED);
        VendorLinkService noKey = new VendorLinkService(links, sealer(""), server, true, clock);
        assertThat(noKey.status(ORG).offReason()).isEqualTo(OffReason.NO_VAULT_KEY);
        assertThatThrownBy(() -> noKey.start(ORG)).isInstanceOf(ApiException.class);
        assertThat(noKey.publicIp(ORG).reason()).isEqualTo(NoIpReason.NOT_LINKED);
        assertThat(server.calls).as("an install that is off never calls the server").isEmpty();
        assertThat(service.status(ORG).state()).isEqualTo(State.UNLINKED);
    }

    @Test
    void startSendsAFreshPublicKeyAndAPlatformNameNeverTheHostName() throws Exception {
        VendorLinkService.Status pending = service.start(ORG);
        assertThat(pending.state()).isEqualTo(State.PENDING);
        assertThat(pending.userCode()).isEqualTo("BCDFGHJK");
        assertThat(pending.approvalUrl()).isEqualTo("http://127.0.0.1:8090/link?code=BCDFGHJK");
        assertThat(Base64.getDecoder().decode(server.publicKey)).hasSize(32);
        assertThat(server.deviceName).matches("(macOS|Windows|Linux|PC)( \\([A-Za-z0-9_]+\\))?");
        String host = java.net.InetAddress.getLocalHost().getHostName();
        assertThat(server.deviceName).doesNotContain(host);
    }

    @Test
    void linkingSealsTheTokenAndAPrivateKeyThatMatchesTheRegisteredPublicKey() throws Exception {
        service.start(ORG);
        assertThat(service.poll(ORG).state()).isEqualTo(State.PENDING);
        server.approved = true;
        VendorLinkService.Status linked = service.poll(ORG);
        assertThat(linked.state()).isEqualTo(State.LINKED);
        assertThat(linked.userCode()).isNull();
        assertThat(linked.approvalUrl()).isNull();

        VendorInstallLink row = links.findAll().get(0);
        assertThat(indexOf(row.getTokenEnc(), TOKEN.getBytes(StandardCharsets.UTF_8))).as("token in plain text").isEqualTo(-1);
        byte[] privateKey = sealer(KEY).open(row.getEncryptionKeyId(), row.getPrivateKeyEnc()).orElseThrow();
        assertThat(agree(privateKey, server.publicKey)).as("the stored private key is the registered key's pair")
                .isTrue();
        assertThat(sealer(KEY).open(row.getEncryptionKeyId(), row.getTokenEnc()).map(b -> new String(b,
                StandardCharsets.UTF_8))).hasValue(TOKEN);

        assertThatThrownBy(() -> service.start(ORG)).as("a linked PC must unlink first").isInstanceOf(ApiException.class);
    }

    @Test
    void anExpiredCodeEndsThePendingGrant() {
        service.start(ORG);
        clock.advance(Duration.ofMinutes(11));
        assertThat(service.poll(ORG).state()).isEqualTo(State.CODE_EXPIRED);
        assertThat(service.status(ORG).state()).isEqualTo(State.UNLINKED);
        service.start(ORG);
        server.expired = true;
        assertThat(service.poll(ORG).state()).isEqualTo(State.CODE_EXPIRED);
    }

    @Test
    void theLinkedPcLearnsItsIpv4AndReusesTheAnswerForAMinute() {
        link();
        server.ip = new VendorServerClient.Ipv4("203.0.113.7");
        assertThat(service.publicIp(ORG)).isEqualTo(new VendorLinkService.PublicIpView("203.0.113.7", null));
        assertThat(server.lastBearer).isEqualTo(TOKEN);
        int calls = server.ipCalls;
        service.publicIp(ORG);
        assertThat(server.ipCalls).isEqualTo(calls);
        clock.advance(VendorLinkService.PUBLIC_IP_REUSE);
        server.ip = new VendorServerClient.NoIpv4();
        assertThat(service.publicIp(ORG).reason()).isEqualTo(NoIpReason.IPV6_ONLY);
        clock.advance(VendorLinkService.PUBLIC_IP_REUSE);
        server.ip = new VendorServerClient.NotAuthorized();
        assertThat(service.publicIp(ORG).reason()).isEqualTo(NoIpReason.NOT_AUTHORIZED);
        clock.advance(VendorLinkService.PUBLIC_IP_REUSE);
        server.unreachable = true;
        assertThat(service.publicIp(ORG)).isEqualTo(new VendorLinkService.PublicIpView(null, NoIpReason.UNREACHABLE));
    }

    @Test
    void withoutALinkThereIsNoAddressAndNoRequest() {
        assertThat(service.publicIp(ORG).reason()).isEqualTo(NoIpReason.NOT_LINKED);
        assertThat(server.ipCalls).isZero();
    }

    @Test
    void unlinkingDropsTheLocalRecordEvenWhenTheServerCannotBeReached() {
        link();
        server.unreachable = true;
        assertThat(service.unlink(ORG).state()).isEqualTo(State.UNLINKED);
        assertThat(links.count()).isZero();
        assertThat(server.calls).contains("unlink");
    }

    /**
     * Two organisations on one backend: each links with its own code, keeps its own token, and never gets the
     * other's — the single-install assumption this service had until 2026-10-31 (V161).
     */
    @Test
    void eachOrganisationLinksAndUsesOnlyItsOwnRegistration() {
        link();
        assertThat(service.status(OTHER_ORG).state()).as("one organisation's link is not another's")
                .isEqualTo(State.UNLINKED);
        assertThat(service.installToken(OTHER_ORG)).isEmpty();
        assertThat(service.publicIp(OTHER_ORG).reason()).isEqualTo(NoIpReason.NOT_LINKED);

        VendorLinkService.Status pending = service.start(OTHER_ORG);
        assertThat(pending.state()).isEqualTo(State.PENDING);
        assertThat(service.status(ORG).state()).as("a pending code elsewhere leaves this link alone")
                .isEqualTo(State.LINKED);
        service.poll(OTHER_ORG);
        assertThat(service.status(OTHER_ORG).state()).isEqualTo(State.LINKED);

        assertThat(links.count()).isEqualTo(2);
        assertThat(service.installToken(ORG)).hasValue(TOKEN);
        assertThat(service.installToken(OTHER_ORG)).hasValueSatisfying(t -> assertThat(t).isNotEqualTo(TOKEN));
        assertThat(service.linkedOrganisations()).containsExactlyInAnyOrder(ORG, OTHER_ORG);

        service.publicIp(OTHER_ORG);
        assertThat(server.lastBearer).as("the address is asked with the asking organisation's token")
                .isNotEqualTo(TOKEN);

        service.unlink(OTHER_ORG);
        assertThat(server.unlinked).singleElement().isNotEqualTo(TOKEN);
        assertThat(service.status(ORG).state()).as("unlinking one leaves the other").isEqualTo(State.LINKED);
        assertThat(service.installToken(ORG)).hasValue(TOKEN);
        assertThat(service.linkedOrganisations()).containsExactly(ORG);
    }

    @Test
    void twoOrganisationsCanWaitOnCodesAtTheSameTime() {
        service.start(ORG);
        service.start(OTHER_ORG);
        server.approved = true;
        assertThat(service.poll(ORG).state()).as("the second code did not replace the first")
                .isEqualTo(State.LINKED);
        assertThat(service.poll(OTHER_ORG).state()).isEqualTo(State.LINKED);
        assertThat(service.installToken(ORG)).hasValue(TOKEN);
        assertThat(service.installToken(OTHER_ORG)).hasValueSatisfying(t -> assertThat(t).isNotEqualTo(TOKEN));
    }

    @Test
    void relinkingReplacesOnlyTheOrganisationsOwnRow() {
        link();
        service.start(OTHER_ORG);
        service.poll(OTHER_ORG);
        service.unlink(ORG);
        link();
        assertThat(links.count()).isEqualTo(2);
        assertThat(links.findByOrgId(OTHER_ORG)).isPresent();
    }

    /**
     * The workspace server (boundary §5): no approval code, no sealed token, no public IP — an organisation is linked
     * exactly when the vendor server lists it, and each call is for the organisation it names.
     */
    @Test
    void onTheWorkspaceServerAnOrganisationIsLinkedWhenTheVendorServerListsIt() {
        VendorLinkService workspace = new VendorLinkService(links, sealer(""), server, false,
                VendorLinkService.Mode.WORKSPACE, clock);
        server.organisations = List.of(ORG);

        assertThat(workspace.status(ORG).state()).isEqualTo(State.LINKED);
        assertThat(workspace.status(ORG).mode()).isEqualTo(VendorLinkService.Mode.WORKSPACE);
        assertThat(workspace.status(OTHER_ORG).state()).isEqualTo(State.UNLINKED);
        assertThat(workspace.installToken(ORG)).as("the call names the organisation").hasValue(ORG.toString());
        assertThat(workspace.installToken(OTHER_ORG)).isEmpty();
        assertThat(workspace.linkedOrganisations()).containsExactly(ORG);
        assertThat(workspace.publicIp(ORG).reason()).isEqualTo(NoIpReason.NOT_LINKED);

        assertThatThrownBy(() -> workspace.start(OTHER_ORG)).as("nothing for a seller to approve")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workspace.unlink(ORG)).isInstanceOf(ApiException.class);
        assertThat(server.calls).as("no code asked, no IP asked").doesNotContain("code", "ip");
        assertThat(links.count()).as("nothing sealed here").isZero();

        // The list is reused briefly, then asked again: an operator's new link shows within the reuse window.
        int asked = server.organisationCalls;
        workspace.status(ORG);
        assertThat(server.organisationCalls).isEqualTo(asked);
        server.organisations = List.of(ORG, OTHER_ORG);
        clock.advance(VendorLinkService.ORGANISATIONS_REUSE);
        assertThat(workspace.status(OTHER_ORG).state()).isEqualTo(State.LINKED);

        // The vendor server unreachable: what was known stays; refused: nothing is linked.
        clock.advance(VendorLinkService.ORGANISATIONS_REUSE);
        server.unreachable = true;
        assertThat(workspace.linkedOrganisations()).containsExactly(ORG, OTHER_ORG);
        server.unreachable = false;
        server.refuseOrganisations = true;
        clock.advance(VendorLinkService.ORGANISATIONS_REUSE);
        assertThat(workspace.linkedOrganisations()).isEmpty();
        assertThat(workspace.installToken(ORG)).isEmpty();
    }

    @Test
    void theWorkspaceServerIsOffWithoutAClient() {
        VendorLinkService workspace = new VendorLinkService(links, sealer(""), null, false,
                VendorLinkService.Mode.WORKSPACE, clock);
        assertThat(workspace.status(ORG).offReason()).isEqualTo(OffReason.NOT_CONFIGURED);
        assertThat(workspace.linkedOrganisations()).isEmpty();
    }

    private void link() {
        service.start(ORG);
        server.approved = true;
        service.poll(ORG);
        assertThat(service.status(ORG).state()).isEqualTo(State.LINKED);
    }

    private static boolean agree(byte[] pkcs8, String rawPublicBase64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("X25519");
        PrivateKey mine = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        KeyPair peer = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] spkiPrefix = Arrays.copyOf(peer.getPublic().getEncoded(), 12);
        byte[] raw = Base64.getDecoder().decode(rawPublicBase64);
        byte[] spki = new byte[44];
        System.arraycopy(spkiPrefix, 0, spki, 0, 12);
        System.arraycopy(raw, 0, spki, 12, 32);
        PublicKey registered = kf.generatePublic(new X509EncodedKeySpec(spki));
        KeyAgreement a = KeyAgreement.getInstance("X25519");
        a.init(mine);
        a.doPhase(peer.getPublic(), true);
        KeyAgreement b = KeyAgreement.getInstance("X25519");
        b.init(peer.getPrivate());
        b.doPhase(registered, true);
        return HexFormat.of().formatHex(a.generateSecret()).equals(HexFormat.of().formatHex(b.generateSecret()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class FakeServer implements VendorServerClient {
        final List<String> calls = new ArrayList<>();
        String deviceName;
        String publicKey;
        String lastBearer;
        boolean approved;
        boolean expired;
        boolean unreachable;
        int ipCalls;
        PublicIp ip = new Ipv4("203.0.113.7");
        List<UUID> organisations = List.of();
        int organisationCalls;
        boolean refuseOrganisations;

        @Override
        public List<UUID> workspaceOrganisations() {
            organisationCalls++;
            if (unreachable) {
                throw new Unreachable();
            }
            if (refuseOrganisations) {
                throw new Refused();
            }
            return organisations;
        }

        int codes;
        final java.util.Map<String, String> tokens = new java.util.HashMap<>();
        final List<String> unlinked = new ArrayList<>();

        /** The first code redeems to {@link #TOKEN}; each later one to a token of its own. */
        @Override
        public Code requestCode(String deviceName, String appVersion, String publicKeyBase64) {
            calls.add("code");
            this.deviceName = deviceName;
            this.publicKey = publicKeyBase64;
            codes++;
            String deviceCode = "device-code-" + codes;
            tokens.put(deviceCode, codes == 1 ? TOKEN : TOKEN + "-" + codes);
            return new Code(deviceCode, codes == 1 ? "BCDFGHJK" : "BCDFGHJ" + "LMNPQRSTVWXZ".charAt(codes % 12),
                    Instant.parse("2026-10-17T00:10:00Z"), 3);
        }

        @Override
        public Poll poll(String deviceCode) {
            calls.add("poll");
            if (expired) {
                return new Expired();
            }
            return approved ? new Linked(tokens.get(deviceCode), UUID.randomUUID(),
                    Instant.parse("2027-04-15T00:00:00Z")) : new Pending();
        }

        @Override
        public PublicIp publicIp(String installToken) {
            calls.add("ip");
            ipCalls++;
            lastBearer = installToken;
            if (unreachable) {
                throw new Unreachable();
            }
            return ip;
        }

        @Override
        public java.net.URI approvalPage(String userCode) {
            return java.net.URI.create("http://127.0.0.1:8090/link?code=" + userCode);
        }

        @Override
        public void unlink(String installToken) {
            calls.add("unlink");
            unlinked.add(installToken);
            if (unreachable) {
                throw new Unreachable();
            }
        }

        // The review relay is VendorReviewPullServiceTest's.
        @Override
        public List<Store> stores(String installToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<String> accountEmail(String installToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ackReviews(String installToken, UUID storeId, long cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot submitDraft(String installToken, UUID storeId, UUID reviewId, String body,
                                        DraftMeta meta, Integer baseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot approve(String installToken, UUID taskId, int version, String fingerprint,
                                    String commandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot cancel(String installToken, UUID taskId, String commandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskChanges taskChanges(String installToken, UUID storeId, long cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot task(String installToken, UUID taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PolicyCheck policyCheck(String installToken, UUID storeId, String knowledgeFp, String policyFp,
                                       String voiceFp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot pcRecheck(String installToken, UUID taskId, int draftVersion, boolean passed,
                                      java.util.List<String> codes, BasisNote note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSnapshot checkApproved(String installToken, UUID taskId, int approvedVersion, boolean hold,
                                          BasisNote note, String commandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
