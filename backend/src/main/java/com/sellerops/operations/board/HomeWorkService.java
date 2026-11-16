package com.sellerops.operations.board;

import com.sellerops.attention.dto.OperatorVocItem;
import com.sellerops.inquiry.followup.InquiryFollowUpService;
import com.sellerops.inquiry.queue.InquiryQueueService;
import com.sellerops.inquiry.queue.dto.InquiryQueueItem;
import com.sellerops.inquiry.queue.dto.InquiryQueueResponse;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.operations.ReviewWorkReader;
import com.sellerops.operations.dto.OperationsHomeView;
import com.sellerops.operations.dto.ReviewWorkView;
import com.sellerops.operationscase.CustomerOperationsHomeService;
import com.sellerops.operationscase.dto.CustomerOperationsHomeView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <b>The seller's work, read once on the server</b> (2026-11-09, demo handoff §7.12).
 *
 * <p>The Home used to merge five capped reads on the screen — five cases, the newest fifty inquiries, a hundred
 * reviews — and count what it had received, so the Home said 5+ where the list said 8, and 「longest waiting first」
 * sorted only the newest fifty. This reads every source whole (up to {@link #CEILING} rows each, and says so when one
 * reached it), applies the screen's own rules once — owner-path dedupe with the case winning, one bucket per row,
 * the two-week 지난 일 split with the safety and due-today exceptions — and sorts the whole set before any page is cut.
 *
 * <p><b>Nothing here decides anything new.</b> Each source is the existing read the screens already use
 * ({@link CustomerOperationsHomeService}, {@link ReviewWorkReader}, {@link InquiryQueueService},
 * {@link InquiryFollowUpService}), so capability, state and approval rules are theirs. Read-only: no collection, no
 * draft, no model call, no channel call.
 *
 * <p><b>Not {@code @Transactional}, on purpose</b> — each source runs in its own read transaction, so one source
 * failing marks only itself unread instead of rolling the whole board back.
 */
@Service
public class HomeWorkService {

    private static final Logger log = LoggerFactory.getLogger(HomeWorkService.class);

    /** The most rows one source is read for. Reaching it marks the source incomplete; nothing past it is guessed. */
    static final int CEILING = 5000;
    /** How long a row is this morning's work — two weeks, every kind (product-owner decision, 2026-10-28). */
    static final Duration RECENT = Duration.ofDays(14);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public enum Source { CASES, REVIEWS, INQUIRIES, FOLLOW_UPS }

    /** Whether a source was read, and whether what was read is all there is. */
    public record SourceRead(Source name, boolean read, boolean complete) {
    }

    /** The waiting list's one row: something the seller has nothing to do on yet. */
    public record WaitingRow(String key, String owner, UUID inquiryId, UUID workItemId, String waitingOn, String phase,
                             String subject, String channelCode, Instant since, LocalDate nextCheckOn,
                             String outcome) {
    }

    /** Everything one read produced: every work row (todo and 지난 일), the waiting rows, and what each source did. */
    public record Board(Instant asOf, List<HomeWorkRow> rows, List<WaitingRow> waiting,
                        Map<Source, SourceRead> sources) {

        public boolean everythingRead() {
            return sources.values().stream().allMatch(s -> s.read() && s.complete());
        }

        public boolean allRead(Set<Source> needed) {
            return needed.stream().map(sources::get).allMatch(s -> s != null && s.read() && s.complete());
        }
    }

    private final CustomerOperationsHomeService cases;
    private final ReviewWorkReader reviews;
    private final InquiryQueueService inquiries;
    private final InquiryFollowUpService followUps;
    private Clock clock = Clock.systemUTC();

    public HomeWorkService(CustomerOperationsHomeService cases, ReviewWorkReader reviews,
                           InquiryQueueService inquiries, InquiryFollowUpService followUps) {
        this.cases = cases;
        this.reviews = reviews;
        this.inquiries = inquiries;
        this.followUps = followUps;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private com.sellerops.replyhold.ReplyHoldService holds;

    /** The holds standing on approved answers, for {@link #heldRow}. Absent: no registration is read as held. */
    @Autowired(required = false)
    public void setHolds(com.sellerops.replyhold.ReplyHoldService holds) {
        this.holds = holds;
    }

    public Board read(UUID orgId) {
        Instant now = clock.instant();
        Map<Source, SourceRead> sources = new EnumMap<>(Source.class);
        Map<String, HomeWorkRow> byOwner = new LinkedHashMap<>();
        Set<String> settled = new java.util.HashSet<>();
        // The holds, read once and shared by the sources that use them: a failure fails each source that needed them.
        Supplier<HeldIndex> held = once(() -> heldIndex(orgId));

        CustomerOperationsHomeService.HomeCases caseRead = attempt(sources, Source.CASES,
                () -> cases.homeCases(orgId), CustomerOperationsHomeService.HomeCases::complete);
        if (caseRead != null) {
            settled.addAll(caseRead.settled());
            for (CustomerOperationsHomeView.DecisionRow row : caseRead.decisions()) {
                HomeWorkRow r = caseRow(row);
                byOwner.putIfAbsent(r.owner(), r);
            }
        }

        ReviewWorkReader.Read reviewRead = attempt(sources, Source.REVIEWS,
                () -> {
                    held.get();
                    return reviews.read(orgId, CEILING, CEILING, true);
                }, ReviewWorkReader.Read::complete);
        if (reviewRead != null) {
            Set<UUID> heldReviews = held.get().drafts(com.sellerops.replyhold.ReplyDraftHold.Lane.REVIEW);
            ReviewWorkView work = reviewRead.view();
            for (OperationsHomeView.AttentionReview r : work.attention()) {
                String owner = "/reviews/reply/" + r.reviewId();
                if (!settled.contains(owner)) {
                    byOwner.putIfAbsent(owner, attentionRow(r, owner)
                            .withOrigin(new HomeWorkRow.Origin("ATTENTION", null, r, null, null, null)));
                }
            }
            for (ReviewWorkView.AccountWork account : work.committed()) {
                for (OperatorVocItem item : account.todo()) {
                    UUID reviewId = uuid(item.reviewId());
                    if (reviewId == null) {
                        continue;
                    }
                    String owner = "/reviews/reply/" + reviewId;
                    if (!settled.contains(owner)) {
                        byOwner.putIfAbsent(owner, committedRow(account, item, reviewId, owner, work)
                                .heldDraft(heldReviews.contains(reviewId))
                                .withOrigin(new HomeWorkRow.Origin("COMMITTED", null, null, new HomeWorkRow.Committed(
                                        account.accountId(), account.channelCode(), account.channelNameKo(), item),
                                        null, null)));
                    }
                }
            }
        }

        InquiryQueueResponse queue = attempt(sources, Source.INQUIRIES,
                () -> {
                    held.get();
                    return inquiries.queueUpTo(orgId, InquiryWorkItemPhase.AWAITING_SELLER, CEILING);
                }, q -> q.totalElements() <= CEILING);
        if (queue != null) {
            Set<UUID> heldInquiries = held.get().drafts(com.sellerops.replyhold.ReplyDraftHold.Lane.INQUIRY);
            for (InquiryQueueItem q : queue.content()) {
                String owner = "/inquiries/" + q.inquiryId();
                if (!settled.contains(owner)) {
                    byOwner.putIfAbsent(owner, inquiryRow(q, owner)
                        .heldDraft(q.workItemId() != null && heldInquiries.contains(q.workItemId()))
                        .withOrigin(new HomeWorkRow.Origin("INQUIRY", null, null, null, q, null)));
                }
            }
        }

        List<WaitingRow> waiting = new ArrayList<>();
        FollowRead followRead = attempt(sources, Source.FOLLOW_UPS,
                () -> new FollowRead(followUps.allFollowUps(orgId), followUps.dueBeforeReply(orgId),
                        held.get().registrations()), f -> true);
        if (followRead != null) {
            InquiryFollowUpService.FollowUps follow = followRead.after();
            for (InquiryFollowUpService.FollowUpRow f : follow.due()) {
                if (followRead.isHeld(f)) {
                    addHeld(byOwner, f);
                }
                addDue(byOwner, f);
            }
            for (InquiryFollowUpService.FollowUpRow f : followRead.beforeReply()) {
                addDue(byOwner, f);
            }
            // Held registrations the follow-up read already set apart from waiting (2026-11-15): 할 일 only.
            for (InquiryFollowUpService.FollowUpRow f : follow.held()) {
                addHeld(byOwner, f);
            }
            for (InquiryFollowUpService.FollowUpRow f : follow.waiting()) {
                if (followRead.isHeld(f)) {
                    addHeld(byOwner, f);
                    continue;
                }
                waiting.add(new WaitingRow("wait:" + f.workItemId(), f.to(), f.inquiryId(), f.workItemId(),
                        f.waitingOn(), f.phase(), f.subject(), f.channelCode(), f.since(), f.nextCheckOn(),
                        f.outcome()));
            }
        }

        List<HomeWorkRow> rows = byOwner.values().stream().map(r -> r.withPast(isPast(r, now))).toList();
        return new Board(now, rows, List.copyOf(waiting), Map.copyOf(sources));
    }

    /**
     * The follow-up source: checks after a reply, checks due on a reply still the seller's to write, and the approved
     * answers whose registration a hold stopped and the seller has not yet decided or fixed ({@code held}, work items).
     */
    record FollowRead(InquiryFollowUpService.FollowUps after, List<InquiryFollowUpService.FollowUpRow> beforeReply,
                      Set<UUID> held) {
        FollowRead(InquiryFollowUpService.FollowUps after, List<InquiryFollowUpService.FollowUpRow> beforeReply) {
            this(after, beforeReply, Set.of());
        }

        boolean isHeld(InquiryFollowUpService.FollowUpRow f) {
            return InquiryFollowUpService.REGISTRATION.equals(f.waitingOn()) && held.contains(f.workItemId());
        }
    }

    /**
     * The holds the seller has not decided yet, as the reply screen shows them — the same {@code confirmed} it draws, so a
     * hold already decided is not work again. Nothing is written.
     *
     * @param registrations approved inquiry answers whose registration a hold stopped (2026-11-14 owner request: 「등록 직전
     *                      보류로 사람의 수정·결정이 필요한 문의는 홈과 별도 할 일 목록에 다시 표시」), work items
     * @param drafts        drafts not yet approved a hold stands on (2026-11-14 review: a held draft was listed 「승인 가능」
     *                      while 승인 was refused) — work items for inquiries, reviews for reviews
     */
    record HeldIndex(Set<UUID> registrations, Map<com.sellerops.replyhold.ReplyDraftHold.Lane, Set<UUID>> draftsByLane) {
        static final HeldIndex NONE = new HeldIndex(Set.of(), Map.of());

        Set<UUID> drafts(com.sellerops.replyhold.ReplyDraftHold.Lane lane) {
            return draftsByLane.getOrDefault(lane, Set.of());
        }
    }

    private HeldIndex heldIndex(UUID orgId) {
        if (holds == null) {
            return HeldIndex.NONE;
        }
        List<com.sellerops.replyhold.ReplyHoldService.HoldView> open = holds.list(orgId).stream()
                .filter(h -> !h.confirmed()).toList();
        Set<UUID> registrations = open.stream().filter(h -> "INQUIRY".equals(h.lane()) && h.approved())
                .map(com.sellerops.replyhold.ReplyHoldService.HoldView::subjectId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<com.sellerops.replyhold.ReplyDraftHold.Lane, Set<UUID>> drafts = new EnumMap<>(
                com.sellerops.replyhold.ReplyDraftHold.Lane.class);
        for (com.sellerops.replyhold.ReplyHoldService.HoldView h : open) {
            if (!h.approved()) {
                drafts.computeIfAbsent(com.sellerops.replyhold.ReplyDraftHold.Lane.valueOf(h.lane()),
                        k -> new java.util.HashSet<>()).add(h.subjectId());
            }
        }
        return new HeldIndex(registrations, Map.copyOf(drafts));
    }

    /** A read done at most once per board, its failure kept and thrown again to every source that asks for it. */
    static <T> Supplier<T> once(Supplier<T> read) {
        return new Supplier<>() {
            private T value;
            private RuntimeException failed;
            private boolean done;

            @Override
            public T get() {
                if (!done) {
                    done = true;
                    try {
                        value = read.get();
                    } catch (RuntimeException e) {
                        failed = e;
                    }
                }
                if (failed != null) {
                    throw failed;
                }
                return value;
            }
        };
    }

    /**
     * <b>A registration a hold stopped is the seller's work again, not something to wait for</b> (2026-11-14). The reply
     * is approved and its registration was refused before anything was sent: until the seller fixes it or decides, it
     * cannot go on by itself, so it leaves 처리 진행 중 and stands in 할 일 under 판단 필요 — once: the waiting row is not
     * added, and an item that already has a row (a case about it) keeps that row. The approval, the phase and the hold
     * are untouched; this is only where the item is listed. Once the seller decides, the hold reads confirmed and the
     * registration is a plain wait again.
     */
    static void addHeld(Map<String, HomeWorkRow> byOwner, InquiryFollowUpService.FollowUpRow f) {
        byOwner.putIfAbsent(f.to(), heldRow(f)
                .withOrigin(new HomeWorkRow.Origin("HELD", null, null, null, null, f)));
    }

    static HomeWorkRow heldRow(InquiryFollowUpService.FollowUpRow f) {
        return new HomeWorkRow("held:" + f.inquiryId(), f.to(), f.to(), "INQUIRY", "INQUIRY", f.inquiryId(), null,
                f.workItemId(), "REGISTRATION_HELD", null, "needsLook", false, false, false, null, null,
                f.channelCode(), null, null, null, f.subject(), null, null, f.since(), false, null, f.waitingOn(), null,
                List.of(), null, null);
    }

    /**
     * A check whose day has come (2026-11-10 review). Alone on its item it is its own 「다시 확인」 row, as before. On an
     * item that already has a row — the inquiry still waiting for its reply, or the case about it — it rides on that
     * row instead of being dropped: one item stays one row, and the check keeps its own action, day and identifiers.
     *
     * <p>A settled case still keeps its item off the list, but not the check (2026-11-10): the case settled the
     * customer's item, not a day the seller set for themselves. With the item settled the check stands alone — the
     * item is not offered again (it was never added), only the check.
     */
    static void addDue(Map<String, HomeWorkRow> byOwner, InquiryFollowUpService.FollowUpRow f) {
        HomeWorkRow held = byOwner.get(f.to());
        byOwner.put(f.to(), held == null
                ? dueRow(f).withOrigin(new HomeWorkRow.Origin("FOLLOW_UP", null, null, null, null, f))
                : held.withAlso(workOf(f)));
    }

    static HomeWorkRow.Work workOf(InquiryFollowUpService.FollowUpRow f) {
        boolean behind = InquiryFollowUpService.TOLD_BEHIND.equals(f.waitingOn());
        return new HomeWorkRow.Work(behind ? "TOLD_BEHIND" : "CHECK_AGAIN", f.workItemId(), f.checkId(), f.waitingOn(),
                f.nextCheckOn(), f.due(), f.since(), f.note(), f.to());
    }

    // ---- the four sources, read whole ----

    private <T> T attempt(Map<Source, SourceRead> sources, Source name, Supplier<T> read,
                          java.util.function.Predicate<T> complete) {
        try {
            T value = read.get();
            sources.put(name, new SourceRead(name, true, complete.test(value)));
            return value;
        } catch (RuntimeException failed) {
            // A failed read is not zero rows: the source is reported unread and every count it feeds is withheld.
            log.warn("home board: {} read failed ({})", name, failed.getClass().getSimpleName());
            sources.put(name, new SourceRead(name, false, false));
            return null;
        }
    }

    // ---- rows, one per source shape; the buckets are decided here and nowhere else ----

    static HomeWorkRow caseRow(CustomerOperationsHomeView.DecisionRow row) {
        return caseRowOf(row).withOrigin(new HomeWorkRow.Origin("CASE", row, null, null, null, null));
    }

    private static HomeWorkRow caseRowOf(CustomerOperationsHomeView.DecisionRow row) {
        boolean missing = row.missingInformation() != null && !row.missingInformation().isEmpty();
        String subject = row.subjectKind();
        String bucket = row.safety() ? "safety"
                : missing ? "needsInfo"
                : row.draftPrepared() ? "approvable"
                : "INQUIRY".equals(subject) ? "needsReply" : "needsLook";
        Instant since = row.receivedOn() != null ? row.receivedOn().atStartOfDay(SEOUL).toInstant() : row.openedAt();
        return new HomeWorkRow("case:" + row.caseId(), ownerOf(row), "/customer-operations/cases/" + row.caseId(), "CASE",
                subject, row.caseId(), row.caseId(), null, row.draftPrepared() ? "DRAFT_READY" : "LOOK_INTO", null,
                bucket, false, false, row.safety(), row.safety() ? row.safetyReading() : null,
                row.safety() ? row.safetyKind() : null, null, row.channelNameKo(), row.rating(), row.reviewTier(),
                row.title() == null || row.title().isBlank() ? row.summary() : row.title(), row.preview(),
                row.summary(), since, false, null, null, null,
                row.missingInformation() == null ? List.of() : List.copyOf(row.missingInformation()), null, null);
    }

    /**
     * The identity a case shares with the raw item it is about — only when it is about one. A case about a source
     * links to {@code /connect}, which every such case shares; keyed by that, two of them were one row and the second
     * was never counted (2026-11-10 review). Such a case is its own item.
     */
    static String ownerOf(CustomerOperationsHomeView.DecisionRow row) {
        String to = row.to();
        return to != null && (to.startsWith("/inquiries/") || to.startsWith("/reviews/reply/")) ? to
                : "/customer-operations/cases/" + row.caseId();
    }

    static HomeWorkRow attentionRow(OperationsHomeView.AttentionReview r, String owner) {
        return new HomeWorkRow("review:" + r.reviewId(), owner, owner, "REVIEW", "REVIEW", r.reviewId(), null, null,
                "NEEDS_LOOK", null, r.safety() ? "safety" : "needsLook", false, false, r.safety(),
                r.safety() ? r.safetyReading() : null, r.safety() ? r.safetyKind() : null, r.channelCode(), null,
                r.rating(), null, r.quote(), null, r.productName(),
                r.occurredOn() == null ? null : r.occurredOn().atStartOfDay(SEOUL).toInstant(), false, null, null,
                null, List.of(), null, null);
    }

    static HomeWorkRow committedRow(ReviewWorkView.AccountWork account, OperatorVocItem item, UUID reviewId,
                                    String owner, ReviewWorkView work) {
        boolean safety = work.safetyReviewIds() != null && work.safetyReviewIds().contains(reviewId);
        String delegated = work.delegatedStates() == null ? null : work.delegatedStates().get(reviewId);
        boolean awaiting = "AWAITING_APPROVAL".equals(item.replyWorkState());
        String state = awaiting ? "AWAITING_APPROVAL" : "DRAFT_NEEDED";
        String bucket = safety ? "safety" : awaiting || delegated != null ? "approvable" : "needsReply";
        boolean praise = !safety && awaiting && delegated == null && item.rating() != null && item.rating() >= 4;
        String channel = item.channelCode() != null ? item.channelCode() : account.channelCode();
        return new HomeWorkRow("review:" + reviewId, owner, owner, "REVIEW", "REVIEW", reviewId, null, null, state,
                delegated, bucket, false, praise, safety,
                !safety ? null : work.safetyWorryReviewIds() != null && work.safetyWorryReviewIds().contains(reviewId)
                        ? "WORRY" : "OCCURRENCE",
                safety && work.safetyKinds() != null ? work.safetyKinds().get(reviewId) : null, channel,
                item.channelNameKo() != null ? item.channelNameKo() : account.channelNameKo(), item.rating(), null,
                item.safePreview(), null, item.productName(), instantOf(item.sourceCreatedDate()), false, null, null,
                null, List.of(), item.actionRef() == null ? null : account.accountId(), item.actionRef());
    }

    static HomeWorkRow inquiryRow(InquiryQueueItem q, String owner) {
        String ask = q.sellerCheckAsk();
        String state = ask != null ? "SELLER_CHECK" : q.hasDraft() ? "DRAFT_READY" : "REPLY_NEEDED";
        String bucket = ask != null ? "needsInfo" : q.hasDraft() ? "approvable" : "needsReply";
        return new HomeWorkRow("inquiry:" + q.inquiryId(), owner, owner, "INQUIRY", "INQUIRY", q.inquiryId(), null,
                q.workItemId(), state, null, bucket, false, false, false, null, null, q.channelCode(),
                q.channelNameKo(), null, null, q.title() == null || q.title().isBlank() ? q.snippet() : q.title(),
                null, q.snippet(), q.receivedAt(), false, null, null, ask, List.of(), null, null);
    }

    static HomeWorkRow dueRow(InquiryFollowUpService.FollowUpRow f) {
        boolean behind = InquiryFollowUpService.TOLD_BEHIND.equals(f.waitingOn());
        return new HomeWorkRow("follow:" + f.inquiryId(), f.to(), f.to(), "INQUIRY", "INQUIRY", f.inquiryId(), null,
                f.workItemId(), behind ? "TOLD_BEHIND" : "CHECK_AGAIN", null, "checkAgain", false, false, false, null,
                null, f.channelCode(), null, null, null, f.subject(), null, f.note(), f.since(), true,
                f.nextCheckOn(), f.waitingOn(), null, List.of(), null, null);
    }

    /**
     * 지난 일: older than two weeks by the customer's own clock. Never a safety row, never a check whose day has come
     * (a three-week-old record due today is today's work), and never a row without a time.
     */
    static boolean isPast(HomeWorkRow row, Instant now) {
        if (row.since() == null || row.safety() || row.dueToday()) {
            return false;
        }
        return Duration.between(row.since(), now).compareTo(RECENT) > 0;
    }

    static Instant instantOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return Instant.parse(v);
        } catch (RuntimeException notInstant) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (RuntimeException notOffset) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v).atZone(SEOUL).toInstant();
        } catch (RuntimeException notLocal) {
            // fall through
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).atStartOfDay(SEOUL).toInstant();
        } catch (RuntimeException unreadable) {
            return null; // absence of a readable date is not evidence of age
        }
    }

    private static UUID uuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }
}
