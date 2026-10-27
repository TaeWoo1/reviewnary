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
  empty = false,
}: {
  pressed: boolean;
  onClick: () => void;
  children: ReactNode;
  /** The view holds nothing — drawn quieter, still pressable (2026-10-28). */
  empty?: boolean;
}) {
  return (
    /*
      <b>The one filter chip</b> (2026-10-28 walk-through: five shapes of tab and filter — a bordered pill, a grey chip,
      a groove, an underline and a black fill). A filter narrows the list in front of you, and it is this chip on every
      screen; a switch between two views is the groove (`ReviewInquirySwitch`, 할 일's views, 주간 · 월간).
    */
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={`min-h-[32px] whitespace-nowrap rounded-lg px-2.5 text-sm tabular-nums transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
        pressed
          ? "bg-surface font-semibold text-ink ring-1 ring-line"
          : empty
            ? "text-muted/60 hover:text-ink"
            : "text-muted hover:bg-surface/60 hover:text-ink"
      }`}
    >
      {children}
    </button>
  );
}
