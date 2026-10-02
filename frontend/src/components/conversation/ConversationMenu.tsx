import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useConversation } from "../../lib/conversation/ConversationProvider";
import { NavIcon } from "../icons/NavIcon";
import { relativeTime } from "../../lib/format";
import type { ConversationSummary } from "../../lib/conversation/types";

/**
 * <b>새 대화 and 지난 대화, where the conversation is</b> (product-owner decision, 2026-10-01).
 *
 * <p>These two controls lived in the navigation rail, as a 「대화」 section with a collapse chevron, a
 * 새 대화 icon and up to eight thread rows — and when the list was empty the rail's own first line
 * under the destinations was 「지난 대화가 없습니다.」. The rail names places in the product; the Home's
 * composer IS the command surface, so the conversation's own controls belong on it.
 *
 * <p><b>Nothing was deleted.</b> Both functions are here, reached from the box a seller is already
 * looking at, and the same component serves desktop and mobile because the composer is the same
 * composer on both — which is what the rail could never be (`SideNav` is `hidden md:flex`).
 *
 * <p>The history read happens when the menu OPENS, not on page load. The rail used to fetch eight
 * summaries on every screen in the product; a control nobody pressed cost a request anyway.
 */
export function ConversationMenu() {
  const conversation = useConversation();
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<ConversationSummary[] | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const version = conversation?.historyVersion ?? 0;
  const currentId = conversation?.conversationId ?? null;

  useEffect(() => {
    if (!conversation || !open) return;
    let live = true;
    conversation
      .loadHistory(8)
      .then((rows) => live && setItems(rows))
      .catch(() => live && setItems([]));
    return () => {
      live = false;
    };
  }, [open, version, currentId]);

  // Escape and a click outside. Both close only — nothing here is destructive and nothing is saved.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mousedown", onDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("mousedown", onDown);
    };
  }, [open]);

  if (!conversation) return null;
  const goHome = () => {
    if (location.pathname !== "/") navigate("/");
  };

  return (
    <div ref={box} className="relative shrink-0">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label="대화 메뉴"
        title="대화 메뉴"
        className="inline-flex h-9 w-9 items-center justify-center rounded-full text-muted transition hover:bg-surface hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
      >
        <NavIcon name="compose" className="h-5 w-5" />
      </button>
      {open ? (
        /*
          Upwards, because the composer is docked at the foot of the viewport — a panel that opened
          downwards would open off the screen.

          <p>A disclosure, not an ARIA menu. `role="menu"` obliges arrow-key navigation between
          `menuitem`s, and without it a screen reader announces a menu the keyboard does not behave
          like — and the children stop being buttons to everything that queries by role. These are
          buttons and a list, which is what the rail's own version was.
        */
        <div
          aria-label="대화"
          className="absolute bottom-11 left-0 z-20 w-72 overflow-hidden rounded-xl border border-line bg-surface shadow-card"
        >
          <button
            type="button"
            onClick={() => {
              conversation.newConversation();
              goHome();
              setOpen(false);
            }}
            className="flex min-h-[40px] w-full items-center gap-2 px-3 text-left text-base font-semibold text-ink transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
          >
            <NavIcon name="compose" className="h-4 w-4" />
            새 대화
          </button>
          <div className="border-t border-line px-3 pb-2 pt-2">
            <p className="pb-1 text-xs font-semibold text-muted">대화 기록</p>
            {items === null ? (
              <p className="py-1 text-sm text-muted">불러오는 중…</p>
            ) : items.length === 0 ? (
              <p className="py-1 text-sm text-muted">지난 대화가 없습니다.</p>
            ) : (
              <ul className="space-y-0.5" aria-label="지난 대화">
                {items.map((h) => {
                  const current = h.conversationId === currentId;
                  // **The disambiguator appears exactly where the ambiguity is.** A seller who asks
                  // 「네이버 리뷰 최신화해줘」 on three days gets three rows reading the same words, and
                  // the list becomes unnavigable. A time column on EVERY row restated the order the
                  // list is already in, so it comes back only on the rows that need it.
                  const ambiguous = items.filter((o) => (o.headline ?? "") === (h.headline ?? "")).length > 1;
                  return (
                    <li key={h.conversationId}>
                      <button
                        type="button"
                        aria-current={current ? "true" : undefined}
                        onClick={() => {
                          void conversation.openConversation(h.conversationId);
                          goHome();
                          setOpen(false);
                        }}
                        className={`flex min-h-[32px] w-full items-center gap-2 rounded-md border-l-2 px-2 text-left text-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
                          current ? "border-brand-700 font-semibold text-ink" : "border-transparent text-muted hover:border-line hover:text-ink"
                        }`}
                      >
                        <span className="min-w-0 flex-1 truncate">{h.headline ?? "제목 없는 대화"}</span>
                        {ambiguous ? (
                          <span className="shrink-0 text-xs font-normal text-muted">{relativeTime(h.updatedAt)}</span>
                        ) : null}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
