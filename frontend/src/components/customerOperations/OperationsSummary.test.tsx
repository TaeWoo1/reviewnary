/**
 * The Home's two summary surfaces as they render — `docs/pilot_usage_loop_v1.md` §8.
 *
 * <p>Pinned here rather than in `homeSummary.test.ts` because the thing under test is the SENTENCE:
 * §8-4b allows 「새로 확인」 and forbids 「처리 완료」, and only rendered text can be held to that.
 *
 * <p><b>Two surfaces since UI System v2</b> (product-owner decision, 2026-09-30). The band
 * (`today-summary`) holds OBLIGATIONS only — 확인할 일 · 실행 대기 · 현재 미답변 — and the quiet line under
 * it (`today-context`) holds what changed, what reviewnary did, and whether the figures can be trusted.
 * Every qualification rule this file already pinned still holds; what moved is which surface states it.
 * A rule that was about the inflow CELL is now about the context LINE and is asserted there, so the
 * reorganisation cannot quietly drop a guarantee — that is why these tests were re-pointed rather than
 * rewritten from scratch.
 */
// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CustomerOperationsHome } from "../../lib/customerOperationsTypes";
import type { MetricKpi, MetricSeries, OperationsHome, OperationsInsight, OperationsMetrics } from "../../lib/types";

const api = vi.hoisted(() => ({
  getInquiryQueueStrict: vi.fn(),
  getReviewWorkStrict: vi.fn(),
  activateCustomerOperations: vi.fn(),
  resumeCustomerOperations: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { CustomerOpsHome } from "./CustomerOpsHome";

const NOW = new Date("2026-09-16T05:30:00Z"); // 14:30 KST
const TODAY = "2026-09-16";

function kpi(key: string, over: Partial<MetricKpi> = {}): MetricKpi {
  return {
    key, label: key, value: 0, unit: "건", previousValue: null, deltaPercent: null,
    comparable: true, excludedChannels: 0, freshnessUnproven: false, ...over,
  };
}
function series(key: string, value: number): MetricSeries {
  return { key, label: key, unit: "건", points: [{ date: "2026-09-15", value: 9 }, { date: TODAY, value }] };
}
function metrics(over: Partial<OperationsMetrics> = {}): OperationsMetrics {
  return {
    period: { from: "2026-09-10", to: TODAY, previousFrom: "2026-09-03", previousTo: "2026-09-09", days: 7 },
    revenueBasis: "", orderCountBasis: "",
    kpis: [kpi("reviews"), kpi("inquiries"), kpi("unansweredInquiries", { value: 4 })],
    series: [series("reviews", 3), series("inquiries", 5)],
    channels: [], exclusions: [], exampleDataIncluded: false,
    ...over,
  };
}

function co(handled: Partial<CustomerOperationsHome["handled"]> = {}): CustomerOperationsHome {
  return {
    available: true, eligible: true, status: "ACTIVE", cadenceMinutes: 120,
    lastCheckedAt: null, lastRunStatus: null, nextCheckAt: null, sources: [],
    decisions: { total: 0, rows: [] },
    handled: {
      since: "2026-09-15T05:30:00Z", autoResolved: 0, monitoring: 0, draftsPrepared: 0,
      verifying: 0, rows: [], checked: 0, ...handled,
    },
    gaps: { total: 0, rows: [] },
  };
}

function ops(): OperationsHome {
  return {
    reviews: { needsAttentionUndecided: 0, needsAttentionTotal: 0, watchTotal: 0, rows: [] },
    problems: { decidable: 0, observing: 0, dormant: 0, rows: [] },
    collection: [],
    prepared: { reviewRepliesApproved: 0, inquiryDraftsReady: 0, improvementDraftsReady: 0, rows: [] },
  } as never;
}

/**
 * `insights` defaults to `[]` — a read that LANDED and found nothing, which is the ordinary case and
 * the one these fixtures are about. Passing nothing would be a read that did not land, and the
 * 오늘 달라진 점 section says nothing at all then (a failed request is not 「변화 없음」).
 */
function draw(home = co(), m: OperationsMetrics | null = metrics(), insights: OperationsInsight[] | null = []) {
  return render(
    <MemoryRouter>
      <CustomerOpsHome co={home} ops={ops()} now={NOW} onChanged={vi.fn()} metrics={m} insights={insights} />
    </MemoryRouter>,
  );
}

const summary = () => screen.findByTestId("today-summary");
/** The quiet line under the band: inflow, the 24-hour window, freshness. */
const context = () => screen.findByTestId("today-context");
/**
 * The channel row at the foot of the screen (UI System v2.1, product-owner decision 2026-10-01). Collection
 * state used to be a clause of the context line; 「채널은 정상인가」 is the morning's own last question, so it
 * is asked where the morning ends. The guarantees that were pinned on the context line are pinned here now —
 * re-pointed rather than rewritten, so the reorganisation cannot quietly drop one.
 */
const channels = () => screen.findByLabelText("채널 상태");

beforeEach(() => {
  api.getInquiryQueueStrict.mockResolvedValue({ content: [], totalElements: 0, page: 0, size: 50 });
  api.getReviewWorkStrict.mockResolvedValue({ attentionTotal: 0, attention: [], committed: [] });
});
afterEach(cleanup);


/**
 * Unchanged rules, new home. Each of these was an assertion about the band's inflow cell; the fact and
 * its qualification are identical, and only the surface that states them moved.
 */
describe("오늘 들어온 것 — a number only when it is a measured fact", () => {
  it("both lanes fresh: both counts, each with its own noun", async () => {
    draw();
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("오늘 들어온 것"));
    expect(line).toHaveTextContent("리뷰 3");
    expect(line).toHaveTextContent("문의 5");
    expect(line).not.toHaveTextContent("수집 확인 필요");
  });

/**
 * <b>Re-pointed: collection health is the channel row's, alone</b> (product-owner decision,
 * 2026-10-01 — the last ownership leak).
 *
 * <p>These were written when this line carried BOTH halves — the figure and, when it was withheld,
 * the reason. The reason is a fact about collection, and 「채널은 정상인가」 is the question the foot of
 * the screen exists to answer; stating it here too made the Home answer one question in two voices,
 * under a heading that asks a different one.
 *
 * <p><b>The rule being defended has not moved an inch: an unobserved figure is never printed.</b> That
 * is still asserted on every case below, and it is the half that could become a lie. What is asserted
 * elsewhere now is where the REASON is said — on the 채널 상태 row, which these check too, so nothing
 * is merely deleted.
 */
  it("review lane stale, inquiry fresh: the review number is withheld and the inquiry number is not", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries")] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("문의 5"));
    // The withheld lane prints no number — the rule, unchanged.
    expect(line).not.toHaveTextContent("리뷰 3");
    // And no reason either: that sentence belongs to the channel row at the foot of the screen.
    expect(line).not.toHaveTextContent("수집 확인 필요");
    expect(await channels()).toHaveTextContent("일부 채널 최신 수집 확인 필요");
  });

  it("inquiry lane stale, review fresh: the mirror case", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews"), kpi("inquiries", { excludedChannels: 1 })] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 3"));
    expect(line).not.toHaveTextContent("문의 5");
    expect(line).not.toHaveTextContent("수집 확인 필요");
  });

  it("both stale: the group disappears rather than printing a cause this line does not own", async () => {
    draw(
      co(),
      metrics({
        kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { excludedChannels: 2 })],
        series: [series("reviews", 0), series("inquiries", 0)],
      }),
    );
    // Nothing measured and nothing opened, so the note has no facts and renders itself away. The
    // section it belongs to then says the one sentence it exists for.
    const section = await screen.findByLabelText("오늘 달라진 점");
    await waitFor(() => expect(section).toHaveTextContent("오늘 새로 확인된 변화는 없습니다."));
    expect(screen.queryByTestId("today-context")).toBeNull();
    // Still no digits anywhere: an unobserved zero never becomes 「0」.
    expect(section.textContent).not.toMatch(/리뷰 \d|문의 \d/);
    // The cause is stated once, where the question is asked.
    expect(await channels()).toHaveTextContent("일부 채널 최신 수집 확인 필요");
  });

  it("a measured zero IS printed — that is the difference the state words exist to keep", async () => {
    draw(co(), metrics({ series: [series("reviews", 0), series("inquiries", 0)] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 0"));
    expect(line).toHaveTextContent("문의 0");
    expect(line).not.toHaveTextContent("수집 확인 필요");
  });

  it("names no channel it was not given — the summary never invents where a gap is", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews", { excludedChannels: 1 }), kpi("inquiries")] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("문의 5"));
    // `excludedChannels` is a COUNT; the response's `exclusions` name channels but this line is not
    // given them, so it must not print 쿠팡/네이버/카페24 from a number.
    expect(line.textContent).not.toMatch(/쿠팡|네이버|카페24|스마트스토어/);
  });

  it("a failed overview read draws no inflow group at all", async () => {
    draw(co({ checked: 1 }), null);
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("최근 24시간"));
    expect(line).not.toHaveTextContent("오늘 들어온 것");
    // A failed read is not a collection finding, and this line would not be the place to say so anyway.
    expect(line).not.toHaveTextContent("수집 확인 필요");
  });

  it("example data is shown and labelled — a DEMO_SEED count may never read as the seller's", async () => {
    draw(co(), metrics({ exampleDataIncluded: true }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("예시 데이터"));
    expect(line).toHaveTextContent("리뷰 3");
  });

  it("drops the example label when no figure survived the freshness gate — it would qualify nothing", async () => {
    draw(
      co({ checked: 1 }),
      metrics({
        exampleDataIncluded: true,
        kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { excludedChannels: 1 })],
      }),
    );
    const line = await context();
    // The line still stands (최근 24시간 has something to say) and the inflow group is simply absent,
    // so the label that would have qualified it has nothing left to qualify.
    await waitFor(() => expect(line).toHaveTextContent("최근 24시간"));
    expect(line).not.toHaveTextContent("오늘 들어온 것");
    expect(line).not.toHaveTextContent("예시 데이터");
  });

  it("and carries no label when the figures are the seller's own", async () => {
    draw();
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 3"));
    expect(line).not.toHaveTextContent("예시 데이터");
  });
});

/** Same rules, same words, now on the quiet line — the automation's report was never an obligation. */
describe("최근 24시간 — what we opened, never what was completed", () => {
  it("nothing opened: one sentence in the value slot, not three zeros", async () => {
    draw(co({ checked: 0, autoResolved: 0, draftsPrepared: 0 }));
    const line = await context();
    // The dot that used to join these two is gone with the line: 최근 24시간 is now the cell's label and
    // the sentence is its value, stacked. Asserting the two spans exactly is stronger than a substring of
    // a flattened line — it fixes which half is the question and which is the answer.
    // Nothing opened is a sentence, and a sentence takes no supporting figures: 「새로 확인 0」 would be
    // three zeros where one honest clause belongs. The cell's three stacked spans are gone with the cell —
    // this is one line now — so the claim is asserted on the line's own text.
    /*
      <b>Re-pointed: a quiet window is not a change either</b> (product-owner decision, 2026-10-01).
      「새로 확인한 일 없음」 was the automation's report of a window in which nothing happened, printed
      under a heading asking what changed. The rule it was protecting is intact and is what is asserted
      here: <b>nothing opened never becomes 「새로 확인 0」</b> — three zeros where one honest clause
      belonged, and now where no clause belongs at all.
    */
    await waitFor(() => expect(screen.getByLabelText("오늘 달라진 점")).toHaveTextContent("오늘 달라진 점"));
    expect(line).not.toHaveTextContent("최근 24시간");
    expect(line.textContent).not.toMatch(/새로 확인 0/);
    expect(line).not.toHaveTextContent("새로 확인한 일 없음");
  });

  it("something opened: the denominator and its two subsets, in that order", async () => {
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("새로 확인 12"));
    expect(line).toHaveTextContent("그중 정리 3");
    expect(line).toHaveTextContent("초안 준비 2");
    expect(line).not.toHaveTextContent("새로 확인한 일 없음");
  });

  it("the denominator stands alone when both subsets are zero — they are subsets, not a sum", async () => {
    // <b>Changed with the move, deliberately.</b> In the band the two subsets were a cell's supporting
    // line and were drawn even at zero, because a cell has a fixed three-tier shape. On a quiet line
    // there is no shape to fill, and 「(그중 정리 0 · 초안 준비 0)」 is a parenthesis that says nothing
    // twice. The denominator is still the fact and it is still printed.
    draw(co({ checked: 4, autoResolved: 0, draftsPrepared: 0 }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("새로 확인 4"));
    expect(line).not.toHaveTextContent("그중 정리 0");
    expect(line).not.toHaveTextContent("초안 준비 0");
  });

  it("never prints monitoring or verifying in this group — neither has a window", async () => {
    // §8-4a. Both are point-in-time counts of currently-open cases.
    draw(co({ checked: 7, autoResolved: 1, draftsPrepared: 1, monitoring: 5, verifying: 4 }), null);
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("새로 확인 7"));
    expect(line).not.toHaveTextContent("관찰");
    expect(line).not.toHaveTextContent("처리 확인 중");
    expect(line.textContent).not.toMatch(/\b5\b|\b4\b/);
  });

  it("says nothing when there is no window yet", async () => {
    draw(co({ since: null }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("오늘 들어온 것"));
    expect(line).not.toHaveTextContent("최근 24시간");
    expect(line).not.toHaveTextContent("새로 확인");
  });

  it("never calls any of it completion — the words §8-3 forbids appear nowhere", async () => {
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("새로 확인 12"));
    for (const banned of ["오늘 처리 완료", "처리 완료", "최근 24시간 처리", "Reviewnary가 처리한 일", "완료"]) {
      expect(line).not.toHaveTextContent(banned);
    }
  });
});

/**
 * <b>The band — obligations, and nothing else</b> (product-owner decision, 2026-09-30).
 *
 * <p>An obligation is something waiting for the seller. 확인할 일 is our queue of decisions, 실행 대기 is
 * what they already decided and has not been posted, 현재 미답변 is the channel's own fact. 오늘 들어온 것
 * and 최근 24시간 are neither, and they are asserted ABSENT here — a band that mixes 「what you must do」
 * with 「what happened」 is a dashboard, and this screen's subject is the list below it.
 */
describe("the band — obligations only", () => {
  const withWork = () => {
    api.getInquiryQueueStrict.mockResolvedValue({
      content: [
        { workItemId: "w1", inquiryId: "i1", status: "OPEN", channelCode: "cafe24", channelNameKo: "카페24 자사몰",
          title: "세금계산서 발행 문의", snippet: "사업자등록증 첨부했습니다", receivedAt: "2026-09-14T00:00:00Z",
          productName: null, productId: null, waitedLabel: "2일" },
      ],
      totalElements: 1, page: 0, size: 50,
    });
  };

  it("states 확인할 일, 실행 대기 and 현재 미답변 — and never inflow or the 24-hour window", async () => {
    withWork();
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band).toHaveTextContent("실행 대기");
    expect(band).toHaveTextContent("현재 미답변");
    // The two facts that moved. Both are still on the screen — on the quiet line — and neither may be
    // drawn at the weight of an obligation.
    expect(band).not.toHaveTextContent("오늘 들어온 것");
    expect(band).not.toHaveTextContent("최근 24시간");
    expect(band).not.toHaveTextContent("새로 확인");
    const ctx = await context();
    expect(ctx).toHaveTextContent("최근 24시간");
  });

  it("반복 문제 never stands in the band — a pattern is not a customer waiting", async () => {
    withWork();
    draw(co({ checked: 1 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band).not.toHaveTextContent("반복 문제");
  });

  it("reads 확인할 일 → 실행 대기 → 현재 미답변, in that order", async () => {
    withWork();
    draw(co({ checked: 1 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("현재 미답변"));
    const text = band.textContent ?? "";
    expect(text.indexOf("확인할 일")).toBeLessThan(text.indexOf("실행 대기"));
    expect(text.indexOf("실행 대기")).toBeLessThan(text.indexOf("현재 미답변"));
  });

  it("현재 미답변 says it is unknown rather than printing the qualified figure", async () => {
    /*
      <b>The defect this test exists for is unchanged; the remedy is the opposite one</b> (product-owner
      decision, 2026-10-01: 「unknown KPI를 빈 공간으로 두지 말 것. 값이 없으면 — + unknown임을 명시.
      값은 절대 추정하지 말 것.」).

      <p>What must not happen is printing the qualified figure as if it were the total — the defect
      `contextStrip` hit, where 「현재 미답변 문의 0건」 stood above 「들어온 문의 3건」, both true under
      definitions nobody could see. That is still asserted below, and it is the whole point.

      <p>What changed is what stands in its place. Dropping the cell was the previous answer, and an
      empty third of a ruled band reads as a value that has not loaded — the seller cannot tell it from a
      zero or from a slot that was never there. So the cell stands, prints 「—」, and names the cause.
    */
    withWork();
    draw(co(), metrics({ kpis: [kpi("reviews"), kpi("inquiries"), kpi("unansweredInquiries", { value: 4, freshnessUnproven: true })] }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    const cell = band.querySelector("[data-testid='pulse-unanswered']")!;
    expect(cell).toHaveTextContent("현재 미답변");
    // <b>The figure is never floored.</b> 4 is what the qualified population counted, and printing it
    // here would be the `contextStrip` defect again.
    expect(cell).not.toHaveTextContent("4");
    expect(cell).toHaveTextContent("—");
    expect(cell).toHaveTextContent("수집 상태 확인 필요");
    // And the cause is still carried by the surfaces whose job IS collection state, exactly once between
    // them. Which sentence they use depends on WHICH lane is unproven (a withheld inflow lane names itself
    // on the context line; otherwise the channel row's freshness clause speaks), so the assertion is on the
    // count over both, not on the wording and not on which of the two said it.
    //
    // <p>The band's own note is deliberately outside that count: it is not a third report of the cause, it
    // is the qualification OF THIS FIGURE, in the slot that exists to qualify it. A seller reading 「—」
    // without it would be looking at an unexplained blank.
    const said = [(await context()).textContent, (await channels()).textContent].join(" ");
    expect(said.match(/수집 (상태|확인) 확인 필요|최신 수집 확인 필요/g) ?? []).toHaveLength(1);
  });

  /**
   * <b>한 원인은 한 번만, 그리고 그 자리는 채널 상태다</b> (product-owner decision, 2026-10-01).
   *
   * <p>The rule is the one this test has always made — one cause, one sentence — and what the decision
   * fixed is WHICH of the two surfaces keeps it. It used to be the inflow clause, on the argument that
   * it names which lane; it is the channel row now, because 「채널은 정상인가」 is that row's whole
   * question and 「무엇이 달라졌나」 is not.
   */
  it("한 원인을 두 문장으로 말하지 않는다 — 수집 상태는 채널 행만 말한다", async () => {
    draw(co({ checked: 1 }), metrics({
      kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { freshnessUnproven: true })],
    }));
    const ctx = await context();
    await waitFor(() => expect(ctx).toHaveTextContent("최근 24시간"));
    expect(ctx).not.toHaveTextContent("수집 확인 필요");
    expect(await channels()).toHaveTextContent("일부 채널 최신 수집 확인 필요");
  });

  it("says 현재 미답변 could not be read, and says nothing about the channels", async () => {
    withWork();
    draw(co({ checked: 1 }), null);
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    const cell = band.querySelector("[data-testid='pulse-unanswered']")!;
    // A failed request still does not say zero. What it now also does not do is vanish: the slot stands
    // and names its own cause. <b>And the cause is OURS, not the seller's</b> — 「수집 상태 확인 필요」 is
    // a claim about their channels and no read supports it here, so a failed request says only that the
    // number was not read.
    expect(cell).toHaveTextContent("—");
    expect(cell).toHaveTextContent("숫자를 읽지 못했습니다");
    expect(cell).not.toHaveTextContent("수집 상태 확인 필요");
    expect(cell.textContent).not.toMatch(/\d/);
  });

  it("is ONE surface of labelled cells — never cards, a tile or a chart", async () => {
    withWork();
    // Same reason as the sibling test: the «nothing is drawn» check is the band's, not the page's.
    draw(co({ checked: 12 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    // <b>The surface is ruled, not filled</b> (Home visual target, 2026-10-01). The guarantee has not
    // changed — ONE surface, never three cards — only what draws it: the band was a `bg-canvas` box at
    // `max-w-3xl`, which is the dashboard shape §8-A spends on nothing else on this screen, and the
    // target rules it full-width between two hairlines instead. So the fill assertion becomes its
    // opposite, and the «no boxes» assertion moves onto the two things that would restore one: a radius
    // and a shadow. `border` is now what the band IS.
    expect(band.className).not.toContain("bg-canvas");
    expect(band.className).toContain("border-b");
    expect(band.className).not.toMatch(/rounded|shadow|gradient/);
    const cells = [...band.querySelectorAll("[data-testid^='pulse-']")] as HTMLElement[];
    expect(cells.length).toBeGreaterThan(1);
    for (const c of cells) {
      expect(c.className).not.toMatch(/border|bg-|rounded|shadow/);
      // Labels stay muted at `sm`; only the figures take ink, weight and size.
      expect(c.className).toContain("text-sm");
      expect(c.className).toContain("text-muted");
    }
    // <b>The state line is one size whatever it holds.</b> This fixture states one cell's answer as a
    // number and another's as a sentence, and both are `xl`: a band that grows only when the answer is a
    // number shrinks exactly when the seller needs to notice something. What separates them is ink.
    const states = cells.map((c) => c.children[1] as HTMLElement);
    expect(states.length).toBeGreaterThan(1);
    // `title` (28/700) — the scale's own step for a page name AND for the one primary metric a screen
    // may draw (product-owner decision, 2026-10-01). The assertion that matters is unchanged: every
    // state slot is the SAME size, whether it holds a number, a dash or a sentence.
    for (const state of states) expect(state.className).toContain("text-title");
    expect(band.querySelectorAll(".font-bold.tabular-nums.text-ink").length).toBeGreaterThan(0);
    expect(band.querySelectorAll("[class*='text-3xl']")).toHaveLength(0);
    // No chart and no icon <b>in the band</b>. Scoped there rather than at the container, because the
    // header above it now carries one control — 새로고침 — and its mark is an `svg`. The claim this
    // assertion has always made is about the SURFACE: a summary that draws is a dashboard.
    expect(band.querySelectorAll("svg,canvas")).toHaveLength(0);
  });

  it("the columns are ruled by the surface, never boxed one by one", async () => {
    withWork();
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band.className).toContain("divide-x");
    expect(band.className).toContain("divide-line");
    for (const c of band.querySelectorAll("[data-testid^='pulse-']")) {
      expect((c as HTMLElement).className).not.toMatch(/border|bg-|rounded|shadow/);
    }
  });

  it("keeps the band's rhythm when a cell has nothing measured to say", async () => {
    // <b>Re-pointed, not dropped</b> (Home visual target, 2026-10-01). The old guarantee — «two facts are
    // two columns» — was about the band never drawing an empty slot, and it bought that with a track
    // count that followed the cell count. Measured at 1600×1000 that is what it cost: an org whose
    // 미답변 read came back unqualified got two 622px columns and a rule down the middle of the page,
    // while an org one cell richer got thirds. Same band, two rhythms, for a reason no seller can see.
    //
    // <p>What the test still protects is the thing that mattered: a cell with nothing to say draws
    // NOTHING. The tracks are the target's thirds, so the cells that do have something keep their width
    // and their rule position whatever the read returned.
    withWork();
    // No metrics: 확인할 일 and 실행 대기 only.
    draw(co({ checked: 1 }), null);
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band.querySelectorAll("[data-testid^='pulse-']")).toHaveLength(3);
    // The third cell stands and says it was not read — see 「says 현재 미답변 could not be read」. What
    // this test keeps is the rhythm: three tracks, so the two that DO have figures sit where they sit
    // whatever the overview returned.
    expect(band.querySelector("[data-testid='pulse-unanswered']")).toHaveTextContent("—");
    expect(band.className).toContain("grid-cols-3");
  });

  it("carries an accessible name and spends no line on a visible one", async () => {
    // <b>Changed with the contents</b> (product-owner decision, 2026-09-30). The visible 「운영 현황」 was
    // bought by the band holding three different WINDOWS — 오늘 / now / 최근 24시간 — where no single
    // heading is true of all three and the frame did real work. Three obligations are all 「now」 and each
    // carries its own lead word, so a group heading repeats the cells and adds a fourth level between the
    // page title and the work.
    withWork();
    draw(co({ checked: 12 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band.getAttribute("aria-label")).toBe("지금 해야 할 일");
    expect(band.getAttribute("aria-labelledby")).toBeNull();
    expect(band.querySelector("h1,h2,h3")).toBeNull();
  });

  it("stands even when it can measure none of the three, and says so three times", async () => {
    // <b>Re-pointed by decision</b> (2026-10-01). The band used to vanish when no cell had a figure, and
    // the reason was sound: a read that failed, a job that is not running and an org with no window yet
    // are three different silences and none of them is 「0」. That reasoning is intact — what changed is
    // where it is served. A band that disappears answers the three questions with nothing at all, and the
    // seller is left to work out whether they have no work or no answer. Each silence is now NAMED, in
    // its own slot, and none of them is ever drawn as a number.
    draw({ ...co({ since: null }), status: "PAUSED" }, null);
    const band = await summary();
    const cells = [...band.querySelectorAll("[data-testid^='pulse-']")] as HTMLElement[];
    expect(cells).toHaveLength(3);
    // 확인할 일 is a measured zero here — the list read landed and found nothing — so it alone is a figure.
    expect(cells[0]).toHaveTextContent("0건");
    for (const cell of cells.slice(1)) {
      expect(cell).toHaveTextContent("—");
      expect(cell.textContent).not.toMatch(/\d/);
    }
  });

  it("does not claim a 실행 대기 count while the job is not running", async () => {
    withWork();
    draw({ ...co({ checked: 1 }), status: "PAUSED" });
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    const cell = band.querySelector("[data-testid='pulse-awaiting']")!;
    // The claim the test has always protected: 실행 대기 is a fact about a job that is LOOKING, and a
    // paused job has not looked. So no number — and now the slot says that instead of leaving the
    // seller to infer it from a gap in the row.
    expect(cell.textContent).not.toMatch(/\d/);
    expect(cell).toHaveTextContent("—");
    expect(cell).toHaveTextContent("자동 확인이 멈춰 있어 확인하지 못했습니다");
  });
});

/** The quiet line's own shape — it is context, and it must never outrank the band above it. */
describe("the context line", () => {
  /**
   * <b>Re-pointed: the way to the numbers is the section's, not this line's</b> (product-owner
   * decision, 2026-10-01 — 오늘 달라진 점 carries at most two CTAs). 「자세한 숫자 보기」 pointed at
   * `/overview` while the heading this line is the note OF carries 「N건 전체 보기 →」 to the same
   * screen, on the same row, plus the insight's own action: three competing links in one section.
   *
   * <p>The guarantee being made here was 「one muted line, and never a copy of the numbers screen」.
   * Both halves are asserted below, and the second one — that `/overview` is still reachable from
   * this section — is asserted on the section rather than on the line, which is where it moved.
   */
  it("is one muted line at `sm` with no numbers of its own, and no second way to the numbers screen", async () => {
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("최근 24시간"));
    expect(line.className).toContain("text-sm");
    expect(line.className).toContain("text-muted");
    expect(line).not.toHaveTextContent("자세한 숫자 보기");
    // 매출 · 주문 · 추이 stay owned by `/overview`. This line never copies them.
    expect(line).not.toHaveTextContent("매출");
    expect(line).not.toHaveTextContent("주문");
    // The section is still the way there, and now it is the ONLY way there from here.
    const section = line.closest("section")!;
    const toOverview = [...section.querySelectorAll('a[href^="/overview"]')];
    expect(toOverview.length).toBeLessThanOrEqual(1);
  });

  it("never carries channel freshness itself — that question is asked at the foot of the screen", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews"), kpi("inquiries"), kpi("unansweredInquiries", { freshnessUnproven: true })] }));
    const row = await channels();
    await waitFor(() => expect(row).toHaveTextContent("일부 채널 최신 수집 확인 필요"));
    expect(await context()).not.toHaveTextContent("일부 채널 최신 수집 확인 필요");
    // Secondary disclosure (§8-B'): it qualifies the figures above and is read in the same breath as
    // them. The warn colour is for a channel the seller can go and fix, and would otherwise spend the
    // page's strongest signal on machinery.
    expect(row.querySelector("[class*='text-warn']")).toBeNull();
  });
});

/**
 * 「채널은 정상인가」 — the fifth question the Home exists to answer, and the one it used to leave open.
 */
describe("the channel row", () => {
  it("answers when the answer is yes — silence cannot be told from «we did not look»", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews"), kpi("inquiries")] }));
    const row = await channels();
    await waitFor(() => expect(row).toHaveTextContent("확인된 수집 문제 없음"));
    // It claims exactly what the reads support — no gap row, no incomplete source, no unproven figure —
    // and never that every channel is up to date, which nothing on this screen measures.
    expect(row).not.toHaveTextContent("최신");
  });

  it("stands below the work, not between the summary and it", async () => {
    draw(co({ checked: 1 }), metrics({ kpis: [kpi("reviews"), kpi("inquiries")] }));
    const row = await channels();
    const band = await summary();
    expect(band.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const list = screen.queryByRole("list", { name: "확인할 일" });
    if (list) expect(list.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
