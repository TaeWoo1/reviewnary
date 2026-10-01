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
import type { MetricKpi, MetricSeries, OperationsHome, OperationsMetrics } from "../../lib/types";

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

function draw(home = co(), m: OperationsMetrics | null = metrics()) {
  return render(
    <MemoryRouter>
      <CustomerOpsHome co={home} ops={ops()} now={NOW} onChanged={vi.fn()} metrics={m} />
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

  it("review lane stale, inquiry fresh: the review number is withheld and the inquiry number is not", async () => {
    draw(co(), metrics({ kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries")] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 수집 확인 필요"));
    expect(line).toHaveTextContent("문의 5");
    expect(line).not.toHaveTextContent("리뷰 3");
    // One lane qualifies, so the lanes differ and the line must say which — never the collapsed form.
    expect(line).not.toHaveTextContent("수집 상태 확인 필요");
  });

  it("inquiry lane stale, review fresh: the mirror case", async () => {
    draw(co(), metrics({ kpis: [kpi("reviews"), kpi("inquiries", { excludedChannels: 1 })] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("문의 수집 확인 필요"));
    expect(line).toHaveTextContent("리뷰 3");
    expect(line).not.toHaveTextContent("문의 5");
    expect(line).not.toHaveTextContent("수집 상태 확인 필요");
  });

  it("both stale: ONE state sentence, not the same five syllables twice", async () => {
    draw(
      co(),
      metrics({
        kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { excludedChannels: 2 })],
        series: [series("reviews", 0), series("inquiries", 0)],
      }),
    );
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("수집 상태 확인 필요"));
    // The per-lane sentences are the thing being replaced — neither may also appear.
    expect(line).not.toHaveTextContent("리뷰 수집 확인 필요");
    expect(line).not.toHaveTextContent("문의 수집 확인 필요");
    // And still no digits: an unobserved zero never becomes 「0」.
    expect(line.textContent).not.toMatch(/리뷰 \d|문의 \d/);
  });

  it("a measured zero IS printed — that is the difference the state words exist to keep", async () => {
    draw(co(), metrics({ series: [series("reviews", 0), series("inquiries", 0)] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 0"));
    expect(line).toHaveTextContent("문의 0");
    expect(line).not.toHaveTextContent("수집 확인 필요");
  });

  it("names no channel it was not given — the summary never invents where a gap is", async () => {
    draw(co(), metrics({ kpis: [kpi("reviews", { excludedChannels: 1 }), kpi("inquiries")] }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("리뷰 수집 확인 필요"));
    // `excludedChannels` is a COUNT; the response's `exclusions` name channels but this line is not
    // given them, so it must not print 쿠팡/네이버/카페24 from a number.
    expect(line.textContent).not.toMatch(/쿠팡|네이버|카페24|스마트스토어/);
  });

  it("a failed overview read draws no inflow group at all", async () => {
    draw(co(), null);
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("새로 확인한 일 없음"));
    expect(line).not.toHaveTextContent("오늘 들어온 것");
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
      co(),
      metrics({
        exampleDataIncluded: true,
        kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { excludedChannels: 1 })],
      }),
    );
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("수집 상태 확인 필요"));
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
    await waitFor(() => expect(line).toHaveTextContent("새로 확인한 일 없음"));
    expect(line).not.toHaveTextContent("최근 24시간 새로 확인");
    expect(line.textContent).not.toMatch(/새로 확인 0/);
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

  it("현재 미답변 leaves the band entirely when the population is incomplete", async () => {
    /*
      Two things must not happen, and the second was introduced and then caught by a visual review at
      1440×900 (2026-10-01).

      Printing the qualified figure as if it were the total is the defect `contextStrip` hit: it once showed
      「현재 미답변 문의 0건」 above 「들어온 문의 3건」, both true under definitions nobody could see. So the
      number is never floored.

      But the first fix put 「수집 상태 확인 필요」 into an OBLIGATION slot — and the context line one row
      below was already saying the same five syllables for the inflow, with 「일부 채널 최신 수집 확인 필요」
      beside it. One cause, three sentences, at the top of the morning screen. A band slot holds something
      that is waiting for the seller; a fact we could not measure is not waiting for anyone.
    */
    withWork();
    draw(co(), metrics({ kpis: [kpi("reviews"), kpi("inquiries"), kpi("unansweredInquiries", { value: 4, freshnessUnproven: true })] }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band).not.toHaveTextContent("현재 미답변");
    expect(band).not.toHaveTextContent("수집 상태 확인 필요");
    expect(band).not.toHaveTextContent("4건");
    // And the fact is not lost — the surfaces whose job is collection state carry it, exactly once between
    // them. Which sentence it uses depends on WHICH lane is unproven (a withheld inflow lane names itself on
    // the context line; otherwise the channel row's freshness clause speaks), so the assertion is on the
    // count over both, not on the wording and not on which of the two said it.
    const said = [(await context()).textContent, (await channels()).textContent].join(" ");
    expect(said.match(/수집 (상태|확인) 확인 필요|최신 수집 확인 필요/g) ?? []).toHaveLength(1);
  });

  it("한 원인을 두 문장으로 말하지 않는다 — 유입이 이미 말했으면 freshness 절은 빠진다", async () => {
    // Both lanes withheld because a collection could not be proven, and `freshnessUnproven` is that same
    // cause. The inflow clause is the better of the two because it names which lane.
    draw(co({ checked: 1 }), metrics({
      kpis: [kpi("reviews", { freshnessUnproven: true }), kpi("inquiries", { freshnessUnproven: true })],
    }));
    const ctx = await context();
    await waitFor(() => expect(ctx).toHaveTextContent("수집 상태 확인 필요"));
    expect(ctx).not.toHaveTextContent("일부 채널 최신 수집 확인 필요");
  });

  it("draws no 현재 미답변 cell at all when the overview read did not land", async () => {
    withWork();
    draw(co({ checked: 1 }), null);
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    // A failed request says nothing. It does not say zero, and it does not say 「확인 필요」 either —
    // that sentence is a claim about the seller's channels, and no read supports it here.
    expect(band).not.toHaveTextContent("현재 미답변");
  });

  it("is ONE surface of labelled cells — never cards, a tile or a chart", async () => {
    withWork();
    const { container } = draw(co({ checked: 12 }));
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band.className).toContain("bg-canvas");
    expect(band.className).not.toMatch(/border|shadow|gradient/);
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
    for (const state of states) expect(state.className).toContain("text-xl");
    expect(band.querySelectorAll(".font-semibold.tabular-nums.text-ink").length).toBeGreaterThan(0);
    expect(band.querySelectorAll("[class*='text-2xl'],[class*='text-3xl']")).toHaveLength(0);
    expect(container.querySelectorAll("svg,canvas")).toHaveLength(0);
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

  it("the grid is sized to the cells it has — two facts are two columns, not two and a gap", async () => {
    withWork();
    // No metrics: 확인할 일 and 실행 대기 only.
    draw(co({ checked: 1 }), null);
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band.querySelectorAll("[data-testid^='pulse-']")).toHaveLength(2);
    expect(band.className).toContain("grid-cols-2");
    expect(band.className).not.toContain("grid-cols-3");
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

  it("disappears entirely when nothing in it is true", async () => {
    draw({ ...co({ since: null }), status: "PAUSED" }, null);
    await screen.findByTestId("today-status");
    await waitFor(() => expect(screen.queryByTestId("today-summary")).toBeNull());
  });

  it("does not claim 실행 대기 while the job is not running", async () => {
    withWork();
    draw({ ...co({ checked: 1 }), status: "PAUSED" });
    const band = await summary();
    await waitFor(() => expect(band).toHaveTextContent("확인할 일"));
    expect(band).not.toHaveTextContent("실행 대기");
  });
});

/** The quiet line's own shape — it is context, and it must never outrank the band above it. */
describe("the context line", () => {
  it("is one muted line at `sm`, with the way to the numbers screen and no numbers of its own", async () => {
    draw(co({ checked: 12, autoResolved: 3, draftsPrepared: 2 }));
    const line = await context();
    await waitFor(() => expect(line).toHaveTextContent("최근 24시간"));
    expect(line.className).toContain("text-sm");
    expect(line.className).toContain("text-muted");
    expect(line).toHaveTextContent("자세한 숫자 보기");
    // 매출 · 주문 · 추이 stay owned by `/overview`. This line is the way there, never a copy of it.
    expect(line).not.toHaveTextContent("매출");
    expect(line).not.toHaveTextContent("주문");
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
