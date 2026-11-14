import { useEffect, useRef, useState } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import { MasterDetail, useWideLayout } from "../../components/workspace/MasterDetail";
import { WorkItemPane } from "../../components/workspace/WorkItemPane";
import { api } from "../../lib/apiClient";
import { PraiseBundle } from "../../components/customerOperations/CustomerOpsHome";
import { HomeWorkList } from "../../components/home/HomeWorkList";
import { useHomeBoard } from "../../lib/useHomeBoard";
import { tallyText } from "../../lib/homeBoard";
import { COPY } from "../../lib/copy/customerOps";
import { FinishedList, MovingList } from "../../components/customerOperations/ReplyBoardList";
import { attentionUncertaintyCopy } from "../../lib/attention";
import type { ReplyBoard, ReviewWorkView } from "../../lib/types";

/**
 * <b>Three views of one place</b> (mockup ③, confirmed 2026-10-25): what the seller has to do, what the service or a
 * delegate is doing, and what ended. 진행 중 is counted where it is shown and never added to the seller's number.
 */
const VIEWS = [
  { key: "todo", label: "내가 할 일" },
  { key: "moving", label: "진행 중" },
  { key: "done", label: "끝난 일" },
] as const;
type View = (typeof VIEWS)[number]["key"];

/** One name for one list: the nav entry, this page's title and the Home's section all say 확인할 일 (UI/UX v2). */
const TITLE = COPY.listTitle;
/**
 * <b>The queue</b> — everything waiting for the seller's decision, in one list.
 *
 * <p>The Home briefs the first few of exactly this list; this screen is the rest of it. <b>That sentence was
 * false.</b> The Home's 「확인 필요」 has been {@link mergeHomeWork} — cases, the reviews triage marked 지금 확인, and
 * the inquiry work queue, deduplicated by owning screen — since Customer Operations v3.1, while this screen read
 * cases alone. Measured on the demo org: the Home said 27건 and 확인할 일, the sidebar entry a seller reaches for
 * first, said 1건. Two screens under one name, one of them missing twenty-six items.
 *
 * <p>So it draws the same list, with the same composer. The only difference left is depth: the case half comes from
 * the dedicated decisions read rather than the Home's briefing-sized slice, because this screen exists to be the
 * whole of what the Home shortens.
 *
 * <p><b>A partial read is drawn, not refused.</b> Four reads answer this list and they fail independently; showing
 * nothing because one of them failed would hide work that was read successfully. Only a list where nothing could be
 * read at all says so.
 *
 * <p><b>문의와 리뷰를 가르지 않는다.</b> What a row says comes from the decision it carries, and its subject kind is
 * one fact in the row rather than the list it belongs to. A case opens the case screen, which carries this whole
 * queue so 「다음 건」 walks the morning; everything else opens the screen that owns it.
 *
 * <p><b>Nothing here decides anything.</b> No control on this screen resolves, dismisses or sends; the case screen
 * and the inquiry/review screens it links to still own every write.
 *
 * <p><b>Master-detail (UI/UX v2 Phase 1).</b> On a wide screen the selected row opens beside the list — drawn by the
 * very screen that owns it ({@link WorkItemPane}) — so the morning is worked without leaving the list.
 *
 * <p><b>Nothing is selected until the seller selects something</b> (Review Decision UX v3.2 — product-owner
 * decision). This screen used to open the first row because 「an empty right half is a page waiting for a click」,
 * and that reasoning was right about a screen whose job is the one item in front of you. Measured, this is not
 * that screen: it opens on 45 rows, five filters and a 5,407px list, and its name is 확인할 일 — it is a screen
 * for looking through. The cost of the old default was the one 오늘 already paid: a 556px column of judgment
 * forms for a row nobody chose, no way to close it, and a URL that could not express 「none」. Same contract as
 * the Home now: no {@code item} is closed, {@code item=<key>} is that row, a key that matches nothing is closed,
 * and 닫기/Esc put it back. Closed, the list takes the width the pane was holding.
 */
/** Rows per page here: deeper than the Home's ten, as deep as this screen's one read used to reach. */
export const QUEUE_PAGE_SIZE = 50;

export function OperationsCaseQueue({ now }: { now?: Date }) {
  /* <b>내가 할 일 is the Home's list</b> (2026-11-10 review: 「홈에서 20건을 보고 메뉴의 ‘할 일’을 눌렀는데 다른 목록·숫자가
     나오면 혼란스럽다」). It merged six reads on the screen while the Home read the server's board; now both read the
     same board and the same pages — the same rows, counts, buckets, 「—」 and 「N+」. Only the depth differs: a page here
     is {@link QUEUE_PAGE_SIZE} rows. The view and the order are the Home's own address keys (`?work=`, `?workSort=`). */
  const work = useHomeBoard(true, QUEUE_PAGE_SIZE);
  const [reviewWork, setReviewWork] = useState<ReviewWorkView | null>(null);
  const [board, setBoard] = useState<ReplyBoard | null>(null);
  // Bumped when a set-aside review is restored below, so it comes back into this list without a reload.
  const [reloadKey, setReloadKey] = useState(0);
  const wide = useWideLayout();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  // Transient, so it is not in the address: a narrowed list is a way of looking, not a place to come back to.
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const searchBox = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    // The seller's own reply work is read for two facts beside the list: an account whose work cannot be attributed
    // (it declines rather than reading as none), and the accounts 끝난 일 restores into. The rows come from the board.
    void Promise.resolve()
      .then(() => api.getReviewWorkStrict())
      .then((v) => live && setReviewWork(v))
      .catch(() => live && setReviewWork(null));
    // 진행 중 · 끝난 일 from the delegate lane; an install without one reads as empty lists.
    void Promise.resolve()
      .then(() => api.getReplyBoard())
      .then((v) => live && setBoard(v))
      .catch(() => live && setBoard(null));
    return () => {
      live = false;
    };
  }, [reloadKey]);

  const view: View = VIEWS.find((v) => v.key === params.get("view"))?.key ?? "todo";
  const setView = (key: View) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (key === "todo") next.delete("view");
        else next.set("view", key);
        next.delete("item");
        return next;
      },
      { replace: true },
    );
  const key = params.get("item");
  // Only what the address names — see the docblock. The first-row fallback this screen used is gone, and with
  // it the last caller of the helper that held it.
  const selected = wide && key ? (work.rows.find((r) => r.key === key) ?? null) : null;
  const close = () =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete("item");
        return next;
      },
      { replace: true },
    );
  // MasterDetail과 같은 술어다: rail은 옆에 페이지가 서 있을 때만 rail이다 ({@code LayoutKind}).
  const rail = wide && selected !== null;

  const list = (
    <div className="space-y-4">
      {/*
        <b>The rail's head</b> (canonical redesign, 2026-10-03). The name of the list, how many are in it,
        and the way to narrow it — on one line, because the column under it is 316px of rows and this is
        the chrome above them.

        <p>The scope sentence 「판매 후 운영이 필요한 문의와 리뷰를 모았습니다」 and the order line are gone
        with it. They were written for a page head over a full-width list; above a rail they are two lines
        of explanation before any work, which is the measurement that removed the last sentence to stand
        here (visual review, 1366×768, 2026-10-01). Nothing a seller acts on was in either.
      */}
      <div className="flex items-center gap-2">
        {/* <b>페이지일 때는 페이지의 제목, rail일 때는 rail의 제목</b> (UI audit, 2026-10-06).
            목록이 폭을 다 쥐고 있을 때 이 화면은 페이지이고, 제품의 페이지 제목은 28/700이다
            (주문·상품·문의가 쓰는 {@code PageHead}의 수). 오른쪽에 읽고 있는 건이 서면 이 열은 316px
            rail이 되고, 거기서 28px은 세 줄짜리 행 위의 현판이 된다 — 문의가 {@code compact}로 같은
            전환을 이미 한다. 한 규칙이고, 두 치수가 아니다. */}
        <h1 className={`font-bold tracking-tight text-ink ${rail ? "text-section" : "text-title"}`}>{TITLE}</h1>
        {view === "todo" && (work.rows.length > 0 || query) ? (
          <button
            type="button"
            aria-label={searching ? "검색 닫기" : "검색"}
            aria-expanded={searching}
            onClick={() => {
              if (searching) setQuery("");
              setSearching((open) => !open);
              if (!searching) window.requestAnimationFrame(() => searchBox.current?.focus());
            }}
            className="ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted transition hover:bg-canvas hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            {/* Open, the same button closes the box, so it draws a close mark (2026-10-28) — a magnifier that
                closes the search read as a second search. */}
            <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round">
              {searching ? (
                <path d="M6 6l12 12M18 6L6 18" />
              ) : (
                <>
                  <circle cx="11" cy="11" r="6.5" />
                  <path d="M16 16l4 4" />
                </>
              )}
            </svg>
          </button>
        ) : null}
      </div>

      {/* A switch between three views of 할 일 — the groove, as 리뷰 | 문의 (2026-10-28). */}
      <div role="group" aria-label="할 일 나누어 보기" className="flex w-max gap-0.5 rounded-xl bg-well p-0.5 shadow-[var(--press-groove)]">
        {VIEWS.map((v) => {
          // 내가 할 일 says the Home's number, with the Home's 「N+」 and 「—」.
          const count = v.key === "todo" ? (work.board ? tallyText(work.board.todo.total) : null) : v.key === "moving" ? (board?.moving.length.toLocaleString("ko-KR") ?? null) : null;
          return (
            <button
              key={v.key}
              type="button"
              aria-pressed={view === v.key}
              onClick={() => setView(v.key)}
              className={`whitespace-nowrap rounded-lg px-3 py-1 text-sm transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
                view === v.key ? "bg-surface font-semibold text-ink shadow-card" : "font-medium text-muted hover:text-ink"
              }`}
            >
              {v.label}
              {count != null ? (
                <span className="ml-1 font-medium tabular-nums">{count}</span>
              ) : null}
            </button>
          );
        })}
      </div>

      {view === "moving" ? <MovingList rows={board?.moving ?? []} now={now} /> : null}
      {view === "done" ? (
        <FinishedList
          rows={board?.done ?? []}
          accounts={reviewWork?.committed ?? []}
          onRestored={() => {
            setReloadKey((n) => n + 1);
            work.reload();
          }}
          now={now}
        />
      ) : null}

      {view === "todo" ? (
      <>
      {searching ? (
        <input
          ref={searchBox}
          type="search"
          aria-label="할 일 검색"
          placeholder="고객이 쓴 내용으로 찾기"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== "Escape") return;
            setQuery("");
            setSearching(false);
          }}
          className="min-h-[40px] w-full max-w-[360px] rounded-xl border border-line bg-surface px-3 text-sm text-ink placeholder:text-muted focus:border-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-700/20"
        />
      ) : null}


      {/* An account whose reply work cannot be attributed declines to answer rather than reading as 「no work」 —
          the same copy the 리뷰 screen's 「내 답변 작업」 used, moved with the work (UI/UX v2 Phase 3). */}
      {(reviewWork?.committed ?? []).map((account) => {
        const uncertain = attentionUncertaintyCopy(account.coverage ?? "COVERED");
        return uncertain ? (
          <div key={account.accountId} role="status" className="rounded-xl bg-warn/10 px-4 py-3" data-testid="reply-work-coverage-uncertain">
            <p className="text-sm font-semibold text-ink">
              {account.channelNameKo ?? account.channelCode}: {uncertain.headline}
            </p>
            <p className="mt-1 text-sm text-muted">{uncertain.detail}</p>
          </div>
        ) : null;
      })}

      {/* Praise approvals are approved together, from the same count the Home shows (2026-11-10). */}
      {!query.trim() && (work.view === "all" || work.view === "approvable") && work.board ? (
        <PraiseBundle count={work.board.todo.praiseApprovable.count ?? 0} />
      ) : null}

      {/* The Home's list itself: its chips, 「오늘 확인 모두」, pages, failure and partial notices — one read, one answer. */}
      <HomeWorkList
        state={work}
        selection={{ wide, selectedKey: selected?.key ?? null, search: location.search }}
        now={now ?? new Date()}
        emptySentence="지금 확인이 필요한 문의나 리뷰가 없어요."
        query={query}
        reading="rail"
        title={null}
      />

      </>
      ) : null}
    </div>
  );

  return (
    <MasterDetail
      wide={wide}
      list={list}
      detailLabel="선택한 할 일"
      detail={selected ? <WorkItemPane row={selected} now={now} /> : null}
      onClose={selected ? close : undefined}
      /* <b>The Decision Workspace's own pane</b> (product-owner decision, 2026-10-02). This route only:
         문의, 리뷰, 기억 and the Home's preview are untouched, because they do not pass this and the
         layout's default is the pane they have always had. What it buys is read in the pane — the
         prepared answer and its two controls stop being a 392px column inside a 440px panel, and the
         flow ends with air under it rather than a scrollbar. */
      pane="decision"
      /* <b>The rail reading</b> ({@link LayoutKind}, 2026-10-03 — product-owner decision). This route
         only: every other master-detail screen keeps the list-first layout it shipped with, because the
         rail is a claim about THIS screen — 46 records a seller walks, where the one being read is the
         page. The `pane` contract above still names the width the rail falls back to below 1200, where
         there is only one column at all. */
      layout="rail"
    />
  );
}
