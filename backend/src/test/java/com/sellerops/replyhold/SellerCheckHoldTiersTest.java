package com.sellerops.replyhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a hold does with the seller's record (2026-11-02 fourth seller-check review): a reply written from another record,
 * or one that calls the action finished when the record does not, is fixed first — no confirmation and no 「이번 건만
 * 예외」 goes past it, because a finished fact is not something a decision makes true.
 */
class SellerCheckHoldTiersTest {

    private static ReplyHoldService.Finding finding(List<String> codes, InquiryDraftRecheck.SellerCheckNote note) {
        return new ReplyHoldService.Finding(codes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), note);
    }

    @Test
    @DisplayName("another record, or a finished sentence the record does not allow, must be fixed and names both outcomes")
    void fixedFirst() {
        ReplyHoldService.Finding changed = finding(List.of(InquiryDraftRecheck.SELLER_CHECK_CHANGED,
                InquiryDraftRecheck.SELLER_CHECK_COMPLETION), new InquiryDraftRecheck.SellerCheckNote("DONE", "IN_PROGRESS",
                List.of("환불 처리했")));
        assertThat(ReplyHoldService.mustFix(changed)).isTrue();
        assertThat(ReplyHoldService.needsException(changed)).as("not an exception").isFalse();
        assertThat(ReplyHoldService.toConfirm(changed)).as("not a confirmation").isEmpty();
        assertThat(ReplyHoldService.headline(changed)).contains("끝난 일로");
        assertThat(String.join(" ", ReplyHoldService.reasonsOf(changed)))
                .contains("「처리 완료」일 때 만든 답변", "지금 기록은 「접수·진행 중」", "「환불 처리했」", "예외로 넘길 수 없어요");

        ReplyHoldService.Finding before = finding(List.of(InquiryDraftRecheck.SELLER_CHECK_CHANGED),
                new InquiryDraftRecheck.SellerCheckNote(null, "CANNOT", List.of()));
        assertThat(ReplyHoldService.mustFix(before)).isTrue();
        assertThat(ReplyHoldService.headline(before)).contains("판매자 확인 기록이 바뀌어");
        assertThat(ReplyHoldService.reasonsOf(before)).singleElement().asString().contains("적기 전에 만든 답변", "「처리 불가」");
    }

    @Test
    @DisplayName("the record's part of a finding is in its key only when there is one, and survives the stored note")
    void keyedAndKept() throws Exception {
        ReplyHoldService.Finding plain = new ReplyHoldService.Finding(List.of("SOURCE_CHANGED"), List.of("교환 정책"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ReplyHoldService.Finding sameWithNull = finding(List.of("SOURCE_CHANGED"), null);
        assertThat(ReplyHoldService.keyOf(new ReplyHoldService.Finding(List.of("SOURCE_CHANGED"), List.of(), List.of(),
                List.of()))).as("a finding without a record keys as before")
                .isEqualTo(ReplyHoldService.keyOf(sameWithNull));
        assertThat(ReplyHoldService.keyOf(plain)).isNotEqualTo(ReplyHoldService.keyOf(sameWithNull));

        ReplyHoldService.Finding a = finding(List.of(InquiryDraftRecheck.SELLER_CHECK_CHANGED),
                new InquiryDraftRecheck.SellerCheckNote("DONE", "IN_PROGRESS", List.of()));
        ReplyHoldService.Finding b = finding(List.of(InquiryDraftRecheck.SELLER_CHECK_CHANGED),
                new InquiryDraftRecheck.SellerCheckNote("DONE", "CANNOT", List.of()));
        assertThat(ReplyHoldService.keyOf(a)).isNotEqualTo(ReplyHoldService.keyOf(b));

        ObjectMapper json = new ObjectMapper();
        ReplyHoldService.Finding back = json.readValue(json.writeValueAsString(a), ReplyHoldService.Finding.class);
        assertThat(back.sellerCheck()).isEqualTo(a.sellerCheck());
        ReplyHoldService.Finding old = json.readValue("{\"codes\":[\"SOURCE_CHANGED\"]}", ReplyHoldService.Finding.class);
        assertThat(old.sellerCheck()).as("a note written before records existed").isNull();
    }
}
