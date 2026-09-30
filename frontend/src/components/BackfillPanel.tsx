import { useMemo, useState } from "react";
import { isAxiosError } from "axios";
import { Section } from "./Section";
import { api } from "../lib/apiClient";
import {
  BACKFILL_DATA_TYPES,
  PRESETS,
  type PresetKey,
  resolvePresetRange,
  validateBackfill,
} from "../lib/backfillPresets";

// Operator-initiated bounded backfill: pick a date range (preset or custom) and
// one or more data types, then run a synchronous collection per type through the
// existing backfill runtime path. Channel-generic — it offers whatever the channel
// supports; the backend fails closed for anything it cannot serve.
//
// <b>「지난 기간 가져오기」, 예전 이름은 「기간 지정 수집」.</b> 판매자 화면에 수집 동작이 둘 있고, 이름만
// 보면 둘 다 「수집」이라 무엇이 다른지 알 수 없었다 — 실제로 다른 것은 <b>어느 구간을 읽는가</b> 하나다:
//
//   · 「지금 수집하기」(CollectionSettingsSection) — 커넥터가 정한 최근 구간을 다시 읽는다. Cafe24는
//     `Cafe24ApiConnector.LOOKBACK_DAYS = 14`, 다른 채널은 각자의 커서. 오래된 자료는 몇 번을 눌러도
//     들어오지 않는다. 그게 고장이 아니라 그 동작의 정의다.
//   · 이 패널 — 판매자가 <b>고른</b> 기간을 읽는다(`manualBackfill` → `BackfillWindow`). 오래된 자료를
//     넣는 유일한 길.
//
// 그래서 이름이 「기간 지정」(무엇을 지정하는지가 아니라 지정한다는 사실만 말한다)에서 「지난 기간
// 가져오기」(무엇을 하는지 말한다)로 바뀌었다.

function backendMessage(e: unknown): string | null {
  if (isAxiosError(e)) {
    const data = e.response?.data as { message?: string } | undefined;
    return data?.message ?? null;
  }
  return null;
}

interface RunResult {
  dataType: string;
  label: string;
  ok: boolean;
  detail: string;
}

const TYPE_LABEL: Record<string, string> = Object.fromEntries(
  BACKFILL_DATA_TYPES.map((t) => [t.value, t.label]),
);

export function BackfillPanel({
  accountId,
  onCompleted,
  dataTypes,
  heading,
}: {
  accountId: string;
  onCompleted?: () => void;
  /**
   * 이 화면이 실제로 기간 수집할 수 있는 종류. 생략하면 예전과 바이트 동일하다(전 종류).
   *
   * <b>이 칸이 생긴 이유.</b> 쿠팡 채널 화면에서 이 패널은 기본값으로 ✓리뷰를 켠 채 [이 기간 수집하기]를
   * 제공했는데, 쿠팡 리뷰에는 API 수집 경로가 없다(`supported:false`). 될 수 없는 수집을 시작하게 하는
   * 컨트롤은 없는 것보다 나쁘다 — 판매자는 실패를 자기 설정 문제로 읽는다.
   */
  dataTypes?: readonly string[];
  /**
   * `null`이면 제목 없이 본문만 — 이미 이름이 붙은 자리(접힌 영역) 안에서 열릴 때. 생략하면 예전과 동일하다.
   */
  heading?: string | null;
}) {
  const offered = useMemo(
    () => (dataTypes ? BACKFILL_DATA_TYPES.filter((t) => dataTypes.includes(t.value)) : BACKFILL_DATA_TYPES),
    [dataTypes],
  );
  const [preset, setPreset] = useState<PresetKey>("recent7");
  const [custom, setCustom] = useState({ from: "", to: "" });
  const [selected, setSelected] = useState<string[]>(() =>
    ["REVIEW", "INQUIRY"].filter((v) => !dataTypes || dataTypes.includes(v)),
  );
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<RunResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Resolve "today"-relative presets at render; custom uses the typed inputs.
  const range = useMemo(() => resolvePresetRange(preset, new Date(), custom), [preset, custom]);

  function toggleType(value: string) {
    setSelected((prev) =>
      prev.includes(value) ? prev.filter((v) => v !== value) : [...prev, value],
    );
  }

  async function run() {
    const check = validateBackfill(range, selected);
    if (!check.ok) {
      setError(check.error ?? "입력을 확인해 주세요.");
      setResults(null);
      return;
    }
    setError(null);
    setRunning(true);
    setResults(null);
    // Order the runs by the panel's display order for a stable result list.
    const ordered = offered.filter((t) => selected.includes(t.value));
    const collected: RunResult[] = [];
    for (const t of ordered) {
      try {
        const runView = await api.backfill(accountId, {
          dataType: t.value,
          startDate: range.from,
          endDate: range.to,
        });
        collected.push({
          dataType: t.value,
          label: t.label,
          ok: runView.status !== "FAILED",
          detail: `저장 ${runView.successRows} · 건너뜀 ${runView.skippedRows} · 실패 ${runView.failedRows}`,
        });
      } catch (e) {
        collected.push({
          dataType: t.value,
          label: t.label,
          ok: false,
          detail: backendMessage(e) ?? "수집에 실패했습니다.",
        });
      }
    }
    setResults(collected);
    setRunning(false);
    onCompleted?.();
  }

  const body = (
    <>
      <div className="space-y-4">
        {/* 두 동작의 차이를 이 패널 안에서 한 번 말한다. 「지금 수집하기」를 눌러도 오래된 자료가 들어오지
            않는 이유를 판매자가 알 수 있는 자리는 여기뿐이다. */}
        <p className="break-keep text-sm leading-relaxed text-muted">
          고른 기간을 한 번 읽어옵니다. 「지금 수집하기」는 최근 구간만 다시 확인하므로, 오래된 자료는
          여기서 기간을 골라 가져오세요. 이미 가져온 자료는 중복으로 저장되지 않습니다.
        </p>
        <div>
          <p className="mb-2 text-sm font-semibold text-muted">수집 기간</p>
          <div className="flex flex-wrap gap-2">
            {PRESETS.map((p) => (
              <button
                key={p.key}
                type="button"
                onClick={() => setPreset(p.key)}
                className={`rounded-xl px-4 py-2 text-base font-semibold ${
                  preset === p.key ? "bg-brand/10 text-brand-700" : "bg-canvas text-muted"
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
          {preset === "custom" ? (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <input
                type="date"
                value={custom.from}
                onChange={(e) => setCustom((c) => ({ ...c, from: e.target.value }))}
                className="rounded-xl border border-line px-3 py-2 text-base focus:border-brand focus:outline-none"
              />
              <span className="text-muted">~</span>
              <input
                type="date"
                value={custom.to}
                onChange={(e) => setCustom((c) => ({ ...c, to: e.target.value }))}
                className="rounded-xl border border-line px-3 py-2 text-base focus:border-brand focus:outline-none"
              />
            </div>
          ) : (
            <p className="mt-2 text-sm text-muted">
              {range.from} ~ {range.to}
            </p>
          )}
        </div>

        <div>
          <p className="mb-2 text-sm font-semibold text-muted">수집할 데이터</p>
          <div className="flex flex-wrap gap-2">
            {offered.map((t) => {
              const on = selected.includes(t.value);
              return (
                <button
                  key={t.value}
                  type="button"
                  onClick={() => toggleType(t.value)}
                  className={`rounded-xl px-4 py-2 text-base font-semibold ${
                    on ? "bg-good/10 text-good" : "bg-canvas text-muted"
                  }`}
                >
                  {on ? "✓ " : ""}
                  {t.label}
                </button>
              );
            })}
          </div>
        </div>

        {error ? <p className="rounded-xl bg-bad/10 px-4 py-3 text-base text-bad">{error}</p> : null}

        <button type="button" onClick={run} disabled={running} className="btn-primary">
          {running ? "수집 중…" : "이 기간 수집하기"}
        </button>

        {results ? (
          <ul className="divide-y divide-line rounded-xl border border-line">
            {results.map((r) => (
              <li key={r.dataType} className="flex items-center justify-between px-4 py-3">
                <span className="text-base font-semibold">{TYPE_LABEL[r.dataType] ?? r.label}</span>
                <span className={`text-sm font-semibold ${r.ok ? "text-good" : "text-bad"}`}>
                  {r.detail}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </>
  );
  if (heading === null) return <div className="space-y-3">{body}</div>;
  return <Section title={heading ?? "지난 기간 가져오기"}>{body}</Section>;
}
