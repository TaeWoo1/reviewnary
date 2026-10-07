// Extracted VERBATIM from the previous single-file 채널 상세 page — the component bodies below are
// the same code that drove the live-verified connection and collection flows. Only the file they
// live in changed; no call, no order, no condition was rewritten.
import { useEffect, useState } from "react";
import { Section } from "../Section";
import { api } from "../../lib/apiClient";
import { channelDataTypeLabel } from "../../lib/channelVocabulary";
import { useApiData } from "../../lib/useApiData";
import type {
  AcquisitionPathView,
  CapabilityView,
  LocalAgentRunState,
  ScheduleView,
  ScreenReadView,
} from "../../lib/types";
import { DATA_TYPES, INTERVALS, backendCode, backendMessage } from "./channelShared";

/** 수집 설정 — one row per data type, each owning its own cadence + manual run. */
export function CollectionSettingsSection({
  accountId,
  channelCode,
  schedules,
  capabilities,
  onChanged,
  onReport,
  heading,
}: {
  accountId: string;
  /** Optional: without it the rows simply say less, never something untrue. */
  channelCode?: string | null;
  schedules: ScheduleView[];
  capabilities: CapabilityView[] | null;
  onChanged: () => void;
  onReport: (message: string, isError: boolean) => void;
  /**
   * `null`이면 제목 없이 본문만. 이 블록이 <b>이미 이름이 붙은 자리</b>(접힌 영역 · capability 카드) 안에서
   * 열릴 때를 위한 것이다 — 한 사실에 화면 위 이름이 둘이면 어느 쪽이 그것인지 말할 사람이 없다. 생략하면
   * 예전과 바이트 동일하다.
   */
  heading?: string | null;
}) {
  // A row that cannot be scheduled still owes the seller a reason, and the honest reason is
  // sometimes "SellerOps collects this — just not on a cadence". Only the capability OVERVIEW knows
  // that: `capabilities` above is the connector_capabilities table, which answers whether a PULL
  // connector can serve the type and is what gates scheduling. Read here strictly to explain, never
  // to gate — a failed read leaves the row exactly as it was.
  const { data, loading, error } = useApiData(
    () => (channelCode ? api.getChannelCapabilityOverview(channelCode) : Promise.resolve(null)),
    [channelCode],
  );
  // `useApiData` keeps the last successful payload across a deps change, so on an account switch the
  // PREVIOUS channel's overview is still in `data` until the new one lands. Honouring `loading` is
  // what stops one channel's route being described on another channel's row.
  const overview = loading || error ? null : data;

  const body = (
    <>
      {/*
        <b>두 수집 동작의 차이를 버튼 옆에서 말한다.</b> 이 화면에는 「지금 수집하기」가 있고 아래 접힌
        영역에는 「지난 기간 가져오기」가 있다. 이름만 보면 둘 다 「수집」이라, 오래된 자료가 필요한 판매자가
        「지금 수집하기」를 반복해서 누르고 아무 일도 일어나지 않는다고 읽는다 — 실제로는 그 동작의 정의대로
        동작한 것이다(커넥터가 정한 최근 구간만 다시 읽는다. Cafe24는 14일).
      */}
      <p className="break-keep text-sm leading-relaxed text-muted">
        「지금 수집하기」는 최근 구간만 다시 확인합니다. 오래된 자료는 아래 「지난 기간 가져오기」에서
        기간을 골라 가져오세요.
      </p>
      <ul className="divide-y divide-line">
        {DATA_TYPES.map((t) => (
          <ScheduleRow
            key={t.value}
            accountId={accountId}
            dataType={t.value}
            label={channelDataTypeLabel(channelCode, t.value, t.label)}
            schedule={schedules.find((s) => s.dataType === t.value) ?? null}
            capability={capabilities?.find((c) => c.dataType === t.value) ?? null}
            capabilitiesReady={capabilities !== null}
            acquisitionPaths={
              overview?.dataTypes.find((d) => d.dataType === t.value)?.acquisitionPaths ?? []
            }
            onChanged={onChanged}
            onReport={onReport}
          />
        ))}
      </ul>
    </>
  );
  if (heading === null) return <div className="space-y-3">{body}</div>;
  return <Section title={heading ?? "수집 설정"}>{body}</Section>;
}

/**
 * <b>화면 읽기는 내 컴퓨터에서 벌어지므로, 끝났는지는 되물어야 안다.</b>
 *
 * 공식 API 수집은 응답이 돌아온 순간 이미 끝나 있다. 화면 읽기는 도우미가 받아 가는 일이라, 버튼을 누른
 * 사람에게 결과를 말하려면 상태를 다시 물어야 한다. 바로 한 번 묻고, 아직 진행 중일 때만 기다린다 —
 * 끝난 작업을 2초 기다렸다가 확인하는 화면은 멀쩡한 수집도 느리게 느껴지게 만든다.
 */
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
function screenReadMessage(label: string, read: ScreenReadView): { text: string; isError: boolean } {
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
      return { text: `판매자 센터 로그인이 필요합니다. 로그인한 뒤 ${label} 수집을 다시 눌러 주세요.`, isError: true };
    case "RUNNING":
      return { text: `${label} 수집이 아직 진행 중입니다. 잠시 뒤 다시 확인해 주세요.`, isError: false };
    default:
      return { text: `${label}을(를) 수집하지 못했습니다. 잠시 후 다시 시도해 주세요.`, isError: true };
  }
}

/** 이 누름 하나의 식별자. 더블클릭·재요청·새로고침이 두 건이 아니라 한 건으로 모이게 하는 값. */
function requestId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  return uuid ?? `p${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/** 도우미가 필요한 줄에서, 지금 누를 수 있는지와 누를 수 없으면 무엇을 하면 되는지. */
function deskSentence(desk: LocalAgentRunState | null): string {
  switch (desk) {
    case "UNPAIRED":
      return "이 컴퓨터의 도우미를 한 번 연결하면 수집할 수 있습니다.";
    case "BUSY":
      return "이미 수집이 진행 중입니다.";
    case "AUTH_REQUIRED":
      return "판매자 센터 로그인이 풀렸습니다. 로그인한 뒤 다시 눌러 주세요.";
    default:
      return "누를 때마다 판매자 센터 화면에서 읽어옵니다. 자동 주기는 없습니다.";
  }
}

function ScheduleRow({
  accountId,
  dataType,
  label,
  schedule,
  capability,
  capabilitiesReady,
  acquisitionPaths,
  onChanged,
  onReport,
}: {
  accountId: string;
  dataType: string;
  label: string;
  schedule: ScheduleView | null;
  capability: CapabilityView | null;
  capabilitiesReady: boolean;
  acquisitionPaths: AcquisitionPathView[];
  onChanged: () => void;
  onReport: (message: string, isError: boolean) => void;
}) {
  const [cadence, setCadence] = useState(schedule?.intervalMinutes ?? 360);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState(false);
  // Bumped after a collection so the row re-asks what it can offer next: a desk that was BUSY is free again,
  // and a sign-in that was expired may have been renewed by the seller in the meantime.
  const [askedAgain, setAskedAgain] = useState(0);

  useEffect(() => {
    if (schedule?.intervalMinutes) {
      setCadence(schedule.intervalMinutes);
    }
  }, [schedule?.intervalMinutes]);

  /**
   * <b>어떤 경로로 수집되는지는 서버가 답한다.</b> 이 컴포넌트는 채널 이름으로 분기하지 않는다 — 그렇게
   * 분기하던 구조가 바로 고치려는 결함이었다. 「지금 수집하기」가 커넥터 pull 엔드포인트에 직접 묶여 있어서,
   * 리뷰 API가 없는 두 채널에서는 버튼이 아예 그려지지 않았고(판매자에게는 「이 제품은 네이버 리뷰를 못
   * 가져온다」로 읽혔다) 그 사이 증명된 화면 읽기 경로가 손잡이 없는 문 뒤에 있었다.
   */
  const readinessQuery = useApiData(
    () => api.collectNowReadiness(accountId, dataType),
    [accountId, dataType, askedAgain],
  );
  // `useApiData` keeps the last successful payload across a deps change, so on an account switch the PREVIOUS
  // account's answer is still in `data` until the new one lands. Honouring `loading` is what stops one row
  // being described by another row's route.
  const readiness = readinessQuery.loading || readinessQuery.error ? null : readinessQuery.data;
  const route = readiness?.path ?? null;
  const desk: LocalAgentRunState | null = readiness?.localAgent ?? null;

  const unsupported = capability !== null && !capability.supported;
  const needsVerification = capability?.verificationStatus === "NEEDS_VERIFICATION";
  // A route the seller runs themselves on the marketplace. It is why this row can be uncollectable
  // on a cadence and collected all the same; it never makes the row schedulable.
  const operatorRunPath = acquisitionPaths.some((p) => p.method === "ACTION_WINDOW");
  // A proven route that only the seller can repeat — an export they download, a window they open.
  // Shown so a row with no schedule reads as "이 채널은 이렇게 가져옵니다" instead of as a gap; the demo
  // org's 3,858 NAVER reviews all arrived this way while the screen said nothing about how.
  const sellerRepeatedPath = acquisitionPaths.find((p) => p.recurrence === "SELLER_REPEATED");
  const enabled = schedule?.enabled ?? false;
  // One guard for the whole row: a save and a manual sync must not overlap.
  const rowBusy = saving || syncing;
  // Cadence changed but not applied yet — saving is always an explicit action.
  const cadenceDirty = enabled && schedule?.intervalMinutes != null && cadence !== schedule.intervalMinutes;

  async function save(nextEnabled: boolean) {
    setSaving(true);
    try {
      await api.putSchedule(accountId, { dataType, intervalMinutes: cadence, enabled: nextEnabled });
      onReport(
        nextEnabled
          ? `${label} 자동 수집을 켰습니다. 다음 주기부터 자동으로 수집됩니다.`
          : `${label} 자동 수집을 껐습니다.`,
        false,
      );
      onChanged();
    } catch (e) {
      onReport(backendMessage(e) ?? "설정 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.", true);
    } finally {
      setSaving(false);
    }
  }

  /**
   * 「지금 수집하기」 — 한 번 누르면 이 채널에 실제로 있는 경로로 수집한다.
   *
   * 공식 API면 예전과 같은 동기 pull 실행이고, 판매자 센터 화면 읽기면 내 컴퓨터의 도우미에게 한 건을
   * 맡기고 끝날 때까지 되묻는다. 둘의 차이는 문장 하나뿐이고, 어느 쪽인지는 서버가 정한다.
   */
  async function syncNow() {
    setSyncing(true);
    try {
      const started = await api.collectNow(accountId, dataType, requestId());
      if (started.path === "SCREEN_READ" && started.screenRead) {
        const finished = await awaitScreenRead(started.screenRead);
        const message = screenReadMessage(label, finished);
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
      setAskedAgain((n) => n + 1);
    }
  }

  return (
    <li className="flex flex-col gap-3 py-4 md:flex-row md:items-center md:justify-between">
      <div className="flex items-center gap-3">
        <span className="w-24 text-base font-semibold">{label}</span>
        {unsupported && route !== "SCREEN_READ" ? (
          // 자동 수집, not 이 채널: this row is about a cadence, and saying the CHANNEL does not
          // support the data type overstated it — Coupang 상품평 sat under this chip while the panel
          // one scroll above counted 22 of them, collected through the Action Window.
          //
          // 화면 읽기 줄에서는 이 칩을 아예 띄우지 않는다. 바로 옆에 동작하는 「지금 수집하기」가 있는데
          // 「미지원」이 붙어 있으면, 한 줄 안에서 화면이 스스로를 부정한다.
          <span className="rounded-lg bg-canvas px-3 py-1 text-sm text-muted">자동 수집 미지원</span>
        ) : needsVerification ? (
          <span className="rounded-lg bg-warn/10 px-3 py-1 text-sm text-warn">확인 필요</span>
        ) : null}
        {schedule?.pausedReason ? (
          <span className="text-sm text-warn">{schedule.pausedReason}</span>
        ) : null}
      </div>

      {!capabilitiesReady || readinessQuery.loading ? (
        <p className="text-sm text-muted">수집 지원 정보 확인 중…</p>
      ) : route === "SCREEN_READ" ? (
        // 이 자료는 채널이 API로 내주지 않는다. 판매자 센터 화면을 내 컴퓨터의 도우미가 한 번 읽어 온다 —
        // 주기 자동 수집은 없고, 누름이 곧 그 한 번의 승인이다.
        <div className="flex flex-col items-start gap-2 md:items-end">
          <p className="break-keep text-sm text-muted">{deskSentence(desk)}</p>
          <button
            type="button"
            disabled={rowBusy || desk === "UNPAIRED" || desk === "BUSY"}
            onClick={syncNow}
            // 이 줄의 버튼은 「저장 중」처럼 잠깐이 아니라, 도우미를 연결할 때까지 계속 꺼져 있을 수 있다.
            // 눌리지 않는 버튼이 눌리는 버튼과 똑같이 생기면, 그 옆 문장을 읽지 않은 판매자는 고장난
            // 화면을 본다.
            className="btn-ghost px-4 py-2 text-base disabled:cursor-not-allowed disabled:opacity-50"
          >
            {syncing ? "수집 중…" : "지금 수집하기"}
          </button>
        </div>
      ) : route === "UNSUPPORTED" || unsupported ? (
        <p className="text-sm text-muted">
          {/*
            **커넥터의 `notes`는 판매자 문장이 아니다.** 그 칸은 우리끼리 쓰는 영문 기록이고, 이 자리에서
            그대로 렌더돼 쿠팡 채널 화면이 판매자에게 「No review-retrieval endpoint in the official seller
            API.」라고 말하고 있었다(실측 2026-09-14). 알 수 없는 종류에는 이 제품이 아는 문장 하나만 쓴다 —
            읽지 못한 사실을 문장으로 바꾸는 것보다 적게 말하는 편이 옳다.
          */}
          {operatorRunPath
            ? "판매자가 직접 실행하는 수집 경로라 자동 수집 주기 대상이 아닙니다."
            : sellerRepeatedPath?.method === "EXPORT"
              ? "이 채널은 리뷰 API를 제공하지 않습니다. 판매자 센터에서 내려받은 파일을 올리는 방식이 정식 수집 경로이며, 새 데이터는 다시 올릴 때 들어옵니다."
              : "이 데이터는 파일 업로드로 채울 수 있습니다."}
        </p>
      ) : (
        <div className="flex flex-wrap items-center gap-3">
          <select
            aria-label={`${label} 수집 주기`}
            value={cadence}
            onChange={(e) => setCadence(Number(e.target.value))}
            disabled={rowBusy}
            className="rounded-xl border border-line px-3 py-2 text-base focus:border-brand focus:outline-none"
          >
            {INTERVALS.map((opt) => (
              <option key={opt.minutes} value={opt.minutes}>
                {opt.label}
              </option>
            ))}
          </select>
          {cadenceDirty ? (
            <button
              type="button"
              disabled={rowBusy}
              onClick={() => save(true)}
              className="btn-primary px-4 py-2 text-base"
            >
              {saving ? "저장 중…" : "주기 적용"}
            </button>
          ) : null}
          <button
            type="button"
            disabled={rowBusy}
            onClick={() => save(!enabled)}
            className={`rounded-xl px-4 py-2 text-base font-semibold ${
              enabled ? "bg-good/10 text-good" : "bg-canvas text-muted"
            }`}
          >
            {saving ? "저장 중…" : enabled ? "자동 수집 켜짐" : "자동 수집 꺼짐"}
          </button>
          <button type="button" disabled={rowBusy} onClick={syncNow} className="btn-ghost px-4 py-2 text-base">
            {syncing ? "수집 중…" : "지금 수집하기"}
          </button>
        </div>
      )}
    </li>
  );
}

