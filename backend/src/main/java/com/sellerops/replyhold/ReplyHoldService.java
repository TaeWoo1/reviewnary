package com.sellerops.replyhold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.attention.reply.ReviewReplyApproval;
import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.common.ApiException;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.publish.InquiryApprovalRepository;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewClaimGuard;
import com.sellerops.review.draft.ReviewDraftBasis;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Waiting replies checked again when what they were written from changes</b> (2026-11-01 review: 「웹 자동 재검사」;
 * product-owner answer to interview question 34, 2026-10-12: the AI checks first — a draft that still stands goes on, one
 * that does not is held and the seller is asked; approved-reply hold, 2026-10-23: hold an approved reply only on a
 * substantive conflict, never rewrite it, always say why).
 *
 * <p>Until now this ran only on the seller-PC sync ({@code VendorReplyTaskService}), behind the vendor link, so a store
 * on the web product alone had no recheck. The checks are the same ones — {@link ReviewDraftRecheck} for reviews,
 * {@link InquiryDraftRecheck} for inquiries — and no model is called. Three entry points:
 *
 * <ul>
 *   <li>{@link #sweep} — when an org's basis ({@link ReviewDraftBasis} plus the inquiry answer style) differs from the
 *       one it was last swept under, every waiting draft is checked: a pass clears its hold and keeps its text; a failure
 *       writes a {@link ReplyDraftHold} with its reasons. Run by {@link ReplyHoldSweepScheduler}.</li>
 *   <li>{@link #approvalBlock} / {@link #inquiryApprovalBlock} — right before an approval, against the basis as it is
 *       now: a sweep that has not run yet cannot let a stale draft through.</li>
 *   <li>{@link #registrationBlock} / {@link #inquiryRegistrationBlock} — right before an approved reply is registered.
 *       A changed citation alone does not hold it, as on the PC; any other finding does.</li>
 * </ul>
 *
 * <p><b>The same check whoever wrote it, and three kinds of finding</b> (2026-11-01 review: 「작성자에 따른 일괄 예외는
 * 없애는 게 좋습니다」 — a seller who touched a model draft has not thereby seen what the check found; and the owner's
 * follow-up the same day: 「UNSUPPORTED_PROMISE를 일반 확인으로 우회하지 못하게」). A version the model wrote, the seller
 * wrote, or a saved template filled, is checked alike, and what it found decides:
 * <ul>
 *   <li><b>Fix first</b> ({@link #mustFix}) — a banned phrase, a condition the law does not let the policy apply, a
 *       promise a policy says otherwise of ({@value ReviewDraftRecheck#POLICY_CONFLICT}), a number its ground no longer
 *       states. Nothing goes on until the text — or the policy — is changed.</li>
 *   <li><b>Decide this one</b> ({@link #needsException}) — a promise nothing registered backs either way. A plain
 *       confirmation does not go past it: the seller adds the evidence, changes the sentence, or decides 「이번 건만
 *       예외」 ({@link Decision#EXCEPTION}), naming the words, and the reply is checked again.</li>
 *   <li><b>Confirm to go on</b> ({@link #toConfirm}) — a cited source that changed, a number nobody can prove still
 *       holds. The reasons are shown; the seller may go on with an explicit confirmation ({@link Decision#CONFIRM}).</li>
 * </ul>
 * Either decision is recorded against the version, the basis, the approval state and the finding the seller was shown
 * ({@link HoldView#checkId}); a decision sent for a check that is no longer the current one is refused, and the new
 * reasons are shown instead. The text is never changed either way.
 */
@Service
public class ReplyHoldService {

    private static final Logger log = LoggerFactory.getLogger(ReplyHoldService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A finding that holds an approved reply only with company: a changed citation alone does not ({@code VendorReplyTaskService#checkOne}). */
    static final String CITATION_ONLY = ReviewDraftRecheck.SOURCE_CHANGED;
    /** Findings no decision goes past: a banned phrase, a promise a policy contradicts, a number now wrong. */
    static final Set<String> FIX_FIRST = Set.of(ReviewDraftRecheck.FORBIDDEN_PHRASE, ReviewDraftRecheck.POLICY_CONFLICT,
            InquiryDraftRecheck.FIGURE_CHANGED, InquiryDraftRecheck.SELLER_CHECK_CHANGED,
            InquiryDraftRecheck.SELLER_CHECK_COMPLETION);
    /** Findings a plain confirmation goes past once shown — recorded. An unbacked promise is not one of them. */
    static final Set<String> CONFIRMABLE = Set.of(ReviewDraftRecheck.SOURCE_CHANGED, InquiryDraftRecheck.FIGURE_UNVERIFIED);

    /** What the seller decided on a hold: 「확인하고 진행」, or 「이번 건만 예외」 for a promise nothing backs. */
    public enum Decision { CONFIRM, EXCEPTION }
    /** Drafted and not yet confirmed; confirmed and not yet sent (an approved answer, held only on a substantive conflict). */
    static final Set<InquiryWorkItemPhase> WAITING =
            Set.of(InquiryWorkItemPhase.PROPOSED, InquiryWorkItemPhase.ACTION_PENDING);

    /**
     * What a recheck found, in the shape both lanes share; also the hold's stored note. {@code sentences}: the reply's own
     * sentences a finding is in; {@code figures}: the inquiry figures that did not stand; {@code conflicts}: the promise
     * words a policy says otherwise of (the rest of {@code promises} are unbacked). Notes written before any of these
     * was kept read back with empty lists — an older promise finding then reads as unbacked, never as confirmable.
     */
    public record Finding(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                          List<String> sentences, List<InquiryDraftRecheck.FigureNote> figures, List<String> conflicts,
                          List<ReviewDraftRecheck.Against> against, InquiryDraftRecheck.SellerCheckNote sellerCheck) {
        public Finding {
            codes = codes == null ? List.of() : codes;
            changed = changed == null ? List.of() : changed;
            promises = promises == null ? List.of() : promises;
            phrases = phrases == null ? List.of() : phrases;
            sentences = sentences == null ? List.of() : sentences;
            figures = figures == null ? List.of() : figures;
            conflicts = conflicts == null ? List.of() : conflicts;
            against = against == null ? List.of() : against;
        }

        public Finding(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                       List<String> sentences, List<InquiryDraftRecheck.FigureNote> figures, List<String> conflicts,
                       List<ReviewDraftRecheck.Against> against) {
            this(codes, changed, promises, phrases, sentences, figures, conflicts, against, null);
        }

        public Finding(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                       List<String> sentences, List<InquiryDraftRecheck.FigureNote> figures, List<String> conflicts) {
            this(codes, changed, promises, phrases, sentences, figures, conflicts, List.of(), null);
        }

        public Finding(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                       List<String> sentences, List<InquiryDraftRecheck.FigureNote> figures) {
            this(codes, changed, promises, phrases, sentences, figures, List.of(), List.of(), null);
        }

        public Finding(List<String> codes, List<String> changed, List<String> promises, List<String> phrases) {
            this(codes, changed, promises, phrases, List.of(), List.of(), List.of(), List.of(), null);
        }

        /** The promise words nothing registered backs either way — what an exception decision names. */
        public List<String> unbacked() {
            return promises.stream().filter(p -> !isLaw(p) && !conflicts.contains(p)).toList();
        }

        public boolean passed() {
            return codes.isEmpty();
        }
    }

    /**
     * One hold as a screen reads it: what to do ({@code FIX} — change the text or the policy first; {@code EXCEPTION} —
     * add the evidence, change the sentence, or decide this one as an exception; {@code CONFIRM} — may go on once
     * confirmed), whether that decision is recorded, a one-line headline, the reasons, and what changed. {@code checkId}
     * names this check — version, basis, approval state and finding — and a decision is sent with it.
     */
    public record HoldView(String lane, UUID subjectId, int draftVersion, boolean approved, List<String> codes,
                           String needs, boolean confirmed, String headline, List<String> reasons, List<String> changed,
                           List<String> sentences, List<InquiryDraftRecheck.FigureNote> figures, Instant checkedAt,
                           String checkId, List<String> unbacked, List<String> conflicts) {
    }

    private final ReviewDraftRecheck reviewRecheck;
    private final InquiryDraftRecheck inquiryRecheck;
    private final ReviewDraftBasis basis;
    private final AnswerStyleService styles;
    private final ReviewRepository reviews;
    private final ReviewReplyDraftRepository reviewDrafts;
    private final ReviewReplyApprovalRepository approvals;
    private final InquiryWorkItemRepository workItems;
    private final InquiryReplyDraftRepository inquiryDrafts;
    private final OrganizationRepository organizations;
    private final InquiryApprovalRepository inquiryApprovals;
    private final ReplyDraftHoldRepository holds;
    private final DraftRecheckBasisRepository swept;
    private final ReplyHoldWriter writer;
    private final ReplyHoldConfirmationRepository confirmations;

    public ReplyHoldService(ReviewDraftRecheck reviewRecheck, InquiryDraftRecheck inquiryRecheck, ReviewDraftBasis basis,
                            AnswerStyleService styles, ReviewRepository reviews, ReviewReplyDraftRepository reviewDrafts,
                            ReviewReplyApprovalRepository approvals, InquiryWorkItemRepository workItems,
                            InquiryReplyDraftRepository inquiryDrafts, OrganizationRepository organizations,
                            InquiryApprovalRepository inquiryApprovals, ReplyDraftHoldRepository holds,
                            DraftRecheckBasisRepository swept, ReplyHoldWriter writer,
                            ReplyHoldConfirmationRepository confirmations) {
        this.reviewRecheck = reviewRecheck;
        this.inquiryRecheck = inquiryRecheck;
        this.basis = basis;
        this.styles = styles;
        this.reviews = reviews;
        this.reviewDrafts = reviewDrafts;
        this.approvals = approvals;
        this.workItems = workItems;
        this.inquiryDrafts = inquiryDrafts;
        this.organizations = organizations;
        this.inquiryApprovals = inquiryApprovals;
        this.holds = holds;
        this.swept = swept;
        this.writer = writer;
        this.confirmations = confirmations;
    }

    /**
     * Which drafts a sweep walks and how. Part of the basis, so a change to the sweep itself — a lane or a phase added —
     * sweeps again once, as a change to the claim guard's words does through {@link ReviewDraftBasis}.
     */
    static final String SWEEP = "h5:review,inquiry,confirmed,figures,all-authors,conflicts,every-rule";

    /** The basis an org's drafts are checked under now: review knowledge, policy and wording, and the inquiry style. */
    public String basisOf(UUID orgId) {
        return basis.of(orgId).combined() + "|s" + styles.profileFor(orgId).version() + "|" + SWEEP;
    }

    // ── gates ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Why this review draft may not be approved now, or empty. Records or clears its hold either way. */
    public Optional<String> approvalBlock(UUID orgId, Review review, ReviewReplyDraft head) {
        Finding found = checkReview(orgId, review, head, false);
        return refusal(orgId, ReplyDraftHold.Lane.REVIEW, review.getId(), head.getVersion(), false, found,
                "고친 뒤 승인해 주세요.", "승인해 주세요.");
    }

    /** Why this approved review reply may not be registered now, or empty. Never changes the reply. */
    public Optional<String> registrationBlock(UUID orgId, Review review, ReviewReplyApproval approval) {
        if (approval.getApprovedVersion() == null) {
            return Optional.empty();
        }
        Optional<ReviewReplyDraft> approved = reviewDrafts.findByReviewIdAndVersion(review.getId(),
                approval.getApprovedVersion());
        if (approved.isEmpty()) {
            return Optional.empty();
        }
        Finding found = checkReview(orgId, review, approved.get(), true);
        return refusal(orgId, ReplyDraftHold.Lane.REVIEW, review.getId(), approved.get().getVersion(), true, found,
                "답글을 고친 뒤 다시 승인해 주세요.", "등록해 주세요.");
    }

    /** Why this inquiry draft may not be confirmed (approved and sent) now, or empty. */
    public Optional<String> inquiryApprovalBlock(InquiryWorkItem item, InquiryReplyDraft head) {
        Finding found = checkInquiry(item, head, false);
        return refusal(item.getOrgId(), ReplyDraftHold.Lane.INQUIRY, item.getId(), head.getVersion(), false, found,
                "답변을 고친 뒤 확정해 주세요.", "확정해 주세요.");
    }

    /** Why this confirmed inquiry answer may not be sent now, or empty. Never changes the answer. */
    public Optional<String> inquiryRegistrationBlock(InquiryWorkItem item, InquiryReplyDraft approved) {
        Finding found = checkInquiry(item, approved, true);
        return refusal(item.getOrgId(), ReplyDraftHold.Lane.INQUIRY, item.getId(), approved.getVersion(), true, found,
                "답변을 고친 뒤 다시 확정해 주세요.", "다시 보내 주세요.");
    }

    /**
     * One inquiry checked now, as a sweep would check it, and its hold written or cleared — for a change that is not
     * the org's basis: the seller's record of what they found (2026-11-02), which belongs to this inquiry alone. Returns
     * the hold standing on it after the check, or null.
     */
    @Transactional
    public HoldView recheckInquiry(UUID orgId, UUID workItemId) {
        Checked now = checkInquiryNow(orgId, workItemId);
        if (now == null || now.found().passed()) {
            return null;
        }
        return holdFor(orgId, ReplyDraftHold.Lane.INQUIRY, workItemId);
    }

    /** As {@link #decide} with {@link Decision#CONFIRM}. */
    @Transactional
    public HoldView confirm(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int draftVersion, String checkId,
                            UUID userId) {
        return decide(orgId, lane, subjectId, draftVersion, checkId, Decision.CONFIRM, userId);
    }

    /**
     * The seller read the hold's reasons and decided (2026-11-01 review): checked again now, and recorded against this
     * version, basis, approval state and finding — only when that is still the check the seller read ({@code checkId}).
     * When it is not, nothing is recorded and the refusal says the reasons changed; the hold now on the reply carries the
     * new ones. A finding that must be fixed takes no decision; an unbacked promise takes {@link Decision#EXCEPTION}
     * only; nothing is recorded when the draft now passes.
     */
    @Transactional
    public HoldView decide(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int draftVersion, String checkId,
                           Decision decision, UUID userId) {
        Checked now = lane == ReplyDraftHold.Lane.REVIEW ? checkReviewNow(orgId, subjectId)
                : checkInquiryNow(orgId, subjectId);
        if (now == null || now.version() != draftVersion) {
            throw ApiException.conflict("확인하려던 답변이 그 사이 바뀌었어요. 새로고침한 뒤 다시 확인해 주세요.");
        }
        if (now.found().passed()) {
            return null;
        }
        String basisNow = basisOf(orgId);
        if (checkId == null || !checkId.equals(checkIdOf(lane, subjectId, draftVersion, now.approved(), basisNow,
                now.found()))) {
            // What the seller read is not what stands now — a policy moved while the screen was open.
            throw ApiException.conflict("보고 계시던 사이 확인할 내용이 바뀌었어요. 새 이유를 확인한 뒤 다시 결정해 주세요.");
        }
        if (mustFix(now.found())) {
            throw ApiException.conflict(String.join(" ", reasonsOf(now.found()))
                    + " 이 부분은 확인만으로 진행할 수 없어요. 고친 뒤 다시 시도해 주세요.");
        }
        boolean exception = needsException(now.found());
        if (exception && decision != Decision.EXCEPTION) {
            throw ApiException.conflict("근거 없는 약속(" + quoted(now.found().unbacked()) + ")은 확인만으로 진행할 수 없어요. "
                    + "근거를 추가하거나 문장을 고치거나, 이번 건만 예외로 결정해 주세요.");
        }
        if (!exception && decision == Decision.EXCEPTION) {
            throw ApiException.conflict("예외로 결정할 약속이 없어요. 내용을 확인했다면 「확인하고 진행」을 눌러 주세요.");
        }
        ReplyHoldConfirmation row = new ReplyHoldConfirmation();
        row.setOrgId(orgId);
        row.setLane(lane);
        row.setSubjectId(subjectId);
        row.setDraftVersion(draftVersion);
        row.setApproved(now.approved());
        row.setCodes(String.join(",", now.found().codes()));
        row.setFindingKey(keyOf(now.found()));
        row.setBasis(basisNow);
        row.setDecision(decision);
        row.setExcepted(exception ? cut(String.join(",", now.found().unbacked()), 500) : null);
        row.setConfirmedBy("SELLER:" + userId);
        confirmations.save(row);
        return view(lane, subjectId, draftVersion, now.approved(), now.found(), true, Instant.now(), basisNow);
    }

    /** One subject checked as a sweep would: the version that waits, and what it found. */
    private record Checked(int version, boolean approved, Finding found) {
    }

    private Checked checkReviewNow(UUID orgId, UUID reviewId) {
        Review review = reviews.findById(reviewId).filter(r -> orgId.equals(r.getOrgId())).orElse(null);
        if (review == null) {
            return null;
        }
        Optional<ReviewReplyApproval> approval = approvals.findByOrgIdAndReviewId(orgId, reviewId)
                .filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED && a.getApprovedVersion() != null);
        Optional<ReviewReplyDraft> version = approval.isPresent()
                ? reviewDrafts.findByReviewIdAndVersion(reviewId, approval.get().getApprovedVersion())
                : reviewDrafts.findTopByReviewIdOrderByVersionDesc(reviewId);
        return version.map(v -> new Checked(v.getVersion(), approval.isPresent(),
                checkReview(orgId, review, v, approval.isPresent()))).orElse(null);
    }

    private Checked checkInquiryNow(UUID orgId, UUID workItemId) {
        InquiryWorkItem item = workItems.findById(workItemId).filter(w -> orgId.equals(w.getOrgId())).orElse(null);
        if (item == null || !WAITING.contains(item.getPhase())) {
            return null;
        }
        boolean confirmed = item.getPhase() == InquiryWorkItemPhase.ACTION_PENDING;
        return versionOf(item, confirmed).map(v -> new Checked(v.getVersion(), confirmed,
                checkInquiry(item, v, confirmed))).orElse(null);
    }

    private Optional<InquiryReplyDraft> versionOf(InquiryWorkItem item, boolean confirmed) {
        return confirmed
                ? inquiryApprovals.findByWorkItemId(item.getId()).flatMap(a ->
                        inquiryDrafts.findByWorkItemIdAndVersion(item.getId(), a.getApprovedDraftVersion()))
                : inquiryDrafts.findTopByWorkItemIdOrderByVersionDesc(item.getId());
    }

    /** The refusal for a finding, or empty: nothing found, or only what the seller has already confirmed. */
    private Optional<String> refusal(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int version,
                                     boolean approved, Finding found, String fixThen, String confirmThen) {
        if (found.passed()) {
            return Optional.empty();
        }
        // The headline is the screen's; a refusal says the reasons once.
        String reasons = "다시 확인이 필요해요. " + String.join(" ", reasonsOf(found));
        if (mustFix(found)) {
            return Optional.of(reasons + " " + fixThen);
        }
        if (decided(orgId, lane, subjectId, version, approved, basisOf(orgId), found)) {
            return Optional.empty();
        }
        if (needsException(found)) {
            return Optional.of(reasons + " 근거를 추가하거나 문장을 고쳐 주세요. 이번 답변에만 그대로 쓰려면 「이번 건만 예외」로 "
                    + "결정한 뒤 " + confirmThen + " 결정한 기록이 남아요.");
        }
        return Optional.of(reasons + " 내용을 확인했다면 「확인하고 진행」을 누른 뒤 " + confirmThen
                + " 확인한 기록이 남아요.");
    }

    /**
     * Whether the seller's decision on this very finding is recorded: for an unbacked promise only an exception that
     * names it counts, for the rest a confirmation or an exception does.
     */
    private boolean decided(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int version, boolean approved,
                            String basis, Finding found) {
        boolean exception = needsException(found);
        return confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(orgId,
                        lane, subjectId, version, approved, basis, keyOf(found)).stream()
                .anyMatch(row -> !exception || row.getDecision() == Decision.EXCEPTION);
    }

    // ── sweep ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Every org whose basis moved since its last sweep. */
    public void sweepAll() {
        for (Organization org : organizations.findAll()) {
            try {
                sweep(org.getId());
            } catch (RuntimeException e) {
                // One org's failure is that org's: the others are still checked. Nothing is held on a failed check.
                log.warn("reply recheck sweep failed org={} cause={}", org.getId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Check every waiting draft of one org, when its basis differs from the one it was last swept under. Returns how
     * many drafts were checked (0 when the basis had not moved).
     */
    @Transactional
    public int sweep(UUID orgId) {
        String now = basisOf(orgId);
        DraftRecheckBasis last = swept.findById(orgId).orElse(null);
        if (last != null && last.getBasis().equals(now)) {
            return 0;
        }
        int checked = 0;
        for (UUID reviewId : reviewDrafts.findReviewIdsByOrgId(orgId)) {
            Review review = reviews.findById(reviewId).orElse(null);
            ReviewReplyDraft head = reviewDrafts.findTopByReviewIdOrderByVersionDesc(reviewId).orElse(null);
            if (review == null || head == null || ReviewReplyState.ANSWERED.equals(review.getReplyState())) {
                continue;
            }
            Optional<ReviewReplyApproval> approval = approvals.findByOrgIdAndReviewId(orgId, reviewId)
                    .filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED);
            if (approval.isPresent() && approval.get().getApprovedVersion() != null) {
                reviewDrafts.findByReviewIdAndVersion(reviewId, approval.get().getApprovedVersion())
                        .ifPresent(v -> checkReview(orgId, review, v, true));
            } else {
                checkReview(orgId, review, head, false);
            }
            checked++;
        }
        for (InquiryWorkItem item : workItems.findByOrgIdAndPhaseIn(orgId, WAITING)) {
            boolean confirmed = item.getPhase() == InquiryWorkItemPhase.ACTION_PENDING;
            Optional<InquiryReplyDraft> version = versionOf(item, confirmed);
            if (version.isPresent()) {
                checkInquiry(item, version.get(), confirmed);
                checked++;
            }
        }
        DraftRecheckBasis row = last == null ? new DraftRecheckBasis() : last;
        row.setOrgId(orgId);
        row.setBasis(now);
        row.setCheckedAt(Instant.now());
        swept.save(row);
        return checked;
    }

    /** The hold standing on one reply now, or null — what the reply screen shows above it. */
    @Transactional(readOnly = true)
    public HoldView holdFor(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId) {
        return holds.findByOrgIdAndLaneAndSubjectId(orgId, lane, subjectId)
                .flatMap(hold -> standing(orgId, hold)).orElse(null);
    }

    /** The holds standing on the drafts as they are now — a hold on an older version is not one. */
    @Transactional(readOnly = true)
    public List<HoldView> list(UUID orgId) {
        List<HoldView> out = new ArrayList<>();
        for (ReplyDraftHold hold : holds.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            standing(orgId, hold).ifPresent(out::add);
        }
        return out;
    }

    private Optional<HoldView> standing(UUID orgId, ReplyDraftHold hold) {
        // An approved hold stands on the approved version, which need not be the head.
        int head = hold.getLane() == ReplyDraftHold.Lane.REVIEW
                ? reviewDrafts.findTopByReviewIdOrderByVersionDesc(hold.getSubjectId())
                        .map(ReviewReplyDraft::getVersion).orElse(-1)
                : inquiryDrafts.findTopByWorkItemIdOrderByVersionDesc(hold.getSubjectId())
                        .map(InquiryReplyDraft::getVersion).orElse(-1);
        if (head != hold.getDraftVersion() && !hold.isApproved()) {
            return Optional.empty();
        }
        Finding found = read(hold.getNote());
        boolean confirmed = !mustFix(found) && decided(orgId, hold.getLane(), hold.getSubjectId(),
                hold.getDraftVersion(), hold.isApproved(), hold.getBasis(), found);
        return Optional.of(view(hold.getLane(), hold.getSubjectId(), hold.getDraftVersion(), hold.isApproved(),
                found, confirmed, hold.getUpdatedAt(), hold.getBasis()));
    }

    // ── checks ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private Finding checkReview(UUID orgId, Review review, ReviewReplyDraft version, boolean approved) {
        ReviewDraftRecheck.Result result = reviewRecheck.check(orgId, review,
                VocPreviewSanitizer.redactFullBody(review.getBody()).text(), version.getBody());
        Finding found = new Finding(result.codes(),
                result.changed().stream().map(ReviewDraftRecheck.Changed::title).toList(), result.promises(),
                result.phrases(), result.sentences(), List.of(), result.conflicts(), result.against());
        return record(orgId, ReplyDraftHold.Lane.REVIEW, review.getId(), version.getVersion(), approved, found);
    }

    private Finding checkInquiry(InquiryWorkItem item, InquiryReplyDraft version, boolean approved) {
        InquiryDraftRecheck.Result result = inquiryRecheck.check(item, version.getVersion(), version.getComments(),
                version.getAuthorKind(), inquiryRecheck.writtenFrom(version));
        List<String> marked = new ArrayList<>(result.promises());
        marked.addAll(result.phrases());
        if (result.sellerCheck() != null) {
            marked.addAll(result.sellerCheck().completions());
        }
        result.figures().forEach(f -> marked.add(f.figure()));
        Finding found = new Finding(result.codes(), result.changed(), result.promises(), result.phrases(),
                ReviewDraftRecheck.sentencesWith(version.getComments(), marked), result.figures(), result.conflicts(),
                result.against(), result.sellerCheck());
        return record(item.getOrgId(), ReplyDraftHold.Lane.INQUIRY, item.getId(), version.getVersion(), approved, found);
    }

    private Finding record(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int version, boolean approved,
                           Finding found) {
        // An approved reply is not held for a changed citation alone, as on the PC; everything else holds it.
        Finding kept = approved && found.codes().contains(CITATION_ONLY)
                ? new Finding(found.codes().stream().filter(c -> !CITATION_ONLY.equals(c)).toList(), found.changed(),
                        found.promises(), found.phrases(), found.sentences(), found.figures(), found.conflicts(),
                        found.against(), found.sellerCheck())
                : found;
        if (kept.passed()) {
            writer.clear(orgId, lane, subjectId);
        } else {
            writer.hold(orgId, lane, subjectId, version, approved, String.join(",", kept.codes()), write(kept),
                    basisOf(orgId));
        }
        return kept;
    }

    /**
     * A finding no decision goes past: a banned phrase, a promise a policy says otherwise of, a number now wrong, a
     * condition the law does not allow.
     */
    static boolean mustFix(Finding found) {
        return found.codes().stream().anyMatch(FIX_FIRST::contains) || !found.conflicts().isEmpty()
                || found.promises().stream().anyMatch(ReplyHoldService::isLaw);
    }

    /** A promise nothing registered backs either way: only an exception decision, a new ground or an edit goes past it. */
    static boolean needsException(Finding found) {
        return !mustFix(found) && found.codes().contains(ReviewDraftRecheck.UNSUPPORTED_PROMISE)
                && !found.unbacked().isEmpty();
    }

    /** The findings a plain confirmation goes past. */
    static List<String> toConfirm(Finding found) {
        return found.codes().stream().filter(CONFIRMABLE::contains).toList();
    }

    private static boolean isLaw(String promise) {
        return ReviewClaimGuard.LAW_WITHDRAWAL.equals(promise) || ReviewClaimGuard.LAW_FAULT.equals(promise);
    }

    /** What a confirmation is bound to: the codes, the changed titles, the words and the figures — not their order. */
    static String keyOf(Finding found) {
        String key = String.join(",", found.codes().stream().sorted().toList()) + "|"
                + String.join(",", found.changed().stream().sorted().toList()) + "|"
                + String.join(",", found.promises().stream().sorted().toList()) + "|"
                + String.join(",", found.conflicts().stream().sorted().toList()) + "|"
                + String.join(",", found.against().stream().map(a -> a.word() + "@" + a.title()).sorted().toList()) + "|"
                + String.join(",", found.figures().stream().map(f -> f.figure() + ":" + f.verdict()).sorted().toList());
        // Appended only when there is a seller record, so every key written before records existed is unchanged.
        InquiryDraftRecheck.SellerCheckNote sc = found.sellerCheck();
        if (sc != null) {
            key += "|sc:" + sc.was() + ">" + sc.now() + ":" + String.join(",", sc.completions().stream().sorted().toList());
        }
        return sha256(key);
    }

    /**
     * The name of one check as the seller was shown it: which reply and version, approved or not, under which basis,
     * with which finding. A decision is recorded only when it is sent with the name of the check standing now.
     */
    static String checkIdOf(ReplyDraftHold.Lane lane, UUID subjectId, int version, boolean approved, String basis,
                            Finding found) {
        return sha256(lane.name() + "|" + subjectId + "|" + version + "|" + approved + "|" + basis + "|" + keyOf(found));
    }

    private static String sha256(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HoldView view(ReplyDraftHold.Lane lane, UUID subjectId, int version, boolean approved, Finding found,
                                 boolean confirmed, Instant at, String basis) {
        String needs = mustFix(found) ? "FIX" : needsException(found) ? "EXCEPTION" : "CONFIRM";
        return new HoldView(lane.name(), subjectId, version, approved, found.codes(), needs, confirmed, headline(found),
                reasonsOf(found), found.changed(), found.sentences(), found.figures(), at,
                checkIdOf(lane, subjectId, version, approved, basis, found), found.unbacked(), found.conflicts());
    }

    /**
     * The one line above a held reply: what moved, when something did — 「교환 정책이 바뀌어 다시 확인이 필요해요」 — else
     * that the reply does not stand on what is registered now.
     */
    static String headline(Finding found) {
        List<String> named = found.changed().stream().filter(t -> t != null && !t.isBlank()).toList();
        boolean moved = found.codes().contains(ReviewDraftRecheck.SOURCE_CHANGED)
                || found.figures().stream().anyMatch(f -> f.was() != null);
        if (found.codes().contains(InquiryDraftRecheck.SELLER_CHECK_COMPLETION)) {
            return "판매자 확인 기록과 다르게 끝난 일로 안내하고 있어 고쳐야 해요.";
        }
        if (found.codes().contains(InquiryDraftRecheck.SELLER_CHECK_CHANGED)) {
            return "판매자 확인 기록이 바뀌어 답변을 다시 확인해야 해요.";
        }
        if (moved && !named.isEmpty()) {
            return "「" + named.get(0) + "」" + (named.size() > 1 ? " 등 " + named.size() + "개 자료가" : "이(가)")
                    + " 바뀌어 다시 확인이 필요해요.";
        }
        if (moved) {
            return "답변의 근거였던 자료가 바뀌어 다시 확인이 필요해요.";
        }
        if (found.codes().contains(ReviewDraftRecheck.FORBIDDEN_PHRASE) && found.codes().size() == 1) {
            return "쓰지 않기로 한 표현이 있어 고쳐야 해요.";
        }
        if (!found.conflicts().isEmpty()) {
            return "등록된 정책과 다르게 안내하고 있어 고쳐야 해요.";
        }
        if (!found.unbacked().isEmpty()) {
            return "등록된 정책·자료에 근거가 없는 약속이 있어요.";
        }
        return "지금 등록된 정책·자료로 다시 확인이 필요해요.";
    }

    /** The seller's sentences for a finding. Names titles, words and phrases; never the customer's text. */
    static List<String> reasonsOf(Finding found) {
        List<String> out = new ArrayList<>();
        InquiryDraftRecheck.SellerCheckNote sc = found.sellerCheck();
        if (found.codes().contains(InquiryDraftRecheck.SELLER_CHECK_CHANGED)) {
            String now = sc == null ? null : outcomeKo(sc.now());
            String was = sc == null ? null : outcomeKo(sc.was());
            if (now == null) {
                out.add("이 답변의 근거였던 판매자 확인 기록을 지금은 찾을 수 없어요. 확인한 결과를 다시 적어 주세요.");
            } else if (was == null) {
                out.add("이 답변은 판매자 확인 기록(「" + now + "」)을 적기 전에 만든 답변이에요. 이 기록으로 답변을 다시 만들거나 "
                        + "직접 고쳐 주세요.");
            } else if (was.equals(now)) {
                out.add("이 답변을 만든 뒤 판매자 확인 기록을 다시 적었어요(「" + now + "」). 바뀐 내용으로 답변을 다시 만들거나 "
                        + "직접 고쳐 주세요.");
            } else {
                out.add("이 답변은 판매자 확인 기록이 「" + was + "」일 때 만든 답변인데, 지금 기록은 「" + now + "」이에요. "
                        + "지금 기록으로 답변을 다시 만들거나 직접 고쳐 주세요.");
            }
        }
        if (found.codes().contains(InquiryDraftRecheck.SELLER_CHECK_COMPLETION) && sc != null) {
            String now = outcomeKo(sc.now());
            out.add("답변이 " + quoted(sc.completions()) + "처럼 끝난 일로 쓰고 있지만, 판매자 확인 기록은 「"
                    + (now == null ? "" : now) + "」이에요. 실제로 끝났다면 확인한 결과를 먼저 고쳐 주세요. "
                    + "이 부분은 예외로 넘길 수 없어요.");
        }
        if (found.codes().contains(ReviewDraftRecheck.SOURCE_CHANGED)) {
            out.add(found.changed().isEmpty() ? "초안이 인용한 자료가 그 뒤 바뀌었거나 빠졌어요."
                    : "초안이 인용한 자료(" + quoted(found.changed()) + ")가 그 뒤 바뀌었거나 빠졌어요.");
        }
        if (found.codes().contains(ReviewDraftRecheck.UNSUPPORTED_PROMISE)) {
            List<String> words = new ArrayList<>();
            boolean law = false;
            for (String p : found.promises()) {
                if (ReviewClaimGuard.LAW_WITHDRAWAL.equals(p) || ReviewClaimGuard.LAW_FAULT.equals(p)) {
                    law = true;
                } else {
                    words.add(p);
                }
            }
            if (law) {
                out.add("반품·환불을 개봉 여부로 제한하거나 불량·오배송에 단순 변심 조건을 붙인 안내가 있어 법정 청약철회 기준과 "
                        + "맞지 않을 수 있어요.");
            }
            List<String> against = words.stream().filter(found.conflicts()::contains).toList();
            List<String> unbacked = words.stream().filter(w -> !found.conflicts().contains(w)).toList();
            // Which document says otherwise, when one was read on its own (2026-11-01 fourth review); and when the reply's own
            // ground says it the reply's way, the documents disagree with each other and which applies is the seller's.
            List<String> clashing = found.against().stream().filter(ReviewDraftRecheck.Against::clash)
                    .map(ReviewDraftRecheck.Against::word).distinct().toList();
            List<String> plain = against.stream().filter(w -> !clashing.contains(w)).toList();
            if (!clashing.isEmpty()) {
                out.add("인용한 자료와 다른 정책(" + quoted(titlesFor(found, clashing)) + ")이 " + quoted(clashing)
                        + " 부분을 서로 다르게 말해요. 이 경우에 어느 정책이 적용되는지 정책을 정리해 주세요.");
            }
            if (!plain.isEmpty()) {
                List<String> titles = titlesFor(found, plain);
                out.add("등록된 정책" + (titles.isEmpty() ? "" : "(" + quoted(titles) + ")") + "은 " + quoted(plain)
                        + " 부분을 다르게 말하고 있어요. 문장을 고치거나, 정책이 바뀌었다면 정책을 먼저 고쳐 주세요.");
            }
            if (!unbacked.isEmpty() || !law && against.isEmpty()) {
                out.add(unbacked.isEmpty() ? "지금 등록된 정책·안내에는 초안의 약속을 뒷받침하는 내용이 없어요."
                        : "지금 등록된 정책·안내에는 " + quoted(unbacked) + " 부분의 근거가 없어요.");
            }
        }
        if (found.codes().contains(ReviewDraftRecheck.FORBIDDEN_PHRASE)) {
            out.add("회사가 쓰지 않기로 한 표현(" + quoted(found.phrases()) + ")이 들어 있어요.");
        }
        List<String> wrong = found.figures().stream()
                .filter(f -> InquiryDraftRecheck.FIGURE_CHANGED.equals(f.verdict())).map(InquiryDraftRecheck.FigureNote::figure)
                .toList();
        List<String> edited = found.figures().stream()
                .filter(f -> InquiryDraftRecheck.FIGURE_UNVERIFIED.equals(f.verdict())
                        && InquiryDraftRecheck.EDITED.equals(f.source()))
                .map(InquiryDraftRecheck.FigureNote::figure).toList();
        List<String> unproven = found.figures().stream()
                .filter(f -> InquiryDraftRecheck.FIGURE_UNVERIFIED.equals(f.verdict())
                        && !InquiryDraftRecheck.EDITED.equals(f.source()))
                .map(InquiryDraftRecheck.FigureNote::figure).toList();
        if (!wrong.isEmpty()) {
            out.add("답변의 " + quoted(wrong) + "이(가) 지금 자료에는 없어요. 바뀐 내용에 맞게 고쳐야 해요.");
        }
        if (!edited.isEmpty()) {
            out.add("고친 문장의 " + quoted(edited) + "은(는) 처음 확인한 근거와 같은 뜻인지 자료만으로는 알 수 없어요.");
        }
        if (!unproven.isEmpty()) {
            out.add("답변의 " + quoted(unproven) + "이(가) 지금도 맞는지 자료만으로는 확인할 수 없어요.");
        }
        return out;
    }

    private static String outcomeKo(String outcome) {
        com.sellerops.inquiry.sellercheck.SellerCheckOutcome o =
                com.sellerops.inquiry.sellercheck.SellerCheckOutcome.parse(outcome);
        return o == null ? null : o.labelKo();
    }

    private static List<String> titlesFor(Finding found, List<String> words) {
        return found.against().stream().filter(a -> words.contains(a.word())).map(ReviewDraftRecheck.Against::title)
                .filter(t -> !t.isBlank()).distinct().toList();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String quoted(List<String> items) {
        return String.join(" · ", items.stream().limit(3).map(i -> "「" + i + "」").toList());
    }

    private static String write(Finding found) {
        try {
            return JSON.writeValueAsString(found);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Finding read(String note) {
        try {
            return JSON.readValue(note, Finding.class);
        } catch (JsonProcessingException e) {
            return new Finding(List.of(), List.of(), List.of(), List.of());
        }
    }
}
