package com.sellerops.knowledge.teach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.publish.InquiryTargetStateReader;
import com.sellerops.inquiry.publish.PreSendCheck;
import com.sellerops.operationscase.OperationsCase;
import com.sellerops.operationscase.OperationsSubjectKind;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다」 — on the case too</b> (product-owner
 * decision, 2026-10-03).
 *
 * <p>확인할 일 opens an inquiry-origin case in a pane whose floor carries the way to the send screen,
 * and the 문의 screen has warned beside that control since the publish package. The case read never
 * asked, so the same seller, one press apart, was told on one screen and not on the other.
 *
 * <p>What is fenced here is the whole of the decision that granted it: <b>one</b> read, of the stored
 * coverage, and <b>only</b> for an inquiry. A review's answer state is not this question, and a case
 * with no channel has nothing to ask about — in both cases the reader must not be touched at all,
 * because the cost of this feature was the one thing the grant was explicit about.
 */
class CaseAnswerStateNoteTest {

    private static OperationsCase inquiryCase(UUID channelId) {
        OperationsCase c = new OperationsCase();
        c.setSubjectKind(OperationsSubjectKind.INQUIRY);
        c.setChannelId(channelId);
        return c;
    }

    @Test
    @DisplayName("an inquiry case on a channel whose collection is not fresh carries the sentence")
    void staleInquiryChannelWarns() {
        UUID org = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        InquiryTargetStateReader reader = mock(InquiryTargetStateReader.class);
        when(reader.read(org, channel)).thenReturn(PreSendCheck.unproven(PreSendCheck.STATE_NOT_FRESH));

        assertThat(CaseKnowledgeService.answerStateNote(reader, org, inquiryCase(channel)))
                .isEqualTo("이 채널의 문의 수집이 최신이 아니라, 이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다.");
        verify(reader).read(org, channel);
    }

    @Test
    @DisplayName("a proven channel says nothing — a reassurance nobody asked for is how a warning stops working")
    void provenChannelIsSilent() {
        UUID org = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        InquiryTargetStateReader reader = mock(InquiryTargetStateReader.class);
        when(reader.read(org, channel)).thenReturn(PreSendCheck.proven());

        assertThat(CaseKnowledgeService.answerStateNote(reader, org, inquiryCase(channel))).isNull();
    }

    @Test
    @DisplayName("a review, and a case with no channel, never reach the reader at all")
    void noReadWhereTheQuestionDoesNotApply() {
        UUID org = UUID.randomUUID();
        InquiryTargetStateReader reader = mock(InquiryTargetStateReader.class);

        OperationsCase review = new OperationsCase();
        review.setSubjectKind(OperationsSubjectKind.REVIEW);
        review.setChannelId(UUID.randomUUID());
        assertThat(CaseKnowledgeService.answerStateNote(reader, org, review)).isNull();

        assertThat(CaseKnowledgeService.answerStateNote(reader, org, inquiryCase(null))).isNull();
        verify(reader, never()).read(any(), any());
    }

    @Test
    @DisplayName("without the reader the case reads exactly as it did — the lane is optional")
    void absentReaderIsSilent() {
        assertThat(CaseKnowledgeService.answerStateNote(null, UUID.randomUUID(), inquiryCase(UUID.randomUUID())))
                .isNull();
    }
}
