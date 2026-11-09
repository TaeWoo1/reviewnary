import { useCallback, useEffect, useState } from "react";
import { isAxiosError } from "axios";
import type { OpenQuestionItem, OpenQuestionResolution } from "../lib/types";
import { api } from "../lib/apiClient";
import { Btn } from "./ui/Btn";

/** The three reasons a seller can give — the backend's closed words, the screen's own copy. */
const REASONS: { value: OpenQuestionResolution; label: string }[] = [
  { value: "ANSWERED", label: "답을 덧붙임" },
  { value: "NOT_NEEDED", label: "답하지 않아도 됨" },
  { value: "ELSEWHERE", label: "따로 처리함" },
];

const CLOSED_LABEL: Record<string, string> = {
  ANSWERED: "답을 덧붙임",
  NOT_NEEDED: "답하지 않아도 됨",
  ELSEWHERE: "따로 처리함",
  REJUDGED: "다시 판단해 빠짐",
  // 2026-11-06 thirteenth review: closed by the system, never as answered — the seller's own record already did it.
  SETTLED_BY_ACTION: "조치 완료로 판단 불필요",
};

/**
 * The questions a draft could not answer from the store's material, one by one (2026-11-01 tenth review: 「질문별로
 * 처리하고, 해결 · 대응 불필요 등의 이유와 이력을 남긴다」).
 *
 * <p>A question belongs to the review or inquiry, not to the draft version, so rewording the reply leaves it here. It
 * closes when the seller presses 「처리함」 and says why, or when a new judgement of the whole message no longer asks it;
 * what was closed, and why, stays readable under 「처리한 질문」; what a later judgement dropped or reworded, under 「판단
 * 변경 이력」. On an inquiry (`required`) the reply is not sent while
 * one is open — the backend refuses, this only says so first.
 *
 * <p>`refreshKey` is the draft version the parent shows: a new version may have opened or closed questions.
 */
export function OpenQuestionList({
  lane,
  subjectId,
  refreshKey,
  fallback = [],
  onChange,
}: {
  lane: "REVIEW" | "INQUIRY";
  subjectId: string;
  refreshKey?: number | string | null;
  /** The head version's stored questions, shown when the list cannot be read (an older backend, a network failure). */
  fallback?: string[];
  /** Told how many required questions are still open, after every read. */
  onChange?: (openRequired: number) => void;
}) {
  const [items, setItems] = useState<OpenQuestionItem[] | null>(null);
  const [choosing, setChoosing] = useState<string | null>(null);
  const [reason, setReason] = useState<OpenQuestionResolution | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const read = await api.listOpenQuestions(lane, subjectId);
      setItems(read);
      onChange?.(read.filter((q) => q.state === "OPEN" && q.required).length);
    } catch {
      setItems(null);
    }
  }, [lane, subjectId, onChange]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const open = items ? items.filter((q) => q.state === "OPEN") : fallback.map((question) => ({ question, id: null }));
  const closedAll = items ? items.filter((q) => q.state !== "OPEN") : [];
  // What the seller (or the seller's own record) closed is 「처리한 질문」. A question a later judgement dropped or reworded
  // is the system changing its mind, not something handled — it goes to a folded history, so a question still open is
  // never also listed as done (2026-11-06 fourteenth review).
  const closed = closedAll.filter((q) => q.resolution !== "REJUDGED");
  const history = closedAll.filter((q) => q.resolution === "REJUDGED");
  const askOf = (question: string) => question.split(" — ")[0];
  const openAsks = new Set(open.map((q) => askOf(q.question)));
  if (open.length === 0 && closed.length === 0 && history.length === 0) return null;

  async function handle(id: string) {
    if (!reason) return;
    setBusy(true);
    setError(null);
    try {
      await api.handleOpenQuestion(id, reason, note.trim() || undefined);
      setChoosing(null);
      setReason(null);
      setNote("");
      await load();
    } catch (e) {
      setError(isAxiosError(e) && e.response?.status === 409 ? "이미 처리된 질문이에요. 목록을 새로 읽었어요." : "처리하지 못했어요. 다시 눌러 주세요.");
      await load();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="break-keep text-sm" role="status" data-testid="open-questions">
      {open.length > 0 ? (
        <>
          <p>
            <span className="font-semibold text-warn">답하지 못한 질문 {open.length}개</span>
            <span aria-hidden="true"> · </span>
            <span className="text-muted">
              {lane === "INQUIRY"
                ? "등록된 자료로 답할 수 없어 초안에 넣지 않았어요. 질문마다 처리해야 보낼 수 있어요."
                : "등록된 자료로 답할 수 없어 답글에 넣지 않았어요. 질문마다 어떻게 했는지 남겨 주세요."}
            </span>
          </p>
          <ul className="mt-1 flex flex-col gap-1.5">
            {open.map((q, i) => (
              <li key={q.id ?? `${i}-${q.question}`} className="flex flex-col gap-1" data-testid="open-question">
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <span className="text-ink">{q.question}</span>
                  {q.id && choosing !== q.id ? (
                    <Btn
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setChoosing(q.id);
                        setReason(null);
                        setNote("");
                        setError(null);
                      }}
                    >
                      처리함
                    </Btn>
                  ) : null}
                </div>
                {q.id && choosing === q.id ? (
                  <div className="flex flex-col gap-1.5 rounded-lg border border-line p-2" data-testid="open-question-handle">
                    <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="처리 이유">
                      {REASONS.map((r) => (
                        <Btn
                          key={r.value}
                          variant={reason === r.value ? "selected" : "outline"}
                          size="sm"
                          role="radio"
                          aria-checked={reason === r.value}
                          onClick={() => setReason(r.value)}
                        >
                          {r.label}
                        </Btn>
                      ))}
                    </div>
                    <input
                      type="text"
                      value={note}
                      maxLength={300}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="메모 (선택)"
                      aria-label="처리 메모"
                      className="min-h-[36px] rounded-lg border border-line bg-surface px-3 text-sm text-ink"
                    />
                    <div className="flex gap-1.5">
                      <Btn size="sm" disabled={!reason || busy} onClick={() => void handle(q.id as string)}>
                        확인
                      </Btn>
                      <Btn variant="ghost" size="sm" disabled={busy} onClick={() => setChoosing(null)}>
                        취소
                      </Btn>
                    </div>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {error ? <p className="mt-1 text-warn">{error}</p> : null}
      {closed.length > 0 ? (
        <details className="mt-1 text-muted" data-testid="open-questions-closed">
          <summary className="cursor-pointer">처리한 질문 {closed.length}개</summary>
          <ul className="mt-1 flex flex-col gap-0.5 pl-4">
            {closed.map((q) => (
              <li key={q.id}>
                {q.question}
                <span aria-hidden="true"> · </span>
                {CLOSED_LABEL[q.resolution ?? ""] ?? "처리함"}
                {q.resolution === "SETTLED_BY_ACTION" && q.basisRef?.startsWith("seller-check:") ? " (판매자 확인 기록 기준)" : ""}
                {q.note ? ` — ${q.note}` : ""}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      {history.length > 0 ? (
        <details className="mt-1 text-muted" data-testid="open-questions-history">
          <summary className="cursor-pointer">판단 변경 이력 {history.length}개</summary>
          <ul className="mt-1 flex flex-col gap-0.5 pl-4">
            {history.map((q) => (
              <li key={q.id}>
                {q.question}
                <span aria-hidden="true"> · </span>
                {openAsks.has(askOf(q.question)) ? "지금 남은 질문으로 바뀜" : "다시 판단해 빠짐"}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}
