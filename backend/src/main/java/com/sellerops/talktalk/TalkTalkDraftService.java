package com.sellerops.talktalk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftResponseParser;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.common.ApiException;
import com.sellerops.common.DataOrigin;
import com.sellerops.inquiry.draft.InquiryClaimGuard;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.CostBearerCheck;
import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.claim.KnowledgeClaim;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.spine.KnowledgeAuthority;
import com.sellerops.knowledge.spine.KnowledgeEntry;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.knowledge.spine.SpineRetrieval;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeVariantScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepares a reply for the conversation's latest customer message (lane contract §5): from the confirmed guidance by
 * code, from the shared knowledge search through the one draft model door, or as the seller's own edit. Nothing is
 * sent from here.
 *
 * <p><b>Every text passes the same checks, whoever wrote it</b> (review item 11): the inquiry lane's claim guard
 * (unbacked promise, customer action, remedy, condition, law, cost bearer, figure) against what the draft cited, and
 * the seller's material as currently settled (a value the material states otherwise is POLICY_CONFLICT; a value the
 * material leaves unsettled is UNSETTLED_VALUE). A model draft that trips one is held and must be rewritten; a
 * seller's own sentence that trips one is held too, and goes out only when the seller confirms those exact holds —
 * the one-off exception the approved-reply hold decision already uses, never a silent pass.
 *
 * <p><b>Product scope.</b> A conversation that came from a product page names a product by its shop name. It is
 * bound to a product only when exactly one of the org's products has that name; otherwise the search runs at company
 * scope and the draft carries PRODUCT_UNRESOLVED, so no other product's source is used as this one's.
 */
@Service
public class TalkTalkDraftService {

    /**
     * The order sentence the drafter is given — a product constant, never an order id: a TalkTalk conversation carries
     * no order, so the reply must not state any one order's dispatch or arrival.
     */
    static final String ORDER_STATE = "이 톡톡 대화에는 확인된 주문 정보가 연결되어 있지 않습니다. 특정 주문의 출고·배송·도착 사실을 말하지 말고, "
            + "필요하면 담당자가 확인해 안내한다고만 말합니다.";
    static final int MAX_TEXT = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record EvidenceRef(String kind, String id, String title, String fingerprint) {
    }

    private final TalkTalkConversationRepository conversations;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkDraftRepository drafts;
    private final TalkTalkGuidanceService guidance;
    private final TalkTalkFactCheck facts;
    private final TalkTalkAccess access;
    private final TalkTalkClock clock;
    private final ProductRepository products;
    private final ObjectProvider<AgentDraftService> model;
    private final ObjectProvider<KnowledgeSpineService> spine;
    private final ObjectProvider<KnowledgeCurrencyService> currency;
    private final OrgKnowledgeSourceRepository orgSources;
    private final ProductKnowledgeSourceRepository productSources;
    private final ObjectProvider<AnswerStyleService> styles;

    /**
     * Holds no confirmation goes past (ReplyHoldService.FIX_FIRST): the material says otherwise, or is unsettled.
     * Every other hold on a seller's own sentence is the one-off exception the seller confirms.
     */
    public static final Set<String> FIX_FIRST = Set.of("POLICY_CONFLICT", "COST_CONFLICT", "UNSETTLED_VALUE",
            "KNOWLEDGE_CONFLICT", "FACT_CHECK_UNAVAILABLE", "EVIDENCE_UNREADABLE", "FORBIDDEN_PHRASE",
            "STYLE_CHECK_UNAVAILABLE");

    public TalkTalkDraftService(TalkTalkConversationRepository conversations, TalkTalkMessageRepository messages,
                                TalkTalkDraftRepository drafts, TalkTalkGuidanceService guidance,
                                TalkTalkFactCheck facts, TalkTalkAccess access, TalkTalkClock clock,
                                ProductRepository products, ObjectProvider<AgentDraftService> model,
                                ObjectProvider<KnowledgeSpineService> spine,
                                ObjectProvider<KnowledgeCurrencyService> currency,
                                OrgKnowledgeSourceRepository orgSources,
                                ProductKnowledgeSourceRepository productSources,
                                ObjectProvider<AnswerStyleService> styles) {
        this.styles = styles;
        this.orgSources = orgSources;
        this.productSources = productSources;
        this.conversations = conversations;
        this.messages = messages;
        this.drafts = drafts;
        this.guidance = guidance;
        this.facts = facts;
        this.access = access;
        this.clock = clock;
        this.products = products;
        this.model = model;
        this.spine = spine;
        this.currency = currency;
    }

    @Transactional
    public TalkTalkDraft prepare(UUID orgId, UUID userId, UUID conversationId, String mode) {
        access.member(orgId, userId);
        TalkTalkConversation conv = conversation(orgId, conversationId);
        TalkTalkMessage trigger = latestCustomer(conv);
        if ("MODEL".equals(mode)) {
            return model(orgId, userId, conv, trigger);
        }
        if (!"GUIDANCE".equals(mode)) {
            throw ApiException.badRequest("답변 준비 방식을 골라 주세요.");
        }
        TalkTalkGuidance g = guidance.current(orgId)
                .orElseThrow(() -> ApiException.conflict("NO_CONFIRMED_GUIDANCE", "확정된 배송 기준이 없어요."));
        TalkTalkGuidanceRules.Rendered rendered = TalkTalkGuidanceRules.render(g, clock.now());
        if (!rendered.ok()) {
            throw ApiException.conflict(rendered.refusal(), "배송 기준으로 답할 수 없어요: " + label(rendered.refusal()));
        }
        Set<String> holds = new LinkedHashSet<>();
        TalkTalkShippingClassifier.Result topic =
                TalkTalkShippingClassifier.classify(trigger.getTextContent(), trigger.getInputType());
        if (!topic.answerable()) {
            holds.add("QUESTION_" + topic.reason());
        }
        TalkTalkFactCheck.Result fact = facts.check(orgId, g, today());
        if (!fact.clear()) {
            holds.add(fact.reason());
        }
        if (fact.productExceptions() && conv.getLastProductName() != null) {
            holds.add("PRODUCT_EXCEPTION_POSSIBLE");
        }
        List<EvidenceRef> evidence = List.of(guidanceRef(g));
        return save(orgId, userId, conv, trigger, TalkTalkDraft.AuthorKind.GUIDANCE, rendered.text(),
                holds.isEmpty() ? TalkTalkDraft.Basis.GROUNDED : TalkTalkDraft.Basis.NEEDS_HUMAN, holds, evidence,
                g.getId());
    }

    @Transactional
    public TalkTalkDraft edit(UUID orgId, UUID userId, UUID conversationId, String text, Integer baseVersion) {
        access.member(orgId, userId);
        TalkTalkConversation conv = conversation(orgId, conversationId);
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            throw ApiException.badRequest("보낼 문장을 적어 주세요.");
        }
        if (body.length() > MAX_TEXT) {
            throw ApiException.badRequest("답변은 " + MAX_TEXT + "자까지 쓸 수 있어요.");
        }
        TalkTalkDraft head = drafts.findFirstByConversationIdOrderByVersionDesc(conv.getId()).orElse(null);
        if (baseVersion != null && (head == null || head.getVersion() != baseVersion)) {
            throw ApiException.conflict("DRAFT_CHANGED", "그사이 답변이 바뀌었어요. 최신 답변을 보고 다시 고쳐 주세요.");
        }
        TalkTalkMessage trigger = latestCustomer(conv);
        List<EvidenceRef> evidence = head == null ? List.of() : evidence(head);
        Set<String> holds = textHolds(orgId, productOf(orgId, conv), body, evidenceTexts(orgId, evidence),
                trigger.getTextContent());
        return save(orgId, userId, conv, trigger, TalkTalkDraft.AuthorKind.SELLER_EDIT, body,
                TalkTalkDraft.Basis.SELLER_WRITTEN, holds, evidence, head == null ? null : head.getGuidanceId());
    }

    private TalkTalkDraft model(UUID orgId, UUID userId, TalkTalkConversation conv, TalkTalkMessage trigger) {
        AgentDraftService drafter = model.getIfAvailable();
        if (drafter == null || !drafter.isEnabledFor(orgId)) {
            throw ApiException.conflict("MODEL_OFF", "이 가게는 AI 답변이 꺼져 있어요. 배송 기준 답변이나 직접 작성을 써 주세요.");
        }
        KnowledgeSpineService search = spine.getIfAvailable();
        if (search == null) {
            throw ApiException.conflict("SEARCH_UNAVAILABLE", "참고 자료를 찾을 수 없어요.");
        }
        String asked = trigger.getTextContent();
        Set<String> holds = new LinkedHashSet<>();
        UUID productId = productOf(orgId, conv);
        if (conv.getLastProductName() != null && productId == null) {
            holds.add("PRODUCT_UNRESOLVED");
        }
        SpineRetrieval found = search.retrieveForProduct(orgId, productId, RetrievalQuery.ofCustomer(null, asked),
                KnowledgeVariantScope.unresolved());
        List<AgentDraftGenerator.Passage> passages = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        List<KnowledgeEntry> used = new ArrayList<>(found.evidence());
        used.addAll(found.draftContext());
        for (KnowledgeEntry e : used) {
            passages.add(new AgentDraftGenerator.Passage(e.authority() == null ? "자료" : e.authority().name(),
                    e.title(), e.text()));
            evidence.add(new EvidenceRef("KNOWLEDGE", e.entryId(), e.title(), TalkTalkHashes.sha256(nz(e.text()))));
        }
        Optional<TalkTalkGuidance> g = guidance.current(orgId);
        g.ifPresent(x -> {
            passages.add(new AgentDraftGenerator.Passage("판매자 운영 기준", "톡톡 배송 기준 v" + x.getVersion(),
                    TalkTalkGuidanceRules.ruleSentence(x)));
            evidence.add(guidanceRef(x));
        });
        UUID guidanceId = g.map(TalkTalkGuidance::getId).orElse(null);
        if (passages.isEmpty()) {
            holds.add("NO_ANSWER_BASIS");
            return save(orgId, userId, conv, trigger, TalkTalkDraft.AuthorKind.MODEL, null,
                    TalkTalkDraft.Basis.NEEDS_HUMAN, holds, evidence, null);
        }
        // §8: the latest customer message only — no TalkTalk user value, partner id, history or product URL.
        Optional<AgentDraftResponseParser.ParsedDraft> parsed = drafter.draft(orgId, null, asked, passages,
                ORDER_STATE, null, null, null, null);
        String body = parsed.map(AgentDraftResponseParser.ParsedDraft::comments).map(String::strip)
                .filter(s -> !s.isEmpty()).orElse(null);
        if (body == null) {
            holds.add("MODEL_NO_DRAFT");
            return save(orgId, userId, conv, trigger, TalkTalkDraft.AuthorKind.MODEL, null,
                    TalkTalkDraft.Basis.NEEDS_HUMAN, holds, evidence, guidanceId);
        }
        if (body.length() > MAX_TEXT) {
            body = body.substring(0, MAX_TEXT);
            holds.add("TOO_LONG");
        }
        holds.addAll(textHolds(orgId, productId, body, evidenceTexts(orgId, evidence), asked));
        TalkTalkShippingClassifier.Result topic = TalkTalkShippingClassifier.classify(asked, trigger.getInputType());
        if ("ORDER_SPECIFIC".equals(topic.reason()) || "ARRIVAL".equals(topic.reason())) {
            holds.add("QUESTION_" + topic.reason());
        }
        return save(orgId, userId, conv, trigger, TalkTalkDraft.AuthorKind.MODEL, body,
                holds.isEmpty() ? TalkTalkDraft.Basis.GROUNDED : TalkTalkDraft.Basis.NEEDS_HUMAN, holds, evidence,
                guidanceId);
    }

    /**
     * The checks a reply text must pass against what it cites and the seller's material as now settled. Codes only;
     * empty means clean. Without the material's state the check cannot run and says so — it never passes silently.
     */
    Set<String> textHolds(UUID orgId, UUID productId, String body, List<String> evidenceTexts, String asked) {
        Set<String> holds = new LinkedHashSet<>();
        for (InquiryClaimGuard.Violation v : InquiryClaimGuard.unsupportedClaims(body, evidenceTexts, List.of(), asked,
                ORDER_STATE)) {
            holds.add("CLAIM_" + v.kind().name());
        }
        // Who pays, read against every document in use as ReviewDraftRecheck.settleCosts reads it: a document that
        // says the other way is COST_CONFLICT (fixed first, never confirmed past); one that only fails to back it stays
        // CLAIM_COST (the one-off exception).
        List<CostBearerCheck.Source> rules = rules(orgId, productId);
        for (CostBearerCheck.Finding f : CostBearerCheck.judge(body, evidenceTexts, rules)) {
            if (f.verdict() == CostBearerCheck.Verdict.CONFLICT) {
                holds.add("COST_CONFLICT");
            } else if (f.verdict() != CostBearerCheck.Verdict.BACKED) {
                holds.add("CLAIM_COST");
            }
        }
        // The company's forbidden phrases — the same helper InquiryDraftRecheck and the composer use. Fixed first.
        AnswerStyleService styleService = styles.getIfAvailable();
        if (styleService == null) {
            holds.add("STYLE_CHECK_UNAVAILABLE");
        } else if (!AnswerStyleInstruction.forbiddenPresentIn(body, styleService.profileFor(orgId)).isEmpty()) {
            holds.add("FORBIDDEN_PHRASE");
        }
        KnowledgeCurrencyService svc = currency.getIfAvailable();
        if (svc == null) {
            holds.add("FACT_CHECK_UNAVAILABLE");
            return holds;
        }
        List<KnowledgeState.Source> extra = new ArrayList<>();
        guidance.current(orgId).filter(g -> g.getCutoffHour() != null).ifPresent(g -> extra.add(
                new KnowledgeState.Source(g.getId(), "ORG", null, "ORG", "톡톡 배송 기준 v" + g.getVersion(),
                        KnowledgeAuthority.SELLER_POLICY.rank(), null, List.of(new KnowledgeClaim(
                        ClaimAttribute.DISPATCH_CUTOFF, ClaimCondition.GENERAL, null, g.getCutoffHour(),
                        g.getCutoffHour(), ClaimAttribute.Unit.HOUR, false, TalkTalkGuidanceRules.ruleSentence(g))))));
        KnowledgeState state;
        try {
            state = svc.stateFor(orgId, productId, today(), null, extra);
        } catch (RuntimeException e) {
            holds.add("FACT_CHECK_UNAVAILABLE");
            return holds;
        }
        for (KnowledgeClaim said : ClaimReader.readReply(body)) {
            if (said.historical() || state.supports(said)) {
                continue;
            }
            if (!state.stating(Set.of(said.attribute())).isEmpty()) {
                holds.add("POLICY_CONFLICT");
            }
        }
        if (!state.unsettledIn(body).isEmpty()) {
            holds.add("UNSETTLED_VALUE");
        }
        return holds;
    }

    /** Every company document in use, and the bound product's, titled — the rules a cost statement is read against. */
    private List<CostBearerCheck.Source> rules(UUID orgId, UUID productId) {
        List<CostBearerCheck.Source> out = new ArrayList<>();
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (s.isActive() && (s.getDataOrigin() == null || s.getDataOrigin() == DataOrigin.REAL)) {
                out.add(new CostBearerCheck.Source(nz(s.getTitle()), nz(s.getBody())));
            }
        }
        if (productId != null) {
            for (ProductKnowledgeSource s : productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId,
                    productId)) {
                if (s.isActive() && (s.getDataOrigin() == null || s.getDataOrigin() == DataOrigin.REAL)) {
                    out.add(new CostBearerCheck.Source(nz(s.getTitle()), nz(s.getBody())));
                }
            }
        }
        return out;
    }

    /** The cited sources' current text — what an edit and a recheck are measured against. */
    private List<String> evidenceTexts(UUID orgId, List<EvidenceRef> refs) {
        List<String> out = new ArrayList<>();
        Map<String, KnowledgeEntry> entries = null;
        for (EvidenceRef ref : refs) {
            if ("GUIDANCE".equals(ref.kind())) {
                // The rule, and the sentence set the owner confirmed for it as rendered now.
                guidance.current(orgId).filter(g -> g.getId().toString().equals(ref.id())).ifPresent(g -> {
                    out.add(TalkTalkGuidanceRules.ruleSentence(g));
                    TalkTalkGuidanceRules.Rendered r = TalkTalkGuidanceRules.render(g, clock.now());
                    if (r.ok()) {
                        out.add(r.text());
                    }
                });
            } else if ("KNOWLEDGE".equals(ref.kind())) {
                if (entries == null) {
                    entries = entries(orgId);
                }
                KnowledgeEntry e = entries.get(ref.id());
                if (e != null) {
                    out.add(e.text());
                }
            }
        }
        return out;
    }

    /** A product of this org whose name is exactly the conversation's product name, when there is exactly one. */
    UUID productOf(UUID orgId, TalkTalkConversation conv) {
        String name = conv.getLastProductName();
        if (name == null || name.isBlank()) {
            return null;
        }
        // A narrow id read (never every product row of the org): exactly one product with this name, or none.
        List<UUID> named = products.findIdsByOrgIdAndName(orgId, name.strip());
        return named.size() == 1 ? named.get(0) : null;
    }

    private TalkTalkDraft save(UUID orgId, UUID userId, TalkTalkConversation conv, TalkTalkMessage trigger,
                               TalkTalkDraft.AuthorKind kind, String text, TalkTalkDraft.Basis basis, Set<String> holds,
                               List<EvidenceRef> evidence, UUID guidanceId) {
        int version = drafts.findFirstByConversationIdOrderByVersionDesc(conv.getId())
                .map(TalkTalkDraft::getVersion).orElse(0) + 1;
        TalkTalkDraft d = new TalkTalkDraft();
        d.setOrgId(orgId);
        d.setConversationId(conv.getId());
        d.setTriggerMessageId(trigger.getId());
        d.setVersion(version);
        d.setAuthorKind(kind);
        d.setTextContent(text);
        d.setContentFingerprint(TalkTalkHashes.replyFingerprint(text));
        d.setBasis(basis);
        d.setHoldReasons(holds.isEmpty() ? null : String.join(",", holds));
        d.setEvidenceJson(json(evidence));
        d.setGuidanceId(guidanceId);
        d.setCreatedBy(userId);
        return drafts.save(d);
    }

    /**
     * What a send must find, at queueing and again at dispatch: each cited source still in use with the same content,
     * and the text still clean against the material as now settled. Codes only; an evidence record that cannot be read
     * is EVIDENCE_UNREADABLE — it never passes.
     */
    @Transactional(readOnly = true)
    public List<String> recheck(UUID orgId, TalkTalkDraft d) {
        List<String> codes = new ArrayList<>();
        List<EvidenceRef> refs = evidence(d);
        Map<String, KnowledgeEntry> current = null;
        for (EvidenceRef ref : refs) {
            if ("GUIDANCE".equals(ref.kind())) {
                Optional<TalkTalkGuidance> now = guidance.current(orgId);
                if (now.isEmpty() || !now.get().getId().toString().equals(ref.id())) {
                    codes.add("GUIDANCE_RETIRED");
                } else if (!now.get().getFingerprint().equals(ref.fingerprint())) {
                    codes.add("GUIDANCE_CHANGED");
                } else {
                    TalkTalkFactCheck.Result fact = facts.check(orgId, now.get(), today());
                    if (!fact.clear()) {
                        codes.add(fact.reason());
                    }
                }
            } else if ("KNOWLEDGE".equals(ref.kind())) {
                if (current == null) {
                    current = entries(orgId);
                }
                KnowledgeEntry e = current.get(ref.id());
                if (e == null) {
                    codes.add("SOURCE_RETIRED");
                } else if (!TalkTalkHashes.sha256(nz(e.text())).equals(ref.fingerprint())) {
                    codes.add("SOURCE_CHANGED");
                }
            } else {
                codes.add("EVIDENCE_UNREADABLE");
            }
        }
        if (d.getAuthorKind() == TalkTalkDraft.AuthorKind.GUIDANCE && codes.isEmpty()) {
            // Code-rendered from the confirmed values: it must still be what the guidance says at this moment
            // (a draft written at 11:50 that says 「오늘 출고」 is not sent at 12:10).
            TalkTalkGuidanceRules.Rendered now = TalkTalkGuidanceRules.render(guidance.current(orgId).orElse(null),
                    clock.now());
            if (!now.ok() || !now.text().equals(d.getTextContent())) {
                codes.add(now.ok() ? "TEXT_CHANGED" : now.refusal());
            }
            AnswerStyleService styleService = styles.getIfAvailable();
            if (styleService == null) {
                codes.add("STYLE_CHECK_UNAVAILABLE");
            } else if (!AnswerStyleInstruction.forbiddenPresentIn(d.getTextContent(),
                    styleService.profileFor(orgId)).isEmpty()) {
                codes.add("FORBIDDEN_PHRASE");
            }
        } else if (d.getTextContent() != null && codes.isEmpty()) {
            TalkTalkConversation conv = conversations.findByIdAndOrgId(d.getConversationId(), orgId).orElse(null);
            TalkTalkMessage trigger = d.getTriggerMessageId() == null ? null
                    : messages.findByIdAndOrgId(d.getTriggerMessageId(), orgId).orElse(null);
            codes.addAll(textHolds(orgId, conv == null ? null : productOf(orgId, conv), d.getTextContent(),
                    evidenceTexts(orgId, refs), trigger == null ? null : trigger.getTextContent()));
        }
        return codes.stream().distinct().toList();
    }

    private Map<String, KnowledgeEntry> entries(UUID orgId) {
        KnowledgeSpineService search = spine.getIfAvailable();
        return search == null ? Map.of() : search.entries(orgId, null).stream()
                .collect(Collectors.toMap(KnowledgeEntry::entryId, e -> e, (a, b) -> a));
    }

    public static List<EvidenceRef> evidence(TalkTalkDraft d) {
        try {
            List<EvidenceRef> refs = JSON.readValue(d.getEvidenceJson(), new TypeReference<List<EvidenceRef>>() { });
            return refs == null ? List.of(new EvidenceRef("UNREADABLE", "", "", "")) : refs;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of(new EvidenceRef("UNREADABLE", "", "", ""));
        }
    }

    private static String json(List<EvidenceRef> refs) {
        try {
            return JSON.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EvidenceRef guidanceRef(TalkTalkGuidance g) {
        return new EvidenceRef("GUIDANCE", g.getId().toString(), "톡톡 배송 기준 v" + g.getVersion(), g.getFingerprint());
    }

    private TalkTalkConversation conversation(UUID orgId, UUID id) {
        return conversations.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("대화를 찾을 수 없어요."));
    }

    private TalkTalkMessage latestCustomer(TalkTalkConversation conv) {
        TalkTalkMessage m = messages
                .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                        conv.getId(), TalkTalkMessage.Author.CUSTOMER)
                .orElseThrow(() -> ApiException.conflict("NO_CUSTOMER_MESSAGE", "답할 고객 메시지가 없어요."));
        if (m.getTextContent() == null) {
            if (TalkTalkEventParser.nonText(m.getInputType())) {
                throw ApiException.conflict("NON_TEXT",
                        "사진·카드 메시지는 내용이 저장되지 않아 답변을 준비할 수 없어요. 톡톡 파트너센터에서 확인해 주세요.");
            }
            throw ApiException.conflict("MESSAGE_PURGED", "보관 기간이 지나 원문이 지워졌어요.");
        }
        return m;
    }

    private LocalDate today() {
        return clock.now().atZone(TalkTalkGuidanceRules.KST).toLocalDate();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String label(String code) {
        return TalkTalkReasons.label(code);
    }
}
