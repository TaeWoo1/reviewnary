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
    @Autowired VendorReplyTaskRepository taskCopies;
    @Autowired com.sellerops.attention.reply.ReviewReplyDraftService drafts;
    @Autowired com.sellerops.attention.reply.ReviewReplyApprovalService approvals;
    @Autowired com.sellerops.attention.reply.ReviewReplyOutcomeService outcomes;
    @Autowired com.sellerops.attention.reply.ReviewReplyOutcomeRepository outcomeRows;
    @Autowired com.sellerops.attention.triage.ReviewTriageService triage;
    @Autowired com.sellerops.attention.triage.ReviewTriageRepository triageRows;

    @Test
    void theRealServersFeedAndTheWholeReplyLoopReachThisPc() {
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
        VendorReplyTaskService replyTasks = new VendorReplyTaskService(link, bindings, refs, taskCopies, reviews, drafts,
                approvals, outcomes, triageRows, Clock.systemUTC());
        replyTasks.setAfter(r -> {});
        VendorReviewPullService pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion,
                followUp, syncJobs, replyTasks, Clock.systemUTC());

        int expected = Integer.parseInt(System.getenv("VENDOR_E2E_REVIEWS"));
        List<UUID> shown = pull.overview(org).stores().stream().map(VendorReviewPullService.StoreView::storeId).toList();
        StoreResult first = pull.start(org, shown).stores().get(0);
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

        // The loop, end to end: the seller sends a draft to the phone, approves it there, the delegate claims it,
        // looks once more and reports it posted; the PC records that as a reported, unverified post.
        Review answer = stored.stream().filter(r -> r.getReplyState() != com.sellerops.review.ReviewReplyState.ANSWERED)
                .findFirst().orElseThrow();
        UUID seller = UUID.randomUUID();
        triage.decide(org, answer.getId(), "RESPONSE_NEEDED", "wire-needs", seller);
        String text = "소중한 리뷰 감사합니다. 다음에도 만족하실 수 있게 준비하겠습니다.";
        drafts.save(org, answer.getId(), "SELLER:" + seller, text, 0);
        assertThat(replyTasks.upload(org, answer.getId()).state()).isEqualTo("AWAITING_APPROVAL");
        VendorReplyTask copy = taskCopies.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(org, answer.getId())
                .orElseThrow();
        String fingerprint = com.sellerops.attention.reply.ReviewReplyFingerprint.of(text);
        assertThat(copy.getDraftFingerprint()).as("both builds fingerprint the reply alike").isEqualTo(fingerprint);

        String task = copy.getVendorTaskId().toString();
        String sellerSession = System.getenv("VENDOR_E2E_SELLER_TOKEN");
        String delegate = System.getenv("VENDOR_E2E_DELEGATE_TOKEN");
        assertThat(call("POST", "/v1/tasks/" + task + "/approve", sellerSession, "{\"version\":" + copy.getDraftVersion()
                + ",\"fingerprint\":\"" + fingerprint + "\",\"commandId\":\"wire-approve\"}")).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode listed = json(get("/v1/delegate/tasks?state=APPROVED", delegate));
        int stateVersion = listed.get(0).path("stateVersion").asInt();
        assertThat(call("POST", "/v1/delegate/tasks/" + task + "/claim", delegate,
                "{\"stateVersion\":" + stateVersion + "}")).isEqualTo(200);
        assertThat(json(get("/v1/delegate/tasks/" + task + "/preflight", delegate)).path("clearToPost").asBoolean())
                .isTrue();
        assertThat(call("POST", "/v1/delegate/tasks/" + task + "/report", delegate, "{\"outcome\":\"POSTED\","
                + "\"postedFingerprint\":\"" + fingerprint + "\",\"checkedAt\":\"" + Instant.now()
                + "\",\"method\":\"SELLER_CENTER_SCREEN\",\"commandId\":\"wire-report\"}")).isEqualTo(200);

        assertThat(pull.pullFor(org).stores().get(0).taskChanges()).isGreaterThanOrEqualTo(3);
        assertThat(replyTasks.holds(org, answer.getId())).as("reported posted: still held").isTrue();
        assertThat(outcomeRows.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(org, answer.getId())).singleElement()
                .satisfies(recorded -> {
                    assertThat(recorded.getOperatorOutcome())
                            .isEqualTo(com.sellerops.attention.reply.OperatorOutcome.OPERATOR_REPORTED_SUBMITTED);
                    assertThat(recorded.getVerification().name()).isEqualTo("UNVERIFIED");
                    assertThat(recorded.getRecordedFingerprint()).isEqualTo(fingerprint);
                });

        // 「답글 등록 요청」 on another review: approved on the server by this PC, listed for the delegate,
        // then taken back with 「요청 취소」.
        Review handed = stored.stream().filter(r -> !r.getId().equals(answer.getId())
                && r.getReplyState() != com.sellerops.review.ReviewReplyState.ANSWERED).findFirst().orElseThrow();
        triage.decide(org, handed.getId(), "RESPONSE_NEEDED", "wire-needs-2", seller);
        drafts.save(org, handed.getId(), "SELLER:" + seller, "확인 감사합니다. 더 꼼꼼히 챙기겠습니다.", 0);
        assertThat(replyTasks.handOver(org, handed.getId(), drafts.currentVersion(handed.getId())).state()).isEqualTo("APPROVED");
        String handedTask = taskCopies.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(org, handed.getId())
                .orElseThrow().getVendorTaskId().toString();
        assertThat(json(get("/v1/delegate/tasks?state=APPROVED", delegate)).findValuesAsText("id"))
                .contains(handedTask);
        assertThat(replyTasks.cancel(org, handed.getId()).state()).isEqualTo("CANCELLED");
        assertThat(json(get("/v1/delegate/tasks?state=APPROVED", delegate)).findValuesAsText("id"))
                .doesNotContain(handedTask);

        // The seller's own 'no reply' on another review reaches the server once.
        Review quiet = stored.stream().filter(r -> !r.getId().equals(answer.getId())
                && !r.getId().equals(handed.getId())).findFirst().orElseThrow();
        triage.decide(org, quiet.getId(), "NO_ACTION", "wire-quiet", seller);
        assertThat(pull.pullFor(org).stores().get(0).noReplyReported()).isEqualTo(1);
        assertThat(pull.pullFor(org).stores().get(0).noReplyReported()).isZero();
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();

    private static int call(String method, String path, String bearer, String body) {
        try {
            return HTTP.send(java.net.http.HttpRequest.newBuilder(URI.create(System.getenv("VENDOR_E2E_URL") + path))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                    .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    private static String get(String path, String bearer) {
        try {
            return HTTP.send(java.net.http.HttpRequest.newBuilder(URI.create(System.getenv("VENDOR_E2E_URL") + path))
                    .header("Authorization", "Bearer " + bearer).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
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
        public java.util.Optional<String> accountEmail(String installToken) {
            return real.accountEmail(installToken);
        }

        @Override
        public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
            return real.reviewFeed(installToken, storeId, cursor, limit);
        }

        @Override
        public void ackReviews(String installToken, UUID storeId, long cursor) {
            real.ackReviews(installToken, storeId, cursor);
        }

        @Override
        public TaskSnapshot submitDraft(String installToken, UUID storeId, UUID reviewId, String body,
                                        DraftMeta meta, Integer baseVersion) {
            return real.submitDraft(installToken, storeId, reviewId, body, meta, baseVersion);
        }

        @Override
        public TaskSnapshot approve(String installToken, UUID taskId, int version, String fingerprint,
                                    String commandId) {
            return real.approve(installToken, taskId, version, fingerprint, commandId);
        }

        @Override
        public TaskSnapshot cancel(String installToken, UUID taskId, String commandId) {
            return real.cancel(installToken, taskId, commandId);
        }

        @Override
        public TaskChanges taskChanges(String installToken, UUID storeId, long cursor, int limit) {
            return real.taskChanges(installToken, storeId, cursor, limit);
        }

        @Override
        public TaskSnapshot task(String installToken, UUID taskId) {
            return real.task(installToken, taskId);
        }

        @Override
        public PolicyCheck policyCheck(String installToken, UUID storeId, String knowledgeFp, String policyFp,
                                       String voiceFp) {
            return real.policyCheck(installToken, storeId, knowledgeFp, policyFp, voiceFp);
        }

        @Override
        public TaskSnapshot pcRecheck(String installToken, UUID taskId, int draftVersion, boolean passed,
                                      java.util.List<String> codes, BasisNote note) {
            return real.pcRecheck(installToken, taskId, draftVersion, passed, codes, note);
        }

        @Override
        public TaskSnapshot checkApproved(String installToken, UUID taskId, int approvedVersion, boolean hold,
                                          BasisNote note, String commandId) {
            return real.checkApproved(installToken, taskId, approvedVersion, hold, note, commandId);
        }

        @Override
        public void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId) {
            real.markNoReplyNeeded(installToken, storeId, reviewId, commandId);
        }
    }
}
