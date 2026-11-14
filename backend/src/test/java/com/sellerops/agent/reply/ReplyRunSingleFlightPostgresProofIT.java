package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import com.sellerops.auth.runtoken.ReplyRunTokenService;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Disposable-Postgres proof that a review's reply-agent run starts <b>once</b> when presses arrive together (2026-11-09,
 * {@code docs/FINDINGS.md} §B126). The H2 suite proves the sequential refusal; only a real database with committing
 * threads can show the look-for-an-open-run and the mint are one step. The runtime here is a counting fake — no model, no
 * network, no channel.
 *
 * <p>{@link #pressesThatAllPassTheLookTogetherStillStartOneRun} forces the overlap §B126 names: every racer is held at a
 * barrier just before the mint, so all of them have already looked before any of them inserts. Without the review-row
 * lock that test starts one run per racer.
 *
 * <p><b>Opt-in only</b>, gated by {@code SELLEROPS_PG_PROOF=1}, pointed with {@code SELLEROPS_PG_URL} at a throwaway
 * database; never a real one. Not {@code @Transactional}: each start commits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class ReplyRunSingleFlightPostgresProofIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }

    @Autowired ReplyRunTokenService tokens;
    @Autowired ReplyAgentRunRepository runs;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;

    @Test
    void pressesThatAllPassTheLookTogetherStillStartOneRun() throws Exception {
        UUID org = seedOrg();
        UUID review = seedReview(org);
        int racers = 8;
        CyclicBarrier allLooked = new CyclicBarrier(racers);
        // The run is still drafting while the others take the lock in turn; a run that has finished may be started anew.
        CountingRuntime runtime = new CountingRuntime(1500);
        ReplyRunStarter starter = new ReplyRunStarter(new HeldAtTheMint(runs, tokens, allLooked), runtime,
                enabledFor(org), runs);

        List<Optional<ReplyRunStarter.Started>> results = race(racers, () -> starter.startReviewRun(org, review,
                "SELLER", null));

        assertThat(runtime.calls(review)).as("runtime calls for the review").isEqualTo(1);
        assertThat(results.stream().filter(Optional::isPresent).count()).as("presses that started a run").isEqualTo(1);
        assertThat(runs.findAll().stream().filter(r -> r.getSubjectId().equals(review)).count())
                .as("run rows for the review").isEqualTo(1);
    }

    @Test
    void pressesArrivingTogetherStartOneRunWhileItDrafts() throws Exception {
        UUID org = seedOrg();
        UUID review = seedReview(org);
        CountingRuntime runtime = new CountingRuntime(400);
        ReplyRunStarter starter = new ReplyRunStarter(tokens, runtime, enabledFor(org), runs);

        race(8, () -> starter.startReviewRun(org, review, "SELLER", null));

        assertThat(runtime.calls(review)).isEqualTo(1);
        assertThat(starter.running(org, review)).as("closed once the runtime returned").isFalse();
    }

    @Test
    void otherReviewsAndOtherOrgsAreNotHeldBack() throws Exception {
        UUID orgA = seedOrg();
        UUID orgB = seedOrg();
        UUID a1 = seedReview(orgA);
        UUID a2 = seedReview(orgA);
        UUID b1 = seedReview(orgB);
        CountingRuntime runtime = new CountingRuntime(400);
        ReplyAgentService agent = mock(ReplyAgentService.class);
        when(agent.isEnabledFor(any())).thenReturn(true);
        ReplyRunStarter starter = new ReplyRunStarter(tokens, runtime, agent, runs);

        List<UUID[]> plan = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            plan.add(new UUID[] {orgA, a1});
            plan.add(new UUID[] {orgA, a2});
            plan.add(new UUID[] {orgB, b1});
        }
        AtomicInteger next = new AtomicInteger();
        race(plan.size(), () -> {
            UUID[] pick = plan.get(next.getAndIncrement());
            return starter.startReviewRun(pick[0], pick[1], "SELLER", null);
        });

        assertThat(runtime.calls(a1)).as("review a1").isEqualTo(1);
        assertThat(runtime.calls(a2)).as("review a2, same org").isEqualTo(1);
        assertThat(runtime.calls(b1)).as("review b1, other org").isEqualTo(1);
    }

    // ---- the race ----

    private static <T> List<T> race(int racers, java.util.concurrent.Callable<T> press) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return press.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }

    /** A configured runtime that counts its calls per review and takes {@code millis} to "draft". */
    private final class CountingRuntime extends AgentRuntimeClient {
        private final long millis;
        private final Map<UUID, AtomicInteger> byReview = new ConcurrentHashMap<>();

        CountingRuntime(long millis) {
            super("http://127.0.0.1:1");
            this.millis = millis;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<RunReport> runReply(UUID runId, String token) {
            UUID review = runs.findById(runId).orElseThrow().getSubjectId();
            byReview.computeIfAbsent(review, k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(new RunReport("HANDOFF", "S", "TEST_FAKE", null));
        }

        int calls(UUID review) {
            AtomicInteger n = byReview.get(review);
            return n == null ? 0 : n.get();
        }
    }

    /**
     * The real (transactional) token service, with every caller held at a barrier just before it mints — so all racers
     * have looked for an open run before any of them can insert one.
     */
    private static final class HeldAtTheMint extends ReplyRunTokenService {
        private final ReplyRunTokenService real;
        private final CyclicBarrier barrier;

        HeldAtTheMint(ReplyAgentRunRepository runs, ReplyRunTokenService real, CyclicBarrier barrier) {
            super(runs);
            this.real = real;
            this.barrier = barrier;
        }

        private void hold() {
            try {
                barrier.await(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("racers did not all arrive", e);
            }
        }

        @Override
        public Optional<Minted> mintIfIdle(UUID orgId, UUID reviewId, String trigger, UUID startedBy) {
            hold();
            return real.mintIfIdle(orgId, reviewId, trigger, startedBy);
        }

        @Override
        public Minted mint(UUID orgId, String subjectKind, UUID subjectId, String trigger, UUID startedBy) {
            hold();
            return real.mint(orgId, subjectKind, subjectId, trigger, startedBy);
        }

        @Override
        public void finish(UUID runId, String outcome) {
            real.finish(runId, outcome);
        }
    }

    // ---- seeds ----

    private static ReplyAgentService enabledFor(UUID org) {
        ReplyAgentService agent = mock(ReplyAgentService.class);
        when(agent.isEnabledFor(org)).thenReturn(true);
        return agent;
    }

    private UUID seedOrg() {
        Organization o = new Organization();
        o.setName("rr-" + UUID.randomUUID().toString().substring(0, 8));
        return organizations.save(o).getId();
    }

    private UUID seedReview(UUID org) {
        UUID channel = channels.findByCode("RR-RACE").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("RR-RACE");
            c.setNameKo("RR-RACE");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSortOrder(0);
            return channels.save(c);
        }).getId();
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channel);
        r.setRating(1);
        r.setBody("합성-리뷰-본문: 동시 실행 증명용");
        r.setNegative(true);
        r.setReplyState(ReviewReplyState.PENDING);
        r.setReceivedAt(Instant.parse("2026-11-09T03:00:00Z"));
        return reviews.save(r).getId();
    }
}
