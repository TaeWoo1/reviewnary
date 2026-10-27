import { useEffect, useRef, useState } from "react";
import { isAxiosError } from "axios";
import type { DraftEvidenceView, GeneratedReviewDraftView, ReviewKnowledgeGapView } from "../lib/types";
import { api } from "../lib/apiClient";
import { Btn } from "./ui/Btn";
import { AnswerBasisQuickAdd } from "./inbox/AnswerBasisQuickAdd";
import { ReviewGuidance } from "./reviews/ReviewGuidance";

/**
 * <b>AI 답변 준비 — the grounded draft, and what it was written from</b> (Grounded Review Drafting v1).
 *
 * <p>The order on screen is the order the seller needs it in: <b>the draft first</b>, because that is
 * what they came for; then, quietly, 「왜 이렇게 썼어요?」 — the passages the drafter was actually shown,
 * with the excerpt that makes each one checkable rather than a title to take on trust; then, when
 * something is missing, the one thing they could register so the next draft can say more.
 *
 * <p><b>It writes into the editor above it and nothing else.</b> The generated version is saved by the
 * backend as an ordinary append-only version; this component hands the text to the panel it lives in
 * so the seller edits, saves and approves through exactly the controls they already use. It never
 * approves, never sends, and never touches the submission path.
 */
/**
 * Why a generation did not happen, as the seller reads it.
 *
 * <p>Two answers, because there are two situations and they call for different next steps. A 409 is
 * the review's own state — an approval stands, or it is not 대응 필요 — and the seller fixes it on
 * this screen. Anything else is the machine, and the answer is to press the button again.
 *
 * <p>Exported and pure so the mapping is checkable: the component's catch is one line over it.
 */
export function generateFailureMessage(status: number | undefined): string {
  return status === 409
    ? "확정한 답글이 있거나 「답글 달기」로 정하지 않은 리뷰라 AI 답글을 쓸 수 없어요."
    : "AI 답글을 쓰지 못했어요. 잠시 뒤 다시 눌러 주세요.";
}

export function GroundedReviewDraft({
  accountId,
  actionRef,
  storedEvidence,
  storedBasis,
  storedBasisNote,
  hasStoredDraft,
  onDrafted,
  autoStart = false,
}: {
  accountId: string;
  actionRef: string;
  /** The head version's stored citations — what 「왜 이렇게 썼어요?」 shows after a reload. */
  storedEvidence: DraftEvidenceView[];
  /**
   * The head version's stored basis and its sentence — what 「근거 있음 / 기본 문구」 says after a
   * reload (Retrieval Runtime Closure v1 §1). Both null when the version recorded no basis, and then
   * the line is not rendered: "not recorded" is a different statement from either basis.
   */
  storedBasis: string | null;
  storedBasisNote: string | null;
  /**
   * Whether a draft version is already SAVED for this review — the persisted head, not this session.
   *
   * <p>The label used to read this session's `result` alone, so the same review said 「다시 준비하기」
   * before a reload and 「AI 초안 준비」 after one, over an editor that still held the saved draft
   * (pilot QA, 2026-09-06). Pressing it replaces what is in the editor; a control that offers to
   * prepare a first draft over an existing one is the wrong promise, and it is wrong precisely at the
   * moment the seller has forgotten what is there.
   *
   * <p>Any author counts. The question this word answers is 「is there already a draft here that this
   * press would replace?」, and a version the seller typed themselves answers it just as much as a
   * generated one. What WROTE the head is a separate fact, and the panel above states it.
   */
  hasStoredDraft: boolean;
  /** Hand the generated body to the editor. Called synchronously; never awaited. */
  onDrafted: (body: string) => void;
  /**
   * The seller already pressed 「AI 초안 준비」 — on the empty reply area before this panel existed (2026-10-26,
   * {@code ReplyStarter}). Generate once on mount, exactly as that press would here; never over a stored draft.
   */
  autoStart?: boolean;
}) {
  const [result, setResult] = useState<GeneratedReviewDraftView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showEvidence, setShowEvidence] = useState(false);

  // What is on screen: this session's generation if there is one, else the stored head's record.
  // The stored record is what survives a reload — 「왜 이렇게 썼어요?」 has to answer the same way
  // tomorrow, and it answers from the citations the saved version carries.
  const evidence = result ? result.evidence : storedEvidence;
  const gaps = result?.knowledgeGaps ?? [];
  // The same rule as the citations above: this session's generation if there is one, else the saved
  // version's own record. The basis is the fact that decides whether the text in the editor is this
  // company's knowledge or its safe default, so it has to survive a reload — before this it did not.
  const basis = result ? result.answerBasis : storedBasis;
  // The server's own sentence (`answerBasisNote`) is not drawn: the screen owns its words (§12), and one line said
  // in the product's voice replaces a chip and a server paragraph that said the same thing twice.
  void storedBasisNote;

  const autoStarted = useRef(false);
  useEffect(() => {
    if (!autoStart || hasStoredDraft || autoStarted.current) return;
    autoStarted.current = true;
    void generate();
    // Once per mount: the press it stands for happened once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function generate() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const view = await api.generateReviewReplyDraft(accountId, actionRef);
      setResult(view);
      setShowEvidence(false);
      // Hand the text over WITHOUT awaiting. What the seller is waiting for is the draft, and it is
      // in hand; whatever the panel does next to re-read its own state must not be able to hold this
      // control in 「준비하는 중…」 — measured 2026-09-03, a refresh that did not settle left the
      // button spinning over a draft that had already arrived and was already in the editor.
      onDrafted(view.draft.body);
    } catch (e) {
      setError(generateFailureMessage(isAxiosError(e) ? e.response?.status : undefined));
    } finally {
      setBusy(false);
    }
  }

  const said = basisSentence(basis, evidence.length);
  const hasDraft = !!result || hasStoredDraft;
  return (
    <section aria-label="AI 답글" className="flex flex-col gap-2" data-testid="grounded-review-draft">
      {/* Under the reply, not above it (§12-4): the reply is the thing; what it leaned on is one quiet line. */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        {evidence.length > 0 ? (
          <button
            type="button"
            onClick={() => setShowEvidence((open) => !open)}
            aria-expanded={showEvidence}
            className="rounded px-1 py-0.5 font-semibold text-ink underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            data-testid="grounded-review-why"
          >
            참고 {evidence.length} {showEvidence ? "▾" : "▸"}
          </button>
        ) : null}
        {said ? (
          <span className="break-keep text-muted" role="status" data-testid="grounded-review-basis">
            {said}
          </span>
        ) : null}
        <Btn
          size="sm"
          variant={hasDraft ? "ghost" : "solid"}
          onClick={() => void generate()}
          disabled={busy}
          data-testid="grounded-review-generate"
          className="ml-auto"
        >
          {busy ? "쓰는 중…" : hasDraft ? "다시 쓰기" : "AI 답글 쓰기"}
        </Btn>
      </div>

      {error ? <p className="break-keep text-sm text-bad" role="alert">{error}</p> : null}

      {/* An operational fact, kept apart from anything about the seller's knowledge — the budget, the
          capability, the vendor, or a promise the evidence did not support. */}
      {result?.unavailableMessage ? (
        <p className="break-keep text-sm text-warn" role="status">{result.unavailableMessage}</p>
      ) : null}

      {showEvidence ? (
        <ul className="flex flex-col gap-2 border-l-2 border-line pl-3" data-testid="grounded-review-evidence">
          {evidence.map((row, i) => (
            <li key={`${row.sourceId ?? row.kind}-${i}`} className="flex flex-col gap-0.5">
              <span className="text-sm font-semibold text-ink">
                {row.scopeLabel ?? row.kind}
                {row.title ? ` 「${row.title}」` : ""}
              </span>
              {/* The sentence the drafter was actually shown. A title is a pointer; this is the check. */}
              {row.snippet ? (
                <span className="break-keep text-sm text-muted">{row.snippet}</span>
              ) : (
                <span className="text-sm text-muted">지워진 자료라 내용을 볼 수 없어요.</span>
              )}
            </li>
          ))}
        </ul>
      ) : null}

      {/* 다음 답글이 쓸 안내를 고르는 자리 (2026-10-27). 매일 보는 칸에서는 접혀 있고 (§12-4 — 검색 결과를 고르는 일은
          받은 리뷰 화면의 일이 아니다), 참고한 내용이 틀렸다고 생각할 때 펼친다. */}
      <details className="text-sm" data-testid="grounded-review-guidance">
        <summary className="cursor-pointer select-none rounded px-1 py-0.5 text-muted hover:text-ink">
          참고할 안내 고르기
        </summary>
        <div className="pt-2">
          <ReviewGuidance accountId={accountId} actionRef={actionRef} />
        </div>
      </details>

      {gaps.length > 0 ? (
        <div className="flex flex-col gap-2 rounded-xl border border-line bg-canvas p-3" data-testid="grounded-review-gaps">
          <p className="break-keep text-sm font-semibold text-ink">이 내용이 있으면 더 정확히 답할 수 있어요.</p>
          {gaps.map((gap) => (
            <Gap key={`${gap.scope}-${gap.subject}`} gap={gap} onSaved={generate} />
          ))}
        </div>
      ) : null}
    </section>
  );
}

/**
 * One ask, with the way to answer it right there.
 *
 * <p>Both scopes open the same quick-add the inquiry screen uses, with the corpus fixed by what was
 * missing. Until Knowledge Setup &amp; Inbox UX v1 an ORG gap was a LINK to the settings screen —
 * the one gap the product could name and the one it made the seller leave the review for, write a
 * title and a body on another screen, come back, and regenerate by hand.
 */
function Gap({ gap, onSaved }: { gap: ReviewKnowledgeGapView; onSaved: () => void | Promise<void> }) {
  const scope = gap.scope === "PRODUCT" && gap.productId ? "PRODUCT" : "ORG";
  return (
    <div className="flex flex-col gap-1">
      <p className="break-keep text-sm text-ink">{gap.question}</p>
      <AnswerBasisQuickAdd
        scope={scope}
        productId={gap.productId}
        candidateId={gap.candidateId}
        onSaved={onSaved}
      />
    </div>
  );
}

/**
 * What the reply leaned on, in one sentence — or nothing, when 「참고 N」 beside it already says it. PAST_ANSWER
 * (2026-10-22): written from a reply the seller once approved for a review like this one, with no current evidence.
 * THANKS_ONLY (owner decision 2026-10-26): written by the model with no evidence, thanks and empathy only.
 */
function basisSentence(basis: string | null, cited: number): string | null {
  if (basis == null) return null;
  // Grounded with its passages listed: 「참고 N」 beside this line already says it. Grounded with none listed (an old
  // version, a deleted source) still says what it stood on.
  if (basis === "GROUNDED") return cited > 0 ? null : "가게 자료를 참고해 썼어요.";
  // What the server's note said and the chip did not: nothing factual came from the old reply.
  if (basis === "PAST_ANSWER") return "지난 답글 말투를 참고했어요. 사실이나 약속은 넣지 않았어요.";
  if (basis === "THANKS_ONLY") return "참고한 내용이 없어서 감사 인사만 썼어요.";
  return "참고한 내용이 없어서 기본 문구로 썼어요.";
}
