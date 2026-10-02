import { NavLink } from "react-router-dom";
import { useAuth } from "../../lib/auth";
import { NAV_GROUPS, navGroupLabel } from "../../lib/nav.v2";
import { NavIcon } from "../icons/NavIcon";
import { ConnectionSignal } from "./ConnectionSignal";

const ITEM_BASE =
  "flex min-h-[38px] items-center gap-3 rounded-lg px-2 text-base transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2";

function itemClass({ isActive }: { isActive: boolean }): string {
  return `${ITEM_BASE} ${
    isActive ? "bg-surface font-semibold text-ink shadow-sm" : "font-medium text-muted hover:bg-surface/70 hover:text-ink"
  }`;
}

/**
 * Desktop navigation rail (docs/reviewnary_design.md §2, §7).
 *
 * 232px, one surface, one rule on the right. The wordmark and the workspace are the top; the two nav
 * groups are the middle; the bottom is the operator and the one piece of chrome that reads data —
 * connection health — as a SECONDARY status line. It used to be a warn pill in the top-right of every
 * page, the strongest thing on every screen; it is still real (`REPEATED_FAILURE`, unacknowledged) and
 * still one press away, but it is not the first thing a seller reads when they came to answer a customer.
 *
 * Hidden below `md`, where the bottom tabs and the 더보기 drawer render from the same `NAV_GROUPS`.
 */
export function SideNav() {
  const { user, logout } = useAuth();

  return (
    // §1 — the rail is the recessed ground, so the conversation beside it reads as the page. An
    // active row is the raised one (`bg-surface`), which is the same information the old blue fill
    // carried with one less colour spent on chrome.
    <aside className="hidden w-sidebar shrink-0 flex-col border-r border-line bg-canvas md:flex">
      <div className="px-4 pb-3 pt-4">
        <p className="text-lg font-bold tracking-tight text-ink">reviewnary</p>
        <p className="mt-0.5 truncate text-sm text-muted">{user?.orgName ?? "내 스토어"}</p>
      </div>

      <nav aria-label="주 메뉴" className="flex-1 overflow-y-auto px-2 py-2">
        {NAV_GROUPS.map((group) => (
          <div key={group.heading} className="mb-6 last:mb-0">
            {navGroupLabel(group) ? (
              <p className="px-2 pb-2 text-xs font-semibold text-muted">{navGroupLabel(group)}</p>
            ) : null}
            <ul className="space-y-0.5">
              {group.items.map((item) => (
                <li key={item.to}>
                  <NavLink to={item.to} end={item.end} className={itemClass}>
                    <NavIcon name={item.icon} className="h-[18px] w-[18px]" />
                    <span className="truncate">{item.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {/*
          <b>The conversation's own controls are not in the rail</b> (product-owner decision,
          2026-10-01). 「대화」 — a collapse chevron, 새 대화, and up to eight thread rows — stood here
          under the destinations, and on an org with no threads the rail's last line read 「지난 대화가
          없습니다.」. The rail names PLACES; the Home's composer is the command surface, so both
          controls moved onto it ({@link ConversationMenu}), which is also the only surface that is
          the same on desktop and mobile — this rail is `hidden md:flex`. Nothing was removed from
          the product: 새 대화 and 지난 대화 are one press from the box on every width.
        */}
      </nav>

      {/*
        <b>One compact account row</b> (product-owner decision, 2026-10-01). It was two stacked rows —
        the connection signal above, the operator and 로그아웃 below — at the foot of a column whose
        job is to stay out of the way. The signal renders nothing when there is no connection problem
        (`ConnectionSignal`), so on an ordinary day this is one line.
      */}
      <div className="flex items-center gap-2 border-t border-line px-4 py-3">
        <p className="min-w-0 flex-1 truncate text-sm font-medium text-ink">{user?.name ?? "운영자"}</p>
        <ConnectionSignal />
        <button
          type="button"
          onClick={logout}
          className="shrink-0 rounded-md text-sm text-muted transition hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
        >
          로그아웃
        </button>
      </div>
    </aside>
  );
}
