import { useEffect, useState, type ReactNode } from "react";

/**
 * <b>Master-detail — the one layout the decision screens share</b> (UI/UX v2 Phase 1).
 *
 * <p>A fixed sidebar (the shell's), the work list in the middle, and the selected item's detail on the right. The
 * row is a selection, not a destination: the seller picks an item, and the ONE primary action for it lives in the
 * detail. No row carries a button of its own — a list where every row shouts 「검토」 has no primary action at all.
 *
 * <p><b>Two readings, one component.</b> From {@link WIDE_QUERY} up the detail stands beside the list and each column
 * scrolls on its own, so reading a long case never moves the list the seller is working through. Below it there is
 * no room for two columns, and the page falls back to what it was before: rows open the full screen that owns the
 * item. That is why callers ask {@link useWideLayout} which address a row should carry, rather than always
 * selecting in place.
 *
 * <p>Nothing here reads or writes. It places what the page gives it.
 */
export const WIDE_QUERY = "(min-width: 1200px)";

function matches(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(WIDE_QUERY).matches;
}

/** Whether the list and the detail stand side by side. False where the environment cannot say (tests, SSR). */
export function useWideLayout(): boolean {
  const [wide, setWide] = useState(matches);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(WIDE_QUERY);
    const onChange = () => setWide(query.matches);
    onChange();
    query.addEventListener?.("change", onChange);
    return () => query.removeEventListener?.("change", onChange);
  }, []);
  return wide;
}

/**
 * <b>The two panes this product has</b> (Decision Workspace pane, 2026-10-02).
 *
 * <p>440 is the layout's own width and stays the default: 문의, 리뷰 and the Home's preview all take it
 * without asking, so none of them can move because another screen did.
 *
 * <p>576 is 확인할 일's — the Decision Workspace, where the pane is not a preview of an item but the place
 * the seller reads the prepared answer and decides on it. Measured at 1600×1000 against the canonical
 * mockup: the mockup gives that pane 574 of 1585 (36%) against our 440 of 1600 (27.5%), and the 134px is
 * paid for in the one column the mockup has less of — a list that is already wider than its rows need.
 *
 * <p><b>A map of literals rather than a number.</b> Tailwind compiles the classes it can see in the source,
 * so a width composed at runtime produces no CSS at all; and a closed set is the point anyway — this is a
 * second declared width, not a per-caller free parameter.
 */
const PANE = {
  default: "w-[440px] min-w-[440px] pb-8",
  /**
   * <b>576 from 1440 up, and the layout's own 440 below it</b> (product-owner decision, 2026-10-02).
   *
   * <p>Measured at 1366×768 with a flat 576: the list region fell to 550px and its column to 486, which
   * is a 104px state column and ~340px of sentence. The tabs wrapped onto a second row, every row's
   * second line was cut mid-word (「등록된 지식으로 답변할 수 있는 문의니…」) and every product name was
   * an ellipsis. The pane was not too wide — the viewport was too narrow to spend 576 of it on one of
   * two columns.
   *
   * <p>So the width is a contract and not a number: above 1440 the Decision Workspace takes its own
   * pane, and below it the screen falls back to exactly the pane every other master-detail screen has.
   * Nothing between is interpolated — a percentage pane is the thing the 2026-09-30 decision retired.
   *
   * <p><b>And a tighter floor</b> (product-owner decision, 2026-10-02). Measured at 1366×768: the
   * decision flow ended at y=753, inside the fold, and the shell's own 32px of trailing air pushed the
   * column's scroll height to 785 — so a pane whose content fitted still offered a scrollbar, which
   * reads as 「there is more below」 about the one screen whose job is to end in a decision. 12px leaves
   * the gap the flow wants and nothing to scroll. Every other pane keeps the 32 it has.
   */
  decision:
    "w-[440px] min-w-[440px] pb-3 min-[1440px]:w-[576px] min-[1440px]:min-w-[576px]",
} as const;

export type PaneKind = keyof typeof PANE;

/**
 * <b>Which column is the page</b> (확인할 일 canonical, 2026-10-03).
 *
 * <p>「list」 is what every master-detail screen has had: the list takes the width, and the selected item
 * opens in a declared pane beside it ({@link PANE}). The list is the page and the pane qualifies it.
 *
 * <p>「rail」 inverts that. Measured off Front's redesigned inbox at the window scale (nav 264 · list 306 ·
 * conversation 979 of 1600), the list is a <b>rail</b> — three truncated lines per row, no more — and the
 * selected conversation is the page. 확인할 일 is the one screen in this product with the same job: 46
 * records a seller walks, where the thing being read is always the one in front of them. The rail is 316
 * because a Korean row needs the ten pixels an English one does not.
 *
 * <p><b>Only while something is selected.</b> With nothing chosen there is no page to be beside, and a
 * 316px column against 1,044px of nothing is a screen waiting for a click — the state this screen's own
 * 2026-09-30 decision retired. Closed, the list takes the width exactly as it does today.
 */
export type LayoutKind = "list" | "rail";

export function MasterDetail({
  list,
  detail,
  detailLabel,
  wide,
  footer,
  onClose,
  preview = false,
  paneFooter,
  pane = "default",
  layout = "list",
}: {
  /** The page head, the actionable summary and the list — everything in the middle column. */
  list: ReactNode;
  /** The selected item. Drawn only when the layout is wide; null draws nothing rather than an empty panel. */
  detail: ReactNode | null;
  detailLabel: string;
  wide: boolean;
  /** Docked under the list column and outside its scroll — the 오늘 screen's composer. */
  footer?: ReactNode;
  /**
   * Dismiss the selection, for a screen whose default state is «nothing chosen».
   *
   * <p><b>Optional, because it is a claim about the screen and not about this layout.</b> A screen that
   * always has a selection — a queue whose whole job is the item in front of you — has nothing to close
   * to, and a close control there would empty a panel the seller cannot get back. Passing this says the
   * screen has a real closed state; leaving it out keeps exactly the panel that shipped.
   *
   * <p>When given, the panel grows persistent chrome: a 닫기 control that stays reachable however far the
   * case is scrolled, and <b>Esc</b>. Both do the one thing — the caller's own navigation — so the open
   * state has one owner (the URL) and no second copy to disagree with.
   */
  onClose?: () => void;
  /**
   * <b>The pane is a contextual preview, not the workspace</b> (Home v3.1).
   *
   * <p>A claim about the screen, and since UI System v2 it no longer carries any WIDTH: both readings are
   * a fixed 440px pane beside a `max-w-content` list, because the same pane being 440 on one screen and
   * 46% of the viewport on another was one of the two page rhythms the audit found (§5, §10.2).
   *
   * <p>What it still decides is the pane's INSIDE: a preview owns its own scroller and can dock a single
   * primary action under it ({@link paneFooter}), so the thing to press does not scroll away with the
   * case. Without it the pane scrolls as one column, which is what a full workspace wants.
   */
  preview?: boolean;
  /**
   * Docked at the bottom of the pane, outside its scroll — the preview's single primary action.
   *
   * <p>A preview that can be read but not acted on is a dead end, and an action that scrolls away with
   * the case is an action the seller has to go looking for. Requires {@link preview}.
   */
  paneFooter?: ReactNode;
  /**
   * {@link PANE}. Defaults to the layout's own, so a screen that does not ask keeps exactly the pane it
   * shipped with — which is what makes this an override on one route rather than a global change.
   */
  pane?: PaneKind;
  /** {@link LayoutKind}. Defaults to the reading every screen shipped with. */
  layout?: LayoutKind;
}) {
  const open = wide && detail !== null;
  // See {@link LayoutKind}: the rail is a rail only while there is a page beside it.
  const rail = layout === "rail" && open;
  useEffect(() => {
    if (!open || !onClose) return;
    const onKey = (e: KeyboardEvent) => {
      // Not while the seller is typing: Esc in a composer or a draft belongs to that control.
      const el = document.activeElement as HTMLElement | null;
      const typing = el ? el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable : false;
      if (e.key === "Escape" && !typing) onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  return (
    <div className="flex h-full min-h-0 flex-1" data-layout="master-detail">
      {/* `min-w-0 flex-1` is the flex spelling of the `minmax(0,1fr)` track the decision names: without
          `min-w-0` a flex item floors at its content's min-content width, and one long unbroken Korean
          line would push the 440px pane off screen. */}
      <div className={rail ? "flex w-[316px] min-w-[316px] shrink-0 flex-col" : "flex min-w-0 flex-1 flex-col"}>
        {/* `relative`: each scroller is the containing block of what it holds. Without it an absolutely positioned
            descendant (an sr-only label) is placed against the document and stretches the PAGE past the viewport. */}
        <div
          className={`relative min-h-0 flex-1 overflow-y-auto ${
            rail ? "px-4 pb-8 pt-6" : "px-4 pb-28 pt-4 md:px-8 md:pb-8 md:pt-6"
          }`}
          data-testid="master-list"
        >
          {/* <b>One width, one alignment, one rhythm — the same as every other page</b> (UI System v2 §10.2).
              This column used to be `mx-auto` at 760px, widening to 1160 when the pane closed, while every
              non-master-detail screen was 1120 and left-aligned. Moving from 문의 to 상품 therefore changed
              the body width, the alignment AND the vertical rhythm at once, which is most of what read as
              「여백이 제각각」 (audit §5). `max-w-content` is the contract's 1120 (§2). */}
          {/* The rail is one column of rows and its own head; `max-w-content` and the 24px rhythm are
              the page's, and a page's rhythm inside a 316px rail is three steps of air between a title
              and the first row. */}
          <div className={rail ? "w-full" : "w-full max-w-content space-y-6"}>{list}</div>
        </div>
        {footer}
      </div>
      {open ? (
        /*
          <b>Fixed, never a percentage</b> (product-owner decision, 2026-09-30). The detail used to be
          `w-[46%] min-w-[440px] max-w-[620px]` here and a fixed 440 in `preview`, so the same pane was a
          different width on two screens and a percentage of the viewport on one of them. A narrow viewport
          does not squeeze it — `wide` already falls back to single-pane below `WIDE_QUERY`.

          <p><b>And it is still fixed with two of them</b> ({@link PANE_WIDTH}, 2026-10-02). What that
          decision retired was a width that moved with the viewport; a screen declaring which of two
          declared widths it takes is the opposite — 440 remains what every caller gets by default.
        */
        <aside
          aria-label={detailLabel}
          className={`relative border-l border-line bg-surface ${
            rail ? "min-w-0 flex-1 flex flex-col" : `${PANE[pane]} shrink-0`
          } ${
            rail || preview ? "flex flex-col" : `overflow-y-auto px-6 ${onClose ? "pt-0" : "pt-6"}`
          }`}
          data-testid="master-detail"
        >
          <div
            className={
              rail
                ? "flex min-h-0 flex-1 flex-col overflow-y-auto px-8 pb-0 pt-0"
                : preview
                  ? "flex min-h-0 flex-1 flex-col overflow-y-auto px-6 pb-0 pt-0"
                  : "contents"
            }
          >
            {onClose ? (
              // Sticky, because the case below it is taller than the viewport: a close control that scrolls
              // away is a close control the seller has to scroll back up to find.
              <div
                className={`sticky top-0 z-10 mb-2 flex justify-end border-b border-line bg-surface py-2 ${
                  rail ? "-mx-8 px-8" : "-mx-6 px-6"
                }`}
              >
                <button
                  type="button"
                  onClick={onClose}
                  className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm font-semibold text-muted transition hover:bg-canvas hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                >
                  닫기
                  <span aria-hidden="true" className="text-base leading-none">
                    ✕
                  </span>
                </button>
              </div>
            ) : null}
            {detail}
            {/* <b>The action follows the content, and pins only when the content runs past it</b> (product-owner
                decision, 2026-09-26). It was a `shrink-0` footer outside the scroller, so it sat on the floor of
                the column whatever was above it: measured at 1440×900 with the preview's content ending at y≈470,
                the button stood at 846 with ~370px of nothing between — and this round's compression made that
                gap bigger, not smaller. Neither reference docks anything: Linear's Peek card sizes to its content
                and Intercom's Details rail ends where its rows end.

                `sticky bottom-0` does not move an element in a container that does not overflow, so a short
                preview gets the button right under the last line, and a long one gets exactly the old behaviour —
                the button riding the bottom edge while the case scrolls under it. The bar keeps its own surface
                and a hairline above it so the content cannot read as sitting on top of the button, and it is
                inside the scroller now, which is why the negative margins put it back out to the column edges.
                One action, unchanged: the preview still carries no control that decides anything. */}
            {(preview || rail) && paneFooter ? (
              <div
                className={`sticky bottom-0 mt-7 pb-6 ${rail ? "-mx-8 px-8" : "-mx-6 px-6"}`}
                data-testid="pane-footer"
              >
                {/* No rule above it. A hairline is right for a bar bolted to the floor of the column and wrong
                    for one that follows the content: over a short preview it drew a divider with nothing under
                    it. The fade does the only job the rule did — saying that content is passing underneath —
                    and it is invisible against plain surface, which is the state a short preview is in. */}
                <div aria-hidden="true" className="pointer-events-none -mt-6 h-6 bg-gradient-to-t from-surface to-transparent" />
                <div className="bg-surface pt-1">{paneFooter}</div>
              </div>
            ) : null}
          </div>
        </aside>
      ) : null}
    </div>
  );
}

/**
 * The address a list row carries: the same page with the item selected when the detail stands beside the list, the
 * full screen that owns the item otherwise. Keeps every other query parameter the page already had.
 */
export function selectionHref(wide: boolean, key: string, fullScreen: string, search: string): string {
  if (!wide) return fullScreen;
  const params = new URLSearchParams(search);
  params.set("item", key);
  return `?${params.toString()}`;
}
