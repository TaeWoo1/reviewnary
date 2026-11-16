import { TalkTalkHomeLine } from "../../components/talktalk/TalkTalkHomeLine";
import { useCallback, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { WorkRows } from "../../components/workspace/WorkRows";
import { WAITING_ANCHOR } from "../../components/home/WaitingWork";
import { AgentHome } from "./AgentHome";
import { api } from "../../lib/apiClient";
import { useApiData } from "../../lib/useApiData";
import { useScreenChanges } from "../../lib/screenChanges";
import { useAgentSurface } from "../../lib/agentPanel";
import { homeFirstUseState } from "../../lib/homeFirstUse";
import { boardRow, tallyText, tallyWithUnit, type Tally } from "../../lib/homeBoard";
import { channelFigure, channelHealth, firstEmptyLine, kpiTally, kstClock, sumTally, workReadWhole, type ChannelFigure, type ChannelHealth } from "../../lib/csHome";
import { channelShort } from "../../lib/copy/customerOps";
import type { ChannelMetricRow } from "../../lib/types";

/**
 * <b>홈 — CS 현황</b> (product-owner decision 2026-11-13; `docs/pilot/pilot_usage_loop_v1.md` §8).
 *
 * <p>What one person running several sales channels needs on opening the product: how much came in and how much is
 * left (four numbers), what to do first (the work list's own first rows, in the server's order), what is still open
 * after a reply (기다리는 일), which channel needs a look and whether its numbers can be trusted (the channel table,
 * full width — the reason a multi-channel service exists), and what customers keep raising (repeated review problems,
 * a side note). Performance figures — time saved, automation rate, response time — are not here; they belong to a
 * separate screen once they can be measured.
 *
 * <p><b>Every number opens the list it counts, under the same condition</b>, and is written the board's one way:
 * exact, 「N+」 when only a floor is vouched for, 「—」 when nothing is. Each read stands alone: one that fails says so
 * in its own place and never becomes a zero. Nothing on this screen sends, approves or starts a collection — the
 * channel buttons are links to the pages that already own those fixes.
 *
 * <p>Before the first connection the screen is still the one that says what to connect ({@link AgentHome}).
 */
export function CsHome({ now = new Date() }: { now?: Date }) {
  useAgentSurface({ surface: "home", label: "CS 현황" });
  const changes = useScreenChanges();
  const [refresh, setRefresh] = useState(0);
  // Phone only: the tiles' term definitions fold behind one button, so the work list's first row reaches the first
  // screen. Folded, never removed — and from `md` up they are always shown.
  const [notesOpen, setNotesOpen] = useState(false);
  const tick = changes + refresh;

  const overview = useApiData(() => api.getOverviewStrict(1), [], tick);
  const board = useApiData(() => api.getHomeBoard(), [], tick);
  const first = useApiData(() => api.getHomeWork({ bucket: "all", sort: "importance", size: 4 }), [], tick);
  const coverage = useApiData(() => api.getChannelCoverageStrict(), [], tick);
  const accounts = useApiData(() => api.getSellerAccountsStrict(), [], tick);
  const ops = useApiData(() => api.getOperationsHomeStrict(), [], tick);
  const co = useApiData(() => Promise.resolve().then(() => api.getCustomerOperationsHome()), [], tick);
  const reload = useCallback(() => setRefresh((n) => n + 1), []);

  const metrics = overview.data?.metrics ?? null;
  const firstUse = metrics ? homeFirstUseState(metrics.channels) : null;
  const channels = useMemo(
    () => (metrics?.channels ?? []).filter((row) => row.connectable || row.connected),
    [metrics],
  );
  const gaps = co.data?.available ? co.data.gaps.rows : null;
  const health = useMemo(
    () => new Map(channels.map((row) => [row.channelCode, channelHealth(row, coverage.data, accounts.data, gaps, now)])),
    [channels, coverage.data, accounts.data, gaps, now],
  );

  // No marketplace connected → the first-use home. TalkTalk is not a marketplace channel, so a TalkTalk-only store
  // lands here too, and its waiting conversations go under that screen's title (the line hides itself otherwise).
  if (firstUse?.kind === "NO_CHANNEL") return <AgentHome now={now} notice={<TalkTalkHomeLine refresh={tick} />} />;

  const kpi = (key: string) => kpiTally(metrics?.kpis.find((k) => k.key === key));
  const inquiries = kpi("inquiries");
  const reviews = kpi("reviews");
  const came = sumTally(inquiries, reviews);
  const day = metrics?.period.to ?? null;
  const todo = board.data?.todo ?? null;
  const answer = todo?.answer ?? null;
  const waiting = board.data?.waiting ?? null;
  const troubled = channels.filter((row) => health.get(row.channelCode)?.tone === "warn");
  const anyUnknown = channels.some((row) =>
    (["inquiries", "reviews", "unanswered"] as const).some((w) => {
      const f = channelFigure(row, w);
      return !f.unsupported && f.tally.count == null && f.tally.atLeast === 0;
    }),
  );
  const firstRows = first.data?.rows.map(boardRow) ?? [];
  const firstWhole = workReadWhole(first.data);
  // A re-read that failed keeps the previous answer on screen — said here, by the part it left behind, never silently.
  const stale = [
    overview.refreshFailed ? "오늘 숫자·채널별 숫자" : null,
    board.refreshFailed ? "할 일 숫자" : null,
    first.refreshFailed ? "먼저 처리할 일" : null,
    coverage.refreshFailed || accounts.refreshFailed || co.refreshFailed ? "수집 상태" : null,
    ops.refreshFailed ? "반복된 문제" : null,
  ].filter((x): x is string => x != null);
  const refreshing = [overview, board, first, coverage, accounts, ops, co].some((r) => r.refreshing);

  return (
    // The shell pads every page 24px above and 32px below; this one spends a little less so the whole screen stands
    // on a 1280×720 laptop without scrolling (owner condition, 2026-11-13). A phone scrolls, but its first screen
    // still owes the seller the first piece of work (2026-11-14): 8px less above and 10px gaps there — spacing only,
    // never a smaller letter.
    <div className="-mt-2 space-y-2.5 md:-mb-6 md:space-y-3" data-testid="cs-home">
      <header className="flex flex-wrap items-end gap-x-4 gap-y-1">
        <h1 className="text-title font-bold tracking-tight text-ink">CS 현황</h1>
        {day ? <p className="pb-1 text-sm text-muted">{dayLabel(day)}</p> : null}
        <p className="ml-auto flex flex-wrap items-center justify-end gap-x-2 pb-0.5 text-sm text-muted">
          {stale.length > 0 ? (
            <span role="alert" className="break-keep font-semibold text-warn" data-testid="cs-stale">
              새로고침하지 못했어요: {stale.join(", ")}. 이전에 불러온 그대로예요.
            </span>
          ) : null}
          {kstClock(board.data?.asOf) ? <span>{kstClock(board.data?.asOf)}에 마지막으로 확인</span> : null}
          <Btn size="sm" variant="ghost" onClick={reload} disabled={refreshing} data-testid="cs-refresh">
            {refreshing ? "불러오는 중" : "새로고침"}
          </Btn>
        </p>
      </header>

      {/* 네 숫자 — 관점이 달라 더하지 않는다. 각 숫자는 같은 조건의 목록을 연다. */}
      <nav aria-label="오늘 숫자" className="grid grid-cols-2 overflow-hidden rounded-xl border border-line bg-surface lg:grid-cols-4" data-testid="cs-tiles">
        <Tile label="오늘 접수" value={came} failed={overview.error} testId="tile-received">
          {came && came.count == null && came.atLeast === 0 ? (
            <span>오늘 가져온 채널이 없어 셀 수 없어요</span>
          ) : inquiries && reviews && day ? (
            <>
              <Link to={`/inquiries?from=${day}&to=${day}`} className={SUB_LINK} data-testid="received-inquiries">
                문의 {tallyWithUnit(inquiries)}
              </Link>
              <Link to="/reviews?days=1" className={SUB_LINK} data-testid="received-reviews">
                리뷰 {tallyWithUnit(reviews)}
              </Link>
            </>
          ) : null}
        </Tile>
        <Tile label="답할 일" value={answer?.total ?? null} failed={board.error} to="/work?work=answer" testId="tile-answer">
          {answer ? <span>리뷰 {tallyWithUnit(answer.reviews)}, 문의 {tallyWithUnit(answer.inquiries)}</span> : null}
        </Tile>
        <Tile label="처리 진행 중" value={waiting?.total ?? null} failed={board.error} to={`/work#${WAITING_ANCHOR}`} testId="tile-waiting" note="답변 등록이나 후속 처리가 남아 있어요" notesOpen={notesOpen} />
        <Tile label="오늘 확인" value={todo?.todayChecks ?? null} failed={board.error} to="/work?work=todayChecks" testId="tile-today" note="확인하기로 한 날이 된 기록이에요" notesOpen={notesOpen} />
      </nav>
      <button
        type="button"
        className="-mt-1 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 md:hidden"
        aria-expanded={notesOpen}
        aria-controls="tile-waiting-note tile-today-note"
        onClick={() => setNotesOpen((v) => !v)}
        data-testid="cs-tile-notes"
      >
        {notesOpen ? "숫자 설명 접기" : "숫자 설명 보기"}
      </button>

      <TalkTalkHomeLine refresh={tick} />

      <section aria-labelledby="cs-channels-h" className="rounded-xl border border-line bg-surface" data-testid="cs-channels">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 pt-3">
          <h2 id="cs-channels-h" className="text-lg font-bold tracking-tight text-ink">
            채널별 CS 현황
          </h2>
          {/* 수집 이상 안내 — 문제가 있을 때만, 그 문제가 사는 표의 제목 줄에. */}
          {troubled.length > 0 ? (
            <p role="status" className="flex min-w-0 flex-wrap gap-x-3 text-sm" data-testid="cs-gap">
              {troubled.map((row) => (
                <span key={row.channelCode} className="break-keep font-semibold text-warn">
                  {channelName(row)} {health.get(row.channelCode)?.badge}
                </span>
              ))}
              <span className="break-keep text-muted">「N+」는 확인한 것만 센 수예요.{anyUnknown ? " 「—」는 셀 수 없는 칸이에요." : ""}</span>
            </p>
          ) : (
            <p className="text-sm text-muted">오늘 들어온 수예요. 미답변 문의는 지금 기준이에요.</p>
          )}
        </div>
        {overview.error ? (
          <p className="px-4 pb-4 pt-2 text-sm text-muted">채널별 숫자를 불러오지 못했어요.</p>
        ) : (
          <ChannelTable rows={channels} health={health} />
        )}
      </section>

      {/* 시안 그대로: 왼쪽 큰 업무 목록(2), 오른쪽 세로 두 칸(1). */}
      {/* `minmax(0,1fr)`: an implicit `auto` track grows to its widest row, and at phone width that pushed the button,
          the counts and 「모두 보기」 past the screen's right edge (measured at 390, 2026-11-14). */}
      <div className="grid grid-cols-[minmax(0,1fr)] gap-2.5 md:gap-3 lg:grid-cols-3">
        <section aria-labelledby="cs-first-h" className="min-w-0 rounded-xl border border-line bg-surface px-4 py-3 lg:col-span-2" data-testid="cs-first">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="cs-first-h" className="text-lg font-bold tracking-tight text-ink">
              먼저 처리할 일
            </h2>
            {todo ? <span className="text-sm tabular-nums text-muted">할 일 {tallyWithUnit(todo.total)} 중 중요한 순서로</span> : null}
            {/* On a phone the button goes under the list instead of wrapping onto a line of its own above it. */}
            <BtnLink to="/work" size="sm" className="ml-auto max-md:hidden">
              할 일 시작하기
            </BtnLink>
          </div>
          {first.error ? (
            <p className="mt-3 text-sm text-muted">할 일을 불러오지 못했어요. 할 일 화면에서 다시 볼 수 있어요.</p>
          ) : first.data && firstRows.length === 0 ? (
            <p className="mt-3 break-keep text-sm text-muted" data-testid="cs-first-empty">
              {firstEmptyLine(firstWhole, troubled.length > 0)}
            </p>
          ) : firstRows.length > 0 ? (
            <div className="mt-2">
              <WorkRows rows={firstRows} selectedKey={null} wide={false} search="" now={now} ariaLabel="먼저 처리할 일" showBacklogDivider={false} dense oneLine reading="home" />
              {!firstWhole ? (
                <p className="mt-1.5 break-keep text-sm text-muted" data-testid="cs-first-partial">
                  할 일을 일부만 불러왔어요. 더 먼저 할 일이 있을 수 있어요.
                </p>
              ) : null}
            </div>
          ) : null}
          <BtnLink to="/work" className="mt-3 w-full justify-center md:hidden">
            할 일 시작하기
          </BtnLink>
        </section>

        <div className="flex min-w-0 flex-col gap-2.5 md:gap-3">

          <section aria-labelledby="cs-after-h" className="rounded-xl border border-line bg-surface px-4 py-3" data-testid="cs-after">
            <div className="flex items-baseline gap-2">
              <h2 id="cs-after-h" className="text-lg font-bold tracking-tight text-ink">
                답변 후 남은 일
              </h2>
              {waiting ? <span className="text-sm tabular-nums text-muted">{tallyWithUnit(waiting.total)}</span> : null}
            </div>
            {board.error ? (
              <p className="mt-2 text-sm text-muted">불러오지 못했어요.</p>
            ) : waiting ? (
              <ul className="mt-2 divide-y divide-line">
                <AfterRow label="조치 진행 중" note="교환·환불·발송" tally={waiting.action} />
                <AfterRow label="고객 회신 대기" note="고객 답을 기다림" tally={waiting.customer} />
                <AfterRow label="답변 등록 확인 중" note="채널에 올라갔는지" tally={waiting.registration} />
              </ul>
            ) : null}
          </section>

          <section aria-labelledby="cs-problem-h" className="rounded-xl border border-line bg-surface px-4 py-3" data-testid="cs-problems">
            <div className="flex items-baseline gap-x-2">
              <h2 id="cs-problem-h" className="text-lg font-bold tracking-tight text-ink">
                리뷰에서 반복된 문제
              </h2>
              <Link to="/memory" className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
                모두 보기
              </Link>
            </div>
            <p className="mt-0.5 break-keep text-sm text-muted">최근 6주에도 나온 문제, 건수는 전체 기간</p>
            {ops.error ? (
              <p className="mt-2 text-sm text-muted">불러오지 못했어요.</p>
            ) : ops.data && ops.data.problems.rows.length === 0 ? (
              <p className="mt-2 text-sm text-muted">요즘 반복되는 문제는 없어요.</p>
            ) : ops.data ? (
              <ul className="mt-1 divide-y divide-line">
                {ops.data.problems.rows.slice(0, 2).map(({ issue, context }) => {
                  const top = context?.evidence?.byProduct?.[0];
                  return (
                    <li key={issue.id}>
                      <Link
                        to={`/memory/${issue.id}`}
                        className="flex items-baseline gap-2 py-1.5 hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
                      >
                        <span className="shrink-0 font-semibold text-ink">{issue.title}</span>
                        <span className="min-w-0 flex-1 truncate text-sm text-muted">{top?.productName ?? ""}</span>
                        <span className="shrink-0 font-semibold tabular-nums text-ink">{issue.evidenceCount.toLocaleString("ko-KR")}건</span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            ) : null}
          </section>
        </div>
      </div>
    </div>
  );
}

const SUB_LINK =
  "font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700";

/** 「11월 13일 금요일」 from the server's own Korean day. */
function dayLabel(day: string): string {
  const [y, m, d] = day.split("-").map(Number);
  const w = "일월화수목금토"[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return `${m}월 ${d}일 ${w}요일`;
}

function channelName(row: ChannelMetricRow): string {
  return channelShort(row.channelCode) ?? row.channelNameKo;
}

/** A number, its name, a short line. Linked when its list exists; 「—」 when its read failed — never 0. */
function Tile({ label, value, failed, to, testId, note, notesOpen = false, children }: {
  label: string;
  value: Tally | null;
  failed: boolean;
  to?: string;
  testId: string;
  /** What the term means. Always shown from `md` up; on a phone it folds behind 「숫자 설명 보기」. */
  note?: string;
  notesOpen?: boolean;
  children?: ReactNode;
}) {
  const figure = (
    <span className="text-2xl font-bold tabular-nums tracking-tight text-ink">
      {failed || !value ? "—" : tallyText(value)}
      {!failed && value && tallyText(value) !== "—" ? <span className="ml-0.5 text-base font-normal">건</span> : null}
    </span>
  );
  return (
    <div className="flex min-w-0 flex-col gap-0.5 border-line px-4 py-2 [&:nth-child(n+3)]:border-t lg:[&:nth-child(n+3)]:border-t-0 [&:nth-child(even)]:border-l lg:[&:not(:first-child)]:border-l" data-testid={testId}>
      {to ? (
        <Link to={to} className="group flex items-baseline gap-3 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
          <span className="text-sm text-muted group-hover:text-ink">{label} ›</span>
          {figure}
        </Link>
      ) : (
        <span className="flex items-baseline gap-3">
          <span className="text-sm text-muted">{label}</span>
          {figure}
        </span>
      )}
      {failed ? (
        <span className="break-keep text-sm text-muted">불러오지 못했어요</span>
      ) : note ? (
        <span id={`${testId}-note`} className={`break-keep text-sm text-muted ${notesOpen ? "" : "max-md:hidden"}`}>
          {note}
        </span>
      ) : (
        <span className="flex min-w-0 flex-wrap gap-x-3 break-keep text-sm text-muted">{children}</span>
      )}
    </div>
  );
}

function Figure({ f }: { f: ChannelFigure }) {
  if (f.unsupported) {
    return (
      <span className="text-muted" title="이 채널에서는 가져오지 않아요">
        —
      </span>
    );
  }
  const text = tallyText(f.tally);
  return (
    <span className={`tabular-nums ${text === "—" ? "text-muted" : "font-semibold text-ink"}`} title={text === "—" ? "확인하지 못해 셀 수 없어요" : undefined}>
      {text}
    </span>
  );
}

/** Full width on a computer; one card per channel, figures in a row, on a phone. */
function ChannelTable({ rows, health }: { rows: ChannelMetricRow[]; health: Map<string, ChannelHealth> }) {
  if (rows.length === 0) return <p className="px-4 pb-4 pt-2 text-sm text-muted">연결할 수 있는 채널이 없어요.</p>;
  return (
    <>
      <table className="mt-2 hidden w-full text-sm md:table" aria-labelledby="cs-channels-h">
        <thead>
          <tr className="border-b border-line text-left text-muted">
            <th scope="col" className="px-4 py-1.5 font-medium">채널</th>
            <th scope="col" className="px-3 py-1 text-right font-medium">오늘 문의</th>
            <th scope="col" className="px-3 py-1 text-right font-medium">오늘 리뷰</th>
            <th scope="col" className="px-3 py-1 text-right font-medium">미답변 문의</th>
            <th scope="col" className="px-4 py-1.5 font-medium">수집 상태</th>
            <th scope="col" className="px-4 py-1.5"><span className="sr-only">조치</span></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((row) => {
            const h = health.get(row.channelCode);
            return (
              <tr key={row.channelCode} data-testid={`cs-channel-${row.channelCode}`}>
                <th scope="row" className="whitespace-nowrap px-4 py-1 text-left font-semibold text-ink">{channelName(row)}</th>
                <td className="px-3 py-1 text-right"><Figure f={channelFigure(row, "inquiries")} /></td>
                <td className="px-3 py-1 text-right"><Figure f={channelFigure(row, "reviews")} /></td>
                <td className="px-3 py-1 text-right"><Figure f={channelFigure(row, "unanswered")} /></td>
                <td className="px-4 py-1"><HealthLine h={h} /></td>
                <td className="px-4 py-1 text-right">{h?.action ? <HealthAction h={h} /> : null}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <PhoneChannels rows={rows} health={health} />
    </>
  );
}

/**
 * <b>A phone reads each channel as one line</b>: its name, its unanswered inquiries and its collection state — the three
 * things that say whether to look closer. The day's counts, the full sentence and the fix unfold under it. A collection
 * problem is never folded away: its word stays on the line, in the warning tone, and the section heading names it too.
 */
function PhoneChannels({ rows, health }: { rows: ChannelMetricRow[]; health: Map<string, ChannelHealth> }) {
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const toggle = (code: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  return (
    <div className="mt-2 md:hidden">
      <div aria-hidden="true" className={`${PHONE_COLS} border-b border-line px-4 pb-1.5 text-sm text-muted`}>
        <span>채널</span>
        <span className="text-right">미답변 문의</span>
        <span>수집 상태</span>
      </div>
      <ul className="divide-y divide-line">
        {rows.map((row) => {
          const h = health.get(row.channelCode);
          const isOpen = open.has(row.channelCode);
          const panel = `cs-channel-more-${row.channelCode}`;
          const state = h ? h.badge ?? h.line : "—";
          return (
            <li key={row.channelCode} data-testid={`cs-channel-card-${row.channelCode}`}>
              <button
                type="button"
                className={`${PHONE_COLS} w-full px-4 py-2.5 text-left hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700`}
                aria-expanded={isOpen}
                aria-controls={panel}
                onClick={() => toggle(row.channelCode)}
              >
                <span className="font-semibold text-ink">{channelName(row)}</span>
                <span className="text-right text-sm">
                  <span className="sr-only">미답변 문의 </span>
                  <Figure f={channelFigure(row, "unanswered")} />
                </span>
                <span className={`min-w-0 break-keep text-sm ${h?.tone === "warn" ? "font-semibold text-warn" : "text-muted"}`} data-testid="cs-channel-state">
                  <span className="sr-only">수집 상태 </span>
                  {state}
                </span>
                <span aria-hidden="true" className={`text-muted transition-transform motion-reduce:transition-none ${isOpen ? "rotate-90" : ""}`}>
                  ›
                </span>
              </button>
              {isOpen ? (
                <div id={panel} className="space-y-2 px-4 pb-3" data-testid="cs-channel-more">
                  <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
                    <div className="flex gap-2"><dt className="text-muted">오늘 문의</dt><dd><Figure f={channelFigure(row, "inquiries")} /></dd></div>
                    <div className="flex gap-2"><dt className="text-muted">오늘 리뷰</dt><dd><Figure f={channelFigure(row, "reviews")} /></dd></div>
                  </dl>
                  {h && h.badge ? <p className="break-keep text-sm text-muted">{h.line}</p> : null}
                  {h?.action ? <HealthAction h={h} roomy /> : null}
                </div>
              ) : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Name · unanswered · state · chevron — one grid, so the header row and every channel line up as columns. */
// Widths measured at 360 (card inner 292px, 2026-11-14): 「카페24」 47px, 「미답변 문의」 69px, so the state keeps 136px —
// 「12분 전에 가져왔어요」 is 129px and stays on one line. A longer state wraps; it is never cut or set smaller.
const PHONE_COLS = "grid grid-cols-[3.25rem_4.5rem_minmax(0,1fr)_0.5rem] items-baseline gap-x-2";

function HealthLine({ h }: { h: ChannelHealth | undefined }) {
  if (!h) return <span className="text-sm text-muted">—</span>;
  return (
    <span className="flex flex-wrap items-baseline gap-x-2 break-keep text-sm">
      {h.badge ? <span className={`font-semibold ${h.tone === "warn" ? "text-warn" : "text-muted"}`}>{h.badge}</span> : null}
      <span className="text-muted">{h.line}</span>
    </span>
  );
}

/** A link styled as a small outlined button — the table row stays one text line high. */
function HealthAction({ h, roomy = false }: { h: ChannelHealth; roomy?: boolean }) {
  return (
    <Link
      to={h.action!.to}
      className={`inline-flex items-center whitespace-nowrap rounded-lg border border-line bg-surface px-3 ${roomy ? "min-h-11 py-2" : "py-px"} text-sm font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700`}
    >
      {h.action!.label}
    </Link>
  );
}

function AfterRow({ label, note, tally }: { label: string; note: string; tally: Tally }) {
  return (
    <li>
      <Link
        to={`/work#${WAITING_ANCHOR}`}
        className="flex items-baseline gap-2 py-1.5 hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
      >
        <span className="shrink-0 font-semibold text-ink">{label}</span>
        <span className="min-w-0 flex-1 truncate text-sm text-muted">{note}</span>
        <span className="shrink-0 font-semibold tabular-nums text-ink">{tallyWithUnit(tally)}</span>
      </Link>
    </li>
  );
}
