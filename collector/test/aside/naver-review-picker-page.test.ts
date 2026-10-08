import { describe, expect, it } from "vitest";
import { runInNewContext } from "node:vm";
import {
  NAVER_REVIEW_DATE_INPUT_SELECTOR,
  NAVER_REVIEW_DAY_CELL_SELECTOR,
  NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
} from "../../src/aside/naver-review-workflow";
import {
  buildNaverReviewControlsScript,
  buildNaverReviewPickerScript,
} from "../../src/naver/review-list-observe-inpage";

/**
 * **The calendar, judged in the page — against the structure the live surface actually has.**
 *
 * The period's two fields are readonly, so the calendar is the only way into a past period, and the first
 * probe that looked at it reported it unusable: no date on any cell, no month navigation. That probe had
 * climbed from the readonly input and called the input's own 190x34 wrapper the picker, so the cells it
 * counted belonged to another view and the arrows it declared missing were two levels above the box it had
 * chosen. One bad root, three false conclusions — and a judgment of «do not implement» resting on all three.
 *
 * <p>So the document described below is not a convenient shape; it is the shape measured on the logged-in
 * surface on 2026-10-08:
 *
 * ```
 * div.form-group._startDate_dropdown          <- the side's own territory
 *   div.input-group.dropdown-toggle           <- one field, one opener
 *     input.form-control (readonly)
 *     a                   「달력보기」
 *   div.datetimepicker                        <- created on open; absent until then
 *     div.datetimepicker-header
 *       button.left  > i.fn-booking-last-backward1   changeViewPrevYear(…)
 *       button.left  > i.fn-booking-backward1        changeView(data.currentView, data.leftDate, …)
 *       button.title 「2026.10」                      changeView(data.previousView, …)
 *       button.right > i.fn-booking-forward1         changeView(data.currentView, data.rightDate, …)
 *       button.right > i.fn-booking-first-forward1   changeViewNextYear(…)
 *     div.datetimepicker-body.day-view
 *       table  th.dow x7  /  td.day… > div > span 「3」  x42
 * ```
 *
 * The four arrows carry **no text, no `title` and no `aria-label`** — their only child is an `aria-hidden`
 * `<i>`. A rule written as «the control labelled 이전 달» is not implementable here, which is why a step is
 * identified by two markers that must agree and then proved against the title.
 *
 * <p>A described document rather than a real one: the collector has no jsdom, and this is the same `vm` +
 * fake-page shape the other page scripts are tested with.
 */

interface Spec {
  tag: string;
  cls?: string;
  text?: string;
  attrs?: Record<string, string>;
  type?: string;
  value?: string;
  disabled?: boolean;
  display?: string;
  width?: number;
  height?: number;
  kids?: Spec[];
}

interface Node {
  tagName: string;
  className: string;
  disabled: boolean;
  value?: string;
  __attrs: Record<string, string>;
  __display: string;
  __w: number;
  __h: number;
  __kids: Node[];
  parentElement: Node | null;
  children: Node[];
  textContent: string;
  getAttribute(name: string): string | null;
  getBoundingClientRect(): { width: number; height: number };
  contains(other: unknown): boolean;
  querySelectorAll(selector: string): Node[];
}

function build(spec: Spec, parent: Node | null, all: Node[]): Node {
  const node = {
    tagName: spec.tag.toUpperCase(),
    className: spec.cls ?? "",
    disabled: spec.disabled ?? false,
    __attrs: { ...(spec.attrs ?? {}), ...(spec.type ? { type: spec.type } : {}) },
    __display: spec.display ?? "block",
    __w: spec.width ?? 40,
    __h: spec.height ?? 20,
    __kids: [] as Node[],
    parentElement: parent,
  } as Node;
  if (spec.value !== undefined) {
    node.value = spec.value;
  }
  node.getAttribute = (name: string) => (name in node.__attrs ? node.__attrs[name]! : null);
  node.getBoundingClientRect = () => ({ width: node.__w, height: node.__h });
  node.contains = (other: unknown) => {
    let cur = other as Node | null;
    while (cur) {
      if (cur === node) return true;
      cur = cur.parentElement;
    }
    return false;
  };
  const descendants = (): Node[] => node.__kids.flatMap((k) => [k, ...k.querySelectorAll("*")]);
  node.querySelectorAll = (selector: string) =>
    selector === "*" ? descendants() : descendants().filter((d) => d.tagName === selector.toUpperCase());
  all.push(node);
  node.__kids = (spec.kids ?? []).map((k) => build(k, node, all));
  Object.defineProperty(node, "children", { get: () => node.__kids });
  Object.defineProperty(node, "textContent", {
    get: () => (spec.text ?? "") + node.__kids.map((k) => k.textContent).join(""),
  });
  return node;
}

const DAYS_IN = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

interface Calendar {
  year: number;
  month: number;
  /** Days of the displayed month to mark `disabled` — the component's own word for «not selectable». */
  disabledDays?: number[];
  /** Drop the YYYY.MM title, as a month-view or year-view would. */
  noTitle?: boolean;
  /** Fewer than seven weekday headings — the other half of «this is not a day view». */
  weekdays?: number;
  /** A second arrow that also looks like a single backward step. */
  twoPrev?: boolean;
  /** No single-step arrows at all — only the year jumps. */
  yearJumpsOnly?: boolean;
}

/** The 6x7 grid as the component draws it: the previous month's tail, this month, the next month's head. */
function dayCells(cal: Calendar): Spec[] {
  const lead = new Date(Date.UTC(cal.year, cal.month - 1, 1)).getUTCDay();
  const prevMonth = cal.month === 1 ? 12 : cal.month - 1;
  const prevYear = cal.month === 1 ? cal.year - 1 : cal.year;
  const prevLen = DAYS_IN(prevYear, prevMonth);
  const len = DAYS_IN(cal.year, cal.month);
  const out: Spec[] = [];
  for (let i = 0; i < lead; i++) {
    out.push(cell("day past", prevLen - lead + 1 + i));
  }
  for (let d = 1; d <= len; d++) {
    const off = (cal.disabledDays ?? []).includes(d);
    out.push(cell(off ? "day disabled" : "day", d));
  }
  let next = 1;
  while (out.length < 42) {
    out.push(cell(next > 7 ? "day future disabled" : "day future", next));
    next += 1;
  }
  return out;
}

function cell(cls: string, day: number): Spec {
  return {
    tag: "td",
    cls,
    kids: [{ tag: "div", kids: [{ tag: "span", text: String(day), width: 30, height: 30,
      attrs: { "data-ng-click": "changeView(data.nextView, dateObject, $event)" } }] }],
  };
}

function header(cal: Calendar): Spec {
  const arrows: Spec[] = [
    { tag: "button", cls: "left", attrs: { "data-ng-click": "changeViewPrevYear(data.leftDate, $event)" },
      kids: [{ tag: "i", cls: "fn-booking fn-booking-last-backward1", attrs: { "aria-hidden": "true" } }] },
  ];
  if (!cal.yearJumpsOnly) {
    arrows.push({ tag: "button", cls: "left",
      attrs: { "data-ng-click": "changeView(data.currentView, data.leftDate, $event)" },
      kids: [{ tag: "i", cls: "fn-booking fn-booking-backward1", attrs: { "aria-hidden": "true" } }] });
  }
  if (cal.twoPrev) {
    arrows.push({ tag: "button", cls: "left",
      attrs: { "data-ng-click": "changeView(data.currentView, data.leftDate, $event)" },
      kids: [{ tag: "i", cls: "fn-booking fn-booking-backward1", attrs: { "aria-hidden": "true" } }] });
  }
  if (!cal.noTitle) {
    arrows.push({ tag: "button", cls: "title", text: `${cal.year}.${String(cal.month).padStart(2, "0")}`,
      attrs: { "data-ng-click": "changeView(data.previousView, data.previousViewDate, $event)" } });
  }
  if (!cal.yearJumpsOnly) {
    arrows.push({ tag: "button", cls: "right",
      attrs: { "data-ng-click": "changeView(data.currentView, data.rightDate, $event)" },
      kids: [{ tag: "i", cls: "fn-booking fn-booking-forward1", attrs: { "aria-hidden": "true" } }] });
  }
  arrows.push({ tag: "button", cls: "right", attrs: { "data-ng-click": "changeViewNextYear(data.rightDate, $event)" },
    kids: [{ tag: "i", cls: "fn-booking fn-booking-first-forward1", attrs: { "aria-hidden": "true" } }] });
  return { tag: "div", cls: "datetimepicker-header", kids: arrows };
}

function picker(cal: Calendar): Spec {
  const dow = ["일", "월", "화", "수", "목", "금", "토"].slice(0, cal.weekdays ?? 7)
    .map((d) => ({ tag: "th", cls: "dow", text: d }));
  return {
    tag: "div",
    cls: "datetimepicker ng-not-empty ng-valid",
    kids: [
      header(cal),
      { tag: "div", cls: "datetimepicker-body day-view", kids: [
        { tag: "table", cls: "table table-condensed", kids: [
          { tag: "thead", kids: [{ tag: "tr", kids: dow }] },
          { tag: "tbody", kids: dayCells(cal) },
        ] },
      ] },
    ],
  };
}

function side(which: "start" | "end", value: string, cal?: Calendar, extraOpener = false): Spec {
  const group: Spec[] = [
    // A readonly text input with a date-ish class — which is how the live component's fields are accepted
    // by the date predicate. That predicate is the grammar test's subject; here it just has to hold.
    { tag: "input", cls: "form-control ng-pristine ng-valid-date", type: "text", value, width: 120 },
    { tag: "a", text: "달력보기", width: 18, height: 15 },
  ];
  if (extraOpener) {
    group.push({ tag: "a", text: "도움말", width: 18, height: 15 });
  }
  const kids: Spec[] = [{ tag: "div", cls: "input-group dropdown-toggle", kids: group }];
  if (cal) {
    kids.push(picker(cal));
  }
  return { tag: "div", cls: `form-group _${which}Date_dropdown seller-datetime-picker dropdown`, kids };
}

/** The review screen: the period's component, and the list's own 조회 — both inside the filter's form. */
function page(opts: { startValue?: string; endValue?: string; open?: "start" | "end"; cal?: Calendar;
  extraOpener?: boolean } = {}) {
  const cal = opts.cal ?? { year: 2026, month: 10 };
  const root: Spec = {
    tag: "form",
    kids: [
      { tag: "ncp-datetime-range-picker2", kids: [
        { tag: "div", cls: "seller-calendar", kids: [
          { tag: "div", cls: "input-daterange date", kids: [
            side("start", opts.startValue ?? "2026.10.02.", opts.open === "start" ? cal : undefined,
              opts.extraOpener === true),
            side("end", opts.endValue ?? "2026.10.08.", opts.open === "end" ? cal : undefined),
          ] },
        ] },
      ] },
      { tag: "button", cls: "btn", text: "조회", width: 120, height: 40 },
    ],
  };
  const all: Node[] = [];
  build(root, null, all);
  const isDate = (n: Node) =>
    n.tagName === "INPUT"
    && (n.getAttribute("type") === "date" || /date|calendar|picker/i.test(n.className));
  const isPress = (n: Node) =>
    n.tagName === "BUTTON" || n.tagName === "A"
    || (n.tagName === "INPUT" && ["button", "submit"].includes(String(n.getAttribute("type"))));
  return {
    document: {
      querySelectorAll: (selector: string) => {
        if (selector.includes(".ag-row")) return [];
        if (selector === NAVER_REVIEW_DAY_CELL_SELECTOR) return all.filter((n) => n.tagName === "TD");
        if (selector.startsWith("input[type=\"date\"]")) return all.filter(isDate);
        return all.filter(isPress);
      },
    },
    window: {
      getComputedStyle: (el: Node) => ({ display: el.__display, visibility: "visible", pointerEvents: "auto" }),
    },
    location: { host: "sell.smartstore.naver.com", hash: "#/review/search" },
    String,
    Number,
    RegExp,
    Date,
  };
}

type SideAnswer = {
  dateIndex: number; open: boolean; year: number | null; month: number | null;
  weekdays: number; cells: number; prev: number[]; next: number[]; days: [number, number][];
};

function pickerCensus(sandbox: Record<string, unknown>) {
  const script = buildNaverReviewPickerScript(NAVER_REVIEW_DATE_INPUT_SELECTOR,
    NAVER_REVIEW_QUERY_CONTROL_SELECTOR, NAVER_REVIEW_DAY_CELL_SELECTOR);
  return runInNewContext(script, sandbox) as { readable: boolean; dateAccepted: number[]; sides: SideAnswer[] };
}

function controlsCensus(sandbox: Record<string, unknown>) {
  const script = buildNaverReviewControlsScript(NAVER_REVIEW_DATE_INPUT_SELECTOR,
    NAVER_REVIEW_QUERY_CONTROL_SELECTOR);
  return runInNewContext(script, sandbox) as {
    dateAccepted: number[] | null; queryAccepted: number[] | null;
    openers: { dateIndex: number; openerIndex: number; openerCandidates: number }[];
  };
}

describe("the calendar opener is found by structure, never by position", () => {
  it("binds each period field to the one pressable thing in its own group", () => {
    const r = controlsCensus(page());
    expect(r.dateAccepted).toEqual([0, 1]);
    // Two openers, one per field, each resolved to a single index into the pressable candidate set.
    expect(r.openers).toHaveLength(2);
    expect(r.openers[0]!.openerCandidates).toBe(1);
    expect(r.openers[1]!.openerCandidates).toBe(1);
    expect(r.openers[0]!.openerIndex).toBeGreaterThanOrEqual(0);
    expect(r.openers[1]!.openerIndex).not.toBe(r.openers[0]!.openerIndex);
  });

  it("stays unambiguous while a calendar is open — the group gained no pressable thing", () => {
    // This is why the census is read again before every press: the OPEN calendar adds five buttons to the
    // document and shifts every index after them, while the binding itself does not move.
    const closed = controlsCensus(page());
    const open = controlsCensus(page({ open: "start" }));
    expect(open.openers.map((o) => o.openerCandidates)).toEqual([1, 1]);
    // The end field's opener is now at a different position in the same candidate set. An index taken before
    // the calendar opened would, used afterwards, press one of the calendar's own arrows.
    expect(open.openers[1]!.openerIndex).not.toBe(closed.openers[1]!.openerIndex);
  });

  it("refuses rather than guesses when a group holds more than one pressable thing", () => {
    // A second anchor beside the field — a help link, a clear button, anything. «The first one» is exactly
    // the guess this lane does not make.
    const r = controlsCensus(page({ extraOpener: true }));
    expect(r.openers[0]!.openerCandidates).toBe(2);
    expect(r.openers[0]!.openerIndex).toBe(-1);
  });
});

describe("the open calendar, read as structure", () => {
  it("reads the month on show, the weekday headings, and the full grid", () => {
    const r = pickerCensus(page({ open: "start" }));
    const s = r.sides.find((x) => x.open)!;
    expect(s.year).toBe(2026);
    expect(s.month).toBe(10);
    expect(s.weekdays).toBe(7);
    expect(s.cells).toBe(42);
    expect(s.dateIndex).toBe(0);
  });

  it("the other field's calendar is not open, and says so", () => {
    const r = pickerCensus(page({ open: "start" }));
    expect(r.sides.filter((s) => s.open)).toHaveLength(1);
    expect(r.sides.find((s) => s.dateIndex === 1)!.open).toBe(false);
  });

  it("offers only the days OF THE MONTH ON SHOW — the neighbours' filler days are not candidates", () => {
    const r = pickerCensus(page({ open: "start" }));
    const s = r.sides.find((x) => x.open)!;
    // October 2026 has 31 days, and the 6x7 grid holds 42 cells. Only the 31 are offered.
    expect(s.days).toHaveLength(31);
    expect(s.days.map((d) => d[1]).sort((a, b) => a - b)).toEqual(
      Array.from({ length: 31 }, (_, i) => i + 1));
  });

  it("a day that appears twice on the page is offered once — the duplicate is the next month's", () => {
    // The live September page prints 「3」 twice: September 3rd, and October 3rd in the trailing week. Choosing
    // by the number printed on a cell would pick one of them with no way to know which.
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 9 } }));
    const s = r.sides.find((x) => x.open)!;
    expect(s.days.filter((d) => d[1] === 3)).toHaveLength(1);
    expect(s.days.filter((d) => d[1] === 9)).toHaveLength(1);
    expect(s.days).toHaveLength(30);
  });

  it("a disabled day is not a candidate", () => {
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 10, disabledDays: [9, 10] } }));
    const s = r.sides.find((x) => x.open)!;
    expect(s.days.map((d) => d[1])).not.toContain(9);
    expect(s.days.map((d) => d[1])).not.toContain(10);
    expect(s.days).toHaveLength(29);
  });

  it("finds exactly one single-month step each way, and neither is a year jump", () => {
    const r = pickerCensus(page({ open: "start" }));
    const s = r.sides.find((x) => x.open)!;
    expect(s.prev).toHaveLength(1);
    expect(s.next).toHaveLength(1);
    expect(s.prev[0]).not.toBe(s.next[0]);
  });

  it("a calendar with only year jumps offers no month step — a stop, not a jump of twelve", () => {
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 10, yearJumpsOnly: true } }));
    const s = r.sides.find((x) => x.dateIndex === 0)!;
    expect(s.prev).toEqual([]);
    expect(s.next).toEqual([]);
  });

  it("two controls that both look like one step back are reported as two", () => {
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 10, twoPrev: true } }));
    const s = r.sides.find((x) => x.dateIndex === 0)!;
    expect(s.prev).toHaveLength(2);
  });

  it("no YYYY.MM title means the month on show is unknown — and unknown is not open", () => {
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 10, noTitle: true } }));
    const s = r.sides.find((x) => x.dateIndex === 0)!;
    expect(s.year).toBeNull();
    expect(s.open).toBe(false);
  });

  it("fewer than seven weekday headings is not a day view", () => {
    // Three facts have to agree that this is a day view. This is the one that caught the first probe's
    // mistake: it had a grid of cells and no weekday row, because it was looking at a month-view template.
    const r = pickerCensus(page({ open: "start", cal: { year: 2026, month: 10, weekdays: 3 } }));
    const s = r.sides.find((x) => x.dateIndex === 0)!;
    expect(s.weekdays).toBe(3);
    expect(s.open).toBe(false);
  });

  it("a candidate set it cannot query is unreadable, not empty", () => {
    const sandbox = page();
    sandbox.document.querySelectorAll = () => {
      throw new Error("SyntaxError: not a valid selector");
    };
    expect(pickerCensus(sandbox).readable).toBe(false);
  });
});
