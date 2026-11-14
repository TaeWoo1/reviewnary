package com.sellerops.operations.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.operations.board.HomeWorkService.Source;
import com.sellerops.operations.board.HomeWorkService.SourceRead;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Home board's rules on the server, without a database (2026-11-09, demo handoff §7.12): the owner's order, the
 * page boundary, one bucket per row, the 지난 일 split and its two exceptions, and a failed read that is never a zero.
 * The Postgres proof ({@code HomeBoardPostgresProofIT}) runs the same rules through the real reads.
 */
class HomeBoardRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");

    private static HomeWorkRow row(String key, String subject, String state, String bucket, Integer rating,
                                   boolean safety, Instant since, boolean dueToday) {
        return new HomeWorkRow(key, "/x/" + key, "/x/" + key, subject, subject, UUID.randomUUID(), null, null, state,
                null, bucket, false, false, safety, null, null, null, null, rating, null, key, null, null, since,
                dueToday, null, null, null, List.of(), null, null);
    }

    private static HomeWorkService.Board board(List<HomeWorkRow> rows, Source... unread) {
        Map<Source, SourceRead> sources = new EnumMap<>(Source.class);
        for (Source s : Source.values()) {
            sources.put(s, new SourceRead(s, true, true));
        }
        for (Source s : unread) {
            sources.put(s, new SourceRead(s, false, false));
        }
        return new HomeWorkService.Board(NOW, rows.stream().map(r -> r.withPast(HomeWorkService.isPast(r, NOW)))
                .toList(), List.of(), sources);
    }

    private static Instant hoursAgo(int h) {
        return NOW.minus(Duration.ofHours(h));
    }

    @Test
    @DisplayName("importance: safety, then inquiries newest first, then reviews risk-first and newest first (owner, 2026-11-09)")
    void theOwnersOrder() {
        List<HomeWorkRow> rows = List.of(
                row("q-old", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(30), false),
                row("r-5", "REVIEW", "AWAITING_APPROVAL", "approvable", 5, false, hoursAgo(1), false),
                row("q-new", "INQUIRY", "DRAFT_READY", "approvable", null, false, hoursAgo(2), false),
                row("r-1", "REVIEW", "DRAFT_NEEDED", "needsReply", 1, false, hoursAgo(5), false),
                row("safe", "REVIEW", "NEEDS_LOOK", "safety", 4, true, hoursAgo(50), false),
                row("r-3", "REVIEW", "DRAFT_NEEDED", "needsReply", 3, false, hoursAgo(3), false),
                row("q-mid", "INQUIRY", "SELLER_CHECK", "needsInfo", null, false, hoursAgo(10), false));

        assertThat(HomeWorkOrder.sorted(rows, HomeWorkOrder.Sort.IMPORTANCE)).extracting(HomeWorkRow::key)
                .containsExactly("safe", "q-new", "q-mid", "q-old", "r-1", "r-3", "r-5");
        assertThat(HomeWorkOrder.sorted(rows, HomeWorkOrder.Sort.LONGEST)).extracting(HomeWorkRow::key)
                .as("longest waiting stays as a sort the seller picks — across the whole list")
                .containsExactly("safe", "q-old", "q-mid", "r-1", "r-3", "q-new", "r-5");
        assertThat(HomeWorkOrder.sorted(rows, HomeWorkOrder.Sort.NEWEST)).extracting(HomeWorkRow::key)
                .containsExactly("r-5", "q-new", "r-3", "r-1", "q-mid", "q-old", "safe");
    }

    @Test
    @DisplayName("an inquiry's waiting time is not an importance score: equal-weight inquiries are newest first, whatever they are")
    void waitingIsNotAScore() {
        List<HomeWorkRow> rows = List.of(
                row("follow-due", "INQUIRY", "CHECK_AGAIN", "checkAgain", null, false, hoursAgo(20 * 24), true),
                row("q-today", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(1), false));
        assertThat(HomeWorkOrder.sorted(rows, HomeWorkOrder.Sort.IMPORTANCE)).extracting(HomeWorkRow::key)
                .containsExactly("q-today", "follow-due");
    }

    @Test
    @DisplayName("a row with no time goes last in its group, and the stable key breaks every tie")
    void tiesAreBrokenByTheKey() {
        Instant same = hoursAgo(4);
        List<HomeWorkRow> rows = List.of(
                row("q-b", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, same, false),
                row("q-none", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, null, false),
                row("q-a", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, same, false));
        assertThat(HomeWorkOrder.sorted(rows, HomeWorkOrder.Sort.IMPORTANCE)).extracting(HomeWorkRow::key)
                .containsExactly("q-a", "q-b", "q-none");
    }

    @Test
    @DisplayName("paging after the sort: every row once, in order, with ties on the page boundary")
    void pagesCoverEveryRowOnce() {
        List<HomeWorkRow> rows = new ArrayList<>();
        for (int i = 0; i < 37; i++) {
            // Groups of three share one time, so ties land on page boundaries.
            rows.add(row(String.format("q-%02d", i), "INQUIRY", "REPLY_NEEDED", "needsReply", null, false,
                    hoursAgo(1 + i / 3), false));
        }
        for (int i = 0; i < 11; i++) {
            rows.add(row(String.format("r-%02d", i), "REVIEW", "DRAFT_NEEDED", "needsReply", 1 + i % 5, false,
                    hoursAgo(2 + i / 2), false));
        }
        HomeWorkService.Board b = board(rows);
        for (HomeWorkOrder.Sort sort : HomeWorkOrder.Sort.values()) {
            List<String> paged = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                HomeBoardController.WorkPage p = HomeBoardController.page(b, "all", sort, cursor, 7);
                p.rows().forEach(r -> paged.add(r.key()));
                assertThat(p.total().count()).isEqualTo(48);
                cursor = p.nextCursor();
                pages++;
            } while (cursor != null);
            assertThat(pages).isEqualTo(7);
            assertThat(paged).as(sort + ": no row twice").doesNotHaveDuplicates();
            assertThat(paged).as(sort + ": the pages are the whole sorted list")
                    .containsExactlyElementsOf(HomeWorkOrder.sorted(b.rows(), sort).stream().map(HomeWorkRow::key)
                            .toList());
        }
    }

    @Test
    @DisplayName("a cursor from another sort or bucket is refused rather than read as a position")
    void aCursorIsBoundToItsSortAndBucket() {
        List<HomeWorkRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("q-" + i, "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(i + 1), false));
        }
        HomeWorkService.Board b = board(rows);
        String cursor = HomeBoardController.page(b, "all", HomeWorkOrder.Sort.IMPORTANCE, null, 2).nextCursor();
        assertThat(cursor).isNotNull();
        assertThatThrownBy(() -> HomeBoardController.page(b, "all", HomeWorkOrder.Sort.LONGEST, cursor, 2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HomeBoardController.page(b, "needsReply", HomeWorkOrder.Sort.IMPORTANCE, cursor, 2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HomeBoardController.page(b, "all", HomeWorkOrder.Sort.IMPORTANCE, "not-a-cursor", 2))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("지난 일 is two weeks for every kind — except a safety review and a check whose day has come")
    void thePastSplit() {
        List<HomeWorkRow> rows = List.of(
                row("old-q", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(15 * 24), false),
                row("old-r1", "REVIEW", "DRAFT_NEEDED", "needsReply", 1, false, hoursAgo(20 * 24), false),
                row("old-safe", "REVIEW", "NEEDS_LOOK", "safety", 5, true, hoursAgo(40 * 24), false),
                row("old-due", "INQUIRY", "CHECK_AGAIN", "checkAgain", null, false, hoursAgo(20 * 24), true),
                row("no-time", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, null, false),
                row("fresh", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(13 * 24), false));
        HomeBoardController.HomeBoardView v = HomeBoardController.board(board(rows));

        assertThat(v.todo().total().count()).isEqualTo(4);
        assertThat(v.todo().safety().count()).isEqualTo(1);
        assertThat(v.todo().checkAgain().count()).as("a 20-day-old record checked again today is today's work")
                .isEqualTo(1);
        assertThat(v.past().total().count()).isEqualTo(2);
        assertThat(v.past().lowRatedReviews().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("every todo row is in exactly one bucket, and the buckets add up to the total")
    void theBucketsPartitionTheTotal() {
        List<HomeWorkRow> rows = List.of(
                row("a", "REVIEW", "NEEDS_LOOK", "safety", 1, true, hoursAgo(1), false),
                row("b", "REVIEW", "AWAITING_APPROVAL", "approvable", 5, false, hoursAgo(2), false),
                row("c", "INQUIRY", "SELLER_CHECK", "needsInfo", null, false, hoursAgo(3), false),
                row("d", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(4), false),
                row("e", "REVIEW", "NEEDS_LOOK", "needsLook", 3, false, hoursAgo(5), false),
                row("f", "INQUIRY", "CHECK_AGAIN", "checkAgain", null, false, hoursAgo(6), true));
        HomeWorkService.Board b = board(rows);
        HomeBoardController.Todo t = HomeBoardController.board(b).todo();
        int sum = t.safety().count() + t.approvable().count() + t.needsInfo().count() + t.needsReply().count()
                + t.needsLook().count() + t.checkAgain().count();
        assertThat(sum).isEqualTo(t.total().count()).isEqualTo(6);

        Set<String> seen = new HashSet<>();
        for (String bucket : List.of("safety", "approvable", "needsInfo", "needsReply", "needsLook", "checkAgain")) {
            HomeBoardController.WorkPage p = HomeBoardController.page(b, bucket, HomeWorkOrder.Sort.IMPORTANCE, null,
                    100);
            p.rows().forEach(r -> assertThat(seen.add(r.key())).as(r.key() + " in two buckets").isTrue());
        }
        assertThat(seen).hasSize(6);
    }

    @Test
    @DisplayName("답변 필요 is every todo row but checkAgain: the number, its review/inquiry halves and /work?bucket=answer agree")
    void theAnswerView() {
        List<HomeWorkRow> rows = List.of(
                row("a", "REVIEW", "NEEDS_LOOK", "safety", 1, true, hoursAgo(1), false),
                row("b", "REVIEW", "AWAITING_APPROVAL", "approvable", 5, false, hoursAgo(2), false),
                row("c", "INQUIRY", "SELLER_CHECK", "needsInfo", null, false, hoursAgo(3), false),
                row("d", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(4), false),
                row("f", "INQUIRY", "CHECK_AGAIN", "checkAgain", null, false, hoursAgo(6), true),
                row("old", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(15 * 24), false));
        HomeWorkService.Board b = board(rows);
        HomeBoardController.Todo t = HomeBoardController.board(b).todo();

        assertThat(t.answer().total().count()).as("total less checkAgain, the past left out").isEqualTo(4)
                .isEqualTo(t.total().count() - t.checkAgain().count());
        assertThat(t.answer().reviews().count()).isEqualTo(2);
        assertThat(t.answer().inquiries().count()).isEqualTo(2);
        HomeBoardController.WorkPage p = HomeBoardController.page(b, "answer", HomeWorkOrder.Sort.IMPORTANCE, null, 100);
        assertThat(p.total().count()).isEqualTo(4);
        assertThat(p.rows()).extracting(HomeWorkRow::key).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    @DisplayName("답변 필요 withholds its count like every other bucket when a source was not read")
    void theAnswerViewOnAFailedRead() {
        List<HomeWorkRow> rows = List.of(
                row("r", "REVIEW", "AWAITING_APPROVAL", "approvable", 5, false, hoursAgo(1), false));
        HomeBoardController.Todo t = HomeBoardController.board(board(rows, Source.INQUIRIES)).todo();
        assertThat(t.answer().total().count()).isNull();
        assertThat(t.answer().total().atLeast()).isEqualTo(1);
        assertThat(t.answer().inquiries().count()).isNull();
    }

    @Test
    @DisplayName("a failed source is not zero: its counts are withheld, the rest stay exact, atLeast says what was seen")
    void aFailedReadIsNotZero() {
        List<HomeWorkRow> rows = List.of(
                row("r", "REVIEW", "AWAITING_APPROVAL", "approvable", 5, false, hoursAgo(1), false),
                row("safe", "REVIEW", "NEEDS_LOOK", "safety", 1, true, hoursAgo(2), false));
        HomeBoardController.HomeBoardView v = HomeBoardController.board(board(rows, Source.INQUIRIES));

        assertThat(v.complete()).isFalse();
        assertThat(v.todo().total().count()).isNull();
        assertThat(v.todo().total().atLeast()).isEqualTo(2);
        assertThat(v.todo().approvable().count()).as("inquiries can be approvable too").isNull();
        assertThat(v.todo().needsReply().count()).isNull();
        assertThat(v.todo().safety().count()).as("safety comes from reviews and cases, both read").isEqualTo(1);
        assertThat(v.sources()).filteredOn(s -> s.name() == Source.INQUIRIES).singleElement()
                .satisfies(s -> assertThat(s.read()).isFalse());
    }

    @Test
    @DisplayName("without the whole case read the rows seen are not a floor — a settled case may hide any of them: 「—」")
    void aMissingCaseReadWithholdsTheFloor() {
        List<HomeWorkRow> rows = List.of(
                row("q", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(1), false),
                row("r", "REVIEW", "DRAFT_NEEDED", "needsReply", 2, false, hoursAgo(2), false));
        HomeWorkService.Board b = board(rows, Source.CASES);
        HomeBoardController.HomeBoardView v = HomeBoardController.board(b);

        assertThat(v.todo().total().count()).isNull();
        assertThat(v.todo().total().atLeast()).as("not 2+: either row may belong to a case we could not read")
                .isZero();
        assertThat(v.todo().needsReply().atLeast()).isZero();
        HomeBoardController.WorkPage p = HomeBoardController.page(b, "all", HomeWorkOrder.Sort.IMPORTANCE, null, 10);
        assertThat(p.total().atLeast()).isZero();
        assertThat(p.rows()).as("the rows themselves are still listed, beside the unread source").hasSize(2);
    }

    private static com.sellerops.inquiry.followup.InquiryFollowUpService.FollowUpRow dueCheck(String to) {
        return new com.sellerops.inquiry.followup.InquiryFollowUpService.FollowUpRow(UUID.randomUUID(),
                UUID.randomUUID(), "합성 문의", null, "ACTION", true, "IN_PROGRESS", "접수·진행 중", "합성-진행 기록",
                java.time.LocalDate.of(2026, 11, 9), "OPEN", hoursAgo(3 * 24), to, null, null, UUID.randomUUID());
    }

    @Test
    @DisplayName("a check due today on an item that has a row rides on it: one row, its bucket kept, not 지난 일")
    void aDueCheckRidesOnTheItemsRow() {
        HomeWorkRow reply = row("q", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(20 * 24), false);
        var f = dueCheck(reply.owner());
        java.util.Map<String, HomeWorkRow> byOwner = new java.util.LinkedHashMap<>();
        byOwner.put(reply.owner(), reply);
        HomeWorkService.addDue(byOwner, f);

        assertThat(byOwner).hasSize(1);
        HomeWorkRow merged = byOwner.get(reply.owner());
        assertThat(merged.bucket()).as("the row's own work decides its bucket").isEqualTo("needsReply");
        assertThat(merged.since()).as("and its place in the order").isEqualTo(reply.since());
        assertThat(merged.also()).singleElement().satisfies(w -> {
            assertThat(w.action()).isEqualTo("CHECK_AGAIN");
            assertThat(w.workItemId()).isEqualTo(f.workItemId());
            assertThat(w.checkId()).isEqualTo(f.checkId());
            assertThat(w.nextCheckOn()).isEqualTo(f.nextCheckOn());
            assertThat(w.dueToday()).isTrue();
        });
        HomeBoardController.HomeBoardView v = HomeBoardController.board(board(List.copyOf(byOwner.values())));
        assertThat(v.todo().total().count()).isEqualTo(1);
        assertThat(v.past().total().count()).isZero();
        assertThat(v.todo().needsReply().count()).isEqualTo(1);
        assertThat(v.todo().checkAgain().count()).as("one item is counted once").isZero();
        assertThat(v.todo().alsoDueToday().count()).isEqualTo(1);
        assertThat(v.todo().todayChecks().count()).as("「오늘 확인」 opens on it").isEqualTo(1);
        assertThat(HomeBoardController.page(board(List.copyOf(byOwner.values())), "todayChecks",
                HomeWorkOrder.Sort.IMPORTANCE, null, 10).rows()).extracting(HomeWorkRow::key).containsExactly("q");
    }

    @Test
    @DisplayName("alone on its item a due check is its own 다시 확인 row — also when a settled case took the item")
    void aDueCheckAloneOrSettled() {
        java.util.Map<String, HomeWorkRow> byOwner = new java.util.LinkedHashMap<>();
        HomeWorkService.addDue(byOwner, dueCheck("/inquiries/alone"));
        assertThat(byOwner.get("/inquiries/alone").bucket()).isEqualTo("checkAgain");
        assertThat(byOwner.get("/inquiries/alone").also()).isEmpty();
    }

    @Test
    @DisplayName("「오늘 확인」 is a view across the buckets: its own checks and the ones riding on other rows, nothing else")
    void theTodayChecksView() {
        HomeWorkRow reply = row("q", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(20 * 24), false);
        java.util.Map<String, HomeWorkRow> byOwner = new java.util.LinkedHashMap<>();
        byOwner.put(reply.owner(), reply);
        byOwner.put("/x/other", row("other", "INQUIRY", "REPLY_NEEDED", "needsReply", null, false, hoursAgo(1), false));
        HomeWorkService.addDue(byOwner, dueCheck(reply.owner()));
        HomeWorkService.addDue(byOwner, dueCheck("/inquiries/alone"));
        HomeWorkService.Board b = board(List.copyOf(byOwner.values()));

        HomeBoardController.Todo t = HomeBoardController.board(b).todo();
        assertThat(t.total().count()).isEqualTo(3);
        assertThat(t.checkAgain().count()).isEqualTo(1);
        assertThat(t.alsoDueToday().count()).isEqualTo(1);
        assertThat(t.todayChecks().count()).isEqualTo(2);
        HomeBoardController.WorkPage p = HomeBoardController.page(b, "todayChecks", HomeWorkOrder.Sort.IMPORTANCE, null,
                10);
        assertThat(p.total().count()).isEqualTo(2);
        assertThat(p.rows()).extracting(HomeWorkRow::owner).containsExactlyInAnyOrder(reply.owner(), "/inquiries/alone");
        assertThat(p.rows()).filteredOn(r -> r.owner().equals(reply.owner())).singleElement()
                .satisfies(r -> assertThat(r.also().get(0).to()).isEqualTo(reply.owner()));
    }
}
