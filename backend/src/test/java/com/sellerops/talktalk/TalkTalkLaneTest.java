package com.sellerops.talktalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeSourceCurrencyRepository;
import com.sellerops.knowledge.currency.KnowledgeSupersessionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.user.User;
import com.sellerops.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The TalkTalk lane end to end on H2, with the fake transport and synthetic events only (lane contract §9): webhook
 * intake → processing → automation gate → dispatch, manual drafts and sends, handover, connection generations,
 * races, retention, tenancy. Every service is the real one behind the same transaction advice Spring gives it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TalkTalkLaneTest {

    @Autowired TalkTalkConnectionRepository connections;
    @Autowired TalkTalkEventRepository events;
    @Autowired TalkTalkConversationRepository conversations;
    @Autowired TalkTalkMessageRepository messages;
    @Autowired TalkTalkGuidanceRepository guidanceRepo;
    @Autowired TalkTalkDelegationRepository delegations;
    @Autowired TalkTalkDraftRepository draftRepo;
    @Autowired TalkTalkOutboundRepository outbound;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired KnowledgeSourceCurrencyRepository currencyRepo;
    @Autowired KnowledgeSupersessionRepository supersessions;
    @Autowired KnowledgeScheduleRepository schedules;
    @Autowired ProductRepository products;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    /** Monday 2026-11-23 10:00 KST — before a 12:00 cut-off on a dispatch day. */
    static final Instant MONDAY_10 = LocalDateTime.of(2026, 11, 23, 10, 0).atZone(TalkTalkGuidanceRules.KST).toInstant();

    final List<UUID> orgs = new ArrayList<>();
    /** The company's forbidden phrases, as the style service reports them to the lane. */
    final List<String> forbidden = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Counts every call; mode chosen per test. */
    static final class CountingTransport implements TalkTalkTransport {
        final TalkTalkConnection.Mode mode;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Outcome next = new Outcome(TalkTalkOutbound.Status.FAKE_RECORDED, null, null);
        volatile CountDownLatch hold;

        CountingTransport(TalkTalkConnection.Mode mode) {
            this.mode = mode;
        }

        @Override public TalkTalkConnection.Mode mode() { return mode; }

        @Override public Outcome send(String sendKey, String jsonBody) {
            calls.incrementAndGet();
            bodies.add(jsonBody);
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    h.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return next;
        }
    }

    /** One fully wired lane. */
    final class Lane {
        final TalkTalkSettings settings;
        final TalkTalkClock clock = new TalkTalkClock();
        final CountingTransport transport;
        final LocalSecretSealer sealer;
        final TalkTalkAccess access = new TalkTalkAccess(users);
        final TalkTalkFactCheck facts;
        final TalkTalkGuidanceService guidance;
        final TalkTalkAutomationGate gate;
        final TalkTalkConnectionService connection;
        final TalkTalkIntakeService intake;
        final TalkTalkEventProcessor processor;
        final TalkTalkDispatcher dispatcher;
        final TalkTalkDraftService drafts;
        final TalkTalkSendService sends;
        final TalkTalkQueryService query;
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        ObjectProvider<KnowledgeCurrencyService> currency;
        ObjectProvider<com.sellerops.knowledge.style.AnswerStyleService> styles;

        Lane(TalkTalkSettings settings, TalkTalkConnection.Mode mode) {
            this.settings = settings;
            this.transport = new CountingTransport(mode);
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            this.sealer = new LocalSecretSealer(new VaultKeyRing(Base64.getEncoder().encodeToString(key), "t-1", ""));
            clock.set(MONDAY_10);
            bf.registerSingleton("currency", new KnowledgeCurrencyService(orgSources, productSources, currencyRepo,
                    supersessions, schedules, products));
            this.currency = bf.getBeanProvider(KnowledgeCurrencyService.class);
            ObjectProvider<AgentDraftService> noModel = bf.getBeanProvider(AgentDraftService.class);
            ObjectProvider<KnowledgeSpineService> noSpine = bf.getBeanProvider(KnowledgeSpineService.class);
            bf.registerSingleton("styles", new com.sellerops.knowledge.style.AnswerStyleService(null) {
                @Override
                public com.sellerops.knowledge.style.AnswerStyleProfile profileFor(UUID orgId) {
                    return new com.sellerops.knowledge.style.AnswerStyleProfile(null, null, null, null, null, null,
                            List.of(), List.copyOf(forbidden), null, 1);
                }
            });
            this.styles = bf.getBeanProvider(com.sellerops.knowledge.style.AnswerStyleService.class);
            this.facts = new TalkTalkFactCheck(currency);
            this.guidance = tx(new TalkTalkGuidanceService(guidanceRepo, delegations, outbound, access, clock,
                    connections));
            this.gate = new TalkTalkAutomationGate(guidance, facts, messages, outbound, settings);
            this.connection = tx(new TalkTalkConnectionService(connections, access, sealer, settings, transport,
                    outbound, clock));
            this.intake = tx(new TalkTalkIntakeService(connections, events, sealer, settings, clock, e -> { }));
            this.processor = new TalkTalkEventProcessor(events, connections, conversations, messages, outbound, gate,
                    settings, clock, e -> { }, txManager);
            this.drafts = tx(new TalkTalkDraftService(conversations, messages, draftRepo, guidance, facts, access,
                    clock, products, noModel, noSpine, currency, orgSources, productSources, styles));
            this.dispatcher = new TalkTalkDispatcher(outbound, connections, conversations, messages, gate, transport,
                    sealer, settings, clock, connection, draftRepo, drafts, txManager);
            this.sends = tx(new TalkTalkSendService(conversations, draftRepo, outbound, messages, connection, drafts,
                    access, clock, e -> { }));
            this.query = tx(new TalkTalkQueryService(connection, guidance, conversations, messages, draftRepo,
                    outbound, access, clock));
        }

        /** The same draft service with a model, a search that finds nothing and the given meter (B208). */
        TalkTalkDraftService draftsWith(AgentDraftService model, com.sellerops.agent.quota.AgentQuotaService quota) {
            DefaultListableBeanFactory more = new DefaultListableBeanFactory();
            more.registerSingleton("model", model);
            KnowledgeSpineService spine = mock(KnowledgeSpineService.class);
            when(spine.retrieveForProduct(any(),
                    any(), any(),
                    any())).thenReturn(
                    new com.sellerops.knowledge.spine.SpineRetrieval(null, List.of(), List.of(), List.of()));
            more.registerSingleton("spine", spine);
            TalkTalkDraftService d = new TalkTalkDraftService(conversations, messages, draftRepo, guidance, facts,
                    access, clock, products, more.getBeanProvider(AgentDraftService.class),
                    more.getBeanProvider(KnowledgeSpineService.class), currency, orgSources, productSources, styles);
            d.setQuota(quota);
            return tx(d);
        }

        void run() {
            processor.processPending(50);
            dispatcher.dispatchPending(20);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T tx(T target) {
        org.springframework.aop.framework.ProxyFactory pf = new org.springframework.aop.framework.ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                (org.springframework.transaction.TransactionManager) txManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) pf.getProxy();
    }

    UUID org;
    UUID owner;
    Lane lane;
    String token;

    @BeforeEach
    void setUp() {
        org = newOrg();
        owner = newUser(org, "OWNER");
        lane = new Lane(TalkTalkSettings.defaults(), TalkTalkConnection.Mode.FAKE);
        token = connect(lane, org, owner, "wc1234", "ct_key_one");
    }

    @AfterEach
    void cleanup() {
        for (UUID o : orgs) {
            for (String t : List.of("talktalk_outbound", "talktalk_drafts", "talktalk_delegations",
                    "talktalk_shipping_guidance", "talktalk_messages", "talktalk_conversations", "talktalk_events",
                    "talktalk_connections", "org_knowledge_sources", "knowledge_schedule", "products", "users")) {
                jdbc.update("delete from " + t + " where org_id = ?", o);
            }
            jdbc.update("delete from organizations where id = ?", o);
        }
        orgs.clear();
    }

    UUID newOrg() {
        Organization o = new Organization();
        o.setName("톡톡 시험 가게");
        UUID id = organizations.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID newUser(UUID orgId, String role) {
        User u = new User();
        u.setOrgId(orgId);
        u.setEmail("tt-" + UUID.randomUUID() + "@example.invalid");
        u.setName("담당자");
        u.setRole(role);
        return users.save(u).getId();
    }

    String connect(Lane l, UUID orgId, UUID userId, String partner, String key) {
        String url = l.connection.connect(orgId, userId, partner, key).webhookUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    TalkTalkIntakeService.Result post(Lane l, String tok, String json) {
        return l.intake.accept(tok, "127.0.0.1", null, json.getBytes(StandardCharsets.UTF_8));
    }

    TalkTalkIntakeService.Result say(String user, String text) {
        return post(lane, token, "{\"event\":\"send\",\"user\":\"" + user + "\",\"textContent\":{\"text\":\"" + text
                + "\",\"inputType\":\"typing\"}}");
    }

    TalkTalkGuidance confirmGuidance(Lane l, UUID orgId, UUID userId, int hour, int minute) {
        TalkTalkGuidance draft = l.guidance.saveDraft(orgId, userId, new TalkTalkGuidanceService.Input(hour, minute,
                List.of("MON", "TUE", "WED", "THU", "FRI"),
                List.of(new TalkTalkGuidanceRules.ClosedDate(LocalDate.of(2026, 11, 27), "휴무")),
                LocalDate.of(2026, 12, 31)));
        return l.guidance.confirm(orgId, userId, draft.getId(), draft.getFingerprint());
    }

    TalkTalkGuidance delegate() {
        TalkTalkGuidance g = confirmGuidance(lane, org, owner, 12, 0);
        lane.guidance.delegate(org, owner, true, g.getId(), g.getFingerprint(),
                TalkTalkGuidanceRules.previewFingerprint(g));
        return g;
    }

    TalkTalkConversation onlyConversation() {
        List<TalkTalkConversation> all = conversations.findAll().stream().filter(c -> c.getOrgId().equals(org)).toList();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    List<TalkTalkOutbound> sent() {
        return outbound.findAll().stream().filter(o -> o.getOrgId().equals(org)).toList();
    }

    // ── intake ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an accepted event is stored minimized before 200; the user value is sealed, not stored")
    void intakeStoresMinimizedEvent() {
        assertThat(say("al-USER-1", "안녕하세요")).isEqualTo(TalkTalkIntakeService.Result.ACCEPTED);
        TalkTalkEvent e = events.findAll().stream().filter(x -> x.getOrgId().equals(org)).findFirst().orElseThrow();
        assertThat(e.getState()).isEqualTo(TalkTalkEvent.State.ACCEPTED);
        assertThat(e.getBodyHash()).hasSize(64);
        assertThat(e.getUserHash()).isEqualTo(TalkTalkHashes.userHash(org, "al-USER-1"));
        assertThat(new String(e.getUserSealed(), StandardCharsets.UTF_8)).doesNotContain("al-USER-1");
        assertThat(e.getConnectionGeneration()).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown token, other partner, disconnected or unreadable bodies leave no row")
    void intakeRefusals() {
        assertThat(post(lane, "x".repeat(43), "{\"event\":\"open\",\"user\":\"u\"}"))
                .isEqualTo(TalkTalkIntakeService.Result.UNKNOWN_TOKEN);
        assertThat(post(lane, token, "{\"event\":\"echo\",\"user\":\"u\",\"partner\":\"wc9999\"}"))
                .isEqualTo(TalkTalkIntakeService.Result.PARTNER_MISMATCH);
        assertThat(post(lane, token, "not json")).isEqualTo(TalkTalkIntakeService.Result.UNREADABLE);
        assertThat(lane.intake.accept(token, "127.0.0.1", null, new byte[70_000]))
                .isEqualTo(TalkTalkIntakeService.Result.TOO_LARGE);
        assertThat(events.countByOrgId(org)).isZero();
        lane.connection.disconnect(org, owner);
        assertThat(say("u", "안녕하세요")).isEqualTo(TalkTalkIntakeService.Result.UNKNOWN_TOKEN);
        assertThat(events.countByOrgId(org)).isZero();
    }

    @Test
    @DisplayName("with the allowlist enforced only documented sources pass, and X-Forwarded-For only from a trusted proxy")
    void sourceAllowlist() {
        Lane strict = new Lane(new TalkTalkSettings("fake", false, true, "10.0.0.0/8", "http://localhost", 90, 65536,
                600, 24, 600, 120), TalkTalkConnection.Mode.FAKE);
        UUID org2 = newOrg();
        UUID owner2 = newUser(org2, "OWNER");
        String tok = connect(strict, org2, owner2, "wc2222", "ct_key");
        byte[] body = "{\"event\":\"open\",\"user\":\"u\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(strict.intake.accept(tok, "8.8.8.8", null, body)).isEqualTo(TalkTalkIntakeService.Result.SOURCE_REFUSED);
        // An arbitrary peer cannot claim a documented address through the header.
        assertThat(strict.intake.accept(tok, "8.8.8.8", "211.249.40.5", body))
                .isEqualTo(TalkTalkIntakeService.Result.SOURCE_REFUSED);
        assertThat(strict.intake.accept(tok, "10.1.2.3", "8.8.8.8, 211.249.40.5", body))
                .isEqualTo(TalkTalkIntakeService.Result.ACCEPTED);
        assertThat(strict.intake.accept(tok, "211.249.68.7", null, body)).isEqualTo(TalkTalkIntakeService.Result.ACCEPTED);
        assertThat(events.countByOrgId(org2)).isEqualTo(2);
    }

    // ── automation ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("default OFF: a shipping question waits for a person and nothing is sent")
    void delegationOffByDefault() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        TalkTalkConversation c = onlyConversation();
        assertThat(c.isNeedsHuman()).isTrue();
        assertThat(c.getHumanReason()).isEqualTo("DELEGATION_OFF");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("delegated + confirmed guidance answers a general question once, through the fake, never as accepted")
    void automaticGuidanceHappyPath() {
        delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        assertThat(lane.transport.calls.get()).isEqualTo(1);
        TalkTalkOutbound o = sent().get(0);
        assertThat(o.getKind()).isEqualTo(TalkTalkOutbound.Kind.AUTO_GUIDANCE);
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.FAKE_RECORDED);
        assertThat(o.getTransportMode()).isEqualTo(TalkTalkConnection.Mode.FAKE);
        assertThat(o.getTextContent()).contains("낮 12시 이전 결제 건은 당일 출고").contains("오늘 11월 23일(월)은 출고일")
                .contains("주문 제작·예약 상품은 출고일이 달라질 수 있어요").contains("담당자가 확인해 안내드려요");
        assertThat(lane.transport.bodies.get(0)).contains("\"user\":\"u1\"").contains("\"event\":\"send\"");
        assertThat(onlyConversation().getLastAutoAt()).isNotNull();
        // a second general question within 24h goes to a person
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        assertThat(lane.transport.calls.get()).isEqualTo(1);
        assertThat(onlyConversation().getHumanReason()).isEqualTo("RECENT_AUTO");
    }

    // ── model draft meter (B208) ─────────────────────────────────────────────────────────────────────────────────

    private static final String MODEL_REPLY = """
            {"content":[{"type":"text","text":"{\\"category\\":\\"general_reply\\",\\"title\\":\\"답변\\",\\"comments\\":\\"안녕하세요. 문의 주셔서 감사합니다.\\"}"}],
             "stop_reason":"end_turn","usage":{"input_tokens":4613,"output_tokens":158}}
            """;

    AgentDraftService fakeModel(AtomicInteger calls) {
        com.sellerops.agent.llm.AgentDraftProperties p = new com.sellerops.agent.llm.AgentDraftProperties(true,
                org.toString(), "ANTHROPIC", "test-model", "sk-test", 4000, "low");
        return new AgentDraftService(p, (uri, headers, body) -> {
            calls.incrementAndGet();
            return new com.sellerops.agent.llm.AgentLlmTransport.Response(200, MODEL_REPLY, 2535L);
        }, new com.sellerops.agent.access.AgentCapabilityAccess("ALLOW_LIST", null));
    }

    @Test
    @DisplayName("a TalkTalk model draft is charged to the day's meter before the call and its tokens are recorded after")
    void modelDraftIsMetered() {
        confirmGuidance(lane, org, owner, 14, 0);
        say("u1", "포장은 어떻게 오나요?");
        lane.run();
        AtomicInteger calls = new AtomicInteger();
        com.sellerops.agent.quota.AgentQuotaService quota =
                mock(com.sellerops.agent.quota.AgentQuotaService.class);
        UUID usage = UUID.randomUUID();
        when(quota.consumeCharge(org, com.sellerops.agent.quota.AgentUsageKind.DRAFT, null,
                com.sellerops.agent.quota.AgentUsageActor.USER, 0)).thenReturn(new com.sellerops.agent.quota.AgentQuotaService
                .Charge(new com.sellerops.agent.quota.QuotaDecision(true, null, 0, 0), usage));

        TalkTalkDraft draft = lane.draftsWith(fakeModel(calls), quota).prepare(org, owner, onlyConversation().getId(), "MODEL");

        assertThat(draft.getTextContent()).contains("감사합니다");
        assertThat(calls.get()).isEqualTo(1);
        verify(quota).recordOutcome(usage, 2535L, 4613, 158);
    }

    @Test
    @DisplayName("a TalkTalk model draft refused by the ceiling or budget never reaches the model")
    void refusedModelDraftNeverCalls() {
        confirmGuidance(lane, org, owner, 14, 0);
        say("u1", "포장은 어떻게 오나요?");
        lane.run();
        AtomicInteger calls = new AtomicInteger();
        com.sellerops.agent.quota.AgentQuotaService quota =
                mock(com.sellerops.agent.quota.AgentQuotaService.class);
        when(quota.consumeCharge(any(), any(),
                any(), any(), anyInt()))
                .thenReturn(new com.sellerops.agent.quota.AgentQuotaService.Charge(new com.sellerops.agent.quota.QuotaDecision(
                        false, com.sellerops.agent.quota.QuotaDecision.Reason.DAILY_LLM_CALLS, 30, 30), null));
        UUID conv = onlyConversation().getId();

        assertThatThrownBy(() -> lane.draftsWith(fakeModel(calls), quota).prepare(org, owner, conv, "MODEL"))
                .isInstanceOf(ApiException.class);
        assertThat(calls.get()).isZero();
        // And with no meter at all the model is not reached either.
        assertThatThrownBy(() -> lane.draftsWith(fakeModel(calls), null).prepare(org, owner, conv, "MODEL"))
                .isInstanceOf(ApiException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("mixed or order-specific messages are a person's, and the reason is recorded")
    void mixedRequestsGoToAPerson() {
        delegate();
        say("u1", "몇 시 마감인가요 그리고 설치도 해주시나요");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("UNHANDLED_REQUEST");
        say("u1", "제 주문 오늘 출고되나요?");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("ORDER_SPECIFIC");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("standby moves the thread to the partner, and the persisted owner blocks a later message without standby")
    void persistedHandoverBlocks() {
        delegate();
        post(lane, token, "{\"standby\":true,\"event\":\"send\",\"user\":\"u1\",\"partner\":\"wc1234\","
                + "\"textContent\":{\"text\":\"헬로\",\"inputType\":\"typing\"}}");
        lane.run();
        assertThat(onlyConversation().getThreadOwner()).isEqualTo(TalkTalkConversation.Owner.PARTNER);
        say("u1", "오늘 주문하면 오늘 출고되나요?"); // e.g. after a bot-button click: standby omitted
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("PARTNER_OWNS_THREAD");
        assertThat(lane.transport.calls.get()).isZero();
        // 파트너 「상담 완료」 → back to the bot
        post(lane, token, "{\"event\":\"handover\",\"user\":\"u1\",\"partner\":\"wc1234\",\"options\":"
                + "{\"control\":\"passThread\",\"metadata\":\"{}\"}}");
        lane.run();
        assertThat(onlyConversation().getThreadOwner()).isEqualTo(TalkTalkConversation.Owner.BOT);
    }

    @Test
    @DisplayName("an echo of our own send never triggers a reply; a partner echo cancels queued automation")
    void echoes() {
        delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        TalkTalkOutbound queued = sent().get(0);
        // the partner speaks before the dispatcher runs
        post(lane, token, "{\"event\":\"echo\",\"echoedEvent\":\"send\",\"user\":\"u1\",\"partner\":\"wc1234\","
                + "\"textContent\":{\"text\":\"제가 확인할게요\",\"inputType\":\"typing\"},\"options\":{\"sourceId\":1,"
                + "\"threadOwnerId\":1}}");
        lane.run();
        assertThat(outbound.findById(queued.getId()).orElseThrow().getStatus())
                .isEqualTo(TalkTalkOutbound.Status.CANCELLED);
        assertThat(lane.transport.calls.get()).isZero();
        assertThat(onlyConversation().getThreadOwner()).isEqualTo(TalkTalkConversation.Owner.PARTNER);

        // a fresh conversation: our send, then its echo
        say("u2", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        TalkTalkOutbound ours = sent().stream().filter(o -> o.getStatus() == TalkTalkOutbound.Status.FAKE_RECORDED)
                .findFirst().orElseThrow();
        String echoed = ours.getTextContent().replace("\n", "\\n");
        post(lane, token, "{\"event\":\"echo\",\"echoedEvent\":\"send\",\"user\":\"u2\",\"partner\":\"wc1234\","
                + "\"textContent\":{\"text\":\"" + echoed + "\"},\"options\":{\"mobile\":false}}");
        lane.run();
        assertThat(outbound.findById(ours.getId()).orElseThrow().getEchoSeenAt()).isNotNull();
        assertThat(lane.transport.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same body twice is kept, marked, answered once, and does not cancel the original")
    void duplicateDelivery() {
        delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        List<TalkTalkMessage> lines = messages.findByConversationIdOrderByOccurredAtAscCreatedAtAsc(
                onlyConversation().getId());
        assertThat(lines.stream().filter(m -> m.getAuthor() == TalkTalkMessage.Author.CUSTOMER)).hasSize(2);
        assertThat(lines.stream().filter(TalkTalkMessage::isDuplicateSuspect)).hasSize(1);
        assertThat(lane.transport.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a newer message supersedes queued automation (reordering never answers a stale question)")
    void newerMessageSupersedes() {
        delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        say("u1", "아 그리고 환불도 되나요");
        lane.run();
        assertThat(sent().get(0).getStatus()).isEqualTo(TalkTalkOutbound.Status.CANCELLED);
        assertThat(sent().get(0).getFailureCode()).isEqualTo("NEWER_MESSAGE");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("turning delegation off, or confirming a new guidance, before the send stops the queued answer")
    void delegationChangeStopsQueued() {
        TalkTalkGuidance g = delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        lane.guidance.delegate(org, owner, false, null, null, null);
        lane.dispatcher.dispatchPending(20);
        assertThat(sent().get(0).getStatus()).isEqualTo(TalkTalkOutbound.Status.CANCELLED);

        // re-enable, queue, then a new version is confirmed: the delegation is stale → off
        lane.guidance.delegate(org, owner, true, g.getId(), g.getFingerprint(), TalkTalkGuidanceRules.previewFingerprint(g));
        say("u2", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        confirmGuidance(lane, org, owner, 13, 0);
        lane.dispatcher.dispatchPending(20);
        assertThat(sent()).allMatch(o -> o.getStatus() == TalkTalkOutbound.Status.CANCELLED);
        assertThat(lane.guidance.delegation(org).reason()).isEqualTo("DELEGATION_STALE");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("a queued answer whose delegation row changed under it is cancelled at the final recheck")
    void delegationRowBoundAtRecheck() {
        TalkTalkGuidance g = delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        // a second "on" row (same guidance) written without the service's cancel — as if it raced the queue
        TalkTalkDelegation row = new TalkTalkDelegation();
        row.setOrgId(org);
        row.setEnabled(true);
        row.setActorUserId(owner);
        row.setGuidanceId(g.getId());
        row.setGuidanceFingerprint(g.getFingerprint());
        row.setPreviewFingerprint(TalkTalkGuidanceRules.previewFingerprint(g));
        delegations.save(row);
        lane.dispatcher.dispatchPending(20);
        assertThat(sent().get(0).getFailureCode()).isEqualTo("DELEGATION_CHANGED");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("a document saying another cut-off — by the hour or by the minute — sends the message to a person")
    void knowledgeConflict() {
        delegate();
        OrgKnowledgeSource doc = new OrgKnowledgeSource();
        doc.setOrgId(org);
        doc.setKnowledgeType(OrgKnowledgeType.SHIPPING_POLICY);
        doc.setTitle("배송 안내");
        doc.setBody("낮 12시 30분 이전 결제 주문은 당일 출고합니다.");
        orgSources.save(doc);
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("KNOWLEDGE_CONFLICT");
        doc.setBody("오후 2시 이전 결제 주문은 당일 출고합니다.");
        orgSources.save(doc);
        say("u2", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        assertThat(lane.transport.calls.get()).isZero();
        assertThat(conversations.findAll().stream().filter(c -> c.getOrgId().equals(org))
                .allMatch(c -> "KNOWLEDGE_CONFLICT".equals(c.getHumanReason()))).isTrue();
    }

    @Test
    @DisplayName("a product-context conversation is a person's when some product leaves on another rule")
    void productException() {
        delegate();
        KnowledgeSchedule line = new KnowledgeSchedule();
        line.setOrgId(org);
        line.setProductId(product(org, "주문제작 머그"));
        line.setKind(KnowledgeSchedule.Kind.PRODUCTION);
        line.setStatus(KnowledgeSchedule.Status.CONFIRMED);
        line.setScheduleDate(LocalDate.of(2026, 12, 5));
        line.setActive(true);
        schedules.save(line);
        post(lane, token, "{\"event\":\"send\",\"user\":\"u1\",\"textContent\":{\"text\":\"이 상품을 문의합니다.\","
                + "\"inputType\":\"product\"},\"options\":{\"product\":{\"name\":\"주문제작 머그\",\"url\":\"http://x\"}}}");
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("PRODUCT_EXCEPTION_POSSIBLE");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("past the closed-day confirmation date the guidance does not answer")
    void calendarUnconfirmed() {
        TalkTalkGuidance draft = lane.guidance.saveDraft(org, owner, new TalkTalkGuidanceService.Input(12, 0,
                List.of("MON", "TUE", "WED", "THU", "FRI"), List.of(), LocalDate.of(2026, 11, 23)));
        TalkTalkGuidance g = lane.guidance.confirm(org, owner, draft.getId(), draft.getFingerprint());
        lane.guidance.delegate(org, owner, true, g.getId(), g.getFingerprint(), TalkTalkGuidanceRules.previewFingerprint(g));
        lane.clock.set(MONDAY_10.plusSeconds(4 * 3600)); // 14:00 — next dispatch day is Tuesday, past the date
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("CALENDAR_UNCONFIRMED");
    }

    @Test
    @DisplayName("a handover that is queued, sending or unknown stops automation until it settles")
    void pendingHandoverBlocks() {
        delegate();
        say("u1", "안녕하세요");
        lane.run();
        UUID conv = onlyConversation().getId();
        lane.sends.handover(org, owner, conv, "h-1", "PASS");
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        assertThat(onlyConversation().getHumanReason()).isEqualTo("HANDOVER_PENDING");
        TalkTalkOutbound pass = sent().get(0);
        pass.setStatus(TalkTalkOutbound.Status.UNKNOWN);
        outbound.save(pass);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        assertThat(onlyConversation().getHumanReason()).isEqualTo("HANDOVER_PENDING");
        assertThat(sent().stream().filter(o -> o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE)).isEmpty();
    }

    @Test
    @DisplayName("LIVE: an event from outside the trust boundary is never answered, and flags alone never send")
    void liveNeverFromFlagsAlone() {
        Lane live = new Lane(new TalkTalkSettings("live", true, false, "", "http://localhost", 90, 65536, 600, 24,
                600, 120), TalkTalkConnection.Mode.LIVE);
        UUID org2 = newOrg();
        UUID owner2 = newUser(org2, "OWNER");
        String tok = connect(live, org2, owner2, "wc3333", "ct_live");
        TalkTalkGuidance g = confirmGuidance(live, org2, owner2, 12, 0);
        live.guidance.delegate(org2, owner2, true, g.getId(), g.getFingerprint(), TalkTalkGuidanceRules.previewFingerprint(g));
        post(live, tok, "{\"event\":\"send\",\"user\":\"u\",\"textContent\":{\"text\":\"오늘 주문하면 오늘 출고되나요?\","
                + "\"inputType\":\"typing\"}}");
        live.run();
        TalkTalkConversation c = conversations.findAll().stream().filter(x -> x.getOrgId().equals(org2)).findFirst()
                .orElseThrow();
        assertThat(c.getHumanReason()).isEqualTo("UNTRUSTED_SOURCE");
        // a manual reply is blocked by readiness (no HTTPS, no allowlist, no observed ingress): zero egress
        live.drafts.prepare(org2, owner2, c.getId(), "GUIDANCE");
        TalkTalkDraft d = draftRepo.findFirstByConversationIdOrderByVersionDesc(c.getId()).orElseThrow();
        live.sends.send(org2, owner2, c.getId(), "m-1", d.getVersion(), d.getContentFingerprint(), null);
        live.run();
        TalkTalkOutbound o = outbound.findAll().stream().filter(x -> x.getOrgId().equals(org2)).findFirst().orElseThrow();
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.BLOCKED);
        assertThat(o.getFailureCode()).isEqualTo("LIVE_NOT_READY");
        assertThat(live.transport.calls.get()).isZero();
        assertThat(live.connection.readiness(connections.findByOrgId(org2).orElseThrow()).ready()).isFalse();
    }

    // ── manual drafts and sends ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a manual send names the exact reviewed version; actor, command and content are bound")
    void manualSend() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        assertThat(d.getBasis()).isEqualTo(TalkTalkDraft.Basis.GROUNDED);
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", d.getVersion(), "wrong", null))
                .isInstanceOf(ApiException.class).hasMessageContaining("바뀌었어요");
        TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        assertThat(lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null).getId())
                .isEqualTo(o.getId());
        lane.run();
        TalkTalkOutbound done = outbound.findById(o.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(TalkTalkOutbound.Status.FAKE_RECORDED);
        assertThat(done.getActorUserId()).isEqualTo(owner);
        assertThat(done.getTextContent()).isEqualTo(d.getTextContent());
        assertThat(onlyConversation().isNeedsHuman()).isFalse();
        // the same command with other content is refused
        TalkTalkDraft edited = lane.drafts.edit(org, owner, conv, "직접 쓴 답이에요.", d.getVersion());
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", edited.getVersion(),
                edited.getContentFingerprint(), null)).hasMessageContaining("다른 내용");
    }

    @Test
    @DisplayName("a held draft cannot be sent; the seller's own edit can")
    void heldDraft() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "제 주문 오늘 출고되나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        assertThat(d.getBasis()).isEqualTo(TalkTalkDraft.Basis.NEEDS_HUMAN);
        assertThat(d.getHoldReasons()).contains("QUESTION_ORDER_SPECIFIC");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(),
                null)).hasMessageContaining("확인이 필요한");
        TalkTalkDraft mine = lane.drafts.edit(org, owner, conv, "주문 확인 후 바로 안내드릴게요.", d.getVersion());
        // The seller's own follow-up promise is held like any other (review item 11) — sent only as a confirmed
        // one-off exception, recorded on the outbound.
        assertThat(mine.getHoldReasons()).contains("CLAIM_SELLER_PROMISE");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-2", mine.getVersion(),
                mine.getContentFingerprint(), null)).hasMessageContaining("예외");
        TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-2", mine.getVersion(), mine.getContentFingerprint(),
                null, true);
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.QUEUED);
        assertThat(o.getHoldConfirmed()).contains("CLAIM_SELLER_PROMISE");
    }

    @Test
    @DisplayName("a guidance retired after the draft makes the send recheck refuse")
    void recheckBeforeSend() {
        TalkTalkGuidance g = confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.guidance.retire(org, owner, g.getId());
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(),
                null)).hasMessageContaining("참고한 내용이 바뀌었어요");
    }

    @Test
    @DisplayName("a queued manual reply is not sent after the customer writes again")
    void queuedManualThenNewerMessage() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        say("u1", "아 그리고 주소 바꿔도 되나요");
        lane.run();
        TalkTalkOutbound o = sent().get(0);
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.CANCELLED);
        assertThat(o.getFailureCode()).isEqualTo("NEWER_MESSAGE");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("UNKNOWN is never resent automatically, and a new draft with the same words still needs the acknowledgement")
    void unknownNeedsAcknowledgement() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.transport.next = new TalkTalkTransport.Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "TIMEOUT");
        TalkTalkOutbound first = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        lane.run();
        lane.run();
        assertThat(outbound.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(lane.transport.calls.get()).isEqualTo(1);
        TalkTalkDraft again = lane.drafts.edit(org, owner, conv, d.getTextContent(), d.getVersion());
        assertThat(again.getContentFingerprint()).isEqualTo(d.getContentFingerprint());
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-2", again.getVersion(),
                again.getContentFingerprint(), null)).hasMessageContaining("결과를 몰라요");
        lane.transport.next = new TalkTalkTransport.Outcome(TalkTalkOutbound.Status.FAKE_RECORDED, null, null);
        lane.sends.send(org, owner, conv, "c-2", again.getVersion(), again.getContentFingerprint(), first.getId());
        lane.run();
        assertThat(lane.transport.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("FAILED and success=false stay failed; nothing retries on its own")
    void failedIsFinal() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.transport.next = new TalkTalkTransport.Outcome(TalkTalkOutbound.Status.FAILED, "01", "RESULT_01");
        TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        lane.run();
        lane.run();
        TalkTalkOutbound done = outbound.findById(o.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(TalkTalkOutbound.Status.FAILED);
        assertThat(done.getResultCode()).isEqualTo("01");
        assertThat(lane.transport.calls.get()).isEqualTo(1);
        assertThat(onlyConversation().isNeedsHuman()).isTrue();
    }

    @Test
    @DisplayName("a crash after the request left and before the result was saved becomes UNKNOWN, never resent")
    void crashAfterSend() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        // as the dispatcher leaves it between the send and the record: SENDING, claimed
        o = outbound.findById(o.getId()).orElseThrow();
        o.setStatus(TalkTalkOutbound.Status.SENDING);
        o.setClaimedAt(lane.clock.now());
        outbound.save(o);
        lane.clock.set(MONDAY_10.plusSeconds(600));
        lane.run();
        TalkTalkOutbound after = outbound.findById(o.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(after.getFailureCode()).isEqualTo("INTERRUPTED");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("two dispatchers racing for one row send it once; two processors racing for one event store it once")
    void concurrentClaims() throws Exception {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        UUID eventId = events.findAll().stream().filter(e -> e.getOrgId().equals(org)).findFirst().orElseThrow().getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> both = pool.invokeAll(List.<Callable<Boolean>>of(
                    () -> lane.processor.process(eventId), () -> lane.processor.process(eventId)));
            assertThat(both.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return false;
                }
            })).hasSize(1);
            UUID conv = onlyConversation().getId();
            assertThat(messages.findByConversationIdOrderByOccurredAtAscCreatedAtAsc(conv)).hasSize(1);
            TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
            TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
            lane.transport.hold = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> lane.dispatcher.dispatch(o.getId()));
            Future<Boolean> b = pool.submit(() -> lane.dispatcher.dispatch(o.getId()));
            Thread.sleep(300);
            lane.transport.hold.countDown();
            assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue)).hasSize(1);
            assertThat(lane.transport.calls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a photo goes to a person, is shown as a photo to check in 파트너센터, and has no draft")
    void nonTextMessage() {
        delegate();
        post(lane, token, "{\"event\":\"send\",\"user\":\"u1\",\"imageContent\":{\"imageUrl\":\"https://example.invalid/x.jpg\"}}");
        lane.run();
        TalkTalkConversation c = onlyConversation();
        assertThat(c.getHumanReason()).isEqualTo("NON_TEXT");
        TalkTalkViews.ConversationDetail d = lane.query.detail(org, owner, c.getId());
        assertThat(d.messages().get(0).text()).isNull();
        assertThat(d.messages().get(0).nonTextNote()).contains("사진").contains("파트너센터");
        assertThat(d.toString()).doesNotContain("example.invalid");
        assertThatThrownBy(() -> lane.drafts.prepare(org, owner, c.getId(), "GUIDANCE")).hasMessageContaining("파트너센터");
        assertThat(lane.transport.calls.get()).isZero();
    }

    // ── safety parity (review item 11) ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a seller's sentence that reverses who pays is fixed first: no confirmation sends it")
    void sellerEditContraryCost() {
        confirmGuidance(lane, org, owner, 12, 0);
        OrgKnowledgeSource doc = new OrgKnowledgeSource();
        doc.setOrgId(org);
        doc.setKnowledgeType(OrgKnowledgeType.EXCHANGE_REFUND_POLICY);
        doc.setTitle("반품 안내");
        doc.setBody("단순 변심 반품 배송비는 고객 부담입니다.");
        orgSources.save(doc);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        TalkTalkDraft mine = lane.drafts.edit(org, owner, conv, "단순 변심 반품 배송비는 판매자가 부담해요.", d.getVersion());
        assertThat(mine.getHoldReasons()).contains("COST_CONFLICT");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", mine.getVersion(),
                mine.getContentFingerprint(), null, true)).hasMessageContaining("고친 뒤");
        // the same promise with nothing on file either way is the one-off exception, not a conflict
        jdbc.update("delete from org_knowledge_sources where org_id = ?", org);
        TalkTalkDraft free = lane.drafts.edit(org, owner, conv, "단순 변심 반품 배송비는 판매자가 부담해요!", mine.getVersion());
        assertThat(free.getHoldReasons()).contains("CLAIM_COST").doesNotContain("COST_CONFLICT");
        assertThat(lane.sends.send(org, owner, conv, "c-2", free.getVersion(), free.getContentFingerprint(), null,
                true).getHoldConfirmed()).contains("CLAIM_COST");
    }

    @Test
    @DisplayName("a forbidden phrase holds a seller's edit with no exception, and stops a queued guidance at dispatch")
    void forbiddenPhrase() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        forbidden.add("무조건");
        TalkTalkDraft mine = lane.drafts.edit(org, owner, conv, "무조건 오늘 보내드려요.", d.getVersion());
        assertThat(mine.getHoldReasons()).contains("FORBIDDEN_PHRASE");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", mine.getVersion(),
                mine.getContentFingerprint(), null, true)).hasMessageContaining("고친 뒤");
        // a guidance reply queued before the phrase was banned is stopped at dispatch
        forbidden.clear();
        TalkTalkDraft g = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.sends.send(org, owner, conv, "c-2", g.getVersion(), g.getContentFingerprint(), null);
        forbidden.add("담당자");
        lane.run();
        TalkTalkOutbound o = outbound.findByOrgIdAndCommandId(org, "c-2").orElseThrow();
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.CANCELLED);
        assertThat(o.getFailureCode()).isEqualTo("RECHECK_REQUIRED:FORBIDDEN_PHRASE");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("a seller's figure the material states otherwise is a policy conflict")
    void sellerEditContraryFigure() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        TalkTalkDraft mine = lane.drafts.edit(org, owner, conv, "오후 3시 이전 결제 건은 당일 출고돼요.", d.getVersion());
        assertThat(mine.getHoldReasons()).contains("POLICY_CONFLICT");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", mine.getVersion(),
                mine.getContentFingerprint(), null, true)).hasMessageContaining("고친 뒤");
    }

    @Test
    @DisplayName("a product is bound only when exactly one product has the conversation's name")
    void productBinding() {
        TalkTalkConversation c = new TalkTalkConversation();
        c.setOrgId(org);
        c.setLastProductName("머그컵");
        assertThat(lane.drafts.productOf(org, c)).isNull();
        UUID one = product(org, "머그컵");
        assertThat(lane.drafts.productOf(org, c)).isEqualTo(one);
        product(org, "머그컵");
        assertThat(lane.drafts.productOf(org, c)).as("ambiguous").isNull();
        UUID other = newOrg();
        product(other, "텀블러");
        c.setLastProductName("텀블러");
        assertThat(lane.drafts.productOf(org, c)).as("another org's product").isNull();
    }

    UUID product(UUID orgId, String name) {
        com.sellerops.product.Product p = new com.sellerops.product.Product();
        p.setOrgId(orgId);
        p.setName(name);
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }

    @Test
    @DisplayName("an evidence record that cannot be read fails the recheck closed")
    void unreadableEvidence() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        d.setEvidenceJson("not json");
        draftRepo.save(d);
        assertThat(lane.drafts.recheck(org, d)).contains("EVIDENCE_UNREADABLE");
        assertThatThrownBy(() -> lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(),
                null)).hasMessageContaining("참고한 내용이 바뀌었어요");
    }

    // ── time of check / concurrency (review item 12) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a source that changes while a manual reply waits stops it at dispatch")
    void dispatchTimeRecheck() {
        TalkTalkGuidance g = confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        lane.guidance.retire(org, owner, g.getId());
        lane.run();
        TalkTalkOutbound o = sent().get(0);
        assertThat(o.getStatus()).isEqualTo(TalkTalkOutbound.Status.CANCELLED);
        assertThat(o.getFailureCode()).startsWith("RECHECK_REQUIRED");
        assertThat(lane.transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("a customer line that arrives while the reply is in flight stays open after the reply is recorded")
    void newMessageDuringTransport() throws Exception {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        TalkTalkOutbound o = lane.sends.send(org, owner, conv, "c-1", d.getVersion(), d.getContentFingerprint(), null);
        lane.transport.hold = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> sending = pool.submit(() -> lane.dispatcher.dispatch(o.getId()));
            Thread.sleep(300); // the request is out (held by the blocking fake)
            say("u1", "그럼 환불은 어떻게 하나요");
            lane.processor.processPending(50);
            lane.transport.hold.countDown();
            assertThat(sending.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(outbound.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(TalkTalkOutbound.Status.FAKE_RECORDED);
        TalkTalkConversation after = onlyConversation();
        assertThat(after.isNeedsHuman()).isTrue();
        assertThat(after.getHumanReason()).isEqualTo("DELEGATION_OFF");
    }

    @Test
    @DisplayName("two sends with different command ids for one conversation: exactly one is queued")
    void concurrentDistinctCommands() throws Exception {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, conv, "GUIDANCE");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String cmd : List.of("c-A", "c-B")) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        lane.sends.send(org, owner, conv, cmd, d.getVersion(), d.getContentFingerprint(), null);
                        return "QUEUED";
                    } catch (ApiException e) {
                        return e.getMessage();
                    }
                }));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsOnlyOnce("QUEUED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(sent()).hasSize(1);
    }

    @Test
    @DisplayName("webhooks racing a rotation never write an event or ingress proof against the new generation with the old token")
    void intakeRacesRotation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> posts = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    say("u" + i, "안녕하세요");
                }
            });
            Future<String> rotated = pool.submit(() -> lane.connection.rotateWebhook(org, owner).webhookUrl());
            posts.get();
            rotated.get();
        } finally {
            pool.shutdownNow();
        }
        TalkTalkConnection c = connections.findByOrgId(org).orElseThrow();
        assertThat(c.getGeneration()).isEqualTo(2);
        assertThat(c.getWebhookTokenHash()).isNotEqualTo(TalkTalkHashes.sha256(token));
        assertThat(events.findAll().stream().filter(e -> e.getOrgId().equals(org)))
                .allMatch(e -> e.getConnectionGeneration() == 1);
    }

    // ── connection generations (review item 10) ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("same-mode reconnect with another partner and key: old queued work is cancelled, zero egress")
    void reconnectInvalidatesOldWork() {
        TalkTalkGuidance g = delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        say("u2", "금요일에 쉬시나요 그리고 포장도 되나요");
        lane.processor.processPending(50);
        TalkTalkConversation c2 = conversations.findAll().stream().filter(c -> c.getOrgId().equals(org))
                .filter(c -> c.getUserHash().equals(TalkTalkHashes.userHash(org, "u2"))).findFirst().orElseThrow();
        TalkTalkDraft d = lane.drafts.prepare(org, owner, c2.getId(), "GUIDANCE");
        TalkTalkDraft mine = lane.drafts.edit(org, owner, c2.getId(), "포장은 가능해요.", d.getVersion());
        lane.sends.send(org, owner, c2.getId(), "c-1", mine.getVersion(), mine.getContentFingerprint(), null);
        say("u3", "오늘 출고되나요?"); // accepted under generation 1, not yet processed
        String newToken = connect(lane, org, owner, "wc5678", "ct_key_two");
        lane.run();
        assertThat(sent()).isNotEmpty().allMatch(o -> o.getStatus() == TalkTalkOutbound.Status.CANCELLED
                && "CONNECTION_CHANGED".equals(o.getFailureCode()));
        assertThat(lane.transport.calls.get()).isZero();
        assertThat(events.findAll().stream().filter(e -> e.getOrgId().equals(org)).filter(e -> "STALE_GENERATION"
                .equals(e.getNote()))).hasSize(1);
        TalkTalkConnection now = connections.findByOrgId(org).orElseThrow();
        assertThat(now.getGeneration()).isEqualTo(2);
        assertThat(now.getFirstTrustedEventAt()).isNull();
        // the old token is dead; an old conversation is never answered through the new partner
        assertThat(say("u1", "안녕하세요")).isEqualTo(TalkTalkIntakeService.Result.UNKNOWN_TOKEN);
        TalkTalkDraft retry = lane.drafts.edit(org, owner, c2.getId(), "포장은 가능해요!", mine.getVersion());
        assertThatThrownBy(() -> lane.sends.send(org, owner, c2.getId(), "c-2", retry.getVersion(),
                retry.getContentFingerprint(), null)).hasMessageContaining("다른 톡톡 계정");
        // a row made under the old generation that escaped the cancel is still blocked at dispatch
        TalkTalkOutbound stray = new TalkTalkOutbound();
        stray.setOrgId(org);
        stray.setConversationId(c2.getId());
        stray.setKind(TalkTalkOutbound.Kind.MANUAL_REPLY);
        stray.setTextContent("x");
        stray.setContentFingerprint(TalkTalkHashes.replyFingerprint("x"));
        stray.setCommandId("stray");
        stray.setTransportMode(TalkTalkConnection.Mode.FAKE);
        stray.setConnectionGeneration(1);
        stray.setStatus(TalkTalkOutbound.Status.QUEUED);
        outbound.save(stray);
        lane.run();
        assertThat(outbound.findById(stray.getId()).orElseThrow().getFailureCode()).isEqualTo("CONNECTION_CHANGED");
        assertThat(lane.transport.calls.get()).isZero();
        // new partner, new events: a new conversation
        post(lane, newToken, "{\"event\":\"open\",\"user\":\"u1\"}");
        lane.run();
        assertThat(conversations.findAll().stream().filter(c -> c.getOrgId().equals(org))
                .filter(c -> "wc5678".equals(c.getPartnerId()))).hasSize(1);
        assertThat(g).isNotNull();
    }

    @Test
    @DisplayName("rotating the webhook address kills the old one and cancels queued work")
    void rotation() {
        delegate();
        say("u1", "오늘 주문하면 오늘 출고되나요?");
        lane.processor.processPending(50);
        String url = lane.connection.rotateWebhook(org, owner).webhookUrl();
        lane.run();
        assertThat(sent().get(0).getFailureCode()).isEqualTo("WEBHOOK_ROTATED");
        assertThat(say("u1", "x")).isEqualTo(TalkTalkIntakeService.Result.UNKNOWN_TOKEN);
        assertThat(post(lane, url.substring(url.lastIndexOf('/') + 1), "{\"event\":\"open\",\"user\":\"u1\"}"))
                .isEqualTo(TalkTalkIntakeService.Result.ACCEPTED);
        assertThat(lane.transport.calls.get()).isZero();
    }

    // ── tenancy, roles, redaction, counts, retention ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("another organization cannot read, prepare for, or send into this one's conversation")
    void tenantIsolation() {
        confirmGuidance(lane, org, owner, 12, 0);
        say("u1", "금요일에 쉬시나요?");
        lane.run();
        UUID conv = onlyConversation().getId();
        UUID other = newOrg();
        UUID stranger = newUser(other, "OWNER");
        connect(lane, other, stranger, "wc7777", "ct_other");
        assertThatThrownBy(() -> lane.query.detail(other, stranger, conv)).hasMessageContaining("찾을 수 없어요");
        assertThatThrownBy(() -> lane.drafts.prepare(other, stranger, conv, "GUIDANCE"))
                .hasMessageContaining("찾을 수 없어요");
        assertThatThrownBy(() -> lane.sends.send(other, stranger, conv, "c", 1, "f", null))
                .hasMessageContaining("찾을 수 없어요");
        // a user of org A acting with org B's id is refused before anything is read
        assertThatThrownBy(() -> lane.query.detail(other, owner, conv)).hasMessageContaining("사용자가 아니에요");
    }

    @Test
    @DisplayName("connecting, confirming and delegating are the owner's")
    void ownerOnly() {
        UUID staff = newUser(org, "STAFF");
        assertThatThrownBy(() -> lane.connection.connect(org, staff, "wc1", "k")).hasMessageContaining("소유자");
        assertThatThrownBy(() -> lane.guidance.delegate(org, staff, false, null, null, null))
                .hasMessageContaining("소유자");
    }

    @Test
    @DisplayName("views carry no key, token, user value or full partner id")
    void redaction() {
        say("al-SECRET-USER", "금요일에 쉬시나요?");
        lane.run();
        TalkTalkViews.StatusView s = lane.query.status(org, owner);
        String all = s.toString() + lane.query.conversations(org, owner)
                + lane.query.detail(org, owner, onlyConversation().getId());
        assertThat(all).doesNotContain("ct_key_one").doesNotContain("al-SECRET-USER").doesNotContain(token)
                .doesNotContain("wc1234");
        assertThat(s.connection().partner()).isEqualTo("wc1***");
        assertThat(s.connection().fake()).isTrue();
        assertThat(s.readiness().ready()).isFalse();
        assertThat(connections.findByOrgId(org).orElseThrow().toString()).doesNotContain("ct_key_one");
        assertThat(new TalkTalkController.ConnectRequest("wc1234", "ct_key_one").toString()).doesNotContain("ct_key_one");
    }

    @Test
    @DisplayName("the summary counts conversations waiting for a person, not messages")
    void summaryCountsConversations() {
        say("u1", "환불 되나요");
        say("u1", "답 주세요");
        say("u2", "재고 있나요");
        lane.run();
        TalkTalkViews.SummaryView s = lane.query.summary(org, owner);
        assertThat(s.needsHumanConversations()).isEqualTo(2);
        assertThat(s.notice()).contains("들어가지 않아요");
    }

    @Test
    @DisplayName("text past the retention period is removed; rows and states stay")
    void retention() {
        say("u1", "환불 되나요");
        lane.run();
        TalkTalkWorker worker = new TalkTalkWorker(lane.processor, lane.dispatcher, events, messages, draftRepo,
                outbound, lane.settings, lane.clock, txManager, false);
        lane.clock.set(MONDAY_10.plusSeconds(91L * 24 * 3600));
        assertThat(worker.purgeIfDue()).isPositive();
        TalkTalkMessage m = messages.findByConversationIdOrderByOccurredAtAscCreatedAtAsc(onlyConversation().getId())
                .get(0);
        assertThat(m.getTextContent()).isNull();
        assertThat(m.getPurgedAt()).isNotNull();
    }
}
