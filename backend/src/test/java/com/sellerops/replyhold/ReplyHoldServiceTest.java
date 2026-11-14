package com.sellerops.replyhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyApproval;
import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewClaimGuard;
import com.sellerops.review.draft.ReviewDraftBasis;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The web recheck's rules (2026-11-01 reviews: 「웹 자동 재검사」, then 「작성자에 따른 일괄 예외는 없애는 게 좋습니다」,
 * then the owner's 「UNSUPPORTED_PROMISE를 일반 확인으로 우회하지 못하게」): a pass keeps the text and clears the hold; a
 * failure holds the version with its reasons whoever wrote it; a banned phrase, a policy conflict or a condition the law
 * forbids must be fixed; a promise nothing backs takes an explicit exception, never a plain confirmation, on the approval
 * and on the send alike; the rest may be confirmed past; every decision is bound to the check the seller read; an approved
 * reply is not held for a changed citation alone; the same basis is not swept twice. The checks themselves are
 * {@code ReviewDraftRecheck}'s and {@code InquiryDraftRecheck}'s and are stubbed here.
 */
class ReplyHoldServiceTest {

    private final UUID org = UUID.randomUUID();
    private final ReviewDraftRecheck reviewRecheck = mock(ReviewDraftRecheck.class);
    private final ReviewDraftBasis basis = mock(ReviewDraftBasis.class);
    private final AnswerStyleService styles = mock(AnswerStyleService.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewReplyDraftRepository drafts = mock(ReviewReplyDraftRepository.class);
    private final ReviewReplyApprovalRepository approvals = mock(ReviewReplyApprovalRepository.class);
    private final InquiryWorkItemRepository workItems = mock(InquiryWorkItemRepository.class);
    private final DraftRecheckBasisRepository swept = mock(DraftRecheckBasisRepository.class);
    private final ReplyHoldWriter writer = mock(ReplyHoldWriter.class);
    private final ReplyHoldConfirmationRepository confirmations = mock(ReplyHoldConfirmationRepository.class);
    private ReplyHoldService service;
    private Review review;

    @BeforeEach
    void setUp() {
        service = new ReplyHoldService(reviewRecheck, mock(InquiryDraftRecheck.class), basis, styles, reviews, drafts,
                approvals, workItems, mock(InquiryReplyDraftRepository.class), mock(OrganizationRepository.class),
                mock(com.sellerops.inquiry.publish.InquiryApprovalRepository.class), mock(ReplyDraftHoldRepository.class),
                swept, writer, confirmations);
        when(basis.of(org)).thenReturn(new ReviewDraftBasis.Fingerprints("v1:k", "v1:p", "v1:v"));
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        review = new Review();
        review.setId(UUID.randomUUID());
        review.setBody("리뷰 본문");
    }

    private ReviewReplyDraft draft(int version, String author) {
        ReviewReplyDraft d = new ReviewReplyDraft();
        d.setReviewId(review.getId());
        d.setVersion(version);
        d.setBody("답글");
        d.setAuthorKind(author);
        return d;
    }

    private void found(List<String> codes, List<String> promises) {
        found(codes, promises, List.of());
    }

    private void found(List<String> codes, List<String> promises, List<String> conflicts) {
        when(reviewRecheck.check(eq(org), eq(review), anyString(), anyString())).thenReturn(new ReviewDraftRecheck.Result(
                codes.isEmpty(), codes, List.of(new ReviewDraftRecheck.Changed("PRODUCT", "배송교환정책", null, false)),
                promises, List.of(), List.of(), conflicts));
    }

    /** The name of the check standing now, as the screen got it with the hold. */
    private String shown(int version, boolean approved, List<String> codes, List<String> promises,
                         List<String> conflicts) {
        return ReplyHoldService.checkIdOf(ReplyDraftHold.Lane.REVIEW, review.getId(), version, approved,
                service.basisOf(org), new ReplyHoldService.Finding(codes, List.of("배송교환정책"), promises, List.of(),
                        List.of(), List.of(), conflicts));
    }

    private ReplyHoldConfirmation onRecord(ReplyHoldService.Decision decision) {
        ReplyHoldConfirmation row = new ReplyHoldConfirmation();
        row.setDecision(decision);
        return row;
    }

    private void waiting(int version) {
        when(approvals.findByOrgIdAndReviewId(org, review.getId())).thenReturn(Optional.empty());
        when(reviews.findById(review.getId())).thenReturn(Optional.of(orgReview()));
        when(drafts.findTopByReviewIdOrderByVersionDesc(review.getId())).thenReturn(Optional.of(draft(version, "SELLER")));
    }

    @Test
    void aDraftThatNoLongerStandsIsHeldWithItsReasons() {
        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.UNSUPPORTED_PROMISE),
                List.of(ReviewClaimGuard.LAW_WITHDRAWAL, "교환"));
        Optional<String> block = service.approvalBlock(org, review, draft(3, "MODEL"));
        assertThat(block).get().asString().contains("「배송교환정책」").contains("법정 청약철회")
                .contains("「교환」").contains("고친 뒤 승인");
        verify(writer).hold(eq(org), eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(3), eq(false),
                eq("SOURCE_CHANGED,UNSUPPORTED_PROMISE"), anyString(), anyString());
    }

    @Test
    void whoeverWroteItTheSameCheckHolds() {
        found(List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of("환불"));
        for (String author : new String[] {"MODEL", "SELLER", "RULE", null}) {
            assertThat(service.approvalBlock(org, review, draft(4, author)))
                    .as("author %s", author).get().asString().contains("답변에 연결된 근거 자료에서 「환불」 부분을 확인하지 못했어요")
                    .contains("「이번 건만 예외」").doesNotContain("「확인하고 진행」");
        }
        verify(writer, times(4)).hold(eq(org), any(), any(), eq(4), eq(false), anyString(), anyString(), anyString());
    }

    @Test
    void aBannedPhraseOrTheLawIsFixedNeverConfirmedPast() {
        waiting(2);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(any(), any(),
                any(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(List.of(onRecord(ReplyHoldService.Decision.EXCEPTION)));

        found(List.of(ReviewDraftRecheck.FORBIDDEN_PHRASE), List.of());
        assertThat(service.approvalBlock(org, review, draft(2, "SELLER")))
                .as("a decision on record does not pass a banned phrase").get().asString().contains("고친 뒤");
        String banned = shown(2, false, List.of(ReviewDraftRecheck.FORBIDDEN_PHRASE), List.of(), List.of());
        assertThatThrownBy(() -> service.confirm(org, ReplyDraftHold.Lane.REVIEW,
                review.getId(), 2, banned, UUID.randomUUID())).hasMessageContaining("확인만으로 진행할 수 없어요");

        found(List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of(ReviewClaimGuard.LAW_FAULT));
        String law = shown(2, false, List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of(ReviewClaimGuard.LAW_FAULT),
                List.of());
        assertThatThrownBy(() -> service.decide(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 2, law,
                ReplyHoldService.Decision.EXCEPTION, UUID.randomUUID())).hasMessageContaining("확인만으로 진행할 수 없어요");
        verify(confirmations, never()).save(any());
    }

    @Test
    void aConfirmationIsRecordedAndBoundToItsFinding() {
        when(approvals.findByOrgIdAndReviewId(org, review.getId())).thenReturn(Optional.empty());
        when(reviews.findById(review.getId())).thenReturn(Optional.of(orgReview()));
        when(drafts.findTopByReviewIdOrderByVersionDesc(review.getId())).thenReturn(Optional.of(draft(6, "MODEL")));
        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED), List.of());

        UUID seller = UUID.randomUUID();
        ReplyHoldService.HoldView view = service.confirm(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 6,
                shown(6, false, List.of(ReviewDraftRecheck.SOURCE_CHANGED), List.of(), List.of()), seller);
        assertThat(view.confirmed()).isTrue();
        assertThat(view.needs()).isEqualTo("CONFIRM");
        ArgumentCaptor<ReplyHoldConfirmation> saved = ArgumentCaptor.forClass(ReplyHoldConfirmation.class);
        verify(confirmations).save(saved.capture());
        assertThat(saved.getValue().getConfirmedBy()).isEqualTo("SELLER:" + seller);
        assertThat(saved.getValue().getDraftVersion()).isEqualTo(6);
        assertThat(saved.getValue().getBasis()).isEqualTo(service.basisOf(org));

        assertThat(saved.getValue().getDecision()).isEqualTo(ReplyHoldService.Decision.CONFIRM);
        String key = saved.getValue().getFindingKey();
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 6, false, service.basisOf(org), key))
                .thenReturn(List.of(saved.getValue()));
        assertThat(service.approvalBlock(org, review, draft(6, "MODEL"))).as("confirmed: approval goes on").isEmpty();

        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of("교환"));
        assertThat(service.approvalBlock(org, review, draft(6, "MODEL")))
                .as("a different finding is not what was confirmed").isPresent();
        assertThatThrownBy(() -> service.confirm(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 5, "x", seller))
                .as("a version the seller did not read").hasMessageContaining("바뀌었어요");
    }

    @Test
    void anUnbackedPromiseTakesAnExceptionNeverAPlainConfirmation() {
        waiting(7);
        List<String> codes = List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        found(codes, List.of("교환"));
        String check = shown(7, false, codes, List.of("교환"), List.of());
        assertThat(service.holdFor(org, ReplyDraftHold.Lane.REVIEW, review.getId())).isNull();

        assertThatThrownBy(() -> service.confirm(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 7, check,
                UUID.randomUUID())).hasMessageContaining("확인만으로 진행할 수 없어요").hasMessageContaining("이번 건만 예외");
        verify(confirmations, never()).save(any());

        // A plain confirmation on record (one written before 2026-11-01's rule) passes nothing: approval and send alike.
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(any(), any(),
                any(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(List.of(onRecord(ReplyHoldService.Decision.CONFIRM)));
        assertThat(service.approvalBlock(org, review, draft(7, "SELLER"))).get().asString().contains("이번 건만 예외");
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setApprovedVersion(7);
        approval.setState(ReviewReplyApprovalState.APPROVED);
        when(drafts.findByReviewIdAndVersion(review.getId(), 7)).thenReturn(Optional.of(draft(7, "MODEL")));
        assertThat(service.registrationBlock(org, review, approval)).as("the send path of an approved reply")
                .get().asString().contains("이번 건만 예외");

        UUID seller = UUID.randomUUID();
        ReplyHoldService.HoldView decided = service.decide(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 7, check,
                ReplyHoldService.Decision.EXCEPTION, seller);
        assertThat(decided.needs()).isEqualTo("EXCEPTION");
        assertThat(decided.unbacked()).containsExactly("교환");
        ArgumentCaptor<ReplyHoldConfirmation> saved = ArgumentCaptor.forClass(ReplyHoldConfirmation.class);
        verify(confirmations).save(saved.capture());
        assertThat(saved.getValue().getDecision()).isEqualTo(ReplyHoldService.Decision.EXCEPTION);
        assertThat(saved.getValue().getExcepted()).isEqualTo("교환");

        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(any(), any(),
                any(), anyInt(), anyBoolean(), any(), any())).thenReturn(List.of(saved.getValue()));
        assertThat(service.approvalBlock(org, review, draft(7, "SELLER"))).as("decided for this one: it goes on")
                .isEmpty();
    }

    @Test
    void aPolicyConflictIsFixedAndNotExceptedPast() {
        waiting(8);
        List<String> codes = List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE, ReviewDraftRecheck.POLICY_CONFLICT);
        found(codes, List.of("14일", "택배비"), List.of("14일"));
        assertThat(service.approvalBlock(org, review, draft(8, "MODEL"))).get().asString()
                .contains("등록된 정책은 「14일」 부분을 다르게 말하고 있어요").contains("답변에 연결된 근거 자료에서 「택배비」 부분을 확인하지 못했어요")
                .contains("고친 뒤 승인");
        String check = shown(8, false, codes, List.of("14일", "택배비"), List.of("14일"));
        assertThatThrownBy(() -> service.decide(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 8, check,
                ReplyHoldService.Decision.EXCEPTION, UUID.randomUUID())).hasMessageContaining("확인만으로 진행할 수 없어요");
        verify(confirmations, never()).save(any());
    }

    @Test
    void twoPoliciesThatDisagreeAreTheSellersToSettleNotToConfirmPast() {
        waiting(10);
        List<String> codes = List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE, ReviewDraftRecheck.POLICY_CONFLICT);
        when(reviewRecheck.check(eq(org), eq(review), anyString(), anyString())).thenReturn(new ReviewDraftRecheck.Result(
                false, codes, List.of(), List.of("교환"), List.of(), List.of(), List.of("교환"),
                List.of(new ReviewDraftRecheck.Against("교환", "반품 · 교환 제한", true))));
        assertThat(service.approvalBlock(org, review, draft(10, "MODEL"))).get().asString()
                .contains("인용한 자료와 다른 정책(「반품 · 교환 제한」)이 「교환」 부분을 서로 다르게 말해요")
                .contains("정책을 정리해 주세요").contains("고친 뒤 승인");
    }

    @Test
    void aDecisionOnACheckTheSellerDidNotReadIsRefused() {
        waiting(9);
        // On screen: a changed source. Meanwhile a policy moved and a promise lost its ground: same version.
        String read = shown(9, false, List.of(ReviewDraftRecheck.SOURCE_CHANGED), List.of(), List.of());
        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of("재발송"));
        assertThatThrownBy(() -> service.confirm(org, ReplyDraftHold.Lane.REVIEW, review.getId(), 9, read,
                UUID.randomUUID())).hasMessageContaining("확인할 내용이 바뀌었어요");
        verify(confirmations, never()).save(any());
        // The hold now on the reply carries the new reasons, written outside the refused request.
        verify(writer).hold(eq(org), eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(9), eq(false),
                eq("SOURCE_CHANGED,UNSUPPORTED_PROMISE"), anyString(), anyString());
    }

    private Review orgReview() {
        review.setOrgId(org);
        return review;
    }

    @Test
    void aPassKeepsTheTextAndClearsTheHold() {
        found(List.of(), List.of());
        assertThat(service.approvalBlock(org, review, draft(2, "MODEL"))).isEmpty();
        verify(writer).clear(org, ReplyDraftHold.Lane.REVIEW, review.getId());
        verify(writer, never()).hold(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    /**
     * 2026-11-10 rehearsal (F3): the seller decided 「이번 건만 예외」 on the draft, approved that very version, and the send
     * asked for the same decision again. The check before the send still runs; what it finds is matched against the
     * decision taken on the same version, basis and finding before the approval — anything else is asked again.
     */
    @Test
    void aDecisionTakenBeforeApprovalCarriesToTheSendOfThatSameVersionOnly() {
        List<String> codes = List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        found(codes, List.of("교환"));
        String key = ReplyHoldService.keyOf(new ReplyHoldService.Finding(codes, List.of("배송교환정책"), List.of("교환"),
                List.of(), List.of(), List.of(), List.of()));
        ReplyHoldConfirmation before = onRecord(ReplyHoldService.Decision.EXCEPTION);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 7, false, service.basisOf(org), key))
                .thenReturn(List.of(before));
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setApprovedVersion(7);
        approval.setState(ReviewReplyApprovalState.APPROVED);
        when(drafts.findByReviewIdAndVersion(review.getId(), 7)).thenReturn(Optional.of(draft(7, "SELLER")));

        assertThat(service.registrationBlock(org, review, approval)).as("decided on this text before approving it")
                .isEmpty();
        verify(reviewRecheck).check(eq(org), eq(review), anyString(), anyString());

        found(codes, List.of("교환", "재발송"));
        assertThat(service.registrationBlock(org, review, approval)).as("the send found something else")
                .get().asString().contains("이번 건만 예외");

        found(codes, List.of("교환"));
        before.setDecision(ReplyHoldService.Decision.CONFIRM);
        assertThat(service.registrationBlock(org, review, approval))
                .as("a plain confirmation never stood for an unbacked promise, before or after approval")
                .get().asString().contains("이번 건만 예외");

        before.setDecision(ReplyHoldService.Decision.EXCEPTION);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 7, false, service.basisOf(org), key))
                .thenReturn(List.of());
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 7, true, service.basisOf(org), key))
                .thenReturn(List.of(before));
        waiting(7);
        assertThat(service.approvalBlock(org, review, draft(7, "SELLER")))
                .as("a decision taken on the send does not reach back to the approval").isPresent();
    }

    /**
     * 2026-11-14 (Galaxy S24, 모의 등록): the draft's check found a changed citation AND an unbacked promise; the seller
     * decided 「이번 건만 예외」 on that and approved it. The send drops the changed citation (an approved reply is not held
     * for it alone), so its finding was another fingerprint and the very same promise was asked a second time. The
     * decision carries — and still only for that promise.
     */
    @Test
    void anExceptionTakenWithAChangedCitationCarriesToTheSendForThatPromiseOnly() {
        List<String> before = List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        String shownKey = ReplyHoldService.keyOf(new ReplyHoldService.Finding(before, List.of("배송교환정책"),
                List.of("교환"), List.of(), List.of(), List.of(), List.of()));
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(7), anyBoolean(), anyString(), anyString()))
                .thenReturn(List.of());
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 7, false, service.basisOf(org), shownKey))
                .thenReturn(List.of(onRecord(ReplyHoldService.Decision.EXCEPTION)));
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setApprovedVersion(7);
        approval.setState(ReviewReplyApprovalState.APPROVED);
        when(drafts.findByReviewIdAndVersion(review.getId(), 7)).thenReturn(Optional.of(draft(7, "SELLER")));

        found(before, List.of("교환"));
        assertThat(service.registrationBlock(org, review, approval))
                .as("the promise decided before approving, with the citation the send no longer counts").isEmpty();

        found(before, List.of("교환", "재발송"));
        assertThat(service.registrationBlock(org, review, approval)).as("a promise nobody decided is asked")
                .get().asString().contains("재발송").contains("이번 건만 예외");

        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.UNSUPPORTED_PROMISE,
                ReviewDraftRecheck.FORBIDDEN_PHRASE), List.of("교환"));
        assertThat(service.registrationBlock(org, review, approval)).as("an exception never waives what must be fixed")
                .get().asString().contains("쓰지 않기로 한 표현");
    }

    @Test
    void anApprovedReplyIsNotHeldForAChangedCitationAlone() {
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setApprovedVersion(5);
        approval.setState(ReviewReplyApprovalState.APPROVED);
        when(drafts.findByReviewIdAndVersion(review.getId(), 5)).thenReturn(Optional.of(draft(5, "MODEL")));

        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED), List.of());
        assertThat(service.registrationBlock(org, review, approval))
                .as("a changed citation alone does not hold an approved reply").isEmpty();

        found(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.FORBIDDEN_PHRASE), List.of());
        assertThat(service.registrationBlock(org, review, approval)).get().asString()
                .contains("쓰지 않기로 한 표현").contains("다시 승인");
        verify(writer).hold(eq(org), eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), eq(true),
                eq("FORBIDDEN_PHRASE"), anyString(), anyString());
    }

    @Test
    void theSameBasisIsNotSweptTwice() {
        DraftRecheckBasis last = new DraftRecheckBasis();
        last.setOrgId(org);
        last.setBasis(ReplyHoldService.sweptUnder(service.basisOf(org)));
        when(swept.findById(org)).thenReturn(Optional.of(last));
        assertThat(service.sweep(org)).isZero();
        verify(drafts, never()).findReviewIdsByOrgId(any());

        last.setBasis("v1:k|v1:p|v1:v|s0|h2:review,inquiry,confirmed");
        // An older sweep scope is an older basis: what the sweep walks changed, so it walks again.
        when(drafts.findReviewIdsByOrgId(org)).thenReturn(List.of(review.getId()));
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));
        when(drafts.findTopByReviewIdOrderByVersionDesc(review.getId())).thenReturn(Optional.of(draft(1, "MODEL")));
        when(approvals.findByOrgIdAndReviewId(org, review.getId())).thenReturn(Optional.empty());
        found(List.of(), List.of());
        assertThat(service.sweep(org)).isOne();
        verify(swept).save(last);
        assertThat(last.getBasis()).isEqualTo(ReplyHoldService.sweptUnder(service.basisOf(org)));
    }

    // ── a cited document withdrawn (사용 안 함 · 삭제) after approval — owner, 2026-11-14 ────────────────────────────────

    /** When 「배송교환정책」 was turned off in the scenes below; a second withdrawal of it happens at {@link #AGAIN}. */
    private static final java.time.Instant OFF = java.time.Instant.parse("2026-11-14T10:00:00Z");
    private static final java.time.Instant AGAIN = java.time.Instant.parse("2026-11-14T11:00:00Z");

    private void withdrawn(List<String> codes) {
        withdrawn(codes, OFF);
    }

    private void withdrawn(List<String> codes, java.time.Instant at) {
        when(reviewRecheck.check(eq(org), eq(review), anyString(), anyString())).thenReturn(new ReviewDraftRecheck.Result(
                codes.isEmpty(), codes, List.of(new ReviewDraftRecheck.Changed("PRODUCT", "배송교환정책", at, true)),
                List.of(), List.of(), List.of(), List.of()));
    }

    private ReviewReplyApproval approvedAt(int version) {
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setApprovedVersion(version);
        approval.setState(ReviewReplyApprovalState.APPROVED);
        when(drafts.findByReviewIdAndVersion(review.getId(), version)).thenReturn(Optional.of(draft(version, "MODEL")));
        return approval;
    }

    private static ReplyHoldService.Finding finding(List<String> codes, List<String> retired) {
        return finding(codes, retired, OFF);
    }

    private static ReplyHoldService.Finding finding(List<String> codes, List<String> retired, java.time.Instant at) {
        return new ReplyHoldService.Finding(codes, List.of("배송교환정책"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, retired,
                retired.isEmpty() ? List.of() : List.of("PRODUCT:배송교환정책@" + at));
    }

    @Test
    void anApprovedReplyWhoseCitedDocumentWasWithdrawnWaitsForTheSellersConfirmation() {
        ReviewReplyApproval approval = approvedAt(5);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), anyBoolean(), anyString(), anyString()))
                .thenReturn(List.of());

        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED));
        assertThat(service.registrationBlock(org, review, approval)).as("withdrawn is not 「changed」 for the send")
                .get().asString().contains("「배송교환정책」").contains("사용 중지했거나 지웠어요").contains("확인하고 진행");
        // Held with the withdrawal only — the wording code is still dropped for an approved reply — and nothing else.
        verify(writer).hold(eq(org), eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), eq(true),
                eq("SOURCE_RETIRED"), anyString(), anyString());
        assertThat(ReplyHoldService.headline(finding(List.of(ReviewDraftRecheck.SOURCE_RETIRED), List.of("배송교환정책"))))
                .contains("「배송교환정책」").contains("사용 중지되거나 지워져");

        // The seller confirms this very withdrawal on the send: it goes on. The approval and the text are untouched.
        String sendKey = ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.SOURCE_RETIRED),
                List.of("배송교환정책")));
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(org,
                ReplyDraftHold.Lane.REVIEW, review.getId(), 5, true, service.basisOf(org), sendKey))
                .thenReturn(List.of(onRecord(ReplyHoldService.Decision.CONFIRM)));
        assertThat(service.registrationBlock(org, review, approval)).as("confirmed").isEmpty();
    }

    @Test
    void aConfirmationTakenBeforeTheWithdrawalNeverStandsForIt() {
        ReviewReplyApproval approval = approvedAt(5);
        // Before approving, the seller confirmed that 「배송교환정책」's wording had changed.
        String editedKey = ReplyHoldService.keyOf(new ReplyHoldService.Finding(List.of(ReviewDraftRecheck.SOURCE_CHANGED),
                List.of("배송교환정책"), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), anyBoolean(), anyString(), anyString()))
                .thenAnswer(inv -> editedKey.equals(inv.getArgument(6)) ? List.of(onRecord(ReplyHoldService.Decision.CONFIRM))
                        : List.of());
        // Then, after approving, they turned the document off.
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED));
        assertThat(service.registrationBlock(org, review, approval))
                .as("a confirmation of the wording is not a confirmation of the withdrawal").isPresent();
        assertThat(ReplyHoldService.beforeApproval(finding(List.of(ReviewDraftRecheck.SOURCE_RETIRED),
                List.of("배송교환정책")))).doesNotContain(editedKey);
    }

    @Test
    void aWithdrawalConfirmedBeforeApprovalCarriesToTheSendOfThatVersion() {
        ReviewReplyApproval approval = approvedAt(5);
        String shownKey = ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.SOURCE_CHANGED,
                ReviewDraftRecheck.SOURCE_RETIRED), List.of("배송교환정책")));
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), anyBoolean(), anyString(), anyString()))
                .thenAnswer(inv -> shownKey.equals(inv.getArgument(6)) && Boolean.FALSE.equals(inv.getArgument(4))
                        ? List.of(onRecord(ReplyHoldService.Decision.CONFIRM)) : List.of());
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED));
        assertThat(service.registrationBlock(org, review, approval))
                .as("the same withdrawal, already confirmed on this version before approving it").isEmpty();
    }

    @Test
    void aWithdrawalNeverLetsWhatMustBeFixedThrough() {
        ReviewReplyApproval approval = approvedAt(5);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), anyBoolean(), anyString(), anyString()))
                .thenReturn(List.of(onRecord(ReplyHoldService.Decision.CONFIRM)));
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED,
                ReviewDraftRecheck.FORBIDDEN_PHRASE));
        assertThat(service.registrationBlock(org, review, approval)).as("fix first, confirmation or not")
                .get().asString().contains("쓰지 않기로 한 표현").contains("다시 승인");
    }

    // ── 2026-11-15: a withdrawal turned back on and off, and data swept before the rule ─────────────────────────────

    @Test
    void aDocumentTurnedBackOnAndOffAgainIsANewWithdrawalToConfirm() {
        ReviewReplyApproval approval = approvedAt(5);
        String firstKey = ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.SOURCE_RETIRED),
                List.of("배송교환정책"), OFF));
        // The seller confirmed the first withdrawal on the send …
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(eq(org),
                eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), anyBoolean(), anyString(), anyString()))
                .thenAnswer(inv -> firstKey.equals(inv.getArgument(6)) ? List.of(onRecord(ReplyHoldService.Decision.CONFIRM))
                        : List.of());
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED), OFF);
        assertThat(service.registrationBlock(org, review, approval)).as("the withdrawal that was confirmed").isEmpty();

        // … then turned the document back on, and off again. Same title, same state, same basis — a new withdrawal.
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED), AGAIN);
        assertThat(service.registrationBlock(org, review, approval))
                .as("a confirmation of the first withdrawal does not stand for the second")
                .get().asString().contains("사용 중지했거나 지웠어요").contains("확인하고 진행");
        assertThat(ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.SOURCE_RETIRED), List.of("배송교환정책"),
                AGAIN))).isNotEqualTo(firstKey);
    }

    /** The key of a finding with no withdrawal, exactly as it was computed before withdrawals existed. */
    private static String legacyKey(ReplyHoldService.Finding f) throws Exception {
        String key = String.join(",", f.codes().stream().sorted().toList()) + "|"
                + String.join(",", f.changed().stream().sorted().toList()) + "|"
                + String.join(",", f.promises().stream().sorted().toList()) + "|"
                + String.join(",", f.conflicts().stream().sorted().toList()) + "|"
                + String.join(",", f.against().stream().map(a -> a.word() + "@" + a.title()).sorted().toList()) + "|"
                + String.join(",", f.figures().stream().map(x -> x.figure() + ":" + x.verdict()).sorted().toList());
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void decisionsRecordedBeforeWithdrawalsExistedStillMatch() throws Exception {
        // A confirmation or exception already on record is bound to this key; a finding without a withdrawal keeps it.
        ReplyHoldService.Finding changed = new ReplyHoldService.Finding(List.of(ReviewDraftRecheck.SOURCE_CHANGED,
                ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of("배송교환정책"), List.of("교환"), List.of(), List.of(),
                List.of(), List.of());
        assertThat(ReplyHoldService.keyOf(changed)).isEqualTo(legacyKey(changed));
        // A hold note stored before withdrawals existed reads back with none.
        String stored = "{\"codes\":[\"SOURCE_CHANGED\"],\"changed\":[\"배송교환정책\"],\"promises\":[],\"phrases\":[],"
                + "\"sentences\":[],\"figures\":[],\"conflicts\":[],\"against\":[],\"sellerCheck\":null}";
        ReplyHoldService.Finding read = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(stored, ReplyHoldService.Finding.class);
        assertThat(read.retired()).isEmpty();
        assertThat(read.withdrawals()).isEmpty();
        assertThat(ReplyHoldService.keyOf(read)).isEqualTo(legacyKey(read));
        // And the basis decisions are bound to does not carry the sweep's rules marker, so it did not move.
        assertThat(service.basisOf(org)).doesNotContain(ReplyHoldService.SWEEP_RULES);
    }

    @Test
    void repliesSweptBeforeTheWithdrawalRuleAreCheckedOnceUnderIt() {
        // Last swept under today's basis, by the rules before 2026-11-15 (no rules marker).
        DraftRecheckBasis last = new DraftRecheckBasis();
        last.setOrgId(org);
        last.setBasis(service.basisOf(org));
        when(swept.findById(org)).thenReturn(Optional.of(last));
        when(drafts.findReviewIdsByOrgId(org)).thenReturn(List.of(review.getId()));
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));
        when(drafts.findTopByReviewIdOrderByVersionDesc(review.getId())).thenReturn(Optional.of(draft(5, "MODEL")));
        ReviewReplyApproval approval = approvedAt(5);
        when(approvals.findByOrgIdAndReviewId(org, review.getId())).thenReturn(Optional.of(approval));
        withdrawn(List.of(ReviewDraftRecheck.SOURCE_CHANGED, ReviewDraftRecheck.SOURCE_RETIRED));

        assertThat(service.sweep(org)).as("checked again although the basis did not move").isOne();
        // The approved reply whose cited document had been withdrawn is held — and shows on the work list as held.
        verify(writer).hold(eq(org), eq(ReplyDraftHold.Lane.REVIEW), eq(review.getId()), eq(5), eq(true),
                eq("SOURCE_RETIRED"), anyString(), anyString());
        assertThat(last.getBasis()).isEqualTo(ReplyHoldService.sweptUnder(service.basisOf(org)));
        assertThat(service.sweep(org)).as("once").isZero();
    }
}
