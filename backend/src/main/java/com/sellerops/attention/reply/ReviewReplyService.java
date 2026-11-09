package com.sellerops.attention.reply;

import com.sellerops.attention.VocItemRef;
import com.sellerops.attention.reply.dto.ReviewReplyApprovalResponse;
import com.sellerops.attention.reply.dto.ReviewReplyApprovalView;
import com.sellerops.attention.reply.dto.ReviewReplyCapabilities;
import com.sellerops.attention.reply.dto.ReviewReplyDraftView;
import com.sellerops.attention.reply.dto.ReviewReplyOutcomeResponse;
import com.sellerops.attention.reply.dto.ReviewReplyOutcomeView;
import com.sellerops.attention.reply.dto.ReviewReplyPhraseFindingView;
import com.sellerops.attention.reply.dto.ReviewReplyPrepView;
import com.sellerops.attention.reply.dto.ReviewReplySubmissionRunResponse;
import com.sellerops.attention.reply.dto.ReviewReplySuggestionView;
import com.sellerops.attention.reply.dto.ReviewReplyTargetHintView;
import com.sellerops.attention.triage.ReviewTriage;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.attention.triage.TriageDisposition;
import com.sellerops.common.ApiException;
import com.sellerops.replyhold.ReplyHoldService;
import com.sellerops.review.draft.ReviewDraftComposer;
import com.sellerops.review.draft.dto.GeneratedReviewDraftView;
import com.sellerops.common.RedactedBody;
import com.sellerops.common.ReviewBodyFingerprint;
import com.sellerops.common.ReviewIdFingerprint;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.identity.ExecutableIdentity;
import com.sellerops.identity.ExecutableIdentityResolver;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.review.publish.ReviewExecutionCapability;
import com.sellerops.review.triage.ReviewTriageChannelCapability;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Review response preparation: read the review, start from a suggested reply, edit it, approve
 * it, copy it. The operator pastes it into the seller center themselves.
 *
 * <p><b>What this is not.</b> There is no marketplace write path behind any of it — no
 * adapter, no action intent, no dispatcher, no verification. The reply leaves SellerOps
 * through the clipboard and nowhere else. Approving freezes text; it does not send it.
 *
 * <p><b>{@code RESPONSE_NEEDED} still promises nothing.</b> It gates whether preparation is
 * OFFERED; it never causes it. Nothing here runs when a disposition is recorded — an operator
 * opens this surface explicitly, or it never opens. {@code TriageDisposition}'s "recording
 * RESPONSE_NEEDED does not draft, queue, send, or promise a reply" is still literally true, and
 * keeping it true is a requirement rather than a coincidence.
 *
 * <p><b>This class owns authorization, the gate, and the capability rule</b>, and delegates
 * persistence to {@link ReviewReplyDraftService} and {@link ReviewReplyApprovalService}. Those
 * two assume both checks have run; nothing else is wired to them. Stating the rule once is what
 * keeps the read, the save, and the approval from drifting into three different opinions about
 * what is allowed.
 *
 * <p><b>Authorization is re-derived from the principal on every call</b>, exactly as
 * {@code ReviewTriageService} does, and the ref is never trusted for any of it: org from the
 * JWT, account must exist in that org, review must exist in that org, review's channel must be
 * the account's channel. Steps 2–4 all fail as 404 — non-disclosure, so a caller cannot tell
 * "no such review" from "someone else's" from "yours, wrong account".
 *
 * <p>Carries no {@code @Transactional}, and must not gain one — see
 * {@link ReviewReplyApprovalWriter}.
 */
@Service
public class ReviewReplyService {

    /** Actor-tag prefix; {@code SELLER:} for the same reason {@code ReviewTriageService} uses it. */
    static final String ACTOR_PREFIX = "SELLER:";

    /** KST — the platform zone every operator-facing date in this product uses. */
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    private final ReviewRepository reviews;
    private final ProductRepository products;
    private final SellerAccountRepository sellerAccounts;
    private final ReviewTriageRepository triages;
    private final ReviewReplyDraftService drafts;
    private final ReviewReplyApprovalService approvals;
    private final ReviewReplyOutcomeService outcomes;
    private final ReviewReplyProposalProvider provider;
    private final Clock clock;
    /** Resolves the executable identity recorded on a guided run's intent at mint (Acceptance Closure). */
    private final ExecutableIdentityResolver identity;
    /** Names the review's channel so the reply-flow gate can refuse a channel with no reply flow at all. */
    private final ChannelRepository channels;

    /** How long a minted guided run may wait for the Local Agent to spend it. */
    static final java.time.Duration SUBMISSION_REF_TTL = java.time.Duration.ofMinutes(15);
    static final String GUIDED_EXECUTION_MODE = "GUIDED_BROWSER_EXECUTION";

    @Autowired
    public ReviewReplyService(ReviewRepository reviews, ProductRepository products,
                              SellerAccountRepository sellerAccounts,
                              ReviewTriageRepository triages, ReviewReplyDraftService drafts,
                              ReviewReplyApprovalService approvals,
                              ReviewReplyOutcomeService outcomes,
                              ReviewReplyProposalProvider provider,
                              ExecutableIdentityResolver identity, ChannelRepository channels,
                              ReviewDraftComposer composer,
                              com.sellerops.review.memory.ReviewAnswerMemoryHook answerMemory) {
        this(reviews, products, sellerAccounts, triages, drafts, approvals, outcomes, provider,
                Clock.systemUTC(), identity, channels);
        this.composer = composer;
        this.answerMemory = answerMemory;
    }

    /** Test seam without a resolver: every minted intent records {@code NONE}, which the target route refuses. */
    ReviewReplyService(ReviewRepository reviews, ProductRepository products,
                       SellerAccountRepository sellerAccounts,
                       ReviewTriageRepository triages, ReviewReplyDraftService drafts,
                       ReviewReplyApprovalService approvals,
                       ReviewReplyOutcomeService outcomes,
                       ReviewReplyProposalProvider provider, Clock clock) {
        this(reviews, products, sellerAccounts, triages, drafts, approvals, outcomes, provider, clock,
                ExecutableIdentityResolver.unresolved(), null);
    }

    /** Test seam: an explicit {@link Clock} pins the KST as-of date used for the recency bucket. */
    ReviewReplyService(ReviewRepository reviews, ProductRepository products,
                       SellerAccountRepository sellerAccounts,
                       ReviewTriageRepository triages, ReviewReplyDraftService drafts,
                       ReviewReplyApprovalService approvals,
                       ReviewReplyOutcomeService outcomes,
                       ReviewReplyProposalProvider provider, Clock clock,
                       ExecutableIdentityResolver identity, ChannelRepository channels) {
        this.identity = identity;
        this.channels = channels;
        this.reviews = reviews;
        this.products = products;
        this.sellerAccounts = sellerAccounts;
        this.triages = triages;
        this.drafts = drafts;
        this.approvals = approvals;
        this.outcomes = outcomes;
        this.provider = provider;
        this.clock = clock;
    }

    /**
     * The grounded-drafting composer, or null in a context that has none.
     *
     * <p>Set by the Spring constructor only. Every test seam below leaves it null, and
     * {@link #generateDraft} answers 409 rather than throwing — a context with no composer is a
     * deployment fact, not a caller error, and the review screen's other twelve operations are
     * unaffected by it.
     */
    private ReviewDraftComposer composer;

    /**
     * Answer Memory's review-lane hook, or null in a context that has none.
     *
     * <p>Set by the Spring constructor only, exactly like {@link #composer}, and for the same reason: every test
     * seam below leaves it null and the approval must still work. Remembering an approval is not part of
     * approving it — {@link #decideApproval} null-checks and moves on, which is also what makes the hook's own
     * best-effort contract honest rather than merely documented.
     */
    private com.sellerops.review.memory.ReviewAnswerMemoryHook answerMemory;

    /** The company's forbidden phrases as review replies obey them; null in a bare test. */
    private com.sellerops.review.draft.ReviewStyleGuard styleGuard;

    @Autowired(required = false)
    public void setStyleGuard(com.sellerops.review.draft.ReviewStyleGuard styleGuard) {
        this.styleGuard = styleGuard;
    }

    /**
     * <b>A reply that carries a phrase the company never uses is not approved</b> (owner decision 2026-10-23). The
     * check reads the exact text being approved — whoever wrote it, the model or the seller — and nothing else: the
     * customer's words and the evidence may carry the phrase without stopping the work.
     */
    private void requireNoForbiddenPhrase(UUID orgId, String text) {
        if (styleGuard == null) {
            return;
        }
        java.util.List<String> found = styleGuard.forbiddenIn(text, orgId);
        if (!found.isEmpty()) {
            throw ApiException.conflict("회사가 쓰지 않기로 한 표현이 있어 승인할 수 없어요: "
                    + String.join(" · ", found.stream().map(p -> "「" + p + "」").toList()) + ". 문장을 고친 뒤 승인하세요.");
        }
    }

    /**
     * The web recheck ({@link ReplyHoldService}, 2026-11-01), looked up when used: it reads this service's own lane, so a
     * constructor reference would be a cycle. Null in a context that wires this service by hand.
     */
    private org.springframework.beans.factory.ObjectProvider<ReplyHoldService> holds;

    @Autowired(required = false)
    public void setReplyHolds(org.springframework.beans.factory.ObjectProvider<ReplyHoldService> holds) {
        this.holds = holds;
    }

    /**
     * <b>An approved reply that no longer stands is not registered</b> (2026-11-01; approved-reply hold, 2026-10-23). A
     * changed citation alone does not hold it; any other finding does, and the reply is never rewritten: the seller
     * fixes it and approves again, or confirms past what may be confirmed.
     */
    void requireStillStands(UUID orgId, Review review, ReviewReplyApproval approval) {
        if (approval.getApprovedVersion() != null) {
            drafts.version(review.getId(), approval.getApprovedVersion())
                    .ifPresent(v -> requireWithinChannelLimit(review, v.getBody()));
        }
        ReplyHoldService holdService = holds == null ? null : holds.getIfAvailable();
        if (holdService != null) {
            holdService.registrationBlock(orgId, review, approval).ifPresent(reason -> {
                throw ApiException.conflict(reason);
            });
        }
    }

    /** Refuses a text longer than the review's channel takes ({@link ChannelReplyLimit}). */
    void requireWithinChannelLimit(Review review, String body) {
        String code = review.getChannelId() == null ? null
                : channels.findById(review.getChannelId()).map(c -> c.getCode()).orElse(null);
        ChannelReplyLimit.refusal(code, body).ifPresent(reason -> {
            throw ApiException.conflict(reason);
        });
    }

    /** The vendor's delegate lane, or null in a context that has none ({@link DelegatedReplyLane}). */
    private DelegatedReplyLane delegated;

    @Autowired(required = false)
    public void setDelegatedReplyLane(DelegatedReplyLane delegated) {
        this.delegated = delegated;
    }

    /** Records 「대응 필요」 when the seller approves a draft written ahead; null where no writer is wired. */
    private com.sellerops.attention.triage.ReviewTriageWriter triageWriter;

    @Autowired(required = false)
    public void setTriageWriter(com.sellerops.attention.triage.ReviewTriageWriter triageWriter) {
        this.triageWriter = triageWriter;
    }

    /** The seller's set-asides, so a draft is never prepared ahead for a review they put away; null in a test seam. */
    private ReviewReplyWorkDismissalRepository dismissals;

    /** The reply agent, when this deployment has one — the seller's press runs it (owner, 2026-10-28). */
    private com.sellerops.agent.reply.ReplyRunStarter agentRuns;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    public void setAgentRuns(com.sellerops.agent.reply.ReplyRunStarter agentRuns) {
        this.agentRuns = agentRuns;
    }

    @Autowired(required = false)
    public void setDismissals(ReviewReplyWorkDismissalRepository dismissals) {
        this.dismissals = dismissals;
    }

    /** The author of a draft written ahead of the seller (2026-10-28) — the responsibility's own actor. */
    public static final String PREPARED_AHEAD_ACTOR = "SYSTEM:RESPONSIBILITY";

    /**
     * <b>Write a review's first draft before the seller opens it</b> (product-owner, 2026-10-28: 「리뷰에는 응대가 필요한
     * 경우 초안을 두기로」) — through the same composer the seller's press runs, and refused by every gate that press
     * keeps except one.
     *
     * <p>The one it does not keep is 「대응 필요」. That record is a person's judgement and stays one: a draft written
     * here is only a draft, and the seller's approval of it is what records 「대응 필요」, as the seller's own act
     * ({@link #decideApproval}). A review the seller has already judged otherwise — 지켜보기, 할 일 없음 — or set
     * aside is never drafted. Neither is one whose channel shows a reply, one with any draft already (a version the
     * seller wrote is never stacked on), one with an approval, one a delegate holds, or one on a channel where a seller
     * cannot reply.
     *
     * @return the draft written, or empty when a gate said no (never throws for a gate)
     */
    public Optional<GeneratedReviewDraftView> prepareAhead(UUID orgId, Review review) {
        UUID reviewId = review.getId();
        // The single-call drafter: never on an install, where only the reply agent writes review replies.
        if (composer == null || (agentRuns != null && agentRuns.required()) || !mayWriteAhead(orgId, review)) {
            return Optional.empty();
        }
        RedactedBody body = VocPreviewSanitizer.redactFullBody(review.getBody());
        GeneratedReviewDraftView generated = composer.compose(orgId, review, body.text(), PREPARED_AHEAD_ACTOR);
        draftSaved(orgId, reviewId);
        return Optional.of(generated);
    }

    /**
     * Every gate {@link #prepareAhead} keeps, as a question — also asked by the reply agent just before it saves a draft
     * no seller asked for, so a draft written in the meantime is never stacked on.
     */
    public boolean mayWriteAhead(UUID orgId, Review review) {
        UUID reviewId = review.getId();
        if (review.getReplyState() == ReviewReplyState.ANSWERED) {
            return false;
        }
        TriageDisposition decided = disposition(orgId, reviewId).orElse(null);
        if (decided != null && decided != TriageDisposition.RESPONSE_NEEDED) {
            return false;
        }
        if (drafts.currentVersion(reviewId) > 0 || approvals.current(orgId, reviewId).isPresent()
                || delegatedOpen(orgId, reviewId)
                || (dismissals != null && dismissals.existsByOrgIdAndReviewId(orgId, reviewId))) {
            return false;
        }
        try {
            requireReplyFlow(review.getChannelId());
        } catch (ApiException noReplyFlow) {
            return false;
        }
        return true;
    }

    /**
     * Whether a review the agent handed off at {@code handedOffAt} is still waiting for a person. It stops waiting when
     * someone acted on it after the handoff — a reply on the channel, an approval, a draft a person wrote or edited, a
     * delegate holding it, the work dismissed, or a decision that it needs no reply. A draft that already existed is not
     * an answer (review 2026-10-28, second pass: an earlier draft made a safety handoff vanish from the list).
     */
    public boolean stillWaitingAfterHandoff(UUID orgId, Review review, java.time.Instant handedOffAt) {
        UUID reviewId = review.getId();
        TriageDisposition decided = disposition(orgId, reviewId).orElse(null);
        return review.getReplyState() != ReviewReplyState.ANSWERED
                && approvals.current(orgId, reviewId).isEmpty()
                && !drafts.writtenByAPersonSince(reviewId, handedOffAt)
                && !delegatedOpen(orgId, reviewId)
                && (dismissals == null || !dismissals.existsByOrgIdAndReviewIdAndDismissedAtAfter(orgId, reviewId,
                        handedOffAt))
                && (decided == null || decided == TriageDisposition.RESPONSE_NEEDED);
    }

    /** The version the seller approved and when, if one stands — for the agent's outcome report. */
    public java.util.Optional<java.util.Map.Entry<Integer, java.time.Instant>> approvedVersion(UUID orgId, UUID reviewId) {
        return approvals.current(orgId, reviewId).filter(a -> a.getApprovedVersion() != null)
                .map(a -> java.util.Map.entry(a.getApprovedVersion(), a.getDecidedAt()));
    }

    /** Who wrote a version — SELLER or MODEL — for telling a seller's edit from a rewrite by the AI. */
    public java.util.Optional<String> draftAuthorKind(UUID reviewId, int version) {
        return drafts.version(reviewId, version).map(ReviewReplyDraft::getAuthorKind);
    }

    /** When a version was written, for the time from the agent's draft to the seller's approval. */
    public java.util.Optional<java.time.Instant> draftWrittenAt(UUID reviewId, int version) {
        return drafts.version(reviewId, version).map(ReviewReplyDraft::getCreatedAt);
    }

    /** Whether the review has a draft from before — shown with the handoff so it is checked, not sent as it stands. */
    public boolean hasDraft(UUID reviewId) {
        return drafts.currentVersion(reviewId) > 0;
    }

    /** The seller-press gates for a run a seller started: 대응 필요 (or a prepared draft), no approval, not answered. */
    public boolean maySellerWrite(UUID orgId, Review review) {
        TriageDisposition decided = disposition(orgId, review.getId()).orElse(null);
        try {
            // A channel where a seller cannot reply (Coupang) gets no draft from any door (review 2026-10-28).
            requireReplyFlow(review.getChannelId());
        } catch (ApiException noReplyFlow) {
            return false;
        }
        return review.getReplyState() != ReviewReplyState.ANSWERED
                && approvals.current(orgId, review.getId()).isEmpty()
                && (decided == null || decided == TriageDisposition.RESPONSE_NEEDED);
    }

    private boolean delegatedOpen(UUID orgId, UUID reviewId) {
        return delegated != null && delegated.holds(orgId, reviewId);
    }

    /** Approving here or starting a guided run while a delegate holds the reply would be a second public reply. */
    private void requireNotDelegated(UUID orgId, UUID reviewId) {
        if (delegatedOpen(orgId, reviewId)) {
            throw ApiException.conflict("담당자에게 맡긴 리뷰예요. 여기서 승인하거나 등록하지 않아요.");
        }
    }

    /** Everything the preparation surface needs for one review, in one read. */
    public ReviewReplyPrepView view(UUID orgId, UUID accountId, String actionRef) {
        Review review = authorize(orgId, accountId, actionRef);
        return compose(orgId, review, actionRef);
    }

    /**
     * Save a new draft version.
     *
     * @throws ApiException 409 when the disposition is not {@code RESPONSE_NEEDED} (the gate) or
     *                      an approval currently stands (the freeze).
     */
    public ReviewReplyDraftView saveDraft(UUID orgId, UUID accountId, String actionRef, String body,
                                          Integer baseVersion, UUID actorUserId) {
        Review review = authorize(orgId, accountId, actionRef);
        requireResponseNeededOrJudge(orgId, review, ACTOR_PREFIX + actorUserId);
        requireNotFrozen(orgId, review.getId());
        requireChannelNotAnswered(review);
        ReviewReplyDraftView saved = drafts.save(orgId, review.getId(), ACTOR_PREFIX + actorUserId, body, baseVersion);
        draftSaved(orgId, review.getId());
        return saved;
    }

    /** The phone shows the same draft (2026-10-25); bringing it up to date never fails the write that moved it. */
    private void draftSaved(UUID orgId, UUID reviewId) {
        if (delegated != null) {
            delegated.draftSaved(orgId, reviewId);
        }
    }

    /**
     * <b>Generate one grounded draft version</b> (Grounded Review Drafting v1).
     *
     * <p>Through the SAME three gates a hand-typed save goes through, because it produces the same
     * kind of row: this account owns the review, the review is 대응 필요, and no approval stands. The
     * approval freeze is why a regenerate on an approved review is a 409 rather than a silent
     * replacement — the seller approved an exact sentence, and the binding is to that sentence.
     *
     * <p>The composer owns everything after those gates and reaches no marketplace: three retrieval
     * lanes against this database, and at most one model call.
     */
    public GeneratedReviewDraftView generateDraft(UUID orgId, UUID accountId, String actionRef,
                                                  UUID actorUserId) {
        Review review = authorize(orgId, accountId, actionRef);
        requireResponseNeededOrJudge(orgId, review, ACTOR_PREFIX + actorUserId);
        requireNotFrozen(orgId, review.getId());
        requireChannelNotAnswered(review);
        if (composer == null) {
            throw ApiException.conflict("AI 초안 기능을 사용할 수 없어요.");
        }
        // Plain text AND redacted, in that order, from one call: the sanitizer strips the markup
        // itself now (2026-10-05). Channels hand reviews over wrapped in it — measured on this
        // deployment, a NAVER row arrives as «<p class="word">…</p>» — and every downstream reader is
        // worse off for the tags: the retrieval counts them as content words, the model reads them as
        // part of what the customer wrote, and a knowledge request that quotes them back is
        // unreadable to the seller.
        RedactedBody body = VocPreviewSanitizer.redactFullBody(review.getBody());
        if (agentRuns != null && (agentRuns.available(orgId) || agentRuns.required())) {
            return generateByAgent(orgId, review, actorUserId);
        }
        GeneratedReviewDraftView generated = composer.compose(orgId, review, body.text(), ACTOR_PREFIX + actorUserId);
        draftSaved(orgId, review.getId());
        return generated;
    }

    /**
     * <b>The seller's press, through the reply agent</b> (owner, 2026-10-28 — the deferred items; second review: the
     * screen's 「초안 생성 · 다시 쓰기」 still ran the old drafter, so pressing it said nothing about the agent). When the
     * agent is on for the org, the press runs it — judge, draft, critique, one revision, save as this seller — and
     * shows the version it saved, or why there is none. It never falls back to the old drafter: a press must say which
     * path wrote what the seller reads. With the agent off, the old drafter runs as before.
     */
    private GeneratedReviewDraftView generateByAgent(UUID orgId, Review review, UUID actorUserId) {
        java.util.Optional<com.sellerops.agent.reply.ReplyRunStarter.Started> started =
                agentRuns.startReviewRun(orgId, review.getId(), "SELLER", actorUserId);
        if (started.isEmpty()) {
            // Only an install gets here: the agent is required and could not start (off for the org, or no runtime).
            return ReviewDraftComposer.agentNoDraft(AGENT_NOT_RUNNING);
        }
        java.util.Optional<com.sellerops.agent.reply.AgentRuntimeClient.RunReport> report =
                started.flatMap(com.sellerops.agent.reply.ReplyRunStarter.Started::report);
        if (report.isPresent() && "SAVED".equals(report.get().outcome()) && report.get().version() != null) {
            draftSaved(orgId, review.getId());
            return composer.agentDraftView(orgId, review.getId(), report.get().version());
        }
        String reason = report.map(com.sellerops.agent.reply.AgentRuntimeClient.RunReport::reason).orElse(null);
        String outcome = report.map(com.sellerops.agent.reply.AgentRuntimeClient.RunReport::outcome).orElse("FAILED");
        return ReviewDraftComposer.agentNoDraft(agentNoDraftMessage(outcome, reason));
    }

    static final String AGENT_NOT_RUNNING = "AI 답글 에이전트가 이 PC에서 켜져 있지 않아 초안을 만들지 않았어요. 프로그램 상태를 "
            + "확인해 주세요.";

    /** Why the agent left no draft, in the seller's words — never the review, never a vendor message. */
    static String agentNoDraftMessage(String outcome, String reason) {
        String r = reason == null ? "" : reason;
        if ("HANDOFF".equals(outcome)) {
            return switch (r) {
                case "CATEGORY_S" -> "안전 관련 리뷰라 AI가 답글을 쓰지 않았어요. 직접 확인해 주세요. 상품에 따라 법정 보고 의무가 "
                        + "있을 수 있어요.";
                case "CATEGORY_L" -> "분쟁 신호가 있는 리뷰라 AI가 답글을 쓰지 않았어요. 직접 확인해 주세요.";
                case "CATEGORY_X" -> "신고 대상일 수 있는 리뷰예요. 답글보다 플랫폼 신고를 먼저 검토해 주세요.";
                case "SAFETY_SIGNAL" -> "리뷰에 다침·이물·신고 같은 말이 있어 AI가 답글을 쓰지 않았어요. 직접 확인해 주세요.";
                case "PERSON_REVIEW" -> "AI가 답글보다 판매자가 먼저 볼 리뷰로 판단했어요. 직접 확인해 주세요.";
                case "UNSURE_AMBIGUOUS" -> "고객이 무엇을 말하는지 AI가 정하지 못했어요. 문제없다는 뜻이 아니니 읽고 답해 주세요.";
                case "UNSURE_PRODUCT" -> "어떤 상품·옵션 이야기인지 연결되지 않았어요. 상품을 확인한 뒤 작성해 주세요.";
                case "TOO_MANY_POINTS" -> "리뷰가 말한 문제·질문이 많아 답글 하나로 빠짐없이 다루기 어려워요. 직접 정리해 주세요.";
                case "QUESTION_NO_FACTS" -> "리뷰의 질문에 답할 상품 정보·정책이 등록돼 있지 않아요. 답을 정한 뒤 작성해 주세요.";
                case "KNOWLEDGE_MISSING" -> "등록된 정보가 이 리뷰의 질문·문제에 답하지 못해요. 답을 정한 뒤 작성해 주세요.";
                case "KNOWLEDGE_NOT_SHOWN" -> "등록된 자료가 많아 AI가 일부만 보고 답을 찾지 못했어요. 자료가 없다는 뜻은 아니니 "
                        + "상품·정책을 확인해 작성해 주세요.";
                case "POLICY_CONFLICT" -> "등록된 정보끼리 서로 다르게 적혀 있어요. 어느 쪽이 맞는지 정리한 뒤 작성해 주세요.";
                default -> "AI가 기준에 맞는 답글을 쓰지 못했어요. 직접 작성해 주세요.";
            };
        }
        if ("PENDING".equals(outcome)) {
            return "오늘 AI 사용량 상한에 닿아 초안을 검토하지 못했어요. 초안은 「검토 대기」로 보관했고, 한도가 풀리면 새로 "
                    + "쓰지 않고 검토부터 이어서 해요.";
        }
        if ("SKIPPED".equals(outcome) && "NO_RESPONSE_NEEDED".equals(r)) {
            return "AI가 이 리뷰는 답글이 없어도 남는 문제가 없다고 봤어요. 그래도 답하시려면 직접 작성해 주세요.";
        }
        if ("SKIPPED".equals(outcome)) {
            return "그 사이 리뷰나 근거가 바뀌어 초안을 저장하지 않았어요. 다시 눌러 주세요.";
        }
        return "AI 답글 에이전트가 지금 초안을 만들지 못했어요. 잠시 뒤 다시 시도하거나 직접 작성해 주세요.";
    }

    /**
     * The evidence a draft of this review would be written from, without writing one (2026-10-27): the same retrieval
     * {@link #generateDraft} runs, and nothing after it — no model call, no version, no 확인 필요 row. Read-only, so it
     * needs no response gate: the owner checks what their knowledge reaches before any model is paid.
     */
    public java.util.List<com.sellerops.inquiry.draft.dto.DraftEvidenceView> previewGrounds(UUID orgId, UUID accountId,
                                                                                             String actionRef) {
        Review review = authorize(orgId, accountId, actionRef);
        if (composer == null) {
            throw ApiException.conflict("AI 초안 기능을 사용할 수 없어요.");
        }
        return composer.groundsFor(orgId, review, VocPreviewSanitizer.redactFullBody(review.getBody()).text());
    }

    /** 「이 리뷰에 맞는 안내」 — what the review is given, refused, and could be given (2026-10-27). No model call. */
    public com.sellerops.review.draft.CustomerPhraseLane.Options guidance(UUID orgId, UUID accountId, String actionRef) {
        Review review = authorize(orgId, accountId, actionRef);
        return composerOrConflict().guidanceOptions(orgId, review,
                VocPreviewSanitizer.redactFullBody(review.getBody()).text());
    }

    /** The seller's 「맞아요」 · pick · 「아니에요」 for one guidance on one review. The next draft follows it. */
    public com.sellerops.review.draft.CustomerPhraseLane.Options chooseGuidance(UUID orgId, UUID accountId,
                                                                               String actionRef, UUID sourceId,
                                                                               String choice, UUID actorUserId) {
        Review review = authorize(orgId, accountId, actionRef);
        return composerOrConflict().chooseGuidance(orgId, review,
                VocPreviewSanitizer.redactFullBody(review.getBody()).text(), sourceId, choice, actorUserId);
    }

    public com.sellerops.review.draft.CustomerPhraseLane.Options clearGuidance(UUID orgId, UUID accountId,
                                                                              String actionRef, UUID sourceId) {
        Review review = authorize(orgId, accountId, actionRef);
        return composerOrConflict().clearGuidance(orgId, review,
                VocPreviewSanitizer.redactFullBody(review.getBody()).text(), sourceId);
    }

    private com.sellerops.review.draft.ReviewDraftComposer composerOrConflict() {
        if (composer == null) {
            throw ApiException.conflict("AI 초안 기능을 사용할 수 없어요.");
        }
        return composer;
    }

    /**
     * Approve or withdraw.
     *
     * <p><b>The gate is asymmetric, and that is the design.</b> Approving requires
     * {@code RESPONSE_NEEDED}; withdrawing never does. Withdrawal is the one operation that
     * reduces commitment, and gating it would strand a review in APPROVED with no way out —
     * frozen against editing by its own approval, and frozen against withdrawal by the gate.
     * An operator who changes their mind about a review must always be able to unsay the
     * approval, whatever they have since concluded about the review itself.
     */
    public ReviewReplyApprovalResponse decideApproval(UUID orgId, UUID accountId, String actionRef,
                                                      String state, Integer baseVersion,
                                                      String commandId, UUID actorUserId) {
        ReviewReplyApprovalState target = ReviewReplyApprovalState.parse(state);
        String command = ReviewReplyApprovalService.requireCommandId(commandId);
        Review review = authorize(orgId, accountId, actionRef);
        UUID reviewId = review.getId();
        String actor = ACTOR_PREFIX + actorUserId;

        if (target == ReviewReplyApprovalState.WITHDRAWN) {
            // No disposition gate: the exit is never blocked.
            //
            // The gate asks whether there is anything to withdraw FROM — a review nobody ever
            // approved has no exit to take, and that stays a conflict. It deliberately does NOT
            // refuse a second withdrawal of an already-withdrawn row.
            //
            // It used to, by testing the STATE here, and that is what made this endpoint answer
            // differently depending on thread scheduling: this check and the write are not one
            // atomic step, so of two identical concurrent withdrawals the loser answered 200 when
            // it read first and 409 when it read after the winner committed — same two callers,
            // same intent, two contracts. The concern that motivated the state test — that a
            // second withdrawal appends a WITHDRAWN→WITHDRAWN edge which moves nothing while
            // reattributing the standing decision to whoever fired last — is real, and is now
            // answered where it can be answered truthfully: the writer re-reads the state UNDER
            // its row lock and writes nothing at all. The exit is idempotent, which is the honest
            // shape for it — the caller asked for a state that already holds.
            gateOrReplay(orgId, reviewId, command, () -> {
                if (approvals.current(orgId, reviewId).isEmpty()) {
                    throw ApiException.conflict("승인된 초안이 없어요.");
                }
            });
            return approvals.decide(orgId, reviewId, actionRef, target, null, null, command, actor);
        }

        // The binding is the version the CLIENT named, resolved before any gate so that a replay
        // can be compared against the same binding a fresh write would have produced.
        if (baseVersion == null) {
            throw ApiException.badRequest("승인할 초안 버전(baseVersion)이 필요해요.");
        }
        ReviewReplyDraft bound = drafts.version(reviewId, baseVersion)
                .orElseThrow(() -> ApiException.conflict("승인할 초안이 없어요. 먼저 초안을 저장하세요."));

        // A draft the automatic check wrote ahead, on a review nobody has judged yet, is approved as the seller's
        // judgement that it needs a response (owner, 2026-10-28). A review someone judged 지켜보기 or 할 일 없음 is
        // still refused, as before — the exception is only for a review nobody has judged.
        boolean judgedNow = preparedAheadUndecided(orgId, reviewId, bound);
        gateOrReplay(orgId, reviewId, command, () -> {
            if (!judgedNow) {
                requireResponseNeeded(orgId, reviewId);
            }
            // Approving is forward motion, so a standing approval freezes it exactly as it
            // freezes saving. Without this, re-approving succeeded while `canApprove` reported
            // false — and the capability object's promise that the server enforces the rules it
            // reports is only worth anything if the flag and the guard are the same rule.
            requireNotFrozen(orgId, reviewId);
            // Same reason, for the same rule: approving is the last step before a reply is meant
            // to be posted, and the channel says one already is.
            requireChannelNotAnswered(review);
            // …and the seller already approved it for a delegate, here or on the phone.
            requireNotDelegated(orgId, reviewId);
            requireNoForbiddenPhrase(orgId, bound.getBody());
            // A channel's length limit is not a style: a text over it is kept to fix, never approved (seventh review).
            requireWithinChannelLimit(review, bound.getBody());
            // Checked again against the knowledge and policy as they are now (2026-11-01): a draft written before a policy
            // change is not approved on the old basis, whoever wrote it — fixed first, or confirmed past with a record.
            ReplyHoldService holdService = holds == null ? null : holds.getIfAvailable();
            if (holdService != null) {
                holdService.approvalBlock(orgId, review, bound).ifPresent(reason -> {
                    throw ApiException.conflict(reason);
                });
            }
            // Approve the version you actually saw. If the head moved on, binding to the newer
            // text would approve words the operator never read.
            //
            // `headVersion` is an int, and that is not incidental: `baseVersion` is an Integer
            // (Jackson boxes it), so comparing it with `!=` against another Integer compares
            // REFERENCES, not values. That silently works up to 127 — where Integer.valueOf's
            // cache hands both sides the same object — and then refuses every approval from
            // version 128 on, with a "refresh and try again" message that cannot help, because
            // refreshing produces the same two distinct boxes. Unboxing one side forces the
            // comparison to be about the number.
            int headVersion = drafts.latest(reviewId).map(ReviewReplyDraft::getVersion).orElse(0);
            if (baseVersion.intValue() != headVersion) {
                throw ApiException.conflict("이미 최신 초안이 있어요. 새로고침 후 다시 시도하세요.");
            }
            // Every other gate has passed, so the seller's approval is also their 「대응 필요」 — recorded as them, before
            // the approval row, so every reader that asks for 대응 필요 finds it.
            if (judgedNow) {
                triageWriter.applyDecision(orgId, reviewId, review.getChannelId(), TriageDisposition.RESPONSE_NEEDED,
                        UUID.nameUUIDFromBytes(("prepared-approval:" + orgId + ":" + command)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), actor);
            }
            // The same draft waits on the phone (2026-10-25); approving here takes it back from there first.
            if (delegated != null) {
                delegated.yieldToApprovalHere(orgId, reviewId);
            }
        });
        ReviewReplyApprovalResponse decided = approvals.decide(orgId, reviewId, actionRef, target,
                bound.getVersion(), bound.getContentFingerprint(), command, actor);
        // <b>The approval is the operation; remembering it is not.</b> Review Delivery Truth Spine v1: an
        // approval is the seller saying «this is what we say», which is the same act the inquiry lane records as
        // USER_APPROVED, so it is recorded with the same strength in the same table. After the decision and
        // outside its gates on purpose — a memory write must never be able to refuse an approval, and
        // `approvals.decide` is documented as hermetic and must stay that way.
        //
        // Replays land here too, and must: `decide` is idempotent on the command id and so is `remember` on the
        // act id (`review-approved:<review>:<version>`), so a retried approval re-records the same row rather
        // than a second one. A WITHDRAWN returns above and writes nothing — a withdrawal does not delete what
        // the seller once stood behind, exactly as it does not delete the approval audit row.
        if (answerMemory != null) {
            answerMemory.rememberApproved(review, bound, actorUserId);
        }
        return decided;
    }

    /**
     * Start a guided Action Window reply-submission run: mint a single-use {@code submissionRef}
     * bound to the current approved head.
     *
     * <p>Same gate as copy — you may guide a post only for an approved reply you may copy, because a
     * guided post IS the copy step performed in the seller center rather than the clipboard. It
     * authorizes no send: SellerOps only guides and observes; the operator submits. Marketplace-neutral.
     *
     * @throws ApiException 409 when the review is not {@code RESPONSE_NEEDED} or no approval stands.
     */
    public ReviewReplySubmissionRunResponse startSubmissionRun(UUID orgId, UUID accountId,
                                                               String actionRef, UUID actorUserId) {
        return startSubmissionRun(orgId, accountId, actionRef, actorUserId, false);
    }

    /**
     * As {@link #startSubmissionRun(UUID, UUID, String, UUID)}, but when {@code requireTargetHint} is set
     * (guided preparation) the privacy-safe review target hint — coarse rating, KST recency bucket, and the
     * one-way {@code review-body-fingerprint/v1} — is derived AND validated <b>before</b> the ref is minted.
     * A review that cannot produce a valid hint (missing rating or blank body) throws 409 and mints
     * <b>nothing</b>, so a missing hint can never leave an unusable single-use ref. The hint carries no raw
     * body/timestamp/id; only the coarse fields and the fingerprint surface, alongside the explicit KST
     * {@code asOfDate} the bucket was computed against.
     *
     * @throws ApiException 409 when the review is not {@code RESPONSE_NEEDED}, no approval stands, or (guided)
     *     the review cannot produce a valid target hint.
     */
    public ReviewReplySubmissionRunResponse startSubmissionRun(UUID orgId, UUID accountId,
                                                               String actionRef, UUID actorUserId,
                                                               boolean requireTargetHint) {
        Review review = authorize(orgId, accountId, actionRef);
        UUID reviewId = review.getId();
        requireResponseNeeded(orgId, reviewId);
        // Enforced server-side, not merely hidden by `canStartSubmissionRun`: the capability object
        // renders affordances, and a client that ignores it must still not be able to start a guided
        // run against a review the channel already answered. A guided run is the step immediately
        // before a public post, and the post cannot be taken back.
        if (review.getReplyState() == ReviewReplyState.ANSWERED) {
            throw ApiException.conflict("채널에 이미 답변이 등록된 리뷰예요. 가이드형 답변을 시작할 수 없어요.");
        }
        // Enforced server-side for the same reason the line above is: the capability object renders
        // affordances, and a client that ignores it must still not start a seller-center handoff for a
        // channel whose approved reply does not travel that way.
        if (!guidedLane(review.getChannelId())) {
            throw ApiException.conflict("이 채널은 판매자센터에서 직접 답변하는 방식이 아니에요. 승인한 답변을 복사해 사용하세요.");
        }
        requireNotDelegated(orgId, reviewId);
        ReviewReplyApproval approval = approvals.current(orgId, reviewId)
                .filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                .orElseThrow(() -> ApiException.conflict("승인된 답변이 없어요. 먼저 답변을 승인하세요."));
        requireStillStands(orgId, review, approval);

        // Guided: derive AND validate the hint BEFORE minting, so a review that cannot produce a valid hint
        // never leaves an unusable spent ref.
        ReviewReplyTargetHintView targetHint = null;
        String asOfDate = null;
        if (requireTargetHint) {
            LocalDate asOf = ReviewRecencyBucket.asOfKstDate(clock.instant());
            Integer rating = review.getRating();
            String body = review.getBody();
            if (rating == null || rating < 1 || rating > 5 || body == null || body.isBlank()) {
                throw ApiException.conflict("이 리뷰로는 제출 대상 힌트를 만들 수 없어 제출을 시작할 수 없어요.");
            }
            targetHint = new ReviewReplyTargetHintView(rating,
                    ReviewRecencyBucket.of(review.getReceivedAt(), asOf).name(),
                    ReviewBodyFingerprint.of(body));
            asOfDate = asOf.toString();
        }

        // The intent (V86): the account the run acts through, its channel, what the review resolves
        // to right now, the mode, and a deadline. Identity is RECORDED here and ENFORCED where the
        // Local Agent spends the ref — a NONE row mints a run nobody can resolve to a page.
        ReviewReplyOutcomeService.SubmissionIntent intent = new ReviewReplyOutcomeService.SubmissionIntent(
                accountId, review.getChannelId(), identity.forReview(review).name(), GUIDED_EXECUTION_MODE,
                clock.instant().plus(SUBMISSION_REF_TTL));
        String ref = outcomes.mint(orgId, reviewId, approval.getApprovedVersion(),
                approval.getApprovedFingerprint(), ACTOR_PREFIX + actorUserId, intent);
        return new ReviewReplySubmissionRunResponse(actionRef, ref, approval.getApprovedVersion(),
                targetHint, asOfDate);
    }

    /**
     * Record the operator's report that they posted (or did not post) the approved reply in the
     * seller center — a LOCAL, operator-reported, explicitly UNVERIFIED fact. Never a claim about
     * NAVER, never a completion.
     *
     * <p>Version and fingerprint are server-sourced from the binding; the client names only the ref,
     * the reported outcome, the run ref, and its command id. The binding must still describe the
     * current approved head — a withdrawal or a newer approval since the mint is a 409. The gate is
     * the same asymmetric forward gate ({@code RESPONSE_NEEDED}) as copy; a spent binding is refused
     * (single-use), and a retry of the same command replays rather than double-recording.
     */
    public ReviewReplyOutcomeResponse recordSubmissionReported(UUID orgId, UUID accountId,
                                                               String actionRef, String submissionRef,
                                                               String operatorOutcomeRaw, String awRunRef,
                                                               String commandId, UUID actorUserId) {
        OperatorOutcome operatorOutcome = OperatorOutcome.parse(operatorOutcomeRaw);
        String command = ReviewReplyApprovalService.requireCommandId(commandId);
        String ref = requireSubmissionRef(submissionRef);
        String runRef = optionalAwRunRef(awRunRef);
        Review review = authorize(orgId, accountId, actionRef);
        UUID reviewId = review.getId();
        String actor = ACTOR_PREFIX + actorUserId;

        ReviewReplySubmissionRef binding = outcomes.binding(orgId, ref)
                .filter(b -> b.getReviewId().equals(reviewId))
                .orElseThrow(() -> ApiException.conflict("유효하지 않은 제출 참조예요. 다시 시작해 주세요."));

        gateOrReplayOutcome(orgId, command, () -> {
            requireResponseNeeded(orgId, reviewId);
            ReviewReplyApproval approval = approvals.current(orgId, reviewId)
                    .filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                    .orElse(null);
            if (approval == null || approval.getApprovedVersion() == null
                    || approval.getApprovedVersion().intValue() != binding.getBoundVersion().intValue()
                    || !approval.getApprovedFingerprint().equals(binding.getBoundFingerprint())) {
                throw ApiException.conflict("승인 상태가 바뀌었어요. 답변 제출을 다시 시작해 주세요.");
            }
            if (outcomes.isSpent(ref)) {
                throw ApiException.conflict("이미 결과가 기록된 제출이에요. 다시 시작해 주세요.");
            }
        });

        String algorithm = drafts.version(reviewId, binding.getBoundVersion())
                .map(ReviewReplyDraft::getFingerprintAlgorithm)
                .orElseThrow(() -> new IllegalStateException(
                        "review_reply_submission_ref binds a draft version that does not exist"));
        return outcomes.record(orgId, reviewId, actionRef, ref, binding.getBoundVersion(),
                binding.getBoundFingerprint(), algorithm, operatorOutcome, runRef, command, actor);
    }

    /** As {@link #gateOrReplay}, but the idempotency ledger is the outcome table, not the approval trail. */
    private void gateOrReplayOutcome(UUID orgId, String command, Runnable gate) {
        try {
            gate.run();
        } catch (ApiException gateClosed) {
            if (!outcomes.isCommandSpent(orgId, command)) {
                throw gateClosed;
            }
        }
    }

    private static String requireSubmissionRef(String submissionRef) {
        if (submissionRef == null || !submissionRef.strip().matches("[0-9a-f]{16}")) {
            throw ApiException.badRequest("유효하지 않은 제출 참조예요.");
        }
        return submissionRef.strip();
    }

    /**
     * The Action Window run ref, or {@code null} when the operator posted MANUALLY with no run.
     *
     * <p>This used to REQUIRE one, and that requirement is what produced the defect it now prevents:
     * with no guided runtime wired, the client had to supply something, so every shipped build sent a
     * locally-minted {@code run_<hex>} for a run that never happened — a fabricated Action Window
     * identity, indistinguishable in the table from a real one.
     *
     * <p>Absence is now a first-class answer. A blank string normalises to null rather than being
     * stored: a caller with no run says so by OMISSION, and there is no placeholder it could send
     * that would be true. A present value is still length-checked.
     */
    private static String optionalAwRunRef(String awRunRef) {
        if (awRunRef == null || awRunRef.isBlank()) {
            return null;
        }
        String ref = awRunRef.strip();
        if (ref.length() > 128) {
            throw ApiException.badRequest("실행 참조가 너무 겨요.");
        }
        return ref;
    }

    /**
     * Run a gate, but do not let it refuse a command that has already been applied.
     *
     * <p><b>Every gate here is closed by its own command's success.</b> Approving closes
     * {@code canApprove}; withdrawing closes {@code canWithdraw}. So a client retrying a request
     * whose response it never saw — the ordinary reason command ids exist at all — would be
     * refused by the state its own first attempt created, and told 409 for a decision that in
     * fact succeeded. Gating before the idempotency lookup makes retries unsafe exactly when
     * they matter most.
     *
     * <p>So a closed gate is re-examined rather than reported: if this command id is already
     * spent in this org, the effect has landed and {@link ReviewReplyApprovalService#decide}
     * resolves it as the replay it is (or as a 409, if the id was spent on a DIFFERENT
     * decision — that comparison stays where it belongs). Any other reason the gate closed is
     * still a conflict, and still says so in its own words.
     *
     * <p>This is race-free rather than narrowly-timed, and the reason is worth naming:
     * {@link ReviewReplyApprovalWriter} commits the approval row and its audit row in ONE
     * transaction. There is therefore no instant at which a concurrent winner has closed this
     * gate but not yet published the audit row that explains why — the state that refuses us and
     * the evidence that exonerates us become visible together, or not at all.
     */
    private void gateOrReplay(UUID orgId, UUID reviewId, String command, Runnable gate) {
        try {
            gate.run();
        } catch (ApiException gateClosed) {
            if (!approvals.isCommandSpent(orgId, command)) {
                throw gateClosed;
            }
        }
    }

    /**
     * Re-derive the caller's right to touch this row. The ref is an address, never a capability
     * — see {@link VocItemRef}.
     */
    private Review authorize(UUID orgId, UUID accountId, String actionRef) {
        UUID reviewId = VocItemRef.parseReviewId(actionRef);
        SellerAccount account = sellerAccounts.findByIdAndOrgId(accountId, orgId)
                .orElseThrow(() -> ApiException.notFound("판매 계정을 찾을 수 없어요."));
        Review review = reviews.findByIdAndOrgId(reviewId, orgId)
                .orElseThrow(ReviewReplyService::unaddressable);
        if (account.getChannelId() == null || !account.getChannelId().equals(review.getChannelId())) {
            throw unaddressable();
        }
        requireReplyFlow(review.getChannelId());
        return review;
    }

    /**
     * Acceptance Closure §10: a channel with no reply flow at all (Coupang) gets no draft, no approval and
     * no guided run through these endpoints — the conversation route refuses it too, and this is the
     * server-side reason a client that skipped the conversation is refused as well. A test seam without a
     * channel repository cannot name the channel and does not gate (the legacy fixtures are NAVER).
     */
    private void requireReplyFlow(UUID channelId) {
        if (channels == null || channelId == null) {
            return;
        }
        String code = channels.findById(channelId).map(c -> c.getCode()).orElse(null);
        if (code != null && !ReviewTriageChannelCapability.of(code).replyFlowExists()) {
            throw ApiException.conflict("이 채널에서는 판매자가 리뷰에 직접 답글을 남길 수 없어요.");
        }
    }

    /**
     * Whether the guided seller-center handoff is this channel's send lane at all.
     *
     * <p>A different question from {@link #requireReplyFlow(UUID)}, and separating the two is the point:
     * a reply flow says the seller may WRITE and APPROVE an answer here; this says the approved text
     * reaches the channel by the operator posting it in the seller center, which is what the handoff
     * guides. Cafe24 has the first and not the second — its approved answer leaves through the
     * board-comment adapter, under its own execution capability, or it is pasted by hand from 복사.
     *
     * <p>Before the two were separated, Cafe24 reached this lane only when the write grant was already
     * recorded; now it reaches it whenever the seller can draft, so an ungated handoff would offer a mall
     * owner 「네이버에서 직접 답변하기」 and a panel telling them to paste into 네이버 판매자센터.
     *
     * <p>Fails OPEN for a test seam with no channel repository, exactly as the reply-flow gate does and
     * for the same reason: those fixtures are NAVER, and an unnameable channel is not evidence.
     */
    private boolean guidedLane(UUID channelId) {
        if (channels == null || channelId == null) {
            return true;
        }
        String code = channels.findById(channelId).map(c -> c.getCode()).orElse(null);
        return code == null || ReviewExecutionCapability.guidedBrowserLane(code);
    }

    private Optional<TriageDisposition> disposition(UUID orgId, UUID reviewId) {
        return triages.findByOrgIdAndReviewId(orgId, reviewId).map(ReviewTriage::getDisposition);
    }

    /**
     * Whether the head draft was written ahead and nobody has judged the review yet (2026-10-28) — the one state in
     * which the seller's next forward act on the draft (edit, rewrite, approve) is also their 「대응 필요」.
     */
    private boolean preparedAheadUndecided(UUID orgId, UUID reviewId, ReviewReplyDraft head) {
        return triageWriter != null && head != null && PREPARED_AHEAD_ACTOR.equals(head.getCreatedBy())
                && disposition(orgId, reviewId).isEmpty();
    }

    /**
     * The 대응 필요 gate, where the seller working on a draft written ahead IS the decision: it is recorded as them,
     * then the gate reads it. Anything else is the gate as it always was.
     */
    private void requireResponseNeededOrJudge(UUID orgId, Review review, String actor) {
        if (preparedAheadUndecided(orgId, review.getId(), drafts.latest(review.getId()).orElse(null))) {
            triageWriter.applyDecision(orgId, review.getId(), review.getChannelId(), TriageDisposition.RESPONSE_NEEDED,
                    UUID.randomUUID().toString(), actor);
        }
        requireResponseNeeded(orgId, review.getId());
    }

    private void requireResponseNeeded(UUID orgId, UUID reviewId) {
        if (disposition(orgId, reviewId).orElse(null) != TriageDisposition.RESPONSE_NEEDED) {
            throw ApiException.conflict("'대응 필요'로 기록된 리뷰만 답변을 준비할 수 있어요.");
        }
    }

    /**
     * Refuse forward motion on a review the CHANNEL already reports as answered.
     *
     * <p>The mirror of {@code canSave} / {@code canApprove} in {@link #compose}, stated here so the
     * flag and the guard are the same rule — the capability object only describes, and a client that
     * ignores it must still be refused. It is not applied to withdrawal (an approval recorded before
     * the import landed must keep its exit) nor to copying (the text is already the operator's).
     */
    private void requireChannelNotAnswered(Review review) {
        if (review.getReplyState() == ReviewReplyState.ANSWERED) {
            throw ApiException.conflict("채널에 이미 답변이 등록된 리뷰예요. 새 답변은 준비하지 않아요.");
        }
    }

    /**
     * Whether an approval currently STANDS — the single predicate behind {@code canApprove},
     * {@code canWithdraw}, {@code canCopy}, and both guards below.
     *
     * <p>Stated once rather than re-expressed at each site. The capability object claims the
     * server enforces the rules it reports; that claim survives only if the flag and the guard
     * are literally the same test, not two expressions that currently agree.
     */
    private boolean isApproved(UUID orgId, UUID reviewId) {
        return approvals.current(orgId, reviewId)
                .map(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                .orElse(false);
    }

    private void requireNotFrozen(UUID orgId, UUID reviewId) {
        if (isApproved(orgId, reviewId)) {
            throw ApiException.conflict("승인된 초안은 수정할 수 없어요. 승인을 해제한 뒤 수정하세요.");
        }
    }

    /** KST calendar date (date only) — the granularity a seller scans a review list by. */
    private static String kstDate(java.time.Instant instant) {
        return instant == null ? null : instant.atZone(KST).toLocalDate().toString();
    }

    /**
     * The review's product display name, or null when none can be shown honestly.
     *
     * <p>Org-scoped at the query boundary: {@code reviews.product_id} is a bare FK with no org
     * constraint, so an id read off a row is not proof of same-org ownership and a cross-org id must
     * resolve to nothing rather than to another org's catalog entry.
     */
    private String productDisplayName(UUID orgId, Review review) {
        if (review.getProductId() == null) {
            return null;
        }
        return products.findAllByOrgIdAndIdIn(orgId, java.util.Set.of(review.getProductId())).stream()
                .findFirst()
                .map(OperatorProductName::displayNameOrNull)
                .orElse(null);
    }

    /**
     * The first reason approving is closed, in {@link #compose}'s own order — or null when it is open. Read from the
     * same booleans that set {@code canApprove}, so the sentence and the flag cannot disagree.
     */
    static String approveUnavailableReason(boolean canApprove, boolean approved, boolean responseNeeded,
            boolean channelAnswered, boolean hasDraft, boolean delegated) {
        if (canApprove) return null;
        if (approved) return "APPROVED";
        if (!responseNeeded) return "NOT_RESPONSE_NEEDED";
        if (channelAnswered) return "CHANNEL_ANSWERED";
        if (!hasDraft) return "NO_DRAFT";
        if (delegated) return "DELEGATED";
        return null;
    }

    private ReviewReplyPrepView compose(UUID orgId, Review review, String actionRef) {
        RedactedBody body = VocPreviewSanitizer.redactFullBody(review.getBody());
        TriageDisposition triage = disposition(orgId, review.getId()).orElse(null);
        ReviewReplyDraft head = drafts.latest(review.getId()).orElse(null);
        ReviewReplyApproval approval = approvals.current(orgId, review.getId()).orElse(null);

        // A draft written ahead, on a review nobody has judged, reads as 대응 필요: the seller's first act on it records
        // the decision as theirs ({@link #requireResponseNeededOrJudge}), so the flags say what the guards will allow.
        boolean responseNeeded = triage == TriageDisposition.RESPONSE_NEEDED
                || preparedAheadUndecided(orgId, review.getId(), head);
        boolean approved = approval != null
                && approval.getState() == ReviewReplyApprovalState.APPROVED;
        // The channel says this review already has a reply.
        //
        // <b>Every step TOWARD a second public reply is closed</b> — writing a new draft version,
        // generating one, and approving one — not only the guided run at the end of that road
        // (product-owner decision, pilot QA 2026-09-06). Until now only the guided run was closed,
        // on the reasoning that an operator might still want the internal record; what the pilot
        // walk showed is that the road stays fully lit and the sign only appears at the last step,
        // after the seller has written, read and approved a reply they cannot post. Preparing an
        // answer to an answered review is work with no destination.
        //
        // Two things stay open, and deliberately: `canWithdraw`, because an approval recorded
        // before the import landed must always have an exit, and `canCopy`, because the text is
        // already theirs and copying posts nothing.
        boolean channelAnswered = review.getReplyState() == ReviewReplyState.ANSWERED;
        // A guided run may only look for a review whose channel-side identity its acquisition can prove.
        // This is the SAME rule the submission-target mint applies (`ExecutableIdentity.MARKETPLACE`);
        // asking it here is what stops the surface offering a control the server would refuse. Before
        // this, a review without that provenance showed 「네이버에서 직접 답변하기」, the press minted
        // nothing, and the seller read 「답변 준비를 시작하지 못했습니다. 다시 시도해 주세요.」 — an error
        // that invited a retry which could never succeed. The provenance word itself does not leave the
        // server; what leaves is the consequence and the next step (copy).
        boolean sourceExecutable = identity.forReview(review) == ExecutableIdentity.MARKETPLACE;
        // …and only for a channel whose approved reply actually travels through the seller center.
        boolean guidedLane = guidedLane(review.getChannelId());
        // A delegate holds this reply (vendor lane): the flags say what the guards above enforce.
        boolean delegatedNow = delegatedOpen(orgId, review.getId());
        ReviewReplyCapabilities capabilities = new ReviewReplyCapabilities(
                responseNeeded && !approved && !channelAnswered,
                responseNeeded && !approved && !channelAnswered && head != null && !delegatedNow,
                approved,
                responseNeeded && approved,
                // canStartSubmissionRun — the same rule as canCopy (a guided post is the copy step
                // performed in the seller center); it never authorizes a send. Plus: never for a
                // review the channel already reports as answered, and never for one no run can find.
                responseNeeded && approved && !channelAnswered && !delegatedNow && sourceExecutable && guidedLane);
        // Said only when the seller is otherwise ready to send — that is when the missing control is a
        // question. `null` while there is nothing approved yet: the panel is already showing them the
        // approve step, and answering an unasked question is how a screen gets noisy.
        // …and said only about a channel that HAS the guided step. Both existing reasons explain why this
        // review cannot use it; neither is true of a channel where it was never part of the flow, and
        // 「수집 경로를 확인할 수 없어」 about a Cafe24 review would be an invented fault. Nothing is rendered,
        // and 복사 — already on screen — remains the next step.
        String guidedUnavailableReason = !capabilities.canCopy() || capabilities.canStartSubmissionRun()
                || !guidedLane ? null
                : channelAnswered ? "CHANNEL_ALREADY_ANSWERED" : "SOURCE_NOT_EXECUTABLE";

        // One indexed read; empty when the version was the template floor, written before V90, or
        // there is no draft at all.
        java.util.List<com.sellerops.inquiry.draft.dto.DraftEvidenceView> storedEvidence =
                head == null || composer == null ? java.util.List.of()
                        : composer.evidenceFor(orgId, review.getId(), head.getVersion());

        ReviewReplyProposalProvider.Suggestion suggestion = provider.suggest(
                new ReviewReplyProposalProvider.ReviewReplyContext(orgId, review.getId(),
                        body.text(), review.getRating()));

        return new ReviewReplyPrepView(
                actionRef,
                body.text(),
                body.redacted(),
                triage == null ? null : triage.name(),
                new ReviewReplySuggestionView(suggestion.body(), suggestion.category(),
                        suggestion.providerKind(), suggestion.providerName(),
                        suggestion.providerVersion()),
                head == null ? null : ReviewReplyDraftView.of(head),
                approvalView(review.getId(), approval, capabilities.canCopy()),
                outcomeView(orgId, review.getId(), approval, approved),
                capabilities,
                // One-way; null when the review was ingested without a channel-side id. The raw
                // external id is read here and immediately digested — it never reaches the response.
                ReviewIdFingerprint.of(review.getExternalId()),
                review.getRating(),
                // The channel's own statement — the reason a guided run may be unavailable. A closed
                // enum name; the reply text and its timestamp never cross this boundary.
                review.getReplyState().name(),
                // Locating context, same values the attention row already showed. The display-name
                // rule is shared rather than re-implemented, so this panel cannot start rendering a
                // SKU while the row beside it withholds one.
                productDisplayName(orgId, review),
                kstDate(review.getReceivedAt()),
                head == null ? null : head.getAuthorKind(),
                storedEvidence,
                // The head version's own record of what it was written from. Read back rather than
                // re-derived: re-running the retrieval to answer «was this grounded?» would be a new
                // retrieval on a read path, and the answer could differ from the one the seller was
                // shown when the draft was written.
                head == null ? null : head.getAnswerBasis(),
                head == null ? null
                        : ReviewDraftComposer.basisNoteOf(head.getAnswerBasis(),
                                !storedEvidence.isEmpty()),
                guidedUnavailableReason,
                // The delegate lane adds its own state to the reply lane's verdict that this saved draft may move on.
                delegated == null ? null : delegated.describe(orgId, review.getId(),
                        responseNeeded && !approved && !channelAnswered && head != null),
                head == null || styleGuard == null ? java.util.List.of()
                        : styleGuard.findings(head.getBody(), orgId).stream()
                                .map(f -> new ReviewReplyPhraseFindingView(f.kind().name(), f.phrase(),
                                        f.replacement(), f.sentence()))
                                .toList(),
                approveUnavailableReason(capabilities.canApprove(), approved, responseNeeded, channelAnswered,
                        head != null, delegatedNow));
    }

    /**
     * The operator-reported outcome for the CURRENT approved reply, or null when nothing is approved
     * or nothing has been reported. Carries {@code operatorOutcome} and {@code verification} as two
     * separate facts — the surface renders the pair, never {@code UNVERIFIED} alone.
     */
    private ReviewReplyOutcomeView outcomeView(UUID orgId, UUID reviewId, ReviewReplyApproval approval,
                                               boolean approved) {
        if (!approved || approval.getApprovedVersion() == null) {
            return null;
        }
        return outcomes.latestForVersion(orgId, reviewId, approval.getApprovedVersion())
                .map(o -> new ReviewReplyOutcomeView(o.getOperatorOutcome().name(),
                        o.getVerification().name(), o.getRecordedVersion(), o.getRecordedFingerprint(),
                        o.getAwRunRef(), o.getCreatedAt()))
                .orElse(null);
    }

    /**
     * The approval, with its copyable body attached only when copying is allowed.
     *
     * <p><b>Fail closed on a mismatched binding.</b> While an approval stands, saves are frozen,
     * so {@code approved_version} is always the head version and its fingerprint always matches
     * the stored draft. If either is ever untrue the data has been corrupted by something this
     * code does not know about, and the honest response is to stop — not to serve a body under a
     * binding that does not describe it. An operator pasting text into a public reply is
     * entitled to know it is the text they approved.
     */
    private ReviewReplyApprovalView approvalView(UUID reviewId, ReviewReplyApproval approval,
                                                 boolean canCopy) {
        if (approval == null) {
            return null;
        }
        if (approval.getState() != ReviewReplyApprovalState.APPROVED) {
            return new ReviewReplyApprovalView(approval.getState().name(), null, null, null,
                    approval.getDecidedAt());
        }
        ReviewReplyDraft bound = drafts.version(reviewId, approval.getApprovedVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "review_reply_approval binds a draft version that does not exist"));
        if (!bound.getContentFingerprint().equals(approval.getApprovedFingerprint())) {
            throw new IllegalStateException(
                    "review_reply_approval fingerprint does not match the version it binds");
        }
        return new ReviewReplyApprovalView(approval.getState().name(), approval.getApprovedVersion(),
                approval.getApprovedFingerprint(), canCopy ? bound.getBody() : null,
                approval.getDecidedAt());
    }

    /**
     * The one "you cannot address this" answer. Deliberately identical for an absent review, a
     * cross-org review, and a review on another account's channel.
     */
    private static ApiException unaddressable() {
        return ApiException.notFound("해당 항목을 찾을 수 없어요.");
    }
}
