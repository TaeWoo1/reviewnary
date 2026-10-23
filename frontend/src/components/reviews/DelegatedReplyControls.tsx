import { useRef, useState } from "react";
import { api } from "../../lib/apiClient";
import type { ReviewReplyDelegation, ReviewReplyDelegationNote } from "../../lib/types";
import { Status, type StatusTone } from "../ui/Status";

// The delegate lane on the review panel (docs/vendor_server_contract_v1.md §4; owner decisions D7 · D9 · D10,
// mockup confirmed 2026-10-21). The seller sends a saved draft either straight to the delegate — approved here —
// or to their phone for approval; the delegate pastes it into Seller Center by hand. Nothing on this panel posts
// anything, and nothing is sent without the seller's press.
//
// Every control is drawn from the SERVER's `delegation` (canSend / canCancel), never re-derived here.

/** What each server state reads as. `POSTED` is the delegate's report, never a proven state — so never `good`. */
const STATE: Record<string, { word: string; tone: StatusTone; note?: string }> = {
  AWAITING_APPROVAL: { word: "휴대폰 승인 기다리는 중", tone: "info" },
  RECHECK_NEEDED: {
    word: "다시 확인 필요",
    tone: "warn",
    note: "응대 기준이 바뀌어 이 초안은 승인할 수 없습니다. 초안을 확인한 뒤 다시 보내세요.",
  },
  APPROVED: { word: "승인됨 · 담당자 등록 대기", tone: "info" },
  CLAIMED: { word: "담당자가 등록하는 중", tone: "info" },
  CANCEL_REQUESTED: {
    word: "취소 요청함 · 담당자 확인 중",
    tone: "warn",
    note: "담당자가 이미 등록을 시작해, 등록 전인지 담당자가 확인한 뒤 끝납니다.",
  },
  POSTED: {
    word: "담당자가 등록함 · 확인 전",
    tone: "neutral",
    note: "담당자의 보고입니다. 판매자센터에서 직접 확인한 것은 아닙니다.",
  },
  UNKNOWN: {
    word: "담당자가 확인 중",
    tone: "warn",
    note: "등록됐는지 담당자가 확실하지 않다고 보고했습니다. 확인될 때까지 다시 보낼 수 없습니다.",
  },
  FAILED: { word: "담당자가 등록하지 못함", tone: "neutral", note: "다시 보내거나 여기서 직접 처리할 수 있습니다." },
  CANCELLED: { word: "요청 취소됨", tone: "neutral" },
  // Owner decision 2026-10-23: an approval is held only when its words lost their ground, and says why below.
  HELD: { word: "승인 보류 · 다시 승인 필요", tone: "warn" },
  HOLD_REQUESTED: {
    word: "승인 보류 · 담당자 확인 중",
    tone: "warn",
    note: "담당자가 등록을 시작한 답글이라 담당자에게도 멈춤을 전했습니다. 담당자가 등록 전인지 확인하면 이 표시가 바뀝니다.",
  },
  INVALIDATED: {
    word: "요청이 끝남",
    tone: "neutral",
    note: "리뷰가 바뀌었거나 이미 답글이 있어 더 진행하지 않습니다.",
  },
};

/** States where a new press re-sends the open task's text rather than opening a new one. */
const AWAITING = new Set(["AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD"]);

const SOURCE_KIND: Record<string, string> = { PRODUCT_KNOWLEDGE: "상품 지식", ORG_POLICY: "운영 정책" };

function when(iso: string | null): string {
  if (!iso) return "";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleString("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
}

/**
 * <b>Why a version was stopped</b> (owner decision 2026-10-23; mockup confirmed the same day): what changed, which
 * sentence it touches, and what to do. Drawn only from the PC's own finding — nothing here is inferred on screen.
 * A cited document that was only edited, under an approval that stands, is one quiet line instead. A phone edit the
 * PC checked before approval is not a hold: before approval it is 「확인할 점」, after it one quiet line.
 */
function WhyStopped({ state, note }: { state: string; note: ReviewReplyDelegationNote }) {
  const promise = note.codes.includes("UNSUPPORTED_PROMISE");
  const forbidden = note.codes.includes("FORBIDDEN_PHRASE") && (note.phrases?.length ?? 0) > 0;
  const source = note.sources[0];
  const changed = source
    ? `${SOURCE_KIND[source.kind] ?? "자료"} 「${source.title}」`
    : null;
  const changedWhen = source ? (source.removed ? "사용 중지됨" : `${when(source.changedAt)}에 고쳐짐`) : null;
  const words = note.promises.map((p) => `「${p}」`).join(" · ");
  const phrases = (note.phrases ?? []).map((p) => `「${p}」`).join(" · ");
  if (state === "APPROVED" || state === "CLAIMED") {
    if (promise || forbidden) {
      const seen = [promise ? `근거에 없는 약속 ${words}` : null, forbidden ? `쓰지 않기로 한 표현 ${phrases}` : null]
        .filter(Boolean)
        .join(", ");
      return (
        <p className="text-xs leading-relaxed text-muted">
          확인하고 승인한 답글입니다 — {seen}. 응대 기준이나 답변 스타일에 맞게 고쳐 두면 다음 답글부터 걸리지 않습니다.
        </p>
      );
    }
    return changed ? (
      <p className="text-xs leading-relaxed text-muted">
        근거로 쓴 {changed} 문서가 바뀌었지만, 이 답글과 부딪히는 곳이 없어 승인을 그대로 둡니다.
      </p>
    ) : null;
  }
  const advisory = state === "AWAITING_APPROVAL";
  const heading = advisory ? "확인할 점" : "왜 멈췄나요";
  const todo = promise
    ? advisory
      ? "휴대폰에서 승인하기 전에 이 약속이 맞는지 확인하세요. 맞다면 응대 기준에 적어 두면 근거가 생깁니다."
      : state === "RECHECK_NEEDED"
        ? "문장을 고쳐 다시 보내세요. 그 약속이 맞다면 응대 기준에 먼저 적으세요."
        : "문장을 고쳐 다시 승인하세요. 그 약속이 맞다면 응대 기준에 먼저 적은 뒤 다시 승인하세요."
    : forbidden
      ? advisory
        ? "휴대폰에서 승인하기 전에 그 표현을 빼고 고치세요."
        : state === "RECHECK_NEEDED"
          ? "그 표현을 빼고 고쳐서 다시 보내세요."
          : "그 표현을 빼고 문장을 고쳐 다시 승인하세요."
      : "고쳐진 문서와 초안을 비교해 보고, 맞으면 그대로, 아니면 고쳐서 다시 보내세요.";
  return (
    <section aria-label={heading} className="flex flex-col gap-2 rounded-xl bg-surface p-3 ring-1 ring-line">
      <h4 className="text-sm font-bold text-ink">{heading}</h4>
      <dl className="grid grid-cols-[88px_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm leading-relaxed">
        {changed ? (
          <>
            <dt className="text-muted">바뀐 근거</dt>
            <dd className="text-ink">
              {changed}
              <span className="block text-xs text-muted">{changedWhen}</span>
            </dd>
          </>
        ) : null}
        <dt className="text-muted">{promise || forbidden ? "부딪히는 곳" : "영향받는 문장"}</dt>
        <dd className="text-ink">
          {promise || forbidden ? (
            <>
              {promise ? <span className="block">답글의 {words} 약속이 지금 근거에는 없습니다.</span> : null}
              {forbidden ? <span className="block">회사가 쓰지 않기로 한 표현이 있습니다: {phrases}</span> : null}
              {note.sentences.map((s) => (
                <mark key={s} className="mt-1 block rounded bg-warn/10 px-1.5 py-0.5 font-semibold text-ink">
                  {s}
                </mark>
              ))}
            </>
          ) : (
            "이 초안은 그 문서를 근거로 썼습니다. 어느 문장이 달라졌는지는 아직 짚지 못합니다."
          )}
        </dd>
        <dt className="text-muted">할 일</dt>
        <dd className="font-semibold text-ink">{todo}</dd>
      </dl>
    </section>
  );
}

type Press = "handOver" | "phone" | "cancel";

/** The backend's own Korean sentence for a refusal (409 / 503), else a generic one. */
function refusal(e: unknown): string {
  const response = (e as { response?: { status?: number; data?: { message?: string } } })?.response;
  if ((response?.status === 409 || response?.status === 503) && response.data?.message) {
    return response.data.message;
  }
  return "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

export function DelegatedReplyControls({
  delegation,
  reviewId,
  dirty,
  disabled,
  blocked = null,
  onChanged,
}: {
  delegation: ReviewReplyDelegation;
  reviewId: string;
  /** The editor holds text that is not saved — every press sends the SAVED draft, so it must wait. */
  dirty: boolean;
  /** The panel itself has a write in flight. */
  disabled: boolean;
  /**
   * Why the saved draft may not leave yet — a company ban it carries (2026-10-23), said in the panel's own words.
   * The server refuses the send on the same check; this keeps the presses off and says why beneath them.
   */
  blocked?: string | null;
  /** Re-read the panel: the server's verdicts (this lane's and the reply lane's) moved. */
  onChanged: () => Promise<unknown> | void;
}) {
  const [busy, setBusy] = useState<Press | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const inFlight = useRef(false);

  const state = delegation.taskState;
  const shown = state == null ? null : STATE[state];
  const working = busy != null || disabled;
  const canSend = delegation.canSend && !dirty && blocked == null;

  async function press(which: Press) {
    // aria-disabled keeps the button focusable, so the press itself refuses whatever the look says is off.
    if (inFlight.current || disabled) return;
    if (which === "cancel" ? !delegation.canCancel : !canSend) return;
    inFlight.current = true;
    setBusy(which);
    setFailed(null);
    try {
      if (which === "handOver") await api.handOverReviewReply(reviewId);
      else if (which === "phone") await api.requestPhoneApproval(reviewId);
      else await api.cancelReviewReplyDelegation(reviewId);
    } catch (e) {
      setFailed(refusal(e));
    } finally {
      // Re-read either way: a refused hand-over may still have sent the draft for the phone.
      await onChanged();
      inFlight.current = false;
      setBusy(null);
    }
  }

  const sendLabel = state != null && AWAITING.has(state) ? "고친 초안 다시 보내기" : "휴대폰으로 승인 요청";

  return (
    <div className="flex flex-col gap-2" data-testid="delegated-reply">
      {shown != null ? (
        <div className="flex flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            {/* The panel itself is canvas; a neutral chip's tint is canvas too, so it carries a hairline. */}
            <Status tone={shown.tone} className={shown.tone === "neutral" ? "ring-1 ring-line" : ""}>
              {shown.word}
            </Status>
            {delegation.editedOnMobile ? (
              <Status tone="neutral" className="ring-1 ring-line">
                휴대폰에서 고침
              </Status>
            ) : null}
          </div>
          {shown.note != null && !(state === "RECHECK_NEEDED" && delegation.note) ? (
            <p className="text-xs leading-relaxed text-muted">{shown.note}</p>
          ) : null}
        </div>
      ) : null}
      {state != null && delegation.note ? <WhyStopped state={state} note={delegation.note} /> : null}

      {delegation.canSend || delegation.canCancel ? (
        <div className="flex flex-wrap items-center gap-2">
          {delegation.canSend ? (
            <>
              <button
                type="button"
                aria-disabled={!canSend || working}
                aria-busy={busy === "handOver"}
                onClick={() => void press("handOver")}
                className={`rounded-lg px-3 py-1.5 text-sm font-semibold ${
                  canSend && !working ? "bg-brand-700 text-white hover:bg-brand-800" : "bg-canvas text-muted opacity-40"
                }`}
              >
                승인하고 담당자에게 맡기기
              </button>
              <button
                type="button"
                aria-disabled={!canSend || working}
                aria-busy={busy === "phone"}
                onClick={() => void press("phone")}
                className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${
                  canSend && !working ? "bg-white text-ink ring-1 ring-line" : "bg-canvas text-muted opacity-40"
                }`}
              >
                {sendLabel}
              </button>
            </>
          ) : null}
          {delegation.canCancel ? (
            <button
              type="button"
              aria-disabled={working}
              aria-busy={busy === "cancel"}
              onClick={() => void press("cancel")}
              className="rounded px-1 py-1 text-sm font-semibold text-muted underline-offset-2 hover:text-ink hover:underline"
            >
              요청 취소
            </button>
          ) : null}
        </div>
      ) : null}

      {delegation.canSend && !dirty && blocked != null ? (
        <p className="break-keep text-sm text-muted">{blocked}</p>
      ) : null}
      {delegation.canSend && dirty ? (
        <p className="text-sm text-muted">저장하지 않은 변경이 있습니다. 먼저 초안을 저장하세요.</p>
      ) : null}
      {delegation.canSend && state == null ? (
        <p className="text-xs leading-relaxed text-muted">
          맡기면 담당자가 판매자센터에 이 문장 그대로 등록합니다. 휴대폰으로 보내면 휴대폰에서 승인한 뒤 담당자가
          등록합니다. 누르기 전에는 아무것도 보내지 않습니다.
        </p>
      ) : null}
      {state != null && !AWAITING.has(state) && !["FAILED", "CANCELLED", "INVALIDATED", "POSTED"].includes(state) ? (
        <p className="text-xs leading-relaxed text-muted">
          담당자에게 맡긴 동안에는 여기서 승인하거나 판매자센터 등록을 시작할 수 없습니다. 답글이 두 번 달리지 않게
          하기 위해서입니다.
        </p>
      ) : null}
      {state === "POSTED" ? (
        <p className="text-xs leading-relaxed text-muted">
          답글이 두 번 달리지 않도록, 채널에서 답글이 확인되기 전까지 여기서 다시 승인하거나 등록하지 않습니다.
        </p>
      ) : null}
      {state != null && AWAITING.has(state) && state !== "HELD" ? (
        <p className="text-xs leading-relaxed text-muted">
          휴대폰에 보낸 동안에는 여기서 따로 승인할 수 없습니다. 여기서 맡기면 휴대폰 승인 없이 바로 담당자에게 갑니다.
        </p>
      ) : null}
      {state === "HELD" ? (
        <p className="text-xs leading-relaxed text-muted">
          초안을 고쳐 저장한 뒤 다시 보내면 새로 승인을 받습니다. 그전까지 담당자는 이 답글을 등록하지 않습니다.
        </p>
      ) : null}
      {failed != null ? (
        <p role="alert" className="text-sm text-bad">
          {failed}
        </p>
      ) : null}
    </div>
  );
}
