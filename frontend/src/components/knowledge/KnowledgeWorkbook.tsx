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
      setError("내려받지 못했어요. 다시 눌러 주세요.");
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
          : "파일을 읽지 못했어요. 다시 눌러 주세요.";
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
          「사용」으로 적은 줄만 답글에 쓰여요. 「확인 전」은 쓰이지 않아요.
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
              ? `${result.changes.length}건을 반영했어요`
              : result.changes.length === 0
                ? "바뀌는 것이 없어요"
                : `올리면 바뀌는 것 ${result.changes.length}건`}
          </p>
          <Lines lines={result.changes} />
          {result.problems.length > 0 ? (
            <>
              <p className="mt-3 break-keep text-sm font-semibold text-warn">
                반영하지 않는 줄 {result.problems.length}건
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
        <LineRow key={`${line.sheet}-${line.row}-${i}`} line={line} />
      ))}
    </ul>
  );
}

/** 이만큼 넘으면 접어 둔다 — 그래도 끝까지 읽을 길은 늘 있다. */
const FOLD_CHARS = 180;
const FOLD_LINES = 6;

function long(text?: string | null) {
  return !!text && (text.length > FOLD_CHARS || text.split("\n").length > FOLD_LINES);
}

/**
 * 한 줄. 긴 문장은 처음엔 접어 두지만 「전체 보기」로 끝까지 펼친다(2026-10-27 두 번째 검토) — 긴 매뉴얼의 마지막 조건이
 * 바뀌었는데 그 부분을 읽지 못한 채 「적용」을 누르는 일이 없어야 한다. 펼치면 지금과 바뀐 뒤가 함께 펼쳐진다.
 */
function LineRow({ line }: { line: KnowledgeWorkbookLine }) {
  const [open, setOpen] = useState(false);
  const foldable = long(line.before) || long(line.after);
  const changed = changedLines(line.before, line.after);
  return (
    <li className="flex gap-2 break-keep text-sm text-ink">
      <span className="shrink-0 text-xs tabular-nums text-muted">
        {line.sheet} {line.row}줄
      </span>
      <span className="min-w-0 flex-1">
        <span className="block">{line.text}</span>
        {line.where ? <span className="block text-xs text-muted">{line.where}</span> : null}
        {line.before != null || line.after != null ? (
          <>
            <span className="mt-1 grid gap-1 sm:grid-cols-2">
              <Version label={line.after != null ? "지금" : "화면의 지금 내용"} text={line.before} open={open} />
              {line.after != null ? (
                <Version label="바뀐 뒤" text={line.after} open={open} strong against={line.before} />
              ) : null}
            </span>
            {foldable ? (
              <button
                type="button"
                className="mt-1 rounded text-xs font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                aria-expanded={open}
                onClick={() => setOpen((v) => !v)}
              >
                {open ? "접기" : changed > 0 ? `전체 보기 · 바뀐 줄 ${changed}개` : "전체 보기"}
              </button>
            ) : null}
          </>
        ) : null}
      </span>
    </li>
  );
}

/** 바뀐 뒤에만 있는 줄의 수 — 접혀 있어도 무엇이 바뀌었는지 있다는 것은 보이게. */
function changedLines(before?: string | null, after?: string | null) {
  if (!before || !after) return 0;
  const was = new Set(before.split("\n").map((l) => l.trim()));
  return after.split("\n").filter((l) => l.trim() !== "" && !was.has(l.trim())).length;
}

/**
 * 한쪽 문장. 빈 것은 「(없음)」이라고 말한다 — 빈 칸은 「안 보여 줬다」와 구별되지 않는다. {@code against}가 있으면 그 글에
 * 없던 줄을 표시한다 — 긴 매뉴얼에서 바뀐 한 줄을 눈으로 찾게 두지 않는다.
 */
function Version({
  label,
  text,
  open,
  strong = false,
  against,
}: {
  label: string;
  text?: string | null;
  open: boolean;
  strong?: boolean;
  against?: string | null;
}) {
  const was = new Set((against ?? "").split("\n").map((l) => l.trim()));
  const marking = against != null && against !== "";
  return (
    <span className={`block rounded-lg border px-2.5 py-1.5 ${strong ? "border-brand-700/30 bg-brand-700/5" : "border-line bg-canvas"}`}>
      <span className="block text-xs font-semibold text-muted">{label}</span>
      {/* 줄마다 제 블록이라 line-clamp는 접지 못한다 — 높이로 접는다(text-sm 한 줄 1.25rem × 6). */}
      <span
        className={`block text-sm text-ink ${open ? "" : "max-h-[7.5rem] overflow-hidden"}`}
        data-folded={open ? "false" : "true"}
      >
        {text
          ? text.split("\n").map((l, i) =>
              marking && l.trim() !== "" && !was.has(l.trim()) ? (
                <mark key={i} className="block rounded bg-warn/10 px-0.5 text-ink" title="바뀐 줄">
                  {l}
                </mark>
              ) : (
                <span key={i} className="block">
                  {l === "" ? "\u00a0" : l}
                </span>
              ),
            )
          : "(없음)"}
      </span>
    </span>
  );
}
