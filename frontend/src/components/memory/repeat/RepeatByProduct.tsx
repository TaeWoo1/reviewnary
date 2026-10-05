import { Link } from "react-router-dom";
import { useCaseVariant } from "../../workspace/CaseLayout";
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
 *
 * <b>And on the problem's own page, a bar</b> (승인된 mockup, 2026-10-05 — Sentry의 tag breakdown).
 * 「16건 · 1건 · 1건」은 읽어야 알고, 그 셋의 모양은 봐야 안다 — 이 문제가 한 상품의 것인지 세 상품에
 * 퍼진 것인지가 이 화면의 질문이다. 폭은 가장 큰 행에 대한 비율이고 <b>어떤 수도 아니다</b>: 분자와
 * 분모의 쌍은 옆 문장이 그대로 찍고, 막대는 {@code RatingSpread}의 것과 같은 읽기 보조물로
 * assistive tech에서 숨는다. 440px pane에는 그릴 자리가 없으므로 그리지 않는다.
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
  const page = useCaseVariant() === "page";
  const largest = evidence.byProduct.reduce((max, row) => (row.evidenceCount > max ? row.evidenceCount : max), 1);

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
                {page ? (
                  <p aria-hidden="true" className="mt-1.5 h-1 overflow-hidden rounded-full bg-canvas">
                    <span
                      className="block h-full rounded-full bg-muted"
                      style={{ width: `${(row.evidenceCount / largest) * 100}%` }}
                    />
                  </p>
                ) : null}
                <p className={`break-keep text-xs tabular-nums text-muted ${page ? "mt-1.5" : ""}`}>
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
