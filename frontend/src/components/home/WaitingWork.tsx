import { useState } from "react";
import { Link } from "react-router-dom";
import type { InquiryFollowUpRow, InquiryFollowUps } from "../../lib/types";
import { channelShort } from "../../lib/copy/customerOps";

/**
 * <b>기다리는 일 — 답변은 정했고, 끝나지 않은 일</b> (2026-11-03 sixth review).
 *
 * <p>Answering the customer and finishing the action are two facts. A reply that tells the customer a refund is in
 * progress can be registered while the refund is still open, so an approved inquiry does not leave the seller's sight:
 * it waits here — its registration not yet confirmed, its action still in progress, or the customer's reply still to
 * come — and every row opens the same inquiry, where the approved answer and the record are kept as they are.
 *
 * <p><b>Nothing here is the seller's turn yet.</b> When the next-check day comes the row leaves this section and joins
 * 확인 필요 above as 「다시 확인」 — a check for the seller, never a send. The section draws nothing while there is nothing
 * waiting, and says it could not read rather than show an empty list when the read failed.
 */
export const HOME_WAITING_ROWS = 3;

export function waitingLabel(row: InquiryFollowUpRow): string {
  switch (row.waitingOn) {
    case "REGISTRATION":
      return "답변 등록 확인 중";
    case "ACTION":
      return "조치 진행 중";
    case "CUSTOMER":
      return "고객 회신 대기";
    case "TOLD_BEHIND":
      return "고객 안내 확인";
  }
}

/** The right-hand fact: when it comes back, or what it waits for. */
export function waitingState(row: InquiryFollowUpRow): string | null {
  if (row.waitingOn === "CUSTOMER") return "회신 받으면 결과 고치기";
  if (row.outcome === "IN_PROGRESS" && row.nextCheckOn) return `${shortDay(row.nextCheckOn)} 다시 확인`;
  return null;
}

function shortDay(day: string): string {
  const [y, m, d] = day.split("-").map(Number);
  const w = "일월화수목금토"[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return `${m}월 ${d}일 (${w})`;
}

export function WaitingWork({ followUps }: { followUps: InquiryFollowUps | null | undefined }) {
  const [all, setAll] = useState(false);
  if (followUps === undefined) return null;
  if (followUps === null) {
    return (
      <section aria-label="기다리는 일" className="rounded-2xl border border-line bg-surface p-3" data-testid="waiting-work">
        <h2 className="text-section font-bold tracking-tight text-ink">기다리는 일</h2>
        <p className="mt-1 break-keep text-sm text-muted">기다리는 일을 불러오지 못했어요. 승인한 문의는 문의 화면에서 열 수 있어요.</p>
      </section>
    );
  }
  const rows = followUps.waiting;
  if (rows.length === 0) return null;
  const total = followUps.waitingTotal;
  const shown = all ? rows : rows.slice(0, HOME_WAITING_ROWS);
  return (
    <section aria-label="기다리는 일" className="rounded-2xl border border-line bg-surface p-3" data-testid="waiting-work">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">기다리는 일</h2>
        <span className="break-keep text-sm tabular-nums text-muted">
          {total > shown.length
            ? `${total.toLocaleString("ko-KR")}건 중 ${shown.length.toLocaleString("ko-KR")}건`
            : `${total.toLocaleString("ko-KR")}건`}
        </span>
        {rows.length > HOME_WAITING_ROWS ? (
          <button
            type="button"
            onClick={() => setAll((v) => !v)}
            className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            {all ? "접기" : "모두 보기"}
          </button>
        ) : null}
      </div>
      <p className="mt-1 break-keep text-sm text-muted">지금은 사장님 차례가 아니에요. 정한 날이 되면 할 일로 다시 올라와요.</p>
      <ul className="mt-2 divide-y divide-line">
        {shown.map((row) => {
          const state = waitingState(row);
          const channel = row.channelCode ? channelShort(row.channelCode) : null;
          return (
            <li key={row.workItemId}>
              <Link
                to={row.to}
                className="block break-keep py-2 text-ink hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
              >
                <span className="flex items-baseline gap-2">
                  <span className="shrink-0 break-keep font-semibold">{waitingLabel(row)}</span>
                  <span className="min-w-0 flex-1 truncate text-sm text-muted">
                    {[channel, row.subject].filter(Boolean).join(" · ") || "문의"}
                  </span>
                  {state ? <span className="hidden shrink-0 text-sm text-muted sm:inline">{state}</span> : null}
                  <span className="shrink-0 text-muted" aria-hidden="true">
                    ›
                  </span>
                </span>
                {/* On a phone the subject keeps the line; when it comes back goes under it (2026-11-03 check: 「[Q…」). */}
                {state ? <span className="mt-0.5 block text-sm text-muted sm:hidden">{state}</span> : null}
              </Link>
            </li>
          );
        })}
      </ul>
      {total > rows.length ? (
        <p className="mt-1 break-keep text-sm text-muted">
          {(total - rows.length).toLocaleString("ko-KR")}건은 더 있어요. 문의 화면의 답변함 목록에서 찾을 수 있어요.
        </p>
      ) : null}
    </section>
  );
}
