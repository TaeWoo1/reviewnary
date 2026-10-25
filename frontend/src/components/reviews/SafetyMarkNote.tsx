import { useState } from "react";
import { api } from "../../lib/apiClient";
import { plainText } from "../../lib/plainText";
import { safetyLabel, safetyTone } from "../../lib/safetyLabel";
import type { SafetyMark } from "../../lib/types";
import { Status } from "../ui/Status";

// A review's safety mark on its own page (mockup ②, confirmed 2026-10-25): the label, the review's words with the
// expression that raised it marked, and one honest line — the mark is about what the review SAYS, and whether it
// happened is not known. 「표시가 맞지 않음」 keeps the seller's word and changes nothing; the line says 「의견 남김」.

/** The expression as a pattern that ignores spacing — the server matched it with the spaces taken out. */
function loose(term: string): RegExp {
  const chars = [...term.replace(/\s+/g, "")].map((c) => c.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return new RegExp(chars.join("\\s*"));
}

/** The review's sentence that carries the expression, split around it; null when it cannot be found again. */
export function markedSentence(body: string, term: string | null): { before: string; hit: string; after: string } | null {
  if (!term) return null;
  const sentences = body.split(/(?<=[.!?。])\s+|\n+/).map((s) => s.trim()).filter(Boolean);
  for (const sentence of sentences) {
    const m = loose(term).exec(sentence);
    if (m) {
      return { before: sentence.slice(0, m.index), hit: m[0], after: sentence.slice(m.index + m[0].length) };
    }
  }
  return null;
}

export function SafetyMarkNote({ reviewId, mark, body }: { reviewId: string; mark: SafetyMark; body: string | null }) {
  const [sent, setSent] = useState<"idle" | "sending" | "done" | "failed">("idle");
  const sentence = body ? markedSentence(plainText(body), mark.term) : null;

  async function report() {
    if (sent === "sending" || sent === "done") return;
    setSent("sending");
    try {
      await api.reportWrongSafetyMark(reviewId);
      setSent("done");
    } catch {
      setSent("failed");
    }
  }

  return (
    <div className="flex flex-col gap-1.5" data-testid="safety-mark">
      <div className="flex flex-wrap items-center gap-2">
        <Status tone={safetyTone(mark.reading)}>{safetyLabel(mark.kind, mark.reading)}</Status>
        {sentence ? (
          <span className="break-keep text-sm text-ink">
            {sentence.before}
            <mark className="rounded bg-bad/10 px-0.5 font-semibold text-ink underline decoration-bad decoration-2 underline-offset-4">
              {sentence.hit}
            </mark>
            {sentence.after}
          </span>
        ) : null}
      </div>
      <p className="text-xs text-muted">
        리뷰 문구 기준 표시 · 사실 여부 미확인 ·{" "}
        {sent === "done" ? (
          <span role="status" className="font-semibold text-good">
            의견 남김
          </span>
        ) : (
          <button
            type="button"
            aria-disabled={sent === "sending"}
            onClick={() => void report()}
            className="font-semibold underline underline-offset-2 hover:text-ink"
          >
            표시가 맞지 않음
          </button>
        )}
        {sent === "failed" ? <span role="alert" className="ml-1 text-bad">남기지 못했습니다. 다시 눌러 주세요.</span> : null}
      </p>
    </div>
  );
}
