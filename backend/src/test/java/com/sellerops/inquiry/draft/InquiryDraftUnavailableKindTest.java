package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Why no inquiry draft came back, as a closed word</b> (2026-11-01 eighth review): a draft the checks refused, a draft
 * waiting for the ceiling, and a run that never reached a model are three different results to count.
 */
class InquiryDraftUnavailableKindTest {

    @Test
    @DisplayName("every refusal of a written draft is HELD_BY_CHECK — the fixed sentences and the ones built per draft")
    void refusalsAreHeldByCheck() {
        for (String m : List.of(InquiryDraftComposer.UNSUPPORTED_CLAIM, InquiryDraftComposer.DROPPED_CONDITION,
                InquiryDraftComposer.LAW_CONFLICT, InquiryDraftComposer.STYLE_FORBIDDEN_PHRASE,
                InquiryDraftComposer.UNCONFIRMED_TEXT, InquiryDraftComposer.CONFLICTING_EVIDENCE,
                InquiryDraftComposer.unconfirmedFigure(List.of("3cm")),
                InquiryDraftComposer.unsupportedStatement("무료로 교환해 드립니다"))) {
            assertThat(InquiryDraftComposer.unavailableKind(m)).as(m).isEqualTo("HELD_BY_CHECK");
        }
    }

    @Test
    @DisplayName("the ceiling's held draft waits; the switch, the vendor, a detail read and an unanswered check did not run")
    void theRestDidNotRun() {
        assertThat(InquiryDraftComposer.unavailableKind(InquiryDraftComposer.REVIEW_PENDING)).isEqualTo("WAITING_FOR_CHECK");
        for (String m : List.of(InquiryDraftComposer.CAPABILITY_OFF, InquiryDraftComposer.MODEL_FAILED,
                InquiryDraftComposer.DETAIL_READ_FAILED, InquiryDraftComposer.DETAIL_READ_PENDING,
                InquiryDraftComposer.CLAIM_CHECK_UNAVAILABLE, InquiryDraftComposer.DECISION_UNAVAILABLE)) {
            assertThat(InquiryDraftComposer.unavailableKind(m)).as(m).isEqualTo("NOT_RUN");
        }
        assertThat(InquiryDraftComposer.unavailableKind(null)).isNull();
    }
}
