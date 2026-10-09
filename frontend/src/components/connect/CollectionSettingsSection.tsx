// Extracted VERBATIM from the previous single-file 채널 상세 page — the component bodies below are
// the same code that drove the live-verified connection and collection flows. Only the file they
// live in changed; no call, no order, no condition was rewritten.
import { useEffect, useState } from "react";
import { Section } from "../Section";
import { api } from "../../lib/apiClient";
import { channelDataTypeLabel } from "../../lib/channelVocabulary";
import { useApiData } from "../../lib/useApiData";
import { CollectNowAction, deskSentence, useCollectNowRoute } from "./CollectNowAction";
import type {
  AcquisitionPathView,
  CapabilityView,
  ScheduleView,
} from "../../lib/types";
import { DATA_TYPES, INTERVALS, backendMessage } from "./channelShared";
import { ReviewAutoCheckToggle } from "./ReviewAutoCheckToggle";

/** 수집 설정 — one row per data type, each owning its own cadence + manual run. */
export function CollectionSettingsSection({
  accountId,
  channelCode,
  schedules,
  capabilities,
  onChanged,
  onReport,
  heading,
  hostOwnsScreenRead = false,
}: {
  accountId: string;
  /** Optional: without it the rows simply say less, never something untrue. */
  channelCode?: string | null;
  schedules: ScheduleView[];
  capabilities: CapabilityView[] | null;
  onChanged: () => void;
  onReport: (message: string, isError: boolean) => void;
  /**
   * 화면 읽기 자료의 수집 primary를 <b>이 화면을 품은 쪽</b>이 이미 그리고 있다는 선언.
   *
   * <p>채널이 아니라 <b>구성</b>이 정한다 — 이 값을 넘기는 것은 그 자료의 카드를 직접 그리는 화면이고,
   * 채널 이름으로 분기하는 코드는 여기에도 저기에도 없다. 켜면 화면 읽기 줄은 주기가 없는 이유만 말하고
   * 버튼을 그리지 않는다. 기본값은 예전과 바이트 동일하다.
   */
  hostOwnsScreenRead?: boolean;
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
            channelCode={channelCode}
            dataType={t.value}
            label={channelDataTypeLabel(channelCode, t.value, t.label)}
            schedule={schedules.find((s) => s.dataType === t.value) ?? null}
            capability={capabilities?.find((c) => c.dataType === t.value) ?? null}
            capabilitiesReady={capabilities !== null}
            acquisitionPaths={
              overview?.dataTypes.find((d) => d.dataType === t.value)?.acquisitionPaths ?? []
            }
            hostOwnsScreenRead={hostOwnsScreenRead}
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

function ScheduleRow({
  accountId,
  channelCode,
  dataType,
  label,
  schedule,
  capability,
  capabilitiesReady,
  acquisitionPaths,
  hostOwnsScreenRead,
  onChanged,
  onReport,
}: {
  accountId: string;
  /**
   * 로그인 복구를 어느 판매자센터로 열지 — 값으로 전달되기만 한다. 이 줄은 이 값을 읽고 분기하지 않는다
   * (경로는 서버가 답하고, 그 분기가 이번에 걷어낸 결함이었다).
   */
  channelCode?: string | null;
  dataType: string;
  label: string;
  schedule: ScheduleView | null;
  capability: CapabilityView | null;
  capabilitiesReady: boolean;
  acquisitionPaths: AcquisitionPathView[];
  hostOwnsScreenRead?: boolean;
  onChanged: () => void;
  onReport: (message: string, isError: boolean) => void;
}) {
  const [cadence, setCadence] = useState(schedule?.intervalMinutes ?? 360);
  const [saving, setSaving] = useState(false);
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
  const { loading: routeLoading, path: route, desk, lastSuccessAt, coverageThrough, coverageGapDays } =
      useCollectNowRoute(accountId, dataType, askedAgain);

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

  // 수집 primary는 하나다 — 두 분기가 각자 버튼을 만들면 둘은 언젠가 다르게 동작한다.
  const collectNow = (
    <CollectNowAction
      accountId={accountId}
      dataType={dataType}
      label={label}
      desk={desk}
      channelCode={channelCode ?? null}
      lastSuccessAt={lastSuccessAt}
      coverageThrough={coverageThrough}
      coverageGapDays={coverageGapDays}
      disabled={saving}
      showSentence={route === "SCREEN_READ"}
      onReport={onReport}
      onChanged={onChanged}
      onSettled={() => setAskedAgain((n) => n + 1)}
    />
  );

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

      {!capabilitiesReady || routeLoading ? (
        <p className="text-sm text-muted">수집 지원 정보 확인 중…</p>
      ) : route === "SCREEN_READ" ? (
        // 이 자료는 채널이 API로 내주지 않는다. 판매자 센터 화면을 내 컴퓨터의 도우미가 한 번 읽어 온다 —
        // 주기 자동 수집은 없고, 누름이 곧 그 한 번의 승인이다. 호스트 화면이 이미 이 자료의 카드를
        // 그리고 있으면(`hostOwnsScreenRead`) 이 줄은 주기가 없는 이유만 말한다 — 같은 자료에 같은
        // 버튼이 두 번 보이면, 판매자는 둘이 다른 일을 하는 줄로 읽는다.
        hostOwnsScreenRead ? (
          <p className="break-keep text-sm text-muted">{deskSentence(desk)}</p>
        ) : (
          // 자동 확인이 기본이고, 「지금 확인」은 수동 refresh다 — 그래서 토글이 먼저 서고 버튼이 그 옆에
          // 선다. 매번 눌러야 하는 제품처럼 보이지 않게 하는 것이 이 순서의 전부다.
          <div className="flex flex-wrap items-center gap-3">
            <ReviewAutoCheckToggle accountId={accountId} onReport={onReport} />
            {collectNow}
          </div>
        )
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
            disabled={saving}
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
              disabled={saving}
              onClick={() => save(true)}
              className="btn-primary px-4 py-2 text-base"
            >
              {saving ? "저장 중…" : "주기 적용"}
            </button>
          ) : null}
          <button
            type="button"
            disabled={saving}
            onClick={() => save(!enabled)}
            className={`rounded-xl px-4 py-2 text-base font-semibold ${
              enabled ? "bg-good/10 text-good" : "bg-canvas text-muted"
            }`}
          >
            {saving ? "저장 중…" : enabled ? "자동 수집 켜짐" : "자동 수집 꺼짐"}
          </button>
          {collectNow}
        </div>
      )}
    </li>
  );
}

