/**
 * **The helper's side of the unattended NAVER Seller Center 리뷰 read: read the screen's period, hand the rows to
 * the backend for the job this helper holds, report a closed token.**
 *
 * The loopback runner (`fixture-observe-runner.ts`) states what an unattended loop must not be able to do, and this
 * file inherits all of it:
 *
 *  - **it cannot be told where to go.** The recipe NAME resolves here to `NAVER_REVIEW_READ_WORKFLOW`, whose one
 *    route is screened by parsing before a tab opens. No field on the wire can name another.
 *  - **it cannot be told what to do.** No prompt, script or step list is on the wire. The program is built here
 *    from the frozen runtime and this repository's own page scripts: no click, no keystroke, no download, no reply.
 *  - **it cannot prove its own store.** The store is judged by the backend at delivery, against the organisation's
 *    own catalogue. The helper is never handed what it would be compared with.
 *  - **it cannot invent an empty store.** A signed-out browser, a page that did not settle, a period that is not
 *    current, a row this file does not understand, or a delivery that did not land all report a failure token with
 *    no count. The count it does report is the rows it read AND the backend accepted.
 *
 * **Coverage is stated, not assumed.** The range census (live-proven on the reply lane) reduces the screen's period
 * to day offsets. The read is accepted only when the period ends today and every row sits inside it; the period's
 * length travels to the backend as the bound this read covered.
 */
import { createHash } from "node:crypto";
import {
  NAVER_REVIEW_MAX_ROWS,
  type NaverReviewReadReason,
} from "../naver/review-list-observe-inpage";
import { parseRangeCensus } from "../action-window/reply-submission/review-list-range-inpage";
import { log } from "../log";
import {
  AsideNaverReviewExecutor,
  kstCivilDate,
  kstDaysBeforeDay,
  kstDayString,
  type NaverReviewWindowRequest,
} from "./naver-review-executor";
import { NAVER_REVIEW_READ_WORKFLOW, validateNaverReviewWorkflow } from "./naver-review-workflow";

export const NAVER_REVIEW_OBSERVE_RECIPE_ID = "NAVER_REVIEW_OBSERVE_V1" as const;

export type NaverObserveOutcome =
  | "OBSERVED"
  | "SURFACE_UNREADABLE"
  | "EXECUTOR_UNAVAILABLE"
  | "REFUSED"
  | "AUTH_REQUIRED"
  | "STORE_UNRESOLVED";

/**
 * The closed words a stop may be reported by, beyond the outcome.
 *
 * <p>A <b>closed list</b>, not a message. The outcome says what happened to the job; this says where it
 * stopped, and the difference matters to the one person who has to fix it: on 2026-10-08 the product could
 * only say `SURFACE_UNREADABLE`, which covers a missing grid, a refused reading and — as it turned out — a
 * selector that was not a selector. Three different fixes, one word.
 *
 * <p>Nothing outside this list crosses the boundary, so the field cannot become a channel for text from a
 * helper. The backend validates against the same list and the screen maps it to one sentence.
 */
export const NAVER_OBSERVE_FAILURE_CODES = [
  "SURFACE_UNEXPECTED",
  // The seller centre's own host, with its router still working after the stated ceiling. Distinct from
  // SURFACE_UNEXPECTED because the page was ours and from AUTH_REQUIRED because no sign-in was asked for.
  "ROUTE_NOT_READY",
  "DATE_CONTROL_CANDIDATES_UNREADABLE",
  "RANGE_CONTROLS_NOT_FOUND",
  "RANGE_CONTROLS_AMBIGUOUS",
  "QUERY_CONTROL_NOT_FOUND",
  "QUERY_CONTROL_AMBIGUOUS",
  "RANGE_ORDER_UNKNOWN",
  "CALENDAR_OPENER_NOT_FOUND",
  "CALENDAR_OPENER_AMBIGUOUS",
  "PICKER_VIEW_UNREADABLE",
  "MONTH_NAV_NOT_FOUND",
  "MONTH_NAV_AMBIGUOUS",
  "MONTH_NAV_UNVERIFIED",
  "MONTH_NAV_EXHAUSTED",
  "DAY_CELL_NOT_FOUND",
  "DAY_CELL_AMBIGUOUS",
  "RANGE_NOT_SETTABLE",
  "RANGE_MISMATCH",
  "READ_UNSETTLED",
  "READING_REFUSED",
  "WINDOW_INVALID",
  // <b>Why the desk could not run the program — four words, not one.</b>
  //
  // `aside-cli` has separated these four since it shipped, and until 2026-10-09 the runner flattened all of
  // them into EXECUTOR_UNAVAILABLE. The first historical catch-up then stopped with that word and the record
  // could not say whether Aside was unreachable, whether the program threw, or whether the ceiling elapsed —
  // three different things to go and fix, behind one word. Diagnosing it took an hour of probing a chain that
  // turned out to be healthy.
  //
  // `EXECUTOR_UNAVAILABLE` is also the job's OUTCOME, and it was only ever that — which is the mechanical
  // reason the record held no reason at all: the outcome column said «this desk could not run it» and the
  // failure-code column stayed null. It is a code as well now, so the four live in one place.
  "EXECUTOR_UNAVAILABLE",
  "EXECUTOR_TIMEOUT",
  "EXECUTOR_REFUSED",
  "EXECUTOR_FAULT",
  "RUNTIME_FAULT",
] as const;
export type NaverObserveFailureCode = (typeof NAVER_OBSERVE_FAILURE_CODES)[number];

/**
 * The CLI's reason word as this lane's failure code. A closed map over a closed set: a word the CLI does not
 * publish cannot reach a record, and nothing arbitrary passes through.
 */
export function executorFailureCode(reason: string): NaverObserveFailureCode {
  switch (reason) {
    case "TIMEOUT":
      return "EXECUTOR_TIMEOUT";
    case "REFUSED":
      return "EXECUTOR_REFUSED";
    case "FAULT":
      return "EXECUTOR_FAULT";
    default:
      // Including "UNAVAILABLE" itself, and anything unrecognised: «we could not run it» is the honest
      // fallback, and it is the one word that was never wrong before this map existed.
      return "EXECUTOR_UNAVAILABLE";
  }
}

export function asFailureCode(raw: string): NaverObserveFailureCode | null {
  return (NAVER_OBSERVE_FAILURE_CODES as readonly string[]).includes(raw)
    ? (raw as NaverObserveFailureCode)
    : null;
}

export interface NaverObserveResult {
  readonly outcome: NaverObserveOutcome;
  readonly observedCount: number | null;
  readonly contentDigest: string | null;
  /** Where it stopped, as one of the closed words above. `null` on success and when there is nothing to add. */
  readonly failureCode?: NaverObserveFailureCode | null;
}

/** One row exactly as the backend accepts it. Eight named fields; there is no field for the buyer. */
export interface NaverObservedReview {
  readonly reviewId: string;
  readonly createdAt: string;
  readonly rating: number;
  readonly body: string;
  readonly productNo: string;
  readonly productName: string | null;
  readonly answered: boolean;
  readonly attachCount: number;
  /** The attachments' own addresses, when the page reader projected them; absent means «not read». */
  readonly attachments?: readonly { readonly url: string; readonly kind: "IMAGE" | "VIDEO" | "UNKNOWN" }[];
}

export interface NaverDeliveryRequest {
  readonly reviews: readonly NaverObservedReview[];
  readonly windowDays: number;
  /**
   * The same period as two KST calendar dates (`YYYY-MM-DD`), inclusive.
   *
   * <p>`windowDays` is a length and a length is not a period: the 2026-10-08 read sent `7`, which was true, and
   * the only thing the record could then say was 「7일을 읽었다」 — while the question being asked of it was
   * 「어느 7일」. The backend keeps these two dates beside the read and will not compute them from its own clock.
   */
  readonly windowStart: string;
  readonly windowEnd: string;
  /**
   * The ceiling this one reading was under.
   *
   * <p>The page's own model answers for the whole period, so a count below the ceiling excludes 「더 있을 수
   * 있음」 — and a count AT the ceiling does not, which `rowCount === NAVER_REVIEW_MAX_ROWS` cannot be told apart
   * from a truncated 500 by anything on this side. Sent as the number rather than a verdict: the comparison is
   * the backend's to make, against values it stores and can re-check.
   */
  readonly rowCapacity: number;
  /**
   * **What the screen itself says it is showing, and how it was paged — evidence, not a verdict.**
   *
   * <p>`rowCapacity` used to be the whole story, and it was doing two jobs at once: this recipe's own ceiling,
   * and a standing assumption that the list was showing 500 rows per page. Those are different facts, and a
   * coverage boundary may only move on the one that was actually read. So the list's printed total
   * (「리뷰목록 (총 N개)」) and its chosen page size (「N개씩」) travel as numbers beside the rows; `null` means
   * the screen did not state it clearly, which is itself a reason not to claim the period was read whole.
   *
   * <p>The verdict is the backend's: it holds the coverage boundary, and it can re-check these against what it
   * stored. Nothing here decides that a window was complete.
   */
  readonly labelledTotal: number | null;
  readonly selectedPageSize: number | null;
  /** How the rows were obtained. `MODEL` is the grid's own row model — every row of the period, no scrolling. */
  readonly gridReadMode: string | null;
  /** How many single-month calendar steps this read took to reach its period. Audit only. */
  readonly monthMoves: number;
}

export interface NaverDeliveryResponse {
  readonly identityVerdict: string;
  readonly received: number;
  readonly inserted: number;
  readonly changed: number;
  readonly skipped: number;
  readonly failed: number;
}

/**
 * What this runner needs of an executor. `executeWindow` is optional so a test double, and a deployment with
 * no READ navigation, can still serve the lane that reads the screen as it stands.
 */
export type NaverObserveExecutorLike = Pick<AsideNaverReviewExecutor, "execute" | "asOf">
  & Partial<Pick<AsideNaverReviewExecutor, "executeWindow">>;

export interface NaverObserveDeps {
  /** Hand the reading in for the job this helper holds. `null` = the delivery did not land. */
  readonly deliver: (request: NaverDeliveryRequest) => Promise<NaverDeliveryResponse | null>;
  /**
   * The period this job was asked to read, when it was asked for one.
   *
   * <p>Absent means what it has always meant: open the route and read whatever period the screen is showing.
   * Present means a historical window — a different program, with the READ navigation this one does not have.
   */
  readonly window?: NaverReviewWindowRequest;
  readonly executor?: NaverObserveExecutorLike;
  readonly asideCli?: string;
  readonly asideAccount?: string;
}

const REVIEW_ID = /^\d{10}$/;
const PRODUCT_NO = /^\d{1,20}$/;
const MAX_BODY = 5000;

/** SHA-256 of the review ids read, sorted. Ids only — never a body, a product or a date. */
export function digestOfReviewIds(reviews: readonly NaverObservedReview[]): string {
  return createHash("sha256").update(reviews.map((r) => r.reviewId).sort().join("\n"), "utf8").digest("hex");
}

export { kstDayString } from "./naver-review-executor";

/** Whole days between a row's KST calendar date and the run's KST calendar date, or null if unparseable. */
export function kstDaysBefore(createdAt: string, asOf: Date): number | null {
  const t = Date.parse(createdAt);
  if (!Number.isFinite(t)) return null;
  const row = kstCivilDate(new Date(t));
  const today = kstCivilDate(asOf);
  return Math.round(
    (Date.UTC(today.year, today.month - 1, today.day) - Date.UTC(row.year, row.month - 1, row.day)) / 86_400_000,
  );
}

/**
 * The page is untrusted input. Every row must be one this file fully understands, or none is kept: half a page
 * delivered is indistinguishable from a whole one once it is stored.
 */
export interface NaverReadingEvidence {
  /** The total the list printed for this period, or null when the screen did not state it unambiguously. */
  readonly labelledTotal: number | null;
  /** The page size the list was set to, or null when the chosen value could not be told from the options. */
  readonly selectedPageSize: number | null;
  /** How the rows were obtained — `MODEL` is the grid's own row model, which answers for the whole period. */
  readonly gridReadMode: string | null;
}

export function sanitizeNaverReading(raw: unknown):
  { ok: true; reviews: NaverObservedReview[]; evidence: NaverReadingEvidence } | { ok: false; reason: string } {
  if (raw === null || typeof raw !== "object") return { ok: false, reason: "READING_SHAPE" };
  const r = raw as Record<string, unknown>;
  const reason = r["reason"] as NaverReviewReadReason | undefined;
  if (reason !== "OK") return { ok: false, reason: typeof reason === "string" ? reason : "READING_SHAPE" };
  const rows = r["rows"];
  if (!Array.isArray(rows) || rows.length > NAVER_REVIEW_MAX_ROWS) return { ok: false, reason: "READING_SHAPE" };
  if (typeof r["rowCount"] !== "number" || r["rowCount"] !== rows.length) return { ok: false, reason: "ROW_COUNT_DISAGREES" };
  const seen = new Set<string>();
  const out: NaverObservedReview[] = [];
  for (const row of rows) {
    if (row === null || typeof row !== "object") return { ok: false, reason: "ROW_SHAPE" };
    const x = row as Record<string, unknown>;
    const reviewId = x["reviewId"];
    const createdAt = x["createdAt"];
    const rating = x["rating"];
    const body = x["body"];
    const productNo = x["productNo"];
    const productName = x["productName"];
    const answered = x["answered"];
    const attachCount = x["attachCount"];
    const attachments = x["attachments"];
    if (typeof reviewId !== "string" || !REVIEW_ID.test(reviewId) || seen.has(reviewId)) return { ok: false, reason: "ROW_ID" };
    if (typeof createdAt !== "string" || !Number.isFinite(Date.parse(createdAt))) return { ok: false, reason: "ROW_DATE" };
    if (typeof rating !== "number" || !Number.isInteger(rating) || rating < 1 || rating > 5) return { ok: false, reason: "ROW_RATING" };
    if (typeof body !== "string" || body.length > MAX_BODY) return { ok: false, reason: "ROW_BODY" };
    if (typeof productNo !== "string" || !PRODUCT_NO.test(productNo)) return { ok: false, reason: "ROW_PRODUCT" };
    if (productName !== null && typeof productName !== "string") return { ok: false, reason: "ROW_PRODUCT" };
    if (typeof answered !== "boolean") return { ok: false, reason: "ROW_ANSWERED" };
    if (typeof attachCount !== "number" || !Number.isInteger(attachCount) || attachCount < 0 || attachCount > 50) {
      return { ok: false, reason: "ROW_ATTACH" };
    }
    let projected: { url: string; kind: "IMAGE" | "VIDEO" | "UNKNOWN" }[] | undefined;
    if (attachments !== null && attachments !== undefined) {
      if (!Array.isArray(attachments) || attachments.length !== attachCount) return { ok: false, reason: "ROW_ATTACH" };
      projected = [];
      for (const a of attachments) {
        const url = a && typeof a === "object" ? (a as Record<string, unknown>)["url"] : undefined;
        const kind = a && typeof a === "object" ? (a as Record<string, unknown>)["kind"] : undefined;
        if (typeof url !== "string" || !url.startsWith("https://") || url.length > 2048) {
          return { ok: false, reason: "ROW_ATTACH" };
        }
        if (kind !== "IMAGE" && kind !== "VIDEO" && kind !== "UNKNOWN") return { ok: false, reason: "ROW_ATTACH" };
        projected.push({ url, kind });
      }
    }
    seen.add(reviewId);
    out.push({
      reviewId,
      createdAt,
      rating,
      body,
      productNo,
      productName: typeof productName === "string" ? productName.slice(0, 255) : null,
      answered,
      attachCount,
      ...(projected ? { attachments: projected } : {}),
    });
  }
  // The screen's own two numbers travel with the rows. Anything but a finite non-negative integer is `null`:
  // «the screen did not say» and «the screen said 0» are different facts and must stay different.
  const count = (v: unknown): number | null =>
    typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 10_000_000 ? v : null;
  return {
    ok: true,
    reviews: out,
    evidence: {
      labelledTotal: count(r["labelledTotal"]),
      selectedPageSize: count(r["selectedPageSize"]),
      gridReadMode: typeof r["gridReadMode"] === "string" && r["gridReadMode"].length <= 16
        ? r["gridReadMode"] : null,
    },
  };
}

/**
 * Run one unattended NAVER review observation. Never throws — an unattended loop that can throw is one that stops.
 */
export async function runNaverReviewObservation(deps: NaverObserveDeps): Promise<NaverObserveResult> {
  const none = (outcome: NaverObserveOutcome, failureCode: NaverObserveFailureCode | null = null):
    NaverObserveResult => ({ outcome, observedCount: null, contentDigest: null, failureCode });
  if (validateNaverReviewWorkflow(NAVER_REVIEW_READ_WORKFLOW).length > 0) {
    log("aside_naver_review_refused", { reason: "WORKFLOW_INVALID" }, "warn");
    return none("REFUSED");
  }
  let executor: NaverObserveExecutorLike;
  try {
    executor = deps.executor
      ?? new AsideNaverReviewExecutor({
        cli: {
          ...(deps.asideCli ? { command: deps.asideCli } : {}),
          ...(deps.asideAccount ? { account: deps.asideAccount } : {}),
        },
      });
  } catch {
    return none("REFUSED");
  }

  const asked = deps.window ?? null;
  // The period as one readable token: the log line is for an operator, and `[object]` is the serializer
  // giving up on a fact that fits in twenty-one characters.
  const askedFor = asked === null ? null : `${asked.start}~${asked.end}`;
  type Settled =
    | { kind: "RESULT"; result: Record<string, unknown> }
    | { kind: "UNAVAILABLE"; stop: { reason: string; exitCode: number | null; signal: string | null } }
    | { kind: "WINDOW_INVALID" };
  let execution: Settled;
  try {
    if (asked === null) {
      execution = (await executor.execute()) as Settled;
    } else if (!executor.executeWindow) {
      // Asked for a period by a backend that has the lane, served by a helper that does not. Honest and
      // bounded: nothing is opened, and the job settles as «this desk could not do it». No process ran, so
      // there is no exit code to name.
      execution = { kind: "UNAVAILABLE", stop: { reason: "UNAVAILABLE", exitCode: null, signal: null } };
    } else {
      execution = (await executor.executeWindow(asked)) as Settled;
    }
  } catch {
    // `runAsideRepl` never throws by contract, so reaching here means something above it did. That is a
    // fault on this side, which is a different thing from a desk that could not be reached.
    log("aside_naver_review_read", { ok: false, code: "EXECUTOR_FAULT", window: askedFor, llmCalls: 0 });
    return none("SURFACE_UNREADABLE", "EXECUTOR_FAULT");
  }
  if (execution.kind === "UNAVAILABLE") {
    const code = executorFailureCode(execution.stop.reason);
    // The reason word, and how the process ended. Two small values beside it, and no page text: the CLI's
    // stdout and stderr never left `aside-cli`.
    log("aside_naver_review_read", {
      ok: false, code, window: askedFor,
      exitCode: execution.stop.exitCode,
      signal: execution.stop.signal,
      llmCalls: 0,
    });
    return none("EXECUTOR_UNAVAILABLE", code);
  }
  if (execution.kind !== "RESULT") {
    // A period this lane cannot look at. Refused rather than reduced to something nearby, because a window
    // quietly narrowed is a coverage claim about days nobody read.
    log("aside_naver_review_read", { ok: false, code: "WINDOW_INVALID", window: askedFor, llmCalls: 0 });
    return none("REFUSED", "WINDOW_INVALID");
  }
  const result = execution.result;
  if (result["ok"] !== true) {
    const code = String(result["code"] ?? "RUNTIME_FAULT");
    const outcome: NaverObserveOutcome = code === "AUTH_REQUIRED" ? "AUTH_REQUIRED" : "SURFACE_UNREADABLE";
    log("aside_naver_review_read", {
      ok: false, code, stage: result["stage"] ?? null,
      reason: result["reason"] ?? null, candidates: result["candidates"] ?? null,
      labelled: result["labelled"] ?? null,
      window: askedFor, llmCalls: 0, outcome,
    });
    return none(outcome, asFailureCode(code));
  }

  const reading = sanitizeNaverReading(result["reading"]);
  if (!reading.ok) {
    log("aside_naver_review_read", { ok: false, code: "READING_REFUSED", reason: reading.reason, llmCalls: 0 });
    return none("SURFACE_UNREADABLE", "READING_REFUSED");
  }
  const asOf = executor.asOf();
  // <b>The period, as the screen states it.</b> Both ends read, and — when a period was asked for — the ends
  // the request named, to the day. A read that filled two fields and did not check is reporting an intention;
  // the census is the only thing here that has seen the screen.
  const range = parseRangeCensus(result["range"]);
  const wantedEnd = asked === null ? 0 : (kstDaysBeforeDay(asked.end, asOf) ?? -1);
  const wantedStart = asked === null ? null : (kstDaysBeforeDay(asked.start, asOf) ?? -1);
  if (
    range.valuesParsed < 2
    || range.endDaysBefore !== wantedEnd
    || range.startDaysBefore < 0
    || range.startDaysBefore > 365
    || (wantedStart !== null && range.startDaysBefore !== wantedStart)
  ) {
    log("aside_naver_review_read", {
      ok: false, code: asked === null ? "RANGE_NOT_CURRENT" : "RANGE_MISMATCH",
      valuesParsed: range.valuesParsed,
      startDaysBefore: range.startDaysBefore, endDaysBefore: range.endDaysBefore,
      wantedStartDaysBefore: wantedStart, wantedEndDaysBefore: wantedEnd, llmCalls: 0,
    });
    return none("SURFACE_UNREADABLE", "RANGE_MISMATCH");
  }
  // Rows outside the period the screen says it is showing. For a historical window that means on BOTH sides:
  // a row newer than the window's end is as much a sign of the wrong screen as one older than its start.
  const outside = reading.reviews.filter((r) => {
    const d = kstDaysBefore(r.createdAt, asOf);
    return d === null || d < range.endDaysBefore || d > range.startDaysBefore;
  }).length;
  if (outside > 0) {
    log("aside_naver_review_read", { ok: false, code: "ROWS_OUTSIDE_PERIOD", outside, llmCalls: 0 });
    return none("SURFACE_UNREADABLE");
  }
  // The period that was verified on screen, as days. Taken from the census's own offsets rather than from the
  // request, so what is recorded is what was seen — the two agree by the gate above, and if they ever stop
  // agreeing the recorded fact should be the screen's.
  const windowDays = range.startDaysBefore - range.endDaysBefore + 1;
  const windowEnd = kstDayString(asOf, range.endDaysBefore);
  const windowStart = kstDayString(asOf, range.startDaysBefore);

  let delivered: NaverDeliveryResponse | null;
  try {
    delivered = await deps.deliver({
      reviews: reading.reviews,
      windowDays,
      windowStart,
      windowEnd,
      rowCapacity: NAVER_REVIEW_MAX_ROWS,
      labelledTotal: reading.evidence.labelledTotal,
      selectedPageSize: reading.evidence.selectedPageSize,
      gridReadMode: reading.evidence.gridReadMode,
      monthMoves: typeof result["monthMoves"] === "number" ? result["monthMoves"] : 0,
    });
  } catch {
    delivered = null;
  }
  if (delivered === null) {
    log("aside_naver_review_delivery", { ok: false, reason: "NOT_DELIVERED" }, "warn");
    return none("EXECUTOR_UNAVAILABLE");
  }
  if (delivered.identityVerdict !== "MATCH") {
    log("aside_naver_review_delivery", { ok: false, identity: delivered.identityVerdict, received: delivered.received });
    return none("STORE_UNRESOLVED");
  }
  log("aside_naver_review_read", {
    ok: true,
    rows: reading.reviews.length,
    windowDays,
    windowStart,
    windowEnd,
    requested: askedFor,
    saturated: reading.reviews.length >= NAVER_REVIEW_MAX_ROWS,
    labelledTotal: reading.evidence.labelledTotal,
    selectedPageSize: reading.evidence.selectedPageSize,
    gridReadMode: reading.evidence.gridReadMode,
    monthMoves: typeof result["monthMoves"] === "number" ? result["monthMoves"] : 0,
    identity: delivered.identityVerdict,
    received: delivered.received,
    inserted: delivered.inserted,
    changed: delivered.changed,
    skipped: delivered.skipped,
    failed: delivered.failed,
    llmCalls: 0,
    durationMs: result["elapsedMs"] ?? 0,
  });
  return {
    outcome: "OBSERVED",
    observedCount: reading.reviews.length,
    contentDigest: digestOfReviewIds(reading.reviews),
  };
}
