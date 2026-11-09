package com.sellerops.inquiry.proposal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.ingest.canonical.CommentAnswerCheck.Result;
import com.sellerops.ingest.canonical.SourceThreadRole;
import com.sellerops.inquiry.Inquiry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the detail says about the Cafe24 comment look (V184, 2026-11-08): the stored record as it is, 「no record」 apart
 * from a failed look, and nothing at all where no collection would ever look.
 */
class CommentCheckViewTest {

    private static final Instant MONDAY = Instant.parse("2026-11-02T02:00:00Z");
    private static final Instant FRIDAY = Instant.parse("2026-11-06T02:00:00Z");

    private static Inquiry collected() {
        Inquiry q = new Inquiry();
        q.setSellerAccountId(UUID.randomUUID());
        q.setThreadRole(SourceThreadRole.ROOT.name());
        return q;
    }

    private static Inquiry withRecord(Result result, Instant attempted, Instant checked) {
        Inquiry q = collected();
        q.setCommentCheck(result);
        q.setCommentCheckAttemptedAt(attempted);
        q.setCommentCheckedAt(checked);
        return q;
    }

    @Test
    void aFailedLookWithNoSuccessYetHasNoSuccessTime() {
        CommentCheckView v = CommentCheckView.of(withRecord(Result.NOT_CHECKED, FRIDAY, null), "CAFE24");
        assertThat(v.state()).isEqualTo("NOT_CHECKED");
        assertThat(v.attemptedAt()).isEqualTo(FRIDAY);
        assertThat(v.lastCheckedAt()).isNull();
    }

    @Test
    void aFailedLookAfterASuccessKeepsBothTimes() {
        CommentCheckView v = CommentCheckView.of(withRecord(Result.NOT_CHECKED, FRIDAY, MONDAY), "CAFE24");
        assertThat(v.state()).isEqualTo("NOT_CHECKED");
        assertThat(v.attemptedAt()).isEqualTo(FRIDAY);
        assertThat(v.lastCheckedAt()).isEqualTo(MONDAY);
    }

    @Test
    void successfulLooksSayWhatTheyFound() {
        assertThat(CommentCheckView.of(withRecord(Result.NO_SHOP_COMMENT, FRIDAY, FRIDAY), "CAFE24"))
                .isEqualTo(new CommentCheckView("NO_SHOP_COMMENT", FRIDAY, FRIDAY));
        assertThat(CommentCheckView.of(withRecord(Result.SHOP_ANSWERED, FRIDAY, FRIDAY), "CAFE24"))
                .isEqualTo(new CommentCheckView("SHOP_ANSWERED", FRIDAY, FRIDAY));
    }

    @Test
    void aCollectedCafe24InquiryWithNothingStoredIsNoRecordNotAFailure() {
        CommentCheckView v = CommentCheckView.of(collected(), "CAFE24");
        assertThat(v).isEqualTo(new CommentCheckView(CommentCheckView.NO_RECORD, null, null));
    }

    @Test
    void whereNoCollectionWouldLookThereIsNothingToSay() {
        assertThat(CommentCheckView.of(collected(), "NAVER")).as("another channel").isNull();
        assertThat(CommentCheckView.of(collected(), null)).as("unresolved channel").isNull();
        Inquiry reply = collected();
        reply.setThreadRole(SourceThreadRole.REPLY.name());
        assertThat(CommentCheckView.of(reply, "CAFE24")).as("a thread reply").isNull();
        Inquiry fileImport = new Inquiry();
        assertThat(CommentCheckView.of(fileImport, "CAFE24")).as("a row no connector wrote").isNull();
        assertThat(CommentCheckView.of(null, "CAFE24")).isNull();
    }

    @Test
    void aStoredRecordIsShownEvenWhereTheNoRecordRuleWouldNotApply() {
        // A record is a fact about a look that happened; it is never hidden because, say, the account link is gone.
        Inquiry q = withRecord(Result.NOT_CHECKED, FRIDAY, MONDAY);
        q.setSellerAccountId(null);
        assertThat(CommentCheckView.of(q, "CAFE24").state()).isEqualTo("NOT_CHECKED");
    }
}
