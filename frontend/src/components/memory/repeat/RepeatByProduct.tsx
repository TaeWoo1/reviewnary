import { Link } from "react-router-dom";
import { productSpanLine, repeatLine, unattributedLine } from "../../../lib/repeatedIssue";
import type { IssueEvidenceSummaryView } from "../../../lib/types";

/**
 * <b>어디서 얼마나 반복되나</b> — the question a repeated problem exists to raise.
 *
 * <b>Every row shows both numbers and no ratio.</b> 「리뷰 1,761건 중 16건」 is what the read
 * measured; 0.9% is a rate over a population nobody examined. The component cannot compute one
 * because it never sees the two numbers apart from the sentence that pairs them.
 *
 * <b>A failed read renders nothing.</b> A screen that could not see where a problem repeats has
 * nothing to say about where it repeats — and 「0개 상품」 would be that screen saying it anyway.
 *
 * <b>No card.</b> It was a bordered, divided box inside a pane that is already a surface. The rows are
 * now the same hairline-separated reading as everything else in the pane — see §4 on three borders
 * saying one thing.
 */
export function RepeatByProduct({
  evidence,
  failed,
  /** Render only this many rows — the representative product, with the rest behind the block's disclosure. */
  limit,
  /** Skip this many rows — what the representative reading above already showed. */
  skip = 0,
  /**
   * Draw the labelled region. False for the continuation behind a disclosure, which belongs to the
   * region the representative reading already opened — two regions of the same name would be two
   * answers to 「어디서 반복되나」 for one problem.
   */
  region = true,
}: {
  evidence: IssueEvidenceSummaryView | null;
  failed: boolean;
  limit?: number;
  skip?: number;
  region?: boolean;
}) {
  if (failed || !evidence) return null;

  // Stated in the reading, not in the continuation behind the fold: it explains a total the seller is
  // looking at, and a sentence about the total hidden under 「모두 보기」 is the total going unexplained.
  const unattributed = skip === 0 ? unattributedLine(evidence.unattributedEvidence) : null;
  const rows = evidence.byProduct.slice(skip, limit == null ? undefined : skip + limit);
  if (rows.length === 0 && !unattributed) return null;

  const body = (
    <>
      {evidence.byProduct.length === 0 ? (
        <p className="break-keep leading-relaxed text-muted">
          이 문제의 근거가 어느 상품에도 연결되어 있지 않습니다.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map((row) => {
            const span = productSpanLine(row);
            return (
              <li key={row.productId} className="py-2 first:pt-0">
                {/* The product page is the other place this problem is already counted, so the name is the
                    door to it rather than plain text beside a door. */}
                <Link
                  to={`/products/${row.productId}`}
                  className="block break-keep font-semibold text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                >
                  {row.productName ?? "이름이 확인되지 않은 상품"}
                  <span className="ml-1 text-brand-700" aria-hidden="true">›</span>
                </Link>
                <p className="break-keep text-xs tabular-nums text-muted">
                  {repeatLine(row)}
                  {span ? ` · ${span}` : ""}
                </p>
              </li>
            );
          })}
        </ul>
      )}
      {unattributed ? (
        <p className="mt-3 break-keep text-xs leading-relaxed text-muted">{unattributed}</p>
      ) : null}
    </>
  );

  return region ? <section aria-label="어디서 반복되나">{body}</section> : <div>{body}</div>;
}
