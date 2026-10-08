import { describe, expect, it } from "vitest";
import { runInNewContext } from "node:vm";
import {
  NAVER_REVIEW_DATE_INPUT_SELECTOR,
  NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
} from "../../src/aside/naver-review-workflow";
import { buildNaverReviewControlsScript } from "../../src/naver/review-list-observe-inpage";

/**
 * **The test that was missing on 2026-10-08.**
 *
 * The first historical catch-up stopped in its third gate with a `SyntaxError`. The selectors carried
 * Playwright's `:visible:not([disabled])`, and Aside hands a selector straight to `document.querySelectorAll`
 * — which has never heard of `:visible`. Nothing here could have told us: every unit test passed a fake
 * locator whose `count()` returned a number, so the only thing that could fail was the live run.
 *
 * <p>What the engine actually accepts was then measured against the installed Aside CLI on `about:blank`:
 * `input[type="date"]:visible` → `SyntaxError: not a valid selector`; `input[class*="date" i]` → fine;
 * `button:has-text("x")` → fine (Aside rewrites that one itself). The rule below is that measurement written
 * as a grammar: a comma-separated list of plain type and attribute selectors, and nothing else. It refuses
 * exactly what the browser refused.
 */

/** A plain CSS candidate selector: `tag`, `tag[attr]`, `tag[attr="v"]`, `tag[attr*="v" i]` — joined by commas. */
const PLAIN_CSS = /^[a-z]+(\[[a-z-]+([*^$~|]?=("[^"]*"|'[^']*')( i)?)?\])*(\s*,\s*[a-z]+(\[[a-z-]+([*^$~|]?=("[^"]*"|'[^']*')( i)?)?\])*)*$/;

describe("the selectors are selectors — the grammar the engine showed us it accepts", () => {
  it("both candidate sets are a plain-CSS list and nothing more", () => {
    for (const selector of [NAVER_REVIEW_DATE_INPUT_SELECTOR, NAVER_REVIEW_QUERY_CONTROL_SELECTOR]) {
      expect(selector, selector).toMatch(PLAIN_CSS);
    }
  });

  it("the rule refuses the exact string that stopped the live run", () => {
    // Not a hypothetical: this is what shipped, and this is what `querySelectorAll` threw on.
    expect('input[type="date"]:visible:not([disabled])').not.toMatch(PLAIN_CSS);
    expect('button:visible:has-text("조회")').not.toMatch(PLAIN_CSS);
    // And it still accepts what the probe confirmed is fine.
    expect('input[class*="date" i]').toMatch(PLAIN_CSS);
    expect('button, a, input[type="button"], input[type="submit"]').toMatch(PLAIN_CSS);
  });

  it("names no Playwright-only engine syntax", () => {
    for (const selector of [NAVER_REVIEW_DATE_INPUT_SELECTOR, NAVER_REVIEW_QUERY_CONTROL_SELECTOR]) {
      for (const token of [":visible", ":hidden", ":has-text(", ":text(", ":text-is(", ":near(",
        ":nth-match(", ">> ", "xpath=", "text=", "css="]) {
        expect(selector, `${selector} contains ${token}`).not.toContain(token);
      }
    }
  });

  it("decides nothing — no visibility or enabled judgement lives in a selector", () => {
    // That judgement is the page predicate's, in one place. Two judges is how the live stop happened, and the
    // copy that ran first was the one with no test behind it.
    for (const selector of [NAVER_REVIEW_DATE_INPUT_SELECTOR, NAVER_REVIEW_QUERY_CONTROL_SELECTOR]) {
      for (const judgement of ["disabled", "visible", "display", "aria-", "readonly"]) {
        expect(selector, `${selector} judges ${judgement}`).not.toContain(judgement);
      }
    }
  });
});

/**
 * The predicate, run as the page runs it.
 *
 * A described document rather than a real one — the collector has no jsdom, and the same `vm` + fake-page
 * shape is how `export-runtime` has been tested since it shipped. What is being checked here is the logic that
 * decides a control: the candidate set it walks, what it rejects, that it answers in INDICES into that set,
 * and that a set it cannot query is `null` rather than empty.
 */
function pageWith(elements: Partial<{
  tag: string;
  type: string;
  className: string;
  text: string;
  value: string;
  disabled: boolean;
  display: string;
  width: number;
  /** Which <form> this element sits in, by name. */
  form: string;
}>[], opts: { throwOn?: string; dateForm?: string | null } = {}) {
  // One node per named form, shared by identity — which is how the predicate compares them.
  const forms: Record<string, { tagName: string; parentElement: null }> = {};
  const formNode = (name?: string) => {
    if (!name) return null;
    forms[name] ??= { tagName: "FORM", parentElement: null };
    return forms[name];
  };
  const dateForm = "dateForm" in opts ? opts.dateForm : "filter";
  const els = elements.map((e) => ({
    tagName: (e.tag ?? "input").toUpperCase(),
    disabled: e.disabled ?? false,
    className: e.className ?? "",
    textContent: e.text ?? "",
    value: e.value,
    getAttribute: (name: string) => (name === "type" ? (e.type ?? null) : null),
    getBoundingClientRect: () => ({ width: e.width ?? 100, height: (e.width ?? 100) === 0 ? 0 : 20 }),
    __display: e.display ?? "block",
    parentElement: formNode(e.form ?? (isDateish(e) ? (dateForm ?? undefined) : undefined)),
  }));
  function isDateish(e: { tag?: string; type?: string; className?: string }) {
    return (e.tag ?? "input") === "input"
      && (e.type === "date" || /date|calendar|picker/i.test(e.className ?? ""));
  }
  const isDateCandidate = (el: (typeof els)[number]) =>
    el.tagName === "INPUT"
    && (el.getAttribute("type") === "date" || /date|calendar|picker/i.test(el.className));
  const isQueryCandidate = (el: (typeof els)[number]) =>
    el.tagName === "BUTTON" || el.tagName === "A"
    || (el.tagName === "INPUT" && ["button", "submit"].includes(String(el.getAttribute("type"))));
  return {
    document: {
      querySelectorAll: (selector: string) => {
        if (opts.throwOn && selector.includes(opts.throwOn)) {
          throw new Error("SyntaxError: not a valid selector");
        }
        if (selector.includes(".ag-row")) return [];
        return selector.startsWith("input[type=\"date\"]")
          ? els.filter(isDateCandidate)
          : els.filter(isQueryCandidate);
      },
    },
    window: { getComputedStyle: (el: { __display: string }) => ({ display: el.__display, visibility: "visible", pointerEvents: "auto" }) },
    location: { host: "sell.smartstore.naver.com", hash: "#/review/search" },
    String,
    Number,
    RegExp,
  };
}

function census(sandbox: Record<string, unknown>, dateSelector = NAVER_REVIEW_DATE_INPUT_SELECTOR) {
  const script = buildNaverReviewControlsScript(dateSelector, NAVER_REVIEW_QUERY_CONTROL_SELECTOR);
  return runInNewContext(script, sandbox) as {
    route: boolean; grid: number;
    dateCandidates: number; dateAccepted: number[] | null;
    queryCandidates: number; queryAccepted: number[] | null;
  };
}

describe("the page predicate is the one judge, and it answers in indices", () => {
  it("accepts the two usable date controls out of a wider candidate set, by their position in it", () => {
    const r = census(pageWith([
      { type: "date", className: "dateFrom" },               // 0 — usable
      { type: "date", className: "tpl", display: "none" },   // 1 — invisible
      { className: "calendar-to" },                          // 2 — usable
      { type: "date", className: "d", disabled: true },      // 3 — disabled
    ]));
    expect(r.dateCandidates).toBe(4);
    expect(r.dateAccepted).toEqual([0, 2]);
  });

  it("rejects an invisible, a zero-sized and a disabled control — all three in the predicate, none in CSS", () => {
    const r = census(pageWith([
      { type: "date", className: "a", display: "none" },
      { type: "date", className: "b", width: 0 },
      { type: "date", className: "c", disabled: true },
    ]));
    expect(r.dateAccepted).toEqual([]);
  });

  it("accepts 조회 as a whole label and refuses a word that merely contains it", () => {
    const r = census(pageWith([
      { type: "date", className: "dateFrom", form: "filter" },
      { className: "calendar-to", form: "filter" },
      { tag: "button", text: "검색어 저장" },   // 0 — a different button
      { tag: "a", text: "조회수" },             // 1 — a quantity
      { tag: "button", text: "조회", form: "filter" },          // 2 — this one
      { tag: "input", type: "submit", value: "검색", form: "filter" }, // 3 — and this one
    ], { dateForm: "filter" }));
    expect(r.queryLabelled).toBe(2);
    expect(r.queryAccepted).toEqual([2, 3]);
  });

  it("the 조회 must be in the period's own form — the live page had two, and only one was", () => {
    // Measured 2026-10-08: a 17x24 anchor 「검색하기」 at the far left of the page (the global search) and a
    // 120x40 submit 「검색」 in the same form as the dates. The form is what tells them apart.
    const r = census(pageWith([
      { type: "date", className: "dateFrom", form: "filter" },
      { className: "calendar-to", form: "filter" },
      { tag: "a", text: "검색하기", form: "global" },            // the left-hand icon
      { tag: "button", type: "submit", text: "검색", form: "filter" }, // the filter's own
    ], { dateForm: "filter" }));
    expect(r.queryLabelled).toBe(2);
    expect(r.queryAccepted).toEqual([1]);
    expect(r.dateFormFound).toBe(true);
  });

  it("no period form means no 조회 belongs to it — fail closed, not «take the labelled one»", () => {
    const r = census(pageWith([
      { type: "date", className: "dateFrom" },
      { className: "calendar-to" },
      { tag: "button", type: "submit", text: "검색", form: "filter" },
    ], { dateForm: null }));
    expect(r.dateFormFound).toBe(false);
    expect(r.queryLabelled).toBe(1);
    expect(r.queryAccepted).toEqual([]);
  });

  it("a candidate set it cannot query is null, not zero — «unreadable» and «none» are different answers", () => {
    // The 2026-10-08 shape, simulated: the selector throws. Before this split that became `RUNTIME_FAULT`,
    // the same word a vanished browser gets.
    const r = census(pageWith([], { throwOn: ":visible" }), 'input[type="date"]:visible');
    expect(r.dateAccepted).toBeNull();
    expect(r.dateCandidates).toBe(-1);
    // The query half still answered, which is why they are two fields and not one.
    expect(r.queryAccepted).toEqual([]);
  });
});
