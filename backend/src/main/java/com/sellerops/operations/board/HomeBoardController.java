package com.sellerops.operations.board;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.operations.board.HomeWorkService.Source;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The Home's three reads</b> (2026-11-09, demo handoff §7.12): the summary counts, the whole work list, and the
 * recent activity. All three are read-only and read existing records only.
 *
 * <p>The summary and the list are two views of ONE {@link HomeWorkService.Board}: a bucket's count is the number of
 * rows {@code /work?bucket=} returns for it, by construction. They can still differ between two calls when the work
 * itself changed in between — each response carries its own {@code asOf}, the moment it was read.
 */
@RestController
@RequestMapping("/api/operations")
public class HomeBoardController {

    static final int MAX_PAGE = 100;
    static final Set<String> BUCKETS = Set.of("all", "safety", "approvable", "praise", "needsInfo", "needsReply",
            "needsLook", "checkAgain", "todayChecks", "answer", "past");

    private final HomeWorkService work;
    private final OperationsActivityService activity;

    public HomeBoardController(HomeWorkService work, OperationsActivityService activity) {
        this.work = work;
        this.activity = activity;
    }

    /**
     * A count and how far it can be trusted. {@code count} is exact, or null when a source it depends on was not read
     * whole; {@code atLeast} is how many rows the sources that were read hold. A failed read is never a zero.
     */
    public record Tally(Integer count, int atLeast) {
        static Tally of(long seen, boolean exact) {
            return new Tally(exact ? Integer.valueOf((int) seen) : null, (int) seen);
        }

        /**
         * A work count. Only the case read takes rows away — a settled case hides its raw item, a case replaces the
         * item it is about — so without the whole case read the rows seen are not a floor: some of them may not be
         * work at all. Then {@code atLeast} is 0 and the screen says 「—」, never an 「N+」 that may be too many
         * (2026-11-10 review).
         */
        static Tally ofWork(long seen, boolean exact, boolean floor) {
            return new Tally(exact ? Integer.valueOf((int) seen) : null, floor ? (int) seen : 0);
        }
    }

    private static boolean floorHolds(HomeWorkService.Board b) {
        return b.allRead(EnumSet.of(Source.CASES));
    }

    /**
     * The buckets partition {@code total}: one item, one row, one bucket. {@code alsoDueToday} is beside them, not one
     * of them — the rows in another bucket that also carry a check due today (2026-11-10 review), so 「다시 확인」 can
     * say how many more checks today has without counting an item twice. {@code todayChecks} is the view 「오늘 확인」
     * opens — {@code /work?bucket=todayChecks}: every row with a check due today, its own or one riding on it
     * ({@code checkAgain} + {@code alsoDueToday}). A view across the buckets, never one of them.
     */
    public record Todo(Tally total, Tally safety, Tally approvable, Tally praiseApprovable, Tally needsInfo,
                       Tally needsReply, Tally needsLook, Tally checkAgain, Tally alsoDueToday, Tally todayChecks,
                       Answer answer) {
    }

    /**
     * 「답변 필요」 — the 홈 CS 현황 number (2026-11-13 owner decision): every row of 할 일 that still waits for a reply, i.e.
     * every bucket but {@code checkAgain}. It is a view like {@code todayChecks}, opened by {@code /work?bucket=answer},
     * so the number and the list it opens are one filter. {@code reviews} and {@code inquiries} split it by subject from
     * the same rows — counted here, never from a page the screen happened to load. A case about something else (an
     * order) is in {@code total} and in neither half.
     */
    public record Answer(Tally total, Tally reviews, Tally inquiries) {
    }

    public record Waiting(Tally total, Tally registration, Tally action, Tally customer) {
    }

    public record Past(Tally total, Tally lowRatedReviews) {
    }

    public record HomeBoardView(Instant asOf, boolean complete, Todo todo, Waiting waiting, Past past,
                                List<HomeWorkService.SourceRead> sources) {
    }

    public record WorkPage(Instant asOf, String bucket, String sort, Tally total, List<HomeWorkRow> rows,
                           String nextCursor, List<HomeWorkService.SourceRead> sources) {
    }

    public record WaitingPage(Instant asOf, Tally total, List<HomeWorkService.WaitingRow> rows,
                              List<HomeWorkService.SourceRead> sources) {
    }

    /** Which sources a bucket's rows can come from — or be taken away by, through the owner dedupe. */
    static Set<Source> dependsOn(String bucket) {
        return switch (bucket) {
            case "safety" -> EnumSet.of(Source.CASES, Source.REVIEWS);
            case "praise" -> EnumSet.of(Source.CASES, Source.REVIEWS);
            case "needsInfo", "checkAgain", "todayChecks" ->
                    EnumSet.of(Source.CASES, Source.INQUIRIES, Source.FOLLOW_UPS);
            // 판단 필요 (needsLook) is here too (2026-11-14): it holds a held draft — review or inquiry — and an approved
            // answer whose registration a hold stopped, read with the follow-ups. Every source feeds it.
            default -> EnumSet.allOf(Source.class);
        };
    }

    static Predicate<HomeWorkRow> in(String bucket) {
        return switch (bucket) {
            case "all" -> r -> !r.past();
            case "past" -> HomeWorkRow::past;
            case "praise" -> r -> !r.past() && r.praise();
            case "todayChecks" -> r -> !r.past() && r.checkToday();
            case "answer" -> r -> !r.past() && !"checkAgain".equals(r.bucket());
            default -> r -> !r.past() && bucket.equals(r.bucket());
        };
    }

    @GetMapping("/home-board")
    public HomeBoardView homeBoard(@AuthenticationPrincipal AuthPrincipal principal) {
        return board(work.read(principal.orgId()));
    }

    static HomeBoardView board(HomeWorkService.Board b) {
        Todo todo = new Todo(tally(b, "all"), tally(b, "safety"), tally(b, "approvable"), tally(b, "praise"),
                tally(b, "needsInfo"), tally(b, "needsReply"), tally(b, "needsLook"), tally(b, "checkAgain"),
                Tally.ofWork(b.rows().stream().filter(in("all"))
                                .filter(r -> !"checkAgain".equals(r.bucket()) && r.alsoDueToday()).count(),
                        b.allRead(dependsOn("checkAgain")), floorHolds(b)),
                tally(b, "todayChecks"),
                new Answer(tally(b, "answer"), answerOf(b, "REVIEW"), answerOf(b, "INQUIRY")));
        boolean followRead = b.allRead(EnumSet.of(Source.FOLLOW_UPS));
        Waiting waiting = new Waiting(Tally.of(b.waiting().size(), followRead),
                Tally.of(b.waiting().stream().filter(w -> "REGISTRATION".equals(w.waitingOn())).count(), followRead),
                Tally.of(b.waiting().stream().filter(w -> "ACTION".equals(w.waitingOn())).count(), followRead),
                Tally.of(b.waiting().stream().filter(w -> "CUSTOMER".equals(w.waitingOn())).count(), followRead));
        Past past = new Past(tally(b, "past"), Tally.ofWork(b.rows().stream().filter(HomeWorkRow::past)
                .filter(r -> "REVIEW".equals(r.subject()) && r.rating() != null && r.rating() <= 2).count(),
                b.everythingRead(), floorHolds(b)));
        return new HomeBoardView(b.asOf(), b.everythingRead(), todo, waiting, past, sourcesOf(b));
    }

    private static Tally answerOf(HomeWorkService.Board b, String subject) {
        return Tally.ofWork(b.rows().stream().filter(in("answer")).filter(r -> subject.equals(r.subject())).count(),
                b.allRead(dependsOn("answer")), floorHolds(b));
    }

    private static Tally tally(HomeWorkService.Board b, String bucket) {
        return Tally.ofWork(b.rows().stream().filter(in(bucket)).count(), b.allRead(dependsOn(bucket)), floorHolds(b));
    }

    private static List<HomeWorkService.SourceRead> sourcesOf(HomeWorkService.Board b) {
        return EnumSet.allOf(Source.class).stream().map(s -> b.sources().getOrDefault(s,
                new HomeWorkService.SourceRead(s, false, false))).toList();
    }

    @GetMapping("/work")
    public WorkPage work(@AuthenticationPrincipal AuthPrincipal principal,
                         @RequestParam(defaultValue = "all") String bucket,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "50") int size) {
        if (!BUCKETS.contains(bucket)) {
            throw ApiException.badRequest("bucket을 알아보지 못했어요.");
        }
        return page(work.read(principal.orgId()), bucket, HomeWorkOrder.Sort.of(sort), cursor, size);
    }

    static WorkPage page(HomeWorkService.Board b, String bucket, HomeWorkOrder.Sort sort, String cursor, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE));
        HomeWorkOrder.Place after = HomeWorkOrder.parseCursor(cursor, sort, bucket);
        List<HomeWorkRow> ordered = HomeWorkOrder.sorted(b.rows().stream().filter(in(bucket)).toList(), sort);
        List<HomeWorkRow> page = HomeWorkOrder.after(ordered, sort, after, safeSize + 1);
        boolean more = page.size() > safeSize;
        List<HomeWorkRow> rows = more ? page.subList(0, safeSize) : page;
        String next = more ? HomeWorkOrder.cursorOf(sort, bucket, rows.get(rows.size() - 1)) : null;
        return new WorkPage(b.asOf(), bucket, sort.name().toLowerCase(java.util.Locale.ROOT),
                Tally.ofWork(ordered.size(), b.allRead(dependsOn(bucket)), floorHolds(b)), List.copyOf(rows), next,
                sourcesOf(b));
    }

    /** 기다리는 일 — the follow-ups with nothing for the seller to do yet. Disjoint from {@code /work} by phase and day. */
    @GetMapping("/work/waiting")
    public WaitingPage waiting(@AuthenticationPrincipal AuthPrincipal principal) {
        HomeWorkService.Board b = work.read(principal.orgId());
        return new WaitingPage(b.asOf(), Tally.of(b.waiting().size(), b.allRead(EnumSet.of(Source.FOLLOW_UPS))),
                b.waiting(), sourcesOf(b));
    }

    @GetMapping("/activity")
    public OperationsActivityService.ActivityPage activity(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @RequestParam(defaultValue = "today") String since,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return activity.read(principal.orgId(), since, limit);
    }
}
