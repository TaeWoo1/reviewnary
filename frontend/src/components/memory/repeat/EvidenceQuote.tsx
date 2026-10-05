import { Link } from "react-router-dom";
import type { IssueEvidenceView } from "../../../lib/types";

/**
 * One customer sentence, with the door back to the review that produced it.
 *
 * <p>Its own file since the problem got a page as well as a pane (반복 문제 canonical, 2026-10-05): the
 * two readings order their blocks differently and share this one unchanged, which is the only way the
 * quote can be the same object on both.
 */
export function EvidenceQuote({ row }: { row: IssueEvidenceView }) {
  return (
    <li>
      <p className="break-keep leading-relaxed text-ink">“{row.quote}”</p>
      <div className="mt-1 flex flex-wrap items-center gap-x-2 text-xs tabular-nums text-muted">
        <span>{row.occurredOn}</span>
        {row.rating != null ? <span>{row.rating}점</span> : null}
        {row.productName ? <span className="min-w-0 truncate">{row.productName}</span> : null}
        {/* Back to the review that produced this evidence — the ONE surface where a review is judged and
            answered. Needs nothing but the review id. */}
        <Link
          to={`/reviews/reply/${row.reviewId}`}
          className="ml-auto shrink-0 font-semibold text-brand-700 transition hover:text-brand-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
        >
          이 리뷰 처리하기
        </Link>
      </div>
    </li>
  );
}
