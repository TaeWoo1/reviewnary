import { useAuth } from "../../lib/auth";
import { ConnectionSignal } from "./ConnectionSignal";

/**
 * Mobile-only utility bar. On desktop there is no top bar at all (docs/frontend/reviewnary_design.md §7): the
 * page title starts the page, and the connection status lives in the sidebar's status area. Below
 * `md` the sidebar is hidden, so the workspace name and that status appear here instead.
 */
export function AppTopBar() {
  const { user } = useAuth();
  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-line bg-surface px-4 md:hidden">
      {/* The wordmark leads on a phone as it does in the rail (2026-10-28): the bar named the store and nothing else. */}
      <p className="flex min-w-0 items-baseline gap-2">
        <span className="shrink-0 text-base font-bold tracking-tight text-ink">reviewnary</span>
        <span className="min-w-0 truncate text-sm text-muted">{user?.orgName ?? "내 스토어"}</span>
      </p>
      <ConnectionSignal />
    </header>
  );
}
