import {
  attentionWorkRow,
  caseWorkRow,
  committedWorkRow,
  followUpWorkRow,
  heldRegistrationRow,
  inquiryWorkRow,
  type HomeWorkRow,
} from "./homeWork";
import { REASON, channelShort, sourceLabel } from "./copy/customerOps";
import { subjectFallback } from "./customerOperations";
import type { CustomerOperationsDecisionRow } from "./customerOperationsTypes";
import type { HomeAttentionReview, InquiryFollowUpRow, InquiryQueueItem, OperatorVocItem } from "./types";

/**
 * <b>The Home's work, read once on the server</b> (2026-11-10, Home FE connection — mockup v5).
 *
 * The Home used to merge five capped reads on the screen and count what it had received. It now asks the server for the
 * whole board (`GET /api/operations/home-board`) and for one page of one view at a time (`GET /api/operations/work`).
 * The server counts, sorts and pages; this file only turns its answer into the words and rows the screen already draws.
 */

/** A count and how far it can be trusted: `count` exact, or null with `atLeast` the rows that were read. */
export interface Tally {
  count: number | null;
  atLeast: number;
}

export type BoardSourceName = "CASES" | "REVIEWS" | "INQUIRIES" | "FOLLOW_UPS";

export interface BoardSource {
  name: BoardSourceName;
  read: boolean;
  complete: boolean;
}

export interface HomeBoardView {
  asOf: string;
  complete: boolean;
  todo: {
    total: Tally;
    safety: Tally;
    approvable: Tally;
    praiseApprovable: Tally;
    needsInfo: Tally;
    needsReply: Tally;
    needsLook: Tally;
    checkAgain: Tally;
    alsoDueToday: Tally;
    todayChecks: Tally;
    /**
     * 답할 일 (홈 CS 현황, 2026-11-13): every row but 다시 확인 — the `answer` view — and its review/inquiry halves,
     * counted by the server from the same rows. Optional: a server older than this field leaves it out.
     */
    answer?: { total: Tally; reviews: Tally; inquiries: Tally };
  };
  waiting: { total: Tally; registration: Tally; action: Tally; customer: Tally };
  past: { total: Tally; lowRatedReviews: Tally };
  sources: BoardSource[];
}

/** Another piece of work on the same customer item — today a check whose day has come (server `HomeWorkRow.Work`). */
export interface RowWork {
  action: "CHECK_AGAIN" | "TOLD_BEHIND";
  workItemId: string | null;
  checkId: string | null;
  waitingOn: string | null;
  nextCheckOn: string | null;
  dueToday: boolean;
  since: string | null;
  note: string | null;
  /** The screen that holds the record — the inquiry's own. */
  to: string;
}

/** The record a server row was read from — exactly one field is set, the one `kind` names. */
export interface RowOrigin {
  kind: "CASE" | "ATTENTION" | "COMMITTED" | "INQUIRY" | "FOLLOW_UP" | "HELD";
  caseRow: CustomerOperationsDecisionRow | null;
  attention: HomeAttentionReview | null;
  committed: { accountId: string; channelCode: string | null; channelNameKo: string | null; item: OperatorVocItem } | null;
  inquiry: InquiryQueueItem | null;
  followUp: InquiryFollowUpRow | null;
}

export interface ServerWorkRow {
  key: string;
  owner: string;
  to: string;
  kind: "CASE" | "REVIEW" | "INQUIRY";
  subject: "INQUIRY" | "REVIEW" | string;
  subjectId: string;
  caseId: string | null;
  workItemId: string | null;
  state: string;
  delegatedState: string | null;
  bucket: WorkBucket;
  past: boolean;
  praise: boolean;
  safety: boolean;
  safetyReading: HomeWorkRow["safetyReading"];
  safetyKind: HomeWorkRow["safetyKind"];
  channelCode: string | null;
  channelNameKo: string | null;
  rating: number | null;
  title: string | null;
  context: string | null;
  since: string | null;
  dueToday: boolean;
  nextCheckOn: string | null;
  also: RowWork[];
  origin: RowOrigin | null;
}

export type WorkBucket = "safety" | "approvable" | "needsInfo" | "needsReply" | "needsLook" | "checkAgain";
/** What `/work?bucket=` takes: the six buckets, and the views across them. */
export type WorkView = "all" | WorkBucket | "todayChecks" | "answer" | "past" | "praise";
export type WorkSort = "importance" | "newest" | "longest";

export interface WorkPage {
  asOf: string;
  bucket: WorkView;
  sort: WorkSort;
  total: Tally;
  rows: ServerWorkRow[];
  nextCursor: string | null;
  sources: BoardSource[];
}

/** The six buckets, in the order the chips stand. Their counts add up to 할 일. */
export const BUCKETS: ReadonlyArray<{ key: WorkBucket; label: string }> = [
  { key: "safety", label: "안전 확인" },
  { key: "approvable", label: "승인 가능" },
  { key: "needsInfo", label: "정보 확인 필요" },
  { key: "needsReply", label: "답변 필요" },
  { key: "needsLook", label: "판단 필요" },
  { key: "checkAgain", label: "다시 확인" },
];

export const SORTS: ReadonlyArray<{ key: WorkSort; label: string }> = [
  { key: "importance", label: "중요도순" },
  { key: "newest", label: "최근 들어온 순" },
  { key: "longest", label: "오래 기다린 순" },
];

const VIEWS = new Set<string>(["all", "todayChecks", "answer", "past", "praise", ...BUCKETS.map((b) => b.key)]);

export function viewOf(value: string | null): WorkView {
  return value && VIEWS.has(value) ? (value as WorkView) : "all";
}

export function sortOf(value: string | null): WorkSort {
  return value === "newest" || value === "longest" ? value : "importance";
}

/**
 * <b>How a count is written</b> — the one rule for every number this board shows. An exact count is the number; a
 * count the server could not confirm is 「N+」 when it saw rows (a floor it vouches for) and 「—」 when it could not
 * even vouch for that. A failed or unconfirmed count is never 「0」.
 */
export function tallyText(t: Tally | null | undefined): string {
  if (!t) return "—";
  if (t.count != null) return t.count.toLocaleString("ko-KR");
  return t.atLeast > 0 ? `${t.atLeast.toLocaleString("ko-KR")}+` : "—";
}

/** 「12건」 · 「3+건」 · 「—」 — the unit only after a figure. */
export function tallyWithUnit(t: Tally | null | undefined): string {
  const text = tallyText(t);
  return text === "—" ? text : `${text}건`;
}

export function tallyKnown(t: Tally | null | undefined): t is Tally & { count: number } {
  return t != null && t.count != null;
}

/** Whether there is anything to show for this count — an exact number above zero, or a floor above zero. */
export function tallyAny(t: Tally | null | undefined): boolean {
  return t != null && (t.count != null ? t.count > 0 : t.atLeast > 0);
}

/**
 * <b>What 할 일 is made of, in one line</b> — the buckets that hold something, then the 「오늘 확인 모두」 view across them
 * when a check rides on another row (2026-11-10). A bucket whose count is unknown is named only when the total is unknown
 * too, so a line never lists 「—」 beside a total that vouches for itself.
 */
export function todoBreakdown(todo: HomeBoardView["todo"]): string {
  const parts = BUCKETS.filter((b) => tallyAny(todo[b.key]) || (!tallyKnown(todo[b.key]) && !tallyKnown(todo.total)))
    .map((b) => `${b.label} ${tallyText(todo[b.key])}`)
    .join(" · ");
  const today = tallyAny(todo.alsoDueToday) ? `오늘 확인 모두 ${tallyWithUnit(todo.todayChecks)}` : "";
  return [parts, today].filter(Boolean).join(" — ");
}

/**
 * <b>A partial read, said as what it does to the seller's list</b> (2026-11-10: no source names, no limits, no
 * system words). One sentence per effect, the strongest first; empty when everything was read whole.
 */
export function partialNotice(sources: readonly BoardSource[]): string[] {
  const by = new Map(sources.map((s) => [s.name, s]));
  const lines: string[] = [];
  const cases = by.get("CASES");
  if (cases && (!cases.read || !cases.complete)) {
    lines.push("이미 처리된 일인지 다 확인하지 못했어요. 목록에 처리된 일이 섞여 보일 수 있어 할 일 수는 비워 두었어요.");
  }
  const missing: string[] = [];
  const partial: string[] = [];
  const noun: Record<Exclude<BoardSourceName, "CASES">, string> = {
    INQUIRIES: "문의",
    REVIEWS: "리뷰",
    FOLLOW_UPS: "다시 확인할 일",
  };
  for (const name of ["INQUIRIES", "REVIEWS", "FOLLOW_UPS"] as const) {
    const s = by.get(name);
    if (!s) continue;
    if (!s.read) missing.push(noun[name]);
    else if (!s.complete) partial.push(noun[name]);
  }
  if (missing.length > 0) {
    lines.push(`${missing.join("·")}를 불러오지 못해서 그 일은 지금 목록에 없어요. 관련된 수는 비워 두었어요.`);
  }
  if (partial.length > 0) {
    lines.push(`${partial.join("·")}가 많아 일부만 불러왔어요. 목록 뒤쪽에 더 있을 수 있어요.`);
  }
  return lines;
}

/**
 * One server row as the row the Home already draws. The row is built by the function that built it from the same record
 * before ({@link attentionWorkRow} and its siblings in `homeWork.ts`), so the words cannot drift between the two reads;
 * the server's identity, its 지난 일 split and the work riding on the row are laid over it.
 */
export function boardRow(r: ServerWorkRow): HomeWorkRow {
  const o = r.origin;
  let base: HomeWorkRow | null = null;
  if (o?.kind === "CASE" && o.caseRow) base = caseWorkRow(o.caseRow);
  else if (o?.kind === "ATTENTION" && o.attention) base = attentionWorkRow(o.attention);
  else if (o?.kind === "COMMITTED" && o.committed && o.committed.item.reviewId) {
    base = committedWorkRow(o.committed, { ...o.committed.item, reviewId: o.committed.item.reviewId }, {
      safety: r.safety,
      worry: r.safetyReading === "WORRY",
      safetyKind: r.safetyKind ?? null,
      delegated: r.delegatedState,
    });
  } else if (o?.kind === "INQUIRY" && o.inquiry) base = inquiryWorkRow(o.inquiry);
  else if (o?.kind === "FOLLOW_UP" && o.followUp) base = followUpWorkRow(o.followUp);
  else if (o?.kind === "HELD" && o.followUp) base = heldRegistrationRow(o.followUp);
  const row = base ?? bareRow(r);
  // A held draft (2026-11-14 review): the record's own builder reads 초안 준비됨 off the draft; the server knows the check
  // before approval holds it, so the row says that instead and points at the reasons on the reply's own screen.
  const held = r.state === "DRAFT_HELD";
  return {
    ...row,
    ...(held ? { state: "DRAFT_HELD" as const, line: HELD_DRAFT_LINE, verb: "확인" } : {}),
    key: r.key,
    owner: r.owner,
    to: row.to || r.to,
    dueToday: r.dueToday || row.dueToday,
    also: r.also.filter((w) => w.dueToday),
    praise: r.praise,
    past: r.past,
  };
}

export const HELD_DRAFT_LINE = "초안이 확정 전 검사에서 멈췄어요. 이유를 보고 고치거나 결정해 주세요";

/** A row from an older server that sent no origin: the server's own facts, nothing guessed. */
function bareRow(r: ServerWorkRow): HomeWorkRow {
  const subject = r.subject === "REVIEW" ? "REVIEW" : "INQUIRY";
  return {
    key: r.key,
    reason: subject === "REVIEW" ? REASON.review : REASON.reply,
    state: (r.state as HomeWorkRow["state"]) ?? "REPLY_NEEDED",
    source: sourceLabel(r.channelCode ?? r.channelNameKo, subject),
    channel: channelShort(r.channelCode ?? r.channelNameKo),
    rating: r.rating,
    title: r.title?.trim() || subjectFallback(subject),
    line: r.context,
    since: r.since,
    to: r.to,
    owner: r.owner,
    caseId: r.caseId,
    verb: "검토",
    kind: r.kind,
    subject,
    subjectId: r.subjectId,
    workItemId: r.workItemId,
    safety: r.safety,
    safetyReading: r.safety ? (r.safetyReading ?? null) : null,
    safetyKind: r.safety ? (r.safetyKind ?? null) : null,
  };
}

/**
 * Where an attached check's 「진행 결과 적기」 goes: the inquiry's own screen, opened on that record. The inquiry screen
 * reads `focus=seller-check` and scrolls to the record; `checkId` lets it say when the record changed in the meantime.
 */
export function checkHref(work: Pick<RowWork, "to" | "checkId">): string {
  const params = new URLSearchParams({ focus: "seller-check" });
  if (work.checkId) params.set("checkId", work.checkId);
  return `${work.to}?${params.toString()}`;
}

/** 「11월 10일」 from the seller's `YYYY-MM-DD`. */
export function dayLabel(day: string | null): string | null {
  if (!day) return null;
  const [, m, d] = day.split("-").map(Number);
  return m && d ? `${m}월 ${d}일` : null;
}
