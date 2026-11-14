import { WorkRows } from "../workspace/WorkRows";
import {
  BUCKETS,
  SORTS,
  partialNotice,
  tallyAny,
  tallyKnown,
  tallyText,
  tallyWithUnit,
  type HomeBoardView,
  type Tally,
  type WorkSort,
  type WorkView,
} from "../../lib/homeBoard";
import type { HomeBoardState } from "../../lib/useHomeBoard";
import type { HomeWorkRow } from "../../lib/homeWork";

const VIEW_TITLE: Partial<Record<WorkView, string>> = {
  todayChecks: "오늘 확인할 기록",
  answer: "답할 일",
  past: "지난 일",
  praise: "칭찬 리뷰 답글",
};

/** The heading of a view that is not the whole list: what it is, how many, and the way back. */
function viewHead(view: WorkView, board: HomeBoardView | null | undefined, total: Tally | null): { title: string; note: string | null } {
  const bucket = BUCKETS.find((b) => b.key === view);
  const title = VIEW_TITLE[view] ?? bucket?.label ?? "할 일";
  if (view === "todayChecks" && board) {
    return {
      title: `${title} ${tallyWithUnit(total)}`,
      note:
        [
          tallyAny(board.todo.checkAgain) ? `다시 확인 ${tallyWithUnit(board.todo.checkAgain)}` : null,
          tallyAny(board.todo.alsoDueToday) ? `다른 할 일 안에 있는 확인 ${tallyWithUnit(board.todo.alsoDueToday)}` : null,
        ]
          .filter(Boolean)
          .join(" + ") || null,
    };
  }
  if (view === "answer") return { title: `${title} ${tallyWithUnit(total)}`, note: "다시 확인을 뺀 할 일이에요. 홈의 「답할 일」과 같은 목록이에요." };
  if (view === "past") return { title: `${title} ${tallyWithUnit(total)}`, note: "2주 넘게 남은 일이에요. 안전 관련 리뷰와 오늘 확인할 일은 여기로 오지 않아요." };
  return { title: `${title} ${tallyWithUnit(total)}`, note: null };
}

function Chip({ on, onClick, children, tone = "plain", testId }: {
  on: boolean;
  onClick: () => void;
  children: React.ReactNode;
  tone?: "plain" | "today";
  testId?: string;
}) {
  const base = "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-full px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700";
  const look = tone === "today"
    ? `font-semibold text-warn ring-1 ring-inset ring-warn ${on ? "bg-warn/10" : "bg-surface hover:bg-warn/5"}`
    : on
      ? "bg-canvas font-semibold text-ink ring-1 ring-inset ring-line"
      : "bg-surface text-muted ring-1 ring-inset ring-line hover:text-ink";
  return (
    <button type="button" aria-pressed={on} onClick={onClick} className={`${base} ${look}`} data-testid={testId}>
      {children}
    </button>
  );
}

/**
 * <b>할 일 — the server's list, one view and one page at a time</b> (2026-11-10, Home FE connection, mockup v5).
 *
 * <ul>
 *   <li><b>Counts</b> are the server's. A count it could not confirm is 「—」 (or 「N+」 when it vouches for a floor), and the
 *       rows it did read are still listed beside it.</li>
 *   <li><b>「오늘 확인 모두」</b> opens every row with a check whose day has come — its own (다시 확인) or one riding on another
 *       row. It stands only when some check rides on another row; otherwise 다시 확인 alone says it.</li>
 *   <li><b>A failed read is not an empty list.</b> 「불러오지 못했어요」 says it could not read; 「할 일이 없어요」 says it read
 *       and found nothing. A partial read says what it does to this list, not which system it came from.</li>
 * </ul>
 */
/**
 * <b>Narrowing a page by what is written in it</b> — the 할 일 screen's search box (canonical mockup, 2026-10-02), over
 * the rows on the page it holds and the three strings a row draws. It is not a server search, so it says so.
 */
export function rowMatches(row: HomeWorkRow, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [row.title, row.said, row.line, row.source].some((v) => (v ?? "").toLowerCase().includes(q));
}

export function HomeWorkList({
  state,
  selection,
  now,
  emptySentence,
  query = "",
  reading,
  title = "할 일",
}: {
  state: HomeBoardState;
  selection: { wide: boolean; selectedKey: string | null; search: string };
  now: Date;
  /** What to say when the whole list read and found nothing — the screen's own sentence (it knows the job's state). */
  emptySentence: string;
  /** The 할 일 screen's search over the page on screen; empty on the Home. */
  query?: string;
  /** How a row reads — the 할 일 screen passes its rail reading through. */
  reading?: "rail";
  /** The section's name — the Home's 「할 일」, or none when the screen above already says it. */
  title?: string | null;
}) {
  const { board, page, rows, view, sort } = state;
  const todo = board?.todo;
  const total = page ? page.total : null;
  const showToday = tallyAny(todo?.alsoDueToday);
  const notice = board && !board.complete ? partialNotice(board.sources) : [];
  const head = view === "all" ? null : viewHead(view, board, total);
  const first = state.pageIndex * state.pageSize + 1;
  const last = state.pageIndex * state.pageSize + rows.length;
  const searching = query.trim().length > 0;
  const shown = searching ? rows.filter((r) => rowMatches(r, query)) : rows;

  return (
    <section aria-label="할 일" data-testid="home-work-list" className="space-y-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line pb-2">
        {title ? <h2 className="text-section font-bold tracking-tight text-ink">{title}</h2> : null}
        <label className="ml-auto flex items-center gap-2 text-sm text-muted">
          <span className="sr-only">정렬</span>
          <select
            value={sort}
            onChange={(e) => state.setSort(e.target.value as WorkSort)}
            className="h-8 rounded-lg border border-line bg-surface px-2 text-sm text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            data-testid="home-work-sort"
          >
            {SORTS.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {notice.length > 0 ? (
        <div role="status" data-testid="home-work-partial" className="flex flex-wrap items-start gap-3 rounded-lg bg-warn/10 px-4 py-3 text-sm text-ink">
          <div className="min-w-0 flex-1 space-y-1 break-keep">
            {notice.map((line) => (
              <p key={line}>{line}</p>
            ))}
          </div>
          <button
            type="button"
            onClick={state.reload}
            className="shrink-0 rounded-lg border border-line bg-surface px-3 py-1 text-sm font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            다시 불러오기
          </button>
        </div>
      ) : null}

      {/* The chips: the six buckets add up to 전체; 「오늘 확인 모두」 is a view across them, never added to anything. */}
      <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1 sm:flex-wrap sm:overflow-visible" role="group" aria-label="할 일 묶음">
        {showToday ? (
          <Chip tone="today" on={view === "todayChecks"} onClick={() => state.setView("todayChecks")} testId="chip-today-checks">
            오늘 확인 모두 <span className="tabular-nums">{tallyText(todo?.todayChecks)}</span> ›
          </Chip>
        ) : null}
        <Chip on={view === "all"} onClick={() => state.setView("all")} testId="chip-all">
          전체 <span className="font-semibold tabular-nums">{tallyText(todo?.total)}</span>
        </Chip>
        {BUCKETS.map((b) => (
          <Chip key={b.key} on={view === b.key} onClick={() => state.setView(b.key)} testId={`chip-${b.key}`}>
            {b.label} <span className="font-semibold tabular-nums">{tallyText(todo?.[b.key])}</span>
          </Chip>
        ))}
      </div>

      {head ? (
        <div className="flex flex-wrap items-end justify-between gap-2" data-testid="home-work-view-head">
          <div>
            <h3 className="text-base font-bold text-ink">{head.title}</h3>
            {head.note ? <p className="break-keep text-sm text-muted">{head.note}</p> : null}
          </div>
          <button
            type="button"
            onClick={() => state.setView("all")}
            className="text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            data-testid="home-work-back"
          >
            ‹ 할 일 전체
          </button>
        </div>
      ) : null}

      {page === undefined ? (
        <p className="text-sm text-muted" data-testid="home-work-loading">할 일을 불러오는 중이에요…</p>
      ) : page === null ? (
        <div role="alert" data-testid="home-work-failed" className="flex flex-wrap items-center gap-3 rounded-lg border border-line px-4 py-3 text-sm">
          <span className="min-w-0 flex-1 break-keep text-ink">
            할 일 목록을 불러오지 못했어요. 할 일이 없다는 뜻은 아니에요.
          </span>
          <button
            type="button"
            onClick={state.reload}
            className="shrink-0 rounded-lg border border-line bg-surface px-3 py-1 font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            다시 불러오기
          </button>
        </div>
      ) : rows.length === 0 ? (
        <p className="break-keep text-sm text-muted" data-testid="home-work-empty">
          {!tallyKnown(page.total)
            ? "확인된 할 일은 없어요. 다 확인하지 못한 부분이 있어 빠진 일이 있을 수 있어요."
            : view === "all"
              ? emptySentence
              : view === "todayChecks"
                ? "오늘 확인할 기록이 없어요."
                : view === "answer"
                  ? "답할 일이 없어요."
                  : "여기에는 지금 할 일이 없어요."}
        </p>
      ) : (
        <>
          {searching ? (
            <p className="break-keep text-sm text-muted" data-testid="home-work-search-note">
              {shown.length > 0
                ? `이 페이지 ${rows.length.toLocaleString("ko-KR")}건 중 ${shown.length.toLocaleString("ko-KR")}건이 맞아요.`
                : "이 페이지에는 찾는 내용이 없어요."}
              {state.hasNext ? " 다음 페이지도 찾아보세요." : ""}
            </p>
          ) : null}
          <WorkRows
            rows={shown}
            selectedKey={selection.selectedKey}
            wide={selection.wide}
            search={selection.search}
            now={now}
            ariaLabel={head?.title ?? "할 일"}
            showBacklogDivider={false}
            dense
            captionSaysReason
            highlightAlso={view === "todayChecks"}
            reading={reading}
          />
          <nav aria-label="할 일 페이지" className="flex flex-wrap items-center gap-3 border-t border-line pt-2 text-sm text-muted" data-testid="home-work-pager">
            <span className="tabular-nums">
              {first}–{last} · {view === "todayChecks" ? "오늘 확인" : view === "answer" ? "답할 일" : "전체"} {tallyWithUnit(page.total)}
            </span>
            <span className="ml-auto flex gap-2">
              {state.pageIndex > 0 ? (
                <button type="button" onClick={state.prev} className="rounded-lg border border-line bg-surface px-3 py-1 font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700" data-testid="home-work-prev">
                  ‹ 이전
                </button>
              ) : null}
              {state.hasNext ? (
                <button type="button" onClick={state.next} className="rounded-lg border border-line bg-surface px-3 py-1 font-semibold text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700" data-testid="home-work-next">
                  다음 {state.pageSize}건 ›
                </button>
              ) : null}
            </span>
          </nav>
        </>
      )}

      {/* 지난 일 is a view of the same list, not another screen: its way in stands under the whole list whenever the board
          was read and it is not a measured zero — an unconfirmed count is still a way in (2026-11-10 review). */}
      {view === "all" && board && !(tallyKnown(board.past.total) && board.past.total.count === 0) ? (
        <p className="flex flex-wrap items-center gap-x-2 break-keep text-sm text-muted" data-testid="home-work-past">
          <span>{tallyAny(board.past.total) ? `지난 일 ${tallyWithUnit(board.past.total)} 따로 (2주 넘은 일)` : "지난 일 수는 다 확인하지 못했어요"}</span>
          <button
            type="button"
            onClick={() => state.setView("past")}
            className="ml-auto font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            data-testid="home-work-past-open"
          >
            지난 일 보기 ›
          </button>
        </p>
      ) : null}
    </section>
  );
}
