import { Fragment, useEffect, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { Btn, BtnLink } from "../ui/Btn";
import { Empty } from "../ui/Empty";
import { RepeatedProblemList } from "../home/RepeatedProblemList";
import { MasterDetail, selectionHref, useWideLayout } from "../workspace/MasterDetail";
import { WorkRows, sharedPhrase } from "../workspace/WorkRows";
import { WorkItemPane, workItemFullScreen } from "../workspace/WorkItemPane";
import { IssueDetailPanel } from "../memory/IssueDetailPanel";
import { ReviewCaseView } from "../../pages/app/ReviewReplyTask";
import { PreparedWorkList } from "../home/PreparedWorkList";
import { api } from "../../lib/apiClient";
import { problemLine } from "../../lib/operationsHome";
import { RESPONSIBILITY_NAME, cadenceLabel, dataTypeKo, kstClock } from "../../lib/customerOperations";
import { COPY, autoCheckWhat, channelShort, failureShort, kstLongDate } from "../../lib/copy/customerOps";
import { mergeHomeWork, sharedRowFacts, type HomeWork } from "../../lib/homeWork";
import {
  INFLOW_WORD,
  RECENT_WORD,
  UNANSWERED_WORD,
  freshnessUnproven,
  recentDay,
  todayInflow,
  unansweredNow,
  type InflowFact,
} from "../../lib/homeSummary";
import type { CustomerOperationsHome } from "../../lib/customerOperationsTypes";
import type {
  HomePreparedItem,
  InquiryQueueResponse,
  OperationsHome,
  OperationsMetrics,
  ReviewIssueView,
  ReviewWorkView,
} from "../../lib/types";

/**
 * How many rows the list shows before 「+N」.
 *
 * <b>Seven rows made the Home an abbreviated copy of 확인할 일</b> (product-owner decision, 2026-10-01).
 * The previous number was chosen to fill the column, and filling the column is exactly what was wrong with it:
 * measured at 1600×1000, seven rows ran the list to y=982 against a scroller that ends at ~920, so the seventh
 * was clipped <i>and</i> 실행 대기 (y=1001), 반복 문제 and the channel's own state were all below the fold.
 * A Home whose only visible section is the queue answers one of the five questions it exists to answer.
 *
 * <p>So the list is sized to <b>leave room for the other sections</b>, not to use up what is left. A dense
 * row costs 117px measured, so four of them end at ~733 and the secondary band closes at ~881 with the
 * channel row above the composer. 「+N」 and 「전체 보기」 are unchanged, and the screen that owns the whole
 * queue still holds all of it: nothing is hidden, one screen stopped pretending to be another.
 *
 * <p><b>A row is either whole or absent.</b> That rule is why the narrow case drops one: a row you can read
 * the top of is a row the screen is pretending to show.
 *
 * <p>The <i>visible</i> limit is its own named function of the layout, not a second constant and not a
 * measurement: {@link visibleHomeRows}. It reuses the side preview's 1200 breakpoint rather than inventing
 * one, because that is the width at which this column stops being the whole page.
 */
export const HOME_ROWS = 4;
/**
 * The visible limit below 1200. It is not a second capacity: `HOME_ROWS` is still what 「+N」 counts against
 * and what the queue screen holds.
 */
export const HOME_ROWS_NARROW = 3;
/**
 * How many rows this width can show whole. `wide` is the side preview's own answer ({@link useWideLayout}),
 * so the two never disagree; an environment that cannot measure says narrow, which is the safe direction —
 * it under-fills a wide screen instead of clipping a narrow one.
 */
export function visibleHomeRows(wide: boolean): number {
  return wide ? HOME_ROWS : HOME_ROWS_NARROW;
}
/**
 * <b>What the docked link says, by what it opens</b> (product-owner decision, 2026-09-26).
 *
 * <p>「처리하기」 had to go for a reason this panel made visible: 「처리 방법 미정」 stands about 150px above the
 * button, so one screen used 처리 as a noun for the disposition and as a verb on the control beside it, and the
 * verb read as «press this and it is handled». The review case is where the seller judges — 이 리뷰의 중요도,
 * 처리 방법 and 조치 기록 are its three controls — so that is what its link says. Everything else gets the plain
 * navigational verb, because 판단하기 would be a promise about a screen whose job is to prepare an answer.
 *
 * <p>No behaviour changed and no destination moved: both strings open exactly what they opened before.
 */
const FULL_SCREEN = { review: "전체 화면에서 판단하기", other: "전체 화면에서 열기" } as const;

/** How many queue rows the Home asks for — enough to fold out, bounded. */
export const HOME_QUEUE_SIZE = 50;

/** Whether the Home is drawn as 고객 운영 관리's (v3.1) — the job is open for this org and has something to say. */
export function coHomeApplies(co: CustomerOperationsHome | null | undefined): co is CustomerOperationsHome {
  return Boolean(co && co.available && (co.status !== null || co.eligible));
}

/**
 * <b>Home (Customer Operations v3.1)</b>: a title line, 「자동 확인 → 내 확인 필요」, the one 「확인 필요」 list,
 * 「실행 대기」 — what the seller already decided and has not finished — and 「반복 문제」, the patterns, stated below
 * the work and never added to it. Nothing here decides, sends or resolves; every row opens the screen that does.
 *
 * <p>The three sections are three different questions in the order a morning asks them: 어떻게 할까 · 아까 정한 걸
 * 끝내자 · 무엇이 반복되나. They are never summed and never merged — a decision and its own unfinished follow-up are
 * the same item at two moments, and 「확인 필요」 deduplicates against exactly that.
 *
 * `ops` is the Operations Home read AgentHome already made — it carries the repeated problems too, which is why this
 * Home can name them without a read of its own. The queue is read here because the list needs more rows than the
 * conversation's brief ever did.
 */
export function CustomerOpsHome({
  co,
  ops,
  now = new Date(),
  onChanged,
  metrics,
  sharedQueue,
  sharedReviewWork,
  selection,
}: {
  co: CustomerOperationsHome;
  ops: OperationsHome | null | undefined;
  now?: Date;
  onChanged: () => void;
  /**
   * The overview read the page around this list already made (`getOverviewStrict(7)`). Only the summary
   * line reads it, and only to answer 「오늘 들어온 것」 — a failed read is `null` and says nothing.
   */
  metrics?: OperationsMetrics | null;
  /** The queue read, when the page around this list already made it (the 오늘 workspace needs the same rows). */
  sharedQueue?: { value: InquiryQueueResponse | null | undefined };
  /** The review half of 확인할 일, when the page around this list already read it. */
  sharedReviewWork?: { value: ReviewWorkView | null | undefined };
  /** Master-detail selection. Absent: every row opens its own screen, as on a narrow page. */
  selection?: HomeSelection;
}) {
  const ownQueue = useHomeQueue(sharedQueue === undefined);
  const queue = sharedQueue ? sharedQueue.value : ownQueue;
  const ownReviewWork = useReviewWork(sharedReviewWork === undefined);
  const reviewWork = sharedReviewWork ? sharedReviewWork.value : ownReviewWork;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const work = mergeHomeWork(co, ops, queue, now, reviewWork);
  // The row limit asks the layout, not the caller: `selection` is absent on pages that do not select in
  // place, and defaulting to narrow there would drop a row on a 1440 screen that has the space.
  const roomy = useWideLayout();
  const shown = work.rows.slice(0, visibleHomeRows(roomy));
  const hidden = work.rows.length - shown.length;
  const running = co.status === "ACTIVE" || co.status === "PAUSED";
  const wide = selection?.wide ?? false;
  const search = selection?.search ?? "";
  const awaiting = awaitingRows(ops, work);
  // What every row in the WHOLE list says identically — over `work.rows`, not the six or seven drawn, because
  // this heading stands under the Pulse's 「확인할 일 11」 and a 「모두 …」 that covered only what is drawn would
  // be a claim about eleven made from six. `WorkRows` is handed the same population and hides exactly this.
  const shared = sharedRowFacts(work.rows);
  const sharedSaid = sharedPhrase(shared);

  async function act(run: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await run();
      onChanged();
    } catch {
      setError("요청 실패 · 다시 시도");
    } finally {
      setBusy(false);
    }
  }

  const pill =
    co.status === "ACTIVE"
      ? { label: COPY.running, cls: "bg-good/10 text-good", dot: "bg-good ring-[3px] ring-good/15" }
      : co.status === "PAUSED"
        ? { label: COPY.paused, cls: "bg-warn/10 text-warn", dot: "bg-warn" }
        : { label: COPY.off, cls: "bg-canvas text-muted", dot: "bg-muted" };

  const warnings = [...lastRunLines(co, now), ...warningLines(co, now), ...failedReads(ops, queue)];

  return (
    <div className="space-y-4 pb-2">
      {/*
        <b>The morning is a list, so the screen opens on one</b> (reference-based hierarchy v1).

        <p>Between the title and the first row there used to be a bordered band with 「확인할 일 11」 and
        「실행 대기 없음」 in two 571px cells at 22px/800. Measured against Linear's Triage list and Intercom's
        Inbox, neither puts a counter card over a work list — the list IS the count, and in our case 실행 대기
        already had its own section four rows down, so the cell was a second pointer to it. Every fact it carried
        is still here; it stands in the one quiet status line under the title, where the automation's own state
        already was. Product-owner decision, 2026-09-25.
      */}
      <header>
        {/* §1: a page title is `xl` (22/700). This was 17px, and the 실행 대기 and 반복 문제 headings below it
            were 17px too — so the page title was the same size as its own sections and SMALLER than the 18px
            `h2` every other screen in the product uses. Size stopped carrying rank, and the seller had to read
            the words to find the structure (docs/ui/reviewnary_ui_system_audit_v1.md §2). */}
        <h1 className="break-keep text-xl font-bold leading-tight tracking-tight text-ink">{COPY.homeTitle}</h1>
        <p
          data-testid="today-status"
          className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-sm leading-relaxed text-muted"
        >
          <span>{kstLongDate(now)}</span>
          <Sep />
          {/* The state is a dot and a word, not a filled pill: on a screen whose subject is what the customers
              wrote, a coloured capsule at the top is the loudest mark for the quietest fact. Same colour, same
              link, same word. */}
          <Link
            to="/customer-operations"
            className="inline-flex items-center gap-1.5 font-medium hover:text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            <span aria-hidden="true" className={`h-[6px] w-[6px] rounded-full ${pill.dot}`} />
            {pill.label}
          </Link>
          {/* <b>Three facts, and the other four moved to the screen that owns the job</b> (product-owner
              decision, 2026-09-26). Measured, this line was 1,144px of seven facts — and that was its SHORT
              form: on an org that has checked once, 「첫 확인 전」 expands to 「최근 24시간 자동 확인 N건 · 정리 N ·
              관찰 N · 초안 N (미발송) · 처리 확인 중 N」, so the real line is eleven facts, and its strongest
              element (semibold ink) was a caveat about the machine. This line answers 「자동 확인이 돌고 있나」;
              Home needs that question's conclusion, not its parameters. 확인 주기 · 다음 확인 · 마지막 확인 were
              already on `/customer-operations` — nothing was deleted, one thing moved. What stays on THIS line is
              the frame the rest of the screen is read inside: which today it is, and whether the list below can
              be trusted. The operational figures — including 실행 대기 — are the summary's, one line down, where
              each one stands beside the question it answers. */}
        </p>
        <OperationsSummary
          work={work}
          awaiting={co.status === "ACTIVE" ? awaiting.count : null}
          unanswered={unansweredNow(metrics)}
        />
        {/* <b>What CHANGED, and nothing about the machinery.</b> Channel state used to stand here too and it
            was the wrong end of the screen for it: 「채널은 정상인가」 is the last of the morning's five
            questions, not a caveat read before the work (product-owner decision, 2026-10-01). It is now its
            own quiet row under the work, with the gap warnings that stood between the summary and the list. */}
        <ContextLine inflow={todayInflow(metrics, now)} recent={recentDay(co)} />
      </header>

      {running && co.status === "ACTIVE" ? null : (
        <section
          aria-label={pill.label}
          className="flex flex-wrap items-center gap-3 rounded-2xl border border-line bg-surface px-6 py-4"
        >
          {/* <b>Before it is running, the card names what the seller is about to start — not the state again.</b>
              It used to print `COPY.off`, the same string as the badge two lines above, so the whole card was one
              state word and a bare 「시작」: a seller could not tell what would be started, how often it would look,
              or whether it would answer a customer on their behalf. The three lines are the job's own contract —
              the name every surface uses, the cadence the server sent, and the boundary the approval path enforces.
              Nothing about the layout moves: this is the same one-row section with its text in a block. */}
          <div className="min-w-[16rem] flex-1 space-y-1">
            <p className="text-base font-bold text-ink">{RESPONSIBILITY_NAME}</p>
            <p className="break-keep text-sm leading-relaxed text-muted">{autoCheckWhat(cadenceLabel(co.cadenceMinutes))}</p>
            <p className="break-keep text-sm leading-relaxed text-muted">{COPY.autoCheckFence}</p>
          </div>
          {error ? (
            <p role="alert" className="text-sm text-bad">
              {error}
            </p>
          ) : null}
          {co.status === "PAUSED" || co.eligible ? (
            <Btn
              className="ml-auto"
              disabled={busy}
              onClick={() =>
                act(() => (co.status === "PAUSED" ? api.resumeCustomerOperations() : api.activateCustomerOperations()))
              }
            >
              {co.status === "PAUSED" ? COPY.resume : COPY.start}
            </Btn>
          ) : (
            <Link
              to="/connect"
              className="ml-auto inline-flex min-h-[40px] items-center rounded-lg border border-line bg-surface px-4 text-base font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              {COPY.connectChannel}
            </Link>
          )}
        </section>
      )}

      {work.rows.length > 0 ? (
        <section aria-label={COPY.listTitle}>
          {/* Count and order on the heading, the way out on the right, one hairline under it — the list header
              both references draw (Intercom: 「5 Open ⌄」 / 「Newest ⌄」). No box, no pill, no second number: the
              count lives on the heading of the thing it counts. */}
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 border-b border-line pb-2">
            {/* The count is the Pulse's, one line up, under this same word (2026-09-26). It is the same
                number from the same `work`, and drawing it in both places put the same fact 12px from
                itself. The heading keeps what is only the list's: its order and its way out. */}
            <h2 className="text-base font-semibold tracking-tight text-ink">{COPY.listTitle}</h2>
            <span className="break-keep text-sm text-muted">
              {COPY.listOrder}
              {/* What every row says identically, said once — instead of twice on each of them. */}
              {sharedSaid ? ` · ${sharedSaid}` : ""}
            </span>
            {/* <b>The breakdown is the queue's, not the brief's</b> (product-owner decision, 2026-10-01).
                Measured at 1600×1000 this heading read 「확인할 일 오래된 순 정보 부족 1 · 답변 필요 25 ·
                리뷰 12 · 승인 대기 4 · 초안 필요 4」 — five tallies over a list showing five rows, and every
                one of them is a filter chip on 확인할 일, drawn there as a control a seller can press. A
                count the brief cannot act on is noise on the brief and a control on the queue. */}
            {hidden > 0 || work.truncated ? (
              <Link
                to="/customer-operations/cases"
                className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {/* <b>The link is a destination, not a count</b> (product-owner decision, 2026-09-26). It said
                    「전체 11건 보기」 while the Pulse two lines above said 확인할 일 11 — the same number twice on
                    one screen, and the one down here had to go quiet when the server sent a floor instead of a
                    total, so the same control changed its shape for a reason no seller could see. The total is
                    the Pulse's, in one form, including 「N+」; this says where it goes. */}
                전체 보기 →
              </Link>
            ) : null}
          </div>
          <WorkRows
            rows={shown}
            selectedKey={selection?.selectedKey ?? null}
            wide={wide}
            search={search}
            now={now}
            ariaLabel={COPY.listTitle}
            showBacklogDivider={false}
            dense
            sharedOver={work.rows}
            // The shared facts are on the heading above. What 리뷰 counts here is a rule about the queue, and it
            // now stands on the queue screen that owns the whole list rather than as a paragraph under a
            // five-row brief — neither reference puts an explanatory sentence under a work list.
            captionSaysReason
          />
        </section>
      ) : running && co.status === "ACTIVE" ? (
        // Nothing waiting is a state, and the list's absence does not state it. 「다음 확인」 is on the status
        // line above, where it was before this became one line.
        <p className="break-keep text-sm text-muted">지금 확인할 일이 없습니다.</p>
      ) : null}

      {/*
        <b>「Reviewnary가 무엇을 준비했나」 and 「무엇이 반복되나」 are one band, not two stacked sections</b>
        (product-owner decision, 2026-10-01 — 「준비된 작업 / 반복 문제」).

        <p>Stacked, they cost ~340px and the second of them never appeared on a 1000px-tall screen: measured
        before this change, 실행 대기 began at y=1001 and 반복 문제 below it. Side by side they are one
        ~150px band that closes above the composer, which is the difference between a question the Home
        answers and a question it merely contains. Neither section changed inside; a section with nothing to
        say still draws nothing, and then the other simply has the row to itself.
      */}
      <div className="grid gap-x-8 gap-y-6 pt-2 md:grid-cols-2">
        <AwaitingExecution awaiting={awaiting} selection={selection} />
        <RepeatedProblems ops={ops} selection={selection} />
      </div>

      <ChannelState warnings={warnings} unproven={freshnessUnproven(metrics)} />
    </div>
  );
}

/**
 * <b>「채널은 정상인가」 — the morning's last question, answered where it is asked.</b>
 *
 * <p>Every fact here was already on this screen and none of it is new or re-read. What changed is the end of
 * the screen it stands at: the gap warnings were a warn-tinted block between the summary and the work, and
 * the freshness clause was a fragment of the context line above it. Both are qualifications of collection,
 * read AFTER the work rather than before it (product-owner decision, 2026-10-01).
 *
 * <p><b>It answers when the answer is yes.</b> Drawing nothing when nothing is wrong leaves the question
 * open, and a seller cannot tell «no problem» from «we did not look». The affirmative is worded as exactly
 * what the reads support — no gap row, no incomplete source, no unproven figure — and never as a claim that
 * every channel is up to date, which nothing here measures.
 */
function ChannelState({ warnings, unproven }: { warnings: React.ReactNode[]; unproven: boolean }) {
  return (
    <section
      aria-label="채널 상태"
      className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-line pt-3 text-sm"
    >
      {/* The question, named. Without it this row is an orange sentence floating under the work, and the
          seller has to infer that it is about their channels. It is a label, not a heading: the two
          sections above are work and this is the frame they were read inside. */}
      <span className="shrink-0 font-semibold text-ink">채널</span>
      {warnings.length > 0 ? (
        // The row names the question; this list keeps the name it has always had, because what it holds is
        // narrower than the question — the places a figure above does not cover.
        <ul aria-label="집계에서 빠진 곳" className="min-w-0 flex-1 space-y-1.5 text-warn">
          {warnings.map((line, i) => (
            <li key={i} className="flex flex-wrap items-center gap-x-2">
              {line}
            </li>
          ))}
        </ul>
      ) : (
        <p className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 break-keep text-muted">
          {/* Muted, never warn (§8-B′): it qualifies the figures above and is read in the same breath as
              them. The warn colour is for a channel the seller can go and fix. */}
          <span>{unproven ? "일부 채널 최신 수집 확인 필요" : "확인된 수집 문제 없음"}</span>
          <Sep />
          <Link
            to="/connect"
            className="font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            연결 상태 보기
          </Link>
        </p>
      )}
    </section>
  );
}

/** What the 오늘 list can select: a row of 확인할 일, an approved reply waiting to be posted, or a repeated problem. */
export interface HomeSelection {
  wide: boolean;
  selectedKey: string | null;
  search: string;
}

/** The review half of 확인할 일 (UI/UX v2 Phase 3). `null` = the read failed; the list is then what it was. */
export function useReviewWork(enabled = true): ReviewWorkView | null | undefined {
  const [value, setValue] = useState<ReviewWorkView | null | undefined>(undefined);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    Promise.resolve()
      .then(() => api.getReviewWorkStrict())
      .then((r) => live && setValue(r))
      .catch(() => live && setValue(null));
    return () => {
      live = false;
    };
  }, [enabled]);
  return value;
}

/** The queue read the Home's list needs. `enabled` false when the page around it already made the same read. */
export function useHomeQueue(enabled = true): InquiryQueueResponse | null | undefined {
  const [queue, setQueue] = useState<InquiryQueueResponse | null | undefined>(undefined);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    api
      .getInquiryQueueStrict({ size: HOME_QUEUE_SIZE })
      .then((r) => live && setQueue(r))
      .catch(() => live && setQueue(null));
    return () => {
      live = false;
    };
  }, [enabled]);
  return queue;
}

/**
 * <b>오늘 — the Home as a work list with the selected item beside it</b> (UI/UX v2 Phase 1).
 *
 * <p>The same sections, the same reads and the same counts as before; what changed is where an item opens. A row of
 * 확인할 일, an approved reply and a repeated problem each open in the pane on the right, drawn by the screen that owns
 * it, and the conversation box is docked under the list.
 *
 * <p><b>Nothing is chosen until the seller chooses it</b> (Home v3). This screen's question is 「오늘 무엇을 해야
 * 하지?」 and the honest answer to it is the list, not one row of it. Opening the first row on arrival made the
 * loudest thing on the morning a case the seller had not asked for — measured at 1440×900, the pane ran 2,290px
 * against a 1,807px list and carried the only filled buttons on screen — and there was no way back out of it,
 * because the URL had no value for «nothing»: a missing {@code item} meant «the first row», so no control could
 * ask for the closed state.
 *
 * <p>So the URL owns it. No {@code item} is closed, {@code item=<key>} is that row, and <b>a key that matches
 * nothing is closed too</b> — a stale address must not quietly open a different record than the one it names.
 */
export function TodayWorkspace({
  co,
  ops,
  now = new Date(),
  onChanged,
  onProblemChanged,
  metrics,
  dock,
}: {
  co: CustomerOperationsHome;
  ops: OperationsHome | null | undefined;
  now?: Date;
  onChanged: () => void;
  /**
   * The overview read the page around this list already made (`getOverviewStrict(7)`). Only the summary
   * line reads it, and only to answer 「오늘 들어온 것」 — a failed read is `null` and says nothing.
   */
  metrics?: OperationsMetrics | null;
  /** A repeated problem changed state in the pane — the list beside it must say so at once. */
  onProblemChanged?: (next: ReviewIssueView) => void;
  dock: ReactNode;
}) {
  const wide = useWideLayout();
  const location = useLocation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const queue = useHomeQueue();
  const reviewWork = useReviewWork();
  const work = mergeHomeWork(co, ops, queue, now, reviewWork);
  const key = params.get("item");

  let detail: ReactNode = null;
  let selectedKey: string | null = null;
  /**
   * The pane's one action. Set beside the detail it belongs to, so a pane can never dock a control that opens
   * something else.
   *
   * <b>It is never solid</b> (product-owner decision, 2026-09-26). The rule this replaces — «solid exactly when
   * the pane offers nothing else to press» — asked the wrong question. What decides a control's weight is what
   * pressing it DOES, and every one of these navigates: nothing here is spent, approved, sent or recorded. A
   * full-bleed `brand-700` block was measured as the heaviest element in a 440px panel whose subject is the
   * customer's own sentence at 22px/800, and neither reference draws anything like it — Linear's Peek carries no
   * action at all and Intercom's Details rail ends where its rows end. So the field that carried that rule is
   * gone rather than inverted, and `paneCarriesOwnAction` went with it: answering it was its only job.
   */
  let open: { to: string; label: string } | null = null;
  if (wide) {
    const prepared = key?.startsWith(PREPARED) ? ops?.prepared.rows.find((r) => `${PREPARED}${r.id}` === key) : undefined;
    const problem = key?.startsWith(PROBLEM) ? ops?.problems.rows.find((r) => `${PROBLEM}${r.issue.id}` === key) : undefined;
    if (prepared && prepared.kind === "REVIEW_REPLY") {
      selectedKey = key;
      detail = <ReviewCaseView key={key} reviewId={prepared.id} variant="pane" depth="preview" />;
      open = { to: `/reviews/reply/${prepared.id}?from=work`, label: FULL_SCREEN.review };
    } else if (problem) {
      selectedKey = key;
      detail = <IssueDetailPanel key={key} issue={problem.issue} onIssueChanged={onProblemChanged ?? (() => undefined)} />;
      open = { to: `/memory/${problem.issue.id}`, label: "근거 전체 보기" };
    } else if (key) {
      // Only what the address names. 확인할 일 kept a first-row fallback when this was written; it does not
      // any more (Review Decision UX v3.2), so both screens now answer 「선택 없음」 the same way.
      const chosen = work.rows.find((r) => r.key === key) ?? null;
      if (chosen) {
        selectedKey = chosen.key;
        detail = <WorkItemPane row={chosen} now={now} depth="preview" />;
        open = { to: workItemFullScreen(chosen), label: chosen.kind === "REVIEW" ? FULL_SCREEN.review : FULL_SCREEN.other };
      }
    }
  }

  const close = () => navigate({ pathname: location.pathname, search: withoutItem(location.search) }, { replace: true });

  return (
    <MasterDetail
      wide={wide}
      detailLabel="선택한 항목"
      detail={detail}
      onClose={detail ? close : undefined}
      preview
      paneFooter={
        open ? (
          <BtnLink to={open.to} variant="outline" className="w-full text-brand-700">
            {open.label}
          </BtnLink>
        ) : null
      }
      footer={dock}
      list={
        <CustomerOpsHome
          co={co}
          ops={ops}
          now={now}
          onChanged={onChanged}
          metrics={metrics}
          sharedQueue={{ value: queue }}
          sharedReviewWork={{ value: reviewWork }}
          selection={{ wide, selectedKey, search: location.search }}
        />
      }
    />
  );
}

/** The address of the closed state: this page, with the selection dropped and every other parameter kept. */
function withoutItem(search: string): string {
  const params = new URLSearchParams(search);
  params.delete("item");
  const rest = params.toString();
  return rest ? `?${rest}` : "";
}

const PREPARED = "prepared:";
const PROBLEM = "problem:";

/** The dot between two facts on the status line. Drawn, never typed, so a wrapped line never starts on one. */
function Sep() {
  return (
    <span aria-hidden="true" className="text-line">
      ·
    </span>
  );
}

/**
 * <b>The band has no heading any more, and that is a consequence of what it now holds.</b>
 *
 * <p>It used to be 「운영 현황」 over three cells that were three different WINDOWS — 오늘 들어온 것 (a KST
 * day), 확인할 일 (now), 최근 24시간 (a rolling window on cases we opened). Three windows genuinely need a
 * frame, because no single heading is true of all three, and that is what the name was buying.
 *
 * <p>Since UI System v2 the band holds three OBLIGATIONS and nothing else — 확인할 일 · 실행 대기 ·
 * 현재 미답변 (product-owner decision, 2026-09-30) — and all three are 「now」. Each carries its own lead
 * word, so a group name over them repeats what the cells already say and adds a fourth level between the
 * page title and the work. The band keeps an accessible name; it no longer spends a line on a visible one.
 *
 * <p>Where the other two went: inflow and 최근 24시간 are not obligations. They are what CHANGED and what
 * reviewnary DID, and §8-A has always said the automation's own report is 「약한 한 줄」 and not a number
 * card. Both moved to {@link ContextLine} under the band, with channel freshness, which is where a fact
 * that qualifies the work belongs. Nothing was deleted and no number changed its definition.
 */
const PULSE_LABEL = "지금 해야 할 일";

/**
 * <b>세 개의 의무, 한 줄로.</b> 확인할 일 · 실행 대기 · 현재 미답변 — and nothing else
 * (product-owner decision, 2026-09-30).
 *
 * <p>The data contract is `docs/pilot_usage_loop_v1.md` §8 and it does not move: <b>no new read</b>, no
 * KPI card, no tile, no chart. 매출 · 주문량 · 추이 · 채널별 수치 stay owned by `/overview` — the defect
 * this repository has fixed several times is the same number living in two places under two definitions
 * (`docs/reviewnary_design.md` §8-A).
 *
 * <p><b>Why these three and not others.</b> An obligation is something that is waiting for the seller.
 * 확인할 일 is our queue of decisions; 실행 대기 is what they already decided and has not been posted;
 * 현재 미답변 is the channel's own fact — a customer without an answer. 반복 문제 is NOT here: it is a
 * pattern, not a customer waiting, and it has its own section below the work, which is where a pattern
 * belongs.
 *
 * <p><b>확인할 일 and 현재 미답변 are not a subset relation</b>, which is why both are drawn and neither is
 * derived from the other — see {@link unansweredNow}. Each cell's note line carries its own qualification,
 * so a figure that counts fewer channels than the seller has says so beside itself.
 *
 * <p><b>Three tiers per cell, and the middle tier is one type size whatever it holds</b> — 질문 / 지금 상태 /
 * 그 상태를 한정하는 사실. A count and a withheld count are both answers; a band that grows only when the
 * answer is a number shrinks precisely when the seller needs to notice something. What separates them is
 * <b>ink</b>: a measured figure is ink and semibold, every other word is the ordinary muted weight. No warn
 * colour, no icon, no tint — a band that turns orange when a channel is quiet trains a seller to stop
 * reading the band.
 *
 * <p><b>One surface, not three cards</b> (product-owner decision: compact summary strip). The surface is
 * the width of what it holds (`max-w-3xl`) rather than of the page, the columns are thirds of it, and the
 * two hairlines are the cheapest way to say 「these three belong to one reading」. No shadow, no gradient,
 * and the cells carry no fill, border or radius of their own.
 *
 * <p>A cell with nothing true to say renders nothing, and when no cell has, neither does the surface. That
 * is not tidiness: a read that failed, a job that is not running and an org with no window yet are three
 * different silences, and none of them is 「0」. The grid is sized to the cells it actually has, so two
 * facts are two columns rather than two columns and a gap.
 */
function OperationsSummary({
  work,
  awaiting,
  unanswered,
}: {
  work: HomeWork;
  /** `null` when the job is not running — 실행 대기 is a fact about a job that is looking. */
  awaiting: number | null;
  /** `null` when the metrics read did not land. */
  unanswered: InflowFact | null;
}) {
  const cells: ReactNode[] = [];

  if (work.rows.length > 0) {
    cells.push(
      <Cell key="work" id="work" label={COPY.listTitle}>
        {/* The server said there are more than it sent, so this total is a floor — 「11+」 건. The unit is
            what stops the state slot from being a naked number: every other cell answers its question in
            words, and 11 alone answered it in the vocabulary of a dashboard tile. */}
        <Big value={work.rows.length} suffix={work.truncated ? "+" : ""} unit={COUNT_UNIT} />
      </Cell>,
    );
  }

  if (awaiting !== null) {
    cells.push(
      <Cell key="awaiting" id="awaiting" label="실행 대기">
        {/* A measured zero, not an unknown: the job is running and it found nothing pending. The label is
            an anchor only when there is a section to land on. */}
        {awaiting === 0 ? (
          <State>없음</State>
        ) : (
          // The label above is a separate span, so without a name of its own this link announces a bare
          // number. The noun rides with it — a link called 「2건」 tells a screen-reader user nothing about
          // where it goes.
          <a
            href="#실행-대기"
            aria-label={`실행 대기 ${awaiting.toLocaleString("ko-KR")}${COUNT_UNIT}`}
            className="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            <Big value={awaiting} unit={COUNT_UNIT} />
          </a>
        )}
      </Cell>,
    );
  }

  /**
   * <b>An obligation slot holds a measured obligation or nothing</b> (visual review, 1440×900, 2026-10-01).
   *
   * <p>When the population is incomplete this cell used to stand in the band saying
   * 「수집 상태 확인 필요」 — and the context line one row below said the same five syllables for the inflow,
   * and then 「일부 채널 최신 수집 확인 필요」 beside it. One cause, three sentences, 40px apart, at the top
   * of the screen a seller opens every morning. The band is for what is waiting for them; a fact we could
   * not measure is not waiting for anyone, and the context line is where collection state already lives.
   */
  if (unanswered?.kind === "COUNT") {
    cells.push(
      <Cell
        key="unanswered"
        id="unanswered"
        label={UNANSWERED_WORD.lead}
      >
        {(
          <Link
            to="/inquiries"
            aria-label={`${UNANSWERED_WORD.lead} ${unanswered.value.toLocaleString("ko-KR")}${COUNT_UNIT}`}
            className="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            <Big value={unanswered.value} unit={COUNT_UNIT} />
          </Link>
        )}
      </Cell>,
    );
  }

  if (cells.length === 0) return null;
  return (
    <section
      data-testid="today-summary"
      aria-label={PULSE_LABEL}
      className={`mt-3 grid max-w-3xl divide-x divide-line rounded-xl bg-canvas px-6 py-3 ${
        cells.length === 3 ? "grid-cols-3" : cells.length === 2 ? "grid-cols-2" : "grid-cols-1"
      }`}
    >
      {cells}
    </section>
  );
}

/**
 * <b>The work's context, as one quiet line</b> — what changed, what reviewnary did, and whether the
 * figures above can be trusted.
 *
 * <p>These are the facts that used to be two of the three summary cells. They are not obligations: nothing
 * here is waiting for the seller. §8-A has said since v3.2 that the automation's own report is 「약한 한 줄」
 * and not a number card, and inflow belongs with it — a day's arrivals qualify the work, they are not the
 * work. Drawing them at the weight of an obligation was what made the band read as a dashboard.
 *
 * <p>Order is fixed and each fact is absent rather than zero: 오늘 들어온 것 → 최근 24시간 → 수집 상태 →
 * the way to the numbers screen. 「자세한 숫자 보기」 stays because this line is deliberately NOT where
 * 매출·주문·추이 live.
 */
function ContextLine({
  inflow,
  recent,
}: {
  inflow: ReturnType<typeof todayInflow>;
  recent: ReturnType<typeof recentDay>;
}) {
  const facts: ReactNode[] = [];

  if (inflow) {
    const blind = inflow.reviews.kind === "UNQUALIFIED" && inflow.inquiries.kind === "UNQUALIFIED";
    if (blind) {
      facts.push(<span key="inflow">{INFLOW_WORD.bothUnqualified}</span>);
    } else {
      const counted = dotted([
        inflow.reviews.kind === "COUNT" ? <Figure key="r" word={INFLOW_WORD.reviews} value={inflow.reviews.value} small /> : null,
        inflow.inquiries.kind === "COUNT" ? <Figure key="i" word={INFLOW_WORD.inquiries} value={inflow.inquiries.value} small /> : null,
      ]);
      if (counted) {
        facts.push(
          <span key="inflow">
            {INFLOW_WORD.lead} {counted}
          </span>,
        );
      }
      // The lane we could not vouch for is still named — on the line whose job is naming what limits the
      // figures. It is never folded into a smaller number.
      for (const withheld of [
        inflow.reviews.kind === "UNQUALIFIED" ? INFLOW_WORD.reviewsUnqualified : null,
        inflow.inquiries.kind === "UNQUALIFIED" ? INFLOW_WORD.inquiriesUnqualified : null,
      ]) {
        if (withheld) facts.push(<span key={withheld}>{withheld}</span>);
      }
      if (inflow.exampleData) facts.push(<span key="example">{INFLOW_WORD.exampleData}</span>);
    }
  }

  if (recent) {
    facts.push(
      recent.checked === 0 ? (
        <span key="recent">{RECENT_WORD.none}</span>
      ) : (
        <span key="recent">
          {RECENT_WORD.lead} <Figure word={RECENT_WORD.checked} value={recent.checked} small />
          {recent.autoResolved > 0 || recent.draftsPrepared > 0 ? (
            <>
              {" ("}
              {dotted([
                recent.autoResolved > 0 ? <Figure key="a" word={RECENT_WORD.autoResolved} value={recent.autoResolved} small /> : null,
                recent.draftsPrepared > 0 ? <Figure key="d" word={RECENT_WORD.draftsPrepared} value={recent.draftsPrepared} small /> : null,
              ])}
              {")"}
            </>
          ) : null}
        </span>
      ),
    );
  }

  /*
    Channel freshness used to end this line. It is now {@link ChannelState}'s, at the foot of the screen,
    because 「채널은 정상인가」 is its own question and not a trailing clause of 「무엇이 달라졌나」
    (product-owner decision, 2026-10-01). The rule that produced it is unchanged and so is its colour.

    <p>The one thing that had to travel with it: a withheld inflow lane above is withheld BECAUSE a
    collection could not be proven, so both clauses are one cause stated twice. They no longer stand 8px
    apart — they stand at opposite ends of the screen, each answering the question it was asked under —
    and the inflow clause remains the better one here because it names which lane.
  */

  if (facts.length === 0) return null;
  return (
    <p
      data-testid="today-context"
      aria-label="운영 상황"
      className="mt-2 flex flex-wrap items-center gap-x-1 gap-y-1 break-keep text-sm text-muted"
    >
      {dotted(facts)}
      <Dot />
      <Link to="/overview" className="font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
        자세한 숫자 보기
      </Link>
    </p>
  );
}

/** 건 — the unit 확인할 일 is counted in everywhere else on this screen, including the list's own heading. */
const COUNT_UNIT = "건";

/**
 * Facts of one cell, separated by a dot that is a real text node. Absent facts take their separator with
 * them, so a caller writes the facts and never the punctuation between them.
 */
function dotted(parts: ReactNode[]): ReactNode {
  const kept = parts.filter(Boolean);
  // Nothing to state is not an empty line: an empty note span would still take its top margin and would
  // still put a space into what a screen reader reads out.
  if (kept.length === 0) return null;
  return kept.map((part, i) => (
    <Fragment key={i}>
      {i > 0 ? <Dot /> : null}
      {part}
    </Fragment>
  ));
}

/**
 * One column: the question, the state, and what qualifies the state.
 *
 * <p><b>One paragraph, three stacked spans</b> — not three paragraphs. The label and its figure are one
 * fact and are read as one; between block children of a paragraph the separating space is a real text
 * node, so a screen reader says 「확인할 일 11건」 and not 「확인할 일11건」. That is the same rule the dots
 * inside a cell follow, and the reason the old line spelled its separators instead of drawing them.
 *
 * <p>The state line is 20px and fixes its line box at 28px, so a cell whose state is a sentence sits on
 * exactly the baseline of a cell whose state is a number — the row of states has to read as a row.
 * `min-w-0` lets a long state wrap inside its third instead of pushing the grid; it may never clip, which
 * is why the atoms carry `whitespace-nowrap` and the separators between them do not.
 *
 * <p>The horizontal padding is what the hairline divides: the first cell keeps the surface's own left edge
 * and the last one its right, so the band has one inset and the two rules sit midway between neighbours.
 */
function Cell({ id, label, note, children }: { id: string; label: string; note?: ReactNode; children: ReactNode }) {
  return (
    <p
      data-testid={`pulse-${id}`}
      className="min-w-0 break-keep px-6 text-sm text-muted first:pl-0 last:pr-0"
    >
      <span className="block leading-[18px]">{label}</span>{" "}
      <span className="mt-0.5 block text-xl leading-[28px]">{children}</span>
      {note ? (
        <>
          {" "}
          <span className="mt-0.5 block text-xs leading-[19px]">{note}</span>
        </>
      ) : null}
    </p>
  );
}

/**
 * <b>미관측은 숫자가 아니고, 사고도 아니다.</b> It stands in the state slot at the state slot's size, because
 * 「아직 확인하지 못했다」 is this column's answer today and the band exists to be read at a glance. What it
 * does not take is the ink and the weight: those mark a measured figure, and this is the absence of one.
 */
function State({ children }: { children: ReactNode }) {
  return <span className="whitespace-nowrap font-medium text-muted">{children}</span>;
}

/**
 * The figure itself — the only ink and the only weight this surface spends. The unit rides with it so a
 * line can never break between a number and the thing it counts.
 */
function Big({ value, suffix = "", unit }: { value: number; suffix?: string; unit?: string }) {
  return (
    <span className="whitespace-nowrap">
      <span className="font-semibold tabular-nums text-ink">
        {value.toLocaleString("ko-KR")}
        {suffix}
      </span>
      {unit}
    </span>
  );
}

/**
 * Noun muted, number ink. `small` is the supporting line, where the figure keeps the ink and the weight
 * but not the size — 그중 정리 and 초안 준비 qualify 새로 확인, and a qualifier at the size of the thing it
 * qualifies is a second headline.
 *
 * <p>The word and its number are one unwrappable atom: a line that breaks between 리뷰 and 6 has moved a
 * number away from the noun it counts.
 */
function Figure({ word, value, small = false }: { word: string; value: number; small?: boolean }) {
  return (
    <span className="whitespace-nowrap">
      {word}{" "}
      {small ? (
        <span className="font-semibold tabular-nums text-ink">{value.toLocaleString("ko-KR")}</span>
      ) : (
        <Big value={value} />
      )}
    </span>
  );
}

/**
 * The separator inside one cell. The spaces are text, not layout: the space between two flex children is
 * rendered, never read, and a screen reader would say 「리뷰6」.
 */
function Dot() {
  return (
    <>
      {" "}
      <span aria-hidden="true" className="text-line">
        &middot;
      </span>{" "}
    </>
  );
}

/**
 * How many rows 실행 대기 draws on the brief.
 *
 * <p>Two, because this section is one half of a band that has to close above the composer — measured, four
 * rows ran it to y=1090 on a 1000px screen and took the channel row off the screen with it. The <b>total</b>
 * is never the drawn count: it stays on the heading and in the summary band's own cell, so capping the list
 * cannot quietly shrink the number the seller is told they owe.
 */
const HOME_AWAITING_ROWS = 2;

/** The rows of 실행 대기 after the dedupe against 확인할 일, and how many the section stands for in total. */
function awaitingRows(ops: OperationsHome | null | undefined, work: HomeWork) {
  const prepared = ops?.prepared;
  if (!prepared) return { rows: [] as HomePreparedItem[], shown: [] as HomePreparedItem[], moreReplies: 0, count: 0 };
  // Anything 확인할 일 is already offering is not offered again, by the same key that list deduped itself with.
  const claimed = new Set(work.rows.map((row) => row.owner));
  const rows = prepared.rows.filter((row) => !claimed.has(row.to));
  // The server caps its list; only the review-reply kind is compared, because it is the only one this section never
  // expects to lose rows to the dedupe above.
  const drawnReplies = rows.filter((row) => row.kind === "REVIEW_REPLY").length;
  const moreReplies = Math.max(0, prepared.reviewRepliesApproved - drawnReplies);
  // `count` is what the section STANDS FOR and `shown` is what it draws. They were the same number until the
  // brief had to make room for the questions below it, and keeping them one field would have made the cap a
  // silent correction to an obligation.
  return { rows, shown: rows.slice(0, HOME_AWAITING_ROWS), moreReplies, count: rows.length + moreReplies };
}

/**
 * <b>실행 대기 — 판매자가 이미 결정했고, 아직 끝나지 않은 일.</b>
 *
 * <p>Everything here rests on a record the seller themselves wrote: an approval that stands, a draft that exists,
 * an improvement they accepted. That is what separates this from 「확인 필요」 above it — there the question is
 * 「어떻게 할까」, here it is 「아까 정한 걸 끝냅시다」 — and it is why these rows are worth their own heading rather
 * than being mixed into the decision list.
 *
 * <p><b>Measured, not supposed.</b> On the live org three approved replies had been standing for seventeen days
 * with no submission recorded and a fourth carried four aborted attempts, and this Home drew none of them: it never
 * read {@code ops.prepared} at all. They could not surface through 「확인 필요」 either, because that list is built
 * from cases, UNDECIDED reviews and the inquiry queue — and an approved reply is, by definition, decided.
 *
 * <p><b>Nothing is re-run and nothing is written.</b> Every row is a link to the surface that owns finishing it.
 * This product has no dispatcher: approving freezes the text and marks it copy-ready, and the posting is the
 * seller's own action on the marketplace. A control here that looked like 「보내기」 would promise a send no
 * approval covers.
 *
 * <p><b>Reported-as-sent work is already gone before it reaches this component</b> — the server's standing-approval
 * predicate excludes an approval whose approved fingerprint has an {@code OPERATOR_REPORTED_SUBMITTED} outcome. An
 * aborted attempt is not such an outcome: it is one guided run ending at the submit barrier, which posts nothing
 * and withdraws nothing, so the reply is still waiting and still belongs here.
 */
function AwaitingExecution({
  awaiting,
  selection,
}: {
  awaiting: ReturnType<typeof awaitingRows>;
  selection?: HomeSelection;
}) {
  const { rows, shown, moreReplies, count } = awaiting;
  if (rows.length === 0 && moreReplies === 0) return null;
  const wide = selection?.wide ?? false;
  const search = selection?.search ?? "";
  const selectedId = selection?.selectedKey?.startsWith(PREPARED) ? selection.selectedKey.slice(PREPARED.length) : null;

  return (
    <section aria-label="실행 대기" id="실행-대기">
      {/* The badge that used to sit here said 「승인함 · 등록 전」 over every row. It is what a standing
          review approval is, and what an inquiry row is not — those arrive with the work item still
          PROPOSED and no approval anywhere. One badge cannot be true of both, so the state moved onto
          the rows, where each one can say its own (see lib/preparedState.ts). */}
      {/* No `mt-8`: this section is one half of a two-column band now, and a top margin on one of two
          grid children stops their headings sharing a baseline. The band's own `gap-y` owns the space. */}
      {/* <b>The total rides on the heading, because the list below it is capped.</b> The brief draws the
          first two and says so; the number the seller owes is the section's, not the list's, and it is the
          same figure the summary band's own cell links down to. */}
      <div className="mb-1 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-base font-semibold tracking-tight text-ink">실행 대기</h2>
        {count > shown.length ? (
          <span className="break-keep text-sm tabular-nums text-muted">
            {count.toLocaleString("ko-KR")}건 중 {shown.length.toLocaleString("ko-KR")}건
          </span>
        ) : null}
      </div>
      <PreparedWorkList
        rows={shown}
        selectedId={wide ? selectedId : null}
        // An approved review reply opens in the pane — the Review Case, where the approved text and its copy are.
        linkFor={(row) => (row.kind === "REVIEW_REPLY" ? selectionHref(wide, `${PREPARED}${row.id}`, row.to, search) : row.to)}
      />
      {moreReplies > 0 ? (
        <p className="mt-2 px-1 text-sm">
          <Link
            to="/reviews"
            className="font-medium text-brand-700 underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            승인한 리뷰 답변 {moreReplies.toLocaleString("ko-KR")}건 더 보기
          </Link>
        </p>
      ) : null}
    </section>
  );
}

/** The latest run failed: the 24-hour tally above stands, and this names the check it does not include. */
function lastRunLines(co: CustomerOperationsHome, now: Date): React.ReactNode[] {
  if (co.lastRunStatus !== "FAILED") return [];
  const at = kstClock(co.lastCheckedAt, now);
  const next = kstClock(co.nextCheckAt, now);
  return [
    <span className="break-keep">
      {COPY.lastCheckFailed}
      {at ? ` (${at})` : ""} · 이번 확인분 집계 제외{next ? ` · 다음 확인 ${next}` : ""}
    </span>,
  ];
}

/**
 * A list read that failed is left out of the count, and the card says so. 「N+」 is kept for a read that reported more
 * than it returned; a failure knows nothing about how many there are, so it gets this line instead of a guess.
 */
function failedReads(ops: OperationsHome | null | undefined, queue: InquiryQueueResponse | null | undefined): React.ReactNode[] {
  const lines: React.ReactNode[] = [];
  if (ops === null) lines.push(<span className="break-keep">리뷰 목록 읽기 실패 · 부분 집계</span>);
  if (queue === null) lines.push(<span className="break-keep">문의 목록 읽기 실패 · 부분 집계</span>);
  return lines;
}

/** Sources the number above does not cover — a gap to reconnect, or a read that did not finish. */
function warningLines(co: CustomerOperationsHome, now: Date): React.ReactNode[] {
  const lines: React.ReactNode[] = [];
  const gapChannels = new Set<string>();
  for (const gap of co.gaps.rows) {
    if (gap.channelCode) gapChannels.add(gap.channelCode);
    const types = gap.dataTypes.map(dataTypeKo).join("·") || "자료";
    const since = kstClock(gap.since, now);
    lines.push(
      <>
        <span className="break-keep">
          {channelShort(gap.channelCode ?? gap.channelNameKo) ?? "채널"} {types} {failureShort(gap.reason)}
          {since ? ` · ${since}부터` : ""} 집계 제외
        </span>
        <Link to={gap.to} className="ml-auto font-bold text-warn hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
          {COPY.reconnect}
        </Link>
      </>,
    );
  }
  for (const source of co.sources) {
    if (source.completeness === "COMPLETE" || source.completeness === null || gapChannels.has(source.channelCode)) continue;
    const name = `${channelShort(source.channelCode) ?? "채널"} ${dataTypeKo(source.dataType)}`;
    lines.push(
      <span className="break-keep">
        {source.completeness === "NONE"
          ? `${name} ${failureShort(source.failureReason)} · 집계 제외`
          : `${name} 일부만 확인 · ${failureShort(source.failureReason)}`}
      </span>,
    );
  }
  return lines;
}

/**
 * <b>반복 문제 — what repeated, on which product, how often, and why it is worth a look.</b>
 *
 * <p>This used to be one grey line: 「관찰 중 {decidable + observing}」 plus, if any problem happened to have a trend
 * label, that one problem's title. Three things were wrong with it, and only the third is about layout.
 *
 * <ul>
 *   <li><b>It printed a sum under one of its parts' names.</b> {@code decidable} and {@code observing} are two
 *       populations the server returns separately and documents as un-addable; adding them and labelling the total
 *       「관찰 중」 told a seller with one problem 조치 중 and nineteen 관찰 중 that twenty were 관찰 중.</li>
 *   <li><b>Its link went to the list, not to the problem</b>, whenever no trend fired — which is the ordinary case
 *       for a problem that repeated steadily rather than suddenly.</li>
 *   <li><b>It named no product, no evidence and no count.</b> On the org this was measured against, eighteen pieces
 *       of evidence for one problem on one product rendered as 「관찰 중 20 · 보기」.</li>
 * </ul>
 *
 * <p>The rows were already on the wire — {@code ops.problems} is the Operations Home read AgentHome makes anyway,
 * already bounded to three, already ordered decidable-first by the server, already carrying each problem's
 * per-product evidence. Nothing new is read, derived or judged here; the same rows the other Home draws are drawn
 * here, by the same component.
 *
 * <p><b>Still not a task.</b> It sits below 「확인 필요」, has no verb and no button, and states its two counts as the
 * server's own sentence ({@code problemLine}) rather than as a workload. A repeated problem is a pattern over many
 * reviews, not another customer waiting — that separation is the reason one row can stand for eighteen of them
 * without the Home saying the same thing twice.
 */
function RepeatedProblems({ ops, selection }: { ops: OperationsHome | null | undefined; selection?: HomeSelection }) {
  const problems = ops?.problems;
  // A read that did not land says nothing; a read that landed on nothing says so. The two were one branch
  // until the Home was asked to answer 「반복되는 문제는 무엇인가」 on its first screen, and a section that
  // renders nothing leaves that question open — a seller cannot tell 「없다」 from 「보지 않았다」
  // (product-owner decision, 2026-10-01).
  if (!problems) return null;
  if (problems.rows.length === 0) {
    return (
      <section aria-label="반복 문제">
        <h2 className="mb-1 text-base font-semibold tracking-tight text-ink">반복 문제</h2>
        <Empty
          compact
          title="아직 없습니다"
          body="같은 문제를 말한 리뷰가 쌓이면 여기 모입니다."
          action={
            <Link
              to="/memory"
              className="text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              지난 기록 보기
            </Link>
          }
        />
      </section>
    );
  }
  const wide = selection?.wide ?? false;
  const search = selection?.search ?? "";
  const selectedId = selection?.selectedKey?.startsWith(PROBLEM) ? selection.selectedKey.slice(PROBLEM.length) : null;
  return (
    <section aria-label="반복 문제">
      {/* The server's own two counts ride on the heading line instead of standing as a paragraph of ink
          under it. They qualify the section — 「조치 필요 1 · 관찰 중 19」 — and a qualification set at the
          weight of body text was a second headline between a heading and the rows it names. Same string,
          same source ({@link problemLine}), one line fewer and one weight quieter. */}
      <div className="mb-1 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-base font-semibold tracking-tight text-ink">반복 문제</h2>
        <span className="break-keep text-sm text-muted">{problemLine(problems)}</span>
      </div>
      <RepeatedProblemList
        rows={problems.rows}
        selectedId={wide ? selectedId : null}
        linkFor={(issueId) => selectionHref(wide, `${PROBLEM}${issueId}`, `/memory/${issueId}`, search)}
      />
      <p className="mt-2 px-1 text-sm">
        <Link
          to="/memory"
          className="font-medium text-brand-700 underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          반복 문제 전체 보기
        </Link>
      </p>
    </section>
  );
}
