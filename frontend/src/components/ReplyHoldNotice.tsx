import { useState } from "react";
import { Link } from "react-router-dom";
import { isAxiosError } from "axios";
import { api } from "../lib/apiClient";
import type { ReplyHold } from "../lib/types";

/**
 * <b>Why the reply cannot go on, and what to do</b> (2026-11-01 review: 「보류 상태 · 이유 · 다음 행동이 함께 보여야
 * 재검사 기능이 사용자에게 완성됩니다」). Drawn in the existing reply area, above the reply it is about — not a new screen.
 *
 * <p>One headline (「교환 정책이 바뀌어 다시 확인이 필요해요」), then 「바뀐 내용 보기」 for the reasons, the reply's own
 * sentences they are in and the clauses its numbers were written from, and 「답변 다시 만들기」. Three kinds of hold:
 * <ul>
 *   <li>FIX — a banned phrase, a policy conflict, the law, a number now wrong: no way past, the text (or the policy)
 *       changes first;</li>
 *   <li>EXCEPTION — a promise nothing registered backs (owner, 2026-11-01: 「일반 확인으로 우회하지 못하게」): add the
 *       ground, change the sentence, or decide 「이번 건만 예외」 after a second, explicit step that names the words;</li>
 *   <li>CONFIRM — a changed source or an unproven number: 「확인하고 진행」.</li>
 * </ul>
 * A decision is sent with the check it was made on; when the reasons changed meanwhile the server refuses it, and the
 * hold is read again so the new reasons are what the seller sees. The reply's text is never changed here.
 */
export function ReplyHoldNotice({
  hold,
  onConfirmed,
  onRegenerate,
  regenerateLabel = "답변 다시 만들기",
}: {
  hold: ReplyHold;
  /** After a decision was recorded, refused for new reasons, or the reply turned out to pass: the hold now. */
  onConfirmed: (next: ReplyHold | null) => void;
  /** Absent where this screen cannot write a new draft. */
  onRegenerate?: () => void;
  regenerateLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [excepting, setExcepting] = useState(false);
  const detailsId = `reply-hold-${hold.subjectId}`;

  async function decide(decision: "CONFIRM" | "EXCEPTION") {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      onConfirmed(await api.confirmReplyHold(hold.lane, hold.subjectId, hold.draftVersion, hold.checkId, decision));
      setExcepting(false);
    } catch (e) {
      const message = isAxiosError(e) ? (e.response?.data as { message?: string } | undefined)?.message : undefined;
      setError(message ?? "결정을 기록하지 못했어요. 다시 눌러 주세요.");
      if (isAxiosError(e) && e.response?.status === 409) {
        // The reasons may have changed while this was open: show the hold as it stands now.
        setExcepting(false);
        try {
          onConfirmed(await api.getReplyHold(hold.lane, hold.subjectId));
        } catch {
          // The refusal above already says what to do.
        }
      }
    } finally {
      setBusy(false);
    }
  }

  const label = hold.needs === "FIX" ? "고쳐야 해요" : hold.needs === "EXCEPTION" ? "결정 필요" : "확인 필요";
  const next =
    hold.needs === "FIX"
      ? hold.approved
        ? "이 부분은 확인만으로 보낼 수 없어요. 확정을 풀고 고친 뒤 다시 확정해 주세요."
        : "이 부분은 확인만으로 진행할 수 없어요. 고치거나 다시 만든 뒤 확정해 주세요."
      : hold.confirmed
        ? hold.needs === "EXCEPTION"
          ? hold.approved
            ? "이번 건만 예외로 결정했어요. 다시 등록할 수 있어요."
            : "이번 건만 예외로 결정했어요. 이제 확정할 수 있어요."
          : hold.approved
            ? "확인을 기록했어요. 다시 등록할 수 있어요."
            : "확인을 기록했어요. 이제 확정할 수 있어요."
        : hold.needs === "EXCEPTION"
          ? "근거를 추가하거나 문장을 고쳐 주세요. 이번 답변에만 그대로 쓰려면 「이번 건만 예외」로 결정해 주세요."
          : "내용을 확인했다면 「확인하고 진행」을 눌러 주세요. 확인한 기록이 남아요.";
  const button =
    "rounded-lg bg-surface px-2.5 py-1 text-sm font-semibold text-ink ring-1 ring-line hover:bg-canvas";
  const words = hold.unbacked.map((w) => `「${w}」`).join(" · ");

  return (
    <section
      role="status"
      aria-label="다시 확인이 필요한 답변"
      data-testid="reply-hold"
      className="rounded-xl border border-warn/30 bg-warn/5 px-3 py-2.5"
    >
      <p className="break-keep text-sm font-semibold text-ink">
        <span className="text-warn">{label}</span>
        <span aria-hidden="true"> · </span>
        {hold.headline}
      </p>
      <p className="mt-0.5 break-keep text-sm text-muted">{next}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={detailsId}
          onClick={() => setOpen((v) => !v)}
          className={button}
        >
          {open ? "바뀐 내용 접기" : "바뀐 내용 보기"}
        </button>
        {onRegenerate ? (
          <button type="button" onClick={onRegenerate} className={button}>
            {regenerateLabel}
          </button>
        ) : null}
        {hold.needs === "EXCEPTION" && !hold.confirmed ? (
          <>
            <Link to="/settings/policies" className={button}>
              근거 추가하기
            </Link>
            <button
              type="button"
              aria-expanded={excepting}
              onClick={() => setExcepting((v) => !v)}
              className={button}
            >
              이번 건만 예외
            </button>
          </>
        ) : null}
        {hold.needs === "CONFIRM" && !hold.confirmed ? (
          <button
            type="button"
            aria-busy={busy}
            aria-disabled={busy}
            onClick={() => void decide("CONFIRM")}
            className={button}
          >
            확인하고 진행
          </button>
        ) : null}
      </div>
      {excepting && hold.needs === "EXCEPTION" && !hold.confirmed ? (
        <div className="mt-2 rounded-lg bg-surface px-3 py-2 ring-1 ring-line">
          <p className="break-keep text-sm text-ink">
            {words}은(는) 등록된 정책·자료에 근거가 없어요. 이 답변에만 판매자 결정으로 쓰고, 다른 답변의 근거가 되지는 않아요.
            결정한 기록이 남아요.
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              aria-busy={busy}
              aria-disabled={busy}
              onClick={() => void decide("EXCEPTION")}
              className="rounded-lg bg-ink px-2.5 py-1 text-sm font-semibold text-white hover:bg-ink/80"
            >
              이번 건만 예외로 결정
            </button>
            <button type="button" onClick={() => setExcepting(false)} className={button}>
              취소
            </button>
          </div>
        </div>
      ) : null}
      {error ? (
        <p role="alert" className="mt-1.5 break-keep text-sm text-ink">
          {error}
        </p>
      ) : null}
      {open ? (
        <div id={detailsId} className="mt-2 flex flex-col gap-2 border-t border-warn/20 pt-2 text-sm text-ink">
          <ul className="flex list-disc flex-col gap-1 pl-5 break-keep">
            {hold.reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
          {hold.figures.some((f) => f.was) ? (
            <div>
              <p className="font-semibold">답변의 숫자와 그 근거</p>
              <ul className="mt-0.5 flex flex-col gap-0.5 break-keep text-muted">
                {hold.figures
                  .filter((f) => f.was)
                  .map((f) => (
                    <li key={f.figure}>
                      「{f.figure}」 — 근거였던 문장: 「{f.was}」
                      {f.verdict === "FIGURE_CHANGED"
                        ? " (지금 자료에는 없어요)"
                        : f.source === "EDITED"
                          ? " (문장을 고쳐 다시 확인이 필요해요)"
                          : " (지금 자료로는 확인할 수 없어요)"}
                    </li>
                  ))}
              </ul>
            </div>
          ) : null}
          {hold.sentences.length > 0 ? (
            <div>
              <p className="font-semibold">답변에서 확인할 문장</p>
              <ul className="mt-0.5 flex flex-col gap-0.5 break-keep text-muted">
                {hold.sentences.map((s) => (
                  <li key={s}>「{s}」</li>
                ))}
              </ul>
            </div>
          ) : null}
          {hold.changed.filter((c) => c).length > 0 ? (
            <p className="break-keep text-muted">
              바뀐 자료: {hold.changed.filter((c) => c).join(" · ")} — 상품·정책 메뉴에서 볼 수 있어요.
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
