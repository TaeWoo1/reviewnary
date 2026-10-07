import { useState, type ReactNode } from "react";
import { api } from "../../lib/apiClient";
import { useApiData } from "../../lib/useApiData";
import type { CollectNowPath, LocalAgentRunState, ScreenReadView } from "../../lib/types";
import { backendCode, backendMessage } from "./channelShared";

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

async function awaitScreenRead(first: ScreenReadView): Promise<ScreenReadView> {
  let latest = first;
  for (let i = 0; i < SCREEN_READ_POLL_LIMIT; i += 1) {
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

/** 읽기 하나의 결과를 판매자의 문장으로. 구현 이름(recipe·provider·outcome)은 한 글자도 나오지 않는다. */
export function screenReadMessage(label: string, read: ScreenReadView): { text: string; isError: boolean } {
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
    default:
      return { text: `${label}을(를) 수집하지 못했습니다. 잠시 후 다시 시도해 주세요.`, isError: true };
  }
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
      return "판매자 센터 로그인이 필요합니다. 로그인한 뒤 다시 수집해 주세요.";
    default:
      return "누를 때마다 판매자 센터 화면에서 읽어옵니다. 자동 주기는 없습니다.";
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
  return { loading: query.loading, path: ready?.path ?? null, desk: ready?.localAgent ?? null };
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
  // 연결되지 않은 도우미와 이미 일하는 중인 도우미는 누를 수 없다. 로그인이 풀린 경우는 누를 수 있게 둔다 —
  // 판매자가 방금 자기 브라우저에서 로그인했을 수 있고, 막아 두면 고친 뒤에도 누를 길이 없다.
  const blocked = desk === "UNPAIRED" || desk === "BUSY";

  async function press() {
    setSyncing(true);
    try {
      const started = await api.collectNow(accountId, dataType, requestId());
      if (started.path === "SCREEN_READ" && started.screenRead) {
        const message = screenReadMessage(label, await awaitScreenRead(started.screenRead));
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
      {showSentence ? <p className="break-keep text-sm text-muted">{deskSentence(desk)}</p> : null}
      <button
        type="button"
        data-testid={`collect-now-${dataType}`}
        disabled={disabled || syncing || blocked}
        onClick={press}
        // 이 버튼은 「저장 중」처럼 잠깐이 아니라, 도우미를 연결할 때까지 계속 꺼져 있을 수 있다. 눌리지 않는
        // 버튼이 눌리는 버튼과 똑같이 생기면, 그 옆 문장을 읽지 않은 판매자는 고장난 화면을 본다.
        className={`${
          emphasis === "primary"
            ? "rounded-xl bg-brand-700 font-semibold text-white hover:bg-brand-800"
            : "btn-ghost"
        } px-4 py-2 text-base disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {syncing ? "수집 중…" : "지금 수집하기"}
      </button>
      {recovery && needsRecovery(desk) ? recovery : null}
    </div>
  );
}
