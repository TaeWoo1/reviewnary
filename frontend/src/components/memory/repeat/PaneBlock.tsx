import type { ReactNode } from "react";

/**
 * One labelled block of the repeated-problem pane.
 *
 * <b>A label, a hairline and air — no card.</b> The pane is already a surface; a bordered box inside it
 * is a second edge saying the same thing (§4). The label is the quiet step, not a heading in the title
 * scale, because the thing a seller reads in a block is its content and not its name.
 *
 * <b>`side` is for what acts on the block</b> — 답변 기준 채우기, 모두 보기. Never the screen's primary
 * action, which is docked at the floor of the pane.
 */
export function PaneBlock({
  label,
  side,
  children,
}: {
  label: string;
  side?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={label} className="border-t border-line pt-3">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-xs font-semibold text-muted">{label}</h3>
        {side ? <div className="shrink-0 text-xs">{side}</div> : null}
      </div>
      <div className="mt-2 space-y-2">{children}</div>
    </section>
  );
}
