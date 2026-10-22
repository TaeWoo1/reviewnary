package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import com.sellerops.vendorlink.VendorReviewPullService.Failure;
import com.sellerops.vendorlink.VendorReviewPullService.Overview;
import com.sellerops.vendorlink.VendorReviewPullService.PullResult;
import com.sellerops.vendorlink.VendorReviewPullService.Standing;
import com.sellerops.vendorlink.VendorReviewPullService.State;
import com.sellerops.vendorlink.VendorReviewPullService.StoreResult;
import com.sellerops.vendorlink.VendorReviewPullService.StoreView;
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
    private static final String TOKEN = FakeVendorServer.TOKEN;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    @Autowired VendorInstallLinkRepository links;
    @Autowired VendorStoreBindingRepository bindings;
    @Autowired VendorReviewRefRepository refs;
    @Autowired VendorReplyTaskRepository taskCopies;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired IngestionService ingestion;
    @Autowired IngestFollowUp followUp;
    @Autowired SyncJobRepository syncJobs;

    private FakeVendorServer vendor;
    private VendorLinkService link;
    private VendorReviewPullService pull;
    private UUID org;
    private UUID store;

    @BeforeEach
    void setUp() {
        taskCopies.deleteAll();
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
        vendor = new FakeVendorServer(store);
        link = new VendorLinkService(links, new LocalSecretSealer(new VaultKeyRing(KEY, "test-key-1", "")), vendor,
                true, CLOCK);
        link.start();
        link.poll();
        pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs,
                CLOCK);
    }

    @Test
    void startingBindsTheShownStoreAndStoresEachReviewOnceWithItsRun() {
        vendor.event("NEW", review("v1", "9000000001", "만족합니다", 5, false, "노출"));
        vendor.event("NEW", review("v2", "9000000002", "", 3, true, "노출"));

        PullResult first = pull.start(org, List.of(store));
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
        pull.start(org, List.of(store));
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
    void noPullBindsAndAnotherOrganisationNeverReceives() {
        vendor.event("NEW", review("v1", "9000000021", "합성 리뷰", 5, false, "노출"));

        PullResult unattended = pull.pullBound();
        assertThat(unattended.stores()).singleElement().satisfies(s -> assertThat(s.bound()).isFalse());
        assertThat(pull.pullFor(org).stores()).as("nor does 「지금 받기」").singleElement()
                .satisfies(s -> assertThat(s.bound()).isFalse());
        assertThat(bindings.findByVendorStoreId(store)).isEmpty();
        assertThat(vendor.cursorsAsked).isEmpty();

        pull.start(org, List.of(store));
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

        StoreResult result = pull.start(org, List.of(store)).stores().get(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cursor()).isEqualTo(1);
        assertThat(result.more()).isFalse();
        assertThat(vendor.acks).containsExactly(1L);
        assertThat(reviews.findByOrgIdAndChannelIdAndExternalId(org, naverId(), "9000000032")).isEmpty();
        assertThat(reviews.findByOrgIdAndChannelIdAndExternalId(org, naverId(), "9000000033")).isEmpty();
        assertThat(pull.overview(org).stores()).singleElement().satisfies(v -> {
            assertThat(v.failure()).as("this store, said apart from the others").isEqualTo(Failure.NOT_STORED);
            assertThat(v.failedAt()).isNotNull();
        });

        pull.pullFor(org);
        assertThat(vendor.cursorsAsked).as("the next pull starts at the event that failed").containsExactly(0L, 1L);
    }

    @Test
    void aLostAckIsRepeatedByTheNextPullEvenWithNothingNew() {
        vendor.event("NEW", review("v1", "9000000041", "첫 리뷰", 5, false, "노출"));
        vendor.dropAcks = 1;
        assertThat(pull.start(org, List.of(store)).stores().get(0).cursor()).isEqualTo(1);
        assertThat(vendor.acks).as("the first ack was lost").isEmpty();

        assertThat(pull.pullBound().stores().get(0).received()).as("nothing new on the server").isZero();
        assertThat(vendor.acks).as("owed, so asked again").containsExactly(1L);

        pull.pullBound();
        assertThat(vendor.acks).as("confirmed, so not asked again").containsExactly(1L);
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

    @Test
    void onlyTheStoresShownAreStartedAndALaterStoreWaitsToBeShown() {
        vendor.event("NEW", review("v1", "9000000051", "합성 리뷰", 5, false, "노출"));
        UUID notNamed = UUID.randomUUID();
        vendor.laterStores.add(new VendorServerClient.Store(notNamed, "NAVER", "이름이 오지 않은 스토어", null));
        UUID later = UUID.randomUUID();
        pull.start(org, List.of(store, UUID.randomUUID()));
        assertThat(bindings.findAll()).as("only what the press named, and only what the server has")
                .extracting(VendorStoreBinding::getVendorStoreId).containsExactly(store);
        vendor.laterStores.clear();

        vendor.laterStores.add(new VendorServerClient.Store(later, "NAVER", "나중에 생긴 스토어", null));
        pull.pullFor(org);
        pull.pullBound();
        assertThat(bindings.findByVendorStoreId(later)).as("added on the server later: shown, not taken").isEmpty();
        assertThat(pull.overview(org).stores()).extracting(StoreView::storeId, StoreView::standing)
                .containsExactly(tuple(store, Standing.RECEIVING), tuple(later, Standing.NOT_RECEIVING));

        pull.start(org, List.of(later));
        assertThat(bindings.findByVendorStoreId(later)).get().extracting(VendorStoreBinding::getOrgId).isEqualTo(org);
    }

    @Test
    void theOverviewSaysWhoseAccountAndKeepsTheLastCheckApartFromTheLastUpload() {
        Instant uploaded = Instant.parse("2026-10-19T09:00:00Z");
        vendor.lastUploadAt = uploaded;
        vendor.event("NEW", review("v1", "9000000061", "합성 리뷰", 5, false, "노출"));
        Overview before = pull.overview(org);
        assertThat(before.accountEmail()).isEqualTo("seller@example.com");
        assertThat(before.stores()).singleElement().satisfies(v -> {
            assertThat(v.standing()).isEqualTo(Standing.NOT_RECEIVING);
            assertThat(v.lastUploadAt()).isEqualTo(uploaded);
            assertThat(v.lastCheckedAt()).isNull();
        });

        pull.start(org, List.of(store));
        StoreView after = pull.overview(org).stores().get(0);
        assertThat(after.lastCheckedAt()).as("asked just now").isEqualTo(CLOCK.instant());
        assertThat(after.lastUploadAt()).as("but the newest file is still the delegate's last").isEqualTo(uploaded);
        assertThat(after.received()).isEqualTo(1);

        vendor.refuse = true;
        Overview refused = pull.overview(org);
        assertThat(refused.state()).isEqualTo(State.NOT_AUTHORIZED);
        assertThat(refused.stores()).as("what this PC had stays on screen").singleElement()
                .satisfies(v -> assertThat(v.lastUploadAt()).isEqualTo(uploaded));
    }

    @Test
    void aStoppedStoreIsNotAskedUntilItIsStartedAgain() {
        vendor.event("NEW", review("v1", "9000000071", "합성 리뷰", 5, false, "노출"));
        pull.start(org, List.of(store));
        UUID other = organization("다른 판매자");
        assertThat(pull.setPaused(other, store, true)).as("not theirs to stop").isFalse();
        assertThat(pull.setPaused(org, store, true)).isTrue();

        vendor.event("NEW", review("v2", "9000000072", "다음 리뷰", 5, false, "노출"));
        int asked = vendor.cursorsAsked.size();
        assertThat(pull.pullBound().stores()).singleElement().satisfies(s -> assertThat(s.paused()).isTrue());
        assertThat(pull.pullFor(org).stores().get(0).inserted()).isZero();
        assertThat(vendor.cursorsAsked).as("neither pull asked the server").hasSize(asked);
        assertThat(pull.overview(org).stores().get(0).standing()).isEqualTo(Standing.PAUSED);

        pull.setPaused(org, store, false);
        assertThat(pull.pullBound().stores().get(0).inserted()).isEqualTo(1);
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
}
