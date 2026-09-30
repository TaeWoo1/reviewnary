import type { ReactNode } from "react";

/**
 * Honest empty state.
 *
 * An empty surface says what it will hold and what has to happen for it to hold something. It
 * never says "준비 중" or otherwise implies that the product is mid-construction — the state the
 * seller is in is "no data connected yet", which is a different and recoverable fact, and the
 * action tells them how to leave it.
 *
 * <b>Two readings.</b> The default is the whole-screen one: a dashed frame, centred, for a surface
 * that has nothing at all on it, where the emptiness IS the page and the seller needs the action.
 * {@link compact} is for a SECTION of a page whose other sections are full — 오늘's 실행 대기 with
 * nothing waiting in it. There, the full reading cost ~120px of vertical space and drew a dashed
 * box and a centred paragraph beside left-aligned work, so the quietest fact on the screen was
 * taking the most room and the only centred block in the product
 * (docs/ui/reviewnary_ui_system_audit_v1.md §5). The compact reading is one left-aligned line at
 * the same measure as the rows it replaces, with the action as a text link beside it.
 *
 * Both readings say the same two things. Compact does not abbreviate the truth, only the frame.
 */
export function Empty({
  title,
  body,
  action,
  compact = false,
}: {
  title: string;
  body: string;
  action?: ReactNode;
  /** A section of an otherwise-populated page, not the page itself. */
  compact?: boolean;
}) {
  if (compact) {
    return (
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 py-1">
        <p className="break-keep text-base text-ink">{title}</p>
        <p className="min-w-0 break-keep text-sm leading-relaxed text-muted">{body}</p>
        {action ? <div className="shrink-0">{action}</div> : null}
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-dashed border-line px-6 py-8 text-center">
      <p className="break-keep text-base font-semibold text-ink">{title}</p>
      <p className="mx-auto mt-2 max-w-md break-keep text-sm leading-relaxed text-muted">{body}</p>
      {action ? <div className="mt-4 flex justify-center">{action}</div> : null}
    </div>
  );
}
