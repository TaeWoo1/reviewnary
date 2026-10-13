import { describe, expect, it, vi } from "vitest";
import { runInNewContext } from "node:vm";
import {
  asideNaverReviewWindowRuntime,
  type NaverReviewWindowRuntimePlan,
  type NaverWindowTabLike,
} from "../../src/aside/naver-review-window-runtime";
import {
  buildNaverReviewRuntimePlan,
  buildNaverReviewWindowRuntimePlan,
  buildNaverReviewWindowRuntimeProgram,
  civilDayOf,
  kstDaysBeforeDay,
  naverReviewWindowCliTimeoutMs,
  parseNaverReviewWindowRuntimeResult,
} from "../../src/aside/naver-review-executor";
import { ASIDE_REPL_CEILING_MS } from "../../src/aside/aside-cli";
import {
  NAVER_REVIEW_DATE_INPUT_SELECTOR,
  NAVER_REVIEW_DAY_CELL_SELECTOR,
  NAVER_REVIEW_MONTH_MOVE_CEILING,
  NAVER_REVIEW_MONTH_MOVE_MARGIN,
  NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
  NAVER_REVIEW_READ_WORKFLOW,
  NAVER_REVIEW_WINDOW_READ_SETTLE_MS,
  NAVER_REVIEW_WINDOW_RUN_BUDGET_MS,
  naverReviewMonthMoveAllowance,
} from "../../src/aside/naver-review-workflow";
import {
  executorFailureCode,
  NAVER_OBSERVE_FAILURE_CODES,
  runNaverReviewObservation,
  type NaverDeliveryResponse,
} from "../../src/aside/naver-review-observe-runner";

/**
 * **Looking at last month, on a calendar, with a proof at every step.**
 *
 * The 2026-10-08 live read covered 10-02 … 10-08 because no lane could ask the page for a period. The lane
 * that could then stopped at `RANGE_NOT_SETTABLE`, and it was right to: the period's two fields are
 * `input[type=text]` with `readOnly = true`. Typing was never the way in. So the vocabulary is now four
 * clicks — open the calendar bound to a field, step it one month, choose a day, press 조회 — and no `fill` at
 * all, which is a strictly smaller set of things that can happen to the seller's page than it replaced.
 *
 * Each test below is one way that could become something else, or could lie:
 *
 *  - acting on a page that is not this page,
 *  - acting on whichever two inputs happened to match,
 *  - pressing whichever arrow happened to sit beside the title, and jumping a year,
 *  - choosing a cell by the number printed on it, on a page that prints 「3」 twice,
 *  - using an index taken before the calendar opened, after it opened,
 *  - and the big one: clicking two days and calling that a period, when the screen never moved.
 */

const NOW = new Date("2026-10-08T03:00:00.000Z"); // 12:00 KST, 10-08

const plan: NaverReviewWindowRuntimePlan = {
  entryUrl: "https://example.test/#/review/search",
  controlsScript: "CONTROLS",
  authScript: "AUTH",
  readerScript: "READER",
  rangeScript: "RANGE",
  pickerScript: "PICKER",
  dateInputSelector: "DATES",
  queryControlSelector: "QUERY",
  dayCellSelector: "CELLS",
  requestedStart: { year: 2026, month: 9, day: 3 },
  requestedEnd: { year: 2026, month: 9, day: 9 },
  requestedStartDaysBefore: 35,
  requestedEndDaysBefore: 29,
  maxMonthMoves: 13,
  routeWaitTimeoutMs: 2_000,
  routePollMs: 1,
  loadWaitMs: 1_000,
  runBudgetMs: 60_000,
  navigationBudgetMs: 50_000,
  settleTimeoutMs: 5_000,
  pollMs: 1,
  pickerSettleMs: 0,
  monthStepSettleMs: 0,
  monthStepPollMs: 0,
  searchSettleMs: 0,
};

/** Where each control sits in the pressable candidate set. Deliberately not 0,1,2 — position proves nothing. */
const START_OPENER = 10;
const END_OPENER = 11;
const SHIFTED_END_OPENER = 12;
const PREV = 20;
const NEXT = 21;
const SEARCH = 30;
const CELL_BASE = 200;

interface Screen {
  /** Where the tab landed, as the census reports it apart. `route` is derived, never set. */
  hostOk?: boolean;
  hashOk?: boolean;
  authHost?: boolean;
  /** The router draws the review route only on the Nth census read — a page still arriving. */
  hashOkFromRead?: number;
  grid?: number;
  signedIn?: boolean;
  /** The values the two date fields arrive with — the page's own default period. */
  arrivesWith?: [string, string];
  dateAccepted?: number[] | null;
  dateCandidates?: number;
  queryAccepted?: number[] | null;
  queryLabelled?: number;
  /** Openers the page bound to each field, by candidate-set position. `-1` is «several, none chosen». */
  openerIndex?: Record<number, number>;
  openerCandidates?: Record<number, number>;
  /** The month each calendar opens on. Live, both opened on the list's current month. */
  opensOn?: { year: number; month: number };
  /** How far a single-step arrow ACTUALLY moves. 12 is a year jump wearing a month arrow's icon. */
  stepsBy?: number;
  prev?: number[];
  next?: number[];
  /** A calendar that never draws. */
  pickerOpens?: boolean;
  /** A calendar whose title cannot be read — so the month on show is unknown. */
  titleUnknown?: boolean;
  /** The requested day is not among the selectable cells. */
  targetAbsent?: boolean;
  /** The requested START day is on the page but drawn `disabled` — the marketplace's horizon. */
  targetDisabled?: boolean;
  /** Every day of the month on show is drawn `disabled`. */
  monthDisabled?: boolean;
  /** Single-month steps the component drew but disabled, per direction. */
  prevBlocked?: number;
  nextBlocked?: number;
  /**
   * A virtual clock. When given, `wait(ms)` advances it by `ms`, every press by `clickMs` and every evaluate by
   * `evaluateMs` — so a run's budgets are exercised in simulated time, not skipped.
   */
  clock?: { t: number; clickMs: number; evaluateMs: number };
  /** The requested day is offered twice — the way choosing by number goes wrong. */
  targetTwice?: boolean;
  /** The field keeps its old value after the cell is clicked. */
  pickIgnored?: boolean;
  /** The end field's opener moves once the start calendar has been and gone, as the live DOM does. */
  shiftEndOpener?: boolean;
  showsAfterSearch?: { valuesParsed: number; startDaysBefore: number; endDaysBefore: number };
}

const DAYS_IN = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

function screen(over: Screen = {}) {
  const s: Screen = {
    hostOk: true,
    hashOk: true,
    authHost: false,
    grid: 15,
    signedIn: true,
    arrivesWith: ["2026.10.02.", "2026.10.08."],
    dateAccepted: [0, 1],
    dateCandidates: 2,
    queryAccepted: [SEARCH],
    opensOn: { year: 2026, month: 10 },
    stepsBy: 1,
    prev: [PREV],
    next: [NEXT],
    pickerOpens: true,
    showsAfterSearch: { valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 29 },
    ...over,
  };
  const acts: string[] = [];
  const values: Record<number, string> = {};
  const accepted = (s.dateAccepted ?? []) as number[];
  if (accepted.length === 2) {
    values[accepted[0]!] = s.arrivesWith![0];
    values[accepted[1]!] = s.arrivesWith![1];
  }
  /** Which of the two fields has its calendar open (by position in `dateAccepted`), and on what month. */
  let open: number | null = null;
  let shown = { ...s.opensOn! };
  let firstPicked = false;
  let searched = false;
  let censusReads = 0;

  /** What the census answers for the field at `position`, now. */
  const opener = (position: number): number => {
    const dateIndex = accepted[position]!;
    if (s.openerIndex && dateIndex in s.openerIndex) return s.openerIndex[dateIndex]!;
    if (position === 0) return START_OPENER;
    return s.shiftEndOpener && firstPicked ? SHIFTED_END_OPENER : END_OPENER;
  };
  const candidates = (position: number): number => {
    const dateIndex = accepted[position]!;
    return s.openerCandidates && dateIndex in s.openerCandidates ? s.openerCandidates[dateIndex]! : 1;
  };

  const tick = (ms: number) => {
    if (s.clock) s.clock.t += ms;
  };
  const pressClick = (i: number) => {
    tick(s.clock?.clickMs ?? 0);
    const openerPosition = [0, 1].find((p) => accepted.length === 2 && opener(p) === i);
    if (openerPosition !== undefined) {
      open = openerPosition;
      shown = { ...s.opensOn! };
      acts.push(`click:opener:${openerPosition}`);
      return;
    }
    if (i === PREV || i === NEXT) {
      const by = (i === PREV ? -1 : 1) * s.stepsBy!;
      const m = shown.year * 12 + shown.month + by;
      shown = { year: Math.floor((m - 1) / 12), month: ((m - 1) % 12) + 1 };
      acts.push(i === PREV ? "click:prev" : "click:next");
      return;
    }
    if (i === SEARCH) {
      searched = true;
      acts.push("click:search");
      return;
    }
    // Anything else is a press this lane should never have made — above all a stale index.
    acts.push(`click:stray:${i}`);
  };

  const locator = (selector: string, index: number | null): never => ({
    count: async () => {
      acts.push(`count:${selector}`);
      return selector === "DATES" ? s.dateCandidates! : 99;
    },
    nth: (i: number) => locator(selector, i),
    inputValue: async () => values[index ?? -1] ?? "",
    click: async () => {
      if (selector === "QUERY") {
        pressClick(index ?? -1);
        return;
      }
      if (selector === "CELLS") {
        tick(s.clock?.clickMs ?? 0);
        const day = (index ?? 0) - CELL_BASE;
        acts.push(`click:cell:${day}`);
        if (open !== null && !s.pickIgnored) {
          values[accepted[open]!] =
            `${shown.year}.${String(shown.month).padStart(2, "0")}.${String(day).padStart(2, "0")}.`;
        }
        firstPicked = true;
        open = null;
        return;
      }
      acts.push(`click:unexpected:${selector}`);
    },
  }) as never;

  const tab: NaverWindowTabLike = {
    evaluate: async (script: string) => {
      tick(s.clock?.evaluateMs ?? 0);
      if (script === "CONTROLS") {
        censusReads += 1;
        const hashOk = s.hashOkFromRead !== undefined
          ? censusReads >= s.hashOkFromRead
          : s.hashOk !== false;
        return {
          route: (s.hostOk !== false) && hashOk,
          hostOk: s.hostOk !== false,
          hashOk,
          authHost: s.authHost === true,
          grid: s.grid,
          dateCandidates: s.dateCandidates,
          dateAccepted: s.dateAccepted,
          dateFormFound: s.dateAccepted !== null,
          queryCandidates: 99,
          queryLabelled: s.queryLabelled ?? (s.queryAccepted ?? []).length,
          queryAccepted: s.queryAccepted,
          openers: accepted.map((dateIndex, position) => ({
            dateIndex,
            openerIndex: candidates(position) === 1 ? opener(position) : -1,
            openerCandidates: candidates(position),
          })),
        };
      }
      if (script === "AUTH") return { signedIn: s.signedIn };
      if (script === "PICKER") {
        return {
          readable: true,
          dateAccepted: accepted,
          sides: accepted.map((dateIndex, position) => {
            if (open !== position || s.pickerOpens === false) {
              return { dateIndex, open: false, year: null, month: null, weekdays: 0, cells: 0,
                prev: [], next: [], days: [] };
            }
            const len = DAYS_IN(shown.year, shown.month);
            const days: [number, number][] = [];
            const disabledDays: number[] = [];
            for (let d = 1; d <= len; d++) {
              if (s.targetAbsent && (d === plan.requestedStart.day || d === plan.requestedEnd.day)) continue;
              // As the picker script reports a disabled day: its NUMBER, and no index to press.
              if (s.monthDisabled || (s.targetDisabled && d === plan.requestedStart.day)) {
                disabledDays.push(d);
                continue;
              }
              days.push([CELL_BASE + d, d]);
            }
            if (s.targetTwice) {
              days.push([CELL_BASE + 100, plan.requestedStart.day]);
              days.push([CELL_BASE + 101, plan.requestedEnd.day]);
            }
            return {
              dateIndex,
              open: !s.titleUnknown,
              year: s.titleUnknown ? null : shown.year,
              month: s.titleUnknown ? null : shown.month,
              weekdays: 7,
              cells: 42,
              prev: s.prev,
              next: s.next,
              prevBlocked: s.prevBlocked ?? 0,
              nextBlocked: s.nextBlocked ?? 0,
              days,
              disabledDays,
            };
          }),
        };
      }
      if (script === "RANGE") {
        if (!searched) return { valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0 };
        return s.showsAfterSearch;
      }
      acts.push("evaluate:READER");
      return { reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0, rows: [] };
    },
    locator: (selector: string) => locator(selector, null),
    waitForLoadState: async () => undefined,
  };
  const closed: string[] = [];
  const env = {
    openTab: async () => tab,
    closeTab: async () => void closed.push("closed"),
    wait: async (ms: number) => void tick(ms),
  };
  const touched = () => acts.filter((a) => !a.startsWith("count:") && a !== "evaluate:READER");
  return { env, acts, closed, values, touched };
}

const HAPPY = [
  "click:opener:0", "click:prev", "click:cell:3",
  "click:opener:1", "click:prev", "click:cell:9",
  "click:search",
];

describe("the historical window read — a calendar, and a proof that the screen moved", () => {
  it("opens each calendar, steps to September, chooses the day, presses 조회, verifies, then reads", async () => {
    const h = screen();
    const r = await asideNaverReviewWindowRuntime(plan, h.env);

    expect(r.ok).toBe(true);
    expect(h.touched()).toEqual(HAPPY);
    expect(h.acts).toContain("evaluate:READER");
    expect(h.closed).toEqual(["closed"]);
  });

  it("reports how many month steps it took — two calendars, one month back each", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen().env);
    expect(r).toMatchObject({ ok: true, monthMoves: 2 });
  });

  it("never types into the seller's page, and presses nothing it was not handed", async () => {
    const h = screen();
    await asideNaverReviewWindowRuntime(plan, h.env);
    expect(h.touched().every((a) => a.startsWith("click:"))).toBe(true);
    expect(h.touched().filter((a) => a.startsWith("click:stray"))).toEqual([]);
    expect(h.touched().filter((a) => a.startsWith("click:unexpected"))).toEqual([]);
  });

  it("will not touch a page that is not the review list", async () => {
    for (const over of [{ hostOk: false }, { grid: 0 }]) {
      const h = screen(over);
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "SURFACE_UNEXPECTED" });
      // Choosing a day on the wrong page is the one mistake no later verification can undo: the census would
      // then be reading some other screen's fields and agreeing with itself.
      expect(h.touched()).toEqual([]);
      expect(h.closed).toEqual(["closed"]);
    }
  });

  it("a sign-in wall is a stop, before any control is touched", async () => {
    const h = screen({ signedIn: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "AUTH_REQUIRED", stage: "AUTH" });
    expect(h.touched()).toEqual([]);
  });

  it("never takes the first two of however many — zero, one and three all stop, with the count", async () => {
    for (const [accepted, code] of [[[], "RANGE_CONTROLS_NOT_FOUND"], [[0], "RANGE_CONTROLS_NOT_FOUND"],
      [[0, 1, 2], "RANGE_CONTROLS_AMBIGUOUS"]] as const) {
      const h = screen({ dateAccepted: [...accepted], dateCandidates: 9 });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code, stage: "CONTROLS", candidates: accepted.length });
      expect(h.touched()).toEqual([]);
    }
  });

  it("a stop says which test excluded the 조회 candidates — the word, or the period's form", async () => {
    const none = await asideNaverReviewWindowRuntime(plan, screen({ queryAccepted: [], queryLabelled: 0 }).env);
    expect(none).toMatchObject({ ok: false, code: "QUERY_CONTROL_NOT_FOUND", candidates: 0, labelled: 0 });
    const words = await asideNaverReviewWindowRuntime(plan, screen({ queryAccepted: [], queryLabelled: 2 }).env);
    expect(words).toMatchObject({ ok: false, code: "QUERY_CONTROL_NOT_FOUND", candidates: 0, labelled: 2 });
    const both = await asideNaverReviewWindowRuntime(plan, screen({ queryAccepted: [1, 2], queryLabelled: 2 }).env);
    expect(both).toMatchObject({ ok: false, code: "QUERY_CONTROL_AMBIGUOUS", candidates: 2 });
  });

  it("a candidate set the page could not query is its own stop, not a runtime fault", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen({ dateAccepted: null, dateCandidates: 7 }).env);
    expect(r).toMatchObject({ ok: false, code: "DATE_CONTROL_CANDIDATES_UNREADABLE", candidates: 7 });
  });

  it("stops when the two fields do not say which of them is the from", async () => {
    for (const arrives of [["", ""], ["2026.10.02.", "2026.10.02."], ["어제", "오늘"]] as const) {
      const h = screen({ arrivesWith: [...arrives] as [string, string] });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "RANGE_ORDER_UNKNOWN", stage: "CONTROLS" });
      expect(h.touched()).toEqual([]);
    }
  });

  it("reads the from field off the page's own values, whichever way round the DOM has them", async () => {
    // The later date first. The calendar of the EARLIER field must be the one that gets the 3rd.
    const h = screen({ arrivesWith: ["2026.10.08.", "2026.10.02."] });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r.ok).toBe(true);
    expect(h.touched()).toEqual([
      "click:opener:1", "click:prev", "click:cell:3",
      "click:opener:0", "click:prev", "click:cell:9",
      "click:search",
    ]);
  });
});

describe("where the tab landed — three answers that used to be one word", () => {
  it("a NAVER sign-in host is AUTH_REQUIRED, not «screen not found»", async () => {
    // 2026-10-09, measured: no session, and the review route lands on accounts.commerce.naver.com/login with
    // a password field and no hash, stable for fourteen seconds. The lane said SURFACE_UNEXPECTED, so the
    // seller read «nothing you can do» while the sign-in recovery for exactly this went unreached.
    const h = screen({ hostOk: false, hashOk: false, authHost: true });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "AUTH_REQUIRED", stage: "SURFACE" });
    expect(h.touched()).toEqual([]);
  });

  it("any OTHER host stays «not this screen» — an unseen host is not a login page", async () => {
    const h = screen({ hostOk: false, hashOk: false, authHost: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "SURFACE_UNEXPECTED", stage: "SURFACE" });
    expect(h.touched()).toEqual([]);
  });

  it("the seller centre with its router still working is waited on, then read", async () => {
    // `openTab` returns when a page looks interactive, which for a hash-routed app can be before it draws.
    const h = screen({ hashOkFromRead: 3 });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r.ok).toBe(true);
    expect(h.touched()).toEqual(HAPPY);
  });

  it("a route that never draws is ROUTE_NOT_READY — ours, and not a sign-in wall", async () => {
    const h = screen({ hashOk: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "ROUTE_NOT_READY", stage: "SURFACE" });
    expect(h.touched()).toEqual([]);
  });

  it("a sign-in redirect DURING the wait is still AUTH_REQUIRED", async () => {
    let reads = 0;
    const h = screen({ hashOk: false });
    const inner = h.env.openTab;
    h.env.openTab = async () => {
      const tab = await inner();
      const original = tab.evaluate;
      tab.evaluate = async (script: string) => {
        if (script === "CONTROLS") {
          reads += 1;
          // The session lapses mid-wait: the host becomes a sign-in origin.
          return reads >= 2
            ? { route: false, hostOk: false, hashOk: false, authHost: true, grid: 0 }
            : { route: false, hostOk: true, hashOk: false, authHost: false, grid: 15 };
        }
        return original.call(tab, script);
      };
      return tab;
    };
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "AUTH_REQUIRED", stage: "SURFACE" });
  });

  it("the right route with no rows drawn is still SURFACE_UNEXPECTED, with the count", async () => {
    const h = screen({ grid: 0 });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "SURFACE_UNEXPECTED", stage: "SURFACE", candidates: 0 });
    expect(h.touched()).toEqual([]);
  });

  it("a signed-out page on the RIGHT host is still AUTH_REQUIRED, from the sign-in test", async () => {
    const h = screen({ signedIn: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "AUTH_REQUIRED", stage: "AUTH" });
    expect(h.touched()).toEqual([]);
  });
});

describe("the calendar opener — structure, never position", () => {
  it("stops when the field has no opener bound to it", async () => {
    const h = screen({ openerCandidates: { 0: 0 } });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "CALENDAR_OPENER_NOT_FOUND", stage: "PICK_START", candidates: 0 });
    expect(h.touched()).toEqual([]);
  });

  it("stops when the field's group holds several — never «the first one»", async () => {
    const h = screen({ openerCandidates: { 0: 2 } });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "CALENDAR_OPENER_AMBIGUOUS", stage: "PICK_START", candidates: 2 });
    expect(h.touched()).toEqual([]);
  });

  it("re-reads the census before the second field — an index taken before the calendar opened is stale", async () => {
    // The open calendar adds five controls to the document, so the end field's opener moves. Using the index
    // read at the start would press one of the first calendar's own arrows.
    const h = screen({ shiftEndOpener: true });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r.ok).toBe(true);
    expect(h.touched()).toEqual(HAPPY);
    expect(h.touched().filter((a) => a.startsWith("click:stray"))).toEqual([]);
  });
});

describe("the month on show — identified, then proved", () => {
  it("a calendar that does not draw is a stop", async () => {
    const h = screen({ pickerOpens: false });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "PICKER_VIEW_UNREADABLE", stage: "PICK_START" });
    expect(h.touched()).toEqual(["click:opener:0"]);
  });

  it("an unreadable title is a stop — the month on show is the thing being verified", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen({ titleUnknown: true }).env);
    expect(r).toMatchObject({ ok: false, code: "PICKER_VIEW_UNREADABLE", stage: "PICK_START" });
  });

  it("no month step at all is a stop, not a year jump", async () => {
    const h = screen({ prev: [] });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_NOT_FOUND", stage: "PICK_START", candidates: 0 });
    expect(h.touched()).toEqual(["click:opener:0"]);
  });

  it("two controls that both look like one step back is a stop, with the count", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen({ prev: [PREV, 22] }).env);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_AMBIGUOUS", stage: "PICK_START", candidates: 2 });
  });

  it("an arrow that moves a YEAR is caught after one press, by the title", async () => {
    // Identification narrows the field; this is what holds. The four arrows carry no label at all, so if the
    // icon and the handler ever stop telling a month step from a year jump, the title says so immediately.
    const h = screen({ stepsBy: 12 });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_UNVERIFIED", stage: "PICK_START", candidates: 1 });
    // One press, and then it stopped — it did not keep spending the allowance.
    expect(h.touched()).toEqual(["click:opener:0", "click:prev"]);
  });

  it("the step bound is PER FIELD, so a period a year back is still reachable", async () => {
    const far: NaverReviewWindowRuntimePlan = {
      ...plan,
      requestedStart: { year: 2025, month: 11, day: 3 },
      requestedEnd: { year: 2025, month: 11, day: 9 },
      requestedStartDaysBefore: 339,
      requestedEndDaysBefore: 333,
    };
    const h = screen({ showsAfterSearch: { valuesParsed: 2, startDaysBefore: 339, endDaysBefore: 333 } });
    const r = await asideNaverReviewWindowRuntime(far, h.env);
    // Eleven steps on each calendar. A single shared allowance of thirteen would have stopped the second
    // field halfway for no reason but the first one's distance.
    expect(r).toMatchObject({ ok: true, monthMoves: 22 });
    expect(naverReviewMonthMoveAllowance({ year: 2026, month: 10 }, { year: 2025, month: 11 }))
      .toBeGreaterThanOrEqual(11);
  });

  it("a period further back than the bound stops, with the steps it had taken", async () => {
    const tiny: NaverReviewWindowRuntimePlan = {
      ...plan,
      maxMonthMoves: 1,
      requestedStart: { year: 2026, month: 7, day: 3 },
      requestedEnd: { year: 2026, month: 7, day: 9 },
    };
    const r = await asideNaverReviewWindowRuntime(tiny, screen().env);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_EXHAUSTED", stage: "PICK_START", candidates: 1 });
  });
});

describe("the day cell — chosen from what the page accepted, never from its number", () => {
  it("stops when the day asked for is not among the selectable cells", async () => {
    const h = screen({ targetAbsent: true });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "DAY_CELL_NOT_FOUND", stage: "PICK_START" });
    expect(h.touched()).toEqual(["click:opener:0", "click:prev"]);
  });

  it("stops when the day is offered twice — which is what choosing by number cannot see", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen({ targetTwice: true }).env);
    expect(r).toMatchObject({ ok: false, code: "DAY_CELL_AMBIGUOUS", stage: "PICK_START", candidates: 2 });
  });

  it("a field that did not take the day is RANGE_NOT_SETTABLE, there and then", async () => {
    const h = screen({ pickIgnored: true });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "RANGE_NOT_SETTABLE", stage: "PICK_START" });
    // It did not go on to the second field, and it never pressed 조회.
    expect(h.touched()).toEqual(["click:opener:0", "click:prev", "click:cell:3"]);
  });
});

describe("and then it proves the screen moved", () => {
  it("a period on screen that is not the period asked for reads nothing", async () => {
    for (const shows of [
      { valuesParsed: 1, startDaysBefore: 35, endDaysBefore: 29 },
      { valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0 },
      { valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 0 },
    ]) {
      const h = screen({ showsAfterSearch: shows });
      const r = await asideNaverReviewWindowRuntime(plan, h.env);
      expect(r).toMatchObject({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" });
      // The reader is unreachable without passing the verification.
      expect(h.acts).not.toContain("evaluate:READER");
    }
  });
});

describe("the window plan and its program", () => {
  it("computes the two offsets and the two civil days from one as-of", () => {
    const built = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-09-03", end: "2026-09-09" });
    expect(built).not.toBeNull();
    expect(built!.requestedStartDaysBefore).toBe(35);
    expect(built!.requestedEndDaysBefore).toBe(29);
    expect(built!.requestedStart).toEqual({ year: 2026, month: 9, day: 3 });
    expect(built!.requestedEnd).toEqual({ year: 2026, month: 9, day: 9 });
    expect(built!.dateInputSelector).toBe(NAVER_REVIEW_DATE_INPUT_SELECTOR);
    expect(built!.queryControlSelector).toBe(NAVER_REVIEW_QUERY_CONTROL_SELECTOR);
    expect(built!.dayCellSelector).toBe(NAVER_REVIEW_DAY_CELL_SELECTOR);
    // One month back, plus the margin — derived from the window, not a flat number.
    expect(built!.maxMonthMoves).toBe(1 + NAVER_REVIEW_MONTH_MOVE_MARGIN);
    expect(built!.pickerScript.length).toBeGreaterThan(0);
  });

  it("refuses a period that is reversed, in the future, past the runaway ceiling, or not a date", () => {
    for (const w of [{ start: "2026-09-09", end: "2026-09-03" }, { start: "2026-10-09", end: "2026-10-12" },
      { start: "2016-01-01", end: "2016-01-07" }, { start: "nope", end: "2026-09-09" }]) {
      expect(buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW, w), JSON.stringify(w)).toBeNull();
    }
  });

  it("civilDayOf parses a day, and refuses anything that is not one", () => {
    expect(civilDayOf("2026-09-03")).toEqual({ year: 2026, month: 9, day: 3 });
    for (const bad of ["2026-13-01", "2026-09-32", "2026/09/03", "", "2026-9-3"]) {
      expect(civilDayOf(bad), bad).toBeNull();
    }
  });

  it("a day is reduced to an offset in the seller's own calendar", () => {
    expect(kstDaysBeforeDay("2026-10-08", NOW)).toBe(0);
    expect(kstDaysBeforeDay("2026-09-03", NOW)).toBe(35);
    // 15:30Z on 10-08 is already 10-09 in Seoul.
    expect(kstDaysBeforeDay("2026-10-09", new Date("2026-10-08T15:30:00.000Z"))).toBe(0);
    expect(kstDaysBeforeDay("nope", NOW)).toBeNull();
  });

  it("the shipped program runs in an empty context with only openTab/closeTab", async () => {
    const built = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-09-03", end: "2026-09-09" })!;
    const program = buildNaverReviewWindowRuntimeProgram(built);
    const body = program.replace(/^const __plan = .*$/m, `const __plan = ${JSON.stringify(plan)};`)
      .replace('console.log("ASIDE_RESULT " + JSON.stringify(__result));', "return __result;");
    const h = screen();
    const sandbox = {
      openTab: h.env.openTab,
      closeTab: h.env.closeTab,
      setTimeout,
      Date,
      JSON,
      Number,
      String,
      Math,
      Promise,
      console: { log: () => undefined },
    };
    const result = (await runInNewContext(`(async () => { ${body} })()`, sandbox)) as { ok: boolean };
    expect(result.ok).toBe(true);
    expect(h.touched()).toEqual(HAPPY);
  });

  it("the parser keeps the step count, and refuses a success with a missing half", () => {
    expect(parseNaverReviewWindowRuntimeResult({ ok: true, reading: {}, range: {}, monthMoves: 2, elapsedMs: 9 }))
      .toMatchObject({ ok: true, monthMoves: 2 });
    expect(parseNaverReviewWindowRuntimeResult({ ok: true, reading: {} })).toBeNull();
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "DAY_CELL_AMBIGUOUS", stage: "PICK_END",
      candidates: 2, labelled: null, monthMoves: 1, elapsedMs: 3 }))
      .toMatchObject({ ok: false, code: "DAY_CELL_AMBIGUOUS", stage: "PICK_END", monthMoves: 1 });
    // A word this build does not publish is not quietly accepted.
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "NOPE", stage: "READ" })).toBeNull();
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" }))
      .toMatchObject({ ok: false, code: "RANGE_MISMATCH", candidates: null, monthMoves: 0 });
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

  /** A reading as the page's own reader answers it, with the two numbers the screen states. */
  const reading = {
    reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0,
    gridReadMode: "MODEL", labelledTotal: 0, selectedPageSize: 500, rows: [],
  };

  it("uses the navigating lane and delivers the period it verified, with the screen's own numbers", async () => {
    const deliver = vi.fn(async (_request: unknown) => MATCH);
    const executor = executorFor({
      ok: true,
      reading,
      range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 29,
        pagerNumberCount: 0, highestPagerNumber: 0 },
      monthMoves: 2,
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
    const sent = deliver.mock.calls[0]![0] as unknown as {
      windowStart: string; windowEnd: string; windowDays: number;
      labelledTotal: number | null; selectedPageSize: number | null; gridReadMode: string | null;
      monthMoves: number;
    };
    expect(sent.windowStart).toBe("2026-09-03");
    expect(sent.windowEnd).toBe("2026-09-09");
    expect(sent.windowDays).toBe(7);
    // The evidence the backend judges completeness on — carried, not decided here.
    expect(sent.labelledTotal).toBe(0);
    expect(sent.selectedPageSize).toBe(500);
    expect(sent.gridReadMode).toBe("MODEL");
    expect(sent.monthMoves).toBe(2);
  });

  it("a screen that states no total says so, rather than sending a number it did not read", async () => {
    const deliver = vi.fn(async (_request: unknown) => MATCH);
    await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: executorFor({
        ok: true,
        reading: { reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0, rows: [] },
        range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 35, endDaysBefore: 29,
          pagerNumberCount: 0, highestPagerNumber: 0 },
        elapsedMs: 1,
      }),
    });
    const sent = deliver.mock.calls[0]![0] as unknown as {
      labelledTotal: number | null; selectedPageSize: number | null; gridReadMode: string | null;
    };
    expect(sent.labelledTotal).toBeNull();
    expect(sent.selectedPageSize).toBeNull();
    expect(sent.gridReadMode).toBeNull();
  });

  it("a screen showing some other period delivers nothing", async () => {
    const deliver = vi.fn(async (_request: unknown) => MATCH);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: executorFor({
        ok: true,
        reading,
        range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0,
          pagerNumberCount: 0, highestPagerNumber: 0 },
        elapsedMs: 1,
      }),
    });
    expect(r.outcome).toBe("SURFACE_UNREADABLE");
    expect(deliver).not.toHaveBeenCalled();
  });

  it("a helper without the navigating lane says so instead of reading the wrong period", async () => {
    const deliver = vi.fn(async (_request: unknown) => MATCH);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: { execute: vi.fn(), asOf: () => NOW } as never,
    });
    expect(r.outcome).toBe("EXECUTOR_UNAVAILABLE");
    expect(deliver).not.toHaveBeenCalled();
  });

  it("a desk that could not be reached says WHICH way, and the record keeps it", async () => {
    // 2026-10-09: the first historical catch-up settled EXECUTOR_UNAVAILABLE with a null failure code, so the
    // row could not say whether Aside was unreachable, whether the program threw, or whether the ceiling
    // elapsed. All three were one word, and telling them apart took an hour of probing a healthy chain.
    for (const [reason, code] of [
      ["UNAVAILABLE", "EXECUTOR_UNAVAILABLE"],
      ["TIMEOUT", "EXECUTOR_TIMEOUT"],
      ["REFUSED", "EXECUTOR_REFUSED"],
      ["FAULT", "EXECUTOR_FAULT"],
    ] as const) {
      const deliver = vi.fn(async (_request: unknown) => MATCH);
      const r = await runNaverReviewObservation({
        deliver,
        window: { start: "2026-09-03", end: "2026-09-09" },
        executor: {
          execute: vi.fn(),
          executeWindow: vi.fn(async () => ({
            kind: "UNAVAILABLE" as const,
            stop: { reason, exitCode: 1, signal: null },
            llmCalls: 0 as const,
          })),
          asOf: () => NOW,
        } as never,
      });
      // The OUTCOME stays what it was — the desk failed. The CODE is what gained four ways to say so.
      expect(r, reason).toMatchObject({ outcome: "EXECUTOR_UNAVAILABLE", failureCode: code });
      expect(deliver).not.toHaveBeenCalled();
    }
  });

  it("a word the CLI does not publish becomes the honest fallback, never itself", async () => {
    const r = await runNaverReviewObservation({
      deliver: vi.fn(async (_request: unknown) => MATCH),
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: {
        execute: vi.fn(),
        executeWindow: vi.fn(async () => ({
          kind: "UNAVAILABLE" as const,
          stop: { reason: "SOMETHING_NEW", exitCode: null, signal: null },
          llmCalls: 0 as const,
        })),
        asOf: () => NOW,
      } as never,
    });
    expect(r).toMatchObject({ outcome: "EXECUTOR_UNAVAILABLE", failureCode: "EXECUTOR_UNAVAILABLE" });
    expect(executorFailureCode("SOMETHING_NEW")).toBe("EXECUTOR_UNAVAILABLE");
    // And every word it maps to is one the closed set publishes, so nothing arbitrary can reach a record.
    for (const reason of ["UNAVAILABLE", "TIMEOUT", "REFUSED", "FAULT", "", "nonsense"]) {
      expect(NAVER_OBSERVE_FAILURE_CODES as readonly string[], reason)
        .toContain(executorFailureCode(reason));
    }
  });

  it("a helper without the window lane is still EXECUTOR_UNAVAILABLE — no process ran, so no exit code", async () => {
    const r = await runNaverReviewObservation({
      deliver: vi.fn(async (_request: unknown) => MATCH),
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: { execute: vi.fn(), asOf: () => NOW } as never,
    });
    expect(r).toMatchObject({ outcome: "EXECUTOR_UNAVAILABLE", failureCode: "EXECUTOR_UNAVAILABLE" });
  });

  it("an executor that THREW is a fault on our side, not an unreachable desk", async () => {
    const r = await runNaverReviewObservation({
      deliver: vi.fn(async (_request: unknown) => MATCH),
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: {
        execute: vi.fn(),
        executeWindow: vi.fn(async () => { throw new Error("boom"); }),
        asOf: () => NOW,
      } as never,
    });
    expect(r).toMatchObject({ outcome: "SURFACE_UNREADABLE", failureCode: "EXECUTOR_FAULT" });
  });

  it("a calendar stop reaches the report as its own word", async () => {
    const deliver = vi.fn(async (_request: unknown) => MATCH);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-09-03", end: "2026-09-09" },
      executor: executorFor({ ok: false, code: "DAY_CELL_AMBIGUOUS", stage: "PICK_START", candidates: 2,
        labelled: null, monthMoves: 1, elapsedMs: 3 }),
    });
    expect(r).toMatchObject({ outcome: "SURFACE_UNREADABLE", failureCode: "DAY_CELL_AMBIGUOUS" });
    expect(deliver).not.toHaveBeenCalled();
  });
});

/**
 * **오늘 하루만 — start와 end가 같은 날일 때.**
 *
 * 자동 확인은 닫힌 과거를 catch-up으로 메우고, 아직 쓰이고 있는 하루는 `today … today`로 따로 읽는다.
 * 하루짜리 창은 추측이 아니라 이 lane이 이미 할 수 있어야 하는 일이다 — 포화된 창을 반으로 쪼개는 길이
 * 하루까지 내려가므로(`ReviewCatchUpPlan.split`), 같은 날 두 번 고르는 것은 이미 표현 가능한 요청이다.
 * 여기서 확인하는 것은 그것이 **실제로** 같은 프로그램을 통과한다는 것이다: 두 달력은 각자 열리고 각자
 * 검증되므로 같은 날짜 셀을 두 번 고르는 데 서로에 대한 가정이 없고, 조회 뒤의 검증은 두 경계를 모두
 * 본다. 미래는 여전히 거절된다 — 오늘은 경계이고, 내일은 아무도 읽을 수 없는 기간이다.
 */
describe("the one-day window — 오늘 하루를 이름을 붙여 읽는다", () => {
  const sameDay: NaverReviewWindowRuntimePlan = {
    ...plan,
    requestedStart: { year: 2026, month: 10, day: 8 },
    requestedEnd: { year: 2026, month: 10, day: 8 },
    requestedStartDaysBefore: 0,
    requestedEndDaysBefore: 0,
  };

  it("오늘~오늘은 계획이 된다 — 그리고 내일이 섞이면 계획이 아니다", () => {
    const made = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-10-08", end: "2026-10-08" });
    expect(made).not.toBeNull();
    expect(made!.requestedStart).toEqual({ year: 2026, month: 10, day: 8 });
    expect(made!.requestedEnd).toEqual({ year: 2026, month: 10, day: 8 });
    expect(made!.requestedStartDaysBefore).toBe(0);
    expect(made!.requestedEndDaysBefore).toBe(0);

    // 미래로 끝나는 기간은 아무도 읽은 기간이 아니다.
    expect(buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-10-08", end: "2026-10-09" })).toBeNull();
    // 거꾸로인 기간도 기간이 아니다.
    expect(buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW,
      { start: "2026-10-08", end: "2026-10-07" })).toBeNull();
  });

  it("같은 날을 두 번 고르고, 달을 넘기지 않고, 조회하고, 읽는다", async () => {
    const h = screen({ showsAfterSearch: { valuesParsed: 2, startDaysBefore: 0, endDaysBefore: 0 } });
    const r = await asideNaverReviewWindowRuntime(sameDay, h.env);

    expect(r).toMatchObject({ ok: true, monthMoves: 0 });
    // 두 달력이 각자 열리고, 각자 같은 날을 받는다. 서로를 가정하는 단계는 없다.
    expect(h.touched()).toEqual([
      "click:opener:0", "click:cell:8",
      "click:opener:1", "click:cell:8",
      "click:search",
    ]);
    expect(h.touched().every((a) => a.startsWith("click:"))).toBe(true);
  });

  it("화면이 하루로 좁혀지지 않았으면 읽지 않는다 — 양쪽 경계를 모두 본다", async () => {
    // 조회 뒤에도 7일 기간이 걸려 있다: 요청한 기간이 아니므로 이 읽기는 성립하지 않는다.
    const h = screen({ showsAfterSearch: { valuesParsed: 2, startDaysBefore: 6, endDaysBefore: 0 } });
    const r = await asideNaverReviewWindowRuntime(sameDay, h.env);
    expect(r).toMatchObject({ ok: false, code: "RANGE_MISMATCH", stage: "VERIFY" });
    expect(h.acts).not.toContain("evaluate:READER");
  });
});

describe("빈 기간 — 읽었고, 저장할 것이 없고, 귀속할 것도 없다", () => {
  const UNRESOLVED: NaverDeliveryResponse =
    { identityVerdict: "UNRESOLVED", received: 0, inserted: 0, changed: 0, skipped: 0, failed: 0 };

  function windowExecutor(result: unknown) {
    return {
      execute: vi.fn(async () => ({ kind: "RESULT" as const, result: result as never, llmCalls: 0 as const })),
      executeWindow: vi.fn(async () => ({ kind: "RESULT" as const, result: result as never, llmCalls: 0 as const })),
      asOf: () => NOW,
    };
  }

  const emptyReading = {
    reason: "OK", modelType: null, rowCount: 0, loaded: 0, linkChecked: 0,
    gridReadMode: "EMPTY_STATE", emptyState: true, labelledTotal: 0, selectedPageSize: 500, rows: [],
  };
  const range = { dateInputCount: 2, valuesParsed: 2, startDaysBefore: 0, endDaysBefore: 0,
    pagerNumberCount: 0, highestPagerNumber: 0 };

  it("그리드가 비었다고 말한 기간은 OBSERVED(0)이다 — 귀속은 백엔드가 정한다", async () => {
    const deliver = vi.fn(async (_request: unknown) => UNRESOLVED);
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-10-08", end: "2026-10-08" },
      executor: windowExecutor({ ok: true, reading: emptyReading, range, monthMoves: 0, elapsedMs: 1 }),
    });
    expect(r.outcome).toBe("OBSERVED");
    expect(r.observedCount).toBe(0);
    const sent = deliver.mock.calls[0]![0] as unknown as
      { gridReadMode: string | null; emptyPeriod: boolean; screenStoreDigest: string | null };
    expect(sent.gridReadMode).toBe("EMPTY_STATE");
    // 화면이 「없다」고 말했다는 사실이 그대로 건너간다 — 0행이라는 수만으로는 구분되지 않는다.
    expect(sent.emptyPeriod).toBe(true);
    expect(sent.screenStoreDigest).toBeNull();
  });

  it("귀속된 빈 기간(MATCH)도 그대로 OBSERVED(0) — 그리고 화면 지문이 함께 건너간다", async () => {
    const digest = "a".repeat(64);
    const deliver = vi.fn(async (_request: unknown) => ({ ...UNRESOLVED, identityVerdict: "MATCH" }));
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-10-08", end: "2026-10-08" },
      executor: windowExecutor({
        ok: true, reading: emptyReading, range, monthMoves: 0, storeFingerprint: digest, elapsedMs: 1,
      }),
    });
    expect(r.outcome).toBe("OBSERVED");
    expect(r.observedCount).toBe(0);
    const sent = deliver.mock.calls[0]![0] as unknown as { screenStoreDigest: string | null };
    expect(sent.screenStoreDigest).toBe(digest);
  });

  it("지문 모양이 아닌 값은 건너가지 않는다 — 비교 장치에 아무 문자열이나 들어오지 않는다", async () => {
    const deliver = vi.fn(async (_request: unknown) => UNRESOLVED);
    await runNaverReviewObservation({
      deliver,
      window: { start: "2026-10-08", end: "2026-10-08" },
      executor: windowExecutor({
        ok: true, reading: emptyReading, range, monthMoves: 0, storeFingerprint: "NOPE", elapsedMs: 1,
      }),
    });
    const sent = deliver.mock.calls[0]![0] as unknown as { screenStoreDigest: string | null };
    expect(sent.screenStoreDigest).toBeNull();
  });

  it("행이 있는데 UNRESOLVED면 예전처럼 거절한다 — 가게 확인을 건너뛰는 문이 열린 것이 아니다", async () => {
    const withRow = {
      ...emptyReading,
      emptyState: false, gridReadMode: "MODEL", rowCount: 1, loaded: 1,
      rows: [{ reviewId: "5100000001", createdAt: "2026-10-08T10:00:00.000+09:00", rating: 5,
        body: "좋아요", productNo: "6473457702", productName: "상품", answered: false, attachCount: 0 }],
    };
    const deliver = vi.fn(async (_request: unknown) => ({ ...UNRESOLVED, received: 1 }));
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-10-08", end: "2026-10-08" },
      executor: windowExecutor({ ok: true, reading: withRow, range, monthMoves: 0, elapsedMs: 1 }),
    });
    expect(r.outcome).toBe("STORE_UNRESOLVED");
  });

  it("비었다고 주장하면서 행을 건네면 그 주장은 버려진다 — 그리고 귀속 없이는 통과하지 못한다", async () => {
    const contradictory = {
      ...emptyReading,
      emptyState: true, rowCount: 1, loaded: 1,
      rows: [{ reviewId: "5100000002", createdAt: "2026-10-08T10:00:00.000+09:00", rating: 4,
        body: "그럭저럭", productNo: "6473457702", productName: "상품", answered: false, attachCount: 0 }],
    };
    const deliver = vi.fn(async (_request: unknown) => ({ ...UNRESOLVED, received: 1 }));
    const r = await runNaverReviewObservation({
      deliver,
      window: { start: "2026-10-08", end: "2026-10-08" },
      executor: windowExecutor({ ok: true, reading: contradictory, range, monthMoves: 0, elapsedMs: 1 }),
    });
    expect(r.outcome).toBe("STORE_UNRESOLVED");
  });
});

/**
 * **Backfill is unattended and goes back further than a year (product decision, 2026-10-13).**
 *
 * The 365-day refusal and the flat 13-step allowance are gone; what replaces them is pinned here:
 *
 *  - a window two years back PLANS, and RUNS on the fake page inside the real budgets, in simulated time;
 *  - the read's settle budget starts at the verified period — a long navigation no longer eats it;
 *  - the navigation's share of the run is bounded, and running past it is a named stop before 조회;
 *  - the month-step allowance is derived from the window and capped at a runaway ceiling;
 *  - how far back is the marketplace's call: a `disabled` day — or a disabled step toward it — is
 *    `DATE_NOT_SELECTABLE`, nothing is pressed on it, and no period is reported.
 */
describe("the backfill window — further than a year, inside the budgets", () => {
  const TWO_YEARS = { start: "2024-10-01", end: "2024-10-31" };

  /** The real plan for a window, with the page scripts swapped for the fake page's names. */
  function realPlan(window: { start: string; end: string }): NaverReviewWindowRuntimePlan {
    const built = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW, window);
    expect(built, JSON.stringify(window)).not.toBeNull();
    return { ...built!, entryUrl: plan.entryUrl, controlsScript: "CONTROLS", authScript: "AUTH",
      readerScript: "READER", rangeScript: "RANGE", pickerScript: "PICKER", storeFingerprintScript: undefined,
      dateInputSelector: "DATES", queryControlSelector: "QUERY", dayCellSelector: "CELLS" };
  }

  async function runOnClock(p: NaverReviewWindowRuntimePlan, clickMs: number, evaluateMs: number) {
    const clock = { t: 1_000_000, clickMs, evaluateMs };
    const spy = vi.spyOn(Date, "now").mockImplementation(() => clock.t);
    try {
      const h = screen({
        clock,
        showsAfterSearch: { valuesParsed: 2, startDaysBefore: p.requestedStartDaysBefore,
          endDaysBefore: p.requestedEndDaysBefore },
      });
      const r = await asideNaverReviewWindowRuntime(p, h.env);
      return { r, h, spent: clock.t - 1_000_000 };
    } finally {
      spy.mockRestore();
    }
  }

  it("a window two years back is a plan — the 365-day horizon is gone", () => {
    const p = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW, TWO_YEARS);
    expect(p).not.toBeNull();
    expect(p!.requestedStartDaysBefore).toBe(kstDaysBeforeDay("2024-10-01", NOW));
    expect(p!.requestedStartDaysBefore).toBeGreaterThan(700);
    // 24 months back, plus the margin.
    expect(p!.maxMonthMoves).toBe(24 + NAVER_REVIEW_MONTH_MOVE_MARGIN);
  });

  it("the window lane has its own budgets, under Aside's ceiling — the plain read keeps its own", () => {
    const p = buildNaverReviewWindowRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW, TWO_YEARS)!;
    expect(p.runBudgetMs).toBe(NAVER_REVIEW_WINDOW_RUN_BUDGET_MS);
    expect(p.settleTimeoutMs).toBe(NAVER_REVIEW_WINDOW_READ_SETTLE_MS);
    // The read's share is reserved out of the run before the navigation gets the rest.
    expect(p.navigationBudgetMs + p.settleTimeoutMs + p.searchSettleMs).toBe(p.runBudgetMs);
    expect(p.loadWaitMs).toBeLessThan(p.navigationBudgetMs);
    // The CLI waits for the whole program, and never longer than Aside itself will.
    expect(naverReviewWindowCliTimeoutMs(p)).toBeGreaterThan(p.runBudgetMs);
    expect(naverReviewWindowCliTimeoutMs(p)).toBeLessThanOrEqual(ASIDE_REPL_CEILING_MS);
    expect(naverReviewWindowCliTimeoutMs(p)).toBeGreaterThan(NAVER_REVIEW_READ_WORKFLOW.settleTimeoutMs + 30_000);
    // The plain 7-day read is unchanged.
    expect(NAVER_REVIEW_READ_WORKFLOW.settleTimeoutMs).toBe(45_000);
    expect(buildNaverReviewRuntimePlan(NAVER_REVIEW_READ_WORKFLOW, NOW).settleTimeoutMs).toBe(45_000);
  });

  it("the executor hands the CLI the window ceiling, not the plain read's settle + 30 s", async () => {
    const seen: (number | undefined)[] = [];
    vi.resetModules();
    vi.doMock("../../src/aside/aside-cli", async (importOriginal) => ({
      ...(await importOriginal<typeof import("../../src/aside/aside-cli")>()),
      runAsideRepl: async (_program: string, opts: { timeoutMs?: number } = {}) => {
        seen.push(opts.timeoutMs);
        return { kind: "NO_RESULT", reason: "UNAVAILABLE", elapsedMs: 0, exitCode: null, signal: null };
      },
    }));
    try {
      const mod = await import("../../src/aside/naver-review-executor");
      const ex = new mod.AsideNaverReviewExecutor({ now: () => NOW });
      await ex.executeWindow(TWO_YEARS);
      await ex.execute();
    } finally {
      vi.doUnmock("../../src/aside/aside-cli");
      vi.resetModules();
    }
    // The window run waits for its whole program; the plain read keeps the ceiling it always had.
    expect(seen).toEqual([NAVER_REVIEW_WINDOW_RUN_BUDGET_MS + 10_000, NAVER_REVIEW_READ_WORKFLOW.settleTimeoutMs + 30_000]);
  });

  it("two years back RUNS on the fake page, inside the run budget, at a realistic step cost", async () => {
    const p = realPlan(TWO_YEARS);
    // 300 ms per press and 50 ms per evaluate, in simulated time; the page redraws on the first poll.
    const { r, h, spent } = await runOnClock(p, 300, 50);
    expect(r).toMatchObject({ ok: true, monthMoves: 48 });
    expect(h.touched().filter((a) => a === "click:prev")).toHaveLength(48);
    expect(h.touched().at(-1)).toBe("click:search");
    expect(spent).toBeLessThan(p.navigationBudgetMs);
    expect(spent).toBeLessThan(p.runBudgetMs);
  });

  it("the read's settle starts at the verified period — a navigation longer than it no longer eats it", async () => {
    // ~1.1 s per step for 48 steps: the navigation alone passes the read's whole 30 s settle. Measured from the
    // tab opening (the old rule) the read would have had nothing left; measured from VERIFY it reads.
    const p = realPlan(TWO_YEARS);
    const { r, spent } = await runOnClock(p, 1_000, 50);
    expect(spent).toBeGreaterThan(p.settleTimeoutMs);
    expect(r).toMatchObject({ ok: true, monthMoves: 48 });
  });

  it("past the navigation's share of the run it stops BEFORE 조회, with a word — never killed mid-step", async () => {
    const p = realPlan(TWO_YEARS);
    const { r, h, spent } = await runOnClock(p, 2_000, 50);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_EXHAUSTED" });
    expect(h.touched()).not.toContain("click:search");
    expect(h.acts).not.toContain("evaluate:READER");
    expect(spent).toBeLessThan(p.runBudgetMs);
  });

  it("the move allowance is derived from the window and capped at the runaway ceiling", () => {
    const asOf = { year: 2026, month: 10 };
    expect(naverReviewMonthMoveAllowance(asOf, { year: 2026, month: 10 })).toBe(NAVER_REVIEW_MONTH_MOVE_MARGIN);
    expect(naverReviewMonthMoveAllowance(asOf, { year: 2024, month: 10 })).toBe(24 + NAVER_REVIEW_MONTH_MOVE_MARGIN);
    // Exactly at the ceiling is allowed; one month past it is refused.
    const lastMonths = NAVER_REVIEW_MONTH_MOVE_CEILING - NAVER_REVIEW_MONTH_MOVE_MARGIN;
    const last = { year: Math.floor((2026 * 12 + 10 - lastMonths - 1) / 12),
      month: ((2026 * 12 + 10 - lastMonths - 1) % 12) + 1 };
    expect(naverReviewMonthMoveAllowance(asOf, last)).toBe(NAVER_REVIEW_MONTH_MOVE_CEILING);
    const beyond = { year: last.month === 1 ? last.year - 1 : last.year, month: last.month === 1 ? 12 : last.month - 1 };
    expect(naverReviewMonthMoveAllowance(asOf, beyond)).toBeNull();
    expect(NAVER_REVIEW_MONTH_MOVE_CEILING).toBeLessThanOrEqual(120);
    // A future start month is not an allowance.
    expect(naverReviewMonthMoveAllowance(asOf, { year: 2026, month: 11 })).toBeNull();
  });

  it("the ceiling holds at runtime: a calendar that never reaches the month stops at the allowance", async () => {
    // A title that keeps moving one month at a time but the target is further than the allowance.
    const far: NaverReviewWindowRuntimePlan = {
      ...plan,
      maxMonthMoves: 3,
      requestedStart: { year: 2024, month: 10, day: 3 },
      requestedEnd: { year: 2024, month: 10, day: 9 },
    };
    const h = screen();
    const r = await asideNaverReviewWindowRuntime(far, h.env);
    expect(r).toMatchObject({ ok: false, code: "MONTH_NAV_EXHAUSTED", stage: "PICK_START", candidates: 3 });
    expect(h.touched().filter((a) => a === "click:prev")).toHaveLength(3);
  });

  it("a disabled target day is DATE_NOT_SELECTABLE — nothing is pressed on it, no period is reported", async () => {
    const h = screen({ targetDisabled: true });
    const r = await asideNaverReviewWindowRuntime(plan, h.env);
    expect(r).toMatchObject({ ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_START" });
    expect(h.touched()).toEqual(["click:opener:0", "click:prev"]);
    expect(h.touched().filter((a) => a.startsWith("click:cell"))).toEqual([]);
    expect(h.acts).not.toContain("evaluate:READER");
  });

  it("a month drawn entirely disabled is still read as a calendar — and still DATE_NOT_SELECTABLE", async () => {
    const r = await asideNaverReviewWindowRuntime(plan, screen({ monthDisabled: true }).env);
    expect(r).toMatchObject({ ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_START" });
  });

  it("a month step toward the target that the component disabled is DATE_NOT_SELECTABLE, not NOT_FOUND", async () => {
    const blocked = await asideNaverReviewWindowRuntime(plan, screen({ prev: [], prevBlocked: 1 }).env);
    expect(blocked).toMatchObject({ ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_START", candidates: 0 });
    // A step that is simply absent stays the calendar-reading stop it always was.
    const absent = await asideNaverReviewWindowRuntime(plan, screen({ prev: [] }).env);
    expect(absent).toMatchObject({ ok: false, code: "MONTH_NAV_NOT_FOUND" });
  });

  it("DATE_NOT_SELECTABLE is a word the parser and the runner's closed list both publish", async () => {
    expect(parseNaverReviewWindowRuntimeResult({ ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_END",
      candidates: 0, labelled: null, monthMoves: 24, elapsedMs: 3 }))
      .toMatchObject({ ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_END" });
    expect(NAVER_OBSERVE_FAILURE_CODES).toContain("DATE_NOT_SELECTABLE");
    const r = await runNaverReviewObservation({
      deliver: vi.fn(async (_request: unknown) => ({ identityVerdict: "MATCH", received: 0, inserted: 0,
        changed: 0, skipped: 0, failed: 0 }) as NaverDeliveryResponse),
      window: TWO_YEARS,
      executor: {
        execute: vi.fn(),
        executeWindow: vi.fn(async () => ({ kind: "RESULT" as const, llmCalls: 0 as const,
          result: { ok: false, code: "DATE_NOT_SELECTABLE", stage: "PICK_START", candidates: 0, labelled: null,
            monthMoves: 24, elapsedMs: 3 } as never })),
        asOf: () => NOW,
      },
    });
    expect(r).toMatchObject({ outcome: "SURFACE_UNREADABLE", failureCode: "DATE_NOT_SELECTABLE" });
  });

  it("the runner accepts a verified window two years back — its old 365 bound no longer refuses it", async () => {
    const start = kstDaysBeforeDay(TWO_YEARS.start, NOW)!;
    const end = kstDaysBeforeDay(TWO_YEARS.end, NOW)!;
    const deliver = vi.fn(async (_request: unknown) => ({ identityVerdict: "MATCH", received: 0, inserted: 0,
      changed: 0, skipped: 0, failed: 0 }) as NaverDeliveryResponse);
    const r = await runNaverReviewObservation({
      deliver,
      window: TWO_YEARS,
      executor: {
        execute: vi.fn(),
        executeWindow: vi.fn(async () => ({ kind: "RESULT" as const, llmCalls: 0 as const, result: {
          ok: true,
          reading: { reason: "OK", modelType: "infinite", rowCount: 0, loaded: 0, rows: [] },
          range: { dateInputCount: 2, valuesParsed: 2, startDaysBefore: start, endDaysBefore: end,
            pagerNumberCount: 0, highestPagerNumber: 0 },
          monthMoves: 48, elapsedMs: 1,
        } as never })),
        asOf: () => NOW,
      },
    });
    expect(r.outcome).toBe("OBSERVED");
    const sent = deliver.mock.calls[0]![0] as unknown as { windowStart: string; windowEnd: string; windowDays: number };
    expect(sent.windowStart).toBe(TWO_YEARS.start);
    expect(sent.windowEnd).toBe(TWO_YEARS.end);
    expect(sent.windowDays).toBe(31);
  });
});
