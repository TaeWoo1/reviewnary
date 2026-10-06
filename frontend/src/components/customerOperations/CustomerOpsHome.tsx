import { Fragment, useEffect, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { Btn, BtnLink } from "../ui/Btn";
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
import { homeBriefRows, mergeHomeWork, sharedRowFacts, type HomeWork } from "../../lib/homeWork";
import { automationHealth, type HealthTone } from "../../lib/automationHealth";
import { NO_CHANGE_TODAY, changeInsights } from "../../lib/homeInsights";
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
  ChannelCoverageRowView,
  HomePreparedItem,
  HomeRepeatedProblems,
  InquiryQueueResponse,
  OperationsHome,
  OperationsInsight,
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

/**
 * <b>The brief's own name</b> (Home visual target, 2026-10-01).
 *
 * <p>Not {@link COPY.listTitle}: that string names the QUEUE — the rail item, the screen it links to, and
 * the summary cell one band above — and printing it here made the first screen say 확인할 일 three times
 * and read as an abbreviation of the list beside it. These rows are a brief over the oldest of the work.
 */
const HOME_BRIEF_TITLE = "오늘 먼저 볼 일";

/**
 * <b>What the four rows ARE, now that they are not an age ranking</b> (product-owner decision,
 * 2026-10-01).
 *
 * <p>It said 「오래 기다린 문의와 리뷰부터 보여드립니다」, and that was an accurate description of the
 * single age comparator this brief used to be sliced from — the one the visual QA removed. The rows
 * are a lane quota now ({@link homeBriefRows}: Review 2 / Inquiry 1 / Case 1), each lane in the order
 * its own source declares, so 「오래 기다린 … 부터」 would be a promise about a ranking that no longer
 * exists and that two of the four rows never obeyed.
 *
 * <p>The sentence says what IS true of every row: it is drawn from both kinds of work, and it is a
 * selection rather than the whole list — which is what the 전체 보기 beside it is for. It claims
 * nothing about order, because the honest answer is three orders and that is the queue screen's.
 */
const HOME_BRIEF_NOTE = "문의와 리뷰에서 먼저 볼 것을 모았습니다.";

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
  insights,
  sharedQueue,
  sharedReviewWork,
  selection,
}: {
  /**
   * 고객 운영 관리(책임 런타임)의 읽기, <b>또는 {@code null}</b> — 그 일이 이 배포에서 열려 있지 않거나
   * 읽기가 실패한 경우.
   *
   * <p><b>{@code null}이어도 이 화면이 그려진다.</b> 전에는 그때 다른 Home(네 개의 성긴 영역 + 대화 턴)이
   * 대신 그려졌고, 같은 제품의 첫 화면이 두 가지 모양을 가졌다 — 그리고 어느 쪽이 보이는지는 데이터가 아니라
   * 배포 설정(rollout)과 읽기 성공 여부가 정했다. 자동 확인에 <b>속한</b> 것(상태 점 · 마지막 확인 · 주기 ·
   * 자동이 처리한 것)만 그때 말하지 않고, 골격 — 오늘 · 숫자 · 오늘 먼저 볼 일 · 오늘 달라진 점 · 실행 대기 ·
   * 반복 문제 · 채널 — 은 그대로 선다.
   */
  co: CustomerOperationsHome | null;
  ops: OperationsHome | null | undefined;
  now?: Date;
  onChanged: () => void;
  /**
   * The overview read the page around this list already made (`getOverviewStrict(7)`). Only the summary
   * line reads it, and only to answer 「오늘 들어온 것」 — a failed read is `null` and says nothing.
   */
  metrics?: OperationsMetrics | null;
  /**
   * The insight half of the SAME overview read `metrics` comes from (`getOverviewStrict(7)`) — no new
   * request. `null`/absent is a read that did not land and draws nothing; an empty array is a read that
   * landed on nothing, which also draws nothing, because 「달라진 점」 with no change in it is not a
   * section, it is a heading.
   */
  insights?: OperationsInsight[] | null;
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
  /*
    <b>Four slots, allotted by lane</b> (product-owner decision, 2026-10-01 — Review 2 / Inquiry 1 /
    Case 1). Slicing the first four off one order made the brief whichever lane was longest: measured
    on the live org it was four네이버 reviews, and this month's inquiry was not on the screen.
    {@link homeBriefRows} chooses how many of each; the lanes' own orders choose which.
  */
  const shown = homeBriefRows(work.rows, visibleHomeRows(roomy));
  const hidden = work.rows.length - shown.length;
  const running = co?.status === "ACTIVE" || co?.status === "PAUSED";
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

  /*
    <b>The dot is health, not a switch position</b> (product-owner decision, 2026-10-01).

    <p>It read `co.status === "ACTIVE" ? green : …` — one CONFIGURATION field — and on the live org
    that drew a green dot and 「자동 확인 중」 on the same line as 「마지막 확인 9월 27일 03:31」, four
    days stale. The rule now lives in {@link automationHealth}: enabled, the last run's outcome and
    when it last succeeded are three facts, and green needs all three. This component only maps the
    tone it is handed onto marks — so the rule is testable and a future tint cannot restore it.
  */
  const health = co ? automationHealth(co, now) : null;
  const DOT: Record<HealthTone, string> = {
    good: "bg-good ring-[3px] ring-good/15",
    warn: "bg-warn",
    bad: "bg-bad",
    neutral: "bg-muted",
  };

  const warnings = co
    ? [...lastRunLines(co, now), ...warningLines(co, now), ...failedReads(ops, queue)]
    : failedReads(ops, queue);
  const lastChecked = co ? kstClock(co.lastCheckedAt, now) : null;

  return (
    /*
      <b>16px between sections, not 24</b> (measured at 1600×1000 after the type scale grew, 2026-10-01).

      <p>The page title went 22 → 28 and every section heading 16 → 20, which is 43px more page — and the
      screen's own contract is that the morning's five questions are all answered above the composer. The
      separation the gap was buying is now carried by the headings themselves: at 20/700 over 15px body a
      section announces itself, where at 16/600 it needed the air to be seen as a break.
    */
    <div className="space-y-4">
      {/*
        <b>The morning is a list, so the screen opens on one</b> (reference-based hierarchy v1).

        <p>Between the title and the first row there used to be a bordered band with 「확인할 일 11」 and
        「실행 대기 없음」 in two 571px cells at 22px/800. Measured against Linear's Triage list and Intercom's
        Inbox, neither puts a counter card over a work list — the list IS the count, and in our case 실행 대기
        already had its own section four rows down, so the cell was a second pointer to it. Every fact it carried
        is still here; it stands in the one quiet status line under the title, where the automation's own state
        already was. Product-owner decision, 2026-09-25.
      */}
      {/*
        <b>Title, state, and the one control that re-reads the screen</b> (Home visual target, 2026-10-01).

        <p>The target mockup is the canonical composition for this screen and it puts 새로고침 at the top
        right of the title row — the only control in the header, and the only thing a seller presses here
        that is not a destination. It calls the page's own `onChanged`; nothing is collected, run or sent.

        <p><b>마지막 확인 comes back.</b> It was moved to `/customer-operations` on 2026-09-26 on the
        argument that Home needs the automation's conclusion and not its parameters — which was right about
        확인 주기 and 다음 확인 and wrong about this one: 「언제 본 것인가」 is how far the list below can be
        trusted, which is exactly what this line is for. The other two stay where they were moved to.
      */}
      <header className="border-b border-line pb-4">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            {/* §1: a page title is the largest thing on the page. It was `xl` (22px) — one step above the
                section headings under it — and the target sets the two a full step apart, so size carries
                rank without the seller reading the words. */}
            <h1 className="break-keep text-title font-bold leading-tight tracking-tight text-ink">{COPY.homeTitle}</h1>
            <p
              data-testid="today-status"
              className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-sm leading-relaxed text-muted"
            >
              <span>{kstLongDate(now)}</span>
              {/* 자동 확인의 상태는 자동 확인이 있을 때만 말한다. 그 일이 열려 있지 않은 배포에서 회색 점과
                  상태어를 그리면, 켜 본 적도 없는 기능의 건강을 보고하는 줄이 된다. */}
              {health ? (
                <>
                  <Sep />
                  {/* The state is a dot and a word, not a filled pill: on a screen whose subject is what the
                      customers wrote, a coloured capsule at the top is the loudest mark for the quietest fact. */}
                  <Link
                    to="/customer-operations"
                    className="inline-flex items-center gap-1.5 font-medium hover:text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                  >
                    <span aria-hidden="true" className={`h-[6px] w-[6px] rounded-full ${DOT[health.tone]}`} />
                    {health.label}
                  </Link>
                  {/* Why it is not green, when it is not. Null while it is — the 마지막 확인 beside this
                      already carries the evidence and does not need a second sentence about it. */}
                  {health.note ? (
                    <>
                      <Sep />
                      <span>{health.note}</span>
                    </>
                  ) : null}
                </>
              ) : null}
              {lastChecked ? (
                <>
                  <Sep />
                  <span>마지막 확인 {lastChecked}</span>
                </>
              ) : null}
            </p>
          </div>
          <Btn variant="outline" size="sm" className="shrink-0" disabled={busy} onClick={onChanged}>
            <RefreshIcon />
            새로고침
          </Btn>
        </div>
      </header>
      {/* 실행 대기는 판매자가 승인해 둔 것이고 자동 확인의 소유가 아니다 — 그 일이 열려 있지 않아도 센다.
          다만 보유 읽기가 실패했으면 0이 아니라 아무 말도 하지 않는다(null). */}
      <OperationsSummary
        work={work}
        awaiting={co ? (co.status === "ACTIVE" ? awaiting.count : null) : ops ? awaiting.count : null}
        unanswered={unansweredNow(metrics)}
      />


      {/* 이 일을 시작하는 카드는 시작할 수 있는 배포에서만 선다. 책임 런타임이 열려 있지 않은 곳에서
          「시작」을 그리면 누를 수 없는 버튼을 첫 화면에 두는 것이다. */}
      {!co || (running && co.status === "ACTIVE") ? null : (
        <section
          aria-label={health?.label ?? RESPONSIBILITY_NAME}
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
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-line pb-2">
            {/* <b>The brief is named for what it is, not for the screen it draws from</b> (Home visual
                target, 2026-10-01). It was 「확인할 일」 — the name of the queue in the rail, of the queue
                screen, and of the summary cell 40px above — so the first screen carried the same four
                syllables three times and read as an abbreviated copy of the list it links to. These four
                rows are a brief: the oldest of the work, drawn here so the morning starts somewhere. */}
            <h2 className="text-section font-bold tracking-tight text-ink">{HOME_BRIEF_TITLE}</h2>
            <span className="break-keep text-sm text-muted">
              {/* The order, which is the only thing the seller has to know to read the four rows — and
                  what every row says identically, said once instead of twice on each of them. */}
              {HOME_BRIEF_NOTE}
              {sharedSaid ? ` · ${sharedSaid}` : ""}
            </span>
            {/* <b>The breakdown is the queue's, not the brief's</b> (product-owner decision, 2026-10-01).
                Measured at 1600×1000 this heading read 「확인할 일 오래된 순 정보 부족 1 · 답변 필요 25 ·
                리뷰 12 · 승인 대기 4 · 초안 필요 4」 — five tallies over a list showing five rows, and every
                one of them is a filter chip on 확인할 일, drawn there as a control a seller can press. */}
            {hidden > 0 || work.truncated ? (
              <Link
                to="/customer-operations/cases"
                className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
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
      ) : !co || (running && co.status === "ACTIVE") ? (
        // Nothing waiting is a state, and the list's absence does not state it. 「다음 확인」 is on the status
        // line above, where it was before this became one line. 자동 확인이 없는 배포에서도 같은 문장을
        // 적는다 — 빈 자리는 「없다」와 「읽지 못했다」를 구분해 주지 않는다(읽기 실패는 채널 줄이 말한다).
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
      {/*
        <b>「오늘 무엇이 달라졌나」 is one question, so it is one section</b> (Home visual target, 2026-10-01).

        <p>오늘 들어온 것 and 최근 24시간 stood as a free line between the summary band and the work — the
        position the target gives nothing — and 오늘 달라진 점 stood 350px below them under a heading that
        asks exactly what they answer. Measured, the two blocks cost 157px of a 911px screen to say one
        thing in two places. The line is now this section's note, under its heading, in the order the
        directive fixed: what changed, then what we noticed about it.
      */}
      {/* <b>오늘 달라진 점 means a change proven by comparing two windows</b> (product-owner decision,
          2026-10-01). {@link changeInsights} keeps only the insights that state both their own window
          and the one they were measured against — not a string match, not a re-ranking, and
          deliberately NOT 「observed today」, which a standing disconnection satisfies every morning.
          Collection health belongs to 채널 상태 at the foot of this screen; everything else that is
          dropped is the overview's, which is where this section's one navigational link goes. */}
      <WhatChanged
        insights={changeInsights(insights, now)}
        context={<ContextLine inflow={todayInflow(metrics, now)} recent={co ? recentDay(co) : null} />}
      />

      <div className="grid gap-x-6 gap-y-6 md:grid-cols-2">
        <AwaitingExecution awaiting={awaiting} selection={selection} />
        <RepeatedProblems ops={ops} selection={selection} />
      </div>

      <ChannelState warnings={warnings} unproven={freshnessUnproven(metrics)} collection={ops?.collection} now={now} />
    </div>
  );
}

/**
 * <b>「오늘 무엇이 달라졌나」 — the first of the morning's five questions</b> (Home visual target, 2026-10-01).
 *
 * <p>Every word here is {@link OperationsInsight}'s, printed verbatim: the backend composes the sentence,
 * the supporting line, the destination and the label of the way out. This component derives nothing,
 * counts nothing and compares nothing — a Home that worked out for itself what had changed would be a
 * second opinion about a window the overview read already owns.
 *
 * <p><b>It is not a card and carries no fill.</b> §8-A has said since v3.2 that what the automation
 * noticed is 「약한 한 줄」; the target draws it as a ruled block under the work, and that is what this is.
 * `severity` is deliberately not drawn as colour — ATTENTION and WATCH are the extractor's judgement about
 * a trend, not a state waiting for the seller, and a tinted row here would compete with the state badges
 * on the rows above it.
 */
function WhatChanged({
  insights,
  context,
}: {
  /** Already filtered to the changes ({@link changeInsights}). `null` = the read did not land. */
  insights?: OperationsInsight[] | null;
  /** {@link ContextLine} — 오늘 들어온 것 · 최근 24시간. It renders itself away when it has nothing. */
  context: ReactNode;
}) {
  const shown = insights ? insights.slice(0, HOME_CHANGED_ROWS) : [];
  // A failed read says nothing at all. Either half of a landed read is enough to ask the question —
  // and a landed read with nothing in it ANSWERS it, which is why an empty day still draws.
  if (insights === null || insights === undefined) {
    if (context === null) return null;
  }
  return (
    <section aria-label="오늘 달라진 점">
      {/* The heading, its note and the ONE way out. <b>Two links at most in this section</b>
          (product-owner decision, 2026-10-01): it carried three — 자세한 숫자 보기 inside the note,
          「N건 전체 보기」 here, and the insight's own action on the row — and the first two went to the
          same screen. The note's link is gone; what remains is one navigation and one contextual
          action, which is the ceiling. */}
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">오늘 달라진 점</h2>
        {context}
        {/*
          <b>The count is CHANGES, and only the ones this cap hid</b> (product-owner decision,
          2026-10-01).

          <p>It counted every insight the read returned, which after the filter meant 「2건 전체 보기 →」
          could stand on the same line as 「오늘 새로 확인된 변화는 없습니다」 — the section denying and
          offering the same two things at once. Those two were a standing backlog and a broken
          connector; they are not changes, they are not this section's, and they are not hidden here.

          <p>So the link appears only when a change was found and the one-row cap kept it off screen,
          which is the layout decision it has always been about. With nothing hidden the section
          carries no navigation at all and the answer is the sentence.
        */}
        {(insights?.length ?? 0) > shown.length ? (
          <Link
            to="/overview"
            aria-label={`운영 숫자에서 변화 ${insights!.length.toLocaleString("ko-KR")}건 전체 보기`}
            className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            {insights!.length.toLocaleString("ko-KR")}건 전체 보기 →
          </Link>
        ) : null}
      </div>
      {shown.length === 0 ? (
        /*
          <b>An empty day is answered, not left blank</b> (product-owner decision, 2026-10-01).
          「변화가 없다」 and 「우리가 보지 않았다」 are different facts and a seller cannot tell them apart
          from an absence — the same rule 반복 문제 follows. This only ever prints when the read landed;
          {@link NO_CHANGE_TODAY} claims nothing about any window but today.
        */
        insights ? <p className="mt-2 break-keep text-sm text-muted">{NO_CHANGE_TODAY}</p> : null
      ) : (
        <ul className="mt-2 space-y-3">
          {shown.map((insight) => (
            <li key={insight.key} className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="min-w-0 flex-1">
                <span className="block break-keep font-semibold text-ink">{insight.title}</span>
                {insight.detail ? (
                  <span className="mt-0.5 block break-keep text-sm text-muted">{insight.detail}</span>
                ) : null}
              </span>
              {/* The label is the server's, so the link never promises an action this screen cannot
                  perform — `agentGoal` is a question a human may send and is deliberately not drawn as
                  a control. This is the section's one contextual action. */}
              <Link
                to={insight.to}
                className="shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {insight.actionLabel} →
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The 새로고침 mark. Stroke-only on `currentColor`, so the control's own `outline` variant colours it. */
function RefreshIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-4 w-4">
      <path
        fill="none"
        stroke="currentColor"
        strokeWidth={1.8}
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M20 12a8 8 0 10-2.3 5.7M20 12V7m0 5h-5"
      />
    </svg>
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
function ChannelState({
  warnings,
  unproven,
  collection,
  now,
}: {
  warnings: React.ReactNode[];
  unproven: boolean;
  /** The Home's own coverage read. Absent (a failed or unlanded read) draws no freshness at all. */
  collection?: ChannelCoverageRowView[];
  now: Date;
}) {
  const freshness = channelFreshness(collection, now);
  return (
    <section
      aria-label="채널 상태"
      className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-line pt-4 text-sm"
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
        <p className="flex flex-wrap items-center gap-x-2 break-keep text-muted">
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
      {/*
        <b>When each channel was last read — whichever branch answered above</b> (Home visual target,
        2026-10-01).

        <p>It sat inside the 「문제 없음」 branch, so the moment one channel had a gap the row stopped
        saying when ANY of them was last looked at — the screen withheld the evidence exactly where a
        seller would want to check it. It is the coverage read's own `lastSuccessfulSyncAt`, the latest
        per channel; a channel that has never synced keeps its place on the line and says so.
      */}
      {freshness.length > 0 ? (
        <p className="flex flex-wrap items-center gap-x-2 break-keep text-muted">
          {freshness.map((channel, i) => (
            <Fragment key={channel.name}>
              {i > 0 ? <Sep /> : null}
              <span className="whitespace-nowrap">
                {channel.name} {channel.ago}
              </span>
            </Fragment>
          ))}
        </p>
      ) : null}
    </section>
  );
}

/**
 * The latest successful collection per channel, as 「N일 전」.
 *
 * <p>The coverage read is one row per (channel × dataType) — nine of them on a three-channel org — and a
 * freshness line with nine entries is a table. A channel is as recently read as its most recently read
 * data type, so the rows fold by channel on `lastSuccessfulSyncAt`'s maximum. A channel whose every row
 * has never synced keeps its place on the line and says so: dropping it would make the line shorter
 * precisely where it has the most to report.
 */
function channelFreshness(
  collection: ChannelCoverageRowView[] | undefined,
  now: Date,
): { name: string; ago: string }[] {
  if (!collection || collection.length === 0) return [];
  const latest = new Map<string, number | null>();
  for (const row of collection) {
    const at = row.lastSuccessfulSyncAt ? new Date(row.lastSuccessfulSyncAt).getTime() : NaN;
    const value = Number.isNaN(at) ? null : at;
    // The short name, which is the one every other surface on this screen uses — 「네이버 스마트스토어」
    // three times over wrapped the row onto a second line and made the question look like a report.
    // `channelShort` returns null for a name it does not recognise; the channel keeps its own name then
    // rather than disappearing from a line whose whole job is naming every channel.
    const name = channelShort(row.channelNameKo) ?? row.channelNameKo;
    const seen2 = latest.get(name);
    if (seen2 === undefined) latest.set(name, value);
    else if (value !== null && (seen2 === null || value > seen2)) latest.set(name, value);
  }
  return [...latest.entries()].map(([name, at]) => ({ name, ago: at === null ? "수집 기록 없음" : agoLabel(at, now) }));
}

/** 「N분 전」 — the same thresholds {@link waitLabel} uses, said about a read instead of about a wait. */
function agoLabel(at: number, now: Date): string {
  const minutes = Math.max(0, Math.floor((now.getTime() - at) / 60_000));
  if (minutes < 1) return "방금 전";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
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
  insights,
  dock,
}: {
  /** {@link CustomerOpsHome}와 같다 — 그 일이 열려 있지 않으면 {@code null}이고, 골격은 그대로 선다. */
  co: CustomerOperationsHome | null;
  ops: OperationsHome | null | undefined;
  now?: Date;
  onChanged: () => void;
  /**
   * The overview read the page around this list already made (`getOverviewStrict(7)`). Only the summary
   * line reads it, and only to answer 「오늘 들어온 것」 — a failed read is `null` and says nothing.
   */
  metrics?: OperationsMetrics | null;
  /** The insight half of the same overview read — see {@link CustomerOpsHome}'s own `insights`. */
  insights?: OperationsInsight[] | null;
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
          insights={insights}
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
  /*
    <b>Three columns, always, and an unmeasured one says so</b> (product-owner decision, 2026-10-01).

    <p>Each cell used to be dropped when its fact was not a number, which left the band with a track of
    empty page where a figure belongs — and an empty slot is the one thing a seller cannot read. It looks
    like a value that has not loaded, like a zero, or like nothing at all, and the three are different
    facts. The cell now always stands and says which: a measured figure, or 「—」 with the reason under it.

    <p><b>No cell ever estimates.</b> 「—」 is the only thing that may stand where a count would, and the
    note is the sentence the product already uses for that cause — never a figure derived from a
    neighbouring read, a previous window, or a partial population.
  */
  const cells: ReactNode[] = [
    <Cell key="work" id="work" label={COPY.listTitle}>
      {/* A measured zero is a figure: the list read landed and found nothing. The server saying there are
          more than it sent makes this total a floor — 「11+」건. */}
      <Big value={work.rows.length} suffix={work.truncated ? "+" : ""} unit={COUNT_UNIT} />
    </Cell>,
    <Cell
      key="awaiting"
      id="awaiting"
      label="실행 대기"
      note={awaiting === null ? "자동 확인이 멈춰 있어 확인하지 못했습니다" : undefined}
    >
      {awaiting === null ? (
        <Unknown />
      ) : awaiting === 0 ? (
        // A measured zero, not an unknown: the job is running and it found nothing pending.
        <Big value={0} unit={COUNT_UNIT} />
      ) : (
        // The label above is a separate span, so without a name of its own this link announces a bare
        // number. The noun rides with it.
        <a
          href="#실행-대기"
          aria-label={`실행 대기 ${awaiting.toLocaleString("ko-KR")}${COUNT_UNIT}`}
          className="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          <Big value={awaiting} unit={COUNT_UNIT} />
        </a>
      )}
    </Cell>,
    <Cell
      key="unanswered"
      id="unanswered"
      label={UNANSWERED_WORD.lead}
      note={
        unanswered === null
          ? "숫자를 읽지 못했습니다"
          : unanswered.kind === "COUNT"
            ? undefined
            : // The population is incomplete, so there is no honest count — the cause, named, and the
              // same words the channel row at the foot of the screen uses for it.
              "수집 상태 확인 필요"
      }
    >
      {unanswered?.kind === "COUNT" ? (
        <Link
          to="/inquiries"
          aria-label={`${UNANSWERED_WORD.lead} ${unanswered.value.toLocaleString("ko-KR")}${COUNT_UNIT}`}
          className="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          <Big value={unanswered.value} unit={COUNT_UNIT} />
        </Link>
      ) : (
        <Unknown />
      )}
    </Cell>,
  ];

  return (
    /*
      <b>The band is ruled, not filled</b> (Home visual target, 2026-10-01).

      <p>It was a `bg-canvas rounded-xl` box at `max-w-3xl` — a grey card two thirds of the way across a
      1120px column, which is the one shape §8-A spends on 「대시보드」 and the one thing the composition
      pass was asked to keep off this screen. The target draws the same three cells as a full-width strip
      between two hairlines: no fill, no radius, no inset of its own.
    */
    <section
      data-testid="today-summary"
      aria-label={PULSE_LABEL}
      className="grid grid-cols-3 divide-x divide-line border-b border-line pb-4"
    >
      {cells}
    </section>
  );
}

/**
 * <b>미관측은 숫자가 아니다.</b> The one mark that may stand where a figure would, at the figure's size
 * and never with its ink or its weight — those two are what this product spends on a measured value, and
 * what this says is that there is not one. The note under it names the cause.
 */
function Unknown() {
  return (
    <span aria-label="값 없음" className="font-normal text-muted">
      —
    </span>
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

  /*
    <b>No collection health on this line</b> (product-owner decision, 2026-10-01 — the last ownership
    leak).

    <p>This line is the note of 오늘 달라진 점, and it was printing 「수집 상태 확인 필요」 /
    「리뷰 수집 확인 필요」 / 「문의 수집 확인 필요」 — the reason a figure is withheld, which is a fact
    about collection and not about what changed. The Home already answers 「채널은 정상인가」 at the foot
    of the screen ({@link ChannelState}: the gaps, the incomplete sources and the freshness line), and
    two surfaces answering one question in two voices is how one of them ends up reassuring a seller
    the other is warning.

    <p><b>Nothing becomes a lie by being removed.</b> The withheld figure is still withheld — an
    unqualified lane prints no number here, exactly as before — and what is gone is only the second
    place the REASON was stated. 예시 데이터 stays: it qualifies a figure that IS shown, which is this
    line's own business.
  */
  if (inflow) {
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
      if (inflow.exampleData) facts.push(<span key="example">{INFLOW_WORD.exampleData}</span>);
    }
  }

  if (recent && recent.checked > 0) {
    /*
      <b>「새로 확인한 일 없음」 is gone too</b> (same decision). It is the automation's report of a quiet
      window — and a quiet window is not a change either. When nothing was opened this line simply has
      one fewer fact, and if it has none the section says the one sentence it is for.
    */
    facts.push(
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
      </span>,
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
  /*
    <b>No link here any more</b> (product-owner decision, 2026-10-01 — CTA 최대 2개).

    <p>This line is the note of 오늘 달라진 점, and 「자세한 숫자 보기」 pointed at `/overview` — the same
    destination as the heading's own 「N건 전체 보기 →」 standing on the same line. Two links to one
    screen, 300px apart, plus the insight row's own action made three competing CTAs in one section.
    The way to the numbers is the heading's; this line states facts and nothing else.
  */
  return (
    <p
      data-testid="today-context"
      aria-label="운영 상황"
      className="flex min-w-0 flex-wrap items-center gap-x-1 gap-y-1 break-keep text-sm text-muted"
    >
      {dotted(facts)}
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
      <span className="mt-1 block text-title">{children}</span>
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
 * The figure itself — the only ink and the only weight this surface spends. The unit rides with it so a
 * line can never break between a number and the thing it counts.
 */
function Big({ value, suffix = "", unit }: { value: number; suffix?: string; unit?: string }) {
  return (
    <span className="whitespace-nowrap">
      <span className="font-bold tabular-nums text-ink">
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

/**
 * How many changes 오늘 달라진 점 draws.
 *
 * <p>One, which is what the target mockup draws, and it is a budget rather than an opinion: measured at
 * 1600×1000 the live org returns three, and three of them ran 채널 상태 to y=1140 — a full 240px under
 * the composer, on the screen whose whole purpose is that the five questions are answered without
 * scrolling. The total and the way to the rest ride on the heading.
 */
const HOME_CHANGED_ROWS = 1;

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
    <section aria-label="실행 대기" id="실행-대기" className="rounded-2xl border border-line bg-surface p-3">
      {/* The badge that used to sit here said 「승인함 · 등록 전」 over every row. It is what a standing
          review approval is, and what an inquiry row is not — those arrive with the work item still
          PROPOSED and no approval anywhere. One badge cannot be true of both, so the state moved onto
          the rows, where each one can say its own (see lib/preparedState.ts). */}
      {/* No `mt-8`: this section is one half of a two-column band now, and a top margin on one of two
          grid children stops their headings sharing a baseline. The band's own `gap-y` owns the space. */}
      {/* <b>The total rides on the heading, because the list below it is capped.</b> The brief draws the
          first two and says so; the number the seller owes is the section's, not the list's, and it is the
          same figure the summary band's own cell links down to. */}
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">실행 대기</h2>
        <span className="break-keep text-sm tabular-nums text-muted">
          {count > shown.length
            ? `${count.toLocaleString("ko-KR")}건 중 ${shown.length.toLocaleString("ko-KR")}건`
            : `${count.toLocaleString("ko-KR")}건`}
        </span>
        <Link
          to="/reviews"
          className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          전체 보기 →
        </Link>
      </div>
      <p className="mt-1 break-keep text-sm text-muted">이미 결정한 일입니다. 등록만 남았습니다.</p>
      <PreparedWorkList
        bare
        rows={shown}
        selectedId={wide ? selectedId : null}
        // An approved review reply opens in the pane — the Review Case, where the approved text and its copy are.
        linkFor={(row) => (row.kind === "REVIEW_REPLY" ? selectionHref(wide, `${PREPARED}${row.id}`, row.to, search) : row.to)}
      />
      {/* The 「승인한 리뷰 답변 N건 더 보기」 line that stood here is the heading's own count and link now:
          `count` has always included {@link moreReplies}, so 「4건 중 2건 · 전체 보기 →」 states the same
          two facts — how many there are and where the rest of them are — on the line that names the
          section, which is where the target puts them. */}
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
      /*
        <b>Nothing to show is one line, not a card</b> (product-owner decision, 2026-10-01).

        <p>A bordered 2xl card holding a heading and one grey sentence spent a card's worth of weight
        — border, radius, fill, 12px of padding — on an absence, in a band whose other half is real
        work. The compact reading keeps every fact: that there is nothing now, how many were collected
        before, and the way to them.

        <p><b>It does not name the window.</b> 「최근 7일」 would be a second copy of
        `ReviewIssueThresholds.PERSIST_LOOKBACK_WEEKS` in Korean prose, and that copy goes stale the
        day the threshold moves — the same reason {@link problemLine} has never printed it. The
        sentence it composes distinguishes 「없다」 from 「전에는 있었다」, which is the distinction a
        seller reads this for.
      */
      <section
        aria-label="반복 문제"
        className="flex flex-wrap items-baseline gap-x-2 gap-y-1 self-start text-sm"
      >
        <span className="shrink-0 font-semibold text-ink">반복 문제</span>
        <span className="min-w-0 break-keep text-muted">{problemLine(problems)}</span>
        <Link
          to="/memory"
          className="shrink-0 font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          보기 →
        </Link>
      </section>
    );
  }
  const wide = selection?.wide ?? false;
  const search = selection?.search ?? "";
  const selectedId = selection?.selectedKey?.startsWith(PROBLEM) ? selection.selectedKey.slice(PROBLEM.length) : null;
  return (
    <section aria-label="반복 문제" className="rounded-2xl border border-line bg-surface p-3">
      <ProblemHeading problems={problems} />
      <RepeatedProblemList
        bare
        rows={problems.rows}
        selectedId={wide ? selectedId : null}
        linkFor={(issueId) => selectionHref(wide, `${PROBLEM}${issueId}`, `/memory/${issueId}`, search)}
      />
      {/* 「반복 문제 전체 보기」 is the heading's own 전체 보기 → now, the same destination on the line
          that names the section — the shape the target gives both cards. */}
    </section>
  );
}

/**
 * The card's heading line: the name, the server's own two counts, and the way to the whole set.
 *
 * <p>One component because the section has two bodies — rows, and the 「아직 없습니다」 that answers the
 * morning's question when there is nothing to draw — and a heading written twice is a heading that drifts
 * once. The counts are {@link problemLine}'s string, printed verbatim; nothing here derives a number.
 */
function ProblemHeading({ problems }: { problems: HomeRepeatedProblems }) {
  return (
    <>
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">반복 문제</h2>
        <Link
          to="/memory"
          className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          전체 보기 →
        </Link>
      </div>
      {/* The server's own two counts, as the sentence {@link problemLine} composes — it is a sentence and
          not a tally, so it stands on the card's note line where the other card's note stands, rather
          than beside the heading where it was the longest thing on the line. Same string, same source. */}
      <p className="mt-1 break-keep text-sm text-muted">{problemLine(problems)}</p>
    </>
  );
}
