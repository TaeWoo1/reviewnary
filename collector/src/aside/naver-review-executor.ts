/**
 * **The Runner half of the unattended NAVER Seller Center 리뷰 read** — build the plan, ship the program, take back
 * a reading or a named failure. The NAVER sibling of `coupang-review-executor.ts`.
 *
 * The page scripts are this repository's own, imported verbatim: the reader and sign-in check from
 * `review-list-observe-inpage.ts`, and the range census the guided reply lane has read live since 2026-09-05. The
 * census needs the run's KST civil date, which the executor supplies — the browser reduces dates to day offsets, and
 * only the offsets cross back.
 */
import {
  buildNaverReviewAuthScript,
  buildNaverReviewListReadScript,
  buildNaverReviewControlsScript,
} from "../naver/review-list-observe-inpage";
import { inPageReviewListRange } from "../action-window/reply-submission/review-list-range-inpage";
import { runAsideRepl, type AsideCliOptions } from "./aside-cli";
import {
  asideNaverReviewRuntime,
  type NaverReviewRuntimePlan,
  type NaverReviewRuntimeResult,
} from "./naver-review-runtime";
import {
  asideNaverReviewWindowRuntime,
  type NaverReviewWindowRuntimePlan,
  type NaverWindowRuntimeResult,
} from "./naver-review-window-runtime";
import {
  NAVER_REVIEW_DATE_INPUT_SELECTOR,
  NAVER_REVIEW_READ_WORKFLOW,
  NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
  validateNaverReviewWorkflow,
  type NaverReviewWorkflow,
} from "./naver-review-workflow";

export const NAVER_REVIEW_RUNTIME_PREAMBLE = "const __name = (target, _value) => target;" as const;

/** The run's civil date in Asia/Seoul — the calendar the Seller Center period is drawn in. */
export function kstCivilDate(now: Date): { year: number; month: number; day: number } {
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  return { year: kst.getUTCFullYear(), month: kst.getUTCMonth() + 1, day: kst.getUTCDate() };
}

/** A KST calendar day as `YYYY-MM-DD`, `daysBefore` days before `asOf`. */
export function kstDayString(asOf: Date, daysBefore: number): string {
  const d = kstCivilDate(new Date(asOf.getTime() - daysBefore * 86_400_000));
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.year}-${pad(d.month)}-${pad(d.day)}`;
}

/** Whole KST days between `day` (`YYYY-MM-DD`) and the run's as-of day. Negative means in the future. */
export function kstDaysBeforeDay(day: string, asOf: Date): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(day || "").trim());
  if (!m) return null;
  const today = kstCivilDate(asOf);
  const t = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  if (!Number.isFinite(t)) return null;
  return Math.round((Date.UTC(today.year, today.month - 1, today.day) - t) / 86_400_000);
}

/** The period a historical read was asked for — two KST calendar days, inclusive. */
export interface NaverReviewWindowRequest {
  readonly start: string;
  readonly end: string;
}

/**
 * The plan for a historical window read.
 *
 * <p>The two offsets are computed here, from the same as-of the census will be given, so «what we asked for»
 * and «what the screen shows» are expressed in one unit and compared without a second clock between them.
 */
export function buildNaverReviewWindowRuntimePlan(
  workflow: NaverReviewWorkflow,
  now: Date,
  window: NaverReviewWindowRequest,
): NaverReviewWindowRuntimePlan | null {
  const startDaysBefore = kstDaysBeforeDay(window.start, now);
  const endDaysBefore = kstDaysBeforeDay(window.end, now);
  if (startDaysBefore === null || endDaysBefore === null) return null;
  // A period that ends before it starts, or that reaches into the future, is not a period to go and look at.
  if (endDaysBefore < 0 || startDaysBefore < endDaysBefore || startDaysBefore > 365) return null;
  return {
    entryUrl: workflow.entryUrl,
    // One script, one judge: it walks the same two candidate sets the locators will, and says which of their
    // members are controls a person could use.
    controlsScript: buildNaverReviewControlsScript(NAVER_REVIEW_DATE_INPUT_SELECTOR,
      NAVER_REVIEW_QUERY_CONTROL_SELECTOR),
    authScript: buildNaverReviewAuthScript(),
    readerScript: buildNaverReviewListReadScript(),
    rangeScript: inPageReviewListRange(kstCivilDate(now)),
    dateInputSelector: NAVER_REVIEW_DATE_INPUT_SELECTOR,
    queryControlSelector: NAVER_REVIEW_QUERY_CONTROL_SELECTOR,
    requestedStartValue: window.start,
    requestedEndValue: window.end,
    requestedStartDaysBefore: startDaysBefore,
    requestedEndDaysBefore: endDaysBefore,
    settleTimeoutMs: workflow.settleTimeoutMs,
    pollMs: 1_500,
    searchSettleMs: 2_000,
  };
}

export function buildNaverReviewWindowRuntimeProgram(plan: NaverReviewWindowRuntimePlan): string {
  const fn = asideNaverReviewWindowRuntime.toString();
  return [
    NAVER_REVIEW_RUNTIME_PREAMBLE,
    `const __plan = ${JSON.stringify(plan)};`,
    `const __run = (${fn});`,
    `const __wait = (ms) => new Promise((r) => setTimeout(r, ms));`,
    `const __result = await __run(__plan, { openTab, closeTab, wait: __wait });`,
    `console.log("ASIDE_RESULT " + JSON.stringify(__result));`,
  ].join("\n");
}

const WINDOW_CODES = [
  "AUTH_REQUIRED", "SURFACE_UNEXPECTED", "DATE_CONTROL_CANDIDATES_UNREADABLE", "RANGE_CONTROLS_NOT_FOUND",
  "RANGE_CONTROLS_AMBIGUOUS", "QUERY_CONTROL_NOT_FOUND", "QUERY_CONTROL_AMBIGUOUS", "RANGE_ORDER_UNKNOWN",
  "RANGE_NOT_SETTABLE", "RANGE_MISMATCH", "READ_UNSETTLED", "RUNTIME_FAULT",
] as const;
const WINDOW_STAGES = ["PREPARE", "SURFACE", "AUTH", "CONTROLS", "NAVIGATE", "VERIFY", "READ"] as const;

/** Shape-check the window program's answer. Off-shape is `null` — never a success with a missing half. */
export function parseNaverReviewWindowRuntimeResult(raw: unknown): NaverWindowRuntimeResult | null {
  if (raw === null || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const elapsedMs = typeof r["elapsedMs"] === "number" && Number.isFinite(r["elapsedMs"]) ? r["elapsedMs"] : 0;
  if (r["ok"] === true) {
    if (r["reading"] === undefined || r["range"] === undefined) return null;
    return { ok: true, reading: r["reading"], range: r["range"], elapsedMs };
  }
  if (r["ok"] !== false) return null;
  const code = r["code"];
  const stage = r["stage"];
  if (typeof code !== "string" || !(WINDOW_CODES as readonly string[]).includes(code)) return null;
  if (typeof stage !== "string" || !(WINDOW_STAGES as readonly string[]).includes(stage)) return null;
  return {
    ok: false,
    code: code as (typeof WINDOW_CODES)[number],
    stage: stage as (typeof WINDOW_STAGES)[number],
    candidates: typeof r["candidates"] === "number" ? r["candidates"] : null,
    labelled: typeof r["labelled"] === "number" ? r["labelled"] : null,
    elapsedMs,
  };
}

export function buildNaverReviewRuntimePlan(workflow: NaverReviewWorkflow, now: Date): NaverReviewRuntimePlan {
  return {
    entryUrl: workflow.entryUrl,
    authScript: buildNaverReviewAuthScript(),
    readerScript: buildNaverReviewListReadScript(),
    rangeScript: inPageReviewListRange(kstCivilDate(now)),
    settleTimeoutMs: workflow.settleTimeoutMs,
    pollMs: 1_500,
  };
}

export function buildNaverReviewRuntimeProgram(plan: NaverReviewRuntimePlan): string {
  const fn = asideNaverReviewRuntime.toString();
  return [
    NAVER_REVIEW_RUNTIME_PREAMBLE,
    `const __plan = ${JSON.stringify(plan)};`,
    `const __run = (${fn});`,
    `const __wait = (ms) => new Promise((r) => setTimeout(r, ms));`,
    `const __result = await __run(__plan, { openTab, closeTab, wait: __wait });`,
    `console.log("ASIDE_RESULT " + JSON.stringify(__result));`,
  ].join("\n");
}

const CODES = ["AUTH_REQUIRED", "UNSUPPORTED_STATE", "READ_UNSETTLED", "RUNTIME_FAULT"] as const;
const STAGES = ["PREPARE", "AUTH", "READ", "RANGE"] as const;

/** Shape-check the program's answer. Off-shape is `null` — never a success with a missing half. */
export function parseNaverReviewRuntimeResult(raw: unknown): NaverReviewRuntimeResult | null {
  if (raw === null || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const elapsedMs = typeof r["elapsedMs"] === "number" && Number.isFinite(r["elapsedMs"]) ? r["elapsedMs"] : 0;
  if (r["ok"] === true) {
    if (r["reading"] === undefined || r["range"] === undefined) return null;
    return { ok: true, reading: r["reading"], range: r["range"], elapsedMs };
  }
  if (r["ok"] !== false) return null;
  const code = r["code"];
  const stage = r["stage"];
  if (typeof code !== "string" || !(CODES as readonly string[]).includes(code)) return null;
  if (typeof stage !== "string" || !(STAGES as readonly string[]).includes(stage)) return null;
  const reason = typeof r["reason"] === "string" ? r["reason"] : null;
  return {
    ok: false,
    code: code as (typeof CODES)[number],
    stage: stage as (typeof STAGES)[number],
    reason,
    elapsedMs,
  };
}

export type NaverReviewExecution =
  | { kind: "RESULT"; result: NaverReviewRuntimeResult; llmCalls: 0 }
  | { kind: "UNAVAILABLE"; llmCalls: 0 };

export type NaverReviewWindowExecution =
  | { kind: "RESULT"; result: NaverWindowRuntimeResult; llmCalls: 0 }
  | { kind: "UNAVAILABLE"; llmCalls: 0 }
  /** The period asked for is not one this lane can look at. Nothing was opened. */
  | { kind: "WINDOW_INVALID"; llmCalls: 0 };

export class AsideNaverReviewExecutor {
  private readonly workflow: NaverReviewWorkflow;
  private readonly cli: AsideCliOptions;
  private readonly now: () => Date;

  constructor(deps: { workflow?: NaverReviewWorkflow; cli?: AsideCliOptions; now?: () => Date } = {}) {
    this.workflow = deps.workflow ?? NAVER_REVIEW_READ_WORKFLOW;
    const errors = validateNaverReviewWorkflow(this.workflow);
    if (errors.length > 0) throw new Error(`naver review workflow invalid: ${errors.join(",")}`);
    this.cli = deps.cli ?? {};
    this.now = deps.now ?? (() => new Date());
  }

  asOf(): Date {
    return this.now();
  }

  async execute(): Promise<NaverReviewExecution> {
    const plan = buildNaverReviewRuntimePlan(this.workflow, this.now());
    const run = await runAsideRepl(buildNaverReviewRuntimeProgram(plan), {
      ...this.cli,
      timeoutMs: this.workflow.settleTimeoutMs + 30_000,
    });
    if (run.kind === "NO_RESULT") return { kind: "UNAVAILABLE", llmCalls: 0 };
    const parsed = parseNaverReviewRuntimeResult(run.result);
    if (parsed === null) {
      return { kind: "RESULT", result: { ok: false, code: "RUNTIME_FAULT", stage: "READ", reason: null, elapsedMs: run.elapsedMs }, llmCalls: 0 };
    }
    return { kind: "RESULT", result: parsed, llmCalls: 0 };
  }

  /**
   * One bounded historical window — the same store, the same list, a period the seller is not looking at.
   *
   * <p>A separate program from {@link execute}, because the two have different permissions: that one touches
   * nothing, this one may put a date in two fields and press 조회. Keeping them apart is what lets the first
   * stay checkable by reading one file.
   */
  async executeWindow(window: NaverReviewWindowRequest): Promise<NaverReviewWindowExecution> {
    const plan = buildNaverReviewWindowRuntimePlan(this.workflow, this.now(), window);
    if (plan === null) return { kind: "WINDOW_INVALID", llmCalls: 0 };
    const run = await runAsideRepl(buildNaverReviewWindowRuntimeProgram(plan), {
      ...this.cli,
      timeoutMs: this.workflow.settleTimeoutMs + 30_000,
    });
    if (run.kind === "NO_RESULT") return { kind: "UNAVAILABLE", llmCalls: 0 };
    const parsed = parseNaverReviewWindowRuntimeResult(run.result);
    if (parsed === null) {
      return {
        kind: "RESULT",
        result: { ok: false, code: "RUNTIME_FAULT", stage: "READ", candidates: null, labelled: null, elapsedMs: run.elapsedMs },
        llmCalls: 0,
      };
    }
    return { kind: "RESULT", result: parsed, llmCalls: 0 };
  }
}
