package com.sellerops.replyhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftBasis;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A registration verified done is past its hold</b> (2026-11-15 owner request: 「등록 완료가 확인되면 현재 등록을 막는 보류
 * 안내는 사라져야 합니다. 과거 확인·예외 결정 이력은 보존합니다. 결과가 아직 불명확하거나 등록이 실패한 경우에는 … 안내를 지우지
 * 않습니다」). The reply screen read the hold row after the answer was registered and still drew 「확인 필요」.
 */
class ReplyHoldRegisteredTest {

    private final UUID org = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final ReplyDraftHoldRepository holdRows = mock(ReplyDraftHoldRepository.class);
    private final InquiryWorkItemRepository workItems = mock(InquiryWorkItemRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReplyHoldConfirmationRepository confirmations = mock(ReplyHoldConfirmationRepository.class);
    private final ReplyHoldWriter writer = mock(ReplyHoldWriter.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private ReplyHoldService service;

    @BeforeEach
    void setUp() {
        ReviewDraftBasis basis = mock(ReviewDraftBasis.class);
        AnswerStyleService styles = mock(AnswerStyleService.class);
        when(basis.of(org)).thenReturn(new ReviewDraftBasis.Fingerprints("v1:k", "v1:p", "v1:v"));
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        service = new ReplyHoldService(mock(ReviewDraftRecheck.class), mock(InquiryDraftRecheck.class), basis, styles,
                reviews, mock(ReviewReplyDraftRepository.class), mock(ReviewReplyApprovalRepository.class), workItems,
                mock(InquiryReplyDraftRepository.class), mock(OrganizationRepository.class),
                mock(com.sellerops.inquiry.publish.InquiryApprovalRepository.class), holdRows,
                mock(DraftRecheckBasisRepository.class), writer, confirmations);
        when(confirmations.findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(any(), any(),
                any(), anyInt(), anyBoolean(), anyString(), anyString())).thenReturn(List.of());
        item.setId(subject);
        item.setOrgId(org);
        when(workItems.findById(subject)).thenReturn(Optional.of(item));
    }

    private void heldRow(ReplyDraftHold.Lane lane) {
        ReplyDraftHold row = new ReplyDraftHold();
        row.setOrgId(org);
        row.setLane(lane);
        row.setSubjectId(subject);
        row.setDraftVersion(2);
        row.setApproved(true);
        row.setCodes(ReviewDraftRecheck.SOURCE_RETIRED);
        row.setNote("{\"codes\":[\"SOURCE_RETIRED\"],\"changed\":[\"텀블러 안내\"],\"retired\":[\"텀블러 안내\"]}");
        row.setBasis(service.basisOf(org));
        when(holdRows.findByOrgIdAndLaneAndSubjectId(org, lane, subject)).thenReturn(Optional.of(row));
        when(holdRows.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("an inquiry answer verified registered shows no standing hold; unknown or failed results still show it")
    void onlyAVerifiedRegistrationIsPastItsHold() {
        heldRow(ReplyDraftHold.Lane.INQUIRY);
        for (InquiryWorkItemPhase still : List.of(InquiryWorkItemPhase.ACTION_PENDING, InquiryWorkItemPhase.EXECUTED,
                InquiryWorkItemPhase.FAILED)) {
            item.setPhase(still);
            assertThat(service.holdFor(org, ReplyDraftHold.Lane.INQUIRY, subject)).as(still.name()).isNotNull();
            assertThat(service.list(org)).as(still.name()).hasSize(1);
        }
        item.setPhase(InquiryWorkItemPhase.COMPLETED);
        assertThat(service.holdFor(org, ReplyDraftHold.Lane.INQUIRY, subject)).isNull();
        assertThat(service.list(org)).isEmpty();
        // Nothing recorded is removed: the hold row and the decisions on it stay.
        verify(writer, never()).clear(any(), any(), any());
        verify(confirmations, never()).deleteAll();
    }

    @Test
    @DisplayName("a review the channel reports answered shows no standing hold")
    void anAnsweredReviewIsPastItsHold() {
        heldRow(ReplyDraftHold.Lane.REVIEW);
        Review review = new Review();
        review.setId(subject);
        when(reviews.findById(subject)).thenReturn(Optional.of(review));
        assertThat(service.holdFor(org, ReplyDraftHold.Lane.REVIEW, subject)).isNotNull();
        review.setReplyState(ReviewReplyState.ANSWERED);
        assertThat(service.holdFor(org, ReplyDraftHold.Lane.REVIEW, subject)).isNull();
    }
}
