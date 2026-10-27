import { useRef, useState } from "react";
import { isAxiosError } from "axios";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import type { KnowledgeWorkbookLine, KnowledgeWorkbookResult } from "../../lib/types";

/** The file name the seller sees in their downloads folder. */
export const WORKBOOK_FILE_NAME = "답글 기준.xlsx";

/**
 * <b>말투 · 답변 템플릿 · 안내 매뉴얼을 엑셀 한 파일로</b> (사장님 결정 2026-10-27).
 *
 * <p>내려받아 고치고, 올리면 <b>먼저 무엇이 바뀌는지만</b> 보여 준다 — 그 자리에서는 아무것도 쓰지 않는다.
 * 「적용」을 눌러야 줄마다 평소의 저장 경로로 들어가고, 거절된 줄은 그 줄 번호와 이유로 남는다.
 *
 * <p>「확인 전」인 줄은 답글에 쓰이지 않는다. 안내 매뉴얼의 「확인 전」 줄은 확인 필요에 제안으로 들어가고,
 * 【확인: …】이 남은 줄은 「사용」으로 등록되지 않는다 — 이 규칙은 서버가 지키고, 이 화면은 그 결과를 읽어 줄 뿐이다.
 *
 * <p><b>「적용」은 글을 보고 누른다</b> (2026-10-27 검토). 바뀌는 줄마다 어디에 쓰이는지, 지금 문장과 바뀔 문장을 나란히
 * 보인다. 내려받은 뒤 화면에서 바뀐 줄은 서버가 반영하지 않고, 여기에는 그 줄이 지금 무엇이라고 적혀 있는지가 선다.
 */
export function KnowledgeWorkbook({ onChanged }: { onChanged: () => Promise<void> | void }) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<KnowledgeWorkbookResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function download() {
    setBusy(true);
    setError(null);
    try {
      const blob = await api.downloadKnowledgeWorkbook();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = WORKBOOK_FILE_NAME;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setError("내려받지 못했습니다 · 다시 시도");
    } finally {
      setBusy(false);
    }
  }

  async function read(chosen: File, apply: boolean) {
    setBusy(true);
    setError(null);
    try {
      const next = await api.uploadKnowledgeWorkbook(chosen, apply);
      setResult(next);
      if (apply) {
        setFile(null);
        await onChanged();
      }
    } catch (e) {
      const message =
        isAxiosError(e) && e.response?.status === 400 && typeof e.response.data?.message === "string"
          ? e.response.data.message
          : "파일을 읽지 못했습니다 · 다시 시도";
      setError(message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3" data-testid="knowledge-workbook">
      <div className="flex flex-wrap items-center gap-2">
        <Btn size="sm" variant="outline" onClick={() => void download()} disabled={busy}>
          엑셀 내려받기
        </Btn>
        <Btn size="sm" variant="outline" onClick={() => input.current?.click()} disabled={busy}>
          엑셀 올리기
        </Btn>
        <input
          ref={input}
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          className="hidden"
          aria-label="엑셀 파일 고르기"
          onChange={(e) => {
            const chosen = e.target.files?.[0] ?? null;
            e.target.value = "";
            if (!chosen) return;
            setFile(chosen);
            setResult(null);
            void read(chosen, false);
          }}
        />
        <span className="break-keep text-xs text-muted">
          「사용」으로 적은 줄만 답글에 쓰입니다. 「확인 전」은 쓰이지 않습니다.
        </span>
      </div>

      {error ? (
        <p className="break-keep text-sm text-bad" role="alert">
          {error}
        </p>
      ) : null}

      {result ? (
        <div className="rounded-xl border border-line bg-surface p-4" role="status">
          <p className="break-keep text-sm font-semibold text-ink">
            {result.applied
              ? `반영했습니다 · ${result.changes.length}건`
              : result.changes.length === 0
                ? "바뀌는 것이 없습니다"
                : `올리면 바뀌는 것 · ${result.changes.length}건`}
          </p>
          <Lines lines={result.changes} />
          {result.problems.length > 0 ? (
            <>
              <p className="mt-3 break-keep text-sm font-semibold text-warn">
                반영하지 않는 줄 · {result.problems.length}건
              </p>
              <Lines lines={result.problems} />
            </>
          ) : null}
          {!result.applied && file && result.changes.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-2">
              <Btn size="sm" onClick={() => void read(file, true)} disabled={busy}>
                {busy ? "반영 중…" : "적용"}
              </Btn>
              <Btn
                size="sm"
                variant="ghost"
                onClick={() => {
                  setFile(null);
                  setResult(null);
                }}
                disabled={busy}
              >
                취소
              </Btn>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function Lines({ lines }: { lines: KnowledgeWorkbookLine[] }) {
  if (lines.length === 0) return null;
  return (
    <ul className="mt-1.5 space-y-2">
      {lines.map((line, i) => (
        <li key={`${line.sheet}-${line.row}-${i}`} className="flex gap-2 break-keep text-sm text-ink">
          <span className="shrink-0 text-xs tabular-nums text-muted">
            {line.sheet} {line.row}줄
          </span>
          <span className="min-w-0 flex-1">
            <span className="block">{line.text}</span>
            {line.where ? <span className="block text-xs text-muted">{line.where}</span> : null}
            {line.before != null || line.after != null ? (
              <span className="mt-1 grid gap-1 sm:grid-cols-2">
                <Version label={line.after != null ? "지금" : "화면의 지금 내용"} text={line.before} />
                {line.after != null ? <Version label="바뀐 뒤" text={line.after} strong /> : null}
              </span>
            ) : null}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** 한쪽 문장. 빈 것은 「(없음)」이라고 말한다 — 빈 칸은 「안 보여 줬다」와 구별되지 않는다. */
function Version({ label, text, strong = false }: { label: string; text?: string | null; strong?: boolean }) {
  return (
    <span className={`block rounded-lg border px-2.5 py-1.5 ${strong ? "border-brand-700/30 bg-brand-700/5" : "border-line bg-canvas"}`}>
      <span className="block text-xs font-semibold text-muted">{label}</span>
      <span className="line-clamp-6 block whitespace-pre-line text-sm text-ink">{text ? text : "(없음)"}</span>
    </span>
  );
}
