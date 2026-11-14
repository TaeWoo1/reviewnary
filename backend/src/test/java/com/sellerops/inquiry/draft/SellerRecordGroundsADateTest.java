package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A date the seller's own record states is grounded on that record (2026-11-10 rehearsal, F1). The figure check keeps
 * reading dates — an invented delivery day is a real risk — but 「11월 11일 출고」 said back from the record the seller
 * wrote is the record's, and stands while the record does.
 */
class SellerRecordGroundsADateTest {

    private static final UUID EXCHANGE = UUID.randomUUID();
    private static final UUID SHIPPING = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private static final List<InquiryFigureGrounds.Shown> SHOWN = List.of(
            new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, EXCHANGE, null,
                    "상품을 받은 날부터 7일 안에 교환·반품을 신청할 수 있어요. 배송 중 포장이 파손된 경우 사진을 확인한 뒤 교환해 드려요."),
            new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.ORG_POLICY, SHIPPING, null,
                    "주문 후 1~2영업일 안에 출고해요. 출고 뒤 보통 2~3일 안에 도착해요. 주말·공휴일은 출고하지 않아요."),
            new InquiryFigureGrounds.Shown(InquiryDraftFigure.Ground.SELLER_CHECK, RECORD, null,
                    "판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): 처리 완료 — "
                            + "교환 접수 완료, 새 상품 11월 11일 출고"));

    @Test
    void theRecordsDateRestsOnTheRecord() {
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground(
                "확인한 결과를 안내드립니다. 처리 완료 — 교환 접수 완료, 새 상품 11월 11일 출고", SHOWN, null,
                "포장이 찢어져서 왔어요. 교환 되나요?", s -> s.text(), List.of());
        assertThat(rows).extracting(InquiryDraftFigure::getValue, InquiryDraftFigure::getUnit,
                InquiryDraftFigure::getGroundKind)
                .contains(org.assertj.core.groups.Tuple.tuple("11", "일", "SELLER_CHECK"));
        assertThat(rows).allSatisfy(r -> assertThat(r.getGroundKind()).isNotEqualTo("NONE"));
    }

    @Test
    void aDateNobodyWroteStaysUngrounded() {
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground("새 상품은 11월 14일에 출고해요.", SHOWN, null,
                "교환 되나요?", s -> s.text(), List.of());
        assertThat(rows).filteredOn(r -> "14".equals(r.getValue())).singleElement()
                .extracting(InquiryDraftFigure::getGroundKind).isEqualTo("NONE");
    }

    /**
     * The model's check confirmed the figure but named no passage (the rehearsal's fake does this; a real model may too).
     * Such a link decides nothing about the ground, so the figure is grounded by the text match as with no link — and a
     * link that does name a passage keeps deciding as before.
     */
    @Test
    void aConfirmationThatNamesNoPassageLeavesTheGroundToTheText() {
        String body = "확인한 결과를 안내드립니다. 처리 완료 — 교환 접수 완료, 새 상품 11월 11일 출고";
        InquiryFigureGrounds.Link unnamed = new InquiryFigureGrounds.Link("11", "일", body, "SUPPORTED", null, null, null,
                null);
        List<InquiryDraftFigure> rows = InquiryFigureGrounds.ground(body, SHOWN, null, "교환 되나요?", s -> s.text(),
                List.of(unnamed));
        assertThat(rows).filteredOn(r -> "11".equals(r.getValue()) && "일".equals(r.getUnit())).singleElement()
                .satisfies(r -> {
                    assertThat(r.getGroundKind()).isEqualTo("SELLER_CHECK");
                    assertThat(r.getLinkedBy()).isEqualTo("TEXT");
                });

        InquiryFigureGrounds.Link wrong = new InquiryFigureGrounds.Link("11", "일", body, "SUPPORTED", 1, null, null, null);
        List<InquiryDraftFigure> named = InquiryFigureGrounds.ground(body, SHOWN, null, "교환 되나요?", s -> s.text(),
                List.of(wrong));
        assertThat(named).filteredOn(r -> "11".equals(r.getValue()) && "일".equals(r.getUnit())).singleElement()
                .extracting(InquiryDraftFigure::getGroundKind).as("a passage the model named that does not state it")
                .isEqualTo("NONE");
    }
}
