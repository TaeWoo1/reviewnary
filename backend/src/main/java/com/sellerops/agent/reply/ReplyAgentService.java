package com.sellerops.agent.reply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.access.AgentCapabilityAccess;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>The reply agent's one door to the model</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md}). Each step is
 * gated by this capability's own flag, charged as one metered step of its run ({@link AgentQuotaService#consumeStep}),
 * and read strictly: a category outside the standard, a rule id the standard does not have, or a blank reply is no
 * answer. Logs carry counts and reasons only — never review text or a draft.
 */
@Service
public class ReplyAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReplyAgentService.class);
    /**
     * Reads a model's answer as one JSON document and nothing else (review 2026-10-28, second pass): a second document
     * after the first, or a field written twice, is not an answer — 「{"failed":[]} {"failed":[{"id":"N9"}]}」 and
     * 「{"failed":[{"id":"N9"}],"failed":[]}」 had both read as a pass. Every step (judge, draft, critique) reads with it.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ReplyAgentProperties properties;
    private final AgentLlmTransport transport;
    private final AgentCapabilityAccess access;
    private final AgentQuotaService quota;
    private final ReplyStandard standard;

    public ReplyAgentService(ReplyAgentProperties properties, AgentLlmTransport transport, AgentCapabilityAccess access,
                             AgentQuotaService quota, ReplyStandard standard) {
        this.properties = properties;
        this.transport = transport;
        this.access = access;
        this.quota = quota;
        this.standard = standard;
    }

    public boolean isEnabledFor(UUID orgId) {
        return access.allows(properties, orgId);
    }

    public String version() {
        return generator().version();
    }

    public ReplyStandard standard() {
        return standard;
    }

    /**
     * The most calls a run may still make after this step, when this step opens it: a run judges, drafts, critiques, and
     * may revise and critique once more ({@code ReviewDraftPreparer.CALLS_PER_AGENT_RUN}). A run starts only when all of
     * them fit (sixth outside review, 2026-10-29); a judgement with no run asks for nothing more.
     */
    public static int callsAfter(String step) {
        return switch (step) {
            case "CLASSIFY" -> 4;
            case "DRAFT" -> 3;
            default -> 0;
        };
    }

    /** Who a run's calls are charged to: the seller when the seller started it, the run otherwise. */
    public static AgentUsageActor actorOf(String trigger) {
        return "SELLER".equals(trigger) ? AgentUsageActor.USER : AgentUsageActor.RUN;
    }

    /**
     * What the judge read: the first category (null when it said it cannot tell), every other category the review also
     * carries, and the points a reply must cover. The model may say UNSURE — that is an answer, a person's to read — but
     * anything off the standard, more than five points, or a point too long to be one is no answer.
     */
    public record Classified(String category, List<String> also, List<String> points, boolean unsure,
                             List<String> actions, String unsureReason) {
        public Classified(String category, List<String> also, List<String> points, boolean unsure) {
            this(category, also, points, unsure, List.of(), null);
        }
    }

    /** What the judge may propose the seller do — a proposal; the seller's own rule is applied by the program. */
    public static final java.util.Set<String> ACTIONS = java.util.Set.of("PUBLIC_REPLY", "CHECK_ORDER", "ASK_MORE_INFO",
            "PERSON_REVIEW", "NO_RESPONSE");

    /** Why the judge could not tell, when it says so — the seller's next step differs by reason. */
    public static final java.util.Set<String> UNSURE_REASONS = java.util.Set.of("AMBIGUOUS_TEXT", "PRODUCT_UNCLEAR");

    /** More points than this is not a list a reply can cover — the run hands it to a person, it never drops one. */
    public static final int MAX_POINTS = 12;

    /** The judge step, or empty when the model did not give a readable answer. */
    public Optional<Classified> judge(UUID orgId, String runId, String trigger, String reviewBody) {
        Optional<String> text = step(orgId, runId, trigger, "CLASSIFY", ReplyAgentPrompt.classifySystem(standard),
                ReplyAgentPrompt.classifyUser(reviewBody));
        if (text.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text.get());
        } catch (Exception e) {
            return Optional.empty();
        }
        // Every field is required (third outside review, 2026-10-28): a missing list is not an empty one — an answer
        // that left 「actions」 out has not said the seller owes nothing.
        if (root == null || !root.isObject() || !root.path("category").isTextual() || !root.path("also").isArray()
                || !root.path("points").isArray() || !root.path("actions").isArray() || !root.has("unsure")
                || !(root.path("unsure").isNull() || root.path("unsure").isTextual())) {
            return Optional.empty();
        }
        String code = root.path("category").asText().strip();
        boolean unsure = "UNSURE".equals(code);
        if (!unsure && standard.category(code).isEmpty()) {
            return Optional.empty();
        }
        List<String> also = new ArrayList<>();
        for (JsonNode a : root.path("also")) {
            String c = a.isTextual() ? a.asText().strip() : "";
            if (standard.category(c).isEmpty()) {
                return Optional.empty();
            }
            if (!c.equals(code) && !also.contains(c)) {
                also.add(c);
            }
        }
        List<String> points = new ArrayList<>();
        for (JsonNode p : root.path("points")) {
            String point = p.isTextual() ? p.asText().strip() : "";
            if (point.isEmpty() || point.length() > 80) {
                return Optional.empty();
            }
            points.add(point);
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode a : root.path("actions")) {
            String act = a.isTextual() ? a.asText().strip() : "";
            if (!ACTIONS.contains(act)) {
                return Optional.empty();
            }
            if (!actions.contains(act)) {
                actions.add(act);
            }
        }
        // 「Nothing to do」 beside something to do is not an answer; an unsure judge may propose nothing.
        if ((!unsure && actions.isEmpty()) || (actions.contains("NO_RESPONSE") && actions.size() > 1)) {
            return Optional.empty();
        }
        String reason = root.path("unsure").isTextual() ? root.path("unsure").asText().strip() : null;
        if (reason != null && !UNSURE_REASONS.contains(reason)) {
            return Optional.empty();
        }
        return Optional.of(new Classified(unsure ? null : code, List.copyOf(also), List.copyOf(points), unsure,
                List.copyOf(actions), unsure ? (reason == null ? "AMBIGUOUS_TEXT" : reason) : null));
    }

    /** The judge step's first category only — the shape callers used before the judge said more. */
    public Optional<String> classify(UUID orgId, String runId, String trigger, String reviewBody) {
        return judge(orgId, runId, trigger, reviewBody).map(Classified::category);
    }

    /** The draft step (and the revision, when a previous draft and its failed rules are given). */
    public Optional<String> draft(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                  String reviewBody, List<AgentDraftGenerator.Passage> knowledge, String voice,
                                  List<String> banned, String manner, String previousDraft, List<String> failedRules,
                                  String critiqueNote) {
        return draft(orgId, runId, trigger, category, reviewBody, List.of(), knowledge, voice, banned, manner,
                previousDraft, failedRules, critiqueNote);
    }

    public Optional<String> draft(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                  String reviewBody, List<String> points, List<AgentDraftGenerator.Passage> knowledge,
                                  String voice, List<String> banned, String manner, String previousDraft,
                                  List<String> failedRules, String critiqueNote) {
        return write(orgId, runId, trigger, category, reviewBody, points, knowledge, voice, banned, manner,
                previousDraft, failedRules, critiqueNote).map(DraftAnswer::comments).filter(c -> !c.isEmpty());
    }

    /**
     * What the drafter answered: the reply, or the points it could not answer from the seller's facts, or that the
     * facts conflict (second outside review, 2026-10-28: one registered passage is not an answer to the question — the
     * drafter, which reads both, says whether it is). An answer with neither a reply nor a reason is no answer.
     */
    public record DraftAnswer(String comments, List<String> unanswered, boolean conflict,
                              List<com.sellerops.review.draft.ReviewEvidenceBundle.Used> used) {
        public DraftAnswer {
            used = used == null ? List.of() : used;
        }

        public DraftAnswer(String comments, List<String> unanswered, boolean conflict) {
            this(comments, unanswered, conflict, List.of());
        }
    }

    public Optional<DraftAnswer> write(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                       String reviewBody, List<String> points, List<AgentDraftGenerator.Passage> knowledge,
                                       String voice, List<String> banned, String manner, String previousDraft,
                                       List<String> failedRules, String critiqueNote) {
        boolean revising = previousDraft != null && !previousDraft.isBlank();
        Optional<String> text = step(orgId, runId, trigger, revising ? "REVISE" : "DRAFT",
                ReplyAgentPrompt.draftSystem(standard, category),
                ReplyAgentPrompt.draftUser(reviewBody, points, knowledge, voice, banned, manner, previousDraft,
                        failedRules, critiqueNote));
        if (text.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text.get());
        } catch (Exception e) {
            return Optional.empty();
        }
        // All three fields, always (third outside review): a missing 「unanswered」 has not said every point is answered.
        if (root == null || !root.path("comments").isTextual() || !root.path("unanswered").isArray()
                || !root.path("conflict").isBoolean()) {
            return Optional.empty();
        }
        List<String> unanswered = new ArrayList<>();
        for (JsonNode u : root.path("unanswered")) {
            if (!u.isTextual() || u.asText().isBlank()) {
                return Optional.empty();
            }
            unanswered.add(u.asText().strip());
        }
        String comments = root.path("comments").asText().strip();
        boolean conflict = root.path("conflict").asBoolean(false);
        if (comments.isEmpty() && unanswered.isEmpty() && !conflict) {
            return Optional.empty();
        }
        // What the reply stands on (v12, 2026-11-01): the bundle's names and a sentence from each. Absent or malformed
        // entries cite nothing — and a reply that cites nothing has no ground for any fact or promise in it.
        List<com.sellerops.review.draft.ReviewEvidenceBundle.Used> used = new ArrayList<>();
        for (JsonNode u : root.path("used")) {
            if (u.isObject() && u.path("id").isTextual() && u.path("quote").isTextual() && used.size() < 12) {
                used.add(new com.sellerops.review.draft.ReviewEvidenceBundle.Used(u.path("id").asText(),
                        u.path("quote").asText()));
            }
        }
        return Optional.of(new DraftAnswer(comments, List.copyOf(unanswered), conflict, List.copyOf(used)));
    }

    /** One rule the critique says the draft broke, with how to fix it. */
    public record Failed(String id, String note) {
    }

    /**
     * The critique step: the rule ids the draft broke, or empty when the step failed. An answer this cannot read whole —
     * an entry that is not an object, has no id, or names a rule the standard does not have — is a failed step, not a
     * pass (review 2026-10-28): dropping the entries it did not understand turned 「문제가 있습니다」 into 「위반 없음」.
     */
    public Optional<List<Failed>> critique(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                           String reviewBody, List<AgentDraftGenerator.Passage> knowledge, String draft) {
        return critique(orgId, runId, trigger, category, reviewBody, List.of(), knowledge, draft);
    }

    public Optional<List<Failed>> critique(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                           String reviewBody, List<String> points,
                                           List<AgentDraftGenerator.Passage> knowledge, String draft) {
        return critique(orgId, runId, trigger, category, reviewBody, points, knowledge, draft, List.of());
    }

    /** {@code handedOver}: the questions the writer left to the seller — the reply does not answer them by design. */
    public Optional<List<Failed>> critique(UUID orgId, String runId, String trigger, ReplyStandard.Category category,
                                           String reviewBody, List<String> points,
                                           List<AgentDraftGenerator.Passage> knowledge, String draft,
                                           List<String> handedOver) {
        Optional<String> text = step(orgId, runId, trigger, "CRITIQUE", ReplyAgentPrompt.critiqueSystem(standard, category),
                ReplyAgentPrompt.critiqueUser(reviewBody, points, knowledge, draft, handedOver));
        if (text.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text.get());
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!root.path("failed").isArray()) {
            return Optional.empty();
        }
        List<Failed> failed = new ArrayList<>();
        for (JsonNode f : root.path("failed")) {
            String id = f.isObject() && f.path("id").isTextual() ? f.path("id").asText().strip() : "";
            if (!standard.knowsRule(id)) {
                log.info("reply_agent orgId={} step=CRITIQUE unreadable=true", orgId);
                return Optional.empty();
            }
            if (failed.stream().noneMatch(x -> x.id().equals(id))) {
                failed.add(new Failed(id, f.path("note").asText("").strip()));
            }
        }
        return Optional.of(List.copyOf(failed));
    }

    private Optional<String> step(UUID orgId, String runId, String trigger, String step, String system, String user) {
        if (!access.allows(properties, orgId)) {
            return Optional.empty();
        }
        AgentQuotaService.Charge charge = quota.consumeStep(orgId, runId, step, trigger, actorOf(trigger), callsAfter(step));
        if (!charge.decision().allowed()) {
            log.info("reply_agent orgId={} step={} called=false reason=quota", orgId, step);
            return Optional.empty();
        }
        long started = System.nanoTime();
        ReplyAgentGenerator.Result result = generator().call(system, user);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        quota.recordOutcome(charge.usageId(), elapsedMs, result.inputTokens(), result.outputTokens());
        log.info("reply_agent orgId={} step={} ok={} reason={} elapsedMs={}", orgId, step, result.text().isPresent(),
                result.reason(), elapsedMs);
        return result.text();
    }

    private static Optional<String> field(String json, String name) {
        try {
            JsonNode value = MAPPER.readTree(json).path(name);
            return value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private ReplyAgentGenerator generator() {
        return new ReplyAgentGenerator(transport, AgentLlmWireFormat.Vendor.of(properties.vendor()), properties.model(),
                properties.apiKey(), properties.maxOutputTokens(), properties.reasoningEffort(), standard.version());
    }
}
