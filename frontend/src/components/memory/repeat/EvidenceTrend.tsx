import { evidenceBeforeTrend, evidenceMonths, trendPeakLine } from "../../../lib/repeatedIssue";
import type { IssueEvidenceView } from "../../../lib/types";

/**
 * <b>언제 말해졌나</b> — this problem's evidence, month by month.
 *
 * <b>Why a shape and not a date pair.</b> 「2025-07-29 ~ 2026-08-19」 says a problem is thirteen months
 * old and nothing about whether it is happening now, clustered in one quarter, or long quiet. On this
 * org 접착 부족 is four months of 3–4 a month inside an otherwise silent year, and no sentence the
 * screen is allowed to write says that — the judgements that would (급증, 집중) did not fire.
 *
 * <b>It counts rows the detail read already returned.</b> No second read, no rate, no verdict about the
 * trend; see {@link evidenceMonths}. The bars are a reading aid for counts, which is why the peak is
 * also stated in words beside them and the axis names its own ends.
 */
export function EvidenceTrend({
  evidence,
  /** Today's month, `YYYY-MM`, in the seller's own timezone. The axis runs to it, not to the last bar. */
  throughMonth,
}: {
  evidence: readonly IssueEvidenceView[];
  throughMonth: string;
}) {
  const months = evidenceMonths(evidence, throughMonth);
  if (months.length === 0) return null;

  const peak = months.reduce((max, m) => (m.count > max ? m.count : max), 0);
  const peakLine = trendPeakLine(months);
  const before = evidenceBeforeTrend(evidence, months);
  const first = months[0].key;
  const last = months[months.length - 1].key;
  const dot = (key: string) => key.replace("-", ".");

  return (
    <div className="mt-2">
      <p className="text-xs text-muted">{peakLine ? `월별 근거 건수 · ${peakLine}` : "월별 근거 건수"}</p>
      {/*
        One figure, not twenty-four. A screen reader hearing 「2025년 7월 1건, 2025년 8월 0건 …」 twenty-four
        times learns less than the sentence, and the two facts a reader acts on — the peak and the span —
        are printed beside the bars as text either way.
      */}
      <div
        role="img"
        aria-label={`월별 근거 건수. ${dot(first)}부터 ${dot(last)}까지${peakLine ? `, ${peakLine}` : ""}.`}
        className="mt-1 flex h-6 items-end gap-1 border-b border-line"
      >
        {months.map((month) =>
          month.count === 0 ? (
            <span key={month.key} className="h-0.5 flex-1 rounded-sm bg-line" />
          ) : (
            <span
              key={month.key}
              className="flex-1 rounded-sm bg-muted"
              style={{ height: `${(month.count / peak) * 100}%` }}
            />
          ),
        )}
      </div>
      <p className="mt-1 flex justify-between text-xs tabular-nums text-muted">
        <span>{dot(first)}</span>
        <span>{dot(last)}</span>
      </p>
      {before > 0 ? (
        <p className="mt-2 break-keep text-xs leading-relaxed text-muted">
          이 기간 이전에도 근거가 {before.toLocaleString("ko-KR")}건 있습니다. 위 막대에는 들어 있지 않습니다.
        </p>
      ) : null}
    </div>
  );
}
