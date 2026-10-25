import { useRef, useState } from "react";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import { SecureRandomUnavailableError, newCommandId } from "../../lib/commandId";

/**
 * <b>답변 자리는 처음부터 거기 있다</b> (2026-10-26, owner: the AI draft button could not be found until 「대응 필요」 was
 * pressed — 「버튼이 없는데?」).
 *
 * <p>A review with reply work and no decision used to show no reply area at all: the seller had to answer 처리 방법
 * first, and only 대응 필요 opened the panel. This is that panel's place before the decision — the same box, empty,
 * with the two ways to start. Starting <b>is</b> the decision: a press records 대응 필요 through the one decision
 * endpoint {@code VocItemTriageControl} uses (same command-id rule: a retry of the same failed intent reuses its id),
 * and only then does the reply panel mount, so every capability it reads was computed under 대응 필요. No draft is
 * made on open — a model call happens on the press, never because a page was looked at.
 *
 * <p>두고 보기 · 조치 불필요 stay where they were, under 처리 방법; choosing either still means no reply.
 */
export function ReplyStarter({
  reviewId,
  onStarted,
}: {
  reviewId: string;
  /** 대응 필요 stands on the server; {@code withAi} says whether the seller asked for a draft or to write it. */
  onStarted: (withAi: boolean) => void;
}) {
  const [busy, setBusy] = useState<null | "ai" | "self">(null);
  const [failed, setFailed] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  // One intent (대응 필요) — so a retry after a failure is the same command, replayed rather than duplicated.
  const commandId = useRef<string | null>(null);

  async function start(withAi: boolean) {
    if (busy) return;
    try {
      commandId.current ??= newCommandId();
    } catch (e) {
      if (e instanceof SecureRandomUnavailableError) {
        setUnavailable(true);
        return;
      }
      throw e;
    }
    setBusy(withAi ? "ai" : "self");
    setFailed(false);
    try {
      const result = await api.recordReviewDecision(reviewId, {
        commandId: commandId.current,
        disposition: "RESPONSE_NEEDED",
      });
      if (result?.disposition !== "RESPONSE_NEEDED") {
        setFailed(true);
        return;
      }
      onStarted(withAi);
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
    }
  }

  return (
    <section aria-labelledby={`reply-start-${reviewId}`} className="space-y-3" data-testid="reply-starter">
      <h2 id={`reply-start-${reviewId}`} className="text-sm font-semibold text-ink">
        답변 준비
      </h2>
      <div className="rounded-lg bg-canvas px-4 py-5">
        <p className="break-keep text-sm text-muted">아직 답변 초안이 없습니다.</p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Btn size="sm" onClick={() => void start(true)} disabled={busy !== null || unavailable}>
            {busy === "ai" ? "준비하는 중…" : "AI 초안 준비"}
          </Btn>
          <Btn size="sm" variant="outline" onClick={() => void start(false)} disabled={busy !== null || unavailable}>
            직접 쓰기
          </Btn>
          <span className="break-keep text-xs text-muted">누르면 이 리뷰가 「대응 필요」로 기록됩니다.</span>
        </div>
      </div>
      {failed ? (
        <p role="alert" className="break-keep text-sm text-bad">
          답변 준비를 시작하지 못했습니다. 다시 눌러 주세요.
        </p>
      ) : null}
      {unavailable ? (
        <p role="alert" className="break-keep text-sm text-bad">
          이 브라우저에서는 기록을 남길 수 없어 답변 준비를 시작할 수 없습니다.
        </p>
      ) : null}
    </section>
  );
}
