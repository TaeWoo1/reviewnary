package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.collect.runtime.CollectionMethod;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.ingest.IngestFollowUp;
import com.sellerops.ingest.IngestionService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.vendorlink.VendorReviewPullService.PullResult;
import com.sellerops.vendorlink.VendorReviewPullService.State;
import com.sellerops.vendorlink.VendorReviewPullService.StoreResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The vendor server's review feed lands in the seller's own review lane, once, in order, and only for the
 * organisation the seller bound it to</b> — contract {@code docs/vendor_server_contract_v1.md} §3.2.
 *
 * <p>A {@code @SpringBootTest} with no ambient transaction, like {@code StampAcquisitionHandoffTest}: the pull is
 * deliberately not one transaction, and the ingest and stamp it calls are the container's proxied beans. The vendor
 * server is a fake; {@code JdkVendorServerClientTest} holds the wire shapes. Every review is synthetic.
 *
 * <p>The database is the subclass's: H2 from the entities ({@link VendorReviewPullServiceTest}) or a Flyway-migrated
 * PostgreSQL ({@link VendorReviewPullPostgresProofIT}).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class VendorReviewPullScenarios {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32});
    private static final String TOKEN = "rvi_synthetic-install-token-for-tests";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    @Autowired VendorInstallLinkRepository links;
    @Autowired VendorStoreBindingRepository bindings;
    @Autowired VendorReviewRefRepository refs;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired IngestionService ingestion;
    @Autowired IngestFollowUp followUp;
    @Autowired SyncJobRepository syncJobs;

    private FakeVendor vendor;
    private VendorLinkService link;
    private VendorReviewPullService pull;
    private UUID org;
    private UUID store;

    @BeforeEach
    void setUp() {
        refs.deleteAll();
        bindings.deleteAll();
        links.deleteAll();
        channels.findByCode("NAVER").orElseGet(() -> {
            Channel naver = new Channel();
            naver.setCode("NAVER");
            naver.setNameKo("네이버");
            naver.setStatus(ChannelStatus.AVAILABLE);
            naver.setSupportsReview(true);
            naver.setSortOrder(0);
            return channels.save(naver);
        });
        org = organization("합성 판매자");
        store = UUID.randomUUID();
        vendor = new FakeVendor(store);
        link = new VendorLinkService(links, new LocalSecretSealer(new VaultKeyRing(KEY, "test-key-1", "")), vendor,
                true, CLOCK);
        link.start();
        link.poll();
        pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs,
                CLOCK);
    }

    @Test
    void aSellersPullBindsTheStoreAndStoresEachReviewOnceWithItsRun() {
        vendor.event("NEW", review("v1", "9000000001", "만족합니다", 5, false, "노출"));
        vendor.event("NEW", review("v2", "9000000002", "", 3, true, "노출"));

        PullResult first = pull.pullFor(org);
        assertThat(first.state()).isEqualTo(State.PULLED);
        assertThat(first.stores()).singleElement().satisfies(s -> {
            assertThat(s.bound()).isTrue();
            assertThat(s.inserted()).isEqualTo(2);
            assertThat(s.cursor()).isEqualTo(2);
        });
        assertThat(bindings.findByVendorStoreId(store)).get().extracting(VendorStoreBinding::getOrgId).isEqualTo(org);
        assertThat(vendor.acks).containsExactly(2L);
        assertThat(vendor.bearers).containsOnly(TOKEN);

        Review answered = held("9000000002");
        assertThat(answered.getReplyState()).isEqualTo(ReviewReplyState.ANSWERED);
        assertThat(held("9000000001").getReplyState()).isEqualTo(ReviewReplyState.PENDING);
        SyncJob run = syncJobs.findById(answered.getAcquisitionSyncJobId()).orElseThrow();
        assertThat(run.getMethod()).isEqualTo(CollectionMethod.VENDOR_RELAY.name());
        assertThat(run.getDataType()).as("a relayed file's age is not freshness").isNull();
        assertThat(refs.findByReviewId(answered.getId())).get().satisfies(r -> {
            assertThat(r.getVendorReviewId()).isEqualTo(vendor.ids.get("v2"));
            assertThat(r.getBodyFingerprint()).isEqualTo(fingerprintOf("v2"));
        });

        StoreResult again = pull.pullFor(org).stores().get(0);
        assertThat(again.received()).as("the cursor moved past what was stored").isZero();
        assertThat(vendor.cursorsAsked).containsExactly(0L, 2L);
        assertThat(reviews.findAll()).filteredOn(r -> org.equals(r.getOrgId())).hasSize(2);
    }

    @Test
    void aChangedReviewIsUpdatedAndAReplyIsNeverTakenBack() {
        vendor.event("NEW", review("v1", "9000000011", "처음 쓴 리뷰", 2, false, "노출"));
        pull.pullFor(org);
        UUID id = held("9000000011").getId();

        vendor.event("CHANGED", review("v1", "9000000011", "고쳐 쓴 리뷰", 4, false, "비노출"));
        vendor.event("REPLIED", review("v1", "9000000011", "고쳐 쓴 리뷰", 4, true, "비노출"));
        vendor.event("CHANGED", review("v1", "9000000011", "고쳐 쓴 리뷰", 4, false, "비노출"));
        assertThat(pull.pullFor(org).stores().get(0).updated()).isEqualTo(2);

        Review now = held("9000000011");
        assertThat(now.getId()).as("the same review, not a second one").isEqualTo(id);
        assertThat(now.getBody()).isEqualTo("고쳐 쓴 리뷰");
        assertThat(now.getRating()).isEqualTo(4);
        assertThat(now.isNegative()).isFalse();
        assertThat(now.getReplyState()).as("Y then N: the lane never un-answers").isEqualTo(ReviewReplyState.ANSWERED);
        assertThat(refs.findByReviewId(id)).get().extracting(VendorReviewRef::getDisplayState).isEqualTo("비노출");
    }

    @Test
    void theScheduledPullNeverBindsAndAnotherOrganisationNeverReceives() {
        vendor.event("NEW", review("v1", "9000000021", "합성 리뷰", 5, false, "노출"));

        PullResult unattended = pull.pullBound();
        assertThat(unattended.stores()).singleElement().satisfies(s -> assertThat(s.bound()).isFalse());
        assertThat(bindings.findByVendorStoreId(store)).isEmpty();
        assertThat(vendor.cursorsAsked).isEmpty();

        pull.pullFor(org);
        UUID other = organization("다른 판매자");
        StoreResult refused = pull.pullFor(other).stores().get(0);
        assertThat(refused.otherOrganisation()).isTrue();
        assertThat(reviews.findAll()).noneMatch(r -> other.equals(r.getOrgId()));

        vendor.event("NEW", review("v2", "9000000022", "다음 리뷰", 5, false, "노출"));
        assertThat(pull.pullBound().stores().get(0).inserted()).as("bound: the schedule follows").isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeStoredHoldsTheCursorBeforeIt() {
        vendor.event("NEW", review("v1", "9000000031", "첫 리뷰", 5, false, "노출"));
        // The server's same review arriving under another review number cannot be one local review.
        vendor.event("NEW", review("v1", "9000000032", "둘째 리뷰", 5, false, "노출"));
        vendor.event("NEW", review("v3", "9000000033", "셋째 리뷰", 5, false, "노출"));

        StoreResult result = pull.pullFor(org).stores().get(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cursor()).isEqualTo(1);
        assertThat(result.more()).isFalse();
        assertThat(vendor.acks).containsExactly(1L);
        assertThat(reviews.findByOrgIdAndChannelIdAndExternalId(org, naverId(), "9000000032")).isEmpty();
        assertThat(reviews.findByOrgIdAndChannelIdAndExternalId(org, naverId(), "9000000033")).isEmpty();

        pull.pullFor(org);
        assertThat(vendor.cursorsAsked).as("the next pull starts at the event that failed").containsExactly(0L, 1L);
    }

    @Test
    void anUnlinkedPcOrARefusedTokenPullsNothing() {
        link.unlink();
        assertThat(pull.pullFor(org).state()).isEqualTo(State.NOT_LINKED);

        link.start();
        link.poll();
        vendor.refuse = true;
        assertThat(pull.pullFor(org).state()).isEqualTo(State.NOT_AUTHORIZED);
        assertThat(bindings.findByVendorStoreId(store)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private UUID organization(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private UUID naverId() {
        return channels.findByCode("NAVER").orElseThrow().getId();
    }

    private Review held(String reviewNo) {
        return reviews.findByOrgIdAndChannelIdAndExternalId(org, naverId(), reviewNo).orElseThrow();
    }

    private VendorServerClient.FeedReview review(String key, String reviewNo, String body, int rating,
                                                 boolean replied, String display) {
        UUID id = vendor.ids.computeIfAbsent(key, k -> UUID.randomUUID());
        return new VendorServerClient.FeedReview(id, reviewNo, "SKU-합성-1", "합성 상품", rating, body,
                fingerprintOf(key), null, null, Instant.parse("2026-10-01T01:00:00Z"), display, replied,
                replied ? Instant.parse("2026-10-02T01:00:00Z") : null);
    }

    private static String fingerprintOf(String key) {
        return String.format("%64s", Integer.toHexString(key.hashCode())).replace(' ', '0');
    }

    /** The vendor server, holding one NAVER store's feed. */
    private static final class FakeVendor implements VendorServerClient {
        final UUID store;
        final java.util.Map<String, UUID> ids = new java.util.HashMap<>();
        final List<FeedEvent> feed = new ArrayList<>();
        final List<Long> cursorsAsked = new ArrayList<>();
        final List<Long> acks = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
        boolean refuse;

        FakeVendor(UUID store) {
            this.store = store;
        }

        void event(String kind, FeedReview review) {
            feed.add(new FeedEvent(feed.size() + 1, kind, review));
        }

        @Override
        public Code requestCode(String deviceName, String appVersion, String publicKeyBase64) {
            return new Code("device-code", "BCDFGHJK", Instant.parse("2026-10-21T00:10:00Z"), 3);
        }

        @Override
        public Poll poll(String deviceCode) {
            return new Linked(TOKEN, UUID.randomUUID(), Instant.parse("2027-04-15T00:00:00Z"));
        }

        @Override
        public PublicIp publicIp(String installToken) {
            return new NoIpv4();
        }

        @Override
        public java.net.URI approvalPage(String userCode) {
            return java.net.URI.create("http://127.0.0.1:8090/link?code=" + userCode);
        }

        @Override
        public void unlink(String installToken) {
        }

        @Override
        public List<Store> stores(String installToken) {
            bearers.add(installToken);
            if (refuse) {
                throw new Refused();
            }
            return List.of(new Store(store, "NAVER", "합성 스토어"));
        }

        @Override
        public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
            bearers.add(installToken);
            cursorsAsked.add(cursor);
            List<FeedEvent> after = feed.stream().filter(e -> e.seq() > cursor).toList();
            List<FeedEvent> page = after.subList(0, Math.min(limit, after.size()));
            long last = page.isEmpty() ? cursor : page.get(page.size() - 1).seq();
            return new FeedPage(page, last, after.size() > page.size());
        }

        @Override
        public void ackReviews(String installToken, UUID storeId, long cursor) {
            bearers.add(installToken);
            acks.add(cursor);
        }
    }
}
