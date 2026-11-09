import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../lib/apiClient";
import { channelShort } from "../../lib/copy/customerOps";
import { listDay } from "../../lib/format";
import type { InquiryRowItem, ReviewRecordRow } from "../../lib/types";

/** How many arrivals the Home shows when 할 일 is empty. */
export const RECENT_ARRIVALS = 5;

export interface Arrival {
  key: string;
  kind: "리뷰" | "문의";
  channel: string | null;
  /** KST day (`YYYY-MM-DD`) the customer wrote it — the sort key and the row's date. */
  day: string | null;
  /** Sort key at the finest grain each read has: an inquiry's instant, a review's day. */
  at: string;
  product: string | null;
  text: string | null;
  /** What has happened to it — never a call to act; this list is not 할 일. Null when the channel did not say. */
  state: string | null;
  to: string;
}

const KST_DAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" });

function kstDay(instant: string): string | null {
  const at = Date.parse(instant);
  return Number.isFinite(at) ? KST_DAY.format(new Date(at)) : null;
}

const REVIEW_STATE: Record<string, string> = { ANSWERED: "답글 있음", PENDING: "답글 없음" };
const INQUIRY_STATE: Record<string, string> = { ANSWERED: "답변함", UNANSWERED: "답변 필요" };

/**
 * <b>The reviews and inquiries that came in last, newest first — merged from the two reads the 리뷰·문의 screens
 * already make</b> (product-owner, 2026-10-28). Pure, so the order is testable without a screen.
 */
export function mergeArrivals(
  reviews: readonly ReviewRecordRow[],
  inquiries: readonly InquiryRowItem[],
  limit = RECENT_ARRIVALS,
): Arrival[] {
  const fromReviews: Arrival[] = reviews.map(({ review, channelCode, channelNameKo }) => ({
    key: `review:${review.id}`,
    kind: "리뷰",
    channel: channelShort(channelCode ?? channelNameKo),
    day: review.writtenOn,
    // A review carries its day only; it sorts as the end of that day so a same-day inquiry with a time is not
    // pushed above it by a missing clock.
    at: review.writtenOn ? `${review.writtenOn}T23:59:59+09:00` : "",
    product: review.productName,
    text: review.preview,
    state: review.channelReplyState ? (REVIEW_STATE[review.channelReplyState] ?? null) : null,
    to: `/reviews/reply/${review.id}`,
  }));
  const fromInquiries: Arrival[] = inquiries.map((row) => ({
    key: `inquiry:${row.inquiryId}`,
    kind: "문의",
    channel: channelShort(row.channelCode ?? row.channelNameKo),
    day: kstDay(row.receivedAt),
    at: row.receivedAt,
    product: row.productName,
    text: row.title ?? row.snippet,
    state: INQUIRY_STATE[row.status] ?? null,
    to: `/inquiries/${row.inquiryId}`,
  }));
  const time = (a: Arrival) => {
    const t = Date.parse(a.at);
    return Number.isFinite(t) ? t : -Infinity;
  };
  return [...fromReviews, ...fromInquiries].sort((a, b) => time(b) - time(a)).slice(0, limit);
}

/**
 * <b>「최근 들어온 리뷰·문의」 — what the Home shows when 할 일 is empty</b> (product-owner, 2026-10-28: 「새로 할
 * 일이 없으면 최근 들어온 문의/리뷰를 보여주자. 홈 화면이 너무 비네」).
 *
 * <p>Two weeks became the one window for every kind of work the same day, and on a store whose last fortnight is
 * quiet the Home was a sentence and a count. This is not work and must not read as work: the heading says what it
 * is, the sentence under it says nothing is owed, and each row says what already happened to it (답변함 · 답글
 * 있음) rather than what to do. It is drawn from the two reads the 리뷰·문의 screens already make, newest first;
 * when either read fails its half is simply absent, and when both fail the section is not drawn.
 */
export function RecentArrivals({ now = new Date() }: { now?: Date }) {
  const [rows, setRows] = useState<Arrival[] | null>(null);

  useEffect(() => {
    let live = true;
    const quiet = <T,>(read: () => Promise<T>) => Promise.resolve().then(read).catch(() => null);
    void Promise.all([
      quiet(() => api.getReviewRecordStrict({ sort: "newest", size: RECENT_ARRIVALS })),
      quiet(() => api.getInquiryRowsStrict({ order: "NEWEST", limit: RECENT_ARRIVALS })),
    ]).then(([reviews, inquiries]) => {
      if (!live) return;
      if (!reviews && !inquiries) {
        setRows([]);
        return;
      }
      setRows(mergeArrivals(reviews?.items ?? [], inquiries?.items ?? []));
    });
    return () => {
      live = false;
    };
  }, []);

  if (!rows || rows.length === 0) return null;
  return (
    <section aria-label="최근 들어온 리뷰·문의" className="rounded-2xl border border-line bg-surface p-3" data-testid="recent-arrivals">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">최근 들어온 리뷰·문의</h2>
        <Link
          to="/reviews"
          className="ml-auto shrink-0 text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          전체 보기 →
        </Link>
      </div>
      <p className="mt-1 break-keep text-sm text-muted">할 일은 아니에요. 무엇이 들어왔는지만 보여 드려요.</p>
      <ul className="mt-2 divide-y divide-line">
        {rows.map((row) => (
          <li key={row.key}>
            <Link
              to={row.to}
              className="block break-keep py-2 text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
            >
              <span className="flex items-baseline gap-2">
                <span className="shrink-0 font-semibold">{row.kind}</span>
                {row.channel ? <span className="shrink-0 text-sm text-muted">{row.channel}</span> : null}
                <span className="min-w-0 flex-1 truncate text-sm text-muted">
                  {[row.product, row.text].filter(Boolean).join(" · ")}
                </span>
                {row.state ? <span className="shrink-0 text-sm text-muted">{row.state}</span> : null}
                <span className="shrink-0 text-sm tabular-nums text-muted">{listDay(row.day, now)}</span>
                <span className="shrink-0 text-muted" aria-hidden="true">›</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
