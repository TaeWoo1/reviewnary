import { describe, expect, it, vi } from "vitest";
import { runInNewContext } from "node:vm";
import {
  asideNaverReviewWindowRuntime,
  type NaverReviewWindowRuntimePlan,
  type NaverWindowTabLike,
} from "../../src/aside/naver-review-window-runtime";
import {
  buildNaverReviewWindowRuntimePlan,
  buildNaverReviewWindowRuntimeProgram,
  kstDaysBeforeDay,
  parseNaverReviewWindowRuntimeResult,
} from "../../src/aside/naver-review-executor";
import {
  NAVER_REVIEW_DATE_INPUT_SELECTOR,
  NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
  NAVER_REVIEW_READ_WORKFLOW,
} from "../../src/aside/naver-review-workflow";
import { runNaverReviewObservation, type NaverDeliveryResponse } from "../../src/aside/naver-review-observe-runner";

/**
 * **Looking at last month, with three keystrokes and a proof.**
 *
 * The 2026-10-08 live read covered 10-02 … 10-08 because no lane could ask the page for a period. This is the
 * lane that can — and the whole of what it may do is put a date in the from field, a date in the to field, and
 * press 조회. The tests below are each one way that could become something else, or could lie:
 *
 *  - acting on a page that is not this page,
 *  - acting on whichever two inputs happened to match,
 *  - typing into the «to» field thinking it is the «from»,
 *  - and the big one: filling two fields and calling that a period, when the screen never moved.
 */

const NOW = new Date("2026-10-08T03:00:00.000Z"); // 12:00 KST, 10-08

const plan: NaverReviewWindowRuntimePlan = {
  entryUrl: "https://example.test/#/review/search",
  controlsScript: "CONTROLS",
  authScript: "AUTH",
  readerScript: "READER",
  rangeScript: "RANGE",
  dateInputSelector: "DATES",
  queryControlSelector: "QUERY",
  requestedStartValue: "2026-09-03",
  requestedEndValue: "2026-09-09",
  requestedStartDaysBefore: 35,
  requestedEndDaysBefore: 29,
  settleTimeoutMs: 5_000,
  pollMs: 1,
  searchSettleMs: 0,
};

interface Screen {
  route?: boolean;
  grid?: number;
  signedIn?: boolean;
  /** The values the two date inputs arrive with — the page's own default period. */
  arrivesWith?: [string, string];
  /** Which members of the date candidate set the page predicate accepted. */
  dateAccepted?: number[] | null;
  dateCandidates?: number;
  queryAccepted?: number[] | null;
  queryCandidates?: number;
  /** What the census answers AFTER the search. */
  showsAfterSearch?: { valuesParsed: number; startDaysBefore: number; endDaysBefore: number };
  fillThrows?: boolean;
  /** A field that silently keeps its old value — the exact way «we filled it» becomes a lie. */
  fillIgnored?: boolean;
}

function screen(over: Screen = {}) {
  const s: Screen = {
    route: true,
    grid: 15,
    signedIn: true,
    arrivesWith: ["2026-10-02", "2026-10-08"],
    dateAccepted: [0, 1],
    dateCandidates: 2,
    queryAccepted: [0],
    queryCandidates: 1,
    showsAfterSearch: { valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 29 },
    ...over,
  };
  const acts: string[] = [];
  // Values keyed by the index in the candidate set, so a test can put the two real controls anywhere in it —
  // which is the whole point of the page deciding and the runtime acting on indices.
  const values: Record<number, string> = {};
  const idx = (s.dateAccepted ?? [0, 1]) as number[];
  if (idx.length === 2) {
    values[idx[0]!] = (s.arrivesWith as [string, string])[0];
    values[idx[1]!] = (s.arrivesWith as [string, string])[1];
  }
  let searched = false;
  const locator = (selector: string, index: number | null): never => ({
    count: async () => {
      // A pure-CSS candidate set. The runtime no longer asks this to decide anything — if it ever does again,
      // this double makes that visible.
      acts.push(`count:${selector}`);
      return selector === "DATES" ? s.dateCandidates! : s.queryCandidates!;
    },
    nth: (i: number) => locator(selector, i),
    inputValue: async () => values[index ?? 0] ?? "",
    fill: async (v: string) => {
      if (s.fillThrows) throw new Error("readonly");
      acts.push(`fill:${index}:${v}`);
      if (!s.fillIgnored) values[index ?? 0] = v;
    },
    click: async () => {
      acts.push("click:search");
      searched = true;
    },
  }) as never;
  const tab: NaverWindowTabLike = {
    evaluate: async (script: string) => {
      if (script === "CONTROLS") {
        return {
          route: s.route,
          grid: s.grid,
          dateCandidates: s.dateCandidates,
          dateAccepted: s.dateAccepted,
          queryCandidates: s.queryCandidates,
          queryAccepted: s.queryAccepted,
        };
      }
      if (script === "AUTH") return { signedIn: s.signedIn };
      if (script === "RANGE") {
        if (!searched) return { valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0 };
        return s.showsAfterSearch;
      }
      return { reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0, rows: [] };
    },
    locator: (selector: string) => locator(selector, null),
    waitForLoadState: async () => undefined,
  };
  const closed: string[] = [];
  const env = {
    openTab: async () => tab,
    closeTab: async () => void closed.push("closed"),
    wait: async () => undefined,
  };
  return { env, acts, closed, values };
}

describe("the historical window read — three interactions, and a proof that the screen moved", () => {
  it("fills the two dates, presses 조회, verifies the period, and only then reads", async () => {
    const h = screen();
    const r = await asideNaverReviewWindowRuntime(plan, h.env);

    expect(r.ok).toBe(true);
    // The from field is the one that arrived holding the earlier date — not nth(0) by position.
    expect(h.acts.filter((a) => !a.startsWith("count:")))
      .toEqual(["fill:0:2026-09-03", "fill:1:2026-09-09", "click:search"]);
    expect(h.closed).toEqual(["closed"]);
  });

  it("the whole vocabulary is those three — nothing else is reachable", async () => {
    const h = screen();
    await asideNaverReviewWindowRuntime(plan, h.env);
    // Two fills and one click. A fourth interaction would show up here as a fourth entry, and in the guard
    // test as a fourth call site.
    const touched = h.acts.filter((a) => !a.startsWith("count:"));
    expect(touched).toHaveLength(3);
    expect(touched.filter((a) => a.startsWith("fill:"))).toHaveLength(2);
    expect(touched.filter((a) => a === "click:search")).toHaveLength(1);
  });

  it("will not touch a page that is not the review list", async () => {
    for (const over of [{ route: false }, { grid: 0 }]) {
      const h = screen(over);
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "SURFACE_UNEXPECTED" });
      // Typing a date into the wrong page is the one mistake no later verification can undo: the census
      // would then be reading some other screen's inputs and agreeing with itself.
      expect(h.acts).toEqual([]);
      expect(h.closed).toEqual(["closed"]);
    }
  });

  it("a sign-in wall is a stop, before any control is touched", async () => {
    const h = screen({ signedIn: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "AUTH_REQUIRED", stage: "AUTH" });
    expect(h.acts).toEqual([]);
  });

  it("never takes the first two of however many — zero, one and three all stop, with the count", async () => {
    for (const [accepted, code] of [[[], "RANGE_CONTROLS_NOT_FOUND"], [[0], "RANGE_CONTROLS_NOT_FOUND"],
      [[0, 1, 2], "RANGE_CONTROLS_AMBIGUOUS"]] as const) {
      const h = screen({ dateAccepted: [...accepted], dateCandidates: 9 });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code, stage: "CONTROLS", candidates: accepted.length });
      expect(h.acts.filter((a) => !a.startsWith("count:"))).toEqual([]);
    }
  });

  it("the 조회 control has its own two stops — zero and more than one", async () => {
    // Aside runs locators with strict mode off (export discovery G-2): two matches would mean the first is
    // pressed, silently. And a stop that cannot say WHICH control was wrong sends the next person to the
    // wrong half of the page.
    for (const [accepted, code] of [[[], "QUERY_CONTROL_NOT_FOUND"], [[2, 5], "QUERY_CONTROL_AMBIGUOUS"]] as const) {
      const h = screen({ queryAccepted: [...accepted], queryCandidates: 40 });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code, stage: "CONTROLS", candidates: accepted.length });
      expect(h.acts.filter((a) => !a.startsWith("count:"))).toEqual([]);
    }
  });

  it("a candidate set it could not even look through is named apart from a runtime fault", async () => {
    // 2026-10-08: the selector carried Playwright's `:visible`, Aside handed it to `querySelectorAll`, and the
    // whole lane stopped as `RUNTIME_FAULT` at `CONTROLS` — the same word a vanished browser gets. The page
    // script now answers `null` for a candidate set it could not query, and that has its own name.
    for (const over of [{ dateAccepted: null }, { queryAccepted: null }] as Screen[]) {
      const h = screen(over);
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "DATE_CONTROL_CANDIDATES_UNREADABLE", stage: "CONTROLS" });
      expect(h.acts.filter((a) => !a.startsWith("count:"))).toEqual([]);
    }
  });

  it("acts on the indices the page accepted — not on nth(0) and nth(1) by position", async () => {
    // The two date controls are the 3rd and 7th members of the candidate set; everything else that matched the
    // CSS was invisible, disabled, or not a date control at all. The page decided that; this acts on it.
    const h = screen({ dateAccepted: [2, 6], dateCandidates: 9, queryAccepted: [4], queryCandidates: 40 });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r.ok).toBe(true);
    expect(h.acts.filter((a) => !a.startsWith("count:")))
      .toEqual(["fill:2:2026-09-03", "fill:6:2026-09-09", "click:search"]);
  });

  it("decides which field is the from from the page's own values, and stops when they do not say", async () => {
    // Reversed in the DOM: the later date is in nth(0). The requested start must still go to the field that
    // holds the earlier date, or the store is shown an empty week while every date-level check agrees.
    const reversed = screen({ arrivesWith: ["2026-10-08", "2026-10-02"] });
    await asideNaverReviewWindowRuntime(plan, reversed.env);
    expect(reversed.acts.filter((a) => !a.startsWith("count:")))
      .toEqual(["fill:1:2026-09-03", "fill:0:2026-09-09", "click:search"]);

    for (const over of [{ arrivesWith: ["", ""] }, { arrivesWith: ["2026-10-08", "2026-10-08"] },
      { arrivesWith: ["어제", "오늘"] }] as Screen[]) {
      const h = screen(over);
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "RANGE_ORDER_UNKNOWN", stage: "CONTROLS" });
      expect(h.acts.filter((a) => !a.startsWith("count:"))).toEqual([]);
    }
  });

  it("a field that refuses to be typed into is a stop — not a reason to start clicking a calendar", async () => {
    const throws = screen({ fillThrows: true });
    expect(await asideNaverReviewWindowRuntime(plan, throws.env))
      .toMatchObject({ ok: false, code: "RANGE_NOT_SETTABLE", stage: "NAVIGATE" });

    const ignored = screen({ fillIgnored: true, showsAfterSearch: { valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0 } });
    const r = await asideNaverReviewWindowRuntime(plan, ignored.env);
    // The fields kept their old values, the search ran on the old period, and the census said so. «We filled
    // the fields» is an intention; this is the screen.
    expect(r).toMatchObject({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" });
  });

  it("a screen showing a different period is not read, however it got there", async () => {
    for (const shows of [
      { valuesParsed: 2, startDaysBefore: 34, endDaysBefore: 29 },
      { valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 28 },
      { valuesParsed: 1, startDaysBefore: 35, endDaysBefore: 29 },
      { valuesParsed: -1, startDaysBefore: -1, endDaysBefore: -1 },
    ]) {
      const h = screen({ showsAfterSearch: shows });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r, JSON.stringify(shows)).toMatchObject({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" });
      // It still pressed 조회 — the stop is about what came back, and the tab is closed either way.
      expect(h.acts.filter((a) => !a.startsWith("count:"))).toHaveLength(3);
      expect(h.closed).toEqual(["closed"]);
    }
  });
});

describe("the window plan and its program", () => {
  it("the period becomes two offsets from the same as-of the census is given", () => {
    const built = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-09-03", end: "2026-09-09" });
    expect(built).not.toBeNull();
    expect(built!.requestedStartDaysBefore).toBe(35);
    expect(built!.requestedEndDaysBefore).toBe(29);
    expect(built!.requestedStartValue).toBe("2026-09-03");
    expect(built!.dateInputSelector).toBe(NAVER_REVIEW_DATE_INPUT_SELECTOR);
    expect(built!.queryControlSelector).toBe(NAVER_REVIEW_QUERY_CONTROL_SELECTOR);
    // The route is the recipe's own published one; a period cannot move it.
    expect(built!.entryUrl).toBe(NAVER_REVIEW_READ_WORKFLOW.entryUrl);
  });

  it("refuses a period it cannot go and look at", () => {
    for (const w of [
      { start: "2026-09-09", end: "2026-09-03" },   // ends before it starts
      { start: "2026-10-09", end: "2026-10-10" },   // in the future
      { start: "2020-01-01", end: "2026-09-09" },   // further back than a year
      { start: "not-a-date", end: "2026-09-09" },
    ]) {
      expect(buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW, w), JSON.stringify(w)).toBeNull();
    }
  });

  it("the shipped program runs in an empty context with only openTab/closeTab", async () => {
    const program = buildNaverReviewWindowRuntimeProgram(plan);
    const body = program.replace(
      /console\.log\("ASIDE_RESULT " \+ JSON\.stringify\(__result\)\);$/,
      "return __result;",
    );
    const h = screen();
    const sandbox = {
      openTab: h.env.openTab,
      closeTab: h.env.closeTab,
      setTimeout,
      Date,
      JSON,
      Object,
      String,
      Number,
    };
    const result = (await runInNewContext(`(async () => { ${body} })()`, sandbox)) as { ok: boolean };
    expect(result.ok).toBe(true);
    expect(h.acts.filter((a) => !a.startsWith("count:")))
      .toEqual(["fill:0:2026-09-03", "fill:1:2026-09-09", "click:search"]);
  });

  it("an off-shape answer is never a success with a missing half", () => {
    expect(parseNaverReviewWindowRuntimeResult({ ok: true, reading: {} })).toBeNull();
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "NOPE", stage: "READ" })).toBeNull();
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" }))
      .toMatchObject({ ok: false, code: "RANGE_MISMATCH", candidates: null });
  });

  it("a day is reduced to an offset in the seller's own calendar", () => {
    expect(kstDaysBeforeDay("2026-10-08", NOW)).toBe(0);
    expect(kstDaysBeforeDay("2026-09-03", NOW)).toBe(35);
    // 15:30Z on 10-08 is already 10-09 in Seoul.
    expect(kstDaysBeforeDay("2026-10-09", new Date("2026-10-08T15:30:00.000Z"))).toBe(0);
    expect(kstDaysBeforeDay("nope", NOW)).toBeNull();
  });
});

describe("the runner, given a period", () => {
  const MATCH: NaverDeliveryResponse =
    { identityVerdict: "MATCH", received: 0, inserted: 0, changed: 0, skipped: 0, failed: 0 };

  function executorFor(result: unknown) {
    return {
      execute: vi.fn(async () => ({ kind: "RESULT" as const, result: result as never, llmCalls: 0 as const })),
      executeWindow: vi.fn(async () => ({ kind: "RESULT" as const, result: result as never, llmCalls: 0 as const })),
      asOf: () => NOW,
    };
  }

  const reading = { reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0, rows: [] };

  it("uses the navigating lane and delivers the period it verified", async () => {
    const deliver = vi.fn(async () => MATCH);
    const executor = executorFor({
      ok: true,
      reading,
      range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 29, pagerNumberCount: 0, highestPagerNumber: 0 },
      elapsedMs: 1,
    });

    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor,
    });

    expect(r.outcome).toBe("OBSERVED");
    expect(executor.executeWindow).toHaveBeenCalledWith({ start: "2026-09-03", end: "2026-09-09" });
    expect(executor.execute).not.toHaveBeenCalled();
    const sent = deliver.mock.calls[0]![0] as unknown as { windowStart: string; windowEnd: string; windowDays: number };
    expect(sent.windowStart).toBe("2026-09-03");
    expect(sent.windowEnd).toBe("2026-09-09");
    expect(sent.windowDays).toBe(7);
  });

  it("a screen showing some other period delivers nothing", async () => {
    const deliver = vi.fn(async () => MATCH);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: executorFor({
        ok: true,
        reading,
        range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0, pagerNumberCount: 0, highestPagerNumber: 0 },
        elapsedMs: 1,
      }),
    });
    expect(r.outcome).toBe("SURFACE_UNREADABLE");
    expect(deliver).not.toHaveBeenCalled();
  });

  it("a helper without the navigating lane says so instead of reading the wrong period", async () => {
    const deliver = vi.fn(async () => MATCH);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: { execute: vi.fn(), asOf: () => NOW } as never,
    });
    expect(r.outcome).toBe("EXECUTOR_UNAVAILABLE");
    expect(deliver).not.toHaveBeenCalled();
  });
});
