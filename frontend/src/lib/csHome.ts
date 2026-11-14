import type { Tally, WorkPage } from "./homeBoard";
import type { CustomerOperationsGapRow } from "./customerOperationsTypes";
import type { ChannelCoverageRowView, ChannelMetricRow, MetricKpi, SellerAccountResponse } from "./types";
import { failureShort, gapActionKo } from "./copy/customerOps";
import { dataTypeKo } from "./customerOperations";

/**
 * <b>홈 — CS 현황</b> (product-owner decision 2026-11-13; `docs/pilot/pilot_usage_loop_v1.md` §8: 업무 현황 요약은 홈,
 * 시간 절감·성과 추세는 별도 화면). The rules this screen's numbers obey, kept apart from the page so they can be read
 * and tested without one.
 *
 * <p><b>One way to write a number</b> — the board's ({@link Tally}): exact when the server vouches for it, 「N+」 when
 * it vouches only for a floor, 「—」 when it vouches for nothing. A collection gap does NOT turn every number on the
 * screen into a floor: the 할 일 counts are exact whenever the server read all of its own sources, and the gap is said
 * once, by the channel it belongs to.
 *
 * <p><b>Every number is a doorway to the list it counts</b>, under the same condition: 오늘 접수 opens the same Korean
 * day the overview counted, 답할 일 opens {@code /work?work=answer}, 오늘 확인 opens {@code ?work=todayChecks}.
 */

/** A KPI of `/api/dashboard/overview` as a tally: exact only when no channel was left out and freshness is proven. */
export function kpiTally(kpi: MetricKpi | undefined | null): Tally | null {
  if (!kpi) return null;
  const exact = kpi.excludedChannels === 0 && !kpi.freshnessUnproven;
  return { count: exact ? kpi.value : null, atLeast: kpi.value };
}

/** Two tallies added: exact only if both are. Either missing is a missing sum — never half of one. */
export function sumTally(a: Tally | null | undefined, b: Tally | null | undefined): Tally | null {
  if (!a || !b) return null;
  return {
    count: a.count != null && b.count != null ? a.count + b.count : null,
    atLeast: a.atLeast + b.atLeast,
  };
}

/** What one channel row says for one CS figure — exact, a floor, or nothing it can vouch for. */
export type ChannelFigure = { tally: Tally; unsupported: boolean };

/**
 * One channel's figure, told the way the overview's own verdicts allow:
 * not counted → 「—」 (silent, never drawn as zero); counted but not proven fresh → a floor; counted and fresh → exact.
 */
export function channelFigure(row: ChannelMetricRow, which: "inquiries" | "reviews" | "unanswered"): ChannelFigure {
  const state = which === "reviews" ? row.reviewState : row.inquiryState;
  const value = which === "inquiries" ? row.inquiries : which === "reviews" ? row.reviews : row.unansweredInquiries;
  const counted =
    which === "inquiries" ? row.countedInInquiries : which === "reviews" ? row.countedInReviews : row.countedInUnansweredNow;
  if (state === "NOT_SUPPORTED") return { tally: { count: null, atLeast: 0 }, unsupported: true };
  if (!counted) return { tally: { count: null, atLeast: 0 }, unsupported: false };
  const fresh = state === "OBSERVED_FRESH" || state === "ZERO";
  return { tally: { count: fresh ? value : null, atLeast: value }, unsupported: false };
}

export type ChannelHealth = {
  tone: "ok" | "warn" | "off";
  /** The short state word beside the channel — absent when nothing is wrong. */
  badge: string | null;
  line: string;
  /** The way to fix it, always an existing route: the channel's own page, the connect hub, or the server's gap link. */
  action: { label: string; to: string } | null;
};

const CS_TYPES = new Set(["INQUIRY", "REVIEW"]);

/**
 * <b>Which channel needs a look, and the existing way to fix it.</b> Nothing here starts a collection or changes a
 * rule: every action is a link to a page that already owns the fix — the server's own gap link when 고객 운영 관리
 * names one, otherwise the channel's page (`/connect/channels/:accountId`), or the connect hub for a channel never
 * connected. The cause decides the verb: a broken sign-in is 다시 연결, a failed or partial read is 다시 가져오기.
 */
export function channelHealth(
  row: ChannelMetricRow,
  coverage: readonly ChannelCoverageRowView[] | null | undefined,
  accounts: readonly SellerAccountResponse[] | null | undefined,
  gaps: readonly CustomerOperationsGapRow[] | null | undefined,
  now: Date,
): ChannelHealth {
  if (!row.connected) {
    return { tone: "off", badge: "연결 안 됨", line: "연결하면 리뷰와 문의를 가져와요", action: { label: "연결하기", to: "/connect" } };
  }
  const account = accounts?.find((a) => a.channelNameKo === row.channelNameKo) ?? null;
  const page = account ? `/connect/channels/${account.id}` : "/connect";
  const gap = gaps?.find((g) => g.channelCode === row.channelCode) ?? null;
  if (gap) {
    const types = gap.dataTypes.map(dataTypeKo).join("·") || "자료";
    return {
      tone: "warn",
      badge: failureShort(gap.reason),
      line: `${types}가 숫자에서 빠졌어요`,
      action: { label: gapActionKo(gap.reason), to: gap.to },
    };
  }
  // A data type counts when the product collects it OR something has collected it: NAVER reviews are `supported:
  // false` for the connector yet read by other lanes, and a failed read there is still this channel's problem.
  const rows = (coverage ?? []).filter(
    (c) =>
      c.channelCode === row.channelCode &&
      CS_TYPES.has(c.dataType) &&
      (c.supported || c.latestAttemptAt != null || c.lastSuccessfulSyncAt != null),
  );
  const named = (pick: (c: ChannelCoverageRowView) => boolean) =>
    rows.filter(pick).map((c) => dataTypeKo(c.dataType)).join("·");
  const auth = named((c) => c.latestAttemptOutcome === "AUTH_REQUIRED");
  if (auth) return { tone: "warn", badge: "다시 연결 필요", line: `${auth} 연결이 끊겼어요`, action: { label: "다시 연결", to: page } };
  const paused = named((c) => c.routinePausedBy != null);
  if (paused) return { tone: "warn", badge: "수집 멈춤", line: `${paused} 자동 수집이 멈췄어요`, action: { label: "확인하기", to: page } };
  const failed = named((c) => c.latestAttemptOutcome === "FAILED" || c.state === "BLOCKED");
  if (failed) return { tone: "warn", badge: "확인하지 못함", line: `${failed}를 가져오지 못했어요`, action: { label: "다시 가져오기", to: page } };
  const partial = named((c) => c.latestAttemptOutcome === "PARTIAL");
  if (partial) return { tone: "warn", badge: "일부만 확인", line: `${partial}를 일부만 가져왔어요`, action: { label: "다시 가져오기", to: page } };

  const last = rows
    .map((c) => (c.lastSuccessfulSyncAt ? Date.parse(c.lastSuccessfulSyncAt) : NaN))
    .filter((t) => !Number.isNaN(t))
    .sort((a, b) => b - a)[0];
  if (last === undefined) return { tone: "ok", badge: null, line: "아직 가져온 적 없어요", action: { label: "확인하기", to: page } };
  return { tone: "ok", badge: null, line: `${ago(last, now)}에 가져왔어요`, action: null };
}

function ago(at: number, now: Date): string {
  const minutes = Math.max(0, Math.floor((now.getTime() - at) / 60_000));
  if (minutes < 1) return "방금 전";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

/** 「11:52」 in Korea — when the board was read. */
export function kstClock(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return null;
  return new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hour12: false }).format(t);
}

/**
 * <b>Was the work list read whole?</b> An empty first page proves 「할 일 없음」 only when every source behind it was
 * read, and read to the end, and the server vouches for its total. Anything less and the page may simply not have
 * reached the work — the home must not call that 「모두 처리했어요」.
 */
export function workReadWhole(page: Pick<WorkPage, "sources" | "total"> | null | undefined): boolean {
  if (!page) return false;
  if (page.total.count == null) return false;
  return page.sources.every((s) => s.read && s.complete);
}

/** What the home's empty 「먼저 처리할 일」 says — the strongest true sentence, never more than was read. */
export function firstEmptyLine(readWhole: boolean, channelTrouble: boolean): string {
  if (!readWhole) return "할 일을 다 불러오지 못했어요. 남은 일이 있을 수 있으니 할 일 화면에서 확인해 주세요.";
  if (channelTrouble) return "가져온 할 일은 모두 처리했어요. 아직 확인하지 못한 자료에 남은 일이 있을 수 있어요.";
  return "지금 할 일이 없어요. 새 리뷰나 문의가 오면 여기에 올라와요.";
}
