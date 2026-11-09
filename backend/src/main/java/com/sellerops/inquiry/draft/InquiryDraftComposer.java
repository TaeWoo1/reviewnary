package com.sellerops.inquiry.draft;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftResponseParser;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.QuotaDecision;
import com.sellerops.common.ApiException;
import com.sellerops.common.MarkupText;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.draft.dto.GeneratedDraftView;
import com.sellerops.inquiry.draft.dto.KnowledgeGapView;
import com.sellerops.knowledge.RetrievalOutcome;
import com.sellerops.knowledge.candidate.KnowledgeCandidate;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.inquiry.reply.InquiryReplyDraftService;
import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.order.fact.OrderFact;
import com.sellerops.organization.profile.SellerProfileService;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.catalogue.CatalogueInvestigator;
import com.sellerops.product.detail.ProductDetailEnrichmentTrigger;
import com.sellerops.product.detail.image.ProductDetailImageKnowledge;
import com.sellerops.product.library.KnowledgeVariantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Inquiry Draft v1 — write a reply the seller can send, from what SellerOps can actually prove.
 *
 * <p><b>The order of evidence is the product.</b> The customer's question, then the canonical product
 * it is about, then the seller's own knowledge about that product. Nothing else is consulted, and
 * nothing that is not consulted is cited. When the knowledge is thin the draft is still written — a
 * seller with an empty library is not helped by a refusal — but it is written from the question alone
 * and the screen says so, in one sentence, above the text
 * ({@link DraftKnowledgeState#messageKo()}).
 *
 * <p><b>Two provenances that must not be confused.</b> A passage retrieved from 상품 지식 is something
 * a person at this company typed; a product FACT (brand, category, listing title) is something a
 * marketplace told us. They are different claims with different failure modes, and this class emits
 * only the first kind of evidence. The agent runtime keeps the same separation
 * ({@code PRODUCT_KNOWLEDGE_DOC} vs {@code PRODUCT_FACT}).
 *
 * <p><b>It writes a draft and nothing else.</b> No approval, no intent, no execution. The result is
 * one more append-only version on the work item — the same object a seller who typed it by hand would
 * have produced, distinguishable only by the provenance stamped on it.
 *
 * <p><b>One bounded marketplace READ can happen here, and it is new.</b> Before writing, this asks
 * {@link ProductDetailEnrichmentTrigger} whether the bound product's 상세페이지 has ever been read;
 * if not, one listing is read and indexed. That is the "actionable inquiry + exact attribution +
 * missing knowledge" trigger, and it is the only reason a draft touches a channel besides the order
 * fact. It cannot fail the draft: every outcome of that call is swallowed and reported.
 *
 * <p><b>"No basis" and "did not run" are different answers</b> (product-owner, 2026-08-27).
 * {@link AnswerBasisState} is a statement about EVIDENCE. A spent budget, a capability that is off,
 * a vendor that did not answer, and a 상세페이지 read that failed are statements about the MACHINERY,
 * and reporting them as {@code NO_ANSWER_BASIS} told a seller to go write knowledge they already
 * had. They travel in {@code unavailableMessage} instead — and when a detail read failed, the basis
 * verdict is still reported but must not be the sentence on screen: we did not finish looking, so
 * "there is nothing to find" is not ours to say.
 *
 * <p><b>When there is no basis, nothing is written</b> (product-owner, 2026-08-26).
 * {@link AnswerBasisState#NO_ANSWER_BASIS} means no current evidence applies, and in that state no
 * model is called and no version is saved. The deterministic drafter that used to fill the gap is
 * gone with it: its output was 「확인한 뒤 정확한 안내를 드리겠습니다」 — a promise SellerOps made on
 * the seller's behalf with nothing behind it, in exactly the state where nothing is behind it. A
 * seller-approved fallback belongs to Organization Answer Style, which does not exist yet, and until
 * it does the honest screen is one that says what is missing.
 */
@Service
public class InquiryDraftComposer {

    // How many passages reach the model, and how much of the question is used to find them, are
    // both decisions of the retrieval and live on InquiryEvidenceRetriever. They used to be here,
    // when there was one lane and this class WAS the retrieval.

    private final InquiryWorkItemRepository workItems;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InquiryDraftComposer.class);

    private final InquiryRepository inquiries;
    private final InquiryReplyDraftService drafts;
    private final InquiryDraftEvidenceRepository evidence;
    private final InquiryEvidenceRetriever retriever;
    private final AgentDraftService model;
    private final AgentQuotaService quota;
    private final ProductVariantRepository variants;
    private final DraftEvidenceSnippets snippets;
    private final ProductDetailEnrichmentTrigger detail;
    private final ProductDetailImageKnowledge images;
    private final AnswerStyleService styles;
    private final SellerProfileService profiles;

    /**
     * The three operational sentences, and one rule covering all of them: <b>none of them says
     * anything about the seller's knowledge.</b> Each names what did not run and what to do next,
     * and none of them is 「답변 기준이 필요합니다」 — that sentence belongs to
     * {@link AnswerBasisState#NO_ANSWER_BASIS} alone.
     */
    static final String CAPABILITY_OFF = "AI 답변 초안 기능이 켜져 있지 않아요.";
    static final String MODEL_FAILED = "답변 초안을 생성하지 못했어요. 잠시 후 다시 시도해 주세요.";
    static final String DETAIL_READ_FAILED = "상품 상세 정보를 확인하지 못했어요.";
    /**
     * The third answer, and the first one with a producer.
     *
     * <p>Before the image lane existed there was no moment at which SellerOps was part-way through
     * learning something about a product — so this sentence would have been a state nobody sets, and
     * it was deliberately not written. A picture whose reading is in flight is that moment.
     */
    static final String DETAIL_READ_PENDING = "상품 상세 정보를 확인 중이에요.";
    /**
     * The fifth, and the only one caused by the company's own setting.
     *
     * <p>A generated reply that carries a phrase this company has banned is REFUSED, not edited.
     * Deleting the words out of the sentence would leave a reply whose meaning nobody chose, and
     * saving it anyway would make 「사용하지 않을 표현」 a preference rather than a rule.
     */
    static final String UNSUPPORTED_CLAIM = "근거에 없는 약속이나 요청이 들어가 초안을 저장하지 않았어요. "
            + "다시 생성해 보시거나 직접 작성해 주세요.";

    /** A grant that leaves out the state the seller's policy limits it to — 「미개봉」 (2026-10-31, C06). */
    static final String DROPPED_CONDITION = "근거에 있는 교환·반품 조건(미개봉 등)을 빼고 된다고 적어 초안을 저장하지 "
            + "않았어요. 다시 생성해 보시거나 직접 작성해 주세요.";

    /** The policy applied where the law does not let it apply — 「미개봉 상품만 반품」, a fault held to 단순 변심 (2026-11-01). */
    static final String LAW_CONFLICT = "반품·환불을 개봉 여부로 제한하거나, 불량·오배송에 단순 변심 조건을 붙여 법정 청약철회 "
            + "기준과 맞지 않을 수 있어 초안을 저장하지 않았어요. 반품 조건을 확인한 뒤 직접 작성해 주세요.";

    private DraftReviewPendingRepository pending;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPending(DraftReviewPendingRepository pending) {
        this.pending = pending;
    }

    /** What each figure of a saved draft was written from (V166); null where a test wires the composer without it. */
    private InquiryFigureGrounds figureGrounds;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFigureGrounds(InquiryFigureGrounds figureGrounds) {
        this.figureGrounds = figureGrounds;
    }

    /**
     * Keep a draft the ceiling stopped from being checked — one per work item, the latest — with which try it is and why
     * the first was refused (2026-11-02, fifth review): a resume carries on from that try, never from the first again.
     */
    private void hold(UUID orgId, UUID workItemId, AgentDraftResponseParser.ParsedDraft draft, String inputsHash,
                      int attempt, String rewriteBecause) {
        hold(orgId, workItemId, draft, inputsHash, attempt, rewriteBecause, DraftReviewPending.CHECK);
    }

    /** As above, with what the held text is waiting for — the first check, or only the one second look (V181). */
    private void hold(UUID orgId, UUID workItemId, AgentDraftResponseParser.ParsedDraft draft, String inputsHash,
                      int attempt, String rewriteBecause, String awaiting) {
        if (pending == null) {
            return;
        }
        DraftReviewPending row = pending.findByOrgIdAndWorkItemId(orgId, workItemId).orElseGet(DraftReviewPending::new);
        row.setOrgId(orgId);
        row.setWorkItemId(workItemId);
        row.setCategory(draft.category());
        row.setTitle(draft.title());
        row.setBody(draft.comments());
        row.setInputsHash(inputsHash);
        row.setAttempt(attempt);
        row.setRewriteBecause(rewriteBecause);
        row.setAwaiting(awaiting);
        pending.save(row);
    }

    /**
     * What a held draft was written over: the question, every passage shown, the order sentence, the model, and every
     * instruction the draft call was given (규격 line, style, company description, answer scope).
     */
    static String pendingInputsHash(String title, String details, List<AgentDraftGenerator.Passage> shown,
                                    String orderSentence, String modelVersion, List<String> instructions) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\u0001').append(details).append('\u0001').append(orderSentence).append('\u0001')
                .append(modelVersion).append('\u0002');
        for (String instruction : instructions) {
            sb.append(instruction).append('\u0001');
        }
        sb.append('\u0002');
        for (AgentDraftGenerator.Passage p : shown) {
            sb.append(p.scopeLabel()).append('\u0001').append(p.heading()).append('\u0001').append(p.text())
                    .append('\u0002');
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A figure the evidence does not write, or writes only in another unit (review 2026-10-28). */
    static String unsupportedFigure(List<InquiryClaimGuard.Violation> claims) {
        return "숫자 " + String.join(", ", claims.stream().map(InquiryClaimGuard.Violation::matched).distinct().toList())
                + "이(가) 근거에 없거나 단위가 달라 초안을 저장하지 않았어요. 근거를 확인하고 직접 작성해 주세요.";
    }

    /** A figure the model did not confirm is about the same thing under the same condition — or could not check. */
    static String unconfirmedFigure(List<String> values) {
        return "숫자 " + String.join(", ", values) + "이(가) 근거와 같은 대상·조건의 값인지 확인되지 않아 초안을 "
                + "저장하지 않았어요. 근거를 확인하고 직접 작성해 주세요.";
    }

    /**
     * The day's AI ceiling was reached before the draft could be checked (owner, 2026-10-29): the draft is held, not
     * thrown away and not shown unchecked — the next generate checks it without writing it again.
     */
    static final String REVIEW_PENDING = "오늘 AI 사용량 상한에 닿아 초안 내용을 근거와 대조하지 못했어요. 초안은 "
            + "「검토 대기」로 보관했고, 한도가 풀린 뒤 다시 누르면 새로 쓰지 않고 검토부터 이어서 해요.";

    /**
     * The first check ran and left an item out, and the ceiling stopped the one second look (2026-11-05 eleventh review):
     * the draft is kept as 「추가 검사 대기」 and a resume runs only that look — never a new draft, never the first check
     * again, never a third.
     */
    static final String RECHECK_PENDING = "초안을 근거와 한 번 대조했지만 확인이 끝나지 않았고, 오늘 AI 사용량 상한에 닿아 한 번 더 "
            + "대조하지 못했어요. 초안은 「추가 검사 대기」로 보관했고, 한도가 풀린 뒤 다시 누르면 새로 쓰지 않고 그 검사만 이어서 해요.";

    static final String CLAIM_CHECK_UNAVAILABLE = "초안의 내용을 근거와 대조하지 못해 저장하지 않았어요. 다시 생성해 "
            + "보시거나 직접 작성해 주세요.";

    /** The meter's step names of the one rewrite (2026-11-02): the draft written again, and its check. */
    public static final String REDRAFT_STEP = "REDRAFT";
    public static final String REDRAFT_CHECK_STEP = "REDRAFT_CHECK";
    /** The one second look at a check that came back incomplete (2026-11-05 tenth review) — its own step, so it is seen. */
    public static final String RECHECK_STEP = "CLAIM_RECHECK";

    private static final String REWRITE_HEAD = "- 다시 쓰기: 앞 초안은 아래 이유로 저장되지 않았습니다. 같은 근거, 같은 답변 범위로 다시 "
            + "씁니다. 지적된 부분만 고치고, 근거로 답한 부분은 그대로 둡니다.\n  - 이유: ";

    static final String REWRITE_BLANK = REWRITE_HEAD + "【확인: …】 표시를 썼습니다. 그런 표시 없이, 근거가 답하는 데까지만 씁니다";

    static final String REWRITE_NO_ANSWER = REWRITE_HEAD + "문의에 대한 답이 없었습니다. 「이번 답변이 다룰 내용」의 각 항목에 근거로 "
            + "답합니다";

    static String rewriteForbidden(List<String> phrases) {
        return REWRITE_HEAD + "회사가 쓰지 않기로 한 표현(" + phrases.stream().map(x -> "「" + x + "」")
                .collect(java.util.stream.Collectors.joining(", ")) + ")을 썼습니다. 그 표현 없이 씁니다";
    }

    static String rewriteUnsupported(String statement) {
        return REWRITE_HEAD + "「" + statement + "」은(는) 보여 드린 근거로 확인되지 않습니다. 근거에 없는 내용이면 쓰지 않고, "
                + "근거에 있는 내용이면 근거에 적힌 대로 씁니다. 판매자가 따로 안내하거나 확인한다는 말도 새로 쓰지 않습니다";
    }

    /**
     * What a rewrite is told about the guard's refusal — or null when a rewrite cannot fix it: a number the evidence does
     * not carry may be an answer the evidence lacks, and a condition against the law is the seller's to settle.
     */
    public static String rewriteClaims(List<InquiryClaimGuard.Violation> claims) {
        if (claims.stream().anyMatch(c -> c.kind() == InquiryClaimGuard.Kind.FIGURE
                || c.kind() == InquiryClaimGuard.Kind.LAW)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        List<String> said = claims.stream().filter(c -> c.kind() != InquiryClaimGuard.Kind.CONDITION)
                .map(c -> "「" + c.matched() + "」").distinct().toList();
        if (!said.isEmpty()) {
            parts.add("근거에 없는 후속 약속 · 요청 · 보상(" + String.join(", ", said) + ")을 썼습니다. 그 말을 빼고, 판매자 쪽 후속 "
                    + "안내도 새로 약속하지 않습니다");
        }
        List<String> dropped = claims.stream().filter(c -> c.kind() == InquiryClaimGuard.Kind.CONDITION)
                .map(c -> "「" + c.matched() + "」").distinct().toList();
        if (!dropped.isEmpty()) {
            parts.add("근거가 붙인 조건을 빼고 썼습니다(" + String.join(", ", dropped) + "). 근거의 조건을 함께 씁니다");
        }
        return REWRITE_HEAD + String.join(" / ", parts);
    }

    /** The drafter's call, its tokens on the row charged for it. */
    private Optional<AgentDraftResponseParser.ParsedDraft> writeDraft(
            UUID orgId, String title, String details, List<AgentDraftGenerator.Passage> shown,
            InquiryEvidenceRetriever.InquiryEvidence retrieved, String specSentence, AnswerStyleProfile style, String company,
            com.sellerops.inquiry.decision.NeedDecision needDecision, String scope, UUID usageId) {
        com.sellerops.agent.llm.AgentLlmCallCapture.Captured<Optional<AgentDraftResponseParser.ParsedDraft>> call =
                com.sellerops.agent.llm.AgentLlmCallCapture.capture(() -> needDecision != null
                        ? model.draft(orgId, title, details, shown, retrieved.order().messageKo(), specSentence,
                        AnswerStyleInstruction.of(style), company, scope)
                        : model.draft(orgId, title, details, shown, retrieved.order().messageKo(), specSentence,
                        AnswerStyleInstruction.of(style), company));
        if (call.metrics() != null) {
            quota.recordOutcome(usageId, call.metrics().elapsedMs(), call.metrics().promptTokens(),
                    call.metrics().completionTokens());
        }
        return call.value();
    }

    static String unsupportedStatement(String excerpt) {
        return "초안의 「" + excerpt + "」이(가) 등록된 근거로 확인되지 않아 저장하지 않았어요. 근거를 확인하고 직접 작성해 "
                + "주세요.";
    }

    /**
     * Why no draft came back, as a closed word beside the sentence (2026-11-01 eighth review: an evaluation that reads
     * only the sentence cannot tell a draft the checks refused from a run that never reached a model).
     * <ul>
     *   <li>{@code HELD_BY_CHECK} — a model wrote a draft and a check refused it: a promise, a condition, the law, a number,
     *       a banned phrase, a blank, two sources that disagree, a statement the evidence does not confirm;</li>
     *   <li>{@code WAITING_FOR_CHECK} — the draft is kept until the day's ceiling lets it be checked;</li>
     *   <li>{@code NOT_RUN} — the machinery did not finish: the switch, the vendor, the ceiling, a detail read, the
     *       claim check or the needs judge that did not answer.</li>
     * </ul>
     * Null when nothing was unavailable.
     */
    public static String unavailableKind(String message) {
        if (message == null) {
            return null;
        }
        if (REVIEW_PENDING.equals(message) || RECHECK_PENDING.equals(message)) {
            return "WAITING_FOR_CHECK";
        }
        if (java.util.Set.of(UNSUPPORTED_CLAIM, DROPPED_CONDITION, LAW_CONFLICT, STYLE_FORBIDDEN_PHRASE, UNCONFIRMED_TEXT,
                CONFLICTING_EVIDENCE, NO_ANSWER_IN_DRAFT, COMPLETION_NOT_RECORDED, UNREQUESTED_ASK).contains(message)
                || message.endsWith(unsupportedStatement("").substring(unsupportedStatement("").indexOf("」") + 1))
                || message.startsWith("숫자 ") && message.endsWith("근거를 확인하고 직접 작성해 주세요.")) {
            return "HELD_BY_CHECK";
        }
        return "NOT_RUN";
    }

    static final String CONFLICTING_EVIDENCE = "등록된 근거끼리 서로 다르게 적혀 있어 어느 쪽으로 답할지 정할 수 없어요. "
            + "근거를 정리한 뒤 다시 생성하거나 직접 작성해 주세요.";

    /**
     * <b>The claim check</b> on a model-written inquiry draft (owner, 2026-10-28; second outside review): every number the
     * text cannot settle, and every other fact, condition or promise the draft states, read against the evidence by
     * {@link AgentDraftService#checkFigures} — one call on the draft's own door, metered but not counted against the
     * day (it is part of the draft), on the check model when one is set. Returns the seller's sentence for why the draft
     * is not saved, or null when everything was confirmed. No answer, or an unreadable one, confirms nothing.
     */
    String uncheckedClaims(UUID orgId, String body, List<AgentDraftGenerator.Passage> shown, String title,
                           String details, String orderSentence) {
        return uncheckedClaims(orgId, body, shown, title, details, orderSentence,
                com.sellerops.agent.quota.AgentUsageActor.USER);
    }

    /**
     * Whose day a draft is charged to: the seller's own press ({@code SELLER:…}) is the seller's; every other author — the
     * Proactive agent, the responsibility investigation — is automatic work (sixth outside review, 2026-10-29: background
     * drafts were recorded as the seller's and could spend the seller's reserve).
     */
    static com.sellerops.agent.quota.AgentUsageActor usageActorOf(String actor) {
        return actor != null && actor.startsWith("SELLER:") ? com.sellerops.agent.quota.AgentUsageActor.USER
                : com.sellerops.agent.quota.AgentUsageActor.RUN;
    }

    String uncheckedClaims(UUID orgId, String body, List<AgentDraftGenerator.Passage> shown, String title,
                           String details, String orderSentence, com.sellerops.agent.quota.AgentUsageActor actor) {
        return checkClaims(orgId, body, shown, title, details, orderSentence, actor, null, null).refusal();
    }

    /**
     * The claim check's answer: why the draft is not saved (null when everything was confirmed), and for each figure it
     * confirmed, what it read it from — kept with the draft so a recheck reads the same claim against the same ground
     * (2026-11-01 third review).
     */
    /**
     * @param covered need id → ANSWERED · ASKED · NOT_COVERED for the needs the drafter was told to take up (v11), empty
     *                without a need list
     */
    /** @param unsupported the statement the check did not confirm, when that is the refusal — what a rewrite is told */
    record ClaimCheck(String refusal, List<InquiryFigureGrounds.Link> links, boolean statedNothing,
                      java.util.Map<String, String> covered, String unsupported) {
        ClaimCheck(String refusal, List<InquiryFigureGrounds.Link> links) {
            this(refusal, links, false, java.util.Map.of(), null);
        }

        static ClaimCheck refused(String why) {
            return new ClaimCheck(why, List.of());
        }
    }

    /**
     * A reply that does not answer (2026-11-01 eighth review, synthetic Q3: two exchange periods that disagree, and the
     * drafter wrote thanks and the customer's words back, which every check passed). Whether a draft tries to answer —
     * an answer, a reason it cannot be done, or asking for what decides it — is read by the claim check's model (v7), not
     * by counting facts: a number the code already matched never reaches that model.
     */
    static final String NO_ANSWER_IN_DRAFT = "초안에 문의에 대한 답이 없어 저장하지 않았어요. 다시 생성하거나 직접 작성해 주세요.";

    ClaimCheck checkClaims(UUID orgId, String body, List<AgentDraftGenerator.Passage> shown, String title,
                           String details, String orderSentence, com.sellerops.agent.quota.AgentUsageActor actor,
                           String specLine, String answerScope) {
        return checkClaims(orgId, body, shown, title, details, orderSentence, actor, specLine, answerScope,
                AgentQuotaService.CHECK_STEP);
    }

    /**
     * The same check of the same draft, once more, on {@link #RECHECK_STEP}. A charge the ceiling refuses comes back as
     * {@link #REVIEW_PENDING}; the caller then keeps the draft as 「추가 검사 대기」 ({@link #RECHECK_PENDING}).
     */
    ClaimCheck recheckClaims(UUID orgId, String body, List<AgentDraftGenerator.Passage> shown, String title,
                             String details, String orderSentence, com.sellerops.agent.quota.AgentUsageActor actor,
                             String specLine, String answerScope) {
        return checkClaims(orgId, body, shown, title, details, orderSentence, actor, specLine, answerScope, RECHECK_STEP);
    }

    /** As above, metered on {@code step} — a rewrite's check on its own, so what the rewrite costs can be told apart. */
    ClaimCheck checkClaims(UUID orgId, String body, List<AgentDraftGenerator.Passage> shown, String title,
                           String details, String orderSentence, com.sellerops.agent.quota.AgentUsageActor actor,
                           String specLine, String answerScope, String step) {
        String question = String.join(" ", title == null ? "" : title, details == null ? "" : details).strip();
        List<InquiryClaimGuard.FigureClaim> toVerify = InquiryClaimGuard.figuresToVerify(body,
                shown.stream().map(AgentDraftGenerator.Passage::text).toList(), question, orderSentence);
        List<String> asked = toVerify.stream().map(c -> c.index() + ". " + c.value() + (c.unit() == null ? "" : c.unit())
                + (c.questionOnly() ? " (고객 문의에만 있는 숫자)" : "") + " — 문장: " + c.sentence()).toList();
        // Counted against the actual AI ceiling like every call (owner, 2026-10-29); not a new piece of the seller's work.
        AgentQuotaService.Charge charge = quota.chargeCheck(orgId, AgentUsageKind.DRAFT, step, actor);
        if (charge != null && charge.decision() != null && !charge.decision().allowed()) {
            return ClaimCheck.refused(REVIEW_PENDING);
        }
        UUID usageId = charge == null ? null : charge.usageId();
        // The 규格 line the drafter was given goes with the draft (v8, 2026-11-01 ninth review): the check itself knows a
        // caution was asked for, and still reads every fact said beside it — no sentence is waved through after it.
        // …and the need list it was given (v10, 2026-11-02): a partial draft is judged on what it was asked to answer.
        AgentDraftGenerator.FigureResult checked = model.checkFiguresMetered(orgId, question, shown, orderSentence,
                specLine, answerScope, body, asked);
        if (checked.metrics() != null) {
            quota.recordOutcome(usageId, checked.metrics().elapsedMs(), checked.metrics().promptTokens(),
                    checked.metrics().completionTokens());
        }
        if (checked.verdicts().isEmpty()) {
            // The check did not answer — a send the gate held, a transport failure, an unreadable reply. That says nothing
            // about any number in the draft (case-eval-r12: a held check request reached the seller as 「숫자 20이 …
            // 확인되지 않아」 for a 20 the question and the policy both carry). Refused all the same, and named as what it is.
            log.info("inquiry_claim_check_unanswered orgId={} reason={} figures={}", orgId, checked.reason(),
                    toVerify.size());
            return ClaimCheck.refused(CLAIM_CHECK_UNAVAILABLE);
        }
        boolean conflict = false;
        List<String> figures = new ArrayList<>();
        List<InquiryFigureGrounds.Link> links = new ArrayList<>();
        for (InquiryClaimGuard.FigureClaim claim : toVerify) {
            AgentDraftGenerator.FigureVerdict v = checked.verdicts().get().get(claim.index() - 1);
            String verdict = v.verdict();
            conflict |= "CONFLICT".equals(verdict);
            boolean confirmed = "SUPPORTED".equals(verdict) || "SAID_BACK".equals(verdict) && claim.inQuestion();
            if (!confirmed && !figures.contains(claim.value())) {
                figures.add(claim.value());
            }
            if (confirmed) {
                links.add(new InquiryFigureGrounds.Link(claim.value(), claim.unit(), claim.sentence(), verdict,
                        v.passage(), v.source(), v.about(), v.when()));
            }
        }
        for (AgentDraftGenerator.ClaimVerdict c : checked.claims()) {
            conflict |= "CONFLICT".equals(c.verdict());
        }
        if (conflict) {
            return ClaimCheck.refused(CONFLICTING_EVIDENCE);
        }
        if (!figures.isEmpty()) {
            return ClaimCheck.refused(unconfirmedFigure(figures));
        }
        String unsupported = checked.claims().stream().filter(c -> !"SUPPORTED".equals(c.verdict())).findFirst()
                .map(AgentDraftGenerator.ClaimVerdict::text).orElse(null);
        return new ClaimCheck(unsupported == null ? null : unsupportedStatement(unsupported), List.copyOf(links),
                !checked.answered(), checked.covered() == null ? java.util.Map.of() : checked.covered(), unsupported);
    }
    /**
     * A reply that carries a 【확인: …】 blank — the owner's open question, not an answer (2026-10-27). The retriever drops
     * every passage that carries one, so this is the last door, as it is in the review lane.
     */
    static final String UNCONFIRMED_TEXT = "확인 전 안내(【확인: …】)가 들어가 초안을 저장하지 않았어요. "
            + "다시 생성해 보시거나 직접 작성해 주세요.";

    /**
     * What a model's reply claims that nothing it was shown supports (Inquiry Claim Guard v1). The evidence is the
     * passage list the model was actually given; the seller's approved sentences may authorize a follow-up or a
     * request; the order sentence authorizes a number as the evidence does, and the customer's question only one the
     * reply says back as theirs.
     */
    static List<InquiryClaimGuard.Violation> unsupportedClaims(String body, List<AgentDraftGenerator.Passage> shown,
                                                               AnswerStyleProfile style, String title, String details,
                                                               String orderSentence) {
        List<String> voice = new ArrayList<>();
        if (style != null) {
            voice.addAll(style.requiredPhrases());
            if (style.hasUnknownFallback()) {
                voice.add(style.unknownFallback());
            }
        }
        return InquiryClaimGuard.unsupportedClaims(body,
                shown.stream().map(AgentDraftGenerator.Passage::text).toList(), voice,
                String.join(" ", title == null ? "" : title, details == null ? "" : details), orderSentence);
    }

    /** 2026-11-02 seller-check review: the seller recorded the action as not finished, and the draft said it was. */
    static final String COMPLETION_NOT_RECORDED = "판매자가 기록한 결과는 아직 끝난 일이 아닌데 초안이 끝났다고 써서 "
            + "저장하지 않았어요. 다시 생성해 보시거나 직접 작성해 주세요.";

    static final String UNREQUESTED_ASK = "초안이 판정에 없는 내용을 고객에게 묻고 있어 저장하지 않았어요. 다시 생성해 보시거나 "
            + "직접 작성해 주세요.";

    static String rewriteUnrequestedAsk(List<String> asks) {
        return REWRITE_HEAD + asks.stream().map(x -> "「" + x + "」").collect(java.util.stream.Collectors.joining(", "))
                + "처럼 고객에게 묻는 말을 썼습니다. 이번 답변에는 고객에게 확인할 항목이 없습니다. 묻는 문장은 빼고, 나머지는 "
                + "그대로 씁니다";
    }

    static String rewriteCompletion(List<String> phrases, String recordedKo) {
        return REWRITE_HEAD + "판매자가 기록한 결과는 「" + recordedKo + "」입니다. " + phrases.stream()
                .map(x -> "「" + x + "」").collect(java.util.stream.Collectors.joining(", "))
                + "처럼 끝난 일로 쓰지 않고, 기록된 상태 그대로 안내합니다. 기록에 없는 날짜·기한도 쓰지 않습니다";
    }

    private com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSellerChecks(com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks) {
        this.sellerChecks = sellerChecks;
    }

    /** The outcome the seller recorded for this inquiry, or null when there is no current record. */
    private com.sellerops.inquiry.sellercheck.InquirySellerCheck currentRecord(UUID orgId, UUID workItemId) {
        return sellerChecks == null ? null : sellerChecks
                .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(orgId, workItemId)
                .orElse(null);
    }

    static final String STYLE_FORBIDDEN_PHRASE = "사용하지 않기로 한 표현이 들어가 초안을 저장하지 "
            + "않았어요. 다시 생성해 보시거나, 설정에서 그 표현을 확인해 주세요.";

    /**
     * 확인 필요 — where an ask that a draft could not answer is filed. Optional: a knowledge inbox
     * must never be able to fail a draft, and a context without one drafts exactly as before.
     */
    private final KnowledgeCandidateService candidates;
    private InquiryKnowledgeAssessor assessor;

    public InquiryDraftComposer(InquiryWorkItemRepository workItems, InquiryRepository inquiries,
                                InquiryReplyDraftService drafts, InquiryDraftEvidenceRepository evidence,
                                InquiryEvidenceRetriever retriever, AgentDraftService model,
                                AgentQuotaService quota, ProductVariantRepository variants,
                                DraftEvidenceSnippets snippets,
                                ProductDetailEnrichmentTrigger detail,
                                ProductDetailImageKnowledge images, AnswerStyleService styles,
                                SellerProfileService profiles,
                                KnowledgeCandidateService candidates) {
        this.workItems = workItems;
        this.inquiries = inquiries;
        this.drafts = drafts;
        this.evidence = evidence;
        this.retriever = retriever;
        this.assessor = InquiryKnowledgeAssessor.withoutContext(retriever, variants);
        this.model = model;
        this.quota = quota;
        this.variants = variants;
        this.snippets = snippets;
        this.detail = detail;
        this.images = images;
        this.styles = styles;
        this.profiles = profiles;
        this.candidates = candidates;
    }

    /**
     * Generate one draft version for a work item, grounded where grounding is possible.
     *
     * <p>Charged against the org's daily AI budget before the model is reached, on the same counter
     * every other draft spends — a regenerate is a call, not a free retry. An exhausted budget does
     * not fail the request and does not produce a substitute reply: nothing is written, and the
     * seller is told that the budget — not their knowledge library — is what stopped it
     * ({@code unavailableMessage}). The dashboard, the queue and the send path are unaffected.
     */
    /**
     * The production wiring: the knowledge verdict comes from {@link InquiryKnowledgeAssessor}, the same assessment
     * the case investigator reads (Knowledge &amp; Intelligence Closure v1).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public InquiryDraftComposer(InquiryWorkItemRepository workItems, InquiryRepository inquiries,
                                InquiryReplyDraftService drafts, InquiryDraftEvidenceRepository evidence,
                                InquiryEvidenceRetriever retriever, InquiryKnowledgeAssessor assessor,
                                AgentDraftService model, AgentQuotaService quota, ProductVariantRepository variants,
                                DraftEvidenceSnippets snippets, ProductDetailEnrichmentTrigger detail,
                                ProductDetailImageKnowledge images, AnswerStyleService styles,
                                SellerProfileService profiles, KnowledgeCandidateService candidates) {
        this(workItems, inquiries, drafts, evidence, retriever, model, quota, variants, snippets, detail, images,
                styles, profiles, candidates);
        this.assessor = assessor;
    }

    public GeneratedDraftView generate(UUID orgId, UUID workItemId, UUID sellerUserId) {
        return generate(orgId, workItemId, sellerUserId, null);
    }

    /**
     * As {@link #generate(UUID, UUID, UUID)}, with a one-turn wording hint from the conversation.
     *
     * <p>The hint overrides ONE field of the org's style profile for this draft (see
     * {@link ToneHint}); a null hint is exactly the three-argument call. Nothing about retrieval,
     * basis, quota or the facts section moves — a tone is a request about manner, and manner never
     * wins over grounding. In {@code NO_ANSWER_BASIS} the hint is ignored along with the model.
     */
    public GeneratedDraftView generate(UUID orgId, UUID workItemId, UUID sellerUserId, ToneHint tone) {
        return compose(orgId, workItemId, "SELLER:" + sellerUserId, tone);
    }

    /**
     * As {@link #generate}, with the author named rather than derived from a user id — the seam the
     * Proactive Operations Agent drafts through, before any human has opened the inquiry.
     *
     * <p><b>Nothing else differs.</b> Same retrieval, same three lanes, same quota counter, same
     * evidence rows, same knowledge state. The org's daily AI budget is charged here exactly as it is for a seller-initiated
     * draft, because it is the same call and pretending otherwise would let a background loop spend a
     * budget the seller cannot see.
     */
    public GeneratedDraftView generateAs(UUID orgId, UUID workItemId, String actor) {
        return compose(orgId, workItemId, actor, null);
    }

    /**
     * Whether this organisation's drafts can be written at all — the capability's own flag, key and org policy.
     *
     * <p>{@link #compose} asks the same question again, and has to: it is read there <b>after</b> the basis
     * verdict, so that a switched-off capability is never mistaken for a missing answer basis. This exists for
     * the callers that must not <b>start</b> when the answer is no — starting costs a proposal row, a phase
     * transition and a whole retrieval, none of which a deployment that turned the capability off asked to pay
     * for.
     *
     * <p>It reports the capability, never the budget. A seller who is out of quota today still has the
     * capability, and {@link #compose} remains the one place that decides what an exhausted one means.
     */
    public boolean enabledFor(UUID orgId) {
        return model.isEnabledFor(orgId);
    }

    /**
     * Compose, then file what could not be answered in 확인 필요.
     *
     * <p>(Knowledge Setup &amp; Inbox UX v1 §3) The review lane has filed its gaps since Grounded
     * Review Drafting v1; the inquiry lane produced the same verdict, returned it to whoever was
     * looking at that one inquiry, and dropped it. So the inbox that is supposed to collect
     * 「reviewnary가 모르는 것」 held half of them, and the half it held was the half a seller was
     * least likely to be standing in front of.
     *
     * <p>Nothing about the draft moves. The filing runs after the version is written, is idempotent
     * by the question, and cannot throw into the caller: a knowledge inbox must never be able to
     * fail a draft.
     */
    private GeneratedDraftView compose(UUID orgId, UUID workItemId, String actor, ToneHint tone) {
        // The search helpers' calls inside this draft are charged to who asked for it (2026-10-31).
        java.util.concurrent.atomic.AtomicReference<com.sellerops.inquiry.decision.NeedDecision> decided =
                new java.util.concurrent.atomic.AtomicReference<>();
        GeneratedDraftView view = com.sellerops.agent.quota.AgentUsageScope.as(usageActorOf(actor),
                () -> composeDraft(orgId, workItemId, actor, tone, decided))
                .withSellerCheckAsk(sellerCheckAsk(decided.get()));
        if (decided.get() != null) {
            keepSellerCheckAsk(orgId, workItemId, view.sellerCheckAsk());
            keepJudgedKind(orgId, workItemId, decided.get());
        }
        KnowledgeGapView gap = markGap(orgId, view.knowledgeGap());
        // The id travels back with the gap so that answering THIS ask on THIS screen closes THIS row
        // (Knowledge Gap Continuity v1). When the seller already answered this exact ask and the draft
        // still cannot use it, the gap says THAT instead — a different sentence, not a repeated demand.
        return gap == view.knowledgeGap() ? view : new GeneratedDraftView(view.draft(), view.authorKind(),
                view.knowledgeState(), view.knowledgeNote(), view.answerBasis(), view.answerBasisNote(),
                view.answerBasisAction(), view.productId(), view.evidence(), view.companyContextUsed(),
                view.unavailableMessage(), gap, view.unavailableKind(), view.openQuestions(), view.rewrite(),
                view.sellerCheckAsk());
    }

    /**
     * What the seller is asked to check on the seller centre, read off the needs left open (2026-11-02 seller-check
     * review): an action on this order before its state, since an action asked for is the larger job. Null when no order
     * need is open — a covered one was answered, by the seller's record or the channel's own facts.
     */
    private void keepSellerCheckAsk(UUID orgId, UUID workItemId, String ask) {
        // Kept on the work item so a reload asks for the same check; a failure to keep it never fails the draft.
        try {
            workItems.findById(workItemId).filter(w -> orgId.equals(w.getOrgId()))
                    .filter(w -> !java.util.Objects.equals(w.getSellerCheckAsk(), ask))
                    .ifPresent(w -> {
                        w.setSellerCheckAsk(ask);
                        workItems.save(w);
                    });
        } catch (RuntimeException e) {
            log.warn("seller-check ask not kept workItem={} cause={}", workItemId, e.getClass().getSimpleName());
        }
    }

    private com.sellerops.inquiry.sellercheck.InquiryKindCorrectionRepository kindCorrections;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKindCorrections(com.sellerops.inquiry.sellercheck.InquiryKindCorrectionRepository kindCorrections) {
        this.kindCorrections = kindCorrections;
    }

    /**
     * The service's own reading of what kind of inquiry this is — kept only from a decision no seller correction shaped,
     * so 「서비스 판단」 on screen is never the seller's word read back (2026-11-05). A plan that produced no needs keeps the
     * last reading; a failure to keep it never fails the draft.
     */
    /** The standing kind correction's id, or null when none stands (or the repository is not wired). */
    private UUID currentKindCorrection(UUID orgId, UUID workItemId) {
        return kindCorrections == null ? null : kindCorrections
                .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByCorrectedAtDesc(orgId, workItemId)
                .map(com.sellerops.inquiry.sellercheck.InquiryKindCorrection::getId).orElse(null);
    }

    private void keepJudgedKind(UUID orgId, UUID workItemId, com.sellerops.inquiry.decision.NeedDecision decision) {
        try {
            if (kindCorrections != null && kindCorrections
                    .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByCorrectedAtDesc(orgId, workItemId)
                    .isPresent()) {
                return;
            }
            com.sellerops.inquiry.decision.InquiryKind kind = decision.needs() == null ? null
                    : com.sellerops.inquiry.decision.InquiryKind.of(decision.needs().stream()
                            .map(com.sellerops.inquiry.decision.NeedResult::need).toList());
            if (kind == null) {
                return;
            }
            workItems.findById(workItemId).filter(w -> orgId.equals(w.getOrgId()))
                    .filter(w -> !kind.name().equals(w.getJudgedKind()))
                    .ifPresent(w -> {
                        w.setJudgedKind(kind.name());
                        workItems.save(w);
                    });
        } catch (RuntimeException e) {
            log.warn("judged kind not kept workItem={} cause={}", workItemId, e.getClass().getSimpleName());
        }
    }

    static String sellerCheckAsk(com.sellerops.inquiry.decision.NeedDecision decision) {
        if (decision == null || decision.needs() == null) {
            return null;
        }
        List<com.sellerops.inquiry.decision.NeedType> open = decision.unresolved().stream()
                .map(n -> n.need().type()).toList();
        return open.contains(com.sellerops.inquiry.decision.NeedType.ORDER_ACTION) ? "ORDER_ACTION"
                : open.contains(com.sellerops.inquiry.decision.NeedType.ORDER_STATE) ? "ORDER_STATE" : null;
    }

    /**
     * File one ask, when BOTH lanes came back empty about a noun the customer actually wrote.
     *
     * <p>Both lanes, because a question the operating rules answered is not a gap in the product's
     * knowledge; and a noun the customer wrote, because {@code missingSubject} is quoted from the
     * question rather than classified, and an ask a seller cannot recognise is an ask they will not
     * answer. Everything else — a partial hit, an unresolved 규격, a vendor failure — is a different
     * fact and files nothing.
     *
     * @return the gap, carrying either the 확인 필요 row it was filed as or the fact that this seller
     *         already answered this exact ask — and unchanged when neither is true
     */
    private KnowledgeGapView markGap(UUID orgId, KnowledgeGapView gap) {
        if (candidates == null || gap == null) {
            return gap;
        }
        // A catalogue question asks the seller a catalogue fact — whether they sell it — once their on-sale catalogue
        // has been read and states nothing about it. The operating-rule lane has no say in that: a shipping policy
        // that did not mention a 9oz dispenser is not why the seller is being asked about one.
        boolean catalogue = gap.catalogueChecked() != null;
        /*
          <b>And the noun has to be one</b> (2026-10-06). The ask filed here is a row the seller reads and
          answers later, titled with this word: 「「드립니다」에 대해 고객에게 안내할 공식 기준이 필요합니다.」
          was reachable whenever a stored or classifier-supplied subject was a predicate. A subject that
          cannot be quoted is treated exactly as an absent one has always been — no ask is filed, and the gap
          is returned unchanged. {@link com.sellerops.knowledge.RetrievalQuery#quotableSubject} is the same
          rule the extractor applies; nothing new is accepted by it.
        */
        String subject = com.sellerops.knowledge.RetrievalQuery.quotableSubject(
                catalogue ? gap.askedSubject() : gap.missingSubject());
        if (subject == null) {
            return gap;
        }
        if (!catalogue && (!ABSENT.equals(gap.productOutcome()) || !ABSENT.equals(gap.policyOutcome()))) {
            return gap;
        }
        String scope = catalogue || gap.productId() == null ? "ORG" : "PRODUCT";
        String question = catalogue
                ? "「" + subject + "」" + objectParticle(subject) + " 판매하시는지 알려 주세요."
                : "「" + subject + "」에 대해 고객에게 안내할 공식 기준이 필요해요.";
        try {
            UUID scopedProduct = "ORG".equals(scope) ? null : gap.productId();
            KnowledgeCandidate filed = candidates.noteGap(orgId, scope, scopedProduct, subject, question);
            if (filed != null) {
                return gap.filedAs(filed.getId());
            }
            // Nothing was filed. The ONE reason that is worth a different sentence is that this exact
            // ask was already answered — a fact `noteGap` decides on and used to swallow. Read here so
            // the screen can stop telling a seller to add what they added (Inquiry Operations
            // Workspace v1 §6); one extra query, only on the path that filed nothing.
            return candidates.alreadyAnswered(orgId, scope, scopedProduct, question)
                    ? gap.answeredBefore() : gap;
        } catch (RuntimeException e) {
            // Enum-free and content-free: the draft is what the seller asked for, and it is already saved.
            return gap;
        }
    }

    /** 을/를 for a Korean noun phrase; 를 for anything that does not end in a Hangul syllable. */
    static String objectParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "를";
        }
        return (last - 0xAC00) % 28 == 0 ? "를" : "을";
    }

    /** The retrieval outcome that means "this lane has nothing about it" — {@code RetrievalOutcome.ABSENT}. */
    private static final String ABSENT = RetrievalOutcome.ABSENT.name();

    private GeneratedDraftView composeDraft(UUID orgId, UUID workItemId, String actor, ToneHint tone,
                                            java.util.concurrent.atomic.AtomicReference<
                                                    com.sellerops.inquiry.decision.NeedDecision> decided) {
        InquiryWorkItem workItem = workItems.findById(workItemId)
                .filter(w -> w.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("문의 작업을 찾을 수 없어요."));
        Inquiry inquiry = inquiries.findById(workItem.getInquiryId())
                .filter(i -> i.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("문의를 찾을 수 없어요."));

        // The question, as a person wrote it — the stored body may be a mail thread wrapped in markup.
        String title = MarkupText.toPlainText(inquiry.getTitle());
        String details = MarkupText.toPlainText(inquiry.getBody());

        String detailFailure = enrichDetailIfNeeded(orgId, inquiry);

        // Computed ONCE and used three times: it decides which 규격's knowledge may be retrieved at
        // all, the caution line the drafter reads, and — with the library's verdict — whether there
        // is a basis to draft. Two computations of the same classification would be two chances for
        // the screen and the prompt to disagree.
        //
        // It runs BEFORE the retrieval rather than after it, which is the 2026-08-27 change: a
        // document written about 2호 is not weak evidence about a customer who said 3호, and a filter
        // applied to the results would already have let it take one of the four slots.
        // The seller's record this run had in front of it — read once, before drafting, and named on the version, so a
        // record corrected while this draft was being written is caught before it is approved (2026-11-03).
        com.sellerops.inquiry.sellercheck.InquirySellerCheck seenRecord = currentRecord(orgId, workItemId);
        // And the kind correction it plans under (2026-11-05 eighth review), named on the version the same way: a
        // correction made while this was being written leaves the version naming the older one, and it is not approved.
        UUID seenKind = currentKindCorrection(orgId, workItemId);
        // Computed ONCE, by the assessment the case investigator also reads: the 규격 verdict (before retrieval), the
        // Knowledge Spine retrieval, and the basis. Two computations of the same classification would be two chances
        // for the screen, the prompt and the investigation to disagree.
        InquiryKnowledgeAssessor.Assessment assessment =
                assessor.assess(orgId, inquiry, com.sellerops.order.fact.OrderFactLookup.EXACT_ALLOWED,
                        usageActorOf(actor));
        decided.set(assessment.decision());
        SpecApplicability.Verdict verdict = assessment.verdict();
        SpecApplicability.Applicability applicability = verdict.applicability();
        InquiryEvidenceRetriever.InquiryEvidence retrieved = assessment.retrieved();
        List<com.sellerops.knowledge.spine.KnowledgeEntry> context = assessment.spine().draftContext();
        AnswerBasisState basis = assessment.basis();
        AnswerStyleProfile style = tone == null ? styleFor(orgId) : tone.applyTo(styleFor(orgId));
        if (!basis.mayGenerate()) {
            // No model call and no saved version. Nothing here is a refusal to help — the seller
            // writes their own reply on the same screen — it is a refusal to manufacture one.
            //
            // Unless the 상세페이지 read is what failed. Then this verdict rests on a library we
            // could not finish filling, and 「답변 기준이 필요합니다」 would send the seller off to
            // write knowledge that may already be sitting on their own listing.
            String operational = detailFailure != null ? detailFailure
                    : inFlight(orgId, inquiry) ? DETAIL_READ_PENDING
                    : decisionUnavailable(assessment.decision());
            // The seller's own approved sentence, verbatim, and only here.
            //
            // It is allowed ONLY when the retrieval actually settled with no usable evidence. If the
            // machinery did not finish (a 상세페이지 read that failed, pictures still being read) we
            // do not know there is no basis, and answering "확인 후 안내드리겠습니다" to a question we
            // may be seconds from being able to answer is the deferral this product deleted.
            if (operational == null && style.hasUnknownFallback()) {
                return approvedFallback(orgId, workItemId, actor, title, retrieved, basis, verdict, assessment.asked(),
                        assessment.named(), style, assessment.gap());
            }
            return noBasis(retrieved, basis, verdict, assessment.asked(), assessment.named(), operational,
                    assessment.gap());
        }

        // Each branch names its own reason, because the three are not interchangeable to the person
        // reading the screen: a budget comes back tomorrow, a switch is an operator's job, and a
        // vendor that did not answer is worth pressing the button again for.
        // The company's own description of itself (Seller Context v1-B), read ONCE, and only on the
        // path that reaches a model. It is deliberately read AFTER the basis verdict: the verdict
        // never sees it, so a summary alone can never turn NO_ANSWER_BASIS into a draft.
        String company = companyContextFor(orgId);
        String unavailable = null;
        // How the one rewrite went, when there was one — a closed word on the view (GeneratedDraftView#rewrite).
        String rewriteOutcome = null;
        Optional<AgentDraftResponseParser.ParsedDraft> written = Optional.empty();
        List<InquiryClaimGuard.Violation> claims;
        // What the claim check read each confirmed figure from — kept with the draft below.
        List<InquiryFigureGrounds.Link> figureLinks = List.of();
        // The needs the drafter was told to take up and the claim check found the draft did not (v11).
        List<com.sellerops.inquiry.decision.NeedResult> notTakenUp = List.of();
        // The check's item-by-item answer for the saved draft — what closes a settled need (fourteenth review).
        java.util.Map<String, String> checkedCovered = java.util.Map.of();
        String modelVersion = model.versionFor(orgId);
        // A draft held for review over these same inputs is checked, not written again (owner, 2026-10-29).
        com.sellerops.inquiry.decision.NeedDecision needDecisionEarly = assessment.decision();
        // The need list the drafter and the claim check both read, with when the inquiry arrived (2026-11-02): a date the
        // seller's side knows is not a question for the customer.
        // …and the send limit of this inquiry kind (2026-11-02): a draft longer than it is kept but cannot be sent as written.
        String draftScope = needDecisionEarly == null ? null : withLine(withMoment(needDecisionEarly.answerScope(),
                com.sellerops.inquiry.decision.InquiryMoment.of(inquiry.getReceivedAt())),
                com.sellerops.inquiry.reply.InquiryReplyLength.draftLine(channelCodeOf(inquiry.getChannelId()),
                        inquiry.getSourceSubtype()));
        List<AgentDraftGenerator.Passage> shownEarly = needDecisionEarly != null ? needDecisionEarly.passages()
                : passagesFor(assessment.catalogue(), retrieved.passages(), context);
        // Everything the draft call is given (third outside review, 2026-10-29): a held draft resumes only while none of it
        // changed — the question, the passages, the order sentence, the 규격 line, the company's style instruction, its
        // own description, the answer scope, and the model.
        // The 규격 line the drafter and the claim check both read — asking back only when the seller's evidence leaves the
        // 규격 open (2026-11-01 ninth review: 「모든 옵션의 길이는 2.4m」 is answered, one value seen is still asked).
        String specSentence = applicability.messageKo(retrieved.figuresUnaided(), retrieved.variantSpecific(),
                SpecApplicability.coversEveryOption(shownEarly.stream().map(AgentDraftGenerator.Passage::text).toList(),
                        verdict.topicWord()));
        String heldInputs = pendingInputsHash(title, details, shownEarly, retrieved.order().messageKo(), modelVersion,
                List.of(specSentence,
                        String.valueOf(AnswerStyleInstruction.of(style)), String.valueOf(company),
                        needDecisionEarly == null ? "" : String.valueOf(draftScope)));
        DraftReviewPending held = pending == null ? null : pending.findByOrgIdAndWorkItemId(orgId, workItemId)
                .filter(p -> heldInputs.equals(p.getInputsHash())).orElse(null);
        if (!model.isEnabledFor(orgId)) {
            unavailable = CAPABILITY_OFF;
        } else {
            // Charged to who asked (sixth outside review, 2026-10-29): a background draft is automatic work and stops at the
            // seller's reserve. It starts only when its claim check fits too.
            AgentQuotaService.Charge draftCharge = held != null
                    ? new AgentQuotaService.Charge(QuotaDecision.passWithoutCharge(), null)
                    : quota.consumeCharge(orgId, AgentUsageKind.DRAFT, null, usageActorOf(actor), 1);
            QuotaDecision decision = draftCharge.decision();
            if (decision.allowed()) {
                // Inquiry Decision v2: the drafter sees the evidence of the covered needs and nothing a need did not
                // cite, and is told which needs to answer and which to ask the customer about.
                com.sellerops.inquiry.decision.NeedDecision needDecision = needDecisionEarly;
                List<AgentDraftGenerator.Passage> shown = shownEarly;
                // The rule-based 규격 line stays even when the decision says GROUNDED. The first real-model run
                // (apr-c8715d20) found the judge calling spec-dependent answers FULL (「몇 가닥 들어가나요」 against a FAQ
                // written per product, not per 호) — dropping the line then would let a draft close on a figure the
                // customer's 규격 decides, the 2026-08-26 incident. Over-asking is the safe side of that trade; only the
                // seller's own statement that the value holds for every 규격 lifts it (specSentence).
                if (held != null) {
                    written = Optional.of(new AgentDraftResponseParser.ParsedDraft(held.getCategory(), held.getTitle(),
                            held.getBody()));
                } else {
                    written = writeDraft(orgId, title, details, shown, retrieved, specSentence, style, company,
                            needDecision, draftScope, draftCharge.usageId());
                }
                // One rewrite (2026-11-02, fourth review): a draft refused for something a sentence can fix is written
                // once more from the same evidence and the same scope, told what was wrong, and checked again in full.
                // Q1 wrote two drafts the checks passed and one with a follow-up promise from identical inputs — that one
                // need not become the seller's work. Never for what a rewrite cannot fix: a number, the law, evidence that
                // disagrees, a check that did not finish (a held send among them), the ceiling.
                // A held draft resumes at the try it was (2026-11-02, fifth review): a rewrite held for its check and
                // resumed is still the rewrite — checked, and on a refusal handed to the seller, never written a third time.
                String rewriteBecause = held != null && held.getAttempt() > 1 ? held.getRewriteBecause() : null;
                com.sellerops.inquiry.sellercheck.SellerCheckOutcome recorded =
                        seenRecord == null ? null : seenRecord.outcomeValue();
                boolean heldNow = false;
                // A draft held at its second look resumes there and nowhere else (2026-11-05 eleventh review).
                boolean awaitingRecheck = held != null && DraftReviewPending.RECHECK.equals(held.getAwaiting());
                for (int attempt = held != null && held.getAttempt() > 1 ? 2 : 1; ; attempt++) {
                    boolean rewritten = attempt > 1;
                    String fix = null;
                    String because = null;
                    if (written.isEmpty()) {
                        unavailable = MODEL_FAILED;
                    } else if (com.sellerops.knowledge.KnowledgeBlanks.carries(written.get().comments())) {
                        written = Optional.empty();
                        unavailable = UNCONFIRMED_TEXT;
                        fix = REWRITE_BLANK;
                        because = "BLANK";
                    } else if (!AnswerStyleInstruction
                            .forbiddenPresentIn(written.get().comments(), style).isEmpty()) {
                        // The prompt asked; this checks. A style line is a request to a model, and a
                        // rule the company set is not satisfied by having asked politely.
                        fix = rewriteForbidden(AnswerStyleInstruction.forbiddenPresentIn(written.get().comments(), style));
                        because = "STYLE";
                        written = Optional.empty();
                        unavailable = STYLE_FORBIDDEN_PHRASE;
                    } else if (recorded != null && !com.sellerops.inquiry.sellercheck.SellerCheckGuard
                            .unrecordedCompletions(written.get().comments(), recorded).isEmpty()) {
                        // The seller recorded it as not finished; the reply may not say it is (2026-11-02).
                        List<String> finished = com.sellerops.inquiry.sellercheck.SellerCheckGuard
                                .unrecordedCompletions(written.get().comments(), recorded);
                        log.info("inquiry_draft_refused orgId={} kinds=[COMPLETION_NOT_RECORDED] attempt={}", orgId,
                                attempt);
                        written = Optional.empty();
                        unavailable = COMPLETION_NOT_RECORDED;
                        fix = rewriteCompletion(finished, recorded.labelKo());
                        because = "COMPLETION";
                    } else if (!CustomerAskGuard.unrequestedAsks(written.get().comments(), needDecision, recorded,
                            specSentence).isEmpty()) {
                        // Asking back is not a claim, but only what the judgement left for the customer may be asked
                        // (2026-11-06 thirteenth review); with nothing left, a question is one nobody judged necessary.
                        List<String> asks = CustomerAskGuard.unrequestedAsks(written.get().comments(), needDecision,
                                recorded, specSentence);
                        log.info("inquiry_draft_refused orgId={} kinds=[UNREQUESTED_ASK] attempt={}", orgId, attempt);
                        written = Optional.empty();
                        unavailable = UNREQUESTED_ASK;
                        fix = rewriteUnrequestedAsk(asks);
                        because = "UNREQUESTED_ASK";
                    } else if (!(claims = unsupportedClaims(written.get().comments(), shown, style, title, details,
                            retrieved.order().messageKo())).isEmpty()) {
                        // The same relationship, with the evidence: the prompt forbids inventing a follow-up, a request of
                        // the customer, a remedy or a figure; this refuses a reply that did. Refused, never edited — and a
                        // figure the check could not match says so, so the seller knows what to look at. The kinds go to
                        // the log — never the matched text — so a refusal can be told apart afterwards (case-eval-r10: three
                        // refusals said only 「약속이나 요청」 and nothing recorded which).
                        log.info("inquiry_draft_refused orgId={} kinds={} answerScope={} attempt={}", orgId,
                                claims.stream().map(c -> c.kind().name()).distinct().toList(),
                                needDecision == null ? "NONE" : needDecision.basis(), attempt);
                        written = Optional.empty();
                        unavailable = claims.stream().allMatch(c -> c.kind() == InquiryClaimGuard.Kind.FIGURE)
                                ? unsupportedFigure(claims)
                                : claims.stream().anyMatch(c -> c.kind() == InquiryClaimGuard.Kind.LAW) ? LAW_CONFLICT
                                : claims.stream().allMatch(c -> c.kind() == InquiryClaimGuard.Kind.CONDITION)
                                ? DROPPED_CONDITION : UNSUPPORTED_CLAIM;
                        fix = rewriteClaims(claims);
                        because = fix == null ? null : "GUARD";
                    } else {
                        ClaimCheck claimCheck;
                        boolean recheckWaits = false;
                        if (awaitingRecheck) {
                            // Held at the second look: the first check already ran and came back incomplete. Only the
                            // one look that is left runs — the count is the calls made, and a resume grants no new one.
                            awaitingRecheck = false;
                            claimCheck = recheckClaims(orgId, written.get().comments(), shown, title, details,
                                    retrieved.order().messageKo(), usageActorOf(actor), specSentence, draftScope);
                            recheckWaits = REVIEW_PENDING.equals(claimCheck.refusal());
                            log.info("inquiry_claim_recheck orgId={} resumed=true complete={}", orgId,
                                    claimCheck.refusal() == null && coverageAnswered(needDecision, claimCheck.covered()));
                        } else {
                            claimCheck = checkClaims(orgId, written.get().comments(), shown, title, details,
                                    retrieved.order().messageKo(), usageActorOf(actor), specSentence, draftScope,
                                    rewritten ? REDRAFT_CHECK_STEP : AgentQuotaService.CHECK_STEP);
                            if (claimCheck.refusal() == null && !claimCheck.statedNothing()
                                    && !coverageAnswered(needDecision, claimCheck.covered())) {
                                // An incomplete answer and nothing else found (2026-11-05 tenth review, K3): the same
                                // draft, need list and evidence are checked once more — never rewritten for it. Once per
                                // text: a finding in the first answer is never re-asked away. Metered like any check; a
                                // look the ceiling stops is kept for later, not lost (eleventh review).
                                ClaimCheck second = recheckClaims(orgId, written.get().comments(), shown, title, details,
                                        retrieved.order().messageKo(), usageActorOf(actor), specSentence, draftScope);
                                recheckWaits = REVIEW_PENDING.equals(second.refusal());
                                log.info("inquiry_claim_recheck orgId={} resumed=false complete={} waits={}", orgId,
                                        second.refusal() == null && coverageAnswered(needDecision, second.covered()),
                                        recheckWaits);
                                claimCheck = second;
                            }
                        }
                        String unchecked = recheckWaits ? RECHECK_PENDING
                                : claimCheck.refusal() == null && claimCheck.statedNothing() ? NO_ANSWER_IN_DRAFT
                                : claimCheck.refusal();
                        if (claimCheck.refusal() == null && !coverageAnswered(needDecision, claimCheck.covered())) {
                            // An item-by-item answer that leaves out a need the drafter was told to take up, or names one
                            // it was never sent, is a check that did not finish — not a draft that dropped nothing
                            // (2026-11-02 review: {} for N1 · N2 read as 「누락 0」).
                            log.info("inquiry_claim_check_incomplete orgId={} expected={} got={}", orgId,
                                    needDecision.toTakeUp().stream().map(n -> n.need().id()).toList(),
                                    claimCheck.covered().keySet());
                            unchecked = CLAIM_CHECK_UNAVAILABLE;
                        }
                        figureLinks = claimCheck.links();
                        notTakenUp = notTakenUp(needDecision, claimCheck.covered());
                        checkedCovered = claimCheck.covered() == null ? java.util.Map.of() : claimCheck.covered();
                        if (REVIEW_PENDING.equals(unchecked) || RECHECK_PENDING.equals(unchecked)) {
                            hold(orgId, workItemId, written.get(), heldInputs, attempt, rewriteBecause,
                                    recheckWaits ? DraftReviewPending.RECHECK : DraftReviewPending.CHECK);
                            heldNow = true;
                        } else if (pending != null) {
                            // Checked now, one way or the other: the hold has done its work.
                            pending.findByOrgIdAndWorkItemId(orgId, workItemId).ifPresent(pending::delete);
                        }
                        if (unchecked != null) {
                            // Whether a figure is about the same thing, and whether a fact, condition or promise the draft
                            // states is what the evidence says, is read by the model; what it does not confirm stays out.
                            written = Optional.empty();
                            unavailable = unchecked;
                            if (NO_ANSWER_IN_DRAFT.equals(unchecked)) {
                                fix = REWRITE_NO_ANSWER;
                                because = "NO_ANSWER";
                            } else if (claimCheck.unsupported() != null
                                    && unchecked.equals(unsupportedStatement(claimCheck.unsupported()))) {
                                fix = rewriteUnsupported(claimCheck.unsupported());
                                because = "UNSUPPORTED_STATEMENT";
                            }
                        }
                    }
                    if (rewritten) {
                        // Waiting for the check's budget is not a refusal (fifth review): PENDING, and the resume says how
                        // the same rewrite ended.
                        rewriteOutcome = written.isPresent() ? "PASSED" : heldNow ? "PENDING" : "REFUSED";
                        log.info("inquiry_draft_rewrite orgId={} because={} outcome={} resumed={}", orgId, rewriteBecause,
                                written.isPresent() ? "PASSED" : heldNow ? "PENDING" : unavailableKind(unavailable),
                                held != null);
                    }
                    if (written.isPresent() || fix == null || rewritten || needDecision == null) {
                        break;
                    }
                    // Metered on its own step, counted against the ceiling like any call and never as new work; no room,
                    // no rewrite — the first refusal stands.
                    AgentQuotaService.Charge again = quota.chargeCheck(orgId, AgentUsageKind.DRAFT, REDRAFT_STEP,
                            usageActorOf(actor));
                    if (again != null && again.decision() != null && !again.decision().allowed()) {
                        break;
                    }
                    rewriteBecause = because;
                    Optional<AgentDraftResponseParser.ParsedDraft> second = writeDraft(orgId, title, details, shown,
                            retrieved, specSentence, style, company, needDecision, withLine(draftScope, fix),
                            again == null ? null : again.usageId());
                    if (second.isEmpty()) {
                        rewriteOutcome = "NOT_WRITTEN";
                        log.info("inquiry_draft_rewrite orgId={} because={} outcome=NOT_WRITTEN", orgId, because);
                        break; // the first refusal says more than 「생성하지 못했어요」
                    }
                    written = second;
                    unavailable = null;
                }
            } else {
                unavailable = decision.messageKo();
            }
        }
        if (written.isEmpty()) {
            // Nothing wrote this reply, and a template saying 「확인 후 안내드리겠습니다」 in its place
            // is a promise with no author. The BASIS is reported as it was actually computed — this
            // question IS grounded, and overwriting that with NO_ANSWER_BASIS would be a second
            // false statement laid on top of the first — and the operational reason travels beside it.
            return noBasis(retrieved, basis, verdict, assessment.asked(), assessment.named(), unavailable,
                    assessment.gap()).withRewrite(rewriteOutcome);
        }

        AgentDraftResponseParser.ParsedDraft parsed = written.get();
        String replyTitle = parsed.title() == null || parsed.title().isBlank()
                ? defaultTitle(title) : parsed.title();
        String replyBody = parsed.comments();

        // A need the draft dropped goes to the seller's list, never nowhere (2026-11-02): the version is a partial one and
        // its send waits for that question like any other the evidence left open.
        if (!notTakenUp.isEmpty()) {
            log.info("inquiry_draft_dropped_needs orgId={} needs={}", orgId,
                    notTakenUp.stream().map(n -> n.need().id()).toList());
            if (basis == AnswerBasisState.GROUNDED || basis == AnswerBasisState.NEEDS_CLARIFICATION) {
                basis = AnswerBasisState.PARTIAL_ANSWER;
            }
        }

        int base = drafts.currentVersion(workItemId);
        ReplyDraftView saved = drafts.saveAs(orgId, workItemId, actor, replyTitle, replyBody, base,
                new InquiryReplyDraftService.Provenance(DraftAuthorKind.MODEL,
                        stamped(modelVersion, style), knowledgeStateOf(assessment), retrieved.productId(),
                        basis, seenRecord == null ? null : seenRecord.getId(), seenKind));

        // What the draft cites is what its writer was shown: with a need decision, the covered needs' evidence — the
        // lexical lanes may have found nothing the decision's own collection did (2026-10-31: a draft written from two
        // rules cited none of them, and its state said 「맞는 자료 없음」).
        List<DraftEvidenceView> views = needDecisionEarly != null
                ? recordDecisionEvidence(orgId, workItemId, saved.version(), needDecisionEarly.shownEvidence(),
                        retrieved.order())
                : recordEvidence(orgId, workItemId, saved.version(),
                        catalogueMatches(assessment.catalogue()), retrieved.passages(), context, retrieved.order());
        // …and what each of its numbers was written from — the passage the claim check named for it, line for line with
        // the list it was shown — so a recheck reads the same claim against the same ground (2026-11-01 reviews).
        if (figureGrounds != null) {
            figureGrounds.record(orgId, workItemId, saved.version(), replyBody,
                    needDecisionEarly != null ? shownOf(needDecisionEarly.shownEvidence())
                            : shownOf(catalogueMatches(assessment.catalogue()), retrieved.passages(), context),
                    retrieved.order().messageKo(),
                    String.join(" ", title == null ? "" : title, details == null ? "" : details), figureLinks);
        }
        // …and the sentence above it names what was cited, not what the lexical lanes found (2026-10-31: a draft citing two
        // rules said 「어디에도 해당하는 내용이 없어」 above them).
        boolean decisionCited = needDecisionEarly != null && !needDecisionEarly.shownEvidence().isEmpty();
        // What this version leaves to the seller (tenth review): stated beside it, one question at a time, and the send
        // waits for each — a version that answers everything closes what an earlier one left.
        List<String> open = new ArrayList<>(needDecisionEarly == null ? List.of() : needDecisionEarly.openQuestions());
        // A need the seller's completed action settled closes only when the reply answered the customer's question
        // (fourteenth review, 2026-11-06): the check reads the draft, not the record. Otherwise it is the seller's again,
        // with what deciding it would take — the question as the judge left it, not 「초안이 다루지 않았어요」.
        List<com.sellerops.openquestion.OpenQuestionService.Settled> settled = new ArrayList<>();
        java.util.Map<String, com.sellerops.inquiry.decision.NeedStatus> notSettled = new java.util.HashMap<>();
        for (com.sellerops.inquiry.decision.NeedResult n : needDecisionEarly == null
                ? List.<com.sellerops.inquiry.decision.NeedResult>of() : needDecisionEarly.settledByAction()) {
            com.sellerops.inquiry.decision.EvidenceCandidate record = n.settlingRecord();
            if (record != null && record.sourceId() != null && "ANSWERED".equals(checkedCovered.get(n.need().id()))) {
                settled.add(new com.sellerops.openquestion.OpenQuestionService.Settled(n.need().ask(),
                        "seller-check:" + record.sourceId()));
            } else {
                open.add(n.need().ask() + (n.missing() == null || n.missing().isBlank() ? "" : " — " + n.missing().strip()));
                notSettled.put(n.need().id(), n.judged() == null ? com.sellerops.inquiry.decision.NeedStatus.NONE : n.judged());
            }
        }
        for (com.sellerops.inquiry.decision.NeedResult n : notTakenUp) {
            if (n.settledByAction()) {
                continue;
            }
            // A need answered only in part is listed already, with what the evidence left: one row, told both — never a
            // second row for the same question (2026-11-06: the seller handled 「배송비」 twice).
            int listed = open.indexOf(n.need().ask() + (n.status() != com.sellerops.inquiry.decision.NeedStatus.PARTIAL
                    || n.missing() == null || n.missing().isBlank() ? "" : " — " + n.missing().strip()));
            if (listed >= 0) {
                open.set(listed, open.get(listed) + " · " + DROPPED_BY_DRAFT);
            } else {
                open.add(n.need().ask() + " — " + DROPPED_BY_DRAFT);
            }
        }
        if (openQuestions != null) {
            // What the seller's completed action made moot is closed first, by name and on its record — never left to
            // read as an answer, and never asked of the seller again (2026-11-06 thirteenth review).
            openQuestions.settle(orgId, com.sellerops.openquestion.OpenQuestionService.INQUIRY, workItemId, settled,
                    "inquiry-draft:" + workItemId + ":" + saved.version(), actor);
            openQuestions.replace(orgId, com.sellerops.openquestion.OpenQuestionService.INQUIRY, workItemId, open, true,
                    "inquiry-draft:" + workItemId + ":" + saved.version(), actor);
        }
        return new GeneratedDraftView(saved, DraftAuthorKind.MODEL.name(), knowledgeStateOf(assessment).name(),
                assessment.groundedByCatalogue() ? CATALOGUE_GROUNDED
                        : decisionCited ? DraftKnowledgeState.GROUNDED.messageKo(scopesOf(needDecisionEarly.shownEvidence()))
                        : retrieved.state().messageKo(retrieved.scopes()), basis.name(),
                basis.messageKo(),
                basis.actionKo(retrieved.state(), verdict.topicWord(), applicability,
                        retrieved.productOutcome(), retrieved.policyOutcome(), assessment.asked(),
                        retrieved.policyDeclares(assessment.asked()), verdict.optionsRegistered()),
                retrieved.productId(), views, company != null, null, unsettled(assessment.gap(), notSettled), null, open)
                .withRewrite(rewriteOutcome);
    }

    private com.sellerops.openquestion.OpenQuestionService openQuestions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOpenQuestions(com.sellerops.openquestion.OpenQuestionService openQuestions) {
        this.openQuestions = openQuestions;
    }

    /**
     * The need list as the seller should read it once the reply was checked: a need the decision settled by a completed
     * action that the reply did not answer is shown as the judge left it, not as 「조치 완료로 판단 불필요」 beside an open
     * question for the same need.
     */
    static com.sellerops.inquiry.draft.dto.KnowledgeGapView unsettled(com.sellerops.inquiry.draft.dto.KnowledgeGapView gap,
            java.util.Map<String, com.sellerops.inquiry.decision.NeedStatus> notSettled) {
        if (gap == null || gap.needs() == null || notSettled.isEmpty()) {
            return gap;
        }
        return gap.withNeeds(gap.needs().stream().map(v -> {
            com.sellerops.inquiry.decision.NeedStatus was = notSettled.get(v.id());
            return was == null ? v : new com.sellerops.inquiry.draft.dto.NeedCoverageView(v.id(), v.ask(), v.type(),
                    was.name(), was.labelKo(), v.evidence(), v.missing(), v.askCustomer(), v.precedents(), v.acquirable());
        }).toList());
    }

    /** Beside a question the draft left out though the evidence answers it — the seller adds it before sending. */
    static final String DROPPED_BY_DRAFT = "자료로 답할 수 있는데 초안이 다루지 않았어요";

    private com.sellerops.channel.ChannelRepository channels;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChannels(com.sellerops.channel.ChannelRepository channels) {
        this.channels = channels;
    }

    private String channelCodeOf(UUID channelId) {
        return channels == null || channelId == null ? null
                : channels.findById(channelId).map(com.sellerops.channel.Channel::getCode).orElse(null);
    }

    /** One more line under the need list, when there is one to add. */
    static String withLine(String scope, String line) {
        if (line == null || line.isEmpty()) {
            return scope;
        }
        return scope == null || scope.isBlank() ? line : scope + "\n" + line;
    }

    /** The need list with the received line after it — the same text to the drafter and the claim check. */
    static String withMoment(String scope, String moment) {
        String line = com.sellerops.inquiry.decision.InquiryMoment.scopeLine(moment);
        if (line.isEmpty()) {
            return scope;
        }
        return scope == null || scope.isBlank() ? line : scope + "\n" + line;
    }

    /**
     * Whether the check's item-by-item answer is whole: it names every need the drafter was told to take up, and no id
     * outside the list it was sent. A need it was told to leave to the seller may be named or not — it was sent, and
     * nothing is read from it. Always whole without a need decision.
     */
    static boolean coverageAnswered(com.sellerops.inquiry.decision.NeedDecision decision,
                                    java.util.Map<String, String> covered) {
        if (decision == null) {
            return true;
        }
        java.util.Set<String> got = covered == null ? java.util.Set.of() : covered.keySet();
        java.util.Set<String> sent = decision.needs().stream().map(n -> n.need().id())
                .collect(java.util.stream.Collectors.toSet());
        return sent.containsAll(got) && got.containsAll(decision.toTakeUp().stream().map(n -> n.need().id()).toList());
    }

    /**
     * What the draft did not take up: a need it was told to answer, answer in part or ask about, that the check calls
     * NOT_COVERED. Read only after {@link #coverageAnswered} — a need the answer does not name is a check that did not
     * finish, not a dropped need.
     */
    static List<com.sellerops.inquiry.decision.NeedResult> notTakenUp(
            com.sellerops.inquiry.decision.NeedDecision decision, java.util.Map<String, String> covered) {
        if (decision == null || covered == null) {
            return List.of();
        }
        return decision.toTakeUp().stream().filter(n -> {
            String c = covered.get(n.need().id());
            return !"ANSWERED".equals(c) && !"ASKED".equals(c);
        }).toList();
    }

    public static final String DECISION_UNAVAILABLE =
            "문의를 항목별로 확인하지 못해 자동 초안을 만들지 않았어요. 잠시 후 다시 준비해 보시거나 직접 작성해 주세요.";

    /**
     * Inquiry Decision v2 reached no decision (the planner or the judge did not answer): that is an operational fact,
     * not 「답변 기준이 필요합니다」 — the seller's knowledge may well cover this, we just did not finish looking.
     */
    static String decisionUnavailable(com.sellerops.inquiry.decision.NeedDecision decision) {
        if (decision == null) {
            return null;
        }
        return switch (decision.outcome()) {
            case PLAN_FAILED, JUDGE_FAILED -> DECISION_UNAVAILABLE;
            default -> null;
        };
    }

    /**
     * The seller's registered 회사 정보, or null. Never fails a draft, and never a basis.
     *
     * <p>Null when no profile exists, when the lookup threw, or when the service is absent — three
     * different facts that all mean the same thing to a drafter: nothing to say about the company.
     */
    private String companyContextFor(UUID orgId) {
        try {
            return profiles == null ? null : profiles.summaryFor(orgId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The org's answer style, or the shipped default. Never fails a draft.
     *
     * <p>A settings lookup that threw would otherwise decide whether a seller gets a reply, and the
     * honest degradation is the wording this product had before the setting existed — not silence.
     */
    private AnswerStyleProfile styleFor(UUID orgId) {
        try {
            return styles == null ? AnswerStyleProfile.defaults() : styles.profileFor(orgId);
        } catch (RuntimeException e) {
            return AnswerStyleProfile.defaults();
        }
    }

    /**
     * {@code agent-draft/v1+…+style/v3} — which wording produced this version.
     *
     * <p>Appended to the model version rather than given a column of its own, because the question a
     * reader asks months later is one question: "what wrote this". A prompt snapshot would answer it
     * too, and would also store the customer's message a second time.
     */
    private static String stamped(String modelVersion, AnswerStyleProfile style) {
        String identity = style == null ? AnswerStyleProfile.defaults().identity() : style.identity();
        return modelVersion == null ? identity : modelVersion + "+" + identity;
    }

    /**
     * The seller's own approved sentence, saved as a draft version, with no model call.
     *
     * <p><b>Verbatim.</b> No greeting is prepended, no closing appended, no tone applied — the rest
     * of the style profile is about how a model should word an answer, and this is not an answer. It
     * is the company's own way of saying "we will check and come back", which is exactly why it is
     * allowed to appear where a generated deferral is not.
     *
     * <p>The basis verdict is unchanged: this is still {@code NO_ANSWER_BASIS}, the screen still says
     * what is missing, and the seller can still add the knowledge that would produce a real answer.
     * A draft that defers is not a draft that answers, and the two must not look the same.
     */
    private GeneratedDraftView approvedFallback(UUID orgId, UUID workItemId, String actor,
                                                String inquiryTitle,
                                                InquiryEvidenceRetriever.InquiryEvidence retrieved,
                                                AnswerBasisState basis,
                                                SpecApplicability.Verdict verdict,
                                                KnowledgeTopic asked,
                                                java.util.Set<KnowledgeTopic> named,
                                                AnswerStyleProfile style,
                                                com.sellerops.inquiry.draft.dto.KnowledgeGapView gap) {
        int base = drafts.currentVersion(workItemId);
        ReplyDraftView saved = drafts.saveAs(orgId, workItemId, actor, defaultTitle(inquiryTitle),
                style.unknownFallback(), base,
                new InquiryReplyDraftService.Provenance(DraftAuthorKind.SELLER_APPROVED_FALLBACK,
                        style.identity(), retrieved.state(), retrieved.productId(), basis));
        // No evidence rows: nothing was cited, because nothing applied. A citation of an absence is
        // the one kind of evidence this product does not record.
        return new GeneratedDraftView(saved, DraftAuthorKind.SELLER_APPROVED_FALLBACK.name(),
                retrieved.state().name(), retrieved.state().messageKo(retrieved.scopes()),
                basis.name(), basis.messageKo(),
                gap != null && gap.catalogueChecked() != null ? gap.catalogueChecked()
                        : basis.actionKo(retrieved.state(), verdict.topicWord(), verdict.applicability(),
                        retrieved.productOutcome(), retrieved.policyOutcome(), asked, retrieved.policyDeclares(asked),
                        verdict.optionsRegistered()),
                retrieved.productId(), List.of(), false, null, gap);
    }

    /**
     * The view for a draft that was not written, and why.
     *
     * <p>No version is saved, so a later approval cannot bind to something nobody composed, and the
     * work item's version counter does not advance on a non-event.
     */
    private static GeneratedDraftView noBasis(InquiryEvidenceRetriever.InquiryEvidence retrieved,
                                              AnswerBasisState basis,
                                              SpecApplicability.Verdict verdict,
                                              KnowledgeTopic asked,
                                              java.util.Set<KnowledgeTopic> named,
                                              String unavailableMessage,
                                              com.sellerops.inquiry.draft.dto.KnowledgeGapView gap) {
        String action = gap != null && gap.catalogueChecked() != null && basis == AnswerBasisState.NO_ANSWER_BASIS
                ? gap.catalogueChecked()
                : basis.actionKo(retrieved.state(), verdict.topicWord(), verdict.applicability(),
                        retrieved.productOutcome(), retrieved.policyOutcome(), asked, retrieved.policyDeclares(asked),
                        verdict.optionsRegistered());
        return new GeneratedDraftView(null, null, retrieved.state().name(),
                retrieved.state().messageKo(retrieved.scopes()), basis.name(), basis.messageKo(), action,
                retrieved.productId(), List.of(), false, unavailableMessage, gap);
    }

    /**
     * One bounded 상세페이지 read, when this inquiry names a product exactly and that product's
     * detail has never been read (or is 30 days old).
     *
     * <p><b>Every failure is swallowed here on purpose.</b> The seller asked for a draft, not for a
     * channel read, and a NAVER outage must not become an error on their screen. The trigger already
     * reports its own outcome; this catch exists for the repository lookups around it.
     */
    private String enrichDetailIfNeeded(UUID orgId, Inquiry inquiry) {
        if (inquiry.getProductId() == null || inquiry.productBinding() == null) {
            // No attribution, or an attribution nothing stated — there is no listing to read.
            return null;
        }
        try {
            ProductDetailEnrichmentTrigger.Outcome outcome = detail.enrichIfNeeded(orgId, inquiry.getProductId()).outcome();
            // A refusal of this caller did not finish the read either — same sentence, never silence.
            return outcome == ProductDetailEnrichmentTrigger.Outcome.READ_FAILED
                    || outcome == ProductDetailEnrichmentTrigger.Outcome.CHANNEL_REFUSED ? DETAIL_READ_FAILED : null;
        } catch (RuntimeException ignored) {
            // Deliberately silent about the CAUSE — the trigger logs its own outcomes and a second
            // line here would say the same thing with less information — but not about the FACT: a
            // lookup that threw is a lookup that did not finish, exactly like a channel that refused.
            return DETAIL_READ_FAILED;
        }
    }

    /**
     * Whether this product's pictures are being read right now. Swallows everything: a draft must not
     * fail because a progress query did.
     */
    private boolean inFlight(UUID orgId, com.sellerops.inquiry.Inquiry inquiry) {
        try {
            return images != null && images.inFlight(orgId, inquiry.getProductId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every option recorded for the bound product; empty when there are no variants. */
    private List<SpecApplicability.Option> optionsFor(UUID orgId, UUID productId) {
        return productId == null ? List.of()
                : variants.findByOrgIdAndProductId(orgId, productId).stream()
                        .filter(v -> v.getOptionName() != null && !v.getOptionName().isBlank())
                        .map(v -> new SpecApplicability.Option(v.getId(), v.getOptionName()))
                        .toList();
    }

    /** The evidence for one draft version, for a reader that did not just generate it. */
    public List<DraftEvidenceView> evidenceFor(UUID orgId, UUID workItemId, int version) {
        workItems.findById(workItemId)
                .filter(w -> w.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("문의 작업을 찾을 수 없어요."));
        return snippets.viewsOf(
                evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(workItemId, version));
    }

    /** The catalogue statements that ground this draft; empty unless this was a catalogue question it answered. */
    static List<CatalogueInvestigator.Statement> catalogueMatches(CatalogueInvestigator.Finding finding) {
        return finding == null || !finding.grounds() ? List.of() : finding.matches().stream()
                .limit(CATALOGUE_PASSAGES).toList();
    }

    /** The knowledge note when the seller's catalogue, and nothing in this product's own library, answered. */
    static final String CATALOGUE_GROUNDED = "판매자가 지금 판매 중인 상품 정보를 근거로 썼어요.";

    /**
     * The draft's knowledge state: GROUNDED when the catalogue answered or the need decision gave the writer evidence of
     * the covered needs, the lanes' own state otherwise — the state says what the draft was written from.
     */
    static DraftKnowledgeState knowledgeStateOf(InquiryKnowledgeAssessor.Assessment assessment) {
        com.sellerops.inquiry.decision.NeedDecision decision = assessment.decision();
        boolean decisionGrounded = decision != null && !decision.shownEvidence().isEmpty();
        return assessment.groundedByCatalogue() || decisionGrounded ? DraftKnowledgeState.GROUNDED
                : assessment.retrieved().state();
    }

    /** Catalogue statements a draft is shown. Enough to name what is on sale; not a product list. */
    static final int CATALOGUE_PASSAGES = 3;

    /**
     * A statement from the seller's own catalogue, labelled for what it is: the product the inquiry is on
     * ({@code 상품 정보}), or another product the seller sells ({@code 판매 중인 다른 상품}) — the one label the prompt
     * forbids moving a figure out of.
     */
    static String catalogueLabel(CatalogueInvestigator.Statement s) {
        return s.current() ? KnowledgeScope.PRODUCT.labelKo() : InquiryDraftEvidence.LABEL_CATALOGUE_PRODUCT;
    }

    private static List<AgentDraftGenerator.Passage> passagesFor(
            CatalogueInvestigator.Finding catalogue,
            List<InquiryEvidenceRetriever.ScopedPassage> passages,
            List<com.sellerops.knowledge.spine.KnowledgeEntry> context) {
        // The catalogue first when it answered: for what is on sale now it is the current authority, ahead of any
        // past answer the lanes carried.
        List<AgentDraftGenerator.Passage> out = new ArrayList<>();
        for (CatalogueInvestigator.Statement s : catalogueMatches(catalogue)) {
            out.add(new AgentDraftGenerator.Passage(catalogueLabel(s), s.productName(), s.text()));
        }
        out.addAll(passages.stream()
                .map(p -> new AgentDraftGenerator.Passage(p.scope().labelKo(), p.heading(), p.text()))
                .toList());
        // Seller guidance and the channel's stated attributes, after the grounding passages. They inform the reply;
        // whether a reply may be written at all was already decided from the passages alone.
        for (com.sellerops.knowledge.spine.KnowledgeEntry entry : context) {
            out.add(new AgentDraftGenerator.Passage(contextLabel(entry), entry.title(), entry.text()));
        }
        return out;
    }

    static String contextLabel(com.sellerops.knowledge.spine.KnowledgeEntry entry) {
        return entry.sourceType() == com.sellerops.knowledge.spine.SpineSourceType.SELLER_GUIDANCE
                ? InquiryDraftEvidence.LABEL_SELLER_GUIDANCE : KnowledgeScope.PRODUCT.labelKo();
    }

    /**
     * The material a need decision's drafter was shown, as {@link InquiryFigureGrounds} reads it again — line for line with
     * {@link com.sellerops.inquiry.decision.NeedDecision#passages}, so the claim check's 「근거 n」 names the n-th; null
     * where a line is not the seller's material.
     */
    static List<InquiryFigureGrounds.Shown> shownOf(List<com.sellerops.inquiry.decision.EvidenceCandidate> shown) {
        List<InquiryFigureGrounds.Shown> out = new ArrayList<>();
        for (com.sellerops.inquiry.decision.EvidenceCandidate e : shown) {
            InquiryDraftFigure.Ground kind = switch (e.kind()) {
                case ORG_KNOWLEDGE -> InquiryDraftFigure.Ground.ORG_POLICY;
                case PRODUCT_KNOWLEDGE -> InquiryDraftFigure.Ground.PRODUCT_KNOWLEDGE;
                case PRODUCT_FACTS, OPTIONS, ADDONS -> InquiryDraftFigure.Ground.PRODUCT_FACTS;
                // The order sentence is looked up as itself, after the seller's material.
                case ORDER_FACT -> null;
                case SELLER_CHECK -> InquiryDraftFigure.Ground.SELLER_CHECK;
            };
            out.add(kind == null ? null : new InquiryFigureGrounds.Shown(kind, e.sourceId(), e.productId(), e.text()));
        }
        return out;
    }

    /**
     * The material the lexical lanes' drafter was shown, likewise, line for line with {@link #passagesFor}. A past answer
     * is not material a figure can rest on: its line is null.
     */
    static List<InquiryFigureGrounds.Shown> shownOf(List<CatalogueInvestigator.Statement> catalogue,
                                                    List<InquiryEvidenceRetriever.ScopedPassage> passages,
                                                    List<com.sellerops.knowledge.spine.KnowledgeEntry> context) {
        List<InquiryFigureGrounds.Shown> out = new ArrayList<>();
        for (CatalogueInvestigator.Statement s : catalogue) {
            out.add(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.PRODUCT_FACTS, null, s.productId(), s.text()));
        }
        for (InquiryEvidenceRetriever.ScopedPassage p : passages) {
            if (p.scope() == KnowledgeScope.PRODUCT) {
                out.add(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.PRODUCT_KNOWLEDGE, p.sourceId(), null,
                        p.text()));
            } else if (p.scope() == KnowledgeScope.ORG_OPERATIONS) {
                out.add(new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, p.sourceId(), null, p.text()));
            } else {
                out.add(null);
            }
        }
        for (com.sellerops.knowledge.spine.KnowledgeEntry entry : context) {
            boolean guidance = entry.sourceType() == com.sellerops.knowledge.spine.SpineSourceType.SELLER_GUIDANCE;
            out.add(new InquiryFigureGrounds.Shown(guidance ? InquiryDraftFigure.Ground.SELLER_GUIDANCE
                    : InquiryDraftFigure.Ground.PRODUCT_FACTS,
                    guidance && !entry.sourceRefs().isEmpty() ? entry.sourceRefs().get(0).id() : null,
                    entry.productId(), entry.text()));
        }
        return out;
    }

    private List<DraftEvidenceView> recordEvidence(UUID orgId, UUID workItemId, int version,
                                                   List<CatalogueInvestigator.Statement> catalogue,
                                                   List<InquiryEvidenceRetriever.ScopedPassage> passages,
                                                   List<com.sellerops.knowledge.spine.KnowledgeEntry> context,
                                                   OrderFact order) {
        List<DraftEvidenceView> views = new ArrayList<>(passages.size() + 1);
        int ordinal = 0;
        // The exact product source for every catalogue statement the draft was shown: which product (source id), where
        // on it and when it was captured (locator). The excerpt is the statement itself.
        for (CatalogueInvestigator.Statement s : catalogue) {
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            row.setOrdinal(ordinal++);
            row.setKind(s.current() ? InquiryDraftEvidence.KIND_PRODUCT_FACT
                    : InquiryDraftEvidence.KIND_CATALOGUE_PRODUCT);
            row.setSourceId(s.productId());
            row.setTitle(s.productName());
            row.setLocator(s.locator());
            evidence.save(row);
            views.add(new DraftEvidenceView(row.getKind(), catalogueLabel(s), row.getTitle(), row.getLocator(),
                    row.getSourceId(), null, DraftEvidenceView.snippetOf(s.text())));
        }
        for (InquiryEvidenceRetriever.ScopedPassage passage : passages) {
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            row.setOrdinal(ordinal++);
            row.setKind(InquiryDraftEvidence.kindOf(passage.scope()));
            row.setSourceId(passage.sourceId());
            row.setChunkId(passage.chunkId());
            row.setTitle(passage.heading());
            row.setLocator(passage.locator());
            evidence.save(row);
            // The excerpt comes from the passage the drafter was ACTUALLY shown, not from a
            // re-read: at generation the two are the same text, and this one cannot go stale.
            views.add(new DraftEvidenceView(row.getKind(), passage.scope().labelKo(), row.getTitle(),
                    row.getLocator(), row.getSourceId(), row.getChunkId(),
                    DraftEvidenceView.snippetOf(passage.text())));
        }
        for (com.sellerops.knowledge.spine.KnowledgeEntry entry : context) {
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            row.setOrdinal(ordinal++);
            row.setKind(entry.sourceType() == com.sellerops.knowledge.spine.SpineSourceType.SELLER_GUIDANCE
                    ? InquiryDraftEvidence.KIND_SELLER_GUIDANCE : InquiryDraftEvidence.KIND_PRODUCT_FACT);
            row.setSourceId(entry.sourceRefs().isEmpty() ? null : entry.sourceRefs().get(0).id());
            row.setTitle(entry.title());
            row.setLocator(entry.entryId().substring(0, entry.entryId().indexOf(':')).toLowerCase(java.util.Locale.ROOT)
                    + "/" + (entry.channelCode() == null ? "판매자" : entry.channelCode()));
            evidence.save(row);
            views.add(new DraftEvidenceView(row.getKind(), contextLabel(entry), row.getTitle(), row.getLocator(),
                    row.getSourceId(), null, DraftEvidenceView.snippetOf(entry.text())));
        }
        // Last, and only when an order was actually resolved. A row for "주문 번호가 없었습니다" would
        // be a citation of an absence, and the screen already says that in the state sentence.
        if (order != null && order.available()) {
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            row.setOrdinal(ordinal);
            row.setKind(InquiryDraftEvidence.KIND_ORDER_FACT);
            // No source/chunk: this evidence points at no document. What it points at is a moment.
            row.setTitle(KnowledgeScope.ORDER_STATE.labelKo());
            row.setLocator(orderLocator(order));
            evidence.save(row);
            // No excerpt: an order fact points at a moment, not a document.
            views.add(new DraftEvidenceView(row.getKind(), KnowledgeScope.ORDER_STATE.labelKo(),
                    row.getTitle(), row.getLocator(), null, null, null));
        }
        return views;
    }

    /**
     * The citations of a draft written from a need decision: every candidate the writer was shown, in the order it was
     * shown. A rule or product passage keeps its source and passage ids, so the excerpt can be read back; a catalogue
     * candidate points at its listing; an order fact is cited only when an order was actually resolved.
     */
    /** Where the need decision's cited evidence lives, as the seller names it (상품 정보 · 운영 정책 · 주문 상태). */
    static java.util.Set<com.sellerops.knowledge.KnowledgeScope> scopesOf(
            List<com.sellerops.inquiry.decision.EvidenceCandidate> shown) {
        java.util.Set<com.sellerops.knowledge.KnowledgeScope> scopes =
                java.util.EnumSet.noneOf(com.sellerops.knowledge.KnowledgeScope.class);
        for (com.sellerops.inquiry.decision.EvidenceCandidate e : shown) {
            com.sellerops.knowledge.KnowledgeScope scope = switch (e.kind()) {
                case ORG_KNOWLEDGE -> com.sellerops.knowledge.KnowledgeScope.ORG_OPERATIONS;
                case PRODUCT_KNOWLEDGE, PRODUCT_FACTS, OPTIONS, ADDONS -> com.sellerops.knowledge.KnowledgeScope.PRODUCT;
                case ORDER_FACT -> com.sellerops.knowledge.KnowledgeScope.ORDER_STATE;
                // The seller's record is not a library and not the channel's order state: it is named by its own
                // evidence row (판매자 확인), never folded into one of these.
                case SELLER_CHECK -> null;
            };
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private List<DraftEvidenceView> recordDecisionEvidence(UUID orgId, UUID workItemId, int version,
                                                           List<com.sellerops.inquiry.decision.EvidenceCandidate> shown,
                                                           OrderFact order) {
        List<DraftEvidenceView> views = new ArrayList<>(shown.size());
        int ordinal = 0;
        for (com.sellerops.inquiry.decision.EvidenceCandidate e : shown) {
            String kind = switch (e.kind()) {
                case ORG_KNOWLEDGE -> InquiryDraftEvidence.KIND_ORG_POLICY;
                case PRODUCT_KNOWLEDGE -> InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE;
                case PRODUCT_FACTS, OPTIONS, ADDONS -> InquiryDraftEvidence.KIND_PRODUCT_FACT;
                case ORDER_FACT -> InquiryDraftEvidence.KIND_ORDER_FACT;
                case SELLER_CHECK -> InquiryDraftEvidence.KIND_SELLER_CHECK;
            };
            if (e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.ORDER_FACT
                    && (order == null || !order.available())) {
                continue;
            }
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            row.setOrdinal(ordinal++);
            row.setKind(kind);
            boolean knowledge = e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.ORG_KNOWLEDGE
                    || e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE;
            row.setSourceId(knowledge || e.sourceId() != null ? e.sourceId() : e.productId());
            row.setChunkId(knowledge ? e.chunkId() : null);
            row.setTitle(e.label());
            row.setLocator(e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.ORDER_FACT
                    ? orderLocator(order)
                    : e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.SELLER_CHECK
                    ? "seller-check/" + e.factKey()
                    : "need-decision/" + e.kind().name().toLowerCase(java.util.Locale.ROOT)
                            + (e.factKey() == null ? "" : ":" + e.factKey()));
            evidence.save(row);
            views.add(new DraftEvidenceView(row.getKind(), e.kind().scopeLabelKo(), row.getTitle(), row.getLocator(),
                    row.getSourceId(), row.getChunkId(),
                    e.kind() == com.sellerops.inquiry.decision.EvidenceCandidate.Kind.ORDER_FACT ? null
                            : DraftEvidenceView.snippetOf(e.text())));
        }
        return views;
    }

    /**
     * {@code order-fact/NAVER:OBSERVED_FRESH@2026-08-25} — channel, freshness, and the day it was seen.
     *
     * <p>The order identifier is deliberately absent. A locator exists so a person can re-check the
     * claim, and re-checking an order state means re-reading the channel at a date — which this
     * says — not looking the number up in a log.
     */
    private static String orderLocator(OrderFact order) {
        String day = order.asOf() == null ? "미상"
                : order.asOf().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        return "order-fact/" + (order.channelCode() == null ? "채널미상" : order.channelCode())
                + ":" + order.state().name() + "@" + day;
    }

    private static String defaultTitle(String inquiryTitle) {
        String base = inquiryTitle == null || inquiryTitle.isBlank() ? "문의" : inquiryTitle.strip();
        String prefixed = "[답변] " + base;
        return prefixed.length() > 100 ? prefixed.substring(0, 100) : prefixed;
    }

    /**
     * The one operating topic the customer's own words name, or null — the noun the gap line is
     * allowed to use. Two topics (「반품 배송비」) name none: the line stays general rather than guess.
     */
    private static KnowledgeTopic askedTopic(Inquiry inquiry) {
        java.util.Set<KnowledgeTopic> topics = namedTopics(inquiry);
        return topics.size() == 1 ? topics.iterator().next() : null;
    }

    /** Every operating topic the customer's words name — the set {@link #askedTopic} is the single member of. */
    private static java.util.Set<KnowledgeTopic> namedTopics(Inquiry inquiry) {
        return KnowledgeTopic.of(
                (inquiry.getTitle() == null ? "" : inquiry.getTitle()) + " "
                        + (inquiry.getBody() == null ? "" : inquiry.getBody()));
    }
}
