import { useEffect, useRef, useState, type ReactNode } from "react";
import { api } from "../../lib/apiClient";
import { useApiData } from "../../lib/useApiData";
import type { CollectNowPath, LocalAgentRunState, ScreenReadView } from "../../lib/types";
import { backendCode, backendMessage } from "./channelShared";
import {
  awaitSignIn,
  checkSignIn,
  signInMessage,
  signInStartMessage,
  startSignIn,
  SIGN_IN_ROUNDS,
  watchSignIn,
} from "../../lib/connect/signInRecovery";
import { kstDate, kstHourMinute, kstMonthDay } from "../../lib/format";

/**
 * <b>「지금 수집하기」 — 이 제품에서 한 자료를 가져오는 사용자 동작은 이것 하나다.</b>
 *
 * <p>왜 하나여야 하는가. 2026-10-07 라이브에서 판매자가 쿠팡 리뷰를 가져오려고 화면에서 가장 강한 버튼을
 * 눌렀고, 그 버튼은 이 경로가 아니었다 — 안내 카드의 「리뷰 수집 연결하기」였다. 같은 자료에 수집처럼 보이는
 * 입구가 둘 있었고, 눈에 먼저 드는 쪽이 옛 경로였다. 결과는 `AUTH_REQUIRED`로 정직하게 끝났지만,
 * 「무엇을 누를지 판매자가 고르게 하는 화면」이 그 자리에 있었다는 사실은 남는다. 그래서 수집의 primary는
 * 이 컴포넌트 하나이고, 나머지는 막힌 걸음을 푸는 복구 동작이다.
 *
 * <p><b>경로는 서버가 정한다.</b> 이 컴포넌트에는 채널 이름이 없다. 한 번의 press가 `POST /collect-now`이고,
 * 공식 API가 canonical인 자료면 서버가 pull을 돌리고, 판매자 센터 화면 읽기가 canonical이면 이 컴퓨터의
 * 도우미에게 한 건을 맡긴다. 화면은 그 답을 따르기만 한다 — 채널 이름으로 분기하던 구조가 바로 이번에
 * 걷어낸 결함이었고, 그때 네 번째 채널은 코드를 고쳐야 버튼이 생기는 채널이 됐다.
 *
 * <p><b>press가 곧 승인이다.</b> 제품에 로그인한 판매자가 자기 가게의 자료를 한 번 가져오라고 누른 것이
 * 그 bounded READ의 operator authorization이다. 그 위에 얹을 의례는 없다.
 */

/** 화면 읽기는 도우미가 받아 가는 일이라, 누른 사람에게 결과를 말하려면 상태를 다시 물어야 한다. */
const SCREEN_READ_POLL_MS = 2000;
/** 90초. 작업 자체의 수명(10분)이 아니라, 버튼 앞에 서 있는 사람이 기다릴 만한 시간. */
const SCREEN_READ_POLL_LIMIT = 45;
/**
 * 6분. 밀린 기간을 차례로 읽는 중이면 한 건이 아니라 여러 건이고, 서버가 한 번의 누름에 허용하는 시간이
 * 정확히 이만큼이다. 한 건짜리 기준으로 지켜보면 세 번째 기간쯤에서 보기를 그만두고, 끝난 적 없는 수집을
 * 끝난 것처럼 말하게 된다.
 */
const CATCH_UP_POLL_LIMIT = 180;

async function awaitScreenRead(first: ScreenReadView): Promise<ScreenReadView> {
  let latest = first;
  const limit = first.catchUp ? CATCH_UP_POLL_LIMIT : SCREEN_READ_POLL_LIMIT;
  for (let i = 0; i < limit; i += 1) {
    let next: ScreenReadView | null = null;
    try {
      next = await api.screenReadStatus(latest.jobId);
    } catch {
      // 상태를 못 읽은 것은 수집이 실패한 것과 다르다. 마지막으로 아는 상태를 그대로 돌려준다.
      return latest;
    }
    latest = next;
    if (latest.state !== "RUNNING") return latest;
    await new Promise((resolve) => setTimeout(resolve, SCREEN_READ_POLL_MS));
  }
  return latest;
}

/**
 * <b>막힌 자리를 판매자의 문장으로 — 내부 단어는 한 글자도 나오지 않는다.</b>
 *
 * <p>2026-10-08 첫 historical catch-up이 기간 선택 컨트롤을 식별하지 못해 멈췄고, 화면이 할 수 있는 말은
 * 「수집하지 못했습니다」뿐이었다. 그 문장은 참이지만 아무것도 알려주지 않는다 — 판매자가 그 다음에 할 수
 * 있는 일이 있는지조차. 그래서 막힌 자리를 아는 경우에는 그 자리를 말한다.
 *
 * <p>모르는 단어는 지어내지 않고 일반 문장으로 돌아간다. 그리고 selector·locator·candidate 같은 말은
 * 어느 분기에도 없다: 판매자에게 그것은 우리 쪽 책상의 이름이다.
 */
export function stopSentence(failureCode: string | null | undefined): string | null {
  switch (failureCode) {
    case "DATE_CONTROL_CANDIDATES_UNREADABLE":
    case "RANGE_CONTROLS_NOT_FOUND":
    case "RANGE_CONTROLS_AMBIGUOUS":
    case "QUERY_CONTROL_NOT_FOUND":
    case "QUERY_CONTROL_AMBIGUOUS":
    case "RANGE_ORDER_UNKNOWN":
    case "RANGE_NOT_SETTABLE":
    case "CALENDAR_OPENER_NOT_FOUND":
    case "CALENDAR_OPENER_AMBIGUOUS":
      return "판매자센터 화면에서 기간 선택 영역을 확인하지 못했습니다.";
    // 달력은 열렸지만 그 안을 믿을 수 없었던 경우. 판매자가 지금 할 수 있는 일은 위와 같지만, 다음에 고치러
    // 갈 자리는 전혀 다르다 — 그래서 화면 문장은 달력을 가리키고, 어느 시험에서 멈췄는지는 failureCode가
    // 기록으로 남긴다. 화면에는 내부 용어를 쓰지 않는다.
    case "PICKER_VIEW_UNREADABLE":
    case "MONTH_NAV_NOT_FOUND":
    case "MONTH_NAV_AMBIGUOUS":
    case "MONTH_NAV_UNVERIFIED":
    case "MONTH_NAV_EXHAUSTED":
    case "DAY_CELL_NOT_FOUND":
    case "DAY_CELL_AMBIGUOUS":
      return "판매자센터 달력에서 날짜를 고르지 못했습니다.";
    case "RANGE_MISMATCH":
      // 기간을 바꿨지만 화면이 그 기간을 보여주지 않았다. 판매자가 할 수 있는 일은 같다 — 그 화면을 한 번
      // 열어 보는 것.
      return "판매자센터 화면이 요청한 기간을 보여주지 않았습니다.";
    case "SURFACE_UNEXPECTED":
      return "판매자센터 리뷰 화면을 찾지 못했습니다.";
    case "ROUTE_NOT_READY":
      // 판매자센터는 맞았고 리뷰 화면이 끝까지 그려지지 않았다. 판매자가 할 일은 기다렸다 다시 누르는 것뿐
      // 이므로 「찾지 못했다」로 말하지 않는다 — 그건 화면을 확인하라는 뜻이 되고, 확인할 것이 없다.
      return "판매자센터 리뷰 화면이 아직 열리지 않았습니다.";
    default:
      return null;
  }
}

/**
 * <b>밀린 기간을 차례로 읽는 중이라는 말 — 기간의 날짜는 꺼내지 않는다.</b>
 *
 * <p>판매자가 쓸 수 있는 사실은 두 개다: 지금 밀린 것을 따라잡는 중이라는 것과, 얼마나 왔는지. 「09-03~09-09를
 * 읽고 있습니다」는 우리 쪽 구현의 모양이고, 그걸 읽은 판매자가 할 수 있는 일은 없다.
 */
export function catchUpMessage(read: ScreenReadView): { text: string; isError: boolean } | null {
  const walk = read.catchUp;
  if (!walk) return null;
  switch (walk.runState) {
    case "RUNNING":
      // 「N개 기간 확인됨」은 내부 창 나누기를 간접적으로 꺼내 보인다 — 판매자가 쓸 수 있는 수가 아니고,
      // 그 수가 몇이든 할 일이 같다. 진행 중이라는 사실만 말한다.
      return { text: "밀린 리뷰를 확인하고 있습니다.", isError: false };
    case "COMPLETE":
      return { text: `밀린 리뷰를 모두 확인했습니다. 새로 저장 ${read.inserted ?? 0}건`, isError: false };
    case "PAUSED_AUTH":
      // 앞에서 끝낸 기간은 그대로 남는다 — 로그인하고 이어가면 멈춘 그 기간부터 다시 읽는다. 몇 개를
      // 끝냈는지는 판매자가 할 일을 바꾸지 않는다: 로그인하면 이어진다.
      return { text: "판매자센터 로그인이 필요합니다. 로그인하면 이어서 확인합니다.", isError: true };
    case "STOPPED_SATURATED":
      return {
        text: "일부 기간을 아직 확인하지 못했습니다. 하루에 리뷰가 아주 많은 날이 있어 그 뒤는 남겨 두었습니다.",
        isError: true,
      };
    case "STOPPED_LIMIT":
      return {
        text: "일부 기간을 아직 확인하지 못했습니다. 다시 확인을 누르면 이어서 확인합니다.",
        isError: false,
      };
    default: {
      const why = stopSentence(walk.stopReason);
      return {
        text: why ?? "일부 기간을 아직 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        isError: true,
      };
    }
  }
}

/** 읽기 하나의 결과를 판매자의 문장으로. 구현 이름(recipe·provider·outcome)은 한 글자도 나오지 않는다. */
export function screenReadMessage(label: string, read: ScreenReadView): { text: string; isError: boolean } {
  // 한 번의 누름이 여러 기간을 걷고 있으면, 할 말은 그 걸음에 대한 것이다.
  const walk = catchUpMessage(read);
  if (walk) return walk;
  switch (read.state) {
    case "SUCCESS":
      return {
        text: `${label} 수집 완료: 새로 저장 ${read.inserted ?? 0} · 갱신 ${read.changed ?? 0}`,
        isError: false,
      };
    case "PARTIAL":
      return {
        text: `${label} 수집 완료: 새로 저장 ${read.inserted ?? 0} · 갱신 ${read.changed ?? 0}. 화면에 보이는 최근 구간만 확인했습니다.`,
        isError: false,
      };
    case "AUTH_REQUIRED":
      return {
        text: `판매자 센터 로그인이 필요합니다. 로그인한 뒤 ${label} 수집을 다시 눌러 주세요.`,
        isError: true,
      };
    case "RUNNING":
      return { text: `${label} 수집이 아직 진행 중입니다. 잠시 뒤 다시 확인해 주세요.`, isError: false };
    default: {
      // 「리뷰을(를)」. 조사를 둘 다 적어 두는 것은 둘 중 어느 것도 고르지 않은 것이고, 판매자가 읽는 것은
      // 고르지 않은 그 모양이다. 이 제품의 자료 이름은 받침으로 끝나지 않거나(리뷰) 끝나거나(문의·주문)
      // 하므로, 마지막 글자로 고른다.
      const why = stopSentence(read.failureCode);
      return { text: why ?? `${label}${objectParticle(label)} 수집하지 못했습니다. 잠시 후 다시 시도해 주세요.`, isError: true };
    }
  }
}

/** 을/를 — 받침으로 고른다. 한글이 아니면 「을」이 더 안전하다(「CSV을」보다 어색한 쪽이 적다). */
export function objectParticle(word: string): "을" | "를" {
  const last = word.trim().slice(-1);
  const code = last.charCodeAt(0);
  if (code < 0xac00 || code > 0xd7a3) return "을";
  return (code - 0xac00) % 28 === 0 ? "를" : "을";
}

/** 이 누름 하나의 식별자. 더블클릭·재요청·새로고침이 두 건이 아니라 한 건으로 모이게 하는 값. */
export function requestId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  return uuid ?? `p${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * 도우미가 필요한 자료에서, 지금 누를 수 있는지와 누를 수 없으면 무엇을 하면 되는지.
 *
 * <p>네 상태가 네 가지 다른 할 일로 갈린다. 하나의 문장으로 뭉치면 이미 연결한 판매자가 다시 연결하러 가고,
 * 로그인이 풀린 판매자는 아무것도 하지 않는 버튼 앞에 선다.
 */
export function deskSentence(desk: LocalAgentRunState | null): string {
  switch (desk) {
    case "UNPAIRED":
      return "이 컴퓨터의 도우미를 한 번 연결하면 수집할 수 있습니다.";
    case "BUSY":
      return "이미 수집이 진행 중입니다.";
    case "AUTH_REQUIRED":
      // <b>지난 시도에 대한 문장이고, 지금의 인증 상태가 아니다.</b> 서버가 아는 것은 「마지막 수집 시도가
      // 로그인 벽에서 멈췄다」이지 「지금 로그아웃 상태다」가 아니다 — 후자는 아무도 하지 않은 실시간 확인을
      // 주장하는 말이고, 판매자가 그사이 자기 브라우저에서 로그인했을 수도 있다.
      return "최근 수집 시 로그인이 필요했습니다.";
    default:
      // <b>정상 상태에서는 acquisition 이야기를 하지 않는다.</b> 이 자리에 있던 문장은 「누를 때마다 판매자
      // 센터 화면에서 읽어옵니다. 자동 주기는 없습니다」였다 — 판매자에게 (a) 구현 방식과 (b) 매번 눌러야
      // 한다는 두 가지를 동시에 말했고, 둘 다 이 화면이 할 말이 아니다. 어디까지 확인됐는지는 바로 아래
      // `coverageSentence`가 말하고, 그게 판매자가 쓸 수 있는 유일한 사실이다.
      return "";
  }
}

/** 막힌 걸음을 푸는 복구 동작이 필요한 상태인지. READY·RUNNING에서는 복구할 것이 없다. */
export function needsRecovery(desk: LocalAgentRunState | null): boolean {
  return desk === "UNPAIRED" || desk === "AUTH_REQUIRED";
}

export interface CollectNowRoute {
  loading: boolean;
  /** 서버가 고른 경로. 아직 묻는 중이거나 읽기에 실패하면 `null` — 그때는 아무것도 약속하지 않는다. */
  path: CollectNowPath | null;
  /** 도우미가 필요한 경로에서의 책상 상태. API 경로에서는 `null`. */
  desk: LocalAgentRunState | null;
  /** 실제로 읽은 가장 최근 수집. 한 번의 호출로 같이 오므로 두 사실이 어긋날 수 없다. */
  lastSuccessAt: string | null;
  /** 빠짐없이 확인한 마지막 날. 「언제 봤는가」가 아니라 「어디까지 봤는가」다. */
  coverageThrough: string | null;
  /** 그 경계와 오늘 사이의 미확인 날수. */
  coverageGapDays: number | null;
  /** 이 줄의 무엇인가가 로그인을 기다리고 있는가 — 멈춘 walk이거나, 자동 확인이 벽에 주차돼 있거나. */
  pausedSignIn: boolean;
}

/**
 * 이 계정 × 이 자료를 지금 가져올 수 있는가 — <b>한 정의</b>.
 *
 * <p>`refreshKey`를 올리면 다시 묻는다. 수집이 끝난 직후가 그 자리다: BUSY였던 책상은 비었고, 풀렸던
 * 로그인은 판매자가 그사이 자기 브라우저에서 고쳤을 수 있다.
 */
export function useCollectNowRoute(accountId: string, dataType: string, refreshKey = 0): CollectNowRoute {
  const query = useApiData(
    () => api.collectNowReadiness(accountId, dataType),
    [accountId, dataType, refreshKey],
  );
  // `useApiData`는 deps가 바뀌어도 마지막 성공 payload를 들고 있다. `loading`을 존중하는 것이 한 줄이
  // 다른 계정의 답으로 설명되는 일을 막는다.
  const ready = !query.loading && !query.error ? query.data : null;
  return {
    loading: query.loading,
    path: ready?.path ?? null,
    desk: ready?.localAgent ?? null,
    lastSuccessAt: ready?.lastSuccessAt ?? null,
    coverageThrough: ready?.coverageThrough ?? null,
    coverageGapDays: ready?.coverageGapDays ?? null,
    pausedSignIn: ready?.pausedSignIn === true,
  };
}

/**
 * 「어디까지 빠짐없이 확인했는가」 — 한 줄.
 *
 * <p>경계가 없으면 아무 말도 하지 않는다. 「확인된 적 없음」은 근거가 아니라 빈칸이고, 빈칸을 문장으로 만들면
 * 실제로 4,432건을 들고 있는 채널에 대해 거짓말이 된다.
 */
export function coverageSentence(coverageThrough: string | null, gapDays: number | null): string | null {
  if (!coverageThrough) return null;
  // <b>정상 상태에서는 아무 말도 하지 않는다.</b> 「10월 7일까지 빠짐없이 확인」은 내부 정확성 invariant이고,
  // 모든 것이 제대로 돌아가는 화면에서 그것을 읽는 판매자는 「10월 8일은?」을 묻게 된다 — 오늘은 경계가 될 수
  // 없는 하루이기 때문에 그 질문에는 답이 없다. 정상일 때 판매자가 알아야 하는 것은 「언제 봤는가」뿐이고,
  // 그건 freshnessSentence가 말한다. 이 줄은 메울 것이 남아 있을 때만 선다.
  if (!gapDays || gapDays <= 0) return null;
  return `${kstMonthDay(coverageThrough)}까지 빠짐없이 확인 · 이후 ${gapDays}일은 아직`;
}

/**
 * 「오늘 13:40 확인」 — <b>정상 상태의 한 줄.</b>
 *
 * <p>자동으로 확인하는 제품에서 판매자가 알고 싶은 것은 경계가 아니라 신선도다. 오늘은 아직 쓰이고 있는
 * 하루여서 「오늘까지 빠짐없이」라고 말할 수 없고, 말할 수 있는 참인 문장은 「마지막으로 본 시각」이다.
 *
 * <p>오늘·어제는 그렇게 부른다. 그보다 오래되면 날짜로 — 「9월 2일 확인」은 「38일 전」보다 판매자가
 * 자기 달력에 대고 읽을 수 있는 문장이다.
 */
export function freshnessSentence(lastSuccessAt: string | null, now: Date = new Date()): string | null {
  if (!lastSuccessAt) return null;
  const day = kstDate(lastSuccessAt);
  const today = kstDate(now.toISOString());
  const yesterday = kstDate(new Date(now.getTime() - 86_400_000).toISOString());
  if (day === today) return `오늘 ${kstHourMinute(lastSuccessAt)} 확인`;
  if (day === yesterday) return `어제 ${kstHourMinute(lastSuccessAt)} 확인`;
  return `${kstMonthDay(lastSuccessAt)} 확인`;
}

/**
 * 수집 primary 하나.
 *
 * @param desk 도우미 경로일 때의 책상 상태. `null`이면 API 경로이므로 문장도 복구 동작도 없다.
 * @param recovery 책상이 막혀 있을 때만 그려지는 복구 동작(로그인·기기 연결). 수집 동작처럼 보이게 두지 않는다.
 */
export function CollectNowAction({
  accountId,
  dataType,
  label,
  desk = null,
  channelCode = null,
  lastSuccessAt = null,
  coverageThrough = null,
  coverageGapDays = null,
  pausedSignIn = false,
  disabled = false,
  showSentence = false,
  emphasis = "plain",
  recovery = null,
  onReport,
  onChanged,
  onSettled,
}: {
  accountId: string;
  dataType: string;
  label: string;
  desk?: LocalAgentRunState | null;
  /**
   * 로그인 복구를 어느 판매자센터로 열지. 도우미가 그 채널의 공개된 경로를 열므로 이 값이 필요하다 —
   * 이 컴포넌트가 채널로 분기하는 것이 아니라, 서버가 준 값을 그대로 전달하기만 한다.
   */
  channelCode?: string | null;
  /** 실제로 읽은 가장 최근 수집. 로그인이 필요했다는 소식 옆에 이것이 같이 서야 과거를 잃지 않는다. */
  lastSuccessAt?: string | null;
  /** 빠짐없이 확인한 마지막 날. 성공 시각과 나란히 서야 「최근 7일 45건」이 「공백이 메워졌다」로 읽히지 않는다. */
  coverageThrough?: string | null;
  coverageGapDays?: number | null;
  /**
   * 이 줄이 로그인을 기다리고 있는가. 참이면 이 화면이 살아날 때 **가벼운 로그인 확인 한 번**을 하고,
   * 로그인되어 있으면 멈췄던 일을 이어간다. 수집을 시작하지는 않는다.
   */
  pausedSignIn?: boolean;
  disabled?: boolean;
  showSentence?: boolean;
  /**
   * 이 화면에서 지금 할 일이 이것인가.
   *
   * <p>`"primary"`는 화면의 가장 강한 컨트롤이 된다는 선언이고, 한 시점에 하나여야 한다 — 그것을 정하는
   * 것은 이 컴포넌트가 아니라 화면이다(끝나지 않은 연결이 있으면 그쪽이 먼저다). 목록의 한 줄처럼 다른
   * 줄들과 나란히 서는 자리에서는 `"plain"`이다.
   */
  emphasis?: "primary" | "plain";
  recovery?: ReactNode;
  onReport: (message: string, isError: boolean) => void;
  onChanged: () => void;
  onSettled?: () => void;
}) {
  const [syncing, setSyncing] = useState(false);
  /**
   * 이 탭에서 방금 확인한 로그인. <b>이 탭에서만, 지금만</b> 유효하다 — 서버에 저장하지 않고 freshness로도
   * 쓰지 않는다(제품 결정 2026-10-08). 새로고침하면 사라지고 화면은 다시 「최근 수집 시 로그인이
   * 필요했습니다」로 돌아가는데, 그 문장은 마지막 수집 시도에 대한 것이라 다음 수집까지 참이다.
   */
  const [signedInHere, setSignedInHere] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  /**
   * <b>로그인 벽에서 멈춘 그 수집 하나</b> — 아직 하지 못한 일로 들고 있는다.
   *
   * <p>첫 「지금 수집하기」가 그 bounded collection의 승인이었다. 그 수집이 로그인 벽에서 멈췄고 판매자가 같은
   * 화면에서 로그인까지 이어왔다면, 승인은 이미 있었던 것이고 다시 받을 것이 없다 — 2026-10-08 라이브에서
   * 판매자가 두 번 눌러야 했던 자리가 여기다.
   *
   * <p>하나뿐이고, 한 번만 쓰이고, 쓰이면 사라진다. ref인 것은 의도적이다: 이 값이 바뀌어서 다시 그려질 일이
   * 없고, 그려지는 사이에 사라졌다 되살아나서도 안 된다.
   */
  const pendingCollect = useRef(false);
  /** 언마운트 뒤에 도착한 답으로 수집을 시작하지 않기 위한 표식. 화면을 떠난 것은 취소다. */
  const live = useRef(true);
  useEffect(() => () => {
    live.current = false;
    pendingCollect.current = false;
  }, []);
  /**
   * <b>판매자가 Reviewnary로 돌아왔다 — 기다리던 일이 있으면 한 번 확인한다.</b>
   *
   * <p>2026-10-09 라이브에서 측정된 구멍을 메운다: 판매자가 2차 인증 때문에 제품 창을 닫고 자기 브라우저의
   * 다른 탭에서 로그인했다. 세션은 돌아왔지만 지켜보던 탭이 없었으므로 아무도 그 사실을 몰랐고, 멈춘 일은
   * 그대로 있었다.
   *
   * <p>세 가지를 하지 않는다. <b>수집을 시작하지 않는다</b> — 화면에 들어온 것만으로 리뷰를 읽기 시작하면
   * 그건 아무도 요청하지 않은 수집이다. 기다리는 일이 없으면 <b>묻지도 않는다</b>. 그리고 한 번의 활성화에
   * 대해 <b>한 번만</b> 묻는다.
   *
   * <p>하는 일은 가벼운 로그인 확인 하나이고, 로그인되어 있으면 서버에 「이어가 달라」고 말한다 — 무엇을
   * 이어갈지와 그것이 누구 일인지는 서버의 row가 안다(원래 trigger 그대로).
   */
  const checkedForActivation = useRef(false);
  useEffect(() => {
    if (!pausedSignIn) {
      // 기다리는 일이 사라졌다(이어졌거나, 판매자가 껐거나). 다음 번 기다림을 위해 표식을 비운다.
      checkedForActivation.current = false;
      return;
    }
    if (!channelCode) return;

    async function askOnce() {
      if (checkedForActivation.current || !live.current) return;
      checkedForActivation.current = true;
      const state = await checkSignIn(channelCode!);
      if (!live.current) return;
      if (state !== "SIGNED_IN") {
        // 아직 로그아웃이거나(NOT_SIGNED_IN), 알 수 없거나(UNAVAILABLE), 데스크에 세션이 열려 있다(WAITING).
        // 세 경우 모두 그대로 기다린다 — 시작하는 것은 없다.
        return;
      }
      setSignedInHere(true);
      let resumed: Awaited<ReturnType<typeof api.collectNowResume>> = null;
      try {
        resumed = await api.collectNowResume(accountId, dataType);
      } catch {
        resumed = null;
      }
      if (!live.current || !resumed) return;
      onReport("로그인 확인됨. 멈췄던 확인을 이어서 진행합니다.", false);
      await watchResumed(resumed);
    }

    void askOnce();
    // 화면이 다시 살아나는 두 가지 길. 탭 전환은 visibilitychange, 창 전환은 focus다 — 판매자는 보통
    // 브라우저를 갈아타면서 로그인하므로 둘 다 필요하다.
    const onVisible = () => {
      if (document.visibilityState === "visible") {
        checkedForActivation.current = false;
        void askOnce();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pausedSignIn, channelCode, accountId, dataType]);

  // 연결되지 않은 도우미와 이미 일하는 중인 도우미는 누를 수 없다. 로그인이 풀린 경우는 누를 수 있게 둔다 —
  // 판매자가 방금 자기 브라우저에서 로그인했을 수 있고, 막아 두면 고친 뒤에도 누를 길이 없다.
  const blocked = desk === "UNPAIRED" || desk === "BUSY";
  const authWall = desk === "AUTH_REQUIRED" && !signedInHere;

  /**
   * 「판매자센터 로그인」 — 수집이 쓰는 그 프로필, 그 페이지를 도우미가 연다.
   *
   * 자격은 어디에도 넣지 않는다. 아이디·비밀번호·MFA·CAPTCHA는 전부 판매자가 직접 하고, 도우미는
   * signed-in 여부만 본다. 그리고 <b>로그인되었다고 수집을 자동으로 다시 돌리지 않는다</b> — 다음 수집도
   * 판매자가 누르는 것이고, 그 누름이 그 수집의 승인이다.
   */
  async function signIn() {
    if (!channelCode) return;
    setSigningIn(true);
    try {
      // <b>여러 회차를 이어서 지켜본다.</b> 도우미 쪽 한 세션의 상한은 100초이고 올릴 수 없다 — Aside 자신의
      // 호출 상한이 그 위에 있다. 2026-10-09에 그 100초가 터졌다: 창이 앞으로 나오지 않아 판매자가 창을 찾는
      // 데 시간을 썼고 98초에 끝났다. 회차를 이어 붙이면 각 호출은 상한 안에 있으면서 사람에게는 시간이 생기고,
      // 회차마다 창을 다시 앞으로 가져오는 것이 그 포커스 문제에 대한 두 번째 기회가 된다.
      // 두 primitive를 여기서 건네는 이유: 이 화면이 쓰는 그 둘을 그대로 쓰게 하려는 것이다. 기본값으로
      // 두면 모듈 내부 결합을 쓰게 되고, 이 화면의 동작을 그 둘로 세우는 테스트가 실제 호출을 비켜 간다.
      const watched = await watchSignIn(channelCode, (round, rounds) => {
        if (!live.current) return;
        // <b>몇 분을 기다리게 하면서 아무 말도 하지 않으면 멈춘 것으로 읽힌다.</b> 2026-10-09에 그렇게 읽혔다.
        onReport(round === 1
          ? "판매자센터 로그인 창을 열었습니다. 그 창에서 로그인해 주세요."
          : `아직 기다리고 있습니다. 로그인 창을 다시 앞으로 가져왔습니다. (${round}/${rounds})`, false);
      }, SIGN_IN_ROUNDS, startSignIn, awaitSignIn);
      if (!live.current) return;
      if (!watched.ok) {
        // 시작하지 못했으면 기다리던 수집도 버린다 — 승인을 들고 있을 이유가 없다.
        pendingCollect.current = false;
        onReport(signInStartMessage(watched.reason), true);
        return;
      }
      const settled = watched.state;
      if (settled === "SIGNED_IN") {
        setSignedInHere(true);
      }
      // <b>이어갈 일이 있는지, 그리고 그게 누구 일인지는 서버가 안다.</b>
      //
      // 이 탭은 「로그인이 확인됐다」만 전한다. 멈춰 있던 것이 판매자가 누른 수집인지 자동 확인인지는 서버의
      // row가 들고 있고, 이어가기는 그 row를 <b>원래 trigger 그대로</b> 되살린다. 화면이 그 판단을 하면
      // provenance를 밖에서 정하는 셈이고, 그래서 자동 확인을 이어가는 일이 「판매자가 눌렀다」로 기록된다.
      //
      // 또 하나: 이것은 press가 아니다. 2026-10-09에 이어가기가 「수집을 한 번 더 누르기」였고, 그것은
      // 자동 확인이 멈춰 있는 경우에 새 누름을 하나 더 만드는 길이었다. 지금은 한 번의 resume이고, 멈춘 것이
      // 없으면 아무 일도 일어나지 않는다 — 혼자 로그인한 판매자에게 수집이 저절로 시작되지 않는다는 계약은
      // 그대로다.
      pendingCollect.current = false;
      if (settled !== "SIGNED_IN") {
        const message = signInMessage(settled);
        if (message) {
          onReport(message.text, message.isError);
        }
        return;
      }
      let resumed: Awaited<ReturnType<typeof api.collectNowResume>> = null;
      try {
        resumed = await api.collectNowResume(accountId, dataType);
      } catch {
        // 이어가기를 묻지 못했다. 로그인은 확인됐고, 그 사실만 말한다.
        resumed = null;
      }
      if (!live.current) return;
      if (!resumed) {
        const message = signInMessage(settled);
        if (message) {
          onReport(message.text, message.isError);
        }
        onChanged?.();
        return;
      }
      onReport("로그인 확인됨. 멈췄던 확인을 이어서 진행합니다.", false);
      await watchResumed(resumed);
    } finally {
      if (live.current) setSigningIn(false);
    }
  }

  /**
   * 이어진 확인을 끝까지 지켜본다 — 누름과 같은 방식으로, 같은 문장으로.
   *
   * <p>press와 나뉘어 있는 이유는 하나다: 이쪽은 아무것도 시작하지 않았다. 시작은 서버가 되살린 run이
   * 했고, 이 함수는 그것을 보고 판매자에게 말한다. 또 벽에 다시 막히면 그 사실을 들고 있는다 — 같은 화면에서
   * 다시 로그인할 수 있고, 그 로그인이 또 한 번의 resume이 된다.
   */
  async function watchResumed(first: ScreenReadView) {
    setSyncing(true);
    try {
      const read = await awaitScreenRead(first);
      pendingCollect.current = read.state === "AUTH_REQUIRED";
      const message = screenReadMessage(label, read);
      onReport(message.text, message.isError);
      onChanged();
    } finally {
      setSyncing(false);
      onSettled?.();
    }
  }

  async function press() {
    setSyncing(true);
    try {
      const started = await api.collectNow(accountId, dataType, requestId());
      if (started.path === "SCREEN_READ" && started.screenRead) {
        const read = await awaitScreenRead(started.screenRead);
        // 이 수집이 로그인 벽에서 멈췄다. 승인은 이미 받았으므로, 같은 화면에서 로그인까지 이어오면 다시
        // 받을 것이 없다. 벽이 아닌 결말에서는 들고 있지 않는다 — 성공한 수집을 또 돌릴 이유가 없고, 실패한
        // 수집을 로그인했다고 되살릴 이유도 없다.
        pendingCollect.current = read.state === "AUTH_REQUIRED";
        const message = screenReadMessage(label, read);
        onReport(message.text, message.isError);
      } else if (started.run) {
        const run = started.run;
        onReport(
          `${label} 수집 완료: 저장 ${run.successRows} · 건너뜀 ${run.skippedRows} · 실패 ${run.failedRows}`,
          run.status === "FAILED",
        );
      } else {
        onReport(`${label} 수집을 시작했습니다.`, false);
      }
      onChanged();
    } catch (e) {
      // `HELPER_NOT_LINKED`와 `HELPER_BUSY`는 같은 409이고 지시는 정반대다. 서버가 보낸 토큰으로 갈라야
      // 한다 — 한국어 문장을 맞춰보는 화면은 누군가 문장을 고치는 순간 판매자에게 엉뚱한 안내를 한다.
      const code = backendCode(e);
      if (code === "HELPER_NOT_LINKED") {
        onReport("이 컴퓨터의 도우미가 아직 연결되지 않았습니다. 도우미 카드에서 [이 기기 연결]을 한 번 눌러 주세요.", true);
      } else if (code === "HELPER_BUSY") {
        onReport("이미 수집이 진행 중입니다. 끝난 뒤 다시 눌러 주세요.", true);
      } else {
        onReport(backendMessage(e) ?? "수집 실행에 실패했습니다. 잠시 후 다시 시도해 주세요.", true);
      }
    } finally {
      setSyncing(false);
      onSettled?.();
    }
  }

  return (
    <div className="flex flex-col items-start gap-2 md:items-end">
      {showSentence ? (
        <div className="flex flex-col items-start gap-0.5 md:items-end">
          {/*
            <b>두 사실이 한 화면에 같이 선다.</b> 「최근 수집 시 로그인이 필요했습니다」가 「마지막 성공 수집
            9월 2일」을 지우지 않는다 — 로그인이 만료됐다는 소식이 그 전에 읽은 4,432건을 없애지는 않기
            때문이다. 한 칸으로 합쳐 두었을 때 제품은 실제로 읽은 채널을 「확인된 적 없음」이라고 말했다.
          */}
          {(signedInHere ? "로그인 확인됨." : deskSentence(desk)) ? (
            <p className="break-keep text-sm text-muted">
              {signedInHere ? "로그인 확인됨." : deskSentence(desk)}
            </p>
          ) : null}
          {/*
            <b>정상 상태의 한 줄은 신선도다.</b> 자동으로 확인하는 제품에서 판매자가 알고 싶은 것은
            「마지막으로 본 시각」이고, 「어디까지 빠짐없이」는 그 아래에서 돌아가는 정확성 규칙이다 —
            메울 것이 남아 있을 때만 화면에 올라온다(coverageSentence).
          */}
          {freshnessSentence(lastSuccessAt) ? (
            <p className="break-keep text-sm text-muted">{freshnessSentence(lastSuccessAt)}</p>
          ) : null}
          {/*
            <b>세 번째 줄이 필요한 이유.</b> 10-08 읽기는 성공했고 45건을 가져왔다 — 그리고 그 45건은 최근
            7일이었다. 「마지막 성공 수집 10월 8일」만 서 있으면 9/2 이후의 29일은 화면 어디에도 없고, 그
            공백은 메워진 것처럼 읽힌다. 「언제 봤는가」와 「어디까지 봤는가」는 다른 사실이다.
          */}
          {coverageSentence(coverageThrough, coverageGapDays) ? (
            <p className="break-keep text-sm text-muted">{coverageSentence(coverageThrough, coverageGapDays)}</p>
          ) : null}
        </div>
      ) : null}
      {authWall && channelCode ? (
        <button
          type="button"
          data-testid={`sign-in-${dataType}`}
          disabled={signingIn || syncing}
          onClick={signIn}
          className="rounded-xl bg-brand-700 px-4 py-2 text-base font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {signingIn ? "로그인 창에서 로그인해 주세요…" : "판매자센터 로그인"}
        </button>
      ) : null}
      <button
        type="button"
        data-testid={`collect-now-${dataType}`}
        disabled={disabled || syncing || signingIn || blocked}
        onClick={press}
        // 이 버튼은 「저장 중」처럼 잠깐이 아니라, 도우미를 연결할 때까지 계속 꺼져 있을 수 있다. 눌리지 않는
        // 버튼이 눌리는 버튼과 똑같이 생기면, 그 옆 문장을 읽지 않은 판매자는 고장난 화면을 본다.
        className={`${
          emphasis === "primary"
            ? "rounded-xl bg-brand-700 font-semibold text-white hover:bg-brand-800"
            : "btn-ghost"
        } px-4 py-2 text-base disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {syncing ? "수집 중…" : signedInHere ? "다시 수집하기" : "지금 수집하기"}
      </button>
      {recovery && needsRecovery(desk) ? recovery : null}
    </div>
  );
}
