import { Link } from "react-router-dom";
import type { ReviewIssueView } from "../../lib/types";
import { SEVERITY_LABEL_KO, changeBadges } from "../../lib/reviewIssuesView";
import type { ChangeTone } from "../../lib/reviewIssuesView";

// Status colour is used here because these states have real meaning: a surge and a severity are
// verdicts the extractor reached, not decoration. One word each, no pill — §5's restraint is about
// how much surface a tone paints, and a tinted capsule on every row paints the whole list.
const TONE_TEXT: Record<ChangeTone, string> = {
  bad: "text-bad",
  warn: "text-warn",
  neutral: "text-muted",
  good: "text-good",
};

/**
 * <b>반복 문제 목록 — the problem first, everything else in a column beside it</b> (canonical, 2026-10-03).
 *
 * <p>It used to be three bordered groups of two-line rows: a heading, then every row repeating its own
 * state word under its title. With sixteen of seventeen problems in one state the second line was the
 * same two words sixteen times, and the facts a seller compares — how much evidence, how old, how recent
 * — were prose inside it, so no two rows could be read against each other.
 *
 * <p>Now the state is a column, like the counts and the dates, and the row is one line: the problem is
 * the only thing set in the title step and the only thing in ink-bold. The groups did not go away — they
 * are the tabs above this list, which is also what lets 「확인 필요 0」 be stated instead of a heading
 * silently not appearing.
 *
 * <p><b>No row carries a button.</b> Unchanged: the row is a selection and the one action lives in the
 * decision pane.
 */
export function IssueList({
  issues,
  selectedId,
  hrefFor,
}: {
  issues: readonly ReviewIssueView[];
  selectedId: string | null;
  /** Where a row goes — the page with the problem selected, or the problem's own screen when narrow. */
  hrefFor: (issueId: string) => string;
}) {
  return (
    <section aria-label="반복 이슈 목록" className="-mx-4">
      {/* The column names, written once. Every figure under them is comparable to the one above it, which
          is the whole reason this list stopped repeating its words inside each row. */}
      <div className="flex items-baseline gap-3 border-b border-line px-4 pb-2 text-xs text-muted md:gap-4">
        <span className="min-w-0 flex-1">문제</span>
        <span className="w-20 shrink-0 md:w-24">상태</span>
        <span className="w-14 shrink-0 text-right md:w-16">근거</span>
        <span className="hidden w-28 shrink-0 text-right lg:block">처음 발생</span>
        <span className="w-24 shrink-0 text-right md:w-28">마지막 발생</span>
      </div>
      <ul className="divide-y divide-line">
        {issues.map((issue) => {
          const selected = issue.id === selectedId;
          const badges = changeBadges(issue.change);
          return (
            <li key={issue.id}>
              <Link
                to={hrefFor(issue.id)}
                aria-current={selected ? "true" : undefined}
                className={`flex items-baseline gap-3 px-4 py-3 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 md:gap-4 ${
                  selected ? "bg-brand-50 shadow-selected" : "hover:bg-canvas"
                }`}
              >
                <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-2">
                  <span className="break-keep text-lg font-semibold leading-snug text-ink">{issue.title}</span>
                  {badges.map((badge) => (
                    <span key={badge.kind} className={`text-xs font-semibold ${TONE_TEXT[badge.tone]}`}>
                      {badge.labelKo}
                    </span>
                  ))}
                  {issue.severity === "HIGH" ? (
                    <span className="text-xs font-semibold text-warn">{SEVERITY_LABEL_KO.HIGH}</span>
                  ) : null}
                </span>
                <span className="w-20 shrink-0 text-sm text-muted md:w-24">{issue.lifecycleLabelKo}</span>
                <span className="w-14 shrink-0 text-right text-sm tabular-nums text-ink md:w-16">
                  {issue.evidenceCount.toLocaleString("ko-KR")}건
                </span>
                <span className="hidden w-28 shrink-0 text-right text-sm tabular-nums text-muted lg:block">
                  {issue.firstEvidenceOn ?? "—"}
                </span>
                <span className="w-24 shrink-0 text-right text-sm tabular-nums text-ink md:w-28">
                  {issue.lastEvidenceOn ?? "—"}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
