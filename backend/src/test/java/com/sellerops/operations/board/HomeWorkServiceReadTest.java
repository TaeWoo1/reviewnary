package com.sellerops.operations.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.followup.InquiryFollowUpService;
import com.sellerops.inquiry.queue.InquiryQueueService;
import com.sellerops.inquiry.queue.dto.InquiryQueueResponse;
import com.sellerops.operations.ReviewWorkReader;
import com.sellerops.operations.board.HomeWorkService.Source;
import com.sellerops.operations.dto.ReviewWorkView;
import com.sellerops.operationscase.CustomerOperationsHomeService;
import com.sellerops.operationscase.dto.CustomerOperationsHomeView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The board's read over its four sources, with the sources stubbed (2026-11-10 review of §7.12): which cases keep
 * their own identity, when the inquiry read is whole, and what a missing case read does to the floor.
 */
class HomeWorkServiceReadTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private CustomerOperationsHomeService cases;
    private ReviewWorkReader reviews;
    private InquiryQueueService inquiries;
    private InquiryFollowUpService followUps;
    private HomeWorkService service;

    @BeforeEach
    void wire() {
        cases = mock(CustomerOperationsHomeService.class);
        reviews = mock(ReviewWorkReader.class);
        inquiries = mock(InquiryQueueService.class);
        followUps = mock(InquiryFollowUpService.class);
        service = new HomeWorkService(cases, reviews, inquiries, followUps);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        when(cases.homeCases(ORG)).thenReturn(new CustomerOperationsHomeService.HomeCases(List.of(), Set.of(), true));
        when(reviews.read(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(new ReviewWorkReader.Read(
                new ReviewWorkView(0, List.of(), List.of(), Set.of(), Set.of(), Map.of(), Map.of()), true));
        when(inquiries.queueUpTo(any(), any(), anyInt())).thenReturn(new InquiryQueueResponse(List.of(), 0, 0, 0, 0));
        when(followUps.allFollowUps(ORG)).thenReturn(new InquiryFollowUpService.FollowUps(List.of(), 0, List.of(), 0,
                LocalDate.of(2026, 11, 10)));
    }

    private static CustomerOperationsHomeView.DecisionRow sourceCase() {
        return new CustomerOperationsHomeView.DecisionRow(UUID.randomUUID(), "SOURCE", null, "합성-연결 확인", null, null,
                null, "합성-요약", null, null, List.of(), false, null, NOW.minusSeconds(3600), null, "/connect", false,
                null);
    }

    private static InquiryFollowUpService.FollowUpRow registering(UUID workItem, UUID inquiry) {
        return new InquiryFollowUpService.FollowUpRow(workItem, inquiry, "합성-문의", "NAVER",
                InquiryFollowUpService.REGISTRATION, false, null, null, null, null, "ACTION_PENDING",
                NOW.minusSeconds(600), "/inquiries/" + inquiry);
    }

    private static com.sellerops.replyhold.ReplyHoldService.HoldView hold(UUID workItem, boolean approved,
                                                                         boolean confirmed) {
        return new com.sellerops.replyhold.ReplyHoldService.HoldView("INQUIRY", workItem, 3, approved,
                List.of("UNSUPPORTED_PROMISE"), "EXCEPTION", confirmed, "합성", List.of(), List.of(), List.of(),
                List.of(), NOW, "c", List.of("합성-약속"), List.of());
    }

    /**
     * 2026-11-14 owner request: a registration a hold stopped needs the seller (fix or decide), so it is 할 일 — once —
     * and not 처리 진행 중; a registration only waiting to be confirmed stays where it was, and so does one whose hold the
     * seller already decided.
     */
    @Test
    @DisplayName("a registration a hold stopped is work once; a plain or decided registration still waits")
    void aHeldRegistrationIsWorkOnceAndAPlainOneStillWaits() {
        UUID heldItem = UUID.randomUUID();
        UUID heldInquiry = UUID.randomUUID();
        UUID plainItem = UUID.randomUUID();
        UUID decidedItem = UUID.randomUUID();
        when(followUps.allFollowUps(ORG)).thenReturn(new InquiryFollowUpService.FollowUps(List.of(), 0, List.of(
                registering(heldItem, heldInquiry), registering(plainItem, UUID.randomUUID()),
                registering(decidedItem, UUID.randomUUID())), 3, LocalDate.of(2026, 11, 10)));
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.list(ORG)).thenReturn(List.of(hold(heldItem, true, false), hold(decidedItem, true, true),
                hold(plainItem, false, false)));
        service.setHolds(holds);

        HomeWorkService.Board b = service.read(ORG);
        HomeBoardController.HomeBoardView board = HomeBoardController.board(b);

        assertThat(b.rows()).singleElement().satisfies(r -> {
            assertThat(r.owner()).isEqualTo("/inquiries/" + heldInquiry);
            assertThat(r.workItemId()).isEqualTo(heldItem);
            assertThat(r.state()).isEqualTo("REGISTRATION_HELD");
            assertThat(r.bucket()).isEqualTo("needsLook");
            assertThat(r.origin().kind()).isEqualTo("HELD");
        });
        assertThat(b.waiting()).extracting(HomeWorkService.WaitingRow::workItemId)
                .as("a hold on the draft before approval is not a stopped registration")
                .containsExactlyInAnyOrder(plainItem, decidedItem);
        assertThat(board.todo().total().count()).isEqualTo(1);
        assertThat(board.todo().needsLook().count()).isEqualTo(1);
        assertThat(board.todo().answer().inquiries().count()).isEqualTo(1);
        assertThat(board.waiting().registration().count()).as("counted once, in 할 일").isEqualTo(2);
        assertThat(HomeBoardController.page(b, "needsLook", HomeWorkOrder.Sort.IMPORTANCE, null, 50).rows())
                .extracting(HomeWorkRow::workItemId).containsExactly(heldItem);
    }

    @Test
    @DisplayName("a held registration whose check day came is one row carrying the check, not two")
    void aHeldRegistrationDueTodayIsOneRow() {
        UUID item = UUID.randomUUID();
        UUID inquiry = UUID.randomUUID();
        InquiryFollowUpService.FollowUpRow due = new InquiryFollowUpService.FollowUpRow(item, inquiry, "합성-문의",
                "NAVER", InquiryFollowUpService.REGISTRATION, true, "IN_PROGRESS", "접수·진행 중", "합성-메모",
                LocalDate.of(2026, 11, 10), "ACTION_PENDING", NOW.minusSeconds(600), "/inquiries/" + inquiry);
        when(followUps.allFollowUps(ORG)).thenReturn(new InquiryFollowUpService.FollowUps(List.of(due), 1, List.of(),
                0, LocalDate.of(2026, 11, 10)));
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.list(ORG)).thenReturn(List.of(hold(item, true, false)));
        service.setHolds(holds);

        HomeWorkService.Board b = service.read(ORG);

        assertThat(b.rows()).singleElement().satisfies(r -> {
            assertThat(r.state()).isEqualTo("REGISTRATION_HELD");
            assertThat(r.also()).singleElement().extracting(HomeWorkRow.Work::action).isEqualTo("CHECK_AGAIN");
        });
        assertThat(b.waiting()).isEmpty();
    }

    @Test
    @DisplayName("holds that cannot be read are a follow-up read that failed, never registrations read as plain waits")
    void anUnreadableHoldListFailsTheFollowUpSource() {
        when(followUps.allFollowUps(ORG)).thenReturn(new InquiryFollowUpService.FollowUps(List.of(), 0,
                List.of(registering(UUID.randomUUID(), UUID.randomUUID())), 1, LocalDate.of(2026, 11, 10)));
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.list(ORG)).thenThrow(new IllegalStateException("합성"));
        service.setHolds(holds);

        HomeWorkService.Board b = service.read(ORG);

        assertThat(b.sources().get(Source.FOLLOW_UPS).read()).isFalse();
        assertThat(HomeBoardController.board(b).waiting().registration().count()).isNull();
        assertThat(HomeBoardController.board(b).todo().needsLook().count()).isNull();
    }

    private static com.sellerops.inquiry.queue.dto.InquiryQueueItem drafted(UUID workItem, UUID inquiry) {
        return new com.sellerops.inquiry.queue.dto.InquiryQueueItem(workItem, inquiry, UUID.randomUUID(),
                UUID.randomUUID(), "CAFE24", "카페24", null, null, "PROPOSED", "UNANSWERED", "합성-문의", "합성-본문",
                NOW.minusSeconds(3600), true, null, null, null);
    }

    private static com.sellerops.replyhold.ReplyHoldService.HoldView draftHold(String lane, UUID subject,
                                                                              boolean confirmed) {
        return new com.sellerops.replyhold.ReplyHoldService.HoldView(lane, subject, 2, false,
                List.of("UNSUPPORTED_PROMISE"), "EXCEPTION", confirmed, "합성", List.of(), List.of(), List.of(),
                List.of(), NOW, "c", List.of("합성-약속"), List.of());
    }

    /**
     * 2026-11-14 review: a draft the check before approval held was listed 「승인 가능」 while 확정 was refused. Held and
     * undecided it is 판단 필요 — a ★5 thank-you too, out of the fold; fixed or decided it is approvable again.
     */
    @Test
    @DisplayName("a held draft is 판단 필요 until fixed or decided, then 승인 가능 again")
    void aHeldDraftIsNotApprovableUntilDecided() {
        UUID heldItem = UUID.randomUUID();
        UUID decidedItem = UUID.randomUUID();
        UUID heldReview = UUID.randomUUID();
        when(inquiries.queueUpTo(any(), any(), anyInt())).thenReturn(new InquiryQueueResponse(List.of(
                drafted(heldItem, UUID.randomUUID()), drafted(decidedItem, UUID.randomUUID())), 0, 2, 2, 1));
        com.sellerops.attention.dto.OperatorVocItem thanks = new com.sellerops.attention.dto.OperatorVocItem("NAVER",
                "네이버", "REVIEW", "합성-상품", 5, null, "2026-11-09", "2026-11-09", null, "합성-칭찬", "ref-1",
                heldReview.toString(), "RESPONSE_NEEDED", true, "AWAITING_APPROVAL", null, false);
        when(reviews.read(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(new ReviewWorkReader.Read(
                new ReviewWorkView(0, List.of(), List.of(new ReviewWorkView.AccountWork(UUID.randomUUID(), "NAVER",
                        "네이버", null, List.of(thanks), List.of())), Set.of(), Set.of(), Map.of()), true));
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.list(ORG)).thenReturn(List.of(draftHold("INQUIRY", heldItem, false),
                draftHold("INQUIRY", decidedItem, true), draftHold("REVIEW", heldReview, false)));
        service.setHolds(holds);

        HomeWorkService.Board b = service.read(ORG);
        HomeBoardController.HomeBoardView board = HomeBoardController.board(b);

        assertThat(b.rows()).filteredOn(r -> heldItem.equals(r.workItemId())).singleElement()
                .satisfies(r -> assertThat(r.bucket() + "/" + r.state()).isEqualTo("needsLook/DRAFT_HELD"));
        assertThat(b.rows()).filteredOn(r -> decidedItem.equals(r.workItemId())).singleElement()
                .satisfies(r -> assertThat(r.bucket() + "/" + r.state()).isEqualTo("approvable/DRAFT_READY"));
        assertThat(b.rows()).filteredOn(r -> heldReview.equals(r.subjectId())).singleElement().satisfies(r -> {
            assertThat(r.bucket() + "/" + r.state()).isEqualTo("needsLook/DRAFT_HELD");
            assertThat(r.praise()).as("not folded among the thank-yous confirmed together").isFalse();
        });
        assertThat(board.todo().needsLook().count()).isEqualTo(2);
        assertThat(board.todo().approvable().count()).isEqualTo(1);
        assertThat(board.todo().praiseApprovable().count()).isZero();
        assertThat(board.todo().total().count()).as("moved, not added").isEqualTo(3);
    }

    @Test
    @DisplayName("holds that cannot be read fail every source that places rows by them")
    void anUnreadableHoldListFailsEverySourceThatUsesIt() {
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.list(ORG)).thenThrow(new IllegalStateException("합성"));
        service.setHolds(holds);

        HomeWorkService.Board b = service.read(ORG);

        assertThat(b.sources().get(Source.REVIEWS).read()).isFalse();
        assertThat(b.sources().get(Source.INQUIRIES).read()).isFalse();
        assertThat(b.sources().get(Source.FOLLOW_UPS).read()).isFalse();
        assertThat(b.sources().get(Source.CASES).read()).isTrue();
        assertThat(HomeBoardController.board(b).todo().approvable().count()).isNull();
    }

    @Test
    @DisplayName("two cases about a source are two rows: /connect is not an identity they share")
    void sourceCasesAreEachCounted() {
        when(cases.homeCases(ORG)).thenReturn(new CustomerOperationsHomeService.HomeCases(
                List.of(sourceCase(), sourceCase()), Set.of(), true));

        HomeWorkService.Board b = service.read(ORG);

        assertThat(b.rows()).hasSize(2).extracting(HomeWorkRow::owner).doesNotHaveDuplicates()
                .allSatisfy(o -> assertThat(o).startsWith("/customer-operations/cases/"));
        assertThat(HomeBoardController.board(b).todo().total().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the inquiry read is whole only when the one page held every row there is")
    void theInquiryReadIsWholeOnlyWhenNothingWasLeftOut() {
        when(inquiries.queueUpTo(any(), any(), anyInt())).thenReturn(new InquiryQueueResponse(List.of(), 0,
                HomeWorkService.CEILING, HomeWorkService.CEILING + 1, 2));
        assertThat(service.read(ORG).sources().get(Source.INQUIRIES).complete()).isFalse();

        when(inquiries.queueUpTo(any(), any(), anyInt())).thenReturn(new InquiryQueueResponse(List.of(), 0,
                HomeWorkService.CEILING, HomeWorkService.CEILING, 1));
        assertThat(service.read(ORG).sources().get(Source.INQUIRIES).complete())
                .as("rows dropped by the projection are not rows left out").isTrue();
    }

    @Test
    @DisplayName("a case read that fails marks only itself unread; the other sources are still read")
    void aFailedCaseReadIsOnlyItself() {
        when(cases.homeCases(ORG)).thenThrow(new IllegalStateException("synthetic outage"));
        HomeWorkService.Board b = service.read(ORG);

        assertThat(b.sources().get(Source.CASES).read()).isFalse();
        assertThat(b.sources().get(Source.REVIEWS).read()).isTrue();
        assertThat(b.sources().get(Source.INQUIRIES).read()).isTrue();
        assertThat(HomeBoardController.board(b).todo().total().count()).isNull();
    }
}
