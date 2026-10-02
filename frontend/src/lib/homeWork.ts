import { REASON, channelShort, elapsedSource, reasonOfCase, sourceLabel, waitSince, type Reason } from "./copy/customerOps";
import { onlySharedWord } from "./sharedWord";
import type { WorkStateKey } from "./workState";
import { subjectFallback } from "./customerOperations";
import { isOldBacklog as isOldInquiryBacklog } from "./inquiryWorkspace";
import type { CustomerOperationsDecisionRow, CustomerOperationsHome } from "./customerOperationsTypes";
import type { InquiryQueueResponse, OperationsHome, ReviewWorkView } from "./types";

/**
 * <b>「확인 필요」 on the Home — one list, not three</b> (Customer Operations v3.1).
 *
 * Three reads each know part of what is waiting for the seller: the cases 고객 운영 관리 opened and left for a
 * decision, the reviews the triage marked 「지금 확인」, and the inquiry work queue. The Home used to draw them as
 * three areas, so the same inquiry could appear twice under two names.
 *
 * <b>Identity is the screen that owns the thing.</b> A case row's `to`, a review row's `/reviews/reply/{id}` and a
 * queue row's `/inquiries/{id}` are the same string when they are the same customer item — the backend builds all
 * three from the subject id — so that string is the dedupe key. A case wins over the raw item it is about (it carries
 * the investigation), and anything a case already settled (`handled`) is not re-offered as work.
 *
 * <b>Counts are what was drawn.</b> When a read failed its rows are simply absent, and when a read returned fewer rows
 * than it knows exist the list says so (`truncated`) instead of passing its length off as the total.
 */
export interface HomeWorkRow {
  key: string;
  reason: Reason;
  /**
   * <b>「내가 뭘 해야 하나」 — the row's leading word</b> (product-owner decision, 2026-09-30).
   *
   * <p>Taken from {@link WORK_STATE}, which is already this product's one table of work words, and set
   * from the fact that proves it — never inferred, which is that table's own rule. Before this, the row's
   * first element was {@link reason}'s category badge, so 확인할 일 — the list a seller opens every
   * morning — told them what each item WAS and not what to DO with it, while every other queue in the
   * product (문의 · 리뷰 · 리포트, all on `WorkItem`) led with the state word
   * (`docs/ui/reviewnary_ui_system_audit_v1.md` §3).
   *
   * <p>It also unpicks a slot that was carrying two axes. `REASON` holds real categories (교환·환불 ·
   * 정보 부족 · 리뷰 · 판단 보류) AND three words that are states wearing a category badge (답변 필요 ·
   * 승인 대기 · 초안 필요). Those three now come from here, and the badge keeps only what is genuinely a
   * category — which is why the row drops the badge when it would repeat this word.
   */
  state: WorkStateKey;
  source: string;
  title: string;
  line: string | null;
  since: string | null;
  /** Where the row opens: the case screen for a case, the owning screen otherwise. */
  to: string;
  /**
   * The screen that owns the customer item — the dedupe key this list is built on, kept on the row.
   *
   * Deliberately not the same field as `to`: a case OPENS its own case screen while being ABOUT an inquiry, so the
   * two differ exactly where it matters. It is carried because another section of the same Home has to be able to
   * ask 「is this already in 확인 필요?」, and the only honest way to ask is with the key this list deduped by.
   */
  owner: string;
  caseId: string | null;
  verb: string;
  /**
   * What the row is, so the master-detail pane can draw it in place (UI/UX v2 Phase 1). Carried, never derived from
   * `to`: parsing our own URLs back into ids is the kind of second source that drifts.
   */
  kind: "CASE" | "REVIEW" | "INQUIRY";
  /**
   * What the row is ABOUT — a case's stored `subjectKind`, or the item itself. Carried for the 확인할 일 filter
   * (UI/UX v2 Phase 4), which must not parse `owner` back into a noun.
   */
  subject: "INQUIRY" | "REVIEW";
  /** The id the pane opens: the case, the review, or the inquiry. */
  subjectId: string;
  /** For an inquiry row, the work item its response panel is addressed by. */
  workItemId: string | null;
  /**
   * The channel's short name, for a row that has one — <b>carried, never parsed back out of {@link source}</b>,
   * for the same reason {@link rating} is.
   *
   * <p>The list caption composes 「모두 쿠팡 ★1 리뷰」 out of three pieces it was handed. Taking the channel back
   * out of 「쿠팡 리뷰」 would mean splitting a string this file produced on the assumption that the noun is the
   * last token — a parse of our own output, which is the second source of truth this row refuses everywhere else.
   */
  channel: string | null;
  /**
   * The star rating, for a row that has one — <b>carried, never parsed back out of {@link source}</b>.
   *
   * <p>The inbox reading draws it in the row's right-hand column, beside the wait, and `source` is the composed
   * string 「쿠팡 리뷰」 that stands on the left. Splitting 「쿠팡 리뷰 ★1」 apart at render time is the second
   * source of truth this file refuses everywhere else; the number is right here on the view that made the row.
   */
  rating: number | null;
}

export interface HomeWork {
  rows: HomeWorkRow[];
  truncated: boolean;
}

/**
 * One case, as a row of waiting work — <b>the only place a case becomes a row.</b> The Home's briefing and the queue
 * screen both call this, so a case reads the same way in the list the seller is briefed with and the list they work
 * through; a second mapping would be a second opinion about what this case is.
 *
 * <p>An inquiry and a review are not branched on here. What the row says comes from the decision the case carries
 * (recommended action, what is missing, whether a draft stands) — `subjectKind` only chooses the noun in `source`,
 * and the tag it gives a case that named no action type (see `reasonOfCase`).
 *
 * <p>The one line falls through what the case actually knows, most specific first: what it is missing, what an
 * investigation concluded, <b>what the review lane prepared</b>, and — last — the rule's line about why the subject
 * is here at all. `recommendedAction` sits above `reasonNote` because 「이 상품에서 「…」 문제가 3건 확인됐습니다」
 * tells the seller something 「낮은 별점에 내용이 있는 새 리뷰입니다」 does not; below `summary` because a summary is
 * a conclusion and a recommendation is what to do about one.
 */
export function caseWorkRow(row: CustomerOperationsDecisionRow): HomeWorkRow {
  const reason = reasonOfCase(row.recommendedActionType, row.missingInformation, row.subjectKind);
  /*
    <b>The second line is customer context, and nothing else</b> (product-owner decision, 2026-10-01).

    <p>It used to open with 「초안 있음 · 미발송」 and then fall through `missingInformation` →
    `summary` → `recommendedAction` → `reasonNote`. Two things were wrong with that. The draft clause
    restated the lead badge standing 104px to its left — the badge IS `DRAFT_READY` off the same
    field — so the row told a seller the same thing twice before it told them anything about the
    customer. And the fall-through could land on `recommendedAction`/`reasonNote`, which are
    reviewnary's own workflow vocabulary, in the slot the eye reads as 「이 사람이 무슨 말을 했나」.

    <p>`summary` is the case's own account of the subject, which is the one of the four that is about
    the customer. Nothing is lost: what is missing, what we recommend and why the rule fired are all
    on the case screen this row opens, and the state is on the badge that owns it.

    <p><b>And dropped when it is the title again.</b> `title` falls back to `summary` just below, so a
    case whose subject carries no title of its own would draw one sentence as both lines — measured at
    1600×1000, one live row printed 「전선 한가닥 2.5 3c 지름 10mm…」 twice. {@link preview}'s prefix test
    is the same one the inquiry lane uses, for the same reason.
  */
  // Against the title the ROW will draw, not the raw field: `title` falls back to `summary`, so
  // comparing with `row.title` would miss exactly the case this guard exists for.
  const title = row.title?.trim() || row.summary || subjectFallback(row.subjectKind);
  const line = preview(row.summary, title);
  return {
    key: `case:${row.caseId}`,
    reason,
    // `draftPrepared` is the stored fact, which is what `DRAFT_READY` requires — the row's own line
    // already says 초안 있음 off the same field. Without one, a case is something reviewnary opened for
    // the seller to look at, which is exactly what `NEEDS_LOOK` names.
    state: row.draftPrepared ? "DRAFT_READY" : "NEEDS_LOOK",
    source: sourceLabel(row.channelNameKo, row.subjectKind),
    channel: channelShort(row.channelNameKo),
    rating: row.rating ?? null,
    title,
    line,
    /* <b>The customer's clock, not ours</b> (elapsed-time contract, 2026-10-02). This was `openedAt` —
       when reviewnary opened the case — while the pane for the same case read the subject's
       `receivedOn`, so one item carried two elapsed times on one screen. `elapsedSource` is the one
       place that choice is made, and it is made once here because this value is also the lane's sort
       key and `isOldBacklog`'s input. */
    since: elapsedSource(row.receivedOn, row.openedAt),
    to: `/customer-operations/cases/${row.caseId}`,
    owner: row.to,
    caseId: row.caseId,
    verb: reason === REASON.info ? "정보 입력" : "검토",
    kind: "CASE",
    subject: row.subjectKind,
    subjectId: row.caseId,
    workItemId: null,
  };
}

export function mergeHomeWork(
  co: CustomerOperationsHome | null | undefined,
  ops: OperationsHome | null | undefined,
  queue: InquiryQueueResponse | null | undefined,
  now: Date = new Date(),
  /**
   * The review half, whole (UI/UX v2 Phase 3). When present it replaces the Home's three-row slice of undecided
   * 확인 필요 reviews with all of them, and adds the seller's own reply work still before approval — the items the
   * 리뷰 screen's 「내 답변 작업」 used to be the only place for. Absent (a failed read, an older caller), the list is
   * what it was.
   */
  reviewWork?: ReviewWorkView | null,
): HomeWork {
  const byOwner = new Map<string, HomeWorkRow>();
  const settled = new Set<string>((co?.handled.rows ?? []).map((r) => r.to));
  let truncated = false;

  for (const row of co?.decisions.rows ?? []) {
    byOwner.set(row.to, caseWorkRow(row));
  }
  if (co && co.decisions.total > co.decisions.rows.length) truncated = true;

  const attentionRows = reviewWork ? reviewWork.attention : ops?.reviews.rows ?? [];
  for (const row of attentionRows) {
    const owner = `/reviews/reply/${row.reviewId}`;
    if (byOwner.has(owner) || settled.has(owner)) continue;
    byOwner.set(owner, {
      key: `review:${row.reviewId}`,
      reason: REASON.review,
      // The triage tier said 「지금 확인」 and the seller has not decided yet. `NEEDS_LOOK`'s definition
      // is that tier, read and never computed.
      state: "NEEDS_LOOK",
      source: sourceLabel(row.channelCode, "REVIEW"),
      channel: channelShort(row.channelCode),
      rating: row.rating ?? null,
      title: row.quote?.trim() || "본문 없는 리뷰",
      line: row.productName,
      since: row.occurredOn,
      to: owner,
      owner,
      caseId: null,
      verb: "검토",
      kind: "REVIEW",
      subject: "REVIEW",
      subjectId: row.reviewId,
      workItemId: null,
    });
  }

  if (reviewWork && reviewWork.attentionTotal > reviewWork.attention.length) truncated = true;

  // The seller's own reply work before approval. A decided review is not in the undecided list above, so the two
  // never describe one review twice; a case about it still wins, by the same owner key.
  for (const account of reviewWork?.committed ?? []) {
    for (const item of account.todo) {
      if (!item.reviewId) continue;
      const owner = `/reviews/reply/${item.reviewId}`;
      if (byOwner.has(owner) || settled.has(owner)) continue;
      const awaiting = item.replyWorkState === "AWAITING_APPROVAL";
      byOwner.set(owner, {
        key: `review:${item.reviewId}`,
        reason: awaiting ? REASON.approve : REASON.draft,
        // `replyWorkState` is the fact for both: a saved draft with no standing approval, or reply work
        // the seller committed to and has not written anything for yet. These two are deliberately not
        // merged with 답변 필요 — that one is about the customer, these are about the seller's own work.
        state: awaiting ? "AWAITING_APPROVAL" : "DRAFT_NEEDED",
        source: sourceLabel(item.channelCode ?? account.channelCode, "REVIEW"),
        channel: channelShort(item.channelCode ?? account.channelCode),
        rating: item.rating ?? null,
        title: item.safePreview?.trim() || "본문 없는 리뷰",
        /*
          <b>Customer context only</b> (product-owner decision, 2026-10-01).

          <p>This line read 「대응 필요 · 초안 없음 · {상품명}」 — and before that
          「대응 필요로 정함 · 답변 초안 없음」 — under a lead badge already saying 초안 필요 or 승인 대기
          off the same `replyWorkState`. The state is told once, by the badge that owns it; the line
          is the review's product, which is the fact that tells one of these rows from the next.
        */
        line: item.productName,
        since: item.sourceCreatedDate,
        to: owner,
        owner,
        caseId: null,
        verb: "검토",
        kind: "REVIEW",
        subject: "REVIEW",
        subjectId: item.reviewId,
        workItemId: null,
      });
    }
  }

  for (const row of queue?.content ?? []) {
    const owner = `/inquiries/${row.inquiryId}`;
    if (byOwner.has(owner) || settled.has(owner)) continue;
    // Resolved first, because `title` falls back to the snippet: a NAVER product inquiry carries no
    // subject at all, so the body is BOTH lines unless the preview is compared against what the row
    // will actually draw. Measured live at 1600×1000 — 「전선 한가닥 2.5 3c 지름 10mm…」, twice.
    const inquiryTitle = row.title?.trim() || row.snippet?.trim() || subjectFallback("INQUIRY");
    byOwner.set(owner, {
      key: `inquiry:${row.inquiryId}`,
      reason: REASON.reply,
      // `hasDraft` is a draft VERSION, not a phase — the distinction that caught 「초안 준비됨」 being read
      // off a work-item phase, where eight of the demo org's ten rows had no draft at all.
      state: row.hasDraft ? "DRAFT_READY" : "REPLY_NEEDED",
      source: sourceLabel(row.channelCode ?? row.channelNameKo, "INQUIRY"),
      channel: channelShort(row.channelCode ?? row.channelNameKo),
      rating: null,
      title: inquiryTitle,
      /*
        <b>The inquiry's own body, which was never drawn</b> (product-owner decision, 2026-10-01).

        <p>This slot held 「초안 없음」 / 「초안 있음 · 미발송」 — the lead badge's own fact, restated —
        so an inquiry row on 오늘 carried no customer context at all while a review row beside it
        carried its product. `snippet` is the channel's own first lines of the question and has been
        on the wire the whole time (`InquiryQueueItem.snippet`); the queue screen reads it, the Home
        did not.

        <p><b>Dropped when it adds nothing, which is a PREFIX test and not equality.</b> Measured at
        1600×1000 on the live org: one row drew 「전선 한가닥 2.5 3c 지름 10mm…」 as both its title and
        its line, because the two strings are the channel's own long question cut at different
        lengths — different strings, identical on screen. Either being a prefix of the other means the
        second line is the first line again.
      */
      line: preview(row.snippet, inquiryTitle),
      since: row.receivedAt,
      to: owner,
      owner,
      caseId: null,
      verb: "검토",
      kind: "INQUIRY",
      subject: "INQUIRY",
      subjectId: row.inquiryId,
      workItemId: row.workItemId,
    });
  }
  if (queue && queue.totalElements > queue.content.length) truncated = true;

  /*
    <b>No single age key across the lanes</b> (product-owner decision, 2026-10-01).

    <p>This list used to end with one comparator — year-plus backlog last, then longest-waiting
    first across everything — and measured on the live org that produced a Home whose top four rows
    were네이버 reviews written 339 · 334 · 239 · 212 days ago, with this month's inquiry nowhere on
    the screen. Three facts explain it and none of them is a missing cutoff:

    <ul>
      <li>339 days is not backlog. {@link isOldBacklog} is the 문의 screen's own predicate and its
          only boundary is a year, so every one of those rows sat in the RECENT group.</li>
      <li>The review lane already declares an order and this comparator inverted it. The server
          serves undecided 확인 필요 reviews `order by r.receivedAt desc` — newest first
          (`ReviewRepository.findUndecidedByOrgAndTier`).</li>
      <li>A review's `since` is when the customer WROTE it. Sorting it against an inquiry's receipt
          time ranks a review nobody is waiting on above a question somebody is.</li>
    </ul>

    <p>So each lane keeps the order it already has, and they are concatenated rather than merged:

    <ul>
      <li><b>CASE</b> — the server's own lane priority, which is the insertion order above.</li>
      <li><b>REVIEW</b> — tier rank + `receivedAt desc`, i.e. the order the reads arrived in.</li>
      <li><b>INQUIRY</b> — oldest first, which is the SLA reading `/inquiries` has used since
          Operational Workspace UX System v1 (`queueOrder`). It is applied HERE because the queue
          read itself arrives `createdAt desc` (`InquiryQueueService`), so this is the one lane whose
          own order has to be restated rather than preserved.</li>
    </ul>

    <p>The year-plus split stays as the outer grouping — it is `/inquiries`' contract, it is drawn as
    a divider rather than a sort, and nothing here hides or drops a row. What the Home shows out of
    this list is {@link homeBriefRows}, which is a composition and not another order.
  */
  const lane = (kind: HomeWorkRow["kind"]) => {
    const rows = [...byOwner.values()].filter((r) => r.kind === kind);
    return kind === "INQUIRY" ? rows.sort((a, b) => waitSince(a.since) - waitSince(b.since)) : rows;
  };
  const ordered = [...lane("CASE"), ...lane("REVIEW"), ...lane("INQUIRY")];
  const rows = [
    ...ordered.filter((r) => !isOldBacklog(r, now)),
    ...ordered.filter((r) => isOldBacklog(r, now)),
  ];
  return { rows, truncated };
}

/**
 * <b>What the Home draws out of the list: four slots, allotted by lane</b> (product-owner decision,
 * 2026-10-01 — Review 2 / Inquiry 1 / Case 1).
 *
 * <p>Taking the first N of {@link mergeHomeWork}'s order would make the brief whichever lane happens
 * to be longest — on the measured org, four reviews — and the morning's question is 「오늘 무엇을
 * 해야 하는가」, which no single lane answers. The quota is the composition; an empty slot is not held
 * open, it is spent on whatever else is waiting, so a seller with no cases still gets four rows.
 *
 * <p>Inside a lane the order is that lane's own, untouched: this function only chooses how many of
 * each, never which. The leftover pass walks the lanes in quota order (Review → Inquiry → Case), so
 * the same list always produces the same brief.
 *
 * <p>`limit` is the caller's, because the Home's capacity is a function of the layout
 * (`visibleHomeRows`) and not of this rule. A limit smaller than the quota total fills the lanes in
 * quota order and stops — it never drops a lane's first row to honour a later lane's.
 */
export const HOME_LANE_SLOTS: ReadonlyArray<readonly [HomeWorkRow["kind"], number]> = [
  ["REVIEW", 2],
  ["INQUIRY", 1],
  ["CASE", 1],
];

export function homeBriefRows(rows: readonly HomeWorkRow[], limit: number): HomeWorkRow[] {
  if (limit <= 0) return [];
  const taken = new Set<string>();
  const picked: HomeWorkRow[] = [];
  const take = (row: HomeWorkRow) => {
    taken.add(row.key);
    picked.push(row);
  };
  for (const [kind, quota] of HOME_LANE_SLOTS) {
    if (picked.length >= limit) break;
    const room = Math.min(quota, limit - picked.length);
    for (const row of rows.filter((r) => r.kind === kind).slice(0, room)) take(row);
  }
  // Slots no lane could fill. Walked in quota order so the result is a function of the list alone.
  for (const [kind] of HOME_LANE_SLOTS) {
    if (picked.length >= limit) break;
    for (const row of rows.filter((r) => r.kind === kind && !taken.has(r.key))) {
      if (picked.length >= limit) break;
      take(row);
    }
  }
  // The list's own order is what the brief is read in — the quota decided how many, not the sequence.
  return rows.filter((r) => taken.has(r.key)).slice(0, limit);
}

/**
 * Whether this row is year-plus backlog — <b>the 문의 화면's rule, read here rather than restated.</b>
 *
 * <p>Longest-waiting-first is the one urgency criterion this product has, and applied across a whole list it is the
 * wrong shape: measured on this org, the Home's five briefed rows were five Cafe24 questions from 2016 — 「3838일
 * 대기」 — while the case Reviewnary investigated last night, the three reviews it flagged and the inquiry that
 * arrived this month all sat under 「+22」. A briefing whose visible half is a decade old answers 「오늘 무엇을
 * 해야 하는가」 with 「2016년에 놓친 것」.
 *
 * <p>So the criterion is applied INSIDE two groups instead of across them, exactly as `/inquiries` has since
 * Operational Workspace UX System v1 — {@link isOldBacklog} is that screen's own predicate, imported so the two
 * lists cannot disagree about which rows are this morning's work. <b>Nothing is hidden, dropped or reordered
 * away</b>: the old rows keep their place in the same single list, after the recent ones, and the counts above
 * still count all of them.
 *
 * <p>A row with no timestamp is not backlog: absence of a date is not evidence of age.
 */
export function isOldBacklog(row: HomeWorkRow, now: Date): boolean {
  return row.since != null && isOldInquiryBacklog({ receivedAt: row.since }, now);
}

/**
 * The customer's own words for the row's second line, or nothing when they would repeat the first.
 *
 * <p>A prefix test rather than equality: a channel's question reaches us as a title and a snippet cut
 * at different lengths, and two strings that differ only past the truncation are one string to the
 * eye. Either direction counts — a title can be the shorter cut as easily as the snippet can.
 */
function preview(snippet: string | null | undefined, title: string | null | undefined): string | null {
  const body = snippet?.trim();
  if (!body) return null;
  const head = title?.trim();
  if (!head) return body;
  return body.startsWith(head) || head.startsWith(body) ? null : body;
}

/**
 * <b>What every row in this list says identically</b> — the facts that therefore distinguish nothing.
 *
 * <p>`lib/sharedWord.ts` states the rule and why (a word carried by every row is a fact about the LIST). This
 * names the three fields a work row can repeat: the reason, where it came from, and the rating. On the measured
 * org all five rows read 「쿠팡 리뷰 ★1」 beside a 「리뷰」 badge — the same two facts drawn ten times down a list
 * whose content is what the customers wrote, while Linear's equivalent list varies every one of them.
 *
 * <p><b>One function, so the caption and the rows cannot disagree.</b> The heading that says it and the row that
 * drops it ask this of the same population.
 */
export function sharedRowFacts(rows: HomeWorkRow[]): {
  tag: string | null;
  source: string | null;
  rating: number | null;
  channel: string | null;
  subject: string | null;
} {
  const rating = onlySharedWord(rows.map((r) => (r.rating == null ? null : String(r.rating))));
  return {
    tag: onlySharedWord(rows.map((r) => r.reason.tag)),
    source: onlySharedWord(rows.map((r) => r.source)),
    rating: rating === null ? null : Number(rating),
    // The two halves `source` is made of, hoisted by the same rule, so the caption can order them its own way
    // without taking the composed string apart. `source` still decides what the ROWS stop repeating.
    channel: onlySharedWord(rows.map((r) => r.channel)),
    subject: onlySharedWord(rows.map((r) => (r.subject === "INQUIRY" ? "문의" : "리뷰"))),
  };
}

/** 「교환·환불 1」, 「정보 부족 1」… — the reasons of the rows drawn, in a fixed order, zeros left out. */
export function reasonCounts(rows: HomeWorkRow[]): string[] {
  const order: Reason[] = [REASON.exchange, REASON.info, REASON.reply, REASON.review, REASON.approve, REASON.draft, REASON.withheld];
  return order
    .map((reason) => [reason.tag, rows.filter((r) => r.reason.tag === reason.tag).length] as const)
    .filter(([, n]) => n > 0)
    .map(([tag, n]) => `${tag} ${n}`);
}

/**
 * <b>The one bucket a row belongs to</b> (canonical mockup, 2026-10-02).
 *
 * <p>확인할 일's action tabs are a partition, not five independent predicates. They were written as
 * five independent predicates — {@code subject === "INQUIRY"}, {@code subject === "REVIEW" && reason
 * is neither}, {@code reason === approve}, {@code reason === draft} — and they happened to partition
 * only because {@code REASON.approve} and {@code REASON.draft} are set in one loop whose rows are all
 * reviews. Nothing said so, nothing tested it, and a sixth reason assigned to an inquiry row would
 * have put that row in two tabs with no failure anywhere.
 *
 * <p>So the bucket is computed ONCE, here, by a function that returns exactly one key, and every tab
 * is 「is this row's bucket mine」. One row, one primary bucket, structurally — which is what the
 * canonical mockup's tabs claim when they count.
 *
 * <p>The order of the branches is the precedence: the seller's own unfinished reply work
 * (승인할 일 · 초안 필요) is what that row is FOR, and it outranks the noun the row is about.
 */
export function workBucket(row: HomeWorkRow): Exclude<WorkFilterKey, "all"> {
  if (row.reason === REASON.approve) return "approve";
  if (row.reason === REASON.draft) return "draft";
  return row.subject === "INQUIRY" ? "inquiry" : "review";
}

/**
 * <b>확인할 일's action tabs</b> (UI/UX v2 Phase 4; renamed to the canonical mockup's words, 2026-10-02) —
 * views of the one list, never a second list. None of them ranks, and the order inside a tab is the
 * list's own.
 *
 * <p>The words are the mockup's: 답변할 문의 · 확인할 리뷰 · 승인할 일 — what the seller DOES with the
 * bucket, which is the axis a tab row on an operating queue is read along. 초안 필요 keeps its
 * {@link WORK_STATE} word because that is the word its rows already wear in the list's lead column, and
 * it is a tab the mockup does not draw: dropping it would leave four rows reachable from 전체 alone.
 */
export const WORK_FILTERS = [
  { key: "all", label: "전체", test: () => true },
  { key: "inquiry", label: "답변할 문의", test: (r: HomeWorkRow) => workBucket(r) === "inquiry" },
  { key: "review", label: "확인할 리뷰", test: (r: HomeWorkRow) => workBucket(r) === "review" },
  { key: "approve", label: "승인할 일", test: (r: HomeWorkRow) => workBucket(r) === "approve" },
  { key: "draft", label: "초안 필요", test: (r: HomeWorkRow) => workBucket(r) === "draft" },
] as const;

export type WorkFilterKey = (typeof WORK_FILTERS)[number]["key"];

export function workFilterOf(value: string | null): WorkFilterKey {
  return WORK_FILTERS.find((f) => f.key === value)?.key ?? "all";
}
