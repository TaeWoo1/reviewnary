package com.sellerops.agent.llm;

import com.sellerops.agent.llm.provider.LlmReply;
import com.sellerops.agent.llm.provider.LlmRequest;
import com.sellerops.agent.llm.provider.LlmVendor;
import com.sellerops.agent.llm.provider.LlmWire;
import java.util.List;
import java.util.Optional;

/**
 * The inquiry reply-draft generator: one API call per inquiry, over {@link AgentLlmTransport}.
 *
 * <p><b>This is the LLM behind the LangGraph model seam.</b> `agent-runtime`'s
 * {@code DraftModelSeam} is a pluggable provider whose only implementation was a Korean template
 * table, and the ADR that recorded the graph/LLM split named two reasons it stayed that way: the
 * privacy decision about inquiry content, and the fact that {@code agent-runtime} holds no
 * credential of any kind. Both are answered HERE rather than there — the runtime calls this endpoint
 * with the operator's own bearer token and never sees a vendor key, so the backend remains the only
 * LLM egress in the repository and there is still exactly one place a key lives.
 *
 * <p><b>The payload floor lives here.</b> {@link #requestBody} is the only place a request is built,
 * it reads exactly two values off {@link Input}, and {@code AgentDraftPayloadFloorTest} asserts the
 * serialized bytes rather than this method's intent.
 *
 * <p><b>Every failure is {@code Optional.empty()}.</b> Disabled, unkeyed, refused, timed out,
 * rate-limited, budget-exhausted, malformed — the caller has exactly one response to all of them
 * (report "no model draft"; the graph falls back to its deterministic rule drafter), and a generator
 * that distinguished them would hand the caller reasons it has no use for. The coarse reason is
 * returned separately for the operator-facing log line and never contains vendor body text: an error
 * body can quote the request, and the request contains the seller's inquiry.
 *
 * <p>The API key is read once at construction and never logged, never echoed in a reason, and never
 * placed anywhere a {@link Result} can reach.
 */
public class AgentDraftGenerator {

    /** Which wire format to speak — the shared model connection's vendor (2026-10-29). Nothing else differs. */
    public enum Vendor {
        ANTHROPIC(LlmVendor.ANTHROPIC),
        OPENAI(LlmVendor.OPENAI);

        private final LlmVendor vendor;

        Vendor(LlmVendor vendor) {
            this.vendor = vendor;
        }
    }

    private final AgentLlmTransport http;
    private final Vendor vendor;
    /** The vendor's spelling of every call this generator makes (shared model connection, 2026-10-29). */
    private final LlmWire wire;
    private final String modelId;
    private final String apiKey;
    private final int maxOutputTokens;
    private final String reasoningEffort;
    private final String version;

    public AgentDraftGenerator(AgentLlmTransport http, Vendor vendor, String modelId, String apiKey,
                               int maxOutputTokens, String reasoningEffort) {
        this.http = http;
        this.vendor = vendor;
        this.wire = LlmWire.of(vendor.vendor);
        this.modelId = modelId;
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
        // Everything that decides what a draft is, in one string, so a recorded run can be read back without
        // consulting anything else — the same discipline the triage classifier's version string carries.
        this.version = "agent-draft/v1+" + vendor.name().toLowerCase() + ":" + modelId
                + "+" + AgentDraftPrompt.PROMPT_VERSION + "+schema/v1+out" + maxOutputTokens
                + (reasoningEffort == null ? "" : "+effort:" + reasoningEffort);
    }

    public String version() {
        return version;
    }

    /**
     * The seller's own inquiry, and — since Inquiry Draft v1 — the seller's own product knowledge.
     * Exactly the three things that may leave; see {@link AgentDraftPrompt#user}.
     *
     * <p>{@code knowledge} is what the seller typed into 상품 지식 for the resolved product, retrieved
     * for this question. It widens the payload floor deliberately: a draft that must not invent facts
     * has to be given the facts, and the alternative — retrieving evidence and then not showing it to
     * the drafter — produces a draft grounded in nothing while carrying citations that suggest
     * otherwise. It is the seller's own writing, which is a strictly narrower class than the customer
     * text already in {@code details}.
     *
     * <p>Empty when no product resolved, no library exists, or nothing matched. Empty is a real state
     * the prompt is told about, not a silent absence.
     *
     * <p><b>{@code style} is the fourth and last thing that may leave</b> (Organization Answer Style
     * v1): the org's own wording preferences, rendered into prompt text by
     * {@code AnswerStyleInstruction} — never the seller's raw setting concatenated into a system
     * turn. It names no customer, no order, no product and no identifier, and it is the only field
     * here whose absence is rendered as nothing at all.
     */
    public record Input(String title, String details, List<Passage> knowledge, String orderState,
                        String specScope, String style, String companyContext, String answerScope) {

        /**
         * Before Inquiry Decision v2. {@code answerScope} is the need list the coverage gate settled — which needs this
         * reply answers and which it only asks the customer about — written by us from the needs, never evidence.
         */
        public Input(String title, String details, List<Passage> knowledge, String orderState,
                     String specScope, String style, String companyContext) {
            this(title, details, knowledge, orderState, specScope, style, companyContext, null);
        }

        public Input(String title, String details) {
            this(title, details, List.of(), null, null, null, null);
        }

        public Input(String title, String details, List<Passage> knowledge) {
            this(title, details, knowledge, null, null, null, null);
        }

        public Input(String title, String details, List<Passage> knowledge, String orderState) {
            this(title, details, knowledge, orderState, null, null, null);
        }

        public Input(String title, String details, List<Passage> knowledge, String orderState,
                     String specScope) {
            this(title, details, knowledge, orderState, specScope, null, null);
        }

        /**
         * {@code companyContext} (Seller Context v1-B) is the fifth and last class of content: the
         * seller's own description of their company, quoted as data. Context for wording, never
         * evidence — the prompt says so under it, and no basis verdict reads it.
         */
        public Input(String title, String details, List<Passage> knowledge, String orderState,
                     String specScope, String style) {
            this(title, details, knowledge, orderState, specScope, style, null);
        }

        public Input {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        }
    }

    /**
     * One piece of seller-authored evidence, as it goes to the model: which KIND of evidence it is,
     * a heading, and the text. No id, no product id, no author, no timestamp, no score — the model
     * does not need them and the floor test asserts they are absent. The citation is reassembled
     * from the retrieval result on the way back, where the ids never left the backend.
     *
     * <p>{@code scopeLabel} is one of a closed set of constants ("상품 정보", "운영 정책", "과거 답변")
     * and carries nothing about this seller or this customer. It is on the wire because the model has
     * to be told that a shipping policy is not a product specification: a drafter that reads all
     * evidence as one undifferentiated block will answer a spec question out of a return policy.
     */
    public record Passage(String scopeLabel, String heading, String text) {

        /** Evidence with no scope stated — the shape callers used before there were three lanes. */
        public Passage(String heading, String text) {
            this(null, heading, text);
        }
    }

    /**
     * A generated draft, or a coarse reason there is none.
     *
     * @param draft  the validated candidate, or empty
     * @param reason a sanitized enum-ish marker for the log line; never vendor body text
     * @param metrics what the call cost in time and tokens — reported on every path, failures included
     */
    public record Result(Optional<AgentDraftResponseParser.ParsedDraft> draft, String reason, String version,
                         AgentLlmCallMetrics metrics) {

        static Result failed(String version, String reason, AgentLlmCallMetrics metrics) {
            return new Result(Optional.empty(), reason, version, metrics);
        }
    }

    /**
     * <b>The review lane's input — a separate record, so the review payload cannot widen by accident.</b>
     *
     * <p>It is not {@link Input} with fields left null. Reusing that record would mean the review
     * request travels through the same builder as the inquiry one, and every future field added for
     * inquiries (an order line, a 규격 line, a company summary) would silently start leaving on review
     * requests too. Two records, two builders, two floor tests.
     */
    public record ReviewInput(String reviewBody, List<Passage> knowledge, String style, List<String> banned,
                              ReviewMood mood, String manner) {

        public ReviewInput {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            banned = banned == null ? List.of() : List.copyOf(banned);
            mood = mood == null ? ReviewMood.UNSTATED : mood;
        }

        /** No 답변 스타일 — every request before the owner let the seller set one for reviews (2026-10-27). */
        public ReviewInput(String reviewBody, List<Passage> knowledge, String style, List<String> banned,
                           ReviewMood mood) {
            this(reviewBody, knowledge, style, banned, mood, null);
        }

        public ReviewInput(String reviewBody, List<Passage> knowledge, String style, List<String> banned) {
            this(reviewBody, knowledge, style, banned, ReviewMood.UNSTATED);
        }

        public ReviewInput(String reviewBody, List<Passage> knowledge, String style) {
            this(reviewBody, knowledge, style, List.of());
        }
    }

    /**
     * <b>What the customer felt, said as a word, never as a number</b> (owner, 2026-10-27).
     *
     * <p>Left to read the mood from the text alone, the model closed four of six 1–2★ replies on the owner's judging
     * sheet with 「좋은 하루 보내세요」 — to 「붙여 놓고 하루 만에 다 떨어졌어요」 — and answered 「버튼을 누르면 컵이 두 개씩 나와요」 with
     * 「이용 중 불편」. The rating still never leaves (it would invite 「별점 4점 주셔서 감사합니다」): the composer turns it
     * into one of these words, with the 3★ split the triage rule already makes, and {@link #UNSTATED} adds nothing to
     * the request.
     */
    public enum ReviewMood {
        /** Nothing said: praise, or a review the rule cannot place. The request is byte-for-byte what it was. */
        UNSTATED,
        /** 1–2★: the customer is unhappy, whatever words they chose. */
        COMPLAINT,
        /** 3★ with a reservation in its words: something pleased and something did not. */
        MIXED,
        /**
         * 3★ with no reservation in its words (owner, 2026-10-28: 「★3 칭찬은 질문보다는 더 친절하게 응대」). The
         * customer chose a middling number and wrote nothing against the product, so the reply is a warmer thank-you —
         * never an apology, and never a reason to ask the seller for a standard.
         */
        MILD_PRAISE
    }

    /**
     * Generate one public review reply. Same transport, same model, same metrics — its own prompt.
     *
     * <p>The vendor branch is shared with {@link #generate} rather than duplicated: which field a
     * vendor wants the system turn in is a fact about the vendor, not about what we are drafting.
     */
    public Result generateReview(ReviewInput input) {
        // Its own parser: the review contract is one field, and reusing the inquiry parser would
        // refuse every well-formed review answer for lacking a title it was told not to write.
        return send(reviewRequestBody(input), AgentDraftResponseParser::parseReviewDraft);
    }

    /**
     * The serialized review request — <b>the review payload floor</b>.
     *
     * <p>Asserted on these bytes by {@code AgentReviewDraftPayloadFloorTest}, for the reason the
     * inquiry floor is asserted on its bytes: an intention is not a boundary.
     */
    String reviewRequestBody(ReviewInput input) {
        return wire.body(LlmRequest.of(modelId, AgentDraftPrompt.reviewSystem(), AgentDraftPrompt.reviewUser(input.knowledge(),
                input.reviewBody(), input.style(), input.banned(), input.mood(), input.manner()), maxOutputTokens, reasoningEffort,
                LlmRequest.Format.jsonObject()));
    }

    public Result generate(Input input) {
        return send(requestBody(input), AgentDraftResponseParser::parse);
    }

    /**
     * One figure the model was asked about, and what it said: SUPPORTED · SAID_BACK · NOT_SUPPORTED · CONFLICT — and,
     * since {@code inquiry-claim-check/v6} (2026-11-01 third review), what it read the number from: {@code passage}, the
     * 1-based number of the evidence line, or null; {@code source} {@code 근거} · {@code 주문} · {@code 문의} or null;
     * {@code about}, what the number is about in the evidence's own words; {@code when}, the condition it holds under.
     */
    public record FigureVerdict(int n, String verdict, Integer passage, String source, String about, String when) {
        public FigureVerdict(int n, String verdict) {
            this(n, verdict, null, null, null, null);
        }
    }

    /** One fact, condition or promise the claim check found in the draft, with its verdict. */
    public record ClaimVerdict(String text, String verdict) {
    }

    /** The claim check's answer — a verdict for every figure asked and every claim found, or empty with the reason. */
    /**
     * @param covered need id → ANSWERED · ASKED · NOT_COVERED, for the needs the drafter was told to take up (v11); empty
     *                when no need list was sent.
     */
    public record FigureResult(Optional<List<FigureVerdict>> verdicts, String reason, AgentLlmCallMetrics metrics,
                               List<ClaimVerdict> claims, boolean answered, java.util.Map<String, String> covered) {
        public FigureResult(Optional<List<FigureVerdict>> verdicts, String reason, AgentLlmCallMetrics metrics,
                            List<ClaimVerdict> claims, boolean answered) {
            this(verdicts, reason, metrics, claims, answered, java.util.Map.of());
        }

        public FigureResult(Optional<List<FigureVerdict>> verdicts, String reason, AgentLlmCallMetrics metrics) {
            this(verdicts, reason, metrics, List.of());
        }

        /** {@code answered}: whether the draft tries to answer the question (v7); a check that does not say is read as yes. */
        public FigureResult(Optional<List<FigureVerdict>> verdicts, String reason, AgentLlmCallMetrics metrics,
                            List<ClaimVerdict> claims) {
            this(verdicts, reason, metrics, claims, true);
        }
    }

    /**
     * What the figure check sends: the same question, passages, order sentence and 규격 line the draft was written over
     * (the line since v8 — 2026-11-01 ninth review: whether a caution was asked for is the check's to know, not a pass
     * this backend grants after it).
     */
    public record FigureInput(String question, List<Passage> evidence, String orderSentence, String draft,
                              List<String> figures, String specLine, String answerScope) {
        public FigureInput(String question, List<Passage> evidence, String orderSentence, String draft,
                           List<String> figures) {
            this(question, evidence, orderSentence, draft, figures, null, null);
        }

        public FigureInput(String question, List<Passage> evidence, String orderSentence, String draft,
                           List<String> figures, String specLine) {
            this(question, evidence, orderSentence, draft, figures, specLine, null);
        }
    }

    /** The figure check (owner, 2026-10-28): same transport, model and key as the draft — its own prompt and parser. */
    public FigureResult checkFigures(FigureInput input) {
        Text text = postForText(figureRequestBody(input));
        if (text.text().isEmpty()) {
            return new FigureResult(Optional.empty(), text.reason(), text.metrics());
        }
        Optional<List<FigureVerdict>> verdicts = AgentDraftResponseParser.parseFigureVerdicts(text.text().get(),
                input.figures().size());
        Optional<List<ClaimVerdict>> claims = verdicts.isEmpty() ? Optional.empty()
                : AgentDraftResponseParser.parseClaimVerdicts(text.text().get());
        Optional<Boolean> answered = claims.isEmpty() ? Optional.empty()
                : AgentDraftResponseParser.parseAnswered(text.text().get());
        // With a need list the item-by-item answer is required too (v11): without it nothing says what the draft dropped.
        boolean scoped = input.answerScope() != null && !input.answerScope().isBlank();
        Optional<java.util.Map<String, String>> covered = answered.isEmpty() ? Optional.empty()
                : scoped ? AgentDraftResponseParser.parseCovered(text.text().get()) : Optional.of(java.util.Map.of());
        if (verdicts.isEmpty() || claims.isEmpty() || answered.isEmpty() || covered.isEmpty()) {
            return new FigureResult(Optional.empty(), "off_schema", text.metrics());
        }
        return new FigureResult(verdicts, "ok", text.metrics(), claims.get(), answered.get(), covered.get());
    }

    /** The serialized figure-check request — the inquiry payload floor plus the draft this backend wrote. */
    String figureRequestBody(FigureInput input) {
        return wire.body(LlmRequest.of(modelId, AgentDraftPrompt.figureSystem(), AgentDraftPrompt.figureUser(input.question(),
                input.evidence(), input.orderSentence(), input.specLine(), input.answerScope(), input.draft(),
                input.figures()), maxOutputTokens, reasoningEffort,
                LlmRequest.Format.jsonObject()));
    }

    /**
     * The one call, shared by both lanes: post, measure, parse, classify.
     *
     * <p>The PARSER is the parameter, because that is the only thing the two lanes disagree about.
     * Everything before it — the transport, the metrics, the four refusal reasons — is about the
     * vendor and is identical whichever contract we asked for.
     */
    private Result send(String body, java.util.function.Function<String, Optional<AgentDraftResponseParser.ParsedDraft>> parser) {
        Text answered = postForText(body);
        AgentLlmCallMetrics metrics = answered.metrics();
        if (answered.text().isEmpty()) {
            return Result.failed(version, answered.reason(), metrics);
        }
        Optional<String> text = answered.text();
        Optional<AgentDraftResponseParser.ParsedDraft> parsed = parser.apply(text.get());
        if (parsed.isEmpty()) {
            // Off-schema, unknown category, blank field, or over-long. No repair pass and no second call: a
            // malformed answer is a refusal, and a partial candidate reaching a human as a reviewed draft is
            // worse than none.
            return Result.failed(version, "off_schema", metrics);
        }
        return new Result(parsed, "ok", version, metrics);
    }

    /** The assistant's text, or the reason there is none — shared by every call this generator makes. */
    private record Text(Optional<String> text, String reason, AgentLlmCallMetrics metrics) {
    }

    private Text postForText(String body) {
        AgentLlmTransport.Response response = http.post(wire.endpoint(), wire.headers(apiKey), body);
        AgentLlmCallMetrics metrics = AgentLlmCallMetrics.of(response);
        if (response.status() == 0) {
            // Transport-level: connect failure, timeout, interruption. The marker is the exception TYPE.
            return new Text(Optional.empty(), "transport:" + response.body(), metrics);
        }
        if (!response.ok()) {
            // The status only. A vendor error body can quote the request, and the request contains the inquiry.
            return new Text(Optional.empty(), "http:" + response.status(), metrics);
        }
        LlmReply reply = wire.read(response);
        if (reply.answered()) {
            return new Text(Optional.of(reply.content()), "ok", metrics);
        }
        return new Text(Optional.empty(), switch (reply.failure()) {
            // Named rather than left to surface as "empty response": on a reasoning model the output budget is shared
            // with internal reasoning, so this is the difference between "the model cannot do this" and "the budget
            // was too small" — and one of those is fixable by raising max-output-tokens.
            case "TRUNCATED" -> "budget_exhausted";
            // The vendor's safety classifier declined: no answer, named so the run says why.
            case "REFUSAL" -> "refusal";
            case "UNPARSEABLE" -> "unreadable_envelope";
            default -> "no_message_text";
        }, metrics);
    }

    /**
     * The whole outgoing payload, built from an inquiry title, its body, and the seller's own
     * retrieved product knowledge — and nothing else.
     *
     * <p>Package-private so {@code AgentDraftPayloadFloorTest} can assert the exact string. A check on
     * what this method <i>meant</i> to send would keep passing after someone added the work-item id
     * "for correlation".
     */
    String requestBody(Input input) {
        return wire.body(LlmRequest.of(modelId, AgentDraftPrompt.system(), AgentDraftPrompt.user(input.title(), input.details(),
                input.knowledge(), input.orderState(), input.specScope(), input.style(), input.companyContext(),
                input.answerScope()), maxOutputTokens, reasoningEffort,
                LlmRequest.Format.jsonObject()));
    }
}
