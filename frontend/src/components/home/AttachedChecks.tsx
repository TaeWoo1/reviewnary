import { Link } from "react-router-dom";
import { checkHref, dayLabel, type RowWork } from "../../lib/homeBoard";

/** 「11월 7일」 in the seller's day (KST), from an instant. */
function kstDay(at: string | null): string | null {
  if (!at) return null;
  const t = Date.parse(at);
  if (!Number.isFinite(t)) return null;
  const d = new Date(t + 9 * 3600e3);
  return `${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일`;
}

/**
 * <b>A check whose day has come, riding on another row of the same item</b> (2026-11-10, mockup v5).
 *
 * <p>One customer item is one row: the row's own work decides its bucket and its place, and a check the seller set on the
 * same inquiry is drawn here, under it, with its own day and its own way in — never merged into the row's words and never
 * a second row. It sits outside the row's link ({@link DecisionRow} draws `children` after it), so the two destinations
 * are two controls, not one link inside another.
 *
 * <p>「진행 결과 적기」 opens the inquiry on that record (`?focus=seller-check&checkId=`).
 */
export function AttachedChecks({ also, highlight = false }: { also: readonly RowWork[]; highlight?: boolean }) {
  if (also.length === 0) return null;
  return (
    <ul className="space-y-2" aria-label="이 문의에 붙은 오늘 확인">
      {also.map((w) => {
        const behind = w.action === "TOLD_BEHIND";
        const day = dayLabel(w.nextCheckOn);
        const recorded = kstDay(w.since);
        return (
          <li
            key={`${w.workItemId ?? ""}:${w.checkId ?? w.action}`}
            data-testid="attached-check"
            className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-warn/10 px-3 py-2 text-sm ${
              highlight ? "ring-2 ring-warn" : ""
            }`}
          >
            <span className="rounded-full bg-surface px-2 py-0.5 text-xs font-semibold text-warn">
              {behind ? "알린 내용 확인" : "오늘 다시 확인"}
            </span>
            <span className="min-w-0 break-keep text-ink">
              {behind
                ? "고객에게 알린 내용과 지금 기록이 달라요"
                : [day ? `${day}이 다시 확인할 날` : "다시 확인할 날이 됐어요", recorded ? `${recorded} 「접수·진행 중」 기록` : null]
                    .filter(Boolean)
                    .join(" · ")}
            </span>
            <Link
              to={checkHref(w)}
              className="ml-auto shrink-0 font-semibold text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              진행 결과 적기 ›
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
