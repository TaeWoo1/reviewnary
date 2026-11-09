package com.sellerops.inquiry.proposal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The answer the channel shows, beside the draft we prepared (2026-11-06 sixteenth review), and when its text — not the
 * inquiry — was last read (seventeenth review). Synthetic text only.
 */
class ChannelAnswerViewTest {

    private static final Instant ANSWERED = Instant.parse("2026-11-06T01:00:00Z");
    private static final Instant SEEN = Instant.parse("2026-11-06T02:00:00Z");

    private static final Instant MONDAY = Instant.parse("2026-11-02T02:00:00Z");
    private static final Instant FRIDAY = Instant.parse("2026-11-06T02:00:00Z");

    /** A read that carried the body at {@code lastSeen}. */
    private static Inquiry inquiry(String answerBody, Instant lastSeen) {
        return inquiry(answerBody, lastSeen, lastSeen);
    }

    private static Inquiry inquiry(String answerBody, Instant bodySeen, Instant lastSeen) {
        Inquiry q = new Inquiry();
        q.setAnswerBody(answerBody);
        q.setAnsweredAt(ANSWERED);
        q.setAnswerBodySeenAt(bodySeen);
        q.setLastSeenAt(lastSeen);
        return q;
    }

    private static ReplyDraftView draft(String comments) {
        return new ReplyDraftView(1, 0, "제목", comments, "fp", "sha256", ANSWERED, "AI", null, null, null, null, null,
                null);
    }

    @Test
    void theSameAnswerWithOtherSpacing() {
        ChannelAnswerView v = ChannelAnswerView.of(inquiry("안녕하세요.\n환불을 진행하고 있어요.  ", SEEN),
                draft("안녕하세요. 환불을 진행하고 있어요."));
        assertThat(v.body()).isEqualTo("안녕하세요.\n환불을 진행하고 있어요.");
        assertThat(v.sameAsDraft()).isTrue();
        assertThat(v.bodyCheckedAt()).isEqualTo(SEEN);
        assertThat(v.statusCheckedAt()).isEqualTo(SEEN);
        assertThat(v.latest()).as("the last read carried this text").isTrue();
        assertThat(v.answeredAt()).isEqualTo(ANSWERED);
    }

    @Test
    void anotherWordIsAnotherAnswer() {
        ChannelAnswerView v = ChannelAnswerView.of(inquiry("환불을 완료했어요.", SEEN), draft("환불을 진행하고 있어요."));
        assertThat(v.sameAsDraft()).isFalse();
        assertThat(v.body()).isEqualTo("환불을 완료했어요.");
    }

    @Test
    void noBodyIsNotAnAnswerView() {
        assertThat(ChannelAnswerView.of(inquiry(null, SEEN), draft("x"))).isNull();
        assertThat(ChannelAnswerView.of(inquiry("   ", SEEN), draft("x"))).isNull();
    }

    @Test
    void withoutADraftNothingIsCompared() {
        ChannelAnswerView v = ChannelAnswerView.of(inquiry("<p>환불을 진행하고 있어요.</p>", null), null);
        assertThat(v.sameAsDraft()).isNull();
        assertThat(v.body()).isEqualTo("환불을 진행하고 있어요.");
        assertThat(v.bodyCheckedAt()).as("never filled from the channel's answer time").isNull();
    }

    @Test
    void bodyReadOnMondayStatusOnFridayIsTheLastCollectedAnswer() {
        ChannelAnswerView v = ChannelAnswerView.of(inquiry("환불을 진행하고 있어요.", MONDAY, FRIDAY), null);
        assertThat(v.bodyCheckedAt()).isEqualTo(MONDAY);
        assertThat(v.statusCheckedAt()).isEqualTo(FRIDAY);
        assertThat(v.latest()).as("Friday saw the status, not this text").isFalse();
    }

    @Test
    void aBodyStoredBeforeItsReadTimeWasKeptIsUnknownNotGuessed() {
        ChannelAnswerView v = ChannelAnswerView.of(inquiry("환불을 진행하고 있어요.", null, FRIDAY), null);
        assertThat(v.bodyCheckedAt()).isNull();
        assertThat(v.statusCheckedAt()).isEqualTo(FRIDAY);
        assertThat(v.latest()).isFalse();
    }
}
