package com.sellerops.report;

import com.sellerops.report.ReportSummary.Kind;
import com.sellerops.report.ReportSummary.Line;
import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic reading of a {@link ReportFacts} — every sentence a pure function of the values,
 * typed as fact, interpretation or limit, and citing the ids it reads.
 *
 * <p>Nothing here names a cause or an outcome. 「원인은 리뷰가 말해주지 않습니다」 is the one sentence
 * about causes the report ever prints, and it says the opposite of one.
 *
 * <p><b>An unmeasured figure produces no sentence</b> (2026-10-06). These lines are STORED, so a
 * sentence written over a window nobody read outlives the screen that drew it — which is exactly what
 * happened: the stored weekly summary read 「2026년 9월 28일 ~ 10월 4일에 리뷰 0건, 문의 0건이
 * 들어왔습니다」 for a period whose last successful collection predated it by eight days. A null figure
 * is skipped rather than printed as a zero, and when every figure is null the summary says that it does
 * not know, never that nothing happened.
 */
public final class ReportSummaryComposer {

    /** How many repeated issues the summary names before pointing at the list. */
    static final int ISSUE_LINES = 3;

    private ReportSummaryComposer() {
    }

    public static ReportSummary compose(ReportFacts facts) {
        List<Line> lines = new ArrayList<>();
        boolean anything = false;

        ReportFacts.Counter reviews = counter(facts, ReportFactsBuilder.COUNTER_REVIEWS);
        ReportFacts.Counter inquiries = counter(facts, ReportFactsBuilder.COUNTER_INQUIRIES);
        ReportFacts.Counter negative = counter(facts, ReportFactsBuilder.COUNTER_NEGATIVE_REVIEWS);
        ReportFacts.Counter orders = counter(facts, ReportFactsBuilder.COUNTER_ORDERS);
        ReportFacts.Counter revenue = counter(facts, ReportFactsBuilder.COUNTER_REVENUE);
        ReportFacts.Counter unanswered = counter(facts, ReportFactsBuilder.COUNTER_UNANSWERED_NOW);

        boolean reviewsRead = measured(reviews);
        boolean inquiriesRead = measured(inquiries);
        if (reviewsRead || inquiriesRead) {
            lines.add(new Line(String.format("%s에 %s이 들어왔습니다%s.", facts.period().labelKo(),
                    arrivals(reviews, inquiries), against(reviews, inquiries)), Kind.FACT,
                    citing(reviews, inquiries)));
            anything |= value(reviews) > 0 || value(inquiries) > 0;
        }
        for (ReportFacts.Counter c : java.util.Arrays.asList(negative, orders, revenue)) {
            if (c != null && c.delta() != null && c.delta() != 0) {
                lines.add(new Line(String.format("%s은(는) 이전 기간보다 %s %s (%s → %s).", c.labelKo(),
                        amount(Math.abs(c.delta()), c), c.delta() > 0 ? "늘었습니다" : "줄었습니다",
                        amount(c.previous(), c), amount(c.current(), c)), Kind.FACT, List.of(c.id())));
                anything = true;
            }
        }

        boolean anyRose = false;
        int named = 0;
        for (ReportFacts.IssueFact issue : facts.issues()) {
            if (named >= ISSUE_LINES) {
                break;
            }
            // 근거는 리뷰다. 리뷰 창이 측정되지 않았으면 「있었습니다」도 「늘었습니다」도 쓸 수 없다.
            if (issue.current() == 0 || !issue.measured()) {
                continue;
            }
            lines.add(new Line(String.format("「%s」 관련 리뷰가 %d건 있었습니다 (이전 기간 %d건).", issue.title(),
                    issue.current(), issue.previous()), Kind.FACT, List.of(issue.id())));
            if (issue.delta() > 0 && issue.current() >= 2) {
                lines.add(new Line(String.format("「%s」 관련 리뷰 증가를 확인할 필요가 있습니다.", issue.title()),
                        Kind.INTERPRETATION, List.of(issue.id())));
                anyRose = true;
            }
            named++;
            anything = true;
        }
        if (anyRose) {
            lines.add(new Line("늘어난 원인은 리뷰가 말해주지 않습니다. 근거 리뷰를 직접 확인해 주세요.", Kind.LIMIT,
                    facts.issues().stream().filter(i -> i.measured() && i.delta() > 0 && i.current() >= 2)
                            .map(ReportFacts.IssueFact::id).toList()));
        }
        if (facts.issues().size() > named && named > 0) {
            lines.add(new Line(String.format("그 외 반복된 문제 %d건은 아래 목록에 있습니다.", facts.issues().size() - named),
                    Kind.FACT, facts.issues().stream().skip(named).map(ReportFacts.IssueFact::id).toList()));
        }

        if (!facts.opportunities().isEmpty()) {
            long open = facts.opportunities().stream().filter(o -> "OPEN".equals(o.status())).count();
            lines.add(new Line(String.format("개선 기회 %d건이 제안되어 있습니다%s.", facts.opportunities().size(),
                    open > 0 ? " (검토 전 " + open + "건)" : ""), Kind.FACT,
                    facts.opportunities().stream().map(ReportFacts.OpportunityFact::id).toList()));
            anything = true;
        }
        if (unanswered != null && value(unanswered) > 0) {
            /*
              「지금」은 저장되는 문장이 쓸 수 없는 말이다 (2026-10-06). 이 수치는 기간이 없을 뿐 시점이
              없는 것은 아니다 — 판을 만든 그 순간의 값이고, 사흘 뒤에 그 판을 열면 「지금」은 더 이상
              그 순간이 아니다. 기간이 없다는 사실과, 시점이 고정돼 있다는 사실을 함께 적는다.
            */
            lines.add(new Line(String.format("이 판을 만든 시점에 답변이 필요한 문의가 %d건이었습니다.", value(unanswered)),
                    Kind.FACT, List.of(unanswered.id())));
        }

        if (!reviewsRead && !inquiriesRead) {
            /*
              <b>읽지 못한 기간에는 「달라진 것이 없습니다」를 쓸 수 없다.</b> 그 문장은 측정된 침묵에만
              해당하고, 여기서는 측정 자체가 없다. 빠진 채널은 facts.exclusions()가 이름으로 들고 있으므로
              문장은 사실 하나만 말한다 — 이 기간을 읽지 못했다.
            */
            lines.add(0, new Line(String.format("%s의 수집 결과를 확인하지 못해, 이 기간에 무엇이 들어왔는지 알 수 없습니다.",
                    facts.period().labelKo()), Kind.LIMIT, citing(reviews, inquiries)));
        } else if (!anything) {
            // One sentence replaces the zero-count restatement: 「리뷰 0건, 문의 0건」 under 「달라진 것이
            // 없습니다」 says the same thing twice. The standing unanswered line, if any, stays.
            lines.removeIf(l -> l.kind() == Kind.FACT && l.factIds().contains(ReportFactsBuilder.COUNTER_REVIEWS));
            lines.add(0, new Line(String.format("%s에는 달라진 것이 없습니다 — 새 리뷰·문의·반복 문제가 확인되지 않았습니다.",
                    facts.period().labelKo()), Kind.FACT, factIdsOf(facts)));
        }
        return new ReportSummary(List.copyOf(lines));
    }

    /** 「리뷰 81건, 문의 5건」 — only the halves that were measured appear. */
    private static String arrivals(ReportFacts.Counter reviews, ReportFacts.Counter inquiries) {
        List<String> parts = new ArrayList<>();
        if (measured(reviews)) {
            parts.add("리뷰 " + reviews.current() + "건");
        }
        if (measured(inquiries)) {
            parts.add("문의 " + inquiries.current() + "건");
        }
        return String.join(", ", parts);
    }

    /** 「 (이전 기간 리뷰 65건, 문의 1건)」, or nothing at all when neither window gave a reading. */
    private static String against(ReportFacts.Counter reviews, ReportFacts.Counter inquiries) {
        List<String> parts = new ArrayList<>();
        if (measured(reviews) && reviews.previous() != null) {
            parts.add("리뷰 " + reviews.previous() + "건");
        }
        if (measured(inquiries) && inquiries.previous() != null) {
            parts.add("문의 " + inquiries.previous() + "건");
        }
        return parts.isEmpty() ? "" : " (이전 기간 " + String.join(", ", parts) + ")";
    }

    /** 「81건」 · 「3,884,590원」 — the unit rides on the fact, so 매출 and 주문 do not share a noun. */
    private static String amount(long value, ReportFacts.Counter c) {
        return "원".equals(c.unitOrDefault()) ? String.format("%,d원", value) : value + "건";
    }

    private static boolean measured(ReportFacts.Counter c) {
        return c != null && c.current() != null;
    }

    private static long value(ReportFacts.Counter c) {
        return c == null || c.current() == null ? 0L : c.current();
    }

    private static List<String> citing(ReportFacts.Counter... counters) {
        List<String> ids = new ArrayList<>();
        for (ReportFacts.Counter c : counters) {
            if (c != null) {
                ids.add(c.id());
            }
        }
        return List.copyOf(ids);
    }

    private static ReportFacts.Counter counter(ReportFacts facts, String id) {
        for (ReportFacts.Counter c : facts.counters()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> factIdsOf(ReportFacts facts) {
        return facts.counters().stream().map(ReportFacts.Counter::id).toList();
    }
}
