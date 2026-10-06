package com.sellerops.report;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.OrgChannelVisibility;
import com.sellerops.coverage.ChannelCoverageService;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.coverage.dto.ChannelCoverageRow;
import com.sellerops.dashboard.metrics.OperationsMetricsRepository;
import com.sellerops.dashboard.metrics.OperationsMetricsService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.opportunity.OpportunityService;
import com.sellerops.opportunity.dto.OpportunityView;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueQueryService;
import com.sellerops.reviewissue.dto.ReviewIssueView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the facts of one completed period. Deterministic: the same database state and the same period
 * give the same {@link ReportFacts}, so a snapshot is a function of what the seller's data said at the
 * moment it was taken and nothing else.
 *
 * <p><b>Coverage is consulted before arithmetic</b> (2026-10-06, product-owner decision). Until then it
 * was not consulted at all: this class summed rows and published the sum, so a window no connector had
 * read came out as a zero and then as a comparison. Measured on the demo org that day — the weekly
 * report for 9/28–10/4 stored 「받은 리뷰 0」, 「받은 문의 0, 이전 4, delta −4」 while the newest
 * successful collection of either type was 9월 26일 and every channel sat at
 * {@code OBSERVED_FRESHNESS_UNPROVEN}. The Overview, on the same rows at the same moment, counted
 * nothing and named all twelve exclusions.
 *
 * <p><b>The rule is the Overview's own, imported, not re-derived</b>
 * ({@link OperationsMetricsService#counted} · {@link OperationsMetricsService#exclusionReasonKo}). A
 * channel contributes when its collection is provably current for the type, or when it actually gave
 * us rows in the window; every other channel is excluded and named. One step is added for a STORED
 * fact that the live screen does not need: when no channel qualified at all, the figure is
 * {@code null} rather than 0 — a sum over an empty set of qualifying channels is not a measurement,
 * and a stored zero gets cited as one.
 *
 * <p><b>Every number still comes from a read that already exists.</b> Period counts use the Overview's
 * per-(channel × day) series (KST calendar, REAL rows only — a report is the seller's own figures,
 * never the demo corpus); issue tallies use the evidence table windowed by date; the issue rows are the
 * issue memory's own views; the opportunities are the Opportunity Engine's derivation at the period's
 * end. Nothing is computed here that a screen could not also show.
 *
 * <p><b>Next steps are links to objects.</b> The report keeps no to-do list; it points at the inquiry
 * queue, an issue's evidence page, or an opportunity that already carries its prepared action.
 */
@Service
public class ReportFactsBuilder {

    static final String COUNTER_REVIEWS = "c-reviews";
    static final String COUNTER_NEGATIVE_REVIEWS = "c-negative-reviews";
    static final String COUNTER_INQUIRIES = "c-inquiries";
    static final String COUNTER_ORDERS = "c-orders";
    static final String COUNTER_REVENUE = "c-revenue";
    static final String COUNTER_UNANSWERED_NOW = "c-unanswered-now";

    static final String TYPE_REVIEW = "REVIEW";
    static final String TYPE_INQUIRY = "INQUIRY";
    static final String TYPE_ORDER = "ORDER_SUMMARY";

    /** Where the queue the unanswered figure counts is shown — the same filter the home uses. */
    static final String UNANSWERED_PATH = "/inquiries?status=UNANSWERED";

    private static final Map<String, String> SEVERITY_KO = Map.of("HIGH", "심각", "NORMAL", "보통", "LOW", "경미");

    /** The seller-facing name of each collection, stored with the read so a screen never renames it. */
    private static final Map<String, String> TYPE_LABEL =
            Map.of(TYPE_REVIEW, "리뷰", TYPE_INQUIRY, "문의", TYPE_ORDER, "주문");

    private final OperationsMetricsRepository metrics;
    private final InquiryRepository inquiries;
    private final ReviewIssueEvidenceRepository evidence;
    private final ReviewIssueQueryService issues;
    private final OpportunityService opportunities;
    private final ChannelRepository channels;
    private final ChannelCoverageService coverage;
    private final OrgChannelVisibility visibleChannels;

    public ReportFactsBuilder(OperationsMetricsRepository metrics, InquiryRepository inquiries,
                              ReviewIssueEvidenceRepository evidence, ReviewIssueQueryService issues,
                              OpportunityService opportunities, ChannelRepository channels,
                              ChannelCoverageService coverage, OrgChannelVisibility visibleChannels) {
        this.metrics = metrics;
        this.inquiries = inquiries;
        this.evidence = evidence;
        this.issues = issues;
        this.opportunities = opportunities;
        this.channels = channels;
        this.coverage = coverage;
        this.visibleChannels = visibleChannels;
    }

    @Transactional(readOnly = true)
    public ReportFacts build(UUID orgId, ReportPeriod period, Instant now) {
        Coverage cover = coverage(orgId);
        Figures figures = figures(orgId, period, cover);
        List<ReportFacts.IssueFact> issueFacts = issueFacts(orgId, period, figures.reviewsNow.measured());
        List<ReportFacts.OpportunityFact> opportunityFacts = opportunityFacts(orgId, period);
        List<ReportFacts.NextStep> nextSteps = nextSteps(figures.counters, issueFacts, opportunityFacts);
        return new ReportFacts(
                new ReportFacts.Period(period.kind().name(), period.kind().labelKo(), period.start(), period.end(),
                        period.labelKo(), period.previousStart(), period.previousEnd()),
                figures.counters, figures.salesByChannel, figures.reads, issueFacts, opportunityFacts,
                nextSteps, now);
    }

    /* ─────────────────────────── coverage ─────────────────────────── */

    /** The channels this org may be asked about, and what each can say per type AT GENERATION TIME. */
    private record Coverage(Map<UUID, Channel> byId, Map<String, ChannelCoverageRow> rows) {

        ChannelCoverageRow rowOf(Channel channel, String dataType) {
            return rows.get(channel.getCode() + "|" + dataType);
        }

        ChannelDataState stateOf(Channel channel, String dataType) {
            ChannelCoverageRow row = rowOf(channel, dataType);
            return row == null ? ChannelDataState.NOT_CONNECTED : row.state();
        }

        Instant lastReadAt(Channel channel, String dataType) {
            ChannelCoverageRow row = rowOf(channel, dataType);
            return row == null ? null : row.lastSuccessfulSyncAt();
        }
    }

    private Coverage coverage(UUID orgId) {
        List<String> codes = visibleChannels.codesFor(orgId);
        Map<UUID, Channel> byId = new LinkedHashMap<>();
        for (Channel channel : channels.findAll()) {
            if (codes.contains(channel.getCode())) {
                byId.put(channel.getId(), channel);
            }
        }
        Map<String, ChannelCoverageRow> rows = new HashMap<>();
        for (ChannelCoverageRow row : coverage.coverage(orgId, codes)) {
            rows.put(row.channelCode() + "|" + row.dataType(), row);
        }
        return new Coverage(byId, rows);
    }

    /* ─────────────────────────── figures ─────────────────────────── */

    /**
     * One window of one data type, after the gate.
     *
     * @param primary the first summed column over the channels that qualified
     * @param secondary the second (부정 리뷰 · 매출), over the same channels
     * @param measured whether ANY channel qualified — false means no number may be stored
     */
    private record Reading(long primary, long secondary, boolean measured, int excludedChannels,
                           boolean unproven, ReportFacts.Read read, Map<UUID, long[]> countedByChannel) {
    }

    private record Figures(List<ReportFacts.Counter> counters, List<ReportFacts.ChannelSales> salesByChannel,
                           List<ReportFacts.Read> reads, Reading reviewsNow) {
    }

    private Figures figures(UUID orgId, ReportPeriod p, Coverage cover) {
        Reading reviewsNow = read(orgId, p.start(), p.end(), TYPE_REVIEW, cover);
        Reading reviewsPrev = read(orgId, p.previousStart(), p.previousEnd(), TYPE_REVIEW, cover);
        Reading inquiriesNow = read(orgId, p.start(), p.end(), TYPE_INQUIRY, cover);
        Reading inquiriesPrev = read(orgId, p.previousStart(), p.previousEnd(), TYPE_INQUIRY, cover);
        Reading ordersNow = read(orgId, p.start(), p.end(), TYPE_ORDER, cover);
        Reading ordersPrev = read(orgId, p.previousStart(), p.previousEnd(), TYPE_ORDER, cover);

        long unansweredHeld = inquiries.countUnansweredOperational(orgId);

        List<ReportFacts.Counter> counters = List.of(
                periodic(COUNTER_REVIEWS, "받은 리뷰", "건", TYPE_REVIEW, reviewsNow, reviewsPrev, false),
                periodic(COUNTER_NEGATIVE_REVIEWS, "부정 리뷰", "건", TYPE_REVIEW, reviewsNow, reviewsPrev, true),
                periodic(COUNTER_INQUIRIES, "받은 문의", "건", TYPE_INQUIRY, inquiriesNow, inquiriesPrev, false),
                periodic(COUNTER_ORDERS, "주문", "건", TYPE_ORDER, ordersNow, ordersPrev, false),
                periodic(COUNTER_REVENUE, "매출", "원", TYPE_ORDER, ordersNow, ordersPrev, true),
                // No previous, no delta: this is what is waiting NOW, not a figure of the period. It is
                // also not windowed, so the period's coverage says nothing about it.
                new ReportFacts.Counter(COUNTER_UNANSWERED_NOW, "현재 답변이 필요한 문의", false, unansweredHeld,
                        null, null, unansweredHeld > 0 ? UNANSWERED_PATH : null, "건", TYPE_INQUIRY, 0, false));

        List<ReportFacts.ChannelSales> salesByChannel = new ArrayList<>();
        if (ordersNow.measured()) {
            for (Map.Entry<UUID, long[]> entry : ordersNow.countedByChannel().entrySet()) {
                Channel channel = cover.byId().get(entry.getKey());
                if (channel == null || entry.getValue()[1] <= 0) {
                    continue;
                }
                salesByChannel.add(new ReportFacts.ChannelSales("s-" + channel.getCode(), channel.getCode(),
                        channel.getNameKo(), entry.getValue()[1]));
            }
            salesByChannel.sort(Comparator.comparingLong(ReportFacts.ChannelSales::amount).reversed());
        }

        // The window the report is ABOUT is the current one; the previous window's exclusions are the
        // reason a comparison is missing, which the missing delta already says.
        List<ReportFacts.Read> reads = List.of(reviewsNow.read(), inquiriesNow.read(), ordersNow.read());
        return new Figures(counters, List.copyOf(salesByChannel), reads, reviewsNow);
    }

    /**
     * A period figure, and the two ways it can fail to be one.
     *
     * <p>No qualifying channel in this window ⇒ {@code current} is null. No qualifying channel in the
     * window before ⇒ {@code previous} is null. A delta needs both, which is the whole point: 「0건,
     * 이전 기간보다 4건 줄음」 was a comparison between a measurement and a silence.
     */
    private static ReportFacts.Counter periodic(String id, String label, String unit, String dataType,
                                                Reading now, Reading previous, boolean secondColumn) {
        Long current = now.measured() ? (secondColumn ? now.secondary() : now.primary()) : null;
        Long before = previous.measured() ? (secondColumn ? previous.secondary() : previous.primary()) : null;
        Long delta = current != null && before != null ? current - before : null;
        return new ReportFacts.Counter(id, label, true, current, before, delta, null, unit, dataType,
                now.excludedChannels(), now.unproven());
    }

    /**
     * One window of one type: per-channel sums, gated by the Overview's own predicate.
     *
     * <p>The series are per (channel × day), so the gate is applied where it belongs — per channel —
     * and the total is the sum over the channels that passed it. A channel that is excluded keeps its
     * rows; it just does not stand inside a total that would then be read as complete.
     */
    private Reading read(UUID orgId, LocalDate from, LocalDate to, String dataType, Coverage cover) {
        Map<UUID, long[]> rows = new LinkedHashMap<>();
        for (Object[] row : series(orgId, from, to, dataType)) {
            long[] sums = rows.computeIfAbsent((UUID) row[0], k -> new long[2]);
            sums[0] += ((Number) row[2]).longValue();
            sums[1] += ((Number) row[3]).longValue();
        }

        long primary = 0;
        long secondary = 0;
        boolean measured = false;
        boolean unproven = false;
        Instant lastReadAt = null;
        List<ReportFacts.ReadChannel> included = new ArrayList<>();
        List<ReportFacts.ReadChannel> excluded = new ArrayList<>();
        Map<UUID, long[]> counted = new LinkedHashMap<>();
        for (Channel channel : cover.byId().values()) {
            ChannelDataState state = cover.stateOf(channel, dataType);
            Instant channelRead = cover.lastReadAt(channel, dataType);
            if (channelRead != null && (lastReadAt == null || channelRead.isAfter(lastReadAt))) {
                lastReadAt = channelRead;
            }
            long[] sums = rows.getOrDefault(channel.getId(), new long[2]);
            // The operand is the Overview's, per type: 리뷰·문의 are judged on what arrived, 주문 on
            // count-or-amount, because a day can carry an amount under a count the connector did not send.
            long rowsInWindow = TYPE_ORDER.equals(dataType) ? sums[0] + sums[1] : sums[0];
            if (!OperationsMetricsService.counted(state, rowsInWindow)) {
                excluded.add(new ReportFacts.ReadChannel(channel.getCode(), channel.getNameKo(), state, channelRead,
                        OperationsMetricsService.exclusionReasonKo(state)));
                continue;
            }
            measured = true;
            primary += sums[0];
            secondary += sums[1];
            counted.put(channel.getId(), sums);
            unproven |= state != ChannelDataState.OBSERVED_FRESH && state != ChannelDataState.ZERO;
            included.add(new ReportFacts.ReadChannel(channel.getCode(), channel.getNameKo(), state, channelRead, null));
        }
        ReportFacts.Read read = new ReportFacts.Read(dataType, TYPE_LABEL.getOrDefault(dataType, dataType),
                lastReadAt, measured, List.copyOf(included), List.copyOf(excluded));
        return new Reading(primary, secondary, measured, excluded.size(), unproven, read, counted);
    }

    /** {@code [channelId, day, primary, secondary]} — received/negative · received/unanswered · orders/sales. */
    private List<Object[]> series(UUID orgId, LocalDate from, LocalDate to, String dataType) {
        return switch (dataType) {
            case TYPE_REVIEW -> metrics.reviewSeries(orgId, from, to, false);
            case TYPE_INQUIRY -> metrics.inquirySeries(orgId, from, to, false);
            default -> metrics.orderSeries(orgId, from, to, false);
        };
    }

    /* ─────────────────────────── issues ─────────────────────────── */

    private List<ReportFacts.IssueFact> issueFacts(UUID orgId, ReportPeriod p, boolean measured) {
        Map<UUID, Long> now = countsByIssue(orgId, p.start(), p.end());
        Map<UUID, Long> previous = countsByIssue(orgId, p.previousStart(), p.previousEnd());
        List<ReportFacts.IssueFact> out = new ArrayList<>();
        for (ReviewIssueView issue : issues.list(orgId, p.end())) {
            long current = now.getOrDefault(issue.id(), 0L);
            long before = previous.getOrDefault(issue.id(), 0L);
            if (current == 0 && before == 0) {
                continue;
            }
            out.add(new ReportFacts.IssueFact("i-" + issue.id(), issue.id(), issue.title(), issue.severity(),
                    SEVERITY_KO.getOrDefault(issue.severity(), issue.severity()), current, before, current - before,
                    issue.change().labelsKo(), issue.dominantProductId(), issue.dominantProductName(),
                    // 근거는 리뷰다. 리뷰 창이 측정되지 않았으면 이 건수도 「가진 것」이지 「있었던 것」이 아니다.
                    "/memory/" + issue.id(), measured));
        }
        out.sort(Comparator.comparingLong(ReportFacts.IssueFact::current).reversed()
                .thenComparing(ReportFacts.IssueFact::title));
        return List.copyOf(out);
    }

    private Map<UUID, Long> countsByIssue(UUID orgId, LocalDate from, LocalDate to) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : evidence.issueCountsInWindow(orgId, from, to)) {
            counts.put(UUID.fromString(String.valueOf(row[0])), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private List<ReportFacts.OpportunityFact> opportunityFacts(UUID orgId, ReportPeriod p) {
        List<ReportFacts.OpportunityFact> out = new ArrayList<>();
        for (OpportunityView o : opportunities.list(orgId, p.end(), null, null, false)) {
            out.add(new ReportFacts.OpportunityFact("o-" + o.issueId() + "-" + o.kind(), o.issueId(), o.kind(),
                    o.kindLabelKo(), o.status(), o.statusLabelKo(), o.issueTitle(), o.productId(), o.productName(),
                    o.recommendationKo(), o.nextActionKo(), o.evidenceTo()));
        }
        return List.copyOf(out);
    }

    /**
     * Prepared next steps, each an existing object: the unanswered queue, an opportunity's own next
     * action, or an issue's evidence page when it rose and nothing is proposed for it yet.
     */
    private static List<ReportFacts.NextStep> nextSteps(List<ReportFacts.Counter> counters,
                                                        List<ReportFacts.IssueFact> issues,
                                                        List<ReportFacts.OpportunityFact> opportunities) {
        List<ReportFacts.NextStep> out = new ArrayList<>();
        int n = 1;
        for (ReportFacts.Counter c : counters) {
            if (COUNTER_UNANSWERED_NOW.equals(c.id()) && c.current() != null && c.current() > 0) {
                // <b>The count stays on the counter; the CTA does not carry it</b> (Pilot QA, 2026-09-06).
                // A report is a frozen edition — it prints its own as-of — but this step's destination
                // is the LIVE inquiry screen. Baking the snapshot number into the label made the two
                // disagree in front of the seller: 「답변이 필요한 문의 22건 처리하기」 opened a screen
                // reading 24, two days after the edition was cut. The number is preserved where it is
                // true (the counter fact, cited by this step's trace); the label names the destination.
                out.add(new ReportFacts.NextStep("n-" + n++, "답변이 필요한 문의 보기", UNANSWERED_PATH, List.of(c.id())));
            }
        }
        Set<UUID> proposed = new LinkedHashSet<>();
        for (ReportFacts.OpportunityFact o : opportunities) {
            proposed.add(o.issueId());
            String label = "ACCEPTED".equals(o.status())
                    ? o.issueTitle() + " — 준비된 " + o.kindLabelKo() + " 초안 마무리하기"
                    : o.issueTitle() + " — " + o.nextActionKo();
            out.add(new ReportFacts.NextStep("n-" + n++, label, o.to(), List.of(o.id())));
        }
        for (ReportFacts.IssueFact i : issues) {
            // 「늘었으니 확인하라」는 두 창을 다 읽었을 때만 할 수 있는 말이다.
            if (i.measured() && i.delta() > 0 && !proposed.contains(i.issueId())) {
                out.add(new ReportFacts.NextStep("n-" + n++,
                        "「" + i.title() + "」 근거 리뷰 확인하기 (이번 기간 " + i.current() + "건)", i.to(), List.of(i.id())));
            }
        }
        return List.copyOf(out);
    }
}
