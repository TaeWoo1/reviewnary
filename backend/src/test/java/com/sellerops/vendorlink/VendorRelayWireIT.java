package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ReviewBodyFingerprint;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.ingest.IngestFollowUp;
import com.sellerops.ingest.IngestionService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.vendorlink.VendorReviewPullService.StoreResult;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>The relay against a real vendor server</b> — the same pull, over {@link JdkVendorServerClient}, from a running
 * {@code vendor-server/} that a delegate has uploaded the contract fixture to. It shows the two builds agree on the
 * wire and on the review body fingerprint, which the hand-written stub bodies in {@code JdkVendorServerClientTest}
 * cannot.
 *
 * <p>Opt-in: {@code SELLEROPS_VENDOR_E2E=1} with {@code VENDOR_E2E_URL} (a loopback vendor server on a throwaway
 * database), {@code VENDOR_E2E_TOKEN} (an install token issued by it) and {@code VENDOR_E2E_REVIEWS} (how many reviews
 * the upload holds). The device grant is skipped — the token is handed in — so this proves the relay, not linking.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "SELLEROPS_VENDOR_E2E", matches = "1")
class VendorRelayWireIT {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:sellerops_vendor_relay_wire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32});

    @Autowired VendorInstallLinkRepository links;
    @Autowired VendorStoreBindingRepository bindings;
    @Autowired VendorReviewRefRepository refs;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired IngestionService ingestion;
    @Autowired IngestFollowUp followUp;
    @Autowired SyncJobRepository syncJobs;

    @Test
    void theRealServersFeedLandsOnceAndBothBuildsAgreeOnTheBodyFingerprint() {
        Channel naver = new Channel();
        naver.setCode("NAVER");
        naver.setNameKo("네이버");
        naver.setStatus(ChannelStatus.AVAILABLE);
        naver.setSupportsReview(true);
        naver.setSortOrder(0);
        UUID naverId = channels.save(naver).getId();
        Organization o = new Organization();
        o.setName("합성 판매자");
        UUID org = organizations.save(o).getId();

        VendorServerClient wire = new HandedToken(new JdkVendorServerClient(URI.create(System.getenv("VENDOR_E2E_URL"))),
                System.getenv("VENDOR_E2E_TOKEN"));
        VendorLinkService link = new VendorLinkService(links,
                new LocalSecretSealer(new VaultKeyRing(KEY, "test-key-1", "")), wire, true, Clock.systemUTC());
        link.start();
        link.poll();
        VendorReviewPullService pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion,
                followUp, syncJobs, Clock.systemUTC());

        int expected = Integer.parseInt(System.getenv("VENDOR_E2E_REVIEWS"));
        StoreResult first = pull.pullFor(org).stores().get(0);
        assertThat(first.inserted()).isEqualTo(expected);
        assertThat(first.failed()).isZero();
        List<Review> stored = reviews.findAll().stream().filter(r -> org.equals(r.getOrgId())
                && naverId.equals(r.getChannelId())).toList();
        assertThat(stored).hasSize(expected);
        assertThat(stored).allSatisfy(r -> assertThat(refs.findByReviewId(r.getId())).get()
                .extracting(VendorReviewRef::getBodyFingerprint)
                .as("the server's fingerprint is the PC's own fingerprint of the same text")
                .isEqualTo(ReviewBodyFingerprint.of(r.getBody())));

        StoreResult again = pull.pullFor(org).stores().get(0);
        assertThat(again.received()).isZero();
        assertThat(again.cursor()).isEqualTo(first.cursor());
    }

    /** The real client for every relay call; the grant answers with the token issued outside the test. */
    private record HandedToken(VendorServerClient real, String token) implements VendorServerClient {

        @Override
        public Code requestCode(String deviceName, String appVersion, String publicKeyBase64) {
            return new Code("handed", "HANDEDXX", Instant.now().plusSeconds(600), 3);
        }

        @Override
        public Poll poll(String deviceCode) {
            return new Linked(token, UUID.randomUUID(), Instant.now().plusSeconds(3600));
        }

        @Override
        public PublicIp publicIp(String installToken) {
            return real.publicIp(installToken);
        }

        @Override
        public URI approvalPage(String userCode) {
            return real.approvalPage(userCode);
        }

        @Override
        public void unlink(String installToken) {
        }

        @Override
        public List<Store> stores(String installToken) {
            return real.stores(installToken);
        }

        @Override
        public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
            return real.reviewFeed(installToken, storeId, cursor, limit);
        }

        @Override
        public void ackReviews(String installToken, UUID storeId, long cursor) {
            real.ackReviews(installToken, storeId, cursor);
        }
    }
}
