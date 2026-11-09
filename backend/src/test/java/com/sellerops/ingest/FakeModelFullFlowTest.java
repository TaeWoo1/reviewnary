package com.sellerops.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.quota.AgentLlmUsage;
import com.sellerops.agent.quota.AgentLlmUsageRepository;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.reply.AgentRuntimeClient;
import com.sellerops.agent.reply.ReplyRunService;
import com.sellerops.agent.reply.ReplyStartController;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.runtoken.ReplyAgentRun;
import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.judgement.ReviewJudgement;
import com.sellerops.review.judgement.ReviewJudgementRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The whole flow with a fake model</b> (owner, 2026-10-29: 「가짜 모델 전체 흐름 점검」): a sync lands reviews → every
 * one is judged and the judgement stored → the seller's rule and the judgement choose what is drafted ahead → the reply
 * agent's steps run → a draft waits for the seller's approval, a harm goes to a person, a review that needs nothing gets
 * no draft, and a critique the day's AI ceiling stopped is held (검토 대기) and resumed without writing again.
 *
 * <p>Everything is the production code in one Spring context — prompts, parsers, gates, quota, the judgement store —
 * except two things: the model is {@link com.sellerops.agent.llm.FakeAgentLlmTransport} (rule-based, no network), and
 * the agent-runtime process is a stand-in that calls the backend's run steps in the order
 * {@code agent-runtime/src/reply/replyGraph.ts} does (that order has its own tests there). No marketplace is reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:fake_model_full_flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sellerops.seed.enabled=false",
        "sellerops.seed.channel-catalogue=false",
        "sellerops.dev.fake-llm=true",
        "sellerops.reply-agent.enabled=true",
        "sellerops.reply-agent.enabled-org-ids=*",
        "sellerops.reply-agent.api-key=fake",
        "sellerops.agent.draft.enabled=true",
        "sellerops.agent.draft.enabled-org-ids=*",
        "sellerops.agent.draft.api-key=fake",
        "sellerops.agent.quota.daily-llm-calls-per-org=40",
        "sellerops.agent.quota.daily-seller-reserve-calls=0"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FakeModelFullFlowTest {

    /** What other requests do between a run's draft and its critique — the race a run's start cannot see. */
    static final java.util.concurrent.atomic.AtomicReference<Runnable> AFTER_DRAFT =
            new java.util.concurrent.atomic.AtomicReference<>(() -> { });

    /** The runtime, in-process: the graph's step order over the backend's run steps. */
    @TestConfiguration
    static class InProcessRuntime {
        @Bean
        @Primary
        AgentRuntimeClient inProcessRuntime(ObjectProvider<ReplyRunService> steps, ObjectProvider<ReplyAgentRunRepository> runs) {
            return new AgentRuntimeClient("in-process") {
                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public Optional<RunReport> runReply(UUID runId, String token) {
                    ReplyRunService s = steps.getObject();
                    UUID org = runs.getObject().findById(runId).orElseThrow().getOrgId();
                    ReplyRunService.Judged judged = s.classify(org, runId);
                    if (judged.handoff()) {
                        return end(s, org, runId, "HANDOFF", judged.reason(), judged.category(), null);
                    }
                    if (judged.category() == null) {
                        return end(s, org, runId, "FAILED", "JUDGE_FAILED", null, null);
                    }
                    if (judged.skip()) {
                        return end(s, org, runId, "SKIPPED", "NO_RESPONSE_NEEDED", judged.category(), null);
                    }
                    ReplyRunService.Drafted drafted = s.draft(org, runId, false);
                    AFTER_DRAFT.getAndSet(() -> { }).run();
                    if (!drafted.written()) {
                        return end(s, org, runId, drafted.reason() != null ? "HANDOFF" : "FAILED",
                                drafted.reason() != null ? drafted.reason() : "DRAFT_FAILED", judged.category(), null);
                    }
                    for (int revisions = 0; ; revisions++) {
                        ReplyRunService.Critiqued c = s.critique(org, runId);
                        if (c.pending()) {
                            return end(s, org, runId, "PENDING", "REVIEW_PENDING", judged.category(), null);
                        }
                        if (c.passed()) {
                            ReplyRunService.Saved saved = s.save(org, runId);
                            return saved.saved() ? end(s, org, runId, "SAVED", null, judged.category(), saved.version())
                                    : end(s, org, runId, "SKIPPED", saved.reason(), judged.category(), null);
                        }
                        if (revisions >= 1 || !s.draft(org, runId, true).written()) {
                            return end(s, org, runId, "HANDOFF", "STILL_FAILS", judged.category(), null);
                        }
                    }
                }

                private Optional<RunReport> end(ReplyRunService s, UUID org, UUID runId, String outcome, String reason,
                                                String category, Integer version) {
                    s.finish(org, runId, outcome, reason);
                    return Optional.of(new RunReport(outcome, category, reason, version));
                }
            };
        }
    }

    @Autowired AheadWorkListener listener;
    @Autowired OrganizationRepository organizations;
    @Autowired ChannelRepository channels;
    @Autowired SellerAccountRepository accounts;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewJudgementRepository judgements;
    @Autowired ReviewReplyDraftRepository drafts;
    @Autowired ReplyAgentRunRepository runs;
    @Autowired AgentLlmUsageRepository usage;
    @Autowired ReplyStartController agentController;

    private UUID org;
    private UUID naver;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("합성 판매자 " + UUID.randomUUID());
        org = organizations.save(o).getId();
        naver = channels.findByCode("NAVER").map(Channel::getId).orElseGet(() -> {
            Channel ch = new Channel();
            ch.setCode("NAVER");
            ch.setNameKo("네이버");
            ch.setStatus(ChannelStatus.AVAILABLE);
            ch.setSupportsReview(true);
            ch.setSortOrder(0);
            return channels.save(ch).getId();
        });
        SellerAccount acc = new SellerAccount();
        acc.setOrgId(org);
        acc.setChannelId(naver);
        acc.setConnectionStatus(ChannelStatus.CONNECTED);
        acc.setFileUpload(true);
        accounts.save(acc);
    }

    private Review review(int rating, String body) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(naver);
        r.setRating(rating);
        r.setBody(body);
        r.setNegative(rating <= 2);
        r.setReplyState(ReviewReplyState.PENDING);
        r.setReceivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return reviews.save(r);
    }

    private void sync(Review... landed) {
        listener.handle(new NewWorkIngestedEvent(org, NewWorkIngestedEvent.Kind.REVIEW,
                java.util.Arrays.stream(landed).map(Review::getId).toList()));
    }

    private Optional<ReplyAgentRun> lastRun(Review r) {
        return runs.findFirstByOrgIdAndSubjectIdOrderByCreatedAtDesc(org, r.getId());
    }

    @Test
    @DisplayName("sync → judged → drafted ahead or handed over or left alone, each by the judgement and the seller's rule")
    void aSyncFlowsToDraftsHandoffsAndNothing() {
        Review complaint = review(2, "합성 리뷰: 부품이 하나 빠져 있었어요");
        Review harm = review(5, "합성 리뷰: 디자인은 좋은데 모서리에 손을 베였어요");
        Review praise = review(5, "합성 리뷰: 디자인이 예뻐요");

        sync(complaint, harm, praise);

        // Every review is judged and the judgement stored — the five-star harm and the praise too.
        assertThat(judgements.findByOrgIdAndReviewId(org, complaint.getId())).get().satisfies(j -> {
            assertThat(j.getCategory()).isEqualTo("R");
            assertThat(j.getActions()).isEqualTo("CHECK_ORDER,PUBLIC_REPLY");
            assertThat(j.isNeedsResponse()).isTrue();
        });
        assertThat(judgements.findByOrgIdAndReviewId(org, harm.getId())).get()
                .extracting(ReviewJudgement::getHandoffReason).isEqualTo("CATEGORY_S");
        assertThat(judgements.findByOrgIdAndReviewId(org, praise.getId())).get()
                .satisfies(j -> assertThat(j.isNeedsResponse()).isFalse());

        // The complaint: drafted ahead by the agent, waiting for the seller's approval.
        assertThat(lastRun(complaint)).get().extracting(ReplyAgentRun::getOutcome).isEqualTo("SAVED");
        List<ReviewReplyDraft> written = drafts.findAll().stream()
                .filter(d -> d.getReviewId().equals(complaint.getId())).toList();
        assertThat(written).singleElement().satisfies(d -> {
            assertThat(d.getModelVersion()).startsWith("reply-agent/");
            assertThat(d.getBody()).contains("[가짜 모델]");
        });

        // The harm: a person's — on Home's handoff list, no draft.
        assertThat(drafts.findAll()).noneMatch(d -> d.getReviewId().equals(harm.getId()));
        assertThat(agentController.handoffs(new AuthPrincipal(UUID.randomUUID(), org, "seller@example.com")))
                .anySatisfy(h -> {
                    assertThat(h.reviewId()).isEqualTo(harm.getId());
                    assertThat(h.reason()).isEqualTo("CATEGORY_S");
                });

        // The praise: ★5 is 「필요할 때만」 by default and the judgement says nothing is owed — no draft, no run.
        assertThat(drafts.findAll()).noneMatch(d -> d.getReviewId().equals(praise.getId()));
        assertThat(lastRun(praise)).isEmpty();

        // The judge ran once per review; the agent's run reused it instead of judging again.
        long classifyCalls = usage.findAll().stream().filter(u -> u.getOrgId().equals(org))
                .filter(u -> "CLASSIFY".equals(u.getStep())).count();
        assertThat(classifyCalls).isEqualTo(3);
    }

    @Test
    @DisplayName("a critique the day's AI ceiling stops is held (검토 대기) — then checked, not rewritten, when budget returns")
    void theCeilingHoldsThenResumes() {
        Review complaint = review(1, "합성 리뷰: 하루 만에 떨어졌어요");
        // Fill the day so the judgement and a whole run still fit when the seller presses (a run starts only when it can
        // finish, 2026-10-29) — then, between the draft and its critique, other requests take the rest of the day.
        List<AgentLlmUsage> filler = new ArrayList<>();
        fill(filler, 30);
        AFTER_DRAFT.set(() -> fill(filler, 40 - (int) usage.findAll().stream().filter(u -> u.getOrgId().equals(org))
                .count()));

        // Drafting ahead keeps half the day for the seller, so a full day writes nothing ahead — the seller presses.
        sync(complaint);
        assertThat(judgements.findByOrgIdAndReviewId(org, complaint.getId())).isPresent();
        assertThat(lastRun(complaint)).as("no run ahead past half the day").isEmpty();

        assertThat(agentController.start(new AuthPrincipal(UUID.randomUUID(), org, "seller@example.com"),
                new ReplyStartController.StartRequest(complaint.getId())).outcome())
                .as("the seller's press").isEqualTo("PENDING");
        ReplyAgentRun held = lastRun(complaint).orElseThrow();
        assertThat(held.getWorkingDraft()).as("the draft is kept, unchecked").contains("[가짜 모델]");
        assertThat(drafts.findAll()).noneMatch(d -> d.getReviewId().equals(complaint.getId()));
        assertThat(agentController.handoffs(new AuthPrincipal(UUID.randomUUID(), org, "seller@example.com")))
                .anySatisfy(h -> assertThat(h.reason()).isEqualTo("REVIEW_PENDING"));

        AFTER_DRAFT.set(() -> { });
        usage.deleteAll(filler);
        long draftsBefore = usage.findAll().stream().filter(u -> u.getOrgId().equals(org))
                .filter(u -> "DRAFT".equals(u.getStep())).count();
        assertThat(agentController.start(new AuthPrincipal(UUID.randomUUID(), org, "seller@example.com"),
                new ReplyStartController.StartRequest(complaint.getId())).outcome()).isEqualTo("SAVED");
        long draftsAfter = usage.findAll().stream().filter(u -> u.getOrgId().equals(org))
                .filter(u -> "DRAFT".equals(u.getStep())).count();
        assertThat(draftsAfter).as("the held draft was checked, not written again").isEqualTo(draftsBefore);
        assertThat(drafts.findAll()).anyMatch(d -> d.getReviewId().equals(complaint.getId()));
    }

    private void fill(List<AgentLlmUsage> filler, int calls) {
        for (int i = 0; i < calls; i++) {
            AgentLlmUsage u = new AgentLlmUsage();
            u.setOrgId(org);
            u.setUsageDate(LocalDate.now(ZoneId.of("Asia/Seoul")));
            u.setKind(AgentUsageKind.PLAN);
            u.setActor(AgentUsageActor.RUN);
            filler.add(usage.save(u));
        }
    }
}
