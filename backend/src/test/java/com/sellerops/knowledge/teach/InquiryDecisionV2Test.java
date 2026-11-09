package com.sellerops.knowledge.teach;

import org.assertj.core.groups.Tuple;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftResponseParser;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.QuotaDecision;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryProductBinding;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.AnswerBasisState;
import com.sellerops.inquiry.draft.DraftEvidenceSnippets;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.sellerops.inquiry.draft.InquiryDraftComposer;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.inquiry.draft.InquiryDraftEvidenceRepository;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.inquiry.draft.InquiryKnowledgeAssessor;
import com.sellerops.inquiry.draft.dto.GeneratedDraftView;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.reply.InquiryReplyDraftService;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.candidate.KnowledgeCandidateRepository;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.knowledge.guidance.SellerGuidance;
import com.sellerops.knowledge.guidance.SellerGuidanceRepository;
import com.sellerops.knowledge.guidance.SellerGuidanceService;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.knowledge.spine.SpineRetrieval;
import com.sellerops.knowledge.spine.SpineSourceType;
import com.sellerops.knowledge.spine.SourceRefResolver;
import com.sellerops.knowledge.spine.adapter.InquiryAnswerAdapter;
import com.sellerops.knowledge.spine.adapter.KnowledgeSourceAdapter;
import com.sellerops.knowledge.spine.adapter.ProductFactAdapter;
import com.sellerops.knowledge.spine.adapter.ReviewReplyAdapter;
import com.sellerops.knowledge.spine.adapter.SellerDecisionAdapter;
import com.sellerops.knowledge.spine.adapter.SellerGuidanceAdapter;
import com.sellerops.knowledge.spine.adapter.SellerKnowledgeAdapter;
import com.sellerops.knowledge.teach.dto.CaseDetailView;
import com.sellerops.operationscase.CaseKnowledgeGap;
import com.sellerops.operationscase.OperationsCaseRepository;
import com.sellerops.operationscase.OperationsSubjectKind;
import com.sellerops.operationscase.investigation.CaseDraftPreparer;
import com.sellerops.operationscase.investigation.CaseInvestigationTools;
import com.sellerops.order.fact.OrderFactLookup;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.FactConfidence;
import com.sellerops.product.FactKeys;
import com.sellerops.product.Product;
import com.sellerops.product.ProductFact;
import com.sellerops.product.ProductFactRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.KnowledgeAuthorship;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Inquiry Decision v2 on the real retrieval, catalogue and draft path — the model that plans and judges is scripted,
 * everything else is production code over a real schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class InquiryDecisionV2Test {

    @Autowired EntityManager em;
    @Autowired com.sellerops.review.publish.ReviewReplyExecutionRepository replyExecutions;
    @Autowired com.sellerops.attention.reply.ReviewReplyOutcomeRepository replyOutcomes;
    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired ProductFactRepository facts;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired AnswerMemoryRepository memories;
    @Autowired KnowledgeCandidateRepository candidateRows;
    @Autowired SellerGuidanceRepository guidanceRows;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryReplyDraftRepository draftRows;
    @Autowired InquiryDraftEvidenceRepository evidenceRows;
    @Autowired com.sellerops.inquiry.draft.DraftReviewPendingRepository pendingDrafts;
    @Autowired ChannelRepository channels;
    @Autowired com.sellerops.product.ChannelProductRepository listings;
    @Autowired com.sellerops.order.ChannelOrderRepository channelOrders;

    private UUID org;
    private UUID productId;
    // A real user row (2026-11-02): the seller's knowledge names its author by a foreign key PostgreSQL enforces, so this
    // class runs over the production schema too — the evidence-slot regression was found there, not here.
    @Autowired com.sellerops.user.UserRepository users;
    @Autowired com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerCheckRows;
    @Autowired com.sellerops.knowledge.style.OrganizationAnswerStyleRepository styleRows;
    private UUID user;
    private ProductKnowledgeLibraryService library;
    private SellerOperationsKnowledgeService policies;
    private InquiryEvidenceRetriever retriever;
    private InquiryKnowledgeAssessor assessor;
    private KnowledgeCandidateService candidates;
    private InquiryReplyDraftService draftService;
    private com.sellerops.inquiry.decision.InquiryEvidenceCollector collector;
    private com.sellerops.inquiry.sellercheck.InquirySellerCheckService sellerChecks;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("결정 v2 QA");
        org = organizations.save(o).getId();
        com.sellerops.user.User u = new com.sellerops.user.User();
        u.setOrgId(org);
        u.setEmail("decision-v2-" + UUID.randomUUID() + "@example.invalid");
        u.setName("판매자");
        u.setRole("OWNER");
        user = users.save(u).getId();
        Product p = new Product();
        p.setOrgId(org);
        p.setName("선바로 일체형 전선몰딩");
        p.setStatus("ACTIVE");
        productId = products.save(p).getId();
        library = new ProductKnowledgeLibraryService(products, productSources, productChunks, variants);
        policies = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        AnswerMemoryService memory = new AnswerMemoryService(memories, orgChunks, productChunks);
        retriever = new InquiryEvidenceRetriever(products, library, policies, memory,
                com.sellerops.order.fact.StoredOnlyOrderFacts.reader(channelOrders, channels,
                        (orgId, channelCode, accountId, rows) -> com.sellerops.coverage.ChannelDataState.OBSERVED_FRESH));
        List<KnowledgeSourceAdapter> adapters = List.of(
                new SellerKnowledgeAdapter(orgSources, orgChunks, productSources, productChunks),
                new ProductFactAdapter(facts), new InquiryAnswerAdapter(memories), new ReviewReplyAdapter(em,
                        new com.sellerops.review.publish.ReviewDeliveryTruthReader(
                                replyExecutions, replyOutcomes)),
                new SellerDecisionAdapter(em), new SellerGuidanceAdapter(guidanceRows));
        KnowledgeSpineService spine = new KnowledgeSpineService(adapters, products, new SourceRefResolver(em), retriever);
        assessor = new InquiryKnowledgeAssessor(retriever, spine, variants, products);
        assessor.setKindCorrections(kindRows, workItems);
        collector = new com.sellerops.inquiry.decision.InquiryEvidenceCollector(retriever, productSources,
                productChunks, orgSources, orgChunks, facts, variants, listings, channels, memories, inquiries);
        candidates = new KnowledgeCandidateService(candidateRows, memories, productSources,
                new ProductKnowledgeIndexer(productChunks), products, orgSources, policies, variants);
        draftService = new InquiryReplyDraftService(workItems, draftRows);
        collector.setSellerChecks(sellerCheckRows);
        sellerChecks = new com.sellerops.inquiry.sellercheck.InquirySellerCheckService(sellerCheckRows, workItems);
        candidates.teach(org, "PRODUCT", productId, "호수", null,
                "선바로 전선몰딩 내경 너비는 1호 13mm(내경 높이 9mm), 2호 20mm(내경 높이 10mm), 3호 23mm입니다. "
                        + "지름 10mm 전선 두 가닥은 3호부터 가능합니다.", OrgKnowledgeType.GENERAL_CS_FAQ, user, "판매자");
    }

    /**
     * A judge that says what a correct judge would: a need is FULL when a candidate carries the word it names, and
     * everything else is whatever the case under test scripts. It counts calls and keeps what it was shown.
     */
    static final class ScriptedDecision implements com.sellerops.inquiry.decision.InquiryDecisionModel {
        final List<com.sellerops.inquiry.decision.InquiryNeed> needs;
        final java.util.function.BiFunction<com.sellerops.inquiry.decision.InquiryNeed,
                List<com.sellerops.inquiry.decision.EvidenceCandidate>, com.sellerops.inquiry.decision.NeedVerdict> judge;
        boolean enabled = true;
        boolean planFails;
        List<com.sellerops.inquiry.decision.EvidenceCandidate> shown = List.of();
        int calls;

        ScriptedDecision(List<com.sellerops.inquiry.decision.InquiryNeed> needs,
                         java.util.function.BiFunction<com.sellerops.inquiry.decision.InquiryNeed,
                                 List<com.sellerops.inquiry.decision.EvidenceCandidate>,
                                 com.sellerops.inquiry.decision.NeedVerdict> judge) {
            this.needs = needs;
            this.judge = judge;
        }

        @Override
        public boolean enabledFor(UUID orgId) {
            return enabled;
        }

        @Override
        public Answer<List<com.sellerops.inquiry.decision.InquiryNeed>> plan(UUID orgId, String question) {
            calls++;
            return new Answer<>(planFails ? null : needs, new CallCost(1, 1, 1, 1));
        }

        @Override
        public Answer<java.util.Map<String, com.sellerops.inquiry.decision.NeedVerdict>> judge(UUID orgId,
                String question, List<com.sellerops.inquiry.decision.InquiryNeed> planned,
                List<com.sellerops.inquiry.decision.EvidenceCandidate> evidence,
                List<com.sellerops.inquiry.decision.PrecedentCandidate> precedents) {
            calls++;
            shown = evidence;
            java.util.Map<String, com.sellerops.inquiry.decision.NeedVerdict> out = new java.util.LinkedHashMap<>();
            for (com.sellerops.inquiry.decision.InquiryNeed n : planned) {
                out.put(n.id(), judge.apply(n, evidence));
            }
            return new Answer<>(out, new CallCost(1, 1, 1, 1));
        }

        /** When the inquiry arrived, as the engine told the judge (2026-11-02). */
        String sawReceived;

        @Override
        public Answer<java.util.Map<String, com.sellerops.inquiry.decision.NeedVerdict>> judge(UUID orgId,
                String question, List<com.sellerops.inquiry.decision.InquiryNeed> planned,
                List<com.sellerops.inquiry.decision.EvidenceCandidate> evidence,
                List<com.sellerops.inquiry.decision.PrecedentCandidate> precedents, String received) {
            sawReceived = received;
            return judge(orgId, question, planned, evidence, precedents);
        }
    }

    static com.sellerops.inquiry.decision.InquiryNeed need(String id, String ask,
                                                          com.sellerops.inquiry.decision.NeedType type) {
        return new com.sellerops.inquiry.decision.InquiryNeed(id, ask, type, ask);
    }

    /** FULL citing every candidate whose text carries {@code word}, NONE when none does. */
    static com.sellerops.inquiry.decision.NeedVerdict fullIfSaid(com.sellerops.inquiry.decision.InquiryNeed n,
                                                                 List<com.sellerops.inquiry.decision.EvidenceCandidate> e,
                                                                 String word) {
        List<String> ids = e.stream().filter(c -> c.text().contains(word))
                .map(com.sellerops.inquiry.decision.EvidenceCandidate::id).toList();
        return com.sellerops.inquiry.decision.NeedVerdict.of(n.id(),
                ids.isEmpty() ? com.sellerops.inquiry.decision.NeedStatus.NONE
                        : com.sellerops.inquiry.decision.NeedStatus.FULL, ids, ids.isEmpty() ? "없음" : null, null,
                List.of());
    }

    @Test
    @DisplayName("R 77a91fab shape — one of three needs covered: the part it answers is drafted, the other two are left "
            + "to the seller as questions and the reply is not complete (owner, 2026-11-01 tenth review)")
    void partialCoverageDraftsThePartAndLeavesTheRest() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 8.5mm 케이블인데 몇 호가 적당할까요? 엘보 사이즈도요.");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                need("N2", "8.5mm 케이블에 맞는 호수", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY),
                need("N3", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> n.id().equals("N2") ? fullIfSaid(n, e, "내경 높이 9mm") : fullIfSaid(n, e, "엘보캡"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "1호를 쓰시면 됩니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("PARTIAL_ANSWER");
        assertThat(view.draft()).isNotNull();
        assertThat(model.calls).isEqualTo(1);
        assertThat(view.openQuestions()).containsExactly("마감캡 끝이 뚫려 있는지", "엘보 구간에 쓸 사이즈");
        assertThat(view.answerBasisNote()).contains("일부 질문에만 답한 초안");
        assertThat(model.sawScope).contains("8.5mm 케이블에 맞는 호수\n")
                .contains("마감캡 끝이 뚫려 있는지 → 이번 답변에서 다루지 않습니다");
        // The check reads the partial draft against the same list (v10, 2026-11-02): silence on the open questions is
        // what the drafter was told, not a draft that answers nothing.
        assertThat(model.sawCheckScope).as("the claim check was told what the drafter was").isEqualTo(model.sawScope);
        assertThat(view.knowledgeGap().needs()).extracting(com.sellerops.inquiry.draft.dto.NeedCoverageView::status)
                .containsExactly("NONE", "FULL", "NONE");
        assertThat(view.knowledgeGap().askedSubject()).isEqualTo("마감캡 끝이 뚫려 있는지");
        CaseKnowledgeGap fromDraft = CaseKnowledgeGap.fromDraft(new CaseDraftPreparer.Prepared(false, null,
                view.knowledgeState(), 0, "NO_DRAFT", view.answerBasis(), view.knowledgeGap()));
        assertThat(fromDraft.needs()).hasSize(3);
        assertThat(CaseKnowledgeService.teachSubject(fromDraft))
                .isEqualTo("마감캡 끝이 뚫려 있는지 · 엘보 구간에 쓸 사이즈");
    }

    @Test
    @DisplayName("a need the evidence answers and the draft drops goes to the seller's list — the version is partial and its "
            + "send waits (2026-11-02)")
    void aDroppedNeedIsTheSellers() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 엘보 사이즈도요.");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                need("N2", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
        model.coveredAnswer = java.util.Map.of("N1", "ANSWERED", "N2", "NOT_COVERED");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).as("kept, not thrown away").isNotNull();
        assertThat(view.answerBasis()).isEqualTo("PARTIAL_ANSWER");
        assertThat(view.openQuestions()).containsExactly("엘보 구간에 쓸 사이즈 — 자료로 답할 수 있는데 초안이 다루지 않았어요");
        assertThat(model.sawScope).contains("- N1. 마감캡 끝이 뚫려 있는지").contains("- N2. 엘보 구간에 쓸 사이즈");
    }

    @Test
    @DisplayName("a need answered only in part and dropped by the draft is ONE question for the seller, told both — never two "
            + "rows for the same question (2026-11-06)")
    void aPartialNeedDroppedIsOneQuestion() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 엘보 사이즈도요.");
        assessor.setDecision(new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                need("N2", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> n.id().equals("N1") ? fullIfSaid(n, e, "내경 높이 9mm")
                        : new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                                com.sellerops.inquiry.decision.NeedStatus.PARTIAL,
                                e.stream().filter(c -> c.text().contains("3호부터")).map(
                                        com.sellerops.inquiry.decision.EvidenceCandidate::id).toList(),
                                List.of("엘보 규격"), List.of(), List.of(), null, List.of())), collector);
        StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
        model.coveredAnswer = java.util.Map.of("N1", "ANSWERED", "N2", "NOT_COVERED");

        GeneratedDraftView view = withQuestions(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.openQuestions()).containsExactly(
                "엘보 구간에 쓸 사이즈 — 엘보 규격 · 자료로 답할 수 있는데 초안이 다루지 않았어요");
        assertThat(questionsOf(work)).filteredOn(q -> "OPEN".equals(q.getState())).hasSize(1);
    }

    @Test
    @DisplayName("an item-by-item answer that is empty, leaves a need out or names one never sent is a check that did not "
            + "finish — no draft, not 「nothing dropped」 (2026-11-02 review)")
    void anIncompleteCoverageIsNoCheck() {
        for (java.util.Map<String, String> answer : List.of(java.util.Map.<String, String>of(),
                java.util.Map.of("N1", "ANSWERED"), java.util.Map.of("N1", "ANSWERED", "N2", "ANSWERED", "N9", "ANSWERED"),
                java.util.Map.of("N1", "ANSWERED", "N3", "ANSWERED"))) {
            InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 엘보 사이즈도요.");
            ScriptedDecision decision = new ScriptedDecision(List.of(
                    need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                    need("N2", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                    (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
            assessor.setDecision(decision, collector);
            StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
            model.coveredAnswer = answer;

            GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

            assertThat(view.draft()).as(answer.toString()).isNull();
            assertThat(view.unavailableMessage()).as(answer.toString()).contains("근거와 대조하지 못해");
        }
    }

    /**
     * K3 (2026-11-05 real-model eval, tenth review): after a kind correction the check answered N1 and left S1 out, and a
     * correct draft was not saved. An incomplete answer with nothing else found is asked once more — the same draft, need
     * list and evidence, never rewritten; a second incomplete answer still refuses; a finding in the first answer is never
     * asked away; and a recheck the ceiling refuses is not held, so no resume starts the count again.
     */
    @Test
    @DisplayName("an incomplete check is asked once more about the same draft — never a rewrite, never past a finding")
    void anIncompleteCheckIsAskedOnceMore() {
        java.util.Map<String, String> partial = java.util.Map.of("N1", "ANSWERED");
        java.util.Map<String, String> whole = java.util.Map.of("N1", "ANSWERED", "N2", "ANSWERED");
        record Case(String name, List<java.util.Map<String, String>> answers, boolean claim, boolean ceiling,
                    boolean saved, int checks, List<String> steps) {
        }
        for (Case c : List.of(
                new Case("second complete", List.of(partial, whole), false, false, true, 2,
                        List.of(AgentQuotaService.CHECK_STEP, InquiryDraftComposer.RECHECK_STEP)),
                new Case("second incomplete", List.of(partial, partial), false, false, false, 2,
                        List.of(AgentQuotaService.CHECK_STEP, InquiryDraftComposer.RECHECK_STEP)),
                new Case("a finding in the first answer", List.of(partial, whole), true, false, false, -1, null))) {
            InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 엘보 사이즈도요.");
            ScriptedDecision decision = new ScriptedDecision(List.of(
                    need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                    need("N2", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                    (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
            assessor.setDecision(decision, collector);
            StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있고, 엘보는 3호를 쓰시면 됩니다.");
            model.coveredAnswers.addAll(c.answers());
            if (c.claim()) {
                model.claimsAnswer = List.of(new AgentDraftGenerator.ClaimVerdict("엘보는 3호를 쓰시면 됩니다",
                        "NOT_SUPPORTED"));
            }
            java.util.List<String> steps = new java.util.ArrayList<>();
            InquiryDraftComposer composer = new InquiryDraftComposer(workItems, inquiries, draftService, evidenceRows,
                    retriever, assessor, model, new AgentQuotaService(null, null) {
                        @Override
                        public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String idempotencyKey) {
                            return new QuotaDecision(true, null, 0, 0);
                        }

                        @Override
                        public Charge chargeCheck(UUID orgId, AgentUsageKind kind, String step,
                                                  com.sellerops.agent.quota.AgentUsageActor actor) {
                            steps.add(step);
                            return InquiryDraftComposer.RECHECK_STEP.equals(step) && c.ceiling()
                                    ? new Charge(new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 9, 9), null)
                                    : super.chargeCheck(orgId, kind, step, actor);
                        }
                    }, variants, new DraftEvidenceSnippets(productChunks, orgChunks, memories),
                    new com.sellerops.product.detail.ProductDetailEnrichmentTrigger(null, null, null, null, List.of(),
                            false), null, null, null, candidates);
            composer.setPending(pendingDrafts);

            GeneratedDraftView view = composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

            if (c.claim()) {
                // Refused for what the first answer found, and rewritten for it as before — never asked again for it.
                assertThat(steps).as(c.name()).doesNotContain(InquiryDraftComposer.RECHECK_STEP);
            } else {
                assertThat(model.calls).as(c.name() + ": the draft is written once").isEqualTo(1);
                assertThat(model.checks).as(c.name() + ": checks").isEqualTo(c.checks());
                assertThat(steps).as(c.name() + ": metered").containsExactlyElementsOf(c.steps());
            }
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId()))
                    .as(c.name() + ": nothing held for a resume").isEmpty();
            if (c.saved()) {
                assertThat(view.draft()).as(c.name()).isNotNull();
                assertThat(view.draft().comments()).contains("엘보는 3호");
            } else {
                assertThat(view.draft()).as(c.name()).isNull();
                if (!c.claim()) {
                    assertThat(view.rewrite()).as(c.name() + ": no rewrite for a check").isNull();
                }
                assertThat(view.unavailableMessage()).as(c.name())
                        .contains(c.claim() ? "등록된 근거로 확인되지 않아" : "근거와 대조하지 못해");
            }
        }
    }

    /**
     * Eleventh review (2026-11-05): a recheck the ceiling stops is not a failed check. The draft is kept as 「추가 검사
     * 대기」; a resume runs only that one look — never a new draft, never the first check again — and a second resume
     * after it grants nothing. Counted by the calls made.
     */
    @Test
    @DisplayName("a recheck the ceiling stops is kept for later, and a resume runs that one look and nothing else")
    void aRecheckTheCeilingStopsIsKeptAndResumedAlone() {
        java.util.Map<String, String> partial = java.util.Map.of("N1", "ANSWERED");
        java.util.Map<String, String> whole = java.util.Map.of("N1", "ANSWERED", "N2", "ANSWERED");
        for (boolean completes : new boolean[] {true, false}) {
            InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 엘보 사이즈도요.");
            ScriptedDecision decision = new ScriptedDecision(List.of(
                    need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                    need("N2", "엘보 구간에 쓸 사이즈", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                    (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
            assessor.setDecision(decision, collector);
            StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있고, 엘보는 3호를 쓰시면 됩니다.");
            model.coveredAnswers.addAll(List.of(partial, completes ? whole : partial));
            boolean[] ceiling = {true};
            java.util.List<String> steps = new java.util.ArrayList<>();
            InquiryDraftComposer composer = new InquiryDraftComposer(workItems, inquiries, draftService, evidenceRows,
                    retriever, assessor, model, new AgentQuotaService(null, null) {
                        @Override
                        public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String idempotencyKey) {
                            return new QuotaDecision(true, null, 0, 0);
                        }

                        @Override
                        public Charge chargeCheck(UUID orgId, AgentUsageKind kind, String step,
                                                  com.sellerops.agent.quota.AgentUsageActor actor) {
                            steps.add(step);
                            return InquiryDraftComposer.RECHECK_STEP.equals(step) && ceiling[0]
                                    ? new Charge(new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 9, 9), null)
                                    : super.chargeCheck(orgId, kind, step, actor);
                        }
                    }, variants, new DraftEvidenceSnippets(productChunks, orgChunks, memories),
                    new com.sellerops.product.detail.ProductDetailEnrichmentTrigger(null, null, null, null, List.of(),
                            false), null, null, null, candidates);
            composer.setPending(pendingDrafts);

            GeneratedDraftView waiting = composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
            assertThat(waiting.draft()).isNull();
            assertThat(waiting.unavailableMessage()).contains("추가 검사 대기");
            assertThat(waiting.unavailableKind()).isEqualTo("WAITING_FOR_CHECK");
            assertThat(model.checks).as("the first check ran; the second was never called").isEqualTo(1);
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId())).get()
                    .satisfies(h -> assertThat(h.getAwaiting()).isEqualTo(
                            com.sellerops.inquiry.draft.DraftReviewPending.RECHECK))
                    .satisfies(h -> assertThat(h.getAttempt()).isEqualTo(1));

            // Still no room: nothing is called, the draft stays where it was.
            steps.clear();
            composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
            assertThat(steps).containsExactly(InquiryDraftComposer.RECHECK_STEP);
            assertThat(model.checks).isEqualTo(1);
            assertThat(model.calls).isEqualTo(1);
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId())).get()
                    .satisfies(h -> assertThat(h.getAwaiting()).isEqualTo(
                            com.sellerops.inquiry.draft.DraftReviewPending.RECHECK));

            ceiling[0] = false;
            steps.clear();
            GeneratedDraftView resumed = composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
            assertThat(steps).as("only the look that was left").containsExactly(InquiryDraftComposer.RECHECK_STEP);
            assertThat(model.checks).isEqualTo(2);
            assertThat(model.calls).as("never written again").isEqualTo(1);
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId())).isEmpty();
            if (completes) {
                assertThat(resumed.draft()).isNotNull();
                assertThat(resumed.draft().comments()).contains("엘보는 3호");
            } else {
                assertThat(resumed.draft()).isNull();
                assertThat(resumed.unavailableMessage()).contains("근거와 대조하지 못해");
                assertThat(resumed.rewrite()).isNull();
            }
        }
    }

    @Test
    @DisplayName("a need the drafter was told to leave to the seller may be named too — it was sent, and nothing is read "
            + "from it")
    void aLeftNeedMayBeNamed() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요? 색상은요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC),
                need("N2", "색상 종류", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> n.id().equals("N1") ? fullIfSaid(n, e, "내경 높이 9mm") : fullIfSaid(n, e, "없는 말"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
        model.coveredAnswer = java.util.Map.of("N1", "ANSWERED", "N2", "NOT_COVERED");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(model.sawScope).contains("색상 종류 → 이번 답변에서 다루지 않습니다");
        assertThat(view.openQuestions()).noneMatch(q -> q.contains("초안이 다루지 않았어요"));
    }

    @Test
    @DisplayName("every need taken up: nothing is added, and the reply stays complete")
    void nothingDropped() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
        model.coveredAnswer = java.util.Map.of("N1", "ANSWERED");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(view.openQuestions()).isEmpty();
    }

    @Test
    @DisplayName("when the inquiry arrived reaches the judge, and the same line reaches the drafter and the check (2026-11-02)")
    void theDayIsKnown() {
        InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");

        composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        String day = "2026년 9월 17일 목요일 오전 9시(한국 시간) · 평일(공휴일인지는 알 수 없음)";
        assertThat(decision.sawReceived).isEqualTo(day);
        assertThat(model.sawScope).contains("- 문의 받은 때: " + day).contains("고객에게 오늘이 무슨 요일인지 묻지 않습니다");
        assertThat(model.sawCheckScope).isEqualTo(model.sawScope);
    }

    @Test
    @DisplayName("the drafter and the check are told the channel's known answer limit; none is made up where the channel "
            + "states none (2026-11-02)")
    void theChannelsLimitIsTold() {
        com.sellerops.channel.Channel coupang = channels.findAll().stream().filter(c -> "GMARKET".equals(c.getCode()))
                .findFirst().orElseGet(() -> {
                    com.sellerops.channel.Channel c = new com.sellerops.channel.Channel();
                    c.setCode("GMARKET");
                    c.setNameKo("G마켓");
                    c.setStatus(com.sellerops.channel.ChannelStatus.AVAILABLE);
                    c.setSupportsInquiry(true);
                    c.setSortOrder(0);
                    return channels.save(c);
                });
        for (boolean known : List.of(true, false)) {
            InquiryWorkItem work = seedInquiry(null, "마감캡은 뚫려있나요?");
            if (known) {
                Inquiry q = inquiries.findById(work.getInquiryId()).orElseThrow();
                q.setChannelId(coupang.getId());
                inquiries.save(q);
            }
            ScriptedDecision decision = new ScriptedDecision(List.of(
                    need("N1", "마감캡 끝이 뚫려 있는지", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                    (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
            assessor.setDecision(decision, collector);
            StubModel model = StubModel.writing("안내", "마감캡 끝은 막혀 있습니다.");
            InquiryDraftComposer composer = composer(model);
            composer.setChannels(channels);

            composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

            if (known) {
                assertThat(model.sawScope).contains("- 답변 길이: 답변 본문은 1000바이트");
            } else {
                assertThat(model.sawScope).as("a channel with no stated limit").doesNotContain("답변 길이");
            }
            assertThat(model.sawCheckScope).isEqualTo(model.sawScope);
        }
    }

    @Test
    @DisplayName("a check that never answered is not a number problem — the seller is told the check did not run")
    void anUnansweredCheckBlamesNoNumber() {
        InquiryWorkItem work = seedInquiry(null, "마감캡 내경이 몇 mm인가요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(
                need("N1", "마감캡 내경", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> fullIfSaid(n, e, "내경 높이 9mm"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "내경 높이는 9mm입니다.");
        model.figureCheck = figures -> Optional.empty();

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(model.sawFigures).as("there was a number to check").isNotEmpty();
        assertThat(view.draft()).isNull();
        assertThat(view.unavailableMessage()).contains("근거와 대조하지 못해").doesNotContain("숫자");
    }

    @Test
    @DisplayName("stored catalogue is evidence: an option list answers 「우드 색상 있나요」, and the drafter is shown it")
    void catalogueIsEvidence() {
        seedOption("색상: 우드 / 사이즈: 1호", "SELLING");
        seedOption("색상: 화이트 / 사이즈: 1호", "SUSPENDED");
        InquiryWorkItem work = seedInquiry(null, "우드톤 색상도 있나요?");
        ScriptedDecision decision = new ScriptedDecision(
                List.of(need("N1", "우드톤 색상이 있는지", com.sellerops.inquiry.decision.NeedType.CATALOGUE_AVAILABILITY)),
                (n, e) -> fullIfSaid(n, e, "우드"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "우드 색상도 판매 중입니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(decision.shown).extracting(com.sellerops.inquiry.decision.EvidenceCandidate::label)
                .contains("옵션 목록");
        assertThat(decision.shown).filteredOn(c -> c.label().equals("옵션 목록")).singleElement()
                .satisfies(c -> assertThat(c.text()).contains("화이트 / 사이즈: 1호 (판매 중지)"));
        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(model.sawKnowledge).extracting(AgentDraftGenerator.Passage::heading).containsExactly("옵션 목록");
        assertThat(model.sawScope).contains("우드톤 색상이 있는지");
    }

    @Test
    @DisplayName("2026-10-31 shape — a draft written from the decision's evidence cites exactly that evidence and says GROUNDED")
    void aDraftCitesWhatItsWriterWasShown() {
        // A company rule the question shares no word with: the lexical lanes miss it, the decision's whole-library
        // collection does not. Before the fix the draft was written from the rule and cited nothing, state NO_MATCH.
        candidates.teach(org, "ORG", null, "교환 기한", null,
                "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환과 반품이 가능합니다.", OrgKnowledgeType.EXCHANGE_REFUND_POLICY,
                user, "판매자");
        InquiryWorkItem work = seedInquiry("문의", "뜯지 않았으면 언제까지 바꿔 주시나요?");
        ScriptedDecision decision = new ScriptedDecision(
                List.of(need("N1", "미개봉일 때 바꿀 수 있는 기한", com.sellerops.inquiry.decision.NeedType.POLICY)),
                (n, e) -> fullIfSaid(n, e, "7일 이내"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "받으신 날부터 7일 안에, 개봉하지 않은 상품이면 교환하실 수 있어요.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(view.knowledgeState()).isEqualTo("GROUNDED");
        assertThat(view.knowledgeNote()).as("the sentence above the draft names what it cites")
                .isEqualTo("판매자가 등록한 운영 정책을 근거로 썼어요.");
        List<String> shown = model.sawKnowledge.stream().map(AgentDraftGenerator.Passage::heading).toList();
        assertThat(shown).isNotEmpty();
        List<com.sellerops.inquiry.draft.InquiryDraftEvidence> rows =
                evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), view.draft().version());
        assertThat(rows).extracting(com.sellerops.inquiry.draft.InquiryDraftEvidence::getTitle)
                .as("the citations are what the writer was shown, in its order").containsExactlyElementsOf(shown);
        assertThat(rows).filteredOn(r -> r.getKind().equals(com.sellerops.inquiry.draft.InquiryDraftEvidence.KIND_ORG_POLICY))
                .singleElement().satisfies(r -> {
                    assertThat(r.getChunkId()).as("the rule's passage, so its excerpt can be read back").isNotNull();
                    assertThat(orgChunks.findById(r.getChunkId())).get()
                            .satisfies(c -> assertThat(c.getContent()).contains("7일 이내"));
                });
        assertThat(view.evidence()).extracting(com.sellerops.inquiry.draft.dto.DraftEvidenceView::title)
                .containsExactlyElementsOf(shown);
    }

    @Test
    @DisplayName("S X6a shape — fully answered: GROUNDED, and the rule-based 규격 line still reaches the drafter")
    void fullIsNotClarified() {
        seedOption("사이즈: 1호", "SELLING");
        seedOption("사이즈: 3호", "SELLING");
        InquiryWorkItem work = seedInquiry(null, "지름 10mm 선 두 줄 넣으려면 몇 호가 맞나요?");
        ScriptedDecision decision = new ScriptedDecision(
                List.of(need("N1", "지름 10mm 두 줄에 맞는 호수", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> fullIfSaid(n, e, "3호부터"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "3호부터 가능합니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        // A GROUNDED decision does not silence the 규격 caution: the real judge was measured calling spec-dependent
        // answers FULL (apr-c8715d20), so the drafter is still told when the listing's 규격 is undetermined.
        assertThat(model.sawSpec).isEqualTo(view.knowledgeGap().applicability() == null ? model.sawSpec
                : com.sellerops.inquiry.draft.SpecApplicability.Applicability.valueOf(view.knowledgeGap().applicability())
                        .messageKo(true, false));
        assertThat(view.knowledgeGap().applicability()).isEqualTo("VARIANT_UNRESOLVED");
    }

    @Test
    @DisplayName("the Claim Guard still stands behind the gate: a GROUNDED decision with an invented promise is refused")
    void claimGuardStillRuns() {
        InquiryWorkItem work = seedInquiry(null, "지름 10mm 선 두 줄 넣으려면 몇 호가 맞나요?");
        ScriptedDecision decision = new ScriptedDecision(
                List.of(need("N1", "지름 10mm 두 줄에 맞는 호수", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> fullIfSaid(n, e, "3호부터"));
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "3호부터 가능합니다. 사진을 보내주시면 확인 후 안내드리겠습니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(view.draft()).isNull();
        assertThat(view.unavailableMessage()).startsWith("근거에 없는 약속이나 요청");
    }

    private StubModel rewriting(String first, String second) {
        StubModel model = StubModel.writing("안내", first);
        model.then.add(new AgentDraftResponseParser.ParsedDraft("general_reply", "안내", second));
        return model;
    }

    private InquiryWorkItem groundedOn3ho() {
        InquiryWorkItem work = seedInquiry(null, "지름 10mm 선 두 줄 넣으려면 몇 호가 맞나요?");
        assessor.setDecision(new ScriptedDecision(
                List.of(need("N1", "지름 10mm 두 줄에 맞는 호수", com.sellerops.inquiry.decision.NeedType.PRODUCT_COMPATIBILITY)),
                (n, e) -> fullIfSaid(n, e, "3호부터")), collector);
        return work;
    }

    /**
     * One rewrite (2026-11-02, fourth review): a refusal a sentence can fix is written once more from the same evidence and
     * scope, told what was wrong, and checked again in full; the check reads the scope the first draft had.
     */
    @Test
    @DisplayName("a follow-up promise is written once more, told what was wrong, and saved only through every check")
    void aFixableRefusalIsWrittenOnceMore() {
        InquiryWorkItem work = groundedOn3ho();
        StubModel model = rewriting("3호부터 가능합니다. 사진을 보내주시면 확인 후 안내드리겠습니다.", "3호부터 가능합니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).isEqualTo("3호부터 가능합니다.");
        assertThat(view.rewrite()).isEqualTo("PASSED");
        assertThat(model.calls).isEqualTo(2);
        assertThat(model.sawScopes.get(0)).doesNotContain("다시 쓰기");
        assertThat(model.sawScopes.get(1)).startsWith(model.sawScopes.get(0)).contains("- 다시 쓰기:")
                .contains("후속 약속").contains("확인후안내").contains("같은 근거, 같은 답변 범위");
        assertThat(model.checks).as("the rewrite is checked in full").isEqualTo(1);
        assertThat(model.sawCheckScope).as("the check reads the first scope").isEqualTo(model.sawScopes.get(0));
    }

    @Test
    @DisplayName("a statement the check did not confirm is written once more; a second refusal is the seller's, once")
    void anUnconfirmedStatementIsWrittenOnceMore() {
        InquiryWorkItem work = groundedOn3ho();
        StubModel model = rewriting("3호부터 가능합니다. 나머지는 판매자가 별도로 알려 드립니다.", "3호부터 가능합니다.");
        model.claimsFor = d -> d.contains("별도로") ? List.of(new AgentDraftGenerator.ClaimVerdict(
                "나머지는 판매자가 별도로 알려 드립니다", "NOT_SUPPORTED")) : List.of();

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(model.calls).isEqualTo(2);
        assertThat(model.checks).isEqualTo(2);
        assertThat(model.sawScopes.get(1)).contains("「나머지는 판매자가 별도로 알려 드립니다」은(는) 보여 드린 근거로 확인되지 않습니다");

        StubModel twice = rewriting("3호부터 가능합니다. 나머지는 판매자가 별도로 알려 드립니다.",
                "3호부터 가능합니다. 나머지는 판매자가 별도로 알려 드립니다.");
        twice.claimsFor = model.claimsFor;
        GeneratedDraftView refused = composer(twice).generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(refused.draft()).isNull();
        assertThat(refused.unavailableMessage()).contains("등록된 근거로 확인되지 않아");
        assertThat(twice.calls).as("once more, never twice").isEqualTo(2);
        assertThat(refused.rewrite()).isEqualTo("REFUSED");
    }

    /**
     * A held rewrite is still the rewrite (2026-11-02, fifth review, reproduced): first refused → rewritten → its check
     * out of budget → held; resumed → refused. The resume wrote a third draft and the hold read as 「다시 쓴 뒤 거절」.
     */
    @Test
    @DisplayName("a rewrite held for its check resumes as the rewrite: checked, never written a third time, PENDING not REFUSED")
    void aHeldRewriteResumesAsTheRewrite() {
        for (boolean passes : new boolean[] {false, true}) {
            InquiryWorkItem work = groundedOn3ho();
            StubModel model = rewriting("3호부터 가능합니다. 나머지는 판매자가 별도로 알려 드립니다.",
                    passes ? "3호부터 가능합니다." : "3호부터 가능합니다. 나머지는 판매자가 별도로 알려 드립니다.");
            model.claimsFor = d -> d.contains("별도로") ? List.of(new AgentDraftGenerator.ClaimVerdict(
                    "나머지는 판매자가 별도로 알려 드립니다", "NOT_SUPPORTED")) : List.of();
            boolean[] ceiling = {true};
            java.util.List<String> steps = new java.util.ArrayList<>();
            InquiryDraftComposer composer = new InquiryDraftComposer(workItems, inquiries, draftService, evidenceRows,
                    retriever, assessor, model, new AgentQuotaService(null, null) {
                        @Override
                        public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String idempotencyKey) {
                            return new QuotaDecision(true, null, 0, 0);
                        }

                        @Override
                        public Charge chargeCheck(UUID orgId, AgentUsageKind kind, String step,
                                                  com.sellerops.agent.quota.AgentUsageActor actor) {
                            steps.add(step);
                            return InquiryDraftComposer.REDRAFT_CHECK_STEP.equals(step) && ceiling[0]
                                    ? new Charge(new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 9, 9), null)
                                    : super.chargeCheck(orgId, kind, step, actor);
                        }
                    }, variants, new DraftEvidenceSnippets(productChunks, orgChunks, memories),
                    new com.sellerops.product.detail.ProductDetailEnrichmentTrigger(null, null, null, null, List.of(),
                            false), null, null, null, candidates);
            composer.setPending(pendingDrafts);

            GeneratedDraftView waiting = composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
            assertThat(waiting.draft()).isNull();
            assertThat(waiting.unavailableMessage()).contains("검토 대기");
            assertThat(waiting.rewrite()).as("waiting for the check's budget is not a refusal").isEqualTo("PENDING");
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId())).get()
                    .satisfies(h -> assertThat(h.getAttempt()).as("held as the rewrite").isEqualTo(2))
                    .satisfies(h -> assertThat(h.getRewriteBecause()).isEqualTo("UNSUPPORTED_STATEMENT"));
            assertThat(model.calls).isEqualTo(2);

            ceiling[0] = false;
            steps.clear();
            GeneratedDraftView resumed = composer.generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
            assertThat(model.calls).as("resumed: the held rewrite is checked, nothing written again").isEqualTo(2);
            assertThat(steps).as("checked as the rewrite, never rewritten").containsExactly(
                    InquiryDraftComposer.REDRAFT_CHECK_STEP);
            assertThat(pendingDrafts.findByOrgIdAndWorkItemId(org, work.getId())).isEmpty();
            if (passes) {
                assertThat(resumed.draft()).isNotNull();
                assertThat(resumed.draft().comments()).isEqualTo("3호부터 가능합니다.");
                assertThat(resumed.rewrite()).as("still the rewrite after the resume").isEqualTo("PASSED");
            } else {
                assertThat(resumed.draft()).isNull();
                assertThat(resumed.unavailableMessage()).contains("등록된 근거로 확인되지 않아");
                assertThat(resumed.rewrite()).isEqualTo("REFUSED");
            }
        }
    }

    /**
     * The same words written by two runs are two versions (2026-11-02 checkpoint, reproduced on PostgreSQL): the second
     * reused the head and wrote its evidence into the head's slots — HTTP 500 there, two rows in one slot here before the
     * entity declared the production index. Each run's evidence is bound to its own version; the first is untouched.
     */
    @Test
    @DisplayName("the same words written twice are two versions, each with only its own evidence")
    void theSameWordsWrittenTwiceAreTwoVersions() {
        InquiryWorkItem work = groundedOn3ho();
        GeneratedDraftView first = composer(StubModel.writing("안내", "3호부터 가능합니다."))
                .generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
        em.flush();
        List<UUID> firstRows = evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(),
                first.draft().version()).stream().map(com.sellerops.inquiry.draft.InquiryDraftEvidence::getId).toList();
        assertThat(firstRows).as("the first run cites what it was shown").isNotEmpty();

        GeneratedDraftView second = composer(StubModel.writing("안내", "3호부터 가능합니다."))
                .generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
        em.flush();

        assertThat(second.draft().comments()).isEqualTo(first.draft().comments());
        assertThat(second.draft().version()).as("a run of its own, never folded into the head")
                .isEqualTo(first.draft().version() + 1);
        assertThat(evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), first.draft().version()))
                .extracting(com.sellerops.inquiry.draft.InquiryDraftEvidence::getId).as("the first version's evidence untouched")
                .containsExactlyElementsOf(firstRows);
        assertThat(evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), second.draft().version()))
                .as("the second run's own").hasSameSizeAs(firstRows);
    }

    // ── The seller's record (2026-11-02 seller-check review) ──────────────────────────────────────────────────────

    /** A refund request the store cannot act on or read: closed only by what the seller found on the seller centre. */
    private InquiryWorkItem refundRequest() {
        InquiryWorkItem work = seedInquiry(null, "어제 주문했는데 사이즈를 잘못 골랐어요. 환불해 주세요.");
        assessor.setDecision(new ScriptedDecision(
                List.of(need("N1", "이 주문을 환불해 달라는 요청", com.sellerops.inquiry.decision.NeedType.ORDER_ACTION)),
                (n, e) -> fullIfSaid(n, e, "판매자센터에서 직접 확인")), collector);
        return work;
    }

    @Test
    @DisplayName("a blank record is refused — no outcome, or no sentence — and nothing is kept")
    void aBlankRecordIsRefused() {
        InquiryWorkItem work = refundRequest();
        assertThatThrownBy(() -> sellerChecks.record(org, work.getId(), null, "카드 취소로 환불", "SELLER:x"))
                .isInstanceOf(com.sellerops.common.ApiException.class).hasMessageContaining("골라");
        assertThatThrownBy(() -> sellerChecks.record(org, work.getId(), "DONE", "   ", "SELLER:x"))
                .isInstanceOf(com.sellerops.common.ApiException.class).hasMessageContaining("비워 두면");
        assertThatThrownBy(() -> sellerChecks.record(org, work.getId(), "FINISHED", "카드 취소로 환불", "SELLER:x"))
                .isInstanceOf(com.sellerops.common.ApiException.class);
        assertThat(sellerCheckRows.findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(org, work.getId())).isEmpty();
    }

    @Test
    @DisplayName("without the record the order action has no basis; with it the reply is written from it, cites it, and "
            + "nothing reaches the store's knowledge")
    void theSellersRecordAnswersTheOrderAction() {
        InquiryWorkItem work = refundRequest();
        GeneratedDraftView before = composer(StubModel.writing("안내", "환불 처리했습니다."))
                .generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(before.draft()).as("nothing the seller said yet").isNull();
        assertThat(before.answerBasis()).isEqualTo("NO_ANSWER_BASIS");
        assertThat(before.sellerCheckAsk()).as("the screen asks for the seller centre").isEqualTo("ORDER_ACTION");
        em.flush();
        assertThat(sellerChecks.ask(org, work.getId())).as("kept for a reload").isEqualTo("ORDER_ACTION");
        long policies = orgSources.count();
        long knowledge = productSources.count();
        long memoryRows = memories.count();

        com.sellerops.inquiry.sellercheck.InquirySellerCheckService.SellerCheckView recorded =
                sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user);
        GeneratedDraftView after = composer(StubModel.writing("안내", "문의하신 주문은 카드 결제 취소로 환불 처리했습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();

        assertThat(after.answerBasis()).isEqualTo("GROUNDED");
        assertThat(after.sellerCheckAsk()).as("answered by the record: nothing left to check").isNull();
        assertThat(sellerChecks.ask(org, work.getId())).isNull();
        assertThat(after.draft()).isNotNull();
        List<com.sellerops.inquiry.draft.InquiryDraftEvidence> cited = evidenceRows
                .findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), after.draft().version());
        assertThat(cited).filteredOn(r -> "SELLER_CHECK".equals(r.getKind())).singleElement()
                .satisfies(r -> {
                    assertThat(r.getSourceId()).isEqualTo(recorded.id());
                    assertThat(r.getLocator()).isEqualTo("seller-check/DONE");
                });
        assertThat(after.evidence()).anySatisfy(v -> assertThat(v.scopeLabel()).isEqualTo("판매자 확인"));
        assertThat(orgSources.count()).as("not a store policy").isEqualTo(policies);
        assertThat(productSources.count()).as("not product knowledge").isEqualTo(knowledge);
        assertThat(memories.count()).as("not a past answer").isEqualTo(memoryRows);
    }

    @Test
    @DisplayName("recorded as under way, a reply that calls it finished is written once more, and refused if it says so twice")
    void underWayIsNeverCalledFinished() {
        InquiryWorkItem work = refundRequest();
        sellerChecks.record(org, work.getId(), "IN_PROGRESS", "반품 상품 도착 확인, 환불 대기", "SELLER:" + user, soon());

        StubModel fixed = rewriting("요청하신 환불은 처리했습니다.", "요청하신 환불은 접수되었고, 현재 반품 상품 도착 확인 후 환불 대기 상태입니다.");
        GeneratedDraftView view = composer(fixed).generateAs(org, work.getId(), "SELLER:" + user);
        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).doesNotContain("처리했습니다");
        assertThat(fixed.sawScopes.get(1)).contains("「접수·진행 중」").contains("끝난 일로 쓰지 않고");

        StubModel twice = rewriting("요청하신 환불은 처리했습니다.", "환불되었습니다.");
        GeneratedDraftView refused = composer(twice).generateAs(org, work.getId(), "SELLER:" + user);
        assertThat(refused.draft()).isNull();
        assertThat(refused.unavailableMessage()).startsWith("판매자가 기록한 결과는 아직 끝난 일이 아닌데");
        assertThat(InquiryDraftComposer.unavailableKind(refused.unavailableMessage())).isEqualTo("HELD_BY_CHECK");
    }

    @Test
    @DisplayName("a correction is a new record and a new version: the old record is kept closed, each version cites its own")
    void aCorrectionIsANewRecordAndANewVersion() {
        InquiryWorkItem work = refundRequest();
        UUID first = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, soon()).id();
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        UUID second = sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user).id();
        GeneratedDraftView v2 = composer(StubModel.writing("안내", "요청하신 환불은 카드 결제 취소로 처리했습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();

        assertThat(v2.draft().version()).isEqualTo(v1.draft().version() + 1);
        assertThat(sellerCheckRows.findById(first)).get().satisfies(c -> assertThat(c.getSupersededAt()).isNotNull());
        assertThat(sellerChecks.currentView(org, work.getId())).get().satisfies(c -> assertThat(c.id()).isEqualTo(second));
        assertThat(evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), v1.draft().version()))
                .filteredOn(r -> "SELLER_CHECK".equals(r.getKind())).extracting(r -> r.getSourceId()).containsExactly(first);
        assertThat(evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), v2.draft().version()))
                .filteredOn(r -> "SELLER_CHECK".equals(r.getKind())).extracting(r -> r.getSourceId()).containsExactly(second);
    }

    // ── The record against the reply that would be sent (2026-11-02 fourth seller-check review) ─────────────────────

    private com.sellerops.inquiry.draft.InquiryDraftRecheck recheck() {
        DraftEvidenceSnippets snippets = new DraftEvidenceSnippets(productChunks, orgChunks, memories);
        snippets.setSellerChecks(sellerCheckRows);
        com.sellerops.inquiry.draft.InquiryDraftRecheck r = new com.sellerops.inquiry.draft.InquiryDraftRecheck(inquiries,
                evidenceRows, snippets, orgSources, productSources,
                new com.sellerops.knowledge.style.AnswerStyleService(styleRows));
        r.setSellerChecks(sellerCheckRows);
        return r;
    }

    private com.sellerops.inquiry.draft.InquiryDraftRecheck.Result checkVersion(InquiryWorkItem work, int version) {
        com.sellerops.inquiry.reply.InquiryReplyDraft v = draftRows.findByWorkItemIdAndVersion(work.getId(), version)
                .orElseThrow();
        com.sellerops.inquiry.draft.InquiryDraftRecheck r = recheck();
        return r.check(workItems.findById(work.getId()).orElseThrow(), v.getVersion(), v.getComments(),
                v.getAuthorKind(), r.writtenFrom(v));
    }

    @Test
    @DisplayName("written from 「처리 완료」, the record corrected to 「진행 중」 and no new draft: the old reply is held, and the "
            + "finished sentence is a fix, not something to confirm")
    void aDraftFromAnEarlierRecordIsHeld() {
        InquiryWorkItem work = refundRequest();
        sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user);
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "문의하신 주문은 카드 결제 취소로 환불 처리했습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        assertThat(checkVersion(work, v1.draft().version()).codes())
                .as("written from the record that stands: the record backs the refund it states").isEmpty();

        // Corrected; drafting from the new record then fails, so the old reply is still the head.
        sellerChecks.record(org, work.getId(), "IN_PROGRESS", "반품 상품 도착 확인 전, 환불 대기", "SELLER:" + user, soon());
        em.flush();
        com.sellerops.inquiry.draft.InquiryDraftRecheck.Result found = checkVersion(work, v1.draft().version());
        assertThat(found.codes()).contains("SELLER_CHECK_CHANGED", "SELLER_CHECK_COMPLETION");
        assertThat(found.sellerCheck().was()).isEqualTo("DONE");
        assertThat(found.sellerCheck().now()).isEqualTo("IN_PROGRESS");
        assertThat(found.sellerCheck().completions()).isNotEmpty();
        // The version names the record its writer read — however late it was saved, that is what it stands on.
        com.sellerops.inquiry.reply.InquiryReplyDraft v = draftRows.findByWorkItemIdAndVersion(work.getId(),
                v1.draft().version()).orElseThrow();
        assertThat(v.getSellerCheckId()).as("named on the version").isEqualTo(
                sellerCheckRows.findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(org, work.getId()).get(0).getId());
        assertThat(recheck().check(workItems.findById(work.getId()).orElseThrow(), v.getVersion(), "환불 요청을 확인하고 있어요.",
                "MODEL", v.getSellerCheckId()).codes())
                .as("a version stands only on the record it names, whenever it was saved")
                .contains("SELLER_CHECK_CHANGED").doesNotContain("SELLER_CHECK_COMPLETION");
    }

    @Test
    @DisplayName("a reply written before the record was kept is held once it is; the seller's own edit after it stands on it, "
            + "and still may not call the refund finished")
    void aReplyWrittenBeforeTheRecord() {
        InquiryWorkItem work = refundRequest();
        int before = draftService.save(org, work.getId(), user, "안내", "환불 요청 확인했습니다. 곧 처리해 드릴게요.", 0).version();
        em.flush();
        assertThat(checkVersion(work, before).sellerCheck()).as("no record yet: nothing to read").isNull();

        UUID shown = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "반품 상품 도착 확인 전, 환불 대기",
                "SELLER:" + user, soon()).id();
        em.flush();
        com.sellerops.inquiry.draft.InquiryDraftRecheck.Result held = checkVersion(work, before);
        assertThat(held.codes()).contains("SELLER_CHECK_CHANGED");
        assertThat(held.sellerCheck().was()).as("written before any record").isNull();

        int edited = draftService.save(org, work.getId(), user, "안내", "요청하신 환불은 접수되어 진행 중입니다.", before,
                com.sellerops.inquiry.reply.InquiryReplyDraftService.Provenance.seller(shown)).version();
        em.flush();
        assertThat(checkVersion(work, edited).codes()).as("the seller wrote it with the record in front of them")
                .isEmpty();

        int claimed = draftService.save(org, work.getId(), user, "안내", "요청하신 환불은 처리했습니다.", edited,
                com.sellerops.inquiry.reply.InquiryReplyDraftService.Provenance.seller(shown)).version();
        em.flush();
        assertThat(checkVersion(work, claimed).codes()).contains("SELLER_CHECK_COMPLETION")
                .doesNotContain("SELLER_CHECK_CHANGED");
    }

    @Test
    @DisplayName("a draft left open on the PC and saved after the phone corrected the record is held: a later save time "
            + "is not having seen the record")
    void aLaterSaveIsNotHavingSeenTheRecord() {
        InquiryWorkItem work = refundRequest();
        UUID onThePc = sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user).id();
        em.flush();
        // The phone corrects the record while the PC still shows the first one.
        sellerChecks.record(org, work.getId(), "IN_PROGRESS", "반품 상품 회수 대기", "SELLER:" + user, onThePc, soon());
        em.flush();
        int saved = draftService.save(org, work.getId(), user, "안내", "요청하신 환불 건은 확인해 두었습니다.", 0,
                com.sellerops.inquiry.reply.InquiryReplyDraftService.Provenance.seller(onThePc)).version();
        em.flush();
        com.sellerops.inquiry.draft.InquiryDraftRecheck.Result found = checkVersion(work, saved);
        assertThat(found.codes()).as("no finished phrase to catch — the record it names is the old one")
                .contains("SELLER_CHECK_CHANGED").doesNotContain("SELLER_CHECK_COMPLETION");
        assertThat(found.sellerCheck().was()).isEqualTo("DONE");
        assertThat(found.sellerCheck().now()).isEqualTo("IN_PROGRESS");

        // The same words saved again with the record that stands in front of the seller: a new version, and it stands.
        UUID now = sellerChecks.currentView(org, work.getId()).orElseThrow().id();
        int again = draftService.save(org, work.getId(), user, "안내", "요청하신 환불 건은 확인해 두었습니다.", saved,
                com.sellerops.inquiry.reply.InquiryReplyDraftService.Provenance.seller(now)).version();
        em.flush();
        assertThat(again).isEqualTo(saved + 1);
        assertThat(checkVersion(work, again).codes()).doesNotContain("SELLER_CHECK_CHANGED");
    }

    @Test
    @DisplayName("the old record's citation reads back as the earlier record, never as what the seller says now")
    void anEarlierRecordReadsBackAsEarlier() {
        InquiryWorkItem work = refundRequest();
        UUID first = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, soon()).id();
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        DraftEvidenceSnippets snippets = new DraftEvidenceSnippets(productChunks, orgChunks, memories);
        snippets.setSellerChecks(sellerCheckRows);
        com.sellerops.inquiry.draft.InquiryDraftEvidence row = evidenceRows
                .findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(), v1.draft().version()).stream()
                .filter(r -> "SELLER_CHECK".equals(r.getKind())).findFirst().orElseThrow();
        assertThat(snippets.viewOf(row).snippet()).contains("접수·진행 중").doesNotContain("이전 기록");
        assertThat(snippets.viewOf(row).gone()).isFalse();

        sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user);
        em.flush();
        em.clear();
        assertThat(row.getSourceId()).isEqualTo(first);
        assertThat(snippets.viewOf(row).snippet()).startsWith("(이후 고친 이전 기록)").contains("접수·진행 중");
    }

    @Test
    @DisplayName("a record sent from a screen that showed another one is refused, and the same press twice is one record")
    void aStaleOrRepeatedPressIsOneRecord() {
        InquiryWorkItem work = refundRequest();
        UUID first = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, null, soon()).id();
        em.flush();
        assertThatThrownBy(() -> sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, null, soon()))
                .as("the same first press again").isInstanceOf(com.sellerops.common.ApiException.class)
                .hasMessageContaining("먼저 저장");
        UUID second = sellerChecks.record(org, work.getId(), "DONE", "환불 처리", "SELLER:" + user, first).id();
        em.flush();
        assertThatThrownBy(() -> sellerChecks.record(org, work.getId(), "CANNOT", "처리 불가", "SELLER:" + user, first))
                .as("the phone still showing the first record").isInstanceOf(com.sellerops.common.ApiException.class);
        assertThat(sellerCheckRows.findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(org, work.getId()))
                .extracting(c -> c.getId()).containsExactly(first, second);
        assertThat(sellerChecks.currentView(org, work.getId())).get().satisfies(c -> assertThat(c.outcome()).isEqualTo("DONE"));
    }

    @Test
    @DisplayName("drafting fails after the record is kept: saved, and said to be not drafted; a retry drafts from the record "
            + "shown and writes none")
    void savedIsNotDrafted() {
        InquiryWorkItem work = refundRequest();
        InquiryDraftComposer failing = mock(InquiryDraftComposer.class);
        when(failing.generate(org, work.getId(), user)).thenThrow(new IllegalStateException("model down"));
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController controller =
                controllerWith(failing);
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");

        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse saved = controller.record(me,
                work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordRequest(
                        "IN_PROGRESS", "환불 대기", null, soon()));
        em.flush();
        assertThat(saved.draftState()).isEqualTo("FAILED");
        assertThat(saved.draftFailure()).as("a fixed code, not the exception's name").isEqualTo("DRAFT_ERROR");
        assertThat(saved.draft()).isNull();
        assertThat(saved.check().outcome()).isEqualTo("IN_PROGRESS");
        assertThat(controller.current(me, work.getId()).draftMatches())
                .as("a reload still offers the way back: no answer is written from this record").isFalse();

        InquiryDraftComposer working = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."));
        InquiryDraftComposer delegate = mock(InquiryDraftComposer.class);
        when(delegate.generate(org, work.getId(), user))
                .thenAnswer(i -> working.generateAs(org, work.getId(), "SELLER:" + user));
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController retry =
                controllerWith(delegate);
        assertThatThrownBy(() -> retry.redraft(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RedraftRequest(UUID.randomUUID())))
                .as("not the record the screen showed").isInstanceOf(com.sellerops.common.ApiException.class);
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse redrafted = retry.redraft(me,
                work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RedraftRequest(
                        saved.check().id()));
        em.flush();
        assertThat(redrafted.draftState()).isEqualTo("READY");
        assertThat(redrafted.check().id()).isEqualTo(saved.check().id());
        assertThat(sellerCheckRows.findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(org, work.getId())).as("no second record")
                .hasSize(1);
        assertThat(checkVersion(work, redrafted.draft().draft().version()).codes()).isEmpty();
        assertThat(retry.current(me, work.getId()).draftMatches()).isTrue();
    }

    // ── An inquiry the automatic proposal never reached (2026-11-06 seventeenth review, FINDINGS §B106) ───────────────

    private InquiryWorkItem openRefundRequest() {
        InquiryWorkItem work = refundRequest();
        work.setPhase(InquiryWorkItemPhase.OPEN);
        return workItems.saveAndFlush(work);
    }

    /** The proposal step as the real one moves it — OPEN to PROPOSED — without its fifteen collaborators. */
    private com.sellerops.inquiry.proposal.InquiryProposalService proposingTo(InquiryWorkItemPhase to) {
        com.sellerops.inquiry.proposal.InquiryProposalService p =
                mock(com.sellerops.inquiry.proposal.InquiryProposalService.class);
        when(p.propose(eq(org), any(), eq(user))).thenAnswer(i -> {
                    InquiryWorkItem w = workItems.findById(i.getArgument(1)).orElseThrow();
                    w.setPhase(to);
                    workItems.saveAndFlush(w);
                    return null;
                });
        return p;
    }

    @Test
    @DisplayName("a record on an OPEN inquiry is prepared on the server first, then drafted — the screen's own proposal "
            + "step is no longer what the save depends on")
    void anOpenInquiryIsPreparedOnTheServer() {
        InquiryWorkItem work = openRefundRequest();
        InquiryDraftComposer working = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."));
        InquiryDraftComposer delegate = mock(InquiryDraftComposer.class);
        when(delegate.generate(org, work.getId(), user))
                .thenAnswer(i -> working.generateAs(org, work.getId(), "SELLER:" + user));
        com.sellerops.inquiry.proposal.InquiryProposalService proposals = proposingTo(InquiryWorkItemPhase.PROPOSED);
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController controller = controllerWith(delegate, proposals);
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");

        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse saved = controller.record(me,
                work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordRequest(
                        "IN_PROGRESS", "환불 대기", null, soon()));
        em.flush();

        var order = inOrder(proposals, delegate);
        order.verify(proposals).propose(org, work.getId(), user);
        order.verify(delegate).generate(org, work.getId(), user);
        assertThat(saved.draftState()).isEqualTo("READY");
        assertThat(saved.draftFailure()).isNull();
        assertThat(saved.draft().draft()).isNotNull();
        assertThat(workItems.findById(work.getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.PROPOSED);
    }

    @Test
    @DisplayName("when the preparation fails or leaves the inquiry where no draft can be saved, the record is kept, the "
            + "model is never asked, and the reason is a fixed code")
    void anUnpreparedInquiryStopsBeforeTheModel() {
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");

        InquiryWorkItem failing = openRefundRequest();
        com.sellerops.inquiry.proposal.InquiryProposalService broken =
                mock(com.sellerops.inquiry.proposal.InquiryProposalService.class);
        when(broken.propose(org, failing.getId(), user)).thenThrow(new IllegalStateException("provider down"));
        InquiryDraftComposer never = mock(InquiryDraftComposer.class);
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse a = controllerWith(never, broken)
                .record(me, failing.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                        .RecordRequest("IN_PROGRESS", "환불 대기", null, soon()));
        em.flush();
        assertThat(a.draftState()).isEqualTo("FAILED");
        assertThat(a.draftFailure()).isEqualTo("PREPARE_FAILED");
        assertThat(a.check().outcome()).as("the record is kept").isEqualTo("IN_PROGRESS");
        assertThat(workItems.findById(failing.getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.OPEN);

        InquiryWorkItem elsewhere = openRefundRequest();
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse b =
                controllerWith(never, proposingTo(InquiryWorkItemPhase.DISMISSED))
                        .record(me, elsewhere.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                                .RecordRequest("IN_PROGRESS", "환불 대기", null, soon()));
        assertThat(b.draftFailure()).as("moved somewhere other than PROPOSED meanwhile").isEqualTo("NOT_DRAFTABLE");

        InquiryWorkItem unwired = openRefundRequest();
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse c = controllerWith(never)
                .record(me, unwired.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                        .RecordRequest("IN_PROGRESS", "환불 대기", null, soon()));
        assertThat(c.draftFailure()).as("no preparation available: refused, not attempted").isEqualTo("NOT_DRAFTABLE");

        verifyNoInteractions(never);
        assertThat(draftRows.countByWorkItemId(failing.getId()) + draftRows.countByWorkItemId(elsewhere.getId())
                + draftRows.countByWorkItemId(unwired.getId())).isZero();
    }

    @Test
    @DisplayName("a refusal from the drafting path is told apart from a broken model")
    void aRefusedDraftIsNotABrokenOne() {
        InquiryWorkItem work = refundRequest();
        InquiryDraftComposer refusing = mock(InquiryDraftComposer.class);
        when(refusing.generate(org, work.getId(), user))
                .thenThrow(com.sellerops.common.ApiException.conflict("PROPOSED 상태의 문의만 답변 초안을 저장할 수 있습니다."));
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse saved = controllerWith(refusing)
                .record(me, work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                        .RecordRequest("IN_PROGRESS", "환불 대기", null, soon()));
        assertThat(saved.draftState()).isEqualTo("FAILED");
        assertThat(saved.draftFailure()).isEqualTo("DRAFT_REFUSED");
        assertThat(saved.check()).isNotNull();
    }

    @Test
    @DisplayName("a record corrected after approval keeps the approved answer: nothing is drafted, the approved version "
            + "stays the head, and it is held at the send check until registration is confirmed")
    void aRecordAfterApprovalKeepsTheApprovedAnswer() {
        InquiryWorkItem work = refundRequest();
        UUID first = sellerChecks.record(org, work.getId(), "DONE", "환불 처리 완료", "SELLER:" + user).id();
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "요청하신 환불은 처리되었습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        assertThat(v1.draft()).isNotNull();
        work.setPhase(InquiryWorkItemPhase.ACTION_PENDING);
        workItems.saveAndFlush(work);

        InquiryDraftComposer never = mock(InquiryDraftComposer.class);
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController controller = controllerWith(never);
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordResponse saved = controller.record(me,
                work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordRequest(
                        "IN_PROGRESS", "환불 재요청, 처리 대기", first, soon()));
        em.flush();

        assertThat(saved.draftState()).isEqualTo("KEPT");
        assertThat(saved.draft()).isNull();
        verifyNoInteractions(never);
        assertThat(draftRows.countByWorkItemId(work.getId())).as("no new version").isEqualTo(1);
        assertThat(workItems.findById(work.getId()).orElseThrow().getPhase())
                .as("the approval stands").isEqualTo(InquiryWorkItemPhase.ACTION_PENDING);
        assertThat(checkVersion(work, v1.draft().version()).codes())
                .as("not yet registered: the approved 「처리되었습니다」 no longer matches the record and is held")
                .contains(com.sellerops.inquiry.draft.InquiryDraftRecheck.SELLER_CHECK_CHANGED);
        assertThat(saved.check().nextCheckOn()).isEqualTo(soon());
    }

    @Test
    @DisplayName("moving the next-check day after approval is not a new record: the approved answer still matches and "
            + "nothing is held")
    void movingTheDayKeepsTheApprovedAnswerStanding() {
        InquiryWorkItem work = refundRequest();
        UUID shown = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, null, null)
                .id();
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        assertThat(v1.draft()).isNotNull();
        work.setPhase(InquiryWorkItemPhase.ACTION_PENDING);
        workItems.saveAndFlush(work);

        com.sellerops.inquiry.sellercheck.InquirySellerCheckController controller = controllerWith(mock(InquiryDraftComposer.class));
        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");
        controller.nextCheck(me, work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                .NextCheckRequest(shown, sellerChecks.today().plusDays(5)));
        em.flush();

        assertThat(checkVersion(work, v1.draft().version()).codes()).isEmpty();
        assertThat(controller.current(me, work.getId()).draftMatches()).isTrue();
        assertThat(controller.current(me, work.getId()).check().nextCheckOn()).isEqualTo(sellerChecks.today().plusDays(5));
        assertThat(draftRows.countByWorkItemId(work.getId())).isEqualTo(1);
    }

    @Autowired com.sellerops.inquiry.publish.InquiryApprovalRepository approvalRows;
    @Autowired com.sellerops.inquiry.publish.InquiryActionIntentRepository intentRows;
    @Autowired com.sellerops.inquiry.publish.InquiryExecutionRepository executionRows;
    @Autowired com.sellerops.inquiry.publish.InquiryVerificationRepository verificationRows;
    @Autowired com.sellerops.inquiry.publish.InquiryApprovalWithdrawalRepository withdrawnRows;
    @Autowired com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository auditRows;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired com.sellerops.inquiry.sellercheck.InquiryKindCorrectionRepository kindRows;

    @Test
    @DisplayName("the seller corrects what kind of inquiry this is: the reply is planned under it — by every reader — the "
            + "service's own reading is kept beside it, and nothing is corrected once the answer is approved")
    void theSellerCorrectsWhatKindOfInquiryThisIs() {
        candidates.teach(org, "ORG", null, "환불 신청", null,
                "환불은 마이페이지 주문내역에서 환불 요청을 누르시면 신청됩니다.", OrgKnowledgeType.EXCHANGE_REFUND_POLICY,
                user, "판매자");
        InquiryWorkItem work = seedInquiry("환불", "어제 주문했는데 환불은 어떻게 신청하나요?");
        // The planner reads it as an action on this order; the judge answers a policy need from the policy and an order
        // need from nothing but the seller's own record.
        ScriptedDecision decision = new ScriptedDecision(
                List.of(need("N1", "이 주문을 환불하는 방법", com.sellerops.inquiry.decision.NeedType.ORDER_ACTION)),
                (n, e) -> n.type() == com.sellerops.inquiry.decision.NeedType.POLICY ? fullIfSaid(n, e, "환불 요청")
                        : fullIfSaid(n, e, "판매자센터에서 직접 확인"));
        assessor.setDecision(decision, collector);
        GeneratedDraftView before = composer(StubModel.writing("안내", "환불 처리했습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        assertThat(before.draft()).isNull();
        assertThat(before.sellerCheckAsk()).isEqualTo("ORDER_ACTION");
        assertThat(workItems.findById(work.getId()).orElseThrow().getJudgedKind()).isEqualTo("ORDER_ACTION");

        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");
        var shown = controllerWith(mock(InquiryDraftComposer.class)).current(me, work.getId()).kind();
        assertThat(shown.judged()).isEqualTo("ORDER_ACTION");
        assertThat(shown.corrected()).isNull();

        var howTo = controllerWith(composer(StubModel.writing("안내",
                "환불은 마이페이지 주문내역에서 환불 요청을 누르시면 신청돼요."))).correctKind(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.KindRequest("HOW_TO", null));
        em.flush();
        assertThat(howTo.kind().corrected()).isEqualTo("HOW_TO");
        assertThat(howTo.kind().judged()).as("the service's reading stays beside the seller's").isEqualTo("ORDER_ACTION");
        assertThat(howTo.draftState()).isEqualTo("READY");
        assertThat(howTo.ask()).as("nothing to look up on the seller centre").isNull();
        assertThat(evidenceRows.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(work.getId(),
                howTo.draft().draft().version()))
                .extracting(com.sellerops.inquiry.draft.InquiryDraftEvidence::getKind)
                .contains(com.sellerops.inquiry.draft.InquiryDraftEvidence.KIND_ORG_POLICY);
        assertThat(workItems.findById(work.getId()).orElseThrow().getJudgedKind())
                .as("a corrected decision never writes the service's reading").isEqualTo("ORDER_ACTION");
        Inquiry inquiry = inquiries.findById(work.getInquiryId()).orElseThrow();
        assertThat(assessor.assess(org, inquiry, com.sellerops.order.fact.OrderFactLookup.STORED_ONLY).decision().needs())
                .as("a read-only assessment plans under the same correction")
                .extracting(r -> r.need().type()).containsExactly(com.sellerops.inquiry.decision.NeedType.POLICY);

        UUID standing = howTo.kind().correctionId();
        assertThatThrownBy(() -> controllerWith(mock(InquiryDraftComposer.class)).correctKind(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.KindRequest("ORDER_STATE", null)))
                .as("a screen that did not see the standing correction").hasMessage(
                        com.sellerops.inquiry.sellercheck.InquiryKindCorrectionService.STALE);

        var state = controllerWith(composer(StubModel.writing("안내", "확인 후 안내드리겠습니다."))).correctKind(me,
                work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                        .KindRequest("ORDER_STATE", standing));
        em.flush();
        assertThat(state.ask()).as("now the seller centre is asked for this order's state").isEqualTo("ORDER_STATE");
        assertThat(howTo.kind().draftMatches()).as("the 방법 안내 answer was written under 방법 안내").isTrue();
        assertThat(state.draftState()).isEqualTo("NOT_WRITTEN");
        assertThat(state.kind().draftMatches())
                .as("the answer waiting was written under the reading just corrected away").isFalse();

        // The eighth review's case: the 방법 안내 answer kept open on another device is edited and saved AFTER the
        // correction. Saved later, it still names the correction it was written under, and does not stand.
        UUID nowKind = state.kind().correctionId();
        int head = draftRows.findTopByWorkItemIdOrderByVersionDesc(work.getId()).orElseThrow().getVersion();
        draftService.save(org, work.getId(), user, "안내", "환불은 마이페이지에서 신청하실 수 있어요.", head,
                InquiryReplyDraftService.Provenance.seller(null, standing));
        em.flush();
        assertThat(controllerWith(mock(InquiryDraftComposer.class)).current(me, work.getId()).kind().draftMatches())
                .as("saved after the correction, written under the one before it").isFalse();
        draftService.save(org, work.getId(), user, "안내", "환불은 마이페이지에서 신청하실 수 있어요.", head + 1,
                InquiryReplyDraftService.Provenance.seller(null, nowKind));
        em.flush();
        assertThat(controllerWith(mock(InquiryDraftComposer.class)).current(me, work.getId()).kind().draftMatches())
                .as("the same words saved in front of the standing correction stand").isTrue();
        assertThat(kindRows.findByOrgIdAndWorkItemIdOrderByCorrectedAtAsc(org, work.getId()))
                .extracting(com.sellerops.inquiry.sellercheck.InquiryKindCorrection::getKind,
                        c -> c.getSupersededAt() != null)
                .containsExactly(Tuple.tuple("HOW_TO", true),
                        Tuple.tuple("ORDER_STATE", false));

        InquiryWorkItem decided = workItems.findById(work.getId()).orElseThrow();
        decided.setPhase(InquiryWorkItemPhase.ACTION_PENDING);
        workItems.saveAndFlush(decided);
        UUID now = state.kind().correctionId();
        InquiryDraftComposer never = mock(InquiryDraftComposer.class);
        assertThatThrownBy(() -> controllerWith(never).correctKind(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.KindRequest("HOW_TO", now)))
                .hasMessage(com.sellerops.inquiry.sellercheck.InquiryKindCorrectionService.DECIDED);
        verifyNoInteractions(never);
    }

    @Test
    @DisplayName("an approved answer the record outran comes back: it says what was sent, is taken back only on the "
            + "seller's word, and a new answer is written from the record that stands — waiting for a new approval")
    void anOutrunApprovalComesBackForANewAnswer() {
        InquiryWorkItem work = refundRequest();
        UUID first = sellerChecks.record(org, work.getId(), "IN_PROGRESS", "환불 대기", "SELLER:" + user, null, null).id();
        GeneratedDraftView v1 = composer(StubModel.writing("안내", "요청하신 환불은 현재 대기 중입니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        em.flush();
        assertThat(v1.draft()).isNotNull();
        new com.sellerops.inquiry.publish.InquiryPublishBindingWriter(workItems, approvalRows, intentRows, executionRows,
                auditRows, txManager).bind(workItems.findById(work.getId()).orElseThrow(),
                draftRows.findByWorkItemIdAndVersion(work.getId(), v1.draft().version()).orElseThrow(),
                new com.sellerops.inquiry.publish.InquiryPublishBindingWriter.ApprovalTarget(null, null, "ext", null),
                "cmd-1", "SELLER:" + user);
        em.flush();
        // Collection saw it, still unanswered, after the approval.
        Inquiry seen = inquiries.findById(work.getInquiryId()).orElseThrow();
        seen.setLastSeenAt(approvalRows.findByWorkItemId(work.getId()).orElseThrow().getCreatedAt().plusSeconds(60));
        inquiries.saveAndFlush(seen);

        com.sellerops.auth.AuthPrincipal me = new com.sellerops.auth.AuthPrincipal(user, org, "seller@example.invalid");
        var kept = controllerWith(mock(InquiryDraftComposer.class)).record(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.RecordRequest(
                        "DONE", "카드 결제 취소로 환불 처리", first));
        em.flush();
        assertThat(kept.draftState()).isEqualTo("KEPT");

        var state = controllerWith(mock(InquiryDraftComposer.class)).current(me, work.getId());
        assertThat(state.check().outcome()).as("the action now").isEqualTo("DONE");
        assertThat(state.answered().outcome()).as("what the customer is being told").isEqualTo("IN_PROGRESS");
        assertThat(state.answered().current()).isFalse();
        assertThat(state.answered().reopen()).isEqualTo("OFFERED");

        UUID now = state.check().id();
        String approved = state.answered().fingerprint();
        InquiryDraftComposer never = mock(InquiryDraftComposer.class);
        assertThatThrownBy(() -> controllerWith(never).reopen(me, work.getId(),
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController.ReopenRequest(now, approved, null)))
                .hasMessageContaining("직접 등록하지 않았는지");
        verifyNoInteractions(never);
        assertThat(approvalRows.findByWorkItemId(work.getId())).isPresent();

        var reopened = controllerWith(composer(StubModel.writing("안내", "요청하신 환불은 카드 결제 취소로 처리되었습니다.")))
                .reopen(me, work.getId(), new com.sellerops.inquiry.sellercheck.InquirySellerCheckController
                        .ReopenRequest(now, approved, true));
        em.flush();

        assertThat(reopened.draftState()).isEqualTo("READY");
        int v2 = reopened.draft().draft().version();
        assertThat(v2).isGreaterThan(v1.draft().version());
        assertThat(draftRows.findByWorkItemIdAndVersion(work.getId(), v2).orElseThrow().getSellerCheckId())
                .as("written from the record that stands").isEqualTo(now);
        assertThat(checkVersion(work, v2).codes()).isEmpty();
        assertThat(workItems.findById(work.getId()).orElseThrow().getPhase())
                .as("waiting for the seller's new approval").isEqualTo(InquiryWorkItemPhase.PROPOSED);
        assertThat(approvalRows.findByWorkItemId(work.getId())).as("nothing approved yet").isEmpty();
        assertThat(withdrawnRows.countByWorkItemId(work.getId())).isEqualTo(1);
        var after = controllerWith(mock(InquiryDraftComposer.class)).current(me, work.getId());
        assertThat(after.draftMatches()).isTrue();
        assertThat(after.answered()).isNull();
    }

    private com.sellerops.inquiry.sellercheck.InquirySellerCheckController controllerWith(InquiryDraftComposer c) {
        return controllerWith(c, null);
    }

    private com.sellerops.inquiry.sellercheck.InquirySellerCheckController controllerWith(InquiryDraftComposer c,
            com.sellerops.inquiry.proposal.InquiryProposalService proposals) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (proposals != null) {
            beans.addBean("proposals", proposals);
        }
        com.sellerops.inquiry.draft.InquiryDraftRecheck r = recheck();
        beans.addBean("recheck", r);
        beans.addBean("withdrawals", new com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService(em, approvalRows,
                intentRows, executionRows, verificationRows, withdrawnRows, draftRows, sellerCheckRows, inquiries,
                auditRows, r, memories));
        com.sellerops.inquiry.sellercheck.InquiryKindCorrectionService kinds =
                new com.sellerops.inquiry.sellercheck.InquiryKindCorrectionService(em, kindRows, workItems);
        kinds.setDrafts(draftRows);
        beans.addBean("kinds", kinds);
        com.sellerops.inquiry.sellercheck.InquirySellerCheckController controller =
                new com.sellerops.inquiry.sellercheck.InquirySellerCheckController(sellerChecks, c,
                beans.getBeanProvider(com.sellerops.replyhold.ReplyHoldService.class), draftRows,
                beans.getBeanProvider(com.sellerops.inquiry.draft.InquiryDraftRecheck.class),
                beans.getBeanProvider(com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.class), workItems);
        controller.setKinds(beans.getBeanProvider(com.sellerops.inquiry.sellercheck.InquiryKindCorrectionService.class));
        controller.setProposals(beans.getBeanProvider(com.sellerops.inquiry.proposal.InquiryProposalService.class));
        return controller;
    }

    @Test
    @DisplayName("a draft that does not answer is written once more")
    void aDraftWithNoAnswerIsWrittenOnceMore() {
        InquiryWorkItem work = groundedOn3ho();
        StubModel model = rewriting("문의 감사합니다.", "3호부터 가능합니다.");
        model.answeredFor = d -> d.contains("3호");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(model.sawScopes.get(1)).contains("문의에 대한 답이 없었습니다");
    }

    @Test
    @DisplayName("not written again: a check that did not finish, a number it could not confirm, evidence that disagrees, no room")
    void whatARewriteCannotFixIsNotWrittenAgain() {
        StubModel unchecked = rewriting("3호부터 가능합니다.", "3호부터 가능합니다.");
        unchecked.figureCheck = f -> Optional.empty();
        GeneratedDraftView a = composer(unchecked).generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(a.unavailableMessage()).contains("근거와 대조하지 못해");
        assertThat(unchecked.calls).as("a check that did not finish").isEqualTo(1);

        StubModel figure = rewriting("3호부터 가능합니다. 배송은 7일 걸립니다.", "3호부터 가능합니다.");
        GeneratedDraftView b = composer(figure).generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(b.draft()).isNull();
        assertThat(figure.calls).as("a number the evidence does not carry").isEqualTo(1);

        StubModel conflict = rewriting("3호부터 가능합니다.", "3호부터 가능합니다.");
        conflict.claimsAnswer = List.of(new AgentDraftGenerator.ClaimVerdict("3호부터 가능", "CONFLICT"));
        GeneratedDraftView c = composer(conflict).generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(c.unavailableMessage()).contains("서로 다르게");
        assertThat(conflict.calls).as("evidence that disagrees").isEqualTo(1);

        assertThat(InquiryDraftComposer.rewriteClaims(List.of(new com.sellerops.inquiry.draft.InquiryClaimGuard.Violation(
                com.sellerops.inquiry.draft.InquiryClaimGuard.Kind.LAW, "개봉 시 반품 불가")))).as("the law").isNull();
        assertThat(InquiryDraftComposer.rewriteClaims(List.of(new com.sellerops.inquiry.draft.InquiryClaimGuard.Violation(
                com.sellerops.inquiry.draft.InquiryClaimGuard.Kind.CONDITION, "교환 가능")))).contains("근거가 붙인 조건");

        StubModel noRoom = rewriting("3호부터 가능합니다. 사진을 보내주시면 확인 후 안내드리겠습니다.", "3호부터 가능합니다.");
        InquiryDraftComposer full = new InquiryDraftComposer(workItems, inquiries, draftService, evidenceRows, retriever,
                assessor, noRoom, new AgentQuotaService(null, null) {
                    @Override
                    public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String idempotencyKey) {
                        return new QuotaDecision(true, null, 0, 0);
                    }

                    @Override
                    public Charge chargeCheck(UUID orgId, AgentUsageKind kind, String step,
                                              com.sellerops.agent.quota.AgentUsageActor actor) {
                        return InquiryDraftComposer.REDRAFT_STEP.equals(step)
                                ? new Charge(new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 0, 0), null) : super.chargeCheck(orgId, kind,
                                step, actor);
                    }
                }, variants, new DraftEvidenceSnippets(productChunks, orgChunks, memories),
                new com.sellerops.product.detail.ProductDetailEnrichmentTrigger(null, null, null, null, List.of(), false),
                null, null, null, candidates);
        GeneratedDraftView d = full.generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(d.unavailableMessage()).startsWith("근거에 없는 약속이나 요청");
        assertThat(noRoom.calls).as("no room").isEqualTo(1);
        assertThat(d.rewrite()).as("no rewrite, nothing to tell").isNull();
    }

    @Test
    @DisplayName("no plan: not 「답변 기준이 필요합니다」 but an operational reason, and nothing drafted")
    void noDecisionIsOperational() {
        InquiryWorkItem work = seedInquiry(null, "지름 10mm 선 두 줄 넣으려면 몇 호가 맞나요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(), (n, e) -> null);
        decision.planFails = true;
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "3호부터 가능합니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.answerBasis()).isEqualTo("NO_ANSWER_BASIS");
        assertThat(view.unavailableMessage()).isEqualTo(InquiryDraftComposer.DECISION_UNAVAILABLE);
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("capability off: the assessment is exactly the legacy one, and no decision model is called")
    void offIsLegacy() {
        InquiryWorkItem work = seedInquiry(null, "지름 10mm 선 두 줄 넣으려면 몇 호가 맞나요?");
        ScriptedDecision decision = new ScriptedDecision(List.of(), (n, e) -> null);
        decision.enabled = false;
        assessor.setDecision(decision, collector);
        StubModel model = StubModel.writing("안내", "3호부터 가능합니다.");

        GeneratedDraftView view = composer(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(decision.calls).isZero();
        assertThat(view.knowledgeGap() == null || view.knowledgeGap().needs() == null).isTrue();
        assertThat(model.sawScope).as("the v11-shaped call — no scope list").isNull();
    }

    @Test
    @DisplayName("only past answers whose declared scope admits this Case reach the judge — the stored column decides")
    void theJudgeIsOfferedOnlyAdmissiblePastAnswers() {
        Inquiry origin = inquiries.findById(seedInquiry(null, "예전 문의").getInquiryId()).orElseThrow();
        origin.setSourceOrderRef("ORD-1");
        inquiries.save(origin);
        Inquiry sameOrder = inquiries.findById(seedInquiry(null, "같은 주문").getInquiryId()).orElseThrow();
        sameOrder.setChannelId(origin.getChannelId());
        sameOrder.setSourceOrderRef("ORD-1");
        inquiries.save(sameOrder);
        Inquiry stranger = inquiries.findById(seedInquiry(null, "다른 고객").getInquiryId()).orElseThrow();
        AnswerMemoryService memory = new AnswerMemoryService(memories, orgChunks, productChunks);
        java.util.function.Function<com.sellerops.knowledge.memory.AnswerMemoryReuseScope, UUID> remember = scope ->
                memory.remember(new AnswerMemoryService.RememberCommand(org, "inquiry-answer:" + scope,
                        AnswerMemoryStrength.USER_APPROVED, "질문", null, "답변 " + scope, productId, "NAVER", null,
                        null, origin.getId(), null, null, scope == null ? null : user, null, null, scope))
                        .orElseThrow().getId();
        UUID reusable = remember.apply(com.sellerops.knowledge.memory.AnswerMemoryReuseScope.REUSABLE);
        UUID orderOnly = remember.apply(com.sellerops.knowledge.memory.AnswerMemoryReuseScope.ORDER_ONLY);
        UUID caseOnly = remember.apply(com.sellerops.knowledge.memory.AnswerMemoryReuseScope.CASE_ONLY);
        UUID unknown = remember.apply(null);
        List<com.sellerops.inquiry.decision.PrecedentCandidate> offered = java.util.stream.Stream.of(reusable,
                orderOnly, caseOnly, unknown).map(id -> new com.sellerops.inquiry.decision.PrecedentCandidate(null, id,
                "답변")).toList();

        assertThat(collector.admissible(stranger, offered)).extracting(
                com.sellerops.inquiry.decision.PrecedentCandidate::memoryId).containsExactly(reusable);
        assertThat(collector.admissible(sameOrder, offered)).extracting(
                com.sellerops.inquiry.decision.PrecedentCandidate::memoryId).containsExactly(reusable, orderOnly);
        assertThat(collector.admissible(origin, offered)).extracting(
                com.sellerops.inquiry.decision.PrecedentCandidate::memoryId)
                .as("in its own Case every answer is its own — and its order is its own order")
                .containsExactly(reusable, orderOnly, caseOnly, unknown);
        collector.setScopeSource(m -> com.sellerops.knowledge.memory.AnswerMemoryReuseScope.REUSABLE);
        assertThat(collector.admissible(stranger, offered)).as("an evaluation may substitute annotations").hasSize(4);
        collector.setScopeSource(null);
    }

    // ---- Thirteenth review (2026-11-06): ask only what the judgement left, and do not ask the seller twice. ----

    @Autowired com.sellerops.openquestion.OpenQuestionRepository openQuestionRows;
    @Autowired com.sellerops.openquestion.OpenQuestionEventRepository openQuestionEvents;

    private InquiryDraftComposer withQuestions(StubModel model) {
        InquiryDraftComposer c = composer(model);
        c.setOpenQuestions(new com.sellerops.openquestion.OpenQuestionService(openQuestionRows, openQuestionEvents));
        return c;
    }

    private List<com.sellerops.openquestion.OpenQuestion> questionsOf(InquiryWorkItem work) {
        em.flush();
        return openQuestionRows.findByOrgIdAndLaneAndSubjectIdOrderByCreatedAtAsc(org, "INQUIRY", work.getId());
    }

    @Test
    @DisplayName("1 · a question the judgement left for the customer is asked and kept")
    void aNeededQuestionIsKept() {
        InquiryWorkItem work = seedInquiry(null, "이 몰딩에 전선 몇 가닥까지 들어가나요?");
        assessor.setDecision(new ScriptedDecision(
                List.of(need("N1", "들어가는 전선 가닥 수", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                        com.sellerops.inquiry.decision.NeedStatus.CONDITIONAL_ON_CUSTOMER,
                        e.stream().filter(c -> c.text().contains("3호부터")).map(
                                com.sellerops.inquiry.decision.EvidenceCandidate::id).toList(),
                        List.of(), List.of("쓰시는 전선의 지름"), List.of(), "쓰시는 전선의 지름이 어떻게 되시나요?",
                        List.of())), collector);
        StubModel model = StubModel.writing("안내", "호수마다 들어가는 양이 다릅니다. 쓰시는 전선의 지름이 어떻게 되시나요?");

        GeneratedDraftView view = withQuestions(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).contains("어떻게 되시나요?");
        assertThat(model.calls).as("not rewritten").isEqualTo(1);
        assertThat(model.sawScope).contains("→ 고객에게 확인: 쓰시는 전선의 지름이 어떻게 되시나요?");
    }

    @Test
    @DisplayName("2 · with nothing left for the customer, a question is written out once — and refused if it comes back")
    void anUnneededQuestionIsRemoved() {
        InquiryWorkItem work = groundedOn3ho();
        StubModel model = rewriting("3호부터 가능합니다. 원하시는 색상도 알려주시겠어요?", "3호부터 가능합니다.");

        GeneratedDraftView view = withQuestions(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).isEqualTo("3호부터 가능합니다.");
        assertThat(view.rewrite()).isEqualTo("PASSED");
        assertThat(model.calls).isEqualTo(2);
        assertThat(model.checks).as("the question never reached the model check").isEqualTo(1);
        assertThat(model.sawScopes.get(1)).contains("「원하시는 색상도 알려주시겠어요?」처럼 고객에게 묻는 말을 썼습니다")
                .contains("고객에게 확인할 항목이 없습니다");

        StubModel twice = rewriting("3호부터 가능합니다. 원하시는 색상도 알려주시겠어요?", "3호부터예요. 색상은요?");
        GeneratedDraftView refused = withQuestions(twice).generateAs(org, groundedOn3ho().getId(), "SYSTEM:RESPONSIBILITY");
        assertThat(refused.draft()).isNull();
        assertThat(refused.unavailableMessage()).startsWith("초안이 판정에 없는 내용을 고객에게 묻고 있어");
        assertThat(InquiryDraftComposer.unavailableKind(refused.unavailableMessage())).isEqualTo("HELD_BY_CHECK");
        assertThat(twice.calls).as("once more, never twice").isEqualTo(2);
    }

    @Test
    @DisplayName("2b · where some question is allowed, one beside it is the claim check's to refuse (v13), and it is written out")
    void aQuestionBesideTheAllowedOneIsTheChecks() {
        InquiryWorkItem work = seedInquiry(null, "이 몰딩에 전선 몇 가닥까지 들어가나요?");
        assessor.setDecision(new ScriptedDecision(
                List.of(need("N1", "들어가는 전선 가닥 수", com.sellerops.inquiry.decision.NeedType.PRODUCT_SPEC)),
                (n, e) -> new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                        com.sellerops.inquiry.decision.NeedStatus.CONDITIONAL_ON_CUSTOMER,
                        e.stream().filter(c -> c.text().contains("3호부터")).map(
                                com.sellerops.inquiry.decision.EvidenceCandidate::id).toList(),
                        List.of(), List.of("쓰시는 전선의 지름"), List.of(), "쓰시는 전선의 지름이 어떻게 되시나요?",
                        List.of())), collector);
        StubModel model = rewriting("쓰시는 전선의 지름이 어떻게 되시나요? 원하시는 색상도 알려주시겠어요?",
                "쓰시는 전선의 지름이 어떻게 되시나요?");
        model.claimsFor = d -> d.contains("색상") ? List.of(new AgentDraftGenerator.ClaimVerdict(
                "원하시는 색상도 알려주시겠어요?", "NOT_SUPPORTED")) : List.of();

        GeneratedDraftView view = withQuestions(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).isEqualTo("쓰시는 전선의 지름이 어떻게 되시나요?");
        assertThat(model.calls).isEqualTo(2);
        assertThat(model.checks).as("the deterministic guard let the allowed case through to the check").isEqualTo(2);
    }

    @Test
    @DisplayName("2c · a request with no question mark, nothing left for the customer: the claim check's to refuse (v13), and "
            + "the reply is written once more without it")
    void aRequestWithoutAQuestionMarkIsTheChecks() {
        InquiryWorkItem work = groundedOn3ho();
        StubModel model = rewriting("3호부터 가능합니다. 원하시는 색상을 알려주세요.", "3호부터 가능합니다.");
        model.claimsFor = d -> d.contains("색상") ? List.of(new AgentDraftGenerator.ClaimVerdict(
                "원하시는 색상을 알려주세요.", "NOT_SUPPORTED")) : List.of();

        GeneratedDraftView view = withQuestions(model).generateAs(org, work.getId(), "SYSTEM:RESPONSIBILITY");

        assertThat(view.draft()).isNotNull();
        assertThat(view.draft().comments()).isEqualTo("3호부터 가능합니다.");
        assertThat(model.checks).as("no question mark: the deterministic guard let it through to the check").isEqualTo(2);
        assertThat(model.sawScopes.get(1)).contains("「원하시는 색상을 알려주세요.」은(는) 보여 드린 근거로 확인되지 않습니다");
    }

    @Test
    @DisplayName("a refund done does not settle the exchange the same message asked for — the request stays the seller's")
    void anotherActionIsNotSettled() {
        InquiryWorkItem work = seedInquiry("환불", "이 주문은 환불해 주시고, 다른 상품은 교환해 주세요.");
        assessor.setDecision(new ScriptedDecision(List.of(
                need("N1", "이 주문을 환불해 달라는 요청", com.sellerops.inquiry.decision.NeedType.ORDER_ACTION),
                need("N2", "다른 상품을 교환해 달라는 요청", com.sellerops.inquiry.decision.NeedType.ORDER_ACTION)),
                (n, e) -> {
                    String record = e.stream().filter(c -> c.text().contains("판매자센터에서 직접 확인"))
                            .map(com.sellerops.inquiry.decision.EvidenceCandidate::id).findFirst().orElse(null);
                    return n.id().equals("N1") ? fullIfSaid(n, e, "판매자센터에서 직접 확인")
                            : new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                                    record == null ? com.sellerops.inquiry.decision.NeedStatus.NONE
                                            : com.sellerops.inquiry.decision.NeedStatus.PARTIAL,
                                    record == null ? List.of() : List.of(record), List.of("교환 진행 여부"), List.of(),
                                    List.of(), null, List.of(), record);
                }), collector);
        sellerChecks.record(org, work.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:" + user);

        GeneratedDraftView view = withQuestions(StubModel.writing("안내", "주문하신 상품은 카드 결제 취소로 환불 처리했습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);

        assertThat(view.draft()).isNotNull();
        assertThat(questionsOf(work)).noneMatch(q -> "SETTLED_BY_ACTION".equals(q.getResolution()));
        assertThat(view.openQuestions()).containsExactly("다른 상품을 교환해 달라는 요청 — 교환 진행 여부");
    }

    /** The R1 shape: can a torn package be exchanged (a rule), the exchange itself, and a date asked separately. */
    private InquiryWorkItem tornPackage() {
        candidates.teach(org, "ORG", null, "교환 기한", null, "교환은 상품 수령 후 7일 이내 신청하실 수 있습니다.",
                OrgKnowledgeType.EXCHANGE_REFUND_POLICY, user, "판매자");
        InquiryWorkItem work = seedInquiry("교환", "포장이 찢어져서 왔는데 교환 되나요? 새 상품은 언제 받을 수 있어요? "
                + "교환 배송비는 제가 내나요?");
        java.util.function.BiFunction<com.sellerops.inquiry.decision.InquiryNeed,
                List<com.sellerops.inquiry.decision.EvidenceCandidate>, com.sellerops.inquiry.decision.NeedVerdict> judge =
                (n, e) -> {
                    String record = e.stream().filter(c -> c.text().contains("판매자센터에서 직접 확인"))
                            .map(com.sellerops.inquiry.decision.EvidenceCandidate::id).findFirst().orElse(null);
                    String rule = e.stream().filter(c -> c.text().contains("7일 이내 신청"))
                            .map(com.sellerops.inquiry.decision.EvidenceCandidate::id).findFirst().orElse(null);
                    return switch (n.id()) {
                        // What deciding it would take is missing; the judge names the record as settling it (v6).
                        case "N1" -> new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                                com.sellerops.inquiry.decision.NeedStatus.PARTIAL,
                                rule == null ? List.of() : List.of(rule), List.of("포장 찢김이 불량에 해당하는지에 대한 기준"),
                                List.of(), List.of(), null, List.of(), record);
                        case "N2" -> fullIfSaid(n, e, "판매자센터에서 직접 확인");
                        // A rule the customer asked separately: the judge names the record — wrongly. Only the check's
                        // reading of the reply can keep it, since it is a rule like N1.
                        case "N4" -> new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                                com.sellerops.inquiry.decision.NeedStatus.PARTIAL,
                                rule == null ? List.of() : List.of(rule), List.of("교환 배송비 부담 기준"),
                                List.of(), List.of(), null, List.of(), record);
                        // A date the customer asked: the judge names the record here too — wrongly; code must not take it.
                        default -> new com.sellerops.inquiry.decision.NeedVerdict(n.id(),
                                record == null ? com.sellerops.inquiry.decision.NeedStatus.NONE
                                        : com.sellerops.inquiry.decision.NeedStatus.PARTIAL,
                                record == null ? List.of() : List.of(record), List.of("새 상품 도착일"), List.of(),
                                List.of(), null, List.of(), record);
                    };
                };
        assessor.setDecision(new ScriptedDecision(List.of(
                need("N1", "포장이 찢어져 온 상품을 교환할 수 있는지", com.sellerops.inquiry.decision.NeedType.POLICY),
                need("N2", "이 주문을 교환해 달라는 요청", com.sellerops.inquiry.decision.NeedType.ORDER_ACTION),
                need("N3", "새 상품을 언제 받을 수 있는지", com.sellerops.inquiry.decision.NeedType.ORDER_STATE),
                need("N4", "교환 배송비는 누가 내는지", com.sellerops.inquiry.decision.NeedType.POLICY)),
                judge), collector);
        return work;
    }

    @Test
    @DisplayName("3 · 4 · the exchange done: the rule it needed is closed as 「조치 완료로 판단 불필요」 on the record, never as "
            + "answered — and the date the customer asked stays the seller's")
    void aCompletedActionSettlesOnlyWhatItDecided() {
        InquiryWorkItem work = tornPackage();
        withQuestions(StubModel.writing("안내", "교환은 상품 수령 후 7일 이내 신청하실 수 있습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);
        assertThat(questionsOf(work)).filteredOn(q -> "OPEN".equals(q.getState()))
                .extracting(com.sellerops.openquestion.OpenQuestion::getQuestion)
                .as("before the record, the rule is the seller's to settle")
                .anyMatch(q -> q.startsWith("포장이 찢어져 온 상품을 교환할 수 있는지 — "));

        com.sellerops.inquiry.sellercheck.InquirySellerCheckService.SellerCheckView done =
                sellerChecks.record(org, work.getId(), "DONE", "교환 상품 발송함. 송장 등록 완료", "SELLER:" + user);
        StubModel after = StubModel.writing("안내", "교환 상품을 발송했고 송장 등록까지 마쳤습니다.");
        // The check reads the reply: it answered whether the exchange happens, not who pays its shipping.
        after.coveredAnswer = java.util.Map.of("N1", "ANSWERED", "N2", "ANSWERED", "N3", "ANSWERED", "N4", "NOT_COVERED");
        GeneratedDraftView view = withQuestions(after).generateAs(org, work.getId(), "SELLER:" + user);

        assertThat(view.draft()).isNotNull();
        List<com.sellerops.openquestion.OpenQuestion> rows = questionsOf(work);
        // 3 — the rule it needed: closed by the system, as settled, on the record that did it.
        assertThat(rows).filteredOn(q -> q.getQuestion().startsWith("포장이 찢어져 온 상품을 교환할 수 있는지"))
                .isNotEmpty().allSatisfy(q -> {
                    assertThat(q.getState()).isNotEqualTo("OPEN");
                    assertThat(q.getResolution()).isEqualTo("SETTLED_BY_ACTION").isNotEqualTo("ANSWERED");
                    assertThat(q.getBasisRef()).isEqualTo("seller-check:" + done.id());
                });
        assertThat(openQuestionEvents.findAll()).filteredOn(e -> "SETTLED".equals(e.getAction()))
                .isNotEmpty().allSatisfy(e -> assertThat(e.getBasisRef()).isEqualTo("seller-check:" + done.id()));
        // 4 — the date and the shipping fee the customer asked: open, though the judge named the same record for both. The
        // fee is a rule like N1; what keeps it is that the reply did not answer it — and it stays as the judge left it.
        assertThat(rows).filteredOn(q -> "OPEN".equals(q.getState()))
                .extracting(com.sellerops.openquestion.OpenQuestion::getQuestion)
                .containsExactlyInAnyOrder("새 상품을 언제 받을 수 있는지 — 새 상품 도착일",
                        "교환 배송비는 누가 내는지 — 교환 배송비 부담 기준");
        assertThat(rows).noneMatch(q -> q.getQuestion().startsWith("교환 배송비는 누가 내는지")
                && "SETTLED_BY_ACTION".equals(q.getResolution()));
        assertThat(view.openQuestions()).containsExactlyInAnyOrder("새 상품을 언제 받을 수 있는지 — 새 상품 도착일",
                "교환 배송비는 누가 내는지 — 교환 배송비 부담 기준");
        assertThat(view.knowledgeGap().needs()).extracting(com.sellerops.inquiry.draft.dto.NeedCoverageView::statusKo)
                .first().isEqualTo("조치 완료로 판단 불필요");
        assertThat(view.knowledgeGap().needs()).filteredOn(v -> v.id().equals("N4")).singleElement()
                .satisfies(v -> {
                    assertThat(v.statusKo()).as("open, so not shown as settled").isEqualTo("일부만 있음");
                    assertThat(v.status()).isEqualTo("PARTIAL");
                });
        assertThat(after.sawScope).contains("N1. 포장이 찢어져 온 상품을 교환할 수 있는지 → 판매자가 이 주문에 그 조치를 이미 했습니다")
                .contains("할 수 있는지 정하는 기준은 쓰지 마세요");
    }

    @Test
    @DisplayName("an exchange only under way settles nothing — the rule stays the seller's to settle")
    void anActionUnderWaySettlesNothing() {
        InquiryWorkItem work = tornPackage();
        sellerChecks.record(org, work.getId(), "IN_PROGRESS", "교환 접수함. 회수 요청해 둠", "SELLER:" + user, soon());
        GeneratedDraftView view = withQuestions(StubModel.writing("안내", "교환 접수되었고 회수 요청도 해 두었습니다."))
                .generateAs(org, work.getId(), "SELLER:" + user);

        assertThat(view.draft()).isNotNull();
        assertThat(questionsOf(work)).noneMatch(q -> "SETTLED_BY_ACTION".equals(q.getResolution()));
        assertThat(view.openQuestions()).anyMatch(q -> q.startsWith("포장이 찢어져 온 상품을 교환할 수 있는지 — "));
    }

    private void seedOption(String name, String status) {
        com.sellerops.product.ProductVariant v = new com.sellerops.product.ProductVariant();
        v.setOrgId(org);
        v.setProductId(productId);
        v.setChannelId(channel());
        v.setExternalVariantId(UUID.randomUUID().toString());
        v.setOptionName(name);
        v.setSellingStatus(status);
        v.setSource("NAVER:PRODUCT_DETAIL_API:v2");
        v.setObservedAt(Instant.parse("2026-09-01T00:00:00Z"));
        variants.save(v);
    }

    private InquiryWorkItem seedInquiry(String title, String body) {
        Inquiry q = new Inquiry();
        q.setOrgId(org);
        q.setChannelId(channel());
        q.setTitle(title);
        q.setBody(body);
        q.setStatus("UNANSWERED");
        q.setProductId(productId);
        q.setProductBinding(InquiryProductBinding.SOURCE_EXACT.name());
        q.setReceivedAt(Instant.parse("2026-09-17T00:00:00Z"));
        UUID inquiryId = inquiries.save(q).getId();
        InquiryWorkItem wi = new InquiryWorkItem();
        wi.setOrgId(org);
        wi.setInquiryId(inquiryId);
        wi.setSellerAccountId(sellerAccount(q.getChannelId()));
        wi.setChannelId(q.getChannelId());
        wi.setPhase(InquiryWorkItemPhase.PROPOSED);
        return workItems.save(wi);
    }

    /** A next-check date the record accepts — an action still in progress must say when the seller looks again. */
    private java.time.LocalDate soon() {
        return sellerChecks.today().plusDays(2);
    }

    /** Real rows for the foreign keys PostgreSQL enforces (2026-11-02); a code no product rule knows, as the random id was. */
    private UUID channel() {
        return channels.findByCode("DECISION-V2-QA").orElseGet(() -> {
            com.sellerops.channel.Channel c = new com.sellerops.channel.Channel();
            c.setCode("DECISION-V2-QA");
            c.setNameKo("결정 v2 QA");
            c.setStatus(com.sellerops.channel.ChannelStatus.AVAILABLE);
            c.setSortOrder(0);
            return channels.save(c);
        }).getId();
    }

    @Autowired com.sellerops.selleraccount.SellerAccountRepository sellerAccounts;

    private UUID sellerAccountId;

    /** One account per org and channel, as {@code uq_seller_accounts_api_org_channel} holds. */
    private UUID sellerAccount(UUID channelId) {
        if (sellerAccountId != null) {
            return sellerAccountId;
        }
        com.sellerops.selleraccount.SellerAccount a = new com.sellerops.selleraccount.SellerAccount();
        a.setOrgId(org);
        a.setChannelId(channelId);
        a.setConnectionStatus(com.sellerops.channel.ChannelStatus.PENDING);
        a.setFileUpload(false);
        sellerAccountId = sellerAccounts.save(a).getId();
        return sellerAccountId;
    }

    private InquiryDraftComposer composer(StubModel model) {
        InquiryDraftComposer c = new InquiryDraftComposer(workItems, inquiries, draftService, evidenceRows, retriever,
                assessor, model, allowingQuota(), variants, new DraftEvidenceSnippets(productChunks, orgChunks, memories),
                new com.sellerops.product.detail.ProductDetailEnrichmentTrigger(null, null, null, null, List.of(),
                        false), null, null, null, candidates);
        c.setSellerChecks(sellerCheckRows);
        c.setKindCorrections(kindRows);
        return c;
    }

    private static AgentQuotaService allowingQuota() {
        return new AgentQuotaService(null, null) {
            @Override
            public QuotaDecision consume(UUID orgId, AgentUsageKind kind, String idempotencyKey) {
                return new QuotaDecision(true, null, 0, 0);
            }
        };
    }

    /** The drafting model, stubbed — both the v11 call and the v12 call with a scope list. */
    static final class StubModel extends AgentDraftService {

        /** The figure check: confirms every figure — SAID_BACK for the customer's own — unless a test sets it. */
        java.util.function.Function<java.util.List<String>,
                java.util.Optional<java.util.List<com.sellerops.agent.llm.AgentDraftGenerator.FigureVerdict>>> figureCheck =
                figures -> java.util.Optional.of(java.util.stream.IntStream.range(0, figures.size())
                        .mapToObj(i -> new com.sellerops.agent.llm.AgentDraftGenerator.FigureVerdict(i + 1,
                                figures.get(i).contains("고객 문의에만") ? "SAID_BACK" : "SUPPORTED")).toList());
        java.util.List<String> sawFigures = new java.util.ArrayList<>();

        @Override
        public com.sellerops.agent.llm.AgentDraftGenerator.FigureResult checkFiguresMetered(
                java.util.UUID orgId, String question, java.util.List<com.sellerops.agent.llm.AgentDraftGenerator.Passage> evidence,
                String orderSentence, String specLine, String answerScope, String draft, java.util.List<String> figures) {
            sawFigures.addAll(figures);
            sawCheckScope = answerScope;
            checks++;
            return new com.sellerops.agent.llm.AgentDraftGenerator.FigureResult(figureCheck.apply(figures), "stub", null,
                    claimsFor.apply(draft), answeredFor.test(draft),
                    !coveredAnswers.isEmpty() ? coveredAnswers.poll()
                            : coveredAnswer != null ? coveredAnswer : everyItemAnswered(answerScope));
        }

        /** Item-by-item answers for successive checks, first to last; {@link #coveredAnswer} once they run out. */
        java.util.ArrayDeque<java.util.Map<String, String>> coveredAnswers = new java.util.ArrayDeque<>();

        /** The claims for this draft — {@link #claimsAnswer} for every draft unless a test tells drafts apart. */
        java.util.function.Function<String, java.util.List<com.sellerops.agent.llm.AgentDraftGenerator.ClaimVerdict>>
                claimsFor = d -> this.claimsAnswer;
        /** Whether this draft tries to answer — every draft unless a test says otherwise. */
        java.util.function.Predicate<String> answeredFor = d -> true;

        /** The check's item-by-item answer (v11) — every numbered item it was told to take up, ANSWERED, unless a test sets it. */
        java.util.Map<String, String> coveredAnswer;

        private static java.util.Map<String, String> everyItemAnswered(String scope) {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (String line : scope == null ? new String[0] : scope.split("\n")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^- (N\\d+)\\. ").matcher(line);
                if (m.find() && !line.contains("다루지 않습니다")) {
                    out.put(m.group(1), "ANSWERED");
                }
            }
            return out;
        }

        /** The need list the claim check was told — the drafter's own (v10). */
        String sawCheckScope;

        /** The claims the check reports — none unless a test sets them. */
        java.util.List<com.sellerops.agent.llm.AgentDraftGenerator.ClaimVerdict> claimsAnswer = java.util.List.of();
        int checks;
        private final AgentDraftResponseParser.ParsedDraft answer;
        final List<AgentDraftGenerator.Passage> sawKnowledge = new java.util.ArrayList<>();
        String sawScope;
        /** Every scope the drafter was given, in order — a rewrite's carries what was wrong. */
        final List<String> sawScopes = new java.util.ArrayList<>();
        /** What the second and later calls write — the first draft again unless a test queues another. */
        final java.util.Deque<AgentDraftResponseParser.ParsedDraft> then = new java.util.ArrayDeque<>();
        String sawSpec;
        int calls;

        private StubModel(AgentDraftResponseParser.ParsedDraft answer) {
            super(null, null, null);
            this.answer = answer;
        }

        static StubModel writing(String title, String comments) {
            return new StubModel(new AgentDraftResponseParser.ParsedDraft("general_reply", title, comments));
        }

        @Override
        public boolean isEnabledFor(UUID orgId) {
            return true;
        }

        @Override
        public String versionFor(UUID orgId) {
            return "stub-model/v1";
        }

        @Override
        public Optional<AgentDraftResponseParser.ParsedDraft> draft(
                UUID orgId, String title, String details, List<AgentDraftGenerator.Passage> knowledge,
                String orderState, String specScope, String style, String companyContext) {
            calls++;
            sawKnowledge.addAll(knowledge);
            sawSpec = specScope;
            return Optional.ofNullable(answer);
        }

        @Override
        public Optional<AgentDraftResponseParser.ParsedDraft> draft(
                UUID orgId, String title, String details, List<AgentDraftGenerator.Passage> knowledge,
                String orderState, String specScope, String style, String companyContext, String answerScope) {
            calls++;
            sawKnowledge.addAll(knowledge);
            sawSpec = specScope;
            sawScope = answerScope;
            sawScopes.add(answerScope);
            return Optional.ofNullable(calls > 1 && !then.isEmpty() ? then.poll() : answer);
        }
    }
}
