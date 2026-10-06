package com.sellerops.report;

import com.sellerops.coverage.ChannelDataState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything a report may say, as VALUES with ids — the snapshot that is stored and the only thing a
 * sentence may cite.
 *
 * <p><b>Every fact has an id, and that is the whole design.</b> A sentence in the report is admitted
 * only if it names the fact ids it rests on, and every id it names must exist here. So 「접착 관련
 * 리뷰가 이전 기간보다 늘었습니다」 traces to an {@code i-…} row with {@code current} and
 * {@code previous}, and 「생산 품질이 나빠졌습니다」 has nothing to cite and is refused. The seller can
 * click from any line to the object behind it because the object's link is part of the fact.
 *
 * <p><b>And a fact may be ABSENT</b> (2026-10-06, product-owner decision). Until then every figure was
 * a {@code long}, so a window nobody had read was stored as a zero and cited as one: the live weekly
 * report said 「받은 리뷰 0건」, 「받은 문의 0건 · 이전 기간보다 4건 줄음」 for 9/28–10/4 while the last
 * successful collection of either type was 9월 26일. A {@code null} figure is the shape absence needs,
 * and {@link #exclusions} is where the reason lives — the same pair the Overview has always shipped
 * ({@code MetricKpi} + {@code MetricExclusion}).
 *
 * <p>No customer text and no seller row beyond what the screens already print: counts, dates,
 * closed-vocabulary issue titles, the seller's own product names, opportunity labels.
 */
public record ReportFacts(Period period, List<Counter> counters, List<ChannelSales> salesByChannel,
                          List<Read> reads, List<IssueFact> issues,
                          List<OpportunityFact> opportunities, List<NextStep> nextSteps,
                          Instant generatedAt) {

    /**
     * Rows written before a list existed read back as {@code null}; no reader should have to ask
     * whether a snapshot is old enough to lack one.
     */
    public ReportFacts {
        counters = counters == null ? List.of() : counters;
        salesByChannel = salesByChannel == null ? List.of() : salesByChannel;
        reads = reads == null ? List.of() : reads;
        issues = issues == null ? List.of() : issues;
        opportunities = opportunities == null ? List.of() : opportunities;
        nextSteps = nextSteps == null ? List.of() : nextSteps;
    }

    public record Period(String kind, String kindLabelKo, LocalDate start, LocalDate end, String labelKo,
                         LocalDate previousStart, LocalDate previousEnd) {
    }

    /**
     * One number over the period, with the same number over the previous period when the number is a
     * period figure.
     *
     * <p>{@code periodic=false} is a figure that has no period — 미답변 문의 is what is waiting NOW, and
     * the report says so rather than inventing last week's backlog.
     *
     * <p><b>{@code current == null} means the window was never read</b>, which is not a zero and not a
     * subject for comparison. It is what {@link ReportFactsBuilder} stores when no channel qualified to
     * contribute under {@code OperationsMetricsService.counted} — summing an empty set of qualifying
     * channels is not a measurement. {@code previous == null} says the same of the window before, and
     * {@code delta} exists only when BOTH were measured.
     *
     * @param to where this exact figure is shown, or null when no screen shows exactly it
     * @param unit 건 / 원 — 매출 shares this record, and the number means nothing without it
     * @param dataType which collection this figure rests on (REVIEW · INQUIRY · ORDER_SUMMARY)
     * @param excludedChannels how many channels were left out of it, named in {@link #reads}
     * @param unproven whether a channel that IS counted has collection that is not provably current
     */
    public record Counter(String id, String labelKo, boolean periodic, Long current, Long previous, Long delta,
                          String to, String unit, String dataType, int excludedChannels, boolean unproven) {

        /** Legacy rows carry no unit; everything that existed before 매출 was counted in 건. */
        public String unitOrDefault() {
            return unit == null || unit.isBlank() ? "건" : unit;
        }
    }

    /** One channel's share of the period's 매출, under the same gate as the total it belongs to. */
    public record ChannelSales(String id, String channelCode, String channelNameKo, long amount) {
    }

    /**
     * <b>이 판이 한 종류를 어디까지 읽고 세었는가</b> — 생성 시점의 coverage를 그대로 얼린 것
     * (2026-10-06, product-owner decision).
     *
     * <p>이것이 스냅샷 안에 있어야 하는 이유는 하나다. 숫자는 얼어 있는데 그 숫자의 <b>근거</b>가 라이브
     * 읽기에서 오면, 같은 판을 내일 열었을 때 「읽은 범위」만 혼자 움직인다 — 9월 27일까지 읽고 만든 판이
     * 내일은 10월 8일까지 읽은 것처럼 보인다. 사실과 그 사실의 자격은 같은 시각에 얼어야 한다.
     *
     * @param lastReadAt 생성 시점에 이 종류에서 가장 최근에 성공한 수집. 하나도 없으면 null
     * @param measured 이 기간을 계산할 수 있었는가 — 자격을 갖춘 채널이 하나라도 있었는가
     * @param included 합계에 선 채널
     * @param excluded 빠진 채널과 그 이유 — 빠진 사실을 흔적 없이 지우지 않는다
     */
    public record Read(String dataType, String labelKo, Instant lastReadAt, boolean measured,
                       List<ReadChannel> included, List<ReadChannel> excluded) {

        public Read {
            included = included == null ? List.of() : included;
            excluded = excluded == null ? List.of() : excluded;
        }
    }

    /**
     * One channel's standing for one data type at generation time.
     *
     * <p>{@code reasonKo} is null for an included channel and carries {@code MetricExclusion}'s own
     * wording for an excluded one — the reason comes from that class's method, not from a second
     * vocabulary.
     */
    public record ReadChannel(String channelCode, String channelNameKo, ChannelDataState state,
                              Instant lastReadAt, String reasonKo) {
    }

    /**
     * One repeated issue's evidence over the period, against the previous period.
     *
     * @param measured whether review collection actually covered this window — when false the counts
     *                 are what we happen to hold, not what happened, and no rise may be claimed
     */
    public record IssueFact(String id, UUID issueId, String title, String severity, String severityLabelKo,
                            long current, long previous, long delta, List<String> changeLabelsKo,
                            UUID productId, String productName, String to, boolean measured) {
    }

    /** One improvement opportunity standing at generation time — its own object, cited by id. */
    public record OpportunityFact(String id, UUID issueId, String kind, String kindLabelKo, String status,
                                  String statusLabelKo, String issueTitle, UUID productId, String productName,
                                  String recommendationKo, String nextActionKo, String to) {
    }

    /**
     * A prepared next step, always pointing at an EXISTING object (a queue, an issue, an opportunity)
     * and always citing the facts it follows from. There is no to-do table behind this.
     */
    public record NextStep(String id, String labelKo, String to, List<String> factIds) {
    }

    /** All fact ids, for the guard that admits sentences. */
    public java.util.Set<String> factIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        counters.forEach(c -> ids.add(c.id()));
        salesByChannel.forEach(s -> ids.add(s.id()));
        issues.forEach(i -> ids.add(i.id()));
        opportunities.forEach(o -> ids.add(o.id()));
        nextSteps.forEach(n -> ids.add(n.id()));
        return ids;
    }
}
