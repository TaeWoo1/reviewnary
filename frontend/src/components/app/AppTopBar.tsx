import { NavLink, useLocation } from "react-router-dom";
import { useAuth } from "../../lib/auth";
import { NAV_GROUPS, navItemOwns, type NavItem } from "../../lib/nav.v2";
import { useTodayWorkCount } from "../../lib/useTodayWorkCount";
import { ConnectionSignal } from "./ConnectionSignal";

const FOCUS = "focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2";

/**
 * The app's one bar (product-owner decision 2026-11-12, target mockup v2): three destinations do not earn a
 * 240px rail, so the menu reads across the top on every width from `md` up and the page gets the whole width
 * under it. It replaces the desktop rail (`SideNav`, removed in the same change) — every entry the rail drew
 * is drawn here from the same `NAV_GROUPS`: the three destinations with 할 일's count, then 연동 · 설정, the
 * connection signal, the operator and 로그아웃 at the end.
 *
 * Below `md` it stays the compact bar it was — wordmark, store, connection signal — because the bottom tabs
 * and the 더보기 drawer carry the menu there.
 */
export function AppTopBar() {
  const { user, logout } = useAuth();
  const { pathname, search } = useLocation();
  const work = useTodayWorkCount();
  const main = NAV_GROUPS.filter((group) => !group.foot).flatMap((group) => group.items);
  const foot = NAV_GROUPS.filter((group) => group.foot).flatMap((group) => group.items);
  const lit = (item: NavItem) => navItemOwns(item, pathname, search);

  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-line bg-surface px-4 md:h-14 md:gap-8 md:px-8">
      <p className="flex min-w-0 items-baseline gap-2 md:gap-3">
        <span className="shrink-0 text-base font-bold tracking-tight text-ink md:text-lg">reviewnary</span>
        <span className="min-w-0 truncate text-sm text-muted">{user?.orgName ?? "내 스토어"}</span>
      </p>

      {/* Desktop only: below md the bottom tabs and the drawer render the same model. */}
      <nav aria-label="주 메뉴" className="hidden min-w-0 flex-1 items-center gap-6 md:flex">
        <ul className="flex items-center gap-1">
          {main.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.end}
                aria-current={lit(item) ? "page" : undefined}
                className={() =>
                  `flex h-9 items-center gap-2 rounded-lg px-3 text-base transition ${FOCUS} ${
                    lit(item) ? "bg-brand-50 font-semibold text-ink" : "font-medium text-muted hover:bg-brand-50 hover:text-ink"
                  }`
                }
              >
                {item.label}
                {item.badge === "work" && work ? <NavBadge count={work} /> : null}
              </NavLink>
            </li>
          ))}
        </ul>
        {/* 연동 · 설정 are where the data comes from, not the day's work: quieter, at the far end, still in 「주 메뉴」. */}
        <ul className="ml-auto flex items-center gap-6">
          <li className="flex items-center">
            <ConnectionSignal />
          </li>
          {foot.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                aria-current={lit(item) ? "page" : undefined}
                className={() => `rounded-md text-sm transition ${FOCUS} ${lit(item) ? "font-semibold text-ink" : "text-muted hover:text-ink"}`}
              >
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <div className="hidden shrink-0 items-center gap-3 border-l border-line pl-6 md:flex">
        <p className="max-w-[10rem] truncate text-sm font-medium text-ink">{user?.name ?? "운영자"}</p>
        <button type="button" onClick={logout} className={`shrink-0 rounded-md text-sm text-muted transition hover:text-ink ${FOCUS}`}>
          로그아웃
        </button>
      </div>

      <div className="ml-auto md:hidden">
        <ConnectionSignal />
      </div>
    </header>
  );
}

/** The count beside 할 일. Its own words for a screen reader, since the number alone would be read bare. */
export function NavBadge({ count }: { count: number }) {
  return (
    <span className="rounded-full bg-brand-700 px-1.5 text-xs font-semibold tabular-nums text-white">
      <span aria-hidden="true">{count > 99 ? "99+" : count.toLocaleString("ko-KR")}</span>
      <span className="sr-only">{count.toLocaleString("ko-KR")}건</span>
    </span>
  );
}
