import { elapsedSince } from "./elapsed";

export function won(amount: number): string {
  return `₩${amount.toLocaleString("ko-KR")}`;
}

export function wonShort(amount: number): string {
  if (amount >= 100_000_000) {
    return `${(amount / 100_000_000).toFixed(1)}억`;
  }
  if (amount >= 10_000) {
    return `${Math.round(amount / 10_000).toLocaleString("ko-KR")}만`;
  }
  return amount.toLocaleString("ko-KR");
}

export function count(n: number): string {
  return n.toLocaleString("ko-KR");
}

/**
 * When something happened, as a person would say it — 「17분 전」.
 *
 * The rung comes from {@link elapsedSince}, shared with `waitedLabel`, so a list row and the pane
 * beside it never disagree about the same timestamp. Only the wording is this function's own.
 */
export function relativeTime(iso: string | null): string {
  const elapsed = elapsedSince(iso);
  if (!elapsed) {
    return "-";
  }
  switch (elapsed.unit) {
    case "just":
      return "방금 전";
    case "minute":
      return `${elapsed.value}분 전`;
    case "hour":
      return `${elapsed.value}시간 전`;
    case "day":
      return `${elapsed.value}일 전`;
    case "week":
      return `${elapsed.value}주 전`;
    case "month":
      return `${elapsed.value}개월 전`;
    default:
      return "1년 넘음";
  }
}

export function shortDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/**
 * A history row's date, with the year.
 *
 * `shortDate`'s `M/D` is right for something recent, and wrong for a list that can reach back past a
 * year: a row from last May would read exactly like one from this May. History says the year.
 */
export function importDate(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())}`;
}

/** Future counterpart of relativeTime — "다음 수집 5분 후" style. */
export function untilTime(iso: string | null): string {
  if (!iso) {
    return "-";
  }
  const diffMin = Math.round((new Date(iso).getTime() - Date.now()) / 60000);
  if (diffMin < 1) {
    return "곧";
  }
  if (diffMin < 60) {
    return `${diffMin}분 후`;
  }
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) {
    return `${diffHr}시간 후`;
  }
  return `${Math.round(diffHr / 24)}일 후`;
}

/** Today in Asia/Seoul as `YYYY-MM-DD` — the seller's own day, never the browser's. */
export function kstToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(now);
}

/**
 * A calendar date the seller would write, from an instant — in the seller's own day, not UTC's.
 *
 * `iso.slice(0, 10)` on a stored instant is the UTC date, which is yesterday for anything that happened
 * before 09:00 in Seoul. Measured on the product knowledge library, 2026-09-05 01:xx KST: a note the
 * seller had just saved was dated 2026-09-04 (Full Pilot Walkthrough v1). Same day rule as the review
 * import calendar on the backend (Asia/Seoul, one zone, no setting).
 */
export function kstDate(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) {
    return iso.slice(0, 10);
  }
  return new Date(ms).toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
}

/** KST 달력 조각 — 한 시각을 서울의 날짜·시각으로 나눈 것. 브라우저의 시간대가 아니다. */
function kstParts(iso: string): { month: string; day: string; hour: string; minute: string } | null {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) {
    return null;
  }
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Seoul",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(ms));
  const at = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return { month: at("month"), day: at("day"), hour: at("hour"), minute: at("minute") };
}

/** 「8월 16일」 — 날짜 하나를 사람이 말하는 모양으로. 연도는 말하지 않는다. */
export function kstMonthDay(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const day = kstDate(iso);
  const [, m, d] = day.split("-");
  if (!m || !d) {
    return day;
  }
  return `${Number(m)}월 ${Number(d)}일`;
}

/**
 * 「9월 5일 20:30」 — 시각까지 말해야 하는 자리(마지막 수집)에서, 판매자의 시간대로.
 *
 * 같은 수집을 서울에서 보는 사람과 다른 곳에서 보는 사람이 다른 날짜로 읽으면, 「그때까지 읽은
 * 것입니다」라는 문장이 두 가지 뜻이 된다.
 */
export function kstDayTime(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const p = kstParts(iso);
  return p ? `${Number(p.month)}월 ${Number(p.day)}일 ${p.hour}:${p.minute}` : iso;
}

/** 「9/5 19:57」 — 한 열에 여럿이 세로로 서는 자리(결제 시각). */
export function kstShortDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const p = kstParts(iso);
  return p ? `${Number(p.month)}/${Number(p.day)} ${p.hour}:${p.minute}` : iso;
}

/** 「9/5」 — 날짜만으로 충분한 자리(마지막 확인). */
export function kstShortDate(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const p = kstParts(iso);
  return p ? `${Number(p.month)}/${Number(p.day)}` : iso;
}

/**
 * 「2026-09-03 16:24」 — 레코드 한 장 안에서 시각을 적는 자리.
 *
 * 목록의 「9월 5일 20:30」과 달리 연도를 적는다. 상세는 한 주문의 이력이 세로로 서는 화면이고, 해를
 * 넘긴 주문에서 월·일만으로는 어느 해인지 읽히지 않는다.
 */
export function kstDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return "-";
  }
  const p = kstParts(iso);
  return p ? `${kstDate(iso)} ${p.hour}:${p.minute}` : iso;
}
