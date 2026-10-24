import { Link } from "react-router-dom";
import { Status, type StatusTone } from "../ui/Status";
import { DismissedReplyWork } from "../DismissedReplyWork";
import type { OperatorVocItem, ReplyBoardRow, ReviewWorkAccount } from "../../lib/types";

// 할 일's 「진행 중」 and 「끝난 일」 (mockup ③, confirmed 2026-10-25). 진행 중 is what a delegate, the PC's check or the
// channel still has to finish — never counted with the seller's own work. 끝난 일 is the last 30 days of what ended.
// A delegate's post stays 진행 중 until the channel's own export shows a reply; time never completes it. Words are
// the review panel's (DelegatedReplyControls): short labels, no sentence a label can carry.

/** 「10:12」 today, 「10/23」 before. */
function when(iso: string, now: Date): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  const sameDay = at.toDateString() === now.toDateString();
  return sameDay
    ? at.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false })
    : `${at.getMonth() + 1}/${at.getDate()}`;
}

/** The chip and the one line under a lane row. */
function wording(row: ReplyBoardRow, now: Date): { chip: string; tone: StatusTone; line: string } {
  const at = when(row.since, now);
  switch (row.state) {
    case "APPROVED":
      return { chip: "담당자 대기", tone: "info", line: `${at} 요청` };
    case "CLAIMED":
      return { chip: "등록하는 중", tone: "info", line: `${at} 시작` };
    case "CANCEL_REQUESTED":
      return { chip: "취소 요청 중", tone: "warn", line: "담당자 응답 대기" };
    case "HOLD_REQUESTED":
      return { chip: "멈춤 요청 중", tone: "warn", line: "담당자가 등록 전인지 확인 중" };
    case "UNKNOWN":
      return { chip: "결과 확인 중", tone: "warn", line: "담당자가 등록 여부 확인 중 · 중복 방지로 다시 요청 불가" };
    case "POSTED":
      if (row.channelAnswered) return { chip: "네이버 확인", tone: "good", line: `담당자 등록 · 엑셀에서 확인` };
      return row.confirmOverdue
        ? { chip: "운영팀 확인", tone: "warn", line: "등록 후 네이버에 오래 안 보임 · 운영팀 확인 중" }
        : { chip: "등록함", tone: "neutral", line: `${at} 등록 · 다음 엑셀에서 네이버 확인` };
    case "CANCELLED":
      return { chip: "요청 취소함", tone: "neutral", line: at };
    case "INVALIDATED":
      return { chip: "요청 끝남", tone: "neutral", line: "리뷰가 바뀌었거나 이미 답글이 있음" };
    default:
      return { chip: row.state, tone: "neutral", line: "" };
  }
}

function Row({ to, text, chip, tone, line }: { to: string; text: string; chip: string; tone: StatusTone; line: string }) {
  return (
    <li>
      <Link
        to={to}
        className="block rounded-lg px-2 py-3 transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
      >
        <span className="flex items-start gap-3">
          <span className="line-clamp-2 min-w-0 flex-1 break-keep text-sm leading-snug text-ink [overflow-wrap:anywhere]">
            {text}
          </span>
          <Status tone={tone} className={tone === "neutral" ? "ring-1 ring-line" : ""}>
            {chip}
          </Status>
        </span>
        {line ? <span className="mt-1 block text-xs text-muted">{line}</span> : null}
      </Link>
    </li>
  );
}

function laneRow(row: ReplyBoardRow, now: Date) {
  const w = wording(row, now);
  const text = [row.preview ?? "", row.rating == null ? "" : `★${row.rating}`].filter(Boolean).join(" · ");
  return <Row key={row.reviewId} to={`/reviews/reply/${row.reviewId}?from=work`} text={text} {...w} />;
}

/** A reply the seller posted themselves and recorded — confirmed when the channel's export shows a reply. */
function recordedRow(item: OperatorVocItem) {
  const answered = item.replyStatus === "ANSWERED";
  const text = [item.safePreview ?? "", item.rating == null ? "" : `★${item.rating}`].filter(Boolean).join(" · ");
  return (
    <Row
      key={item.actionRef ?? item.reviewId}
      to={item.reviewId ? `/reviews/reply/${item.reviewId}?from=work` : "/reviews"}
      text={text}
      chip={answered ? "네이버 확인" : "직접 등록함"}
      tone={answered ? "good" : "neutral"}
      line={answered ? "직접 등록 · 엑셀에서 확인" : "직접 등록했다고 기록 · 네이버 확인 전"}
    />
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title} className="space-y-1">
      <h2 className="text-sm font-semibold text-muted">{title}</h2>
      <ul className="-mx-2 divide-y divide-line">{children}</ul>
    </section>
  );
}

export function MovingList({ rows, now = new Date() }: { rows: ReplyBoardRow[]; now?: Date }) {
  if (rows.length === 0) return <p className="break-keep text-sm text-muted">진행 중인 일이 없습니다.</p>;
  return (
    <ul aria-label="진행 중" className="-mx-2 divide-y divide-line">
      {rows.map((r) => laneRow(r, now))}
    </ul>
  );
}

export function FinishedList({
  rows,
  accounts,
  onRestored,
  now = new Date(),
}: {
  rows: ReplyBoardRow[];
  accounts: ReviewWorkAccount[];
  onRestored?: () => void;
  now?: Date;
}) {
  const recorded = accounts.flatMap((a) => a.recentlyReported);
  const confirmed = rows.filter((r) => r.state === "POSTED");
  const confirmedRecorded = recorded.filter((i) => i.replyStatus === "ANSWERED");
  const unconfirmedRecorded = recorded.filter((i) => i.replyStatus !== "ANSWERED");
  const ended = rows.filter((r) => r.state !== "POSTED");
  const nothing = rows.length === 0 && recorded.length === 0;
  return (
    <div className="space-y-5">
      {nothing ? <p className="break-keep text-sm text-muted">최근 30일 동안 끝난 일이 없습니다.</p> : null}
      {confirmed.length + confirmedRecorded.length > 0 ? (
        <Section title="네이버에 답글 있음">
          {confirmed.map((r) => laneRow(r, now))}
          {confirmedRecorded.map(recordedRow)}
        </Section>
      ) : null}
      {unconfirmedRecorded.length > 0 ? (
        <Section title="직접 등록했다고 기록">{unconfirmedRecorded.map(recordedRow)}</Section>
      ) : null}
      {ended.length > 0 ? <Section title="요청 취소 · 끝남">{ended.map((r) => laneRow(r, now))}</Section> : null}
      {accounts.map((account) => (
        <div key={account.accountId}>
          {accounts.length > 1 ? (
            <p className="text-sm font-semibold text-muted">{account.channelNameKo ?? account.channelCode}</p>
          ) : null}
          <DismissedReplyWork accountId={account.accountId} refreshSignal={0} onRestored={() => onRestored?.()} />
        </div>
      ))}
    </div>
  );
}
