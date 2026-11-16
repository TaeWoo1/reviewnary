package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The PC delegation lane stays out of the conflict change (DESIGN_conflict.md §7): it is told what it was told before. */
class VendorReplyPcLaneUnsettledTest {

    @Test
    @DisplayName("UNSETTLED_VALUE alone does not hold the PC lane; with another code, only that code travels")
    void thePcLaneIsUnchanged() {
        ReviewDraftRecheck.Result only = new ReviewDraftRecheck.Result(false, List.of(ReviewDraftRecheck.UNSETTLED_VALUE),
                List.of(), List.of(), List.of("교환은 14일 안에 가능합니다."), List.of(), List.of(), List.of(),
                List.of("교환 신청 기한이 자료마다 달라요"));
        ReviewDraftRecheck.Result pc = VendorReplyTaskService.pcLane(only, "교환은 14일 안에 가능합니다.");
        assertThat(pc.passed()).isTrue();
        assertThat(pc.codes()).isEmpty();
        assertThat(pc.sentences()).isEmpty();

        ReviewDraftRecheck.Result both = new ReviewDraftRecheck.Result(false,
                List.of(ReviewDraftRecheck.FORBIDDEN_PHRASE, ReviewDraftRecheck.UNSETTLED_VALUE), List.of(), List.of(),
                List.of(), List.of("무조건"), List.of(), List.of(), List.of("x"));
        ReviewDraftRecheck.Result pc2 = VendorReplyTaskService.pcLane(both, "무조건 됩니다. 교환은 14일 안에 가능합니다.");
        assertThat(pc2.passed()).isFalse();
        assertThat(pc2.codes()).containsExactly(ReviewDraftRecheck.FORBIDDEN_PHRASE);
        assertThat(pc2.sentences()).containsExactly("무조건 됩니다.");

        ReviewDraftRecheck.Result none = new ReviewDraftRecheck.Result(true, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        assertThat(VendorReplyTaskService.pcLane(none, "x")).isSameAs(none);
    }
}
