import type { ReactNode } from "react";

/**
 * <b>One tab of a list's own axis.</b>
 *
 * <p>The underline reading 확인할 일's queue, 리뷰's record and 반복 문제's memory all use, and for the same
 * reason: a filter whose values are the thing the list is counted and ordered by is navigation, not a
 * setting, and a row of filled pills above a white list reads as a toolbar over a table.
 *
 * <p>It lives in `ui/` because three screens draw it. It was `TierTab`, local to 리뷰 — and the moment a
 * second screen needed the same control, a copy of six styled lines is how one product grows two tab
 * readings that drift apart.
 */
export function FilterTab({
  pressed,
  onClick,
  children,
}: {
  pressed: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={`-mb-px min-h-[40px] border-b-2 px-1 pb-3 text-base tabular-nums transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
        pressed ? "border-brand-700 font-semibold text-ink" : "border-transparent text-muted hover:text-ink"
      }`}
    >
      {children}
    </button>
  );
}
