package com.sellerops.agent.reply;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.auth.runtoken.ReplyAgentRun;
import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import com.sellerops.auth.runtoken.ReplyRunTokenService;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftComposer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * <b>The steps one reply-agent run takes, each on the run's own review</b> (2026-10-28,
 * {@code docs/standing/reply_agent_v1.md} §1). The runtime orders them; this service does them, and holds the run's
 * state between them — the category, the working draft, the rules it failed — so every model step's input is text this
 * backend wrote. A step answers with closed words and counts, never with the review or the draft.
 *
 * <p>v1 covers reviews. An inquiry run is refused until the inquiry steps exist; the inquiry lane keeps its own drafter.
 */
@Service
public class ReplyRunService {

    /** At most one revision: a draft that still fails after one is a person's to fix (§1). */
    static final int MAX_REVISIONS = 1;

    private final ReplyAgentRunRepository runs;
    private final ReplyRunTokenService tokens;
    private final ReviewRepository reviews;
    private final ReviewDraftComposer composer;
    private final ReviewReplyService replies;
    private final ReplyAgentService agent;
    private final com.sellerops.channel.ChannelRepository channels;

    public ReplyRunService(ReplyAgentRunRepository runs, ReplyRunTokenService tokens, ReviewRepository reviews,
                           ReviewDraftComposer composer, ReviewReplyService replies, ReplyAgentService agent,
                           com.sellerops.channel.ChannelRepository channels) {
        this.runs = runs;
        this.tokens = tokens;
        this.reviews = reviews;
        this.composer = composer;
        this.replies = replies;
        this.agent = agent;
        this.channels = channels;
    }

    /**
     * The longest reply the review's channel takes. NAVER's seller reply is 5–200 characters (research 2026-10-28,
     * {@code reply-references-platform-legal.md} R12); other channels publish no limit, so a generous one bounds them.
     */
    int maxLengthFor(Review review) {
        return com.sellerops.attention.reply.ChannelReplyLimit.maxFor(channelCode(review)).orElse(1000);
    }

    private String channelCode(Review review) {
        return review.getChannelId() == null || channels == null ? null
                : channels.findById(review.getChannelId()).map(com.sellerops.channel.Channel::getCode).orElse(null);
    }

    /** What the judge decided for the run; {@code reason} names a handoff (CATEGORY_S · UNSURE · QUESTION_NO_FACTS …). */
    public record Judged(String category, boolean needsResponse, boolean handoff, String reason, boolean skip) {
        public Judged(String category, boolean needsResponse, boolean handoff, String reason) {
            this(category, needsResponse, handoff, reason, false);
        }

        public Judged(String category, boolean needsResponse, boolean handoff) {
            this(category, needsResponse, handoff, handoff && category != null ? "CATEGORY_" + category : null);
        }
    }

    /** {@code reason} names a handoff the drafter found (KNOWLEDGE_MISSING · KNOWLEDGE_NOT_SHOWN · POLICY_CONFLICT). */
    public record Drafted(boolean written, int revisions, String reason) {
        public Drafted(boolean written, int revisions) {
            this(written, revisions, null);
        }
    }

    /** {@code pending}: the day's AI ceiling stopped the critique — the draft is held for review (검토 대기). */
    public record Critiqued(boolean passed, List<String> failed, boolean pending) {
        public Critiqued(boolean passed, List<String> failed) {
            this(passed, failed, false);
        }
    }

    /** The critique could not run for the day's ceiling: the working draft is kept, unchecked, for the next run. */
    static final String REVIEW_PENDING = "REVIEW_PENDING";

    public record Saved(boolean saved, Integer version, String reason) {
    }

    private ReplyAgentRun open(UUID orgId, UUID runId) {
        ReplyAgentRun run = runs.findById(runId).filter(r -> r.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("실행을 찾을 수 없어요."));
        if (run.getFinishedAt() != null) {
            throw ApiException.conflict("이미 끝난 실행이에요.");
        }
        if (!"REVIEW".equals(run.getSubjectKind())) {
            throw ApiException.conflict("문의 답글 에이전트는 아직 준비 중이에요.");
        }
        return run;
    }

    private Review review(ReplyAgentRun run) {
        return reviews.findByIdAndOrgId(run.getSubjectId(), run.getOrgId())
                .orElseThrow(() -> ApiException.notFound("리뷰를 찾을 수 없어요."));
    }

    /**
     * Judge: the categories the review carries, the points a reply must cover, the action the judge proposes — and what
     * that means for the run. Every review is read by the model, whatever its rating or words (second outside review,
     * 2026-10-28: a five-star can report an injury, a one-star can need nothing).
     *
     * <p>A person gets the review when: ANY category it carries is S · L · X; the word net found a harm or a legal word
     * (an auxiliary signal — it asks for a person's look even when the reading disagrees, and when the model did not
     * answer); the judge cannot tell, with its reason; the review raises more points than a reply can cover; the
     * question is about a product the review cannot be tied to; or it asks a question the seller has registered no fact
     * for at all (G2 — whether a fact that exists actually answers it is the drafter's to say).
     */
    public Judged classify(UUID orgId, UUID runId) {
        ReplyAgentRun run = open(orgId, runId);
        Review review = review(run);
        ReviewDraftComposer.AgentInputs in = inputs(orgId, run, review);
        // The review's one judgement — read when the review arrived, reused here while its words and the judge are the
        // same, read now otherwise (§3-4). The run does not keep a second copy of the decision rules.
        com.sellerops.review.judgement.ReviewJudgeService.Judged judgedNow =
                judges().judge(orgId, review, in, run.getTriggerKind(), runId.toString());
        ReplyAgentService.Classified read = judgedNow.reading().orElse(null);
        com.sellerops.review.judgement.ReviewJudgeService.Decision d = judgedNow.decision();
        run.setCategory(read == null ? null : read.category());
        run.setAlsoCategories(read == null || read.also().isEmpty() ? null : String.join(",", read.also()));
        run.setPoints(read == null || read.points().isEmpty() ? null : String.join("\n", read.points()));
        // A new judgement voids whatever passed under the old one: a draft that passed as R is not a draft for S.
        run.setJudgedHash(sha256(String.valueOf(in.redactedBody())));
        run.setPassedHash(null);
        // An automatic run in a band the seller drafts only when needed stops when the review needs nothing: the AI
        // proposes, the seller's rule decides (§3-4). A seller's own press always drafts.
        boolean automatic = !"SELLER".equals(run.getTriggerKind());
        boolean onlyWhenNeeded = rules != null && rules.modesFor(orgId).forRating(review.getRating())
                != com.sellerops.review.response.ReviewResponseMode.AHEAD;
        Judged judged = d.handoff() || d.category() == null
                ? new Judged(d.category(), d.needsResponse(), d.handoff(), d.reason())
                : automatic && onlyWhenNeeded && !d.needsResponse()
                        ? new Judged(d.category(), false, false, "NO_RESPONSE_NEEDED", true)
                        : new Judged(d.category(), d.needsResponse(), false, null);
        // The backend holds the handoff too: a run judged a person's is not drafted or saved by calling the steps.
        run.setHandoffReason(judged.handoff() ? judged.reason() : null);
        runs.save(run);
        return judged;
    }

    private com.sellerops.review.judgement.ReviewJudgeService judges() {
        return judgeService != null ? judgeService
                : new com.sellerops.review.judgement.ReviewJudgeService(agent, composer, judgements);
    }

    private com.sellerops.agent.quota.AgentQuotaService quota;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setQuota(com.sellerops.agent.quota.AgentQuotaService quota) {
        this.quota = quota;
    }

    private com.sellerops.review.judgement.ReviewJudgeService judgeService;
    private com.sellerops.review.response.ReviewResponseRuleService rules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJudgeService(com.sellerops.review.judgement.ReviewJudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRules(com.sellerops.review.response.ReviewResponseRuleService rules) {
        this.rules = rules;
    }

    private com.sellerops.review.judgement.ReviewJudgementRepository judgements;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJudgements(com.sellerops.review.judgement.ReviewJudgementRepository judgements) {
        this.judgements = judgements;
    }

    /** Draft, or — when {@code revise} — rewrite the working draft with the failed rules and notes. */
    public Drafted draft(UUID orgId, UUID runId, boolean revise) {
        ReplyAgentRun run = open(orgId, runId);
        ReplyStandard.Category category = writable(run, "판단 전에는 초안을 쓰지 않아요.");
        if (revise && failedOf(run).contains(REVIEW_PENDING)) {
            return new Drafted(false, run.getRevisions(), REVIEW_PENDING);
        }
        if (revise && (run.getRevisions() >= MAX_REVISIONS || failedOf(run).contains("CRITIQUE_UNAVAILABLE"))) {
            // A critique that never ran gives a revision nothing to fix: the draft goes to a person without a call.
            return new Drafted(false, run.getRevisions());
        }
        Review review = review(run);
        ReviewDraftComposer.AgentInputs in = inputs(orgId, run, review);
        requireJudgedOn(run, in);
        if (!revise) {
            // A draft held for review over these same inputs is checked, not written again (owner, 2026-10-29).
            String inputs = fingerprint(in);
            ReplyAgentRun held = runs.findFirstByOrgIdAndSubjectIdAndOutcomeOrderByFinishedAtDesc(orgId,
                    run.getSubjectId(), "PENDING").filter(h -> h.getWorkingDraft() != null)
                    .filter(h -> inputs.equals(h.getInputsHash()) && java.util.Objects.equals(h.getCategory(),
                            run.getCategory())).orElse(null);
            if (held != null) {
                run.setWorkingDraft(held.getWorkingDraft());
                run.setUsedPassages(held.getUsedPassages());
                run.setUsedRejected(held.getUsedRejected());
                run.setOpenQuestions(held.getOpenQuestions());
                run.setInputsHash(inputs);
                run.setPassedHash(null);
                run.setFailedRules(null);
                run.setCritiqueNote(null);
                runs.save(run);
                return new Drafted(true, run.getRevisions());
            }
        }
        ReviewDraftComposer.AgentInputs shown = offered(in, run);
        java.util.Optional<ReplyAgentService.DraftAnswer> answer = agent.write(orgId, runId.toString(),
                run.getTriggerKind(), category, in.redactedBody(), pointsOf(run), shown.passages(), in.voice(), in.banned(),
                in.manner(), revise ? run.getWorkingDraft() : null, revise ? failedOf(run) : List.of(),
                revise ? run.getCritiqueNote() : null);
        if (answer.isEmpty()) {
            return new Drafted(false, run.getRevisions());
        }
        // What the writer was not shown for want of room — kept whatever the writer answered, a handoff included (sixth
        // review: the 「자료 일부만 봄」 handoff returned before the counts were written).
        run.setBundlePoliciesLeft(shown.policiesLeftOut());
        run.setBundleProductLeft(shown.productLeftOut());
        boolean leftOut = shown.policiesLeftOut() + shown.productLeftOut() > 0;
        // A question the material does not answer, beside points it does (2026-11-01 eighth review): the reply keeps what
        // stands on its own and the question goes to the seller beside it — a partial draft, never a complete one. The
        // writer leaves the reply empty when the question is tangled with the rest (its answer changes the others, or a
        // safety or deal-deciding condition hangs on it); a conflict, or a bundle that left material out, keeps the whole
        // review a person's as before.
        boolean partial = !answer.get().unanswered().isEmpty() && !answer.get().conflict() && !leftOut
                && !answer.get().comments().isBlank();
        if (!partial && (!answer.get().unanswered().isEmpty() || answer.get().conflict())) {
            // The drafter read the facts against the points and found no answer, or two that disagree: the seller
            // decides the answer, and the backend keeps it a person's whatever is called next.
            // 「없다」 only when the writer saw everything: a bundle that left passages out for want of room has not
            // shown that the store has no answer (2026-11-01 fifth review).
            String reason = answer.get().conflict() ? "POLICY_CONFLICT"
                    : leftOut ? KNOWLEDGE_NOT_SHOWN : "KNOWLEDGE_MISSING";
            run.setHandoffReason(reason);
            judges().noteHandoff(orgId, review.getId(), reason);
            run.setWorkingDraft(null);
            run.setOpenQuestions(null);
            run.setPassedHash(null);
            runs.save(run);
            return new Drafted(false, run.getRevisions(), reason);
        }
        java.util.Optional<String> text = java.util.Optional.of(answer.get().comments());
        run.setWorkingDraft(text.get());
        run.setOpenQuestions(partial ? openQuestionsOf(answer.get().unanswered()) : null);
        // What the draft stands on: the bundle passages it cited, as quoted (2026-11-01 third review).
        com.sellerops.review.draft.ReviewEvidenceBundle.Chosen chosen =
                com.sellerops.review.draft.ReviewEvidenceBundle.chosen(shown.bundle(), answer.get().used());
        run.setUsedPassages(chosen.items().isEmpty() ? null : String.join(",", chosen.ids()));
        run.setUsedRejected(chosen.rejected());
        if (revise) {
            run.setRevisions(run.getRevisions() + 1);
        }
        run.setInputsHash(fingerprint(in));
        run.setPassedHash(null);
        run.setFailedRules(null);
        run.setCritiqueNote(null);
        runs.save(run);
        return new Drafted(true, run.getRevisions());
    }

    /** Critique: the model's reading against the standard, plus the press's own deterministic checks. */
    public Critiqued critique(UUID orgId, UUID runId) {
        ReplyAgentRun run = open(orgId, runId);
        ReplyStandard.Category category = writable(run, "판단 전에는 검토하지 않아요.");
        if (run.getWorkingDraft() == null) {
            throw ApiException.conflict("검토할 초안이 없어요.");
        }
        Review review = review(run);
        ReviewDraftComposer.AgentInputs in = inputs(orgId, run, review);
        requireJudgedOn(run, in);
        // Read against what the draft cites, not the whole bundle: a promise is backed by a passage it names or by none.
        ReviewDraftComposer.AgentInputs ground = groundOf(in, run);
        List<ReviewDraftComposer.AgentCheck> checks =
                new ArrayList<>(composer.agentFindings(orgId, run.getWorkingDraft(), ground, maxLengthFor(review)));
        // Whatever it cited, a draft another document in use contradicts is not saved as written (2026-11-01 fourth review).
        for (com.sellerops.review.draft.ReviewDraftRecheck.Against a
                : composer.againstRules(orgId, in.productId(), run.getWorkingDraft(), ground)) {
            checks.add(new ReviewDraftComposer.AgentCheck("N9", "등록된 「" + a.title() + "」은 「" + a.word()
                    + "」을(를) 다르게 말해요. " + (a.clash() ? "인용한 근거와 정책끼리 서로 다르니 conflict를 true로 쓰세요."
                    : "그 말을 정책대로 고치거나 빼세요.")));
        }
        if (run.getUsedRejected() > 0) {
            checks.add(new ReviewDraftComposer.AgentCheck(CITATION_UNVERIFIED, "used에 적은 근거 중 "
                    + run.getUsedRejected() + "개는 이름이 없거나 그 근거에 없는 문장이에요. 근거의 이름과 문장을 그대로 "
                    + "옮기고, 근거에 없는 내용은 답글에서 빼세요."));
        }
        List<String> failed = new ArrayList<>(checks.stream().map(ReviewDraftComposer.AgentCheck::id).toList());
        StringBuilder notes = new StringBuilder();
        for (ReviewDraftComposer.AgentCheck check : checks) {
            notes.append(check.id()).append(": ").append(check.note()).append('\n');
        }
        if (complaint(run) && BRIGHT_CLOSING.matcher(run.getWorkingDraft()).find()) {
            checks.add(new ReviewDraftComposer.AgentCheck("F6",
                    "불만·피해를 말하는 리뷰예요. 밝은 인사(「좋은 하루 보내세요」 등)나 웃음 표시를 빼고 차분하게 맺으세요."));
            failed.add("F6");
            notes.append("F6: 불만·피해 리뷰에는 밝은 인사로 끝내지 않아요.\n");
        }
        String inputs = fingerprint(in);
        if (!inputs.equals(run.getInputsHash())) {
            // The passages or the company's wording changed after the draft was written: the critique would check a
            // draft against evidence it was not written over, and the save would cite what the writer never saw.
            failed.add(EVIDENCE_CHANGED);
            notes.append(EVIDENCE_CHANGED).append(": 초안을 쓴 뒤 근거가 바뀌었어요. 지금 근거로 다시 쓰세요.\n");
        }
        com.sellerops.agent.quota.AgentUsageActor actor = ReplyAgentService.actorOf(run.getTriggerKind());
        java.util.Optional<List<ReplyAgentService.Failed>> model = quota != null && !quota.hasRoom(orgId, actor)
                ? java.util.Optional.empty()
                : agent.critique(orgId, runId.toString(), run.getTriggerKind(), category, in.redactedBody(),
                        pointsOf(run), ground.passages(), run.getWorkingDraft(), openQuestionsOf(run));
        if (model.isEmpty() && quota != null && !quota.hasRoom(orgId, actor)) {
            // The ceiling, not a bad answer: the draft waits for review instead of reaching a person unchecked or being
            // thrown away (owner, 2026-10-29). The next run over the same inputs checks this text.
            run.setFailedRules(REVIEW_PENDING);
            run.setPassedHash(null);
            runs.save(run);
            return new Critiqued(false, List.of(REVIEW_PENDING), true);
        }
        if (model.isEmpty()) {
            // A critique that could not run is not a pass: the draft goes to a person.
            failed.add("CRITIQUE_UNAVAILABLE");
        } else {
            for (ReplyAgentService.Failed f : model.get()) {
                if (!failed.contains(f.id())) {
                    failed.add(f.id());
                }
                if (!f.note().isBlank()) {
                    notes.append(f.id()).append(": ").append(f.note()).append('\n');
                }
            }
        }
        run.setPassedHash(failed.isEmpty() ? passedFingerprint(run, inputs) : null);
        run.setFailedRules(failed.isEmpty() ? null : String.join(",", failed));
        run.setCritiqueNote(notes.isEmpty() ? null : notes.toString().strip());
        if (run.getRevisions() == 0) {
            run.setFirstFailedRules(failed.isEmpty() ? "" : String.join(",", failed));
        }
        if (!checks.isEmpty()) {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (run.getChecksFailed() != null && !run.getChecksFailed().isBlank()) {
                all.addAll(Arrays.asList(run.getChecksFailed().split(",")));
            }
            checks.forEach(c -> all.add(c.id()));
            run.setChecksFailed(String.join(",", all));
        }
        // After the one revision, a draft whose findings are all about form — length, a repeated sentence, a closing — is
        // the seller's to polish, not a judgement to hand over (2026-11-01 sixth review: 「판단 필요」 ≠ 「문장 다듬기 필요」).
        // A channel's length limit is kept to fix too, but it is a constraint, not a style: the run says so, the note says
        // so, and approval and registration refuse the text until it fits (seventh review).
        boolean polish = !failed.isEmpty() && run.getRevisions() >= MAX_REVISIONS
                && failed.stream().allMatch(f -> isForm(f)
                        || com.sellerops.attention.reply.ChannelReplyLimit.CHANNEL_LENGTH_EXCEEDED.equals(f));
        run.setPolishRules(polish ? String.join(",", failed) : null);
        if (polish) {
            run.setPassedHash(passedFingerprint(run, inputs));
            run.setFailedRules(null);
            runs.save(run);
            return new Critiqued(true, List.of());
        }
        runs.save(run);
        return new Critiqued(failed.isEmpty(), List.copyOf(failed));
    }

    /**
     * Save the working draft as a draft version — only the draft whose last critique passed, over the very inputs that
     * critique checked (review 2026-10-28). The graph never saves a failed draft, and this does not trust it to: a draft
     * no critique passed is NOT_REVIEWED, and one whose passages or wording changed since is EVIDENCE_CHANGED, so the
     * citations stored with a version are the passages the writer and the critique both saw. A run no seller started is
     * saved as the prepared-ahead author, and only while every gate on drafting ahead still holds; a seller's run is
     * saved as that seller.
     */
    public Saved save(UUID orgId, UUID runId) {
        ReplyAgentRun run = open(orgId, runId);
        ReplyStandard.Category category = agent.standard().category(run.getCategory()).orElse(null);
        if (category != null && category.handoff() || run.getHandoffReason() != null) {
            // S · L · X are a person's whatever was called before: the backend refuses, not only the graph's order.
            return new Saved(false, null, "HANDOFF_CATEGORY");
        }
        if (run.getWorkingDraft() == null) {
            return new Saved(false, null, "NO_DRAFT");
        }
        if (category == null) {
            return new Saved(false, null, "NOT_REVIEWED");
        }
        if (run.getPassedHash() == null) {
            return new Saved(false, null, "NOT_REVIEWED");
        }
        Review review = review(run);
        ReviewDraftComposer.AgentInputs in = inputs(orgId, run, review);
        if (!run.getPassedHash().equals(passedFingerprint(run, fingerprint(in)))) {
            return new Saved(false, null, EVIDENCE_CHANGED);
        }
        boolean seller = "SELLER".equals(run.getTriggerKind()) && run.getStartedBy() != null;
        if (seller ? !replies.maySellerWrite(orgId, review) : !replies.mayWriteAhead(orgId, review)) {
            return new Saved(false, null, "GATE_CLOSED");
        }
        String actor = seller ? "SELLER:" + run.getStartedBy() : ReviewReplyService.PREPARED_AHEAD_ACTOR;
        int version = composer.saveAgentDraft(orgId, review, run.getWorkingDraft(), groundOf(in, run), actor,
                agent.version()).version();
        if (draftRows != null) {
            // This run judged the whole review again, so its answer replaces what the version before carried — none
            // left over clears them (the draft save keeps the previous version's otherwise).
            draftRows.findByReviewIdAndVersion(review.getId(), version)
                    .filter(row -> !java.util.Objects.equals(row.getOpenQuestions(), run.getOpenQuestions()))
                    .ifPresent(row -> {
                        row.setOpenQuestions(run.getOpenQuestions());
                        draftRows.save(row);
                    });
        }
        if (openQuestions != null) {
            // The same judgement, as the seller's one-by-one list (tenth review). A review reply is not held for them —
            // approving stays the seller's — but each is closed with a reason, and the history stays.
            openQuestions.replace(orgId, com.sellerops.openquestion.OpenQuestionService.REVIEW, review.getId(),
                    openQuestionsOf(run), false, "reply-run:" + run.getId(), actor);
        }
        if (run.getPolishRules() != null && draftRows != null) {
            String note = run.getPolishRules().contains(
                    com.sellerops.attention.reply.ChannelReplyLimit.CHANNEL_LENGTH_EXCEEDED)
                    ? com.sellerops.attention.reply.ChannelReplyLimit.refusal(channelCode(review), run.getWorkingDraft())
                            .orElse(polishNoteOf(run.getCritiqueNote()))
                    : polishNoteOf(run.getCritiqueNote());
            draftRows.findByReviewIdAndVersion(review.getId(), version).ifPresent(row -> {
                row.setPolishNote(note);
                draftRows.save(row);
            });
        }
        run.setDraftVersion(version);
        runs.save(run);
        return new Saved(true, version, null);
    }

    /** The review's inputs, with the search helpers' calls charged to whoever started the run (2026-10-31). */
    private ReviewDraftComposer.AgentInputs inputs(UUID orgId, ReplyAgentRun run, Review review) {
        return com.sellerops.agent.quota.AgentUsageScope.as(ReplyAgentService.actorOf(run.getTriggerKind()),
                () -> composer.agentInputs(orgId, review, pointsOf(run)));
    }

    /** End the run: its token stops working. {@code reason} is a closed word (CATEGORY_S · STILL_FAILS · …). */
    public void finish(UUID orgId, UUID runId, String outcome, String reason) {
        ReplyAgentRun run = runs.findById(runId).filter(r -> r.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("실행을 찾을 수 없어요."));
        if (reason != null && reason.matches("[A-Z_]{1,40}")) {
            run.setHandoffReason(reason);
            runs.save(run);
        }
        String closed = outcome != null && outcome.matches("SAVED|HANDOFF|FAILED|SKIPPED|PENDING") ? outcome : "FAILED";
        tokens.finish(runId, closed);
    }

    /** A cheerful sign-off: 「좋은 하루 보내세요」, 「즐거운 쇼핑 되세요」, a smile (F6). */
    static final java.util.regex.Pattern BRIGHT_CLOSING = java.util.regex.Pattern.compile(
            "(좋은|즐거운|행복한|기분\\s*좋은|편안한|행복 가득한)\\s*(하루|쇼핑|시간|주말|저녁|한\\s*주)|[😊😄😀🙂😁]|\\^\\^");

    /** The run's review complains or reports harm, in any category it carries (R · D · M · V · S · L). */
    private static boolean complaint(ReplyAgentRun run) {
        java.util.Set<String> carried = new java.util.HashSet<>();
        if (run.getCategory() != null) {
            carried.add(run.getCategory());
        }
        if (run.getAlsoCategories() != null) {
            carried.addAll(Arrays.asList(run.getAlsoCategories().split(",")));
        }
        return carried.stream().anyMatch(c -> java.util.Set.of("R", "D", "M", "V", "S", "L").contains(c));
    }

    /** The inputs moved under the draft — a critique failure, and a save refusal. */
    static final String EVIDENCE_CHANGED = "EVIDENCE_CHANGED";

    /** The writer found no answer in a bundle that had to leave in-use passages out: not 「the store has none」. */
    static final String KNOWLEDGE_NOT_SHOWN = "KNOWLEDGE_NOT_SHOWN";

    /** A rule about the reply's form (F1 · F2 · …), never what it says: length, a repeated sentence, a closing. */
    static boolean isForm(String rule) {
        return rule != null && rule.matches("F\\d+");
    }

    /** The seller's note for a draft saved to polish: the critique's form notes, cut to the column. */
    static String polishNoteOf(String critiqueNote) {
        String note = critiqueNote == null || critiqueNote.isBlank() ? "길이나 되풀이되는 문장을 다듬어 주세요."
                : critiqueNote.replaceAll("(?m)^F\\d+:\\s*", "").replaceAll("\\s*\\n\\s*", " ").strip();
        return note.length() <= 300 ? note : note.substring(0, 300);
    }

    private com.sellerops.attention.reply.ReviewReplyDraftRepository draftRows;

    private com.sellerops.openquestion.OpenQuestionService openQuestions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOpenQuestions(com.sellerops.openquestion.OpenQuestionService openQuestions) {
        this.openQuestions = openQuestions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDraftRows(com.sellerops.attention.reply.ReviewReplyDraftRepository draftRows) {
        this.draftRows = draftRows;
    }

    /** The writer cited a passage the bundle does not have, or a sentence that passage does not say. */
    static final String CITATION_UNVERIFIED = "CITATION_UNVERIFIED";

    /**
     * What the writer is offered: the whole bundle — except for a review judged praise only or before use only (P · U,
     * nothing else carried), which gets no store facts (2026-11-01: a ★5 「잘 붙어요」 was answered with a fixing tip; the
     * fifth round moved that call from a word list to the judgement).
     */
    static ReviewDraftComposer.AgentInputs offered(ReviewDraftComposer.AgentInputs in, ReplyAgentRun run) {
        boolean factsUnneeded = ("P".equals(run.getCategory()) || "U".equals(run.getCategory()))
                && (run.getAlsoCategories() == null || run.getAlsoCategories().isBlank());
        return factsUnneeded && !in.bundle().isEmpty() ? in.withoutBundle() : in;
    }

    /**
     * The inputs a step after the writer reads: narrowed to the bundle passages the working draft cites. Where no bundle
     * was built (a context without its sources), the inputs as they are.
     */
    static ReviewDraftComposer.AgentInputs groundOf(ReviewDraftComposer.AgentInputs in, ReplyAgentRun run) {
        if (in.bundle().isEmpty()) {
            return in;
        }
        return in.chosen(run.getUsedPassages() == null ? List.of() : Arrays.asList(run.getUsedPassages().split(",")));
    }

    /**
     * What a draft is written over, as one hash: the redacted review, every passage the model is shown and every one a
     * version would cite, the company's wording, manner and bans. Any change among them is a different draft's inputs.
     */
    static String fingerprint(ReviewDraftComposer.AgentInputs in) {
        StringBuilder sb = new StringBuilder();
        java.util.function.Consumer<Object> part = v -> sb.append(v == null ? "\u0000" : v.toString()).append('\u0001');
        part.accept(in.redactedBody());
        for (AgentDraftGenerator.Passage p : in.passages() == null ? List.<AgentDraftGenerator.Passage>of() : in.passages()) {
            part.accept(p.scopeLabel());
            part.accept(p.heading());
            part.accept(p.text());
        }
        sb.append('\u0002');
        for (ScopedPassage p : in.cited() == null ? List.<ScopedPassage>of() : in.cited()) {
            part.accept(p.sourceId());
            part.accept(p.chunkId());
            part.accept(p.text());
        }
        sb.append('\u0002');
        part.accept(in.voice());
        part.accept(in.manner());
        part.accept(in.banned());
        part.accept(in.productId());
        part.accept(in.knowledgeState());
        return sha256(sb.toString());
    }

    /** The passed draft bound to the inputs it was checked over, the category it was judged as, and the standard. */
    private String passedFingerprint(ReplyAgentRun run, String inputs) {
        return sha256(inputs + '\u0003' + run.getCategory() + '\u0003' + agent.standard().version() + '\u0003'
                + run.getWorkingDraft() + '\u0003' + run.getUsedPassages() + '\u0003' + run.getOpenQuestions());
    }

    /**
     * The run's category, when it is one an agent may write for. S · L · X — a person's — are refused here, whatever
     * order the steps are called in (review 2026-10-28, second pass: a run judged S could still be drafted, critiqued
     * and saved by calling the steps directly).
     */
    private ReplyStandard.Category writable(ReplyAgentRun run, String unjudged) {
        ReplyStandard.Category category = agent.standard().category(run.getCategory())
                .orElseThrow(() -> ApiException.conflict(unjudged));
        if (category.handoff() || run.getHandoffReason() != null) {
            throw ApiException.conflict("사람에게 넘길 리뷰예요. 초안을 쓰지 않아요.");
        }
        return category;
    }

    /** The review read now is the one the category was judged on; otherwise the run must judge again. */
    private static void requireJudgedOn(ReplyAgentRun run, ReviewDraftComposer.AgentInputs in) {
        if (!sha256(String.valueOf(in.redactedBody())).equals(run.getJudgedHash())) {
            throw ApiException.conflict("판단한 뒤 리뷰가 바뀌었어요. 다시 판단해야 해요.");
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> pointsOf(ReplyAgentRun run) {
        return run.getPoints() == null || run.getPoints().isBlank() ? List.of()
                : Arrays.asList(run.getPoints().split("\n"));
    }

    /** The writer's unanswered questions as the run keeps them: one per line, cut to the column (V170). */
    static String openQuestionsOf(List<String> unanswered) {
        StringBuilder sb = new StringBuilder();
        for (String q : unanswered) {
            String line = q.replaceAll("\\s+", " ").strip();
            if (line.isEmpty() || sb.length() + line.length() + 1 > 500) {
                continue;
            }
            sb.append(sb.isEmpty() ? "" : "\n").append(line);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    static List<String> openQuestionsOf(ReplyAgentRun run) {
        return run.getOpenQuestions() == null || run.getOpenQuestions().isBlank() ? List.of()
                : Arrays.asList(run.getOpenQuestions().split("\n"));
    }

    private static List<String> failedOf(ReplyAgentRun run) {
        return run.getFailedRules() == null ? List.of() : Arrays.asList(run.getFailedRules().split(","));
    }
}
