import { useState } from "react";
import type { KnowledgeConflictView, KnowledgeCurrencyView, KnowledgeScheduleView } from "../../lib/types";
import { api } from "../../lib/apiClient";
import { Btn } from "../ui/Btn";

/**
 * <b>자료끼리 다른 값</b> (2026-11-15, owner decision 1) — the seller is asked only when two documents disagree.
 *
 * <p>Each line names the item, both values and both documents. The seller settles it by saying which document is current
 * for that one item — 「이 자료가 저 자료를 대신함」 — or by giving the documents their 적용일. Nothing here decides by
 * when a file was uploaded. An unresolved line is why an answer stating that value is held, so it says so.
 */
export function KnowledgeConflicts({
  view,
  onChanged,
}: {
  view: KnowledgeCurrencyView;
  onChanged: () => void | Promise<void>;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const open = view.conflicts.filter((c) => c.status === "UNRESOLVED");
  const settled = view.conflicts.filter((c) => c.status !== "UNRESOLVED");

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await action();
      await onChanged();
    } catch {
      setError("바꾸지 못했어요. 다시 눌러 주세요.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div data-testid="knowledge-conflicts">
      {error ? <p className="break-keep pt-2 text-sm text-bad" role="alert">{error}</p> : null}
      {open.length === 0 ? (
        <p className="py-2 text-sm text-muted">값이 서로 다른 자료가 없어요.</p>
      ) : (
        <ul>
          {open.map((c) => (
            <ConflictRow key={keyOf(c)} conflict={c} busy={busy} run={run} />
          ))}
        </ul>
      )}
      {settled.length > 0 || view.replacements.length > 0 ? (
        <div className="pt-3">
          <p className="pb-1 text-xs text-muted">정리한 것</p>
          <ul>
            {settled.map((c) => (
              <li key={keyOf(c)} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-line/70 py-2">
                <span className="min-w-0 flex-1 break-keep text-sm text-ink">{c.sentence}</span>
                {c.replacementId ? (
                  <Btn
                    size="sm"
                    variant="ghost"
                    disabled={busy !== null}
                    onClick={() => void run(`undo:${c.replacementId}`, () =>
                      api.removeKnowledgeSupersession(c.replacementId as string))}
                  >
                    정한 것 취소
                  </Btn>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <p className="break-keep border-t border-line py-2 text-xs text-muted">
        올린 날짜로는 정하지 않아요. 정해지지 않은 값은 답변에 확정된 사실로 쓰이지 않고, 그 값을 적은 답변은 등록 전에 멈춰요.
      </p>
    </div>
  );
}

function keyOf(c: KnowledgeConflictView): string {
  return `${c.attribute}|${c.condition}|${c.current.sourceId}|${c.other.sourceId}`;
}

/** One unresolved disagreement: the item, the two values with their documents, and how to settle it. */
function ConflictRow({
  conflict,
  busy,
  run,
}: {
  conflict: KnowledgeConflictView;
  busy: string | null;
  run: (key: string, action: () => Promise<unknown>) => Promise<void>;
}) {
  const sides = [conflict.current, conflict.other];
  return (
    <li className="space-y-2 border-t border-line/70 py-3" data-testid="knowledge-conflict">
      <p className="break-keep text-sm font-semibold text-ink">{conflict.subject}이(가) 자료마다 달라요</p>
      <ul className="space-y-2">
        {sides.map((side, i) => {
          const other = sides[1 - i];
          const key = `pick:${keyOf(conflict)}:${side.sourceId}`;
          return (
            <li key={side.sourceId} className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="min-w-0 flex-1 break-keep text-sm text-ink">
                <b className="font-semibold tabular-nums">{side.value}</b>
                <span className="text-muted"> · 「{side.title}」</span>
                {side.effectiveFrom ? (
                  <span className="ml-1 text-xs text-muted">적용일 {side.effectiveFrom}</span>
                ) : null}
              </span>
              <Btn
                size="sm"
                variant="outline"
                disabled={busy !== null}
                onClick={() => void run(key, () => api.supersedeKnowledge({
                  newerSourceId: side.sourceId,
                  olderSourceId: other.sourceId,
                  attribute: conflict.attribute,
                  condition: conflict.condition,
                }))}
              >
                {busy === key ? "정하는 중…" : "이 자료가 현행"}
              </Btn>
            </li>
          );
        })}
      </ul>
      <p className="break-keep text-xs text-muted">
        {conflict.reason === "ORDER_DATE_UNKNOWN"
          ? "두 자료에 적용일이 있지만, 주문일을 모르는 문의에는 어느 기준이 맞는지 정할 수 없어요. 현행 자료를 골라 주세요."
          : `현행 자료를 고르면 이 항목(${conflict.subject})만 대신해요. 같은 자료의 다른 내용은 그대로 남아요.`}
      </p>
    </li>
  );
}

/**
 * <b>입고·출고·생산 일정</b> (owner decision 4). Written through the workbook's 「입고·출고 일정」 sheet; turned on or off here.
 * A 예정 line is never told to a customer as a fixed date, a past line is history, and none is any one customer's order.
 */
export function KnowledgeSchedules({
  schedules,
  onChanged,
}: {
  schedules: KnowledgeScheduleView[];
  onChanged: () => void | Promise<void>;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (schedules.length === 0) {
    return (
      <p className="py-2 break-keep text-sm text-muted">
        아직 적어 둔 일정이 없어요. 아래 「원본 자료 → 엑셀로 한 번에 고치기」에서 내려받은 파일의 「입고·출고 일정」 시트에 적어 올려 주세요.
      </p>
    );
  }
  return (
    <div data-testid="knowledge-schedules">
      {error ? <p className="break-keep pt-2 text-sm text-bad" role="alert">{error}</p> : null}
      <ul>
        {schedules.map((s) => (
          <li key={s.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line/70 py-2">
            <span className="min-w-0 flex-1 break-keep text-sm text-ink">
              {s.productName ?? "전체 상품"}
              {s.optionName ? ` · ${s.optionName}` : ""}
              <span className="text-muted"> — {s.kindLabel} </span>
              <b className={`font-semibold ${s.status === "PLANNED" ? "text-warn" : "text-ink"}`}>{s.statusLabel}</b>
              <span className="tabular-nums"> {s.scheduleDate ?? "날짜 미정"}</span>
              {s.scopeNote ? <span className="text-muted"> · {s.scopeNote}</span> : null}
              {s.lastCheckedOn ? <span className="ml-1 text-xs text-muted">마지막 확인 {s.lastCheckedOn}</span> : null}
              {s.past ? <span className="ml-2 text-xs text-muted">지난 일정</span> : null}
              {s.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
            </span>
            <Btn
              size="sm"
              variant="ghost"
              disabled={busy === s.id}
              onClick={async () => {
                setBusy(s.id);
                setError(null);
                try {
                  await api.setKnowledgeScheduleActive(s.id, !s.active);
                  await onChanged();
                } catch {
                  setError("바꾸지 못했어요. 다시 눌러 주세요.");
                } finally {
                  setBusy(null);
                }
              }}
            >
              {s.active ? "쓰지 않기" : "다시 켜기"}
            </Btn>
          </li>
        ))}
      </ul>
      <p className="break-keep border-t border-line py-2 text-xs text-muted">
        「예정」은 바뀔 수 있다고 함께 안내하고, 지난 날짜는 답변에 쓰지 않아요. 일정은 상품의 일정이지 고객 한 분의 주문 상태가 아니에요.
      </p>
    </div>
  );
}
