import { isLowRatedReview, isOldBacklog, isPraiseApproval, mergeHomeWork, type HomeWorkRow } from "../lib/homeWork";
import type { HomeBoardView, RowOrigin, ServerWorkRow, Tally, WorkBucket, WorkPage, WorkView } from "../lib/homeBoard";
import type { CustomerOperationsHome } from "../lib/customerOperationsTypes";
import type { InquiryFollowUps, InquiryQueueResponse, OperationsHome, ReviewWorkView } from "../lib/types";

/**
 * <b>A stand-in for the Home's two server reads, built from the same fixtures the screen tests already use</b>
 * (2026-11-10, Home FE connection).
 *
 * The real server reads the four sources whole, dedupes, buckets, sorts and pages (`HomeBoardRulesTest`,
 * `HomeBoardPostgresProofIT` prove that). These tests are about what the SCREEN does with the answer, so the answer is
 * produced here from the fixtures: the screen merge decides the rows and their order (the server's rules are the same
 * rules), each row carries the record it came from, and the counts are tallied from the rows.
 */
export interface FakeSources {
  co?: CustomerOperationsHome | null;
  ops?: OperationsHome | null;
  queue?: InquiryQueueResponse | null;
  reviewWork?: ReviewWorkView | null;
  followUps?: InquiryFollowUps | null;
  now: Date;
}

function bucketOf(r: HomeWorkRow, missingInfo: boolean): WorkBucket {
  if (r.safety) return "safety";
  if (r.state === "CHECK_AGAIN" || r.state === "TOLD_BEHIND") return "checkAgain";
  if (r.state === "SELLER_CHECK" || missingInfo) return "needsInfo";
  if (r.state === "DRAFT_READY" || r.state === "AWAITING_APPROVAL" || (r.kind === "REVIEW" && r.state !== "DRAFT_NEEDED" && r.state !== "NEEDS_LOOK")) return "approvable";
  if (r.state === "NEEDS_LOOK" || r.state === "LOOK_INTO") return r.subject === "INQUIRY" ? "needsReply" : "needsLook";
  return "needsReply";
}

export function serverRowsFrom(src: FakeSources): ServerWorkRow[] {
  const merged = mergeHomeWork(src.co ?? null, src.ops ?? null, src.queue ?? null, src.now, src.reviewWork ?? null, src.followUps ?? null);
  return merged.rows.map((r) => {
    const origin: RowOrigin = { kind: "INQUIRY", caseRow: null, attention: null, committed: null, inquiry: null, followUp: null };
    let missingInfo = false;
    if (r.key.startsWith("case:")) {
      const c = src.co?.decisions.rows.find((d) => `case:${d.caseId}` === r.key) ?? null;
      origin.kind = "CASE";
      origin.caseRow = c;
      missingInfo = (c?.missingInformation?.length ?? 0) > 0;
    } else if (r.key.startsWith("review:")) {
      const id = r.subjectId;
      const attention = (src.reviewWork?.attention ?? src.ops?.reviews.rows ?? []).find((a) => a.reviewId === id) ?? null;
      if (attention) {
        origin.kind = "ATTENTION";
        origin.attention = attention;
      } else {
        for (const account of src.reviewWork?.committed ?? []) {
          const item = account.todo.find((t) => t.reviewId === id);
          if (item) {
            origin.kind = "COMMITTED";
            origin.committed = { accountId: account.accountId, channelCode: account.channelCode, channelNameKo: account.channelNameKo, item };
          }
        }
      }
    } else if (r.key.startsWith("inquiry:")) {
      origin.inquiry = src.queue?.content.find((q) => `inquiry:${q.inquiryId}` === r.key) ?? null;
    } else if (r.key.startsWith("follow:")) {
      origin.kind = "FOLLOW_UP";
      origin.followUp = src.followUps?.due.find((f) => `follow:${f.inquiryId}` === r.key) ?? null;
    }
    return {
      key: r.key, owner: r.owner, to: r.to, kind: r.kind, subject: r.subject, subjectId: r.subjectId, caseId: r.caseId,
      workItemId: r.workItemId, state: r.state, delegatedState: null, bucket: bucketOf(r, missingInfo),
      past: isOldBacklog(r, src.now), praise: isPraiseApproval(r), safety: r.safety, safetyReading: r.safetyReading ?? null,
      safetyKind: r.safetyKind ?? null, channelCode: null, channelNameKo: null, rating: r.rating, title: r.title,
      context: r.line, since: r.since, dueToday: r.dueToday === true, nextCheckOn: null, also: [], origin,
    };
  });
}

const exact = (n: number): Tally => ({ count: n, atLeast: n });

export function boardFrom(rows: ServerWorkRow[], over: Partial<HomeBoardView> = {}): HomeBoardView {
  const todo = rows.filter((r) => !r.past);
  const of = (b: WorkBucket) => exact(todo.filter((r) => r.bucket === b).length);
  const also = todo.filter((r) => r.bucket !== "checkAgain" && r.also.some((w) => w.dueToday)).length;
  const past = rows.filter((r) => r.past);
  return {
    asOf: "2026-09-16T05:30:00Z",
    complete: true,
    todo: {
      total: exact(todo.length), safety: of("safety"), approvable: of("approvable"),
      praiseApprovable: exact(todo.filter((r) => r.praise).length), needsInfo: of("needsInfo"), needsReply: of("needsReply"),
      needsLook: of("needsLook"), checkAgain: of("checkAgain"), alsoDueToday: exact(also),
      todayChecks: exact(todo.filter((r) => r.bucket === "checkAgain").length + also),
    },
    waiting: { total: exact(0), registration: exact(0), action: exact(0), customer: exact(0) },
    past: {
      total: exact(past.length),
      lowRatedReviews: exact(past.filter((r) => isLowRatedReview({ kind: r.kind, subject: r.subject, rating: r.rating } as HomeWorkRow)).length),
    },
    sources: [
      { name: "CASES", read: true, complete: true }, { name: "REVIEWS", read: true, complete: true },
      { name: "INQUIRIES", read: true, complete: true }, { name: "FOLLOW_UPS", read: true, complete: true },
    ],
    ...over,
  };
}

function inView(view: WorkView, r: ServerWorkRow): boolean {
  if (view === "past") return r.past;
  if (r.past) return false;
  if (view === "all") return true;
  if (view === "praise") return r.praise;
  if (view === "todayChecks") return r.bucket === "checkAgain" || r.also.some((w) => w.dueToday);
  return r.bucket === view;
}

/** One page of a view; the cursor is the index of the next row, which is all a fake needs to be a position. */
export function pageFrom(rows: ServerWorkRow[], board: HomeBoardView, params: { bucket: WorkView; sort: string; cursor?: string | null; size?: number }): WorkPage {
  const all = rows.filter((r) => inView(params.bucket, r));
  const size = params.size ?? 10;
  const from = params.cursor ? Number(params.cursor) : 0;
  const slice = all.slice(from, from + size);
  return {
    asOf: board.asOf, bucket: params.bucket, sort: params.sort as WorkPage["sort"], total: exact(all.length), rows: slice,
    nextCursor: from + size < all.length ? String(from + size) : null, sources: board.sources,
  };
}

/** Points the two mocked reads at these rows. Returns them so a test can change a row and read again. */
export function serveBoard(
  api: { getHomeBoard: { mockImplementation: (f: () => Promise<HomeBoardView>) => unknown }; getHomeWork: { mockImplementation: (f: (p: { bucket: WorkView; sort: string; cursor?: string | null; size?: number }) => Promise<WorkPage>) => unknown } },
  rows: ServerWorkRow[],
  over: Partial<HomeBoardView> = {},
): { rows: ServerWorkRow[]; board: HomeBoardView } {
  const board = boardFrom(rows, over);
  api.getHomeBoard.mockImplementation(() => Promise.resolve(board));
  api.getHomeWork.mockImplementation((p) => Promise.resolve(pageFrom(rows, board, p)));
  return { rows, board };
}

type Fn = ((...args: never[]) => unknown) | undefined;
async function tryRead<T>(fn: Fn): Promise<{ value: T | null; ok: boolean }> {
  if (typeof fn !== "function") return { value: null, ok: true };
  // The implementation, not the mock: reading a fixture on the fake server's behalf is not a call the screen made.
  const impl = (fn as { getMockImplementation?: () => (() => Promise<T>) | undefined }).getMockImplementation?.();
  try {
    return { value: (await (impl ? impl() : (fn as () => Promise<T>)())) ?? null, ok: true };
  } catch {
    return { value: null, ok: false };
  }
}

/**
 * Serves the two Home reads the way the server does it: at call time, from whatever the test's other mocked reads
 * return — so a test that makes the queue read fail sees the inquiries reported unread, exactly as a failed source is.
 */
/** A fixture read through its mock's implementation — absent or failed is null, as the server would see it. */
export async function fixture<T>(fn: Fn): Promise<T | null> {
  return (await tryRead<T>(fn)).value;
}

export function serveFromReads(
  api: Record<string, unknown> & { getHomeBoard: { mockImplementation: (f: () => Promise<HomeBoardView>) => unknown }; getHomeWork: { mockImplementation: (f: (p: { bucket: WorkView; sort: string; cursor?: string | null; size?: number }) => Promise<WorkPage>) => unknown } },
  base: () =>
    | { co?: CustomerOperationsHome | null; ops?: OperationsHome | null; now: Date }
    | Promise<{ co?: CustomerOperationsHome | null; ops?: OperationsHome | null; now: Date }>,
): void {
  const read = async () => {
    const queue = await tryRead<InquiryQueueResponse>(api.getInquiryQueueStrict as Fn);
    const reviewWork = await tryRead<ReviewWorkView>(api.getReviewWorkStrict as Fn);
    const followUps = await tryRead<InquiryFollowUps>(api.getInquiryFollowUps as Fn);
    const b = await base();
    const rows = serverRowsFrom({ ...b, queue: queue.value, reviewWork: reviewWork.value, followUps: followUps.value });
    const truncated = (queue.value && queue.value.totalElements > queue.value.content.length) || false;
    const sources = [
      { name: "CASES" as const, read: true, complete: true },
      { name: "REVIEWS" as const, read: reviewWork.ok, complete: reviewWork.ok },
      { name: "INQUIRIES" as const, read: queue.ok, complete: queue.ok && !truncated },
      { name: "FOLLOW_UPS" as const, read: followUps.ok, complete: followUps.ok },
    ];
    const complete = sources.every((s) => s.read && s.complete);
    const board = boardFrom(rows, { sources, complete });
    if (!complete) {
      // A count that leans on a source not read whole is not exact: the rows seen stand as the floor.
      const floor = (t: Tally): Tally => ({ count: null, atLeast: t.atLeast });
      const t = board.todo;
      board.todo = { ...t, total: floor(t.total), approvable: floor(t.approvable), needsReply: floor(t.needsReply), needsInfo: floor(t.needsInfo), checkAgain: floor(t.checkAgain), todayChecks: floor(t.todayChecks), alsoDueToday: floor(t.alsoDueToday) };
      board.past = { total: floor(board.past.total), lowRatedReviews: floor(board.past.lowRatedReviews) };
    }
    return { rows, board };
  };
  api.getHomeBoard.mockImplementation(async () => (await read()).board);
  api.getHomeWork.mockImplementation(async (p) => {
    const { rows, board } = await read();
    const page = pageFrom(rows, board, p);
    return board.complete ? page : { ...page, total: { count: null, atLeast: page.total.atLeast } };
  });
}
