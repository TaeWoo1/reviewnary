import type { ReactNode } from "react";

/**
 * The legacy section, drawn in the v2 grammar (UI/UX v2 Phase 4).
 *
 * <p>This was a card whose title sat inside it at `text-xl` bold — louder than the v2 page title — so every screen
 * still composed from it (channel detail, upload, backfill) read as a stack of framed posters beside screens whose
 * sections are an outline. Its callers keep their API; what changed is the shape: the title stands outside, at the
 * size of `ui/Section`'s, and the body is one container. One section, one box.
 */
export function Section({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        {/*
          <b>18/600 — the normal section step</b> (typography contract, product-owner decision
          2026-10-01). The same step `ui/Section` takes — the two draw one thing and must not drift.

          <p>The scale has three ranks above a row and each names a job: `title` (28/700) is the page,
          `section` (20/700) is a MAJOR region — the five questions 오늘 is composed of — and this is
          every other region in the product. The intermediate pass put all of them on 20/700, and at
          1600×1000 that made a card's own heading as loud as the page's top-level questions.

          <p>`text-lg` is 18px and already in the scale: a rank chosen from it, not a new step.
        */}
        <h2 className="break-keep text-lg font-semibold tracking-tight text-ink">{title}</h2>
        {action}
      </div>
      <div className="rounded-2xl border border-line bg-surface p-5">{children}</div>
    </section>
  );
}
