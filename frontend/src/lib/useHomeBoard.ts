import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "./apiClient";
import { useScreenChanges } from "./screenChanges";
import { beginTodayRead, useWorkChanges } from "./workChanges";
import { boardRow, sortOf, viewOf, type HomeBoardView, type WorkPage, type WorkSort, type WorkView } from "./homeBoard";
import type { HomeWorkRow } from "./homeWork";

/** Rows per page of the Home's list. */
export const HOME_PAGE_SIZE = 10;

/** The address keys this list owns. `item` (the open row) belongs to the master-detail and is left alone. */
export const VIEW_PARAM = "work";
export const SORT_PARAM = "workSort";

/** `undefined` — not read yet · `null` — the read failed · a value — what the server said. */
export type Read<T> = T | null | undefined;

export interface HomeBoardState {
  board: Read<HomeBoardView>;
  page: Read<WorkPage>;
  /** The page's rows, as the Home draws them. Empty until the page lands. */
  rows: HomeWorkRow[];
  view: WorkView;
  sort: WorkSort;
  /** 0-based page number in this view. */
  pageIndex: number;
  /** Rows per page — the Home's ten, or the 할 일 screen's deeper page. */
  pageSize: number;
  hasNext: boolean;
  setView: (view: WorkView) => void;
  setSort: (sort: WorkSort) => void;
  next: () => void;
  prev: () => void;
  /** Read the board and this page again — 「다시 불러오기」 and 새로고침. */
  reload: () => void;
}

/**
 * <b>The Home's board and one page of its list</b> (2026-11-10, Home FE connection).
 *
 * The view (`?work=`) and the order (`?workSort=`) live in the address, so a reload or a back press lands on the same
 * list; the page does not — a cursor is a position in a list that may have moved, so a new view or order starts at the
 * first page. Earlier pages are remembered by the cursor that opened them, so 「이전」 goes back exactly.
 *
 * A failed read is `null`, never an empty list: the screen says it could not read, and keeps nothing it cannot vouch for.
 */
export function useHomeBoard(enabled = true, pageSize = HOME_PAGE_SIZE): HomeBoardState {
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const pushed = useScreenChanges();
  const writes = useWorkChanges();
  const changes = `${pushed}|${writes}`;
  const view = viewOf(params.get(VIEW_PARAM));
  const sort = sortOf(params.get(SORT_PARAM));
  const [board, setBoard] = useState<Read<HomeBoardView>>(undefined);
  const [page, setPage] = useState<Read<WorkPage>>(undefined);
  /** The cursor that opened each page so far; `[null]` is the first page. */
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [tick, setTick] = useState(0);
  const cursor = cursors[cursors.length - 1] ?? null;

  // A new view or order is a new list: back to its first page.
  const listKey = `${view}|${sort}`;
  const lastList = useRef(listKey);
  useEffect(() => {
    if (lastList.current !== listKey) {
      lastList.current = listKey;
      setCursors([null]);
    }
  }, [listKey]);

  useEffect(() => {
    if (!enabled) return;
    let live = true;
    // `live` keeps a read this effect has moved past off the Home's board; `settle` keeps it off the badge, which a
    // read from another screen may have answered since (see `beginTodayRead`).
    const settle = beginTodayRead();
    Promise.resolve()
      .then(() => api.getHomeBoard())
      .then((b) => {
        settle(b.todo.total.count ?? null);
        if (live) setBoard(b);
      })
      .catch(() => {
        // The Home cannot vouch for a number now, so neither can the badge.
        settle(null);
        if (live) setBoard(null);
      });
    return () => {
      live = false;
    };
  }, [enabled, changes, tick]);

  useEffect(() => {
    if (!enabled) return;
    let live = true;
    setPage(undefined);
    Promise.resolve()
      .then(() => api.getHomeWork({ bucket: view, sort, cursor, size: pageSize }))
      .then((p) => live && setPage(p))
      .catch(() => live && setPage(null));
    return () => {
      live = false;
    };
  }, [enabled, view, sort, cursor, changes, tick, pageSize]);

  const go = useCallback(
    (key: string, value: string | null) => {
      const next = new URLSearchParams(location.search);
      if (value) next.set(key, value);
      else next.delete(key);
      next.delete("item");
      const search = next.toString();
      navigate({ pathname: location.pathname, search: search ? `?${search}` : "" });
    },
    [location.pathname, location.search, navigate],
  );

  const rows = page ? page.rows.map(boardRow) : [];
  return {
    board,
    page,
    rows,
    view,
    sort,
    pageIndex: cursors.length - 1,
    pageSize,
    hasNext: Boolean(page?.nextCursor),
    setView: (v) => go(VIEW_PARAM, v === "all" ? null : v),
    setSort: (s) => go(SORT_PARAM, s === "importance" ? null : s),
    next: () => {
      if (page?.nextCursor) setCursors((c) => [...c, page.nextCursor]);
    },
    prev: () => setCursors((c) => (c.length > 1 ? c.slice(0, -1) : c)),
    reload: () => setTick((t) => t + 1),
  };
}
