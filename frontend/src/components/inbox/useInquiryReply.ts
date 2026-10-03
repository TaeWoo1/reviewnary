import { useCallback, useEffect, useState } from "react";
import { isAxiosError } from "axios";
import { api } from "../../lib/apiClient";
import {
  canGenerateProposal,
  classifyProposeError,
  detailErrorMessage,
} from "../../lib/inquiryWorkflow";
import {
  canEditDraft,
  answeredElsewhere,
  canPublishReply,
  classifyPublishError,
  publishUnavailableReason,
} from "../../lib/inquiryPublish";
import type {
  DraftEvidenceView,
  InquiryDetail,
  PublishCapabilityView,
  PublishStatusView,
  ReplyDraftView,
} from "../../lib/types";
import { answerStateOf, type AnswerStateView } from "../../lib/answerState";
import { copyText } from "../../lib/clipboard";

/**
 * <b>한 문의의 답변 작업 전체를 쥔 훅</b> — 읽기 두 번, 쓰기 네 번, 그리고 그 사이의 모든 상태.
 *
 * <p><b>왜 컴포넌트에서 나왔나</b> (문의 canonical, 2026-10-03). 이 상태는 {@code InquiryResponsePanel}
 * 안에 있었고, 그래서 판매자가 누를 <b>단 하나의 버튼</b>도 그 안에만 있을 수 있었다. canonical은 그 버튼을
 * pane 바닥에 고정한다 — 초안이 길어도 눌러야 할 것이 스크롤 밖으로 사라지지 않게. 바닥은 레이아웃의
 * 자리이고 상태는 패널의 것이었으니, 둘 다 읽을 수 있는 곳으로 상태를 올렸다. 반복 문제가 같은 이유로
 * 같은 일을 했다({@code useRepeatedIssue}).
 *
 * <p><b>null을 받는다.</b> 답변 대기 목록에 없는 문의 — 이미 답변됐거나, 리뷰 행 — 에서는 작업 자체가
 * 없다. 훅은 조건부로 부를 수 없으므로 없음을 값으로 받고, 아무것도 읽지 않는다.
 *
 * <p>행동은 하나도 바뀌지 않았다. 읽기·쓰기·실패 처리·fail-closed 규칙은 전부 옮겨오기만 했고, 이 파일에
 * 새 호출은 없다.
 */
export function useInquiryReply(workItemId: string | null) {
  const [detail, setDetail] = useState<InquiryDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  /** `null` until the capability read lands — and `null` means "do not offer the send". */
  const [capability, setCapability] = useState<PublishCapabilityView | null>(null);
  const [publishStatus, setPublishStatus] = useState<PublishStatusView | null>(null);
  const [replyTitle, setReplyTitle] = useState("");
  const [replyComments, setReplyComments] = useState("");
  /** The second-press gate. Opening the confirm block is not sending; the button inside it is. */
  const [confirming, setConfirming] = useState(false);
  /**
   * The 초안 복사 result, and the fallback when there is no clipboard to copy with.
   *
   * `manualCopy` holds the SAVED text to reveal on a non-secure origin, because `copyText` cannot
   * pretend on one — the same rule the review lane follows. Claiming a copy that did not happen is
   * how a seller pastes an empty clipboard into a customer's inquiry and never learns why.
   */
  const [copied, setCopied] = useState(false);
  const [manualCopy, setManualCopy] = useState<string | null>(null);
  /** The draft reads as text until the seller chooses to edit; an always-open textarea invites typing. */
  const [editing, setEditing] = useState(false);
  /** What the CURRENT draft was grounded in. Refreshed on every generate; cleared by a seller edit. */
  const [evidence, setEvidence] = useState<DraftEvidenceView[]>([]);
  /** The one sentence above the draft: what the knowledge library could and could not offer. */
  const [knowledgeNote, setKnowledgeNote] = useState<string | null>(null);
  /**
   * Whether THIS generate read the registered 회사 정보 as wording context (Seller Context v1-B). Not
   * stored on the version, so a reload does not claim it; a flag, never the text; never a citation.
   */
  const [companyContextUsed, setCompanyContextUsed] = useState(false);
  /**
   * Whether that sentence is the good-news one.
   *
   * Held as a boolean rather than read off the draft row, because the two places it comes from — a
   * reload and a generate — carry it in different shapes, and a sentence rendered as a caution when
   * it says the question COULD be answered is the defect this replaces.
   */
  const [knowledgeGrounded, setKnowledgeGrounded] = useState(false);
  /**
   * Set when the MACHINERY is why there is no draft — budget spent, capability off, vendor silent,
   * or a 상세페이지 read that failed. Never a statement about the seller's knowledge, and it wins
   * over the no-basis card when both could apply: not having finished looking is not the same as
   * having looked and found nothing.
   */
  const [unavailable, setUnavailable] = useState<string | null>(null);
  /**
   * Why no draft was written, when none was.
   *
   * Held separately from `knowledgeNote` because they answer different questions: the note says what
   * the library could offer, and this says what the seller should do next. A generate that produces
   * nothing must not look like a generate that failed.
   */
  const [answerState, setAnswerState] = useState<AnswerStateView | null>(null);
  /**
   * Set for one render pass after the seller saves an answer basis from this screen.
   *
   * The knowledge round trip used to end in silence: the box closed, a draft was regenerated, and
   * the seller was left asking whether their sentence had been saved at all — especially when the
   * regenerated draft landed on the SAME state, which is the ordinary outcome of adding knowledge
   * that does not happen to cover this question. It says what was saved and what was re-run, and
   * claims nothing about the result: the card underneath is where the result is.
   */
  const [basisSaved, setBasisSaved] = useState(false);
  /** Open only while the seller is choosing a product. Never open by default — it is not a step. */
  const [binding, setBinding] = useState(false);

  const load = useCallback(async () => {
    // 작업이 없는 문의. 읽지 않고, 실패도 아니다 — 그릴 것이 없다는 사실 그대로.
    if (workItemId === null) {
      setDetail(null);
      setError(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const next = await api.getInquiryDetailStrict(workItemId);
      setDetail(next);
      // Seed the editor from the saved draft when there is one. A seller who saved a draft yesterday
      // must come back to it, not to an empty box that would overwrite it on the next save.
      setReplyTitle(next.draft?.title ?? (next.title ? `[답변] ${next.title}` : ""));
      setReplyComments(next.draft?.comments ?? "");
      setEvidence(next.draftEvidence ?? []);
      setKnowledgeNote(next.draft?.knowledgeNote ?? null);
      setCompanyContextUsed(false);
      setKnowledgeGrounded(next.draft?.knowledgeState === "GROUNDED");
      // The state is READ, never re-derived (Agent Command Center v1 §2). It was computed on the
      // generate and stored on the version, because `knowledgeState` alone cannot tell a grounded
      // answer from a clarification question — both are GROUNDED there. A version written before the
      // column carries null, and null claims nothing: no card, exactly as before.
      setAnswerState(storedAnswerState(next.draft ?? null, next.productId ?? null));
      setBasisSaved(false);
      setUnavailable(null);
    } catch (e) {
      setDetail(null);
      setError(detailErrorMessage(isAxiosError(e) ? e.response?.status : undefined));
    } finally {
      setLoading(false);
    }
  }, [workItemId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    // Read once per panel. A failure leaves `capability` null, which `canPublishReply` treats as "no"
    // — offering a marketplace write on a guess is the one thing this surface must never do.
    if (workItemId === null) return;
    let live = true;
    void api
      .getInquiryPublishCapability()
      .then((c) => {
        if (live) setCapability(c);
      })
      .catch(() => {
        if (live) setCapability(null);
      });
    return () => {
      live = false;
    };
  }, [workItemId]);

  const draft = detail?.draft ?? null;
  const publishable = detail ? canPublishReply(detail, capability) : false;
  const unavailableReason = detail ? publishUnavailableReason(detail, capability) : null;
  const draftDirty = !!draft && (draft.title !== replyTitle || draft.comments !== replyComments);
  const draftEditable = canEditDraft(publishStatus);
  /**
   * What a PREVIOUS session's send left behind, when this one has not sent anything.
   *
   * `publishStatus` is this session's memory of the press and is richer, so it wins when present;
   * this is the same facts read back from the execution and verification rows, which is the only way
   * the outcome survives a reload.
   */
  const priorDelivery = publishStatus ? null : detail?.delivery ?? null;
  /**
   * Whether a draft can be written at all. OPEN items are proposed on the way (see
   * {@link onGenerateDraft}); anything past PROPOSED is already bound into the reply lifecycle and a
   * new version would be a draft nobody can send.
   */
  const canDraft =
    !!detail
    // Already answered on the channel — a new draft version here would be a reply nobody needs,
    // written for a customer who is no longer waiting.
    && !answeredElsewhere(detail)
    && (canGenerateProposal(detail.phase) || detail.phase === "PROPOSED");

  async function onSaveDraft() {
    if (!detail || workItemId === null) return;
    setBusy(true);
    setActionError(null);
    try {
      const saved = await api.saveInquiryReplyDraft(workItemId, {
        title: replyTitle,
        comments: replyComments,
        // The version being edited FROM — `0` for the first save. A stale base is the seller's own
        // draft having moved (another tab, another device), which is a 409 rather than a silent
        // overwrite of whichever version they did not see.
        baseVersion: draft?.version ?? 0,
      });
      setDetail((current) => (current ? { ...current, draft: saved } : current));
      setReplyTitle(saved.title);
      setReplyComments(saved.comments);
      setEditing(false);
      // The seller rewrote it, so it is no longer the model's sentence and no longer stands on the
      // model's evidence. Keeping the citations here would attribute the seller's own words to a
      // knowledge passage that may no longer support them.
      setEvidence([]);
      setKnowledgeNote(null);
      // The sentences are the seller's now. A state card that said "답변에 필요한 정보를 확인했습니다"
      // over text the model never saw would attribute their words to our evidence.
      setAnswerState(null);
      setBasisSaved(false);
      // A saved draft is a NEW version with a new fingerprint, so any confirm block that was open is
      // now about content that no longer exists. Close it rather than let a stale approval be pressed.
      setConfirming(false);
    } catch (e) {
      const info = classifyPublishError(isAxiosError(e) ? e.response?.status : undefined);
      setActionError(info.message);
      if (info.shouldRefresh) await load();
    } finally {
      setBusy(false);
    }
  }

  /**
   * **The one marketplace WRITE in this product.**
   *
   * `commandId` is minted HERE, once per press, so a network timeout followed by a retry cannot
   * become a second reply — the backend treats a repeat of the same id as the same confirm.
   * `expectedFingerprint` is the exact draft version shown above the button; a mismatch is a 409, and
   * the seller is sent back to re-read rather than having an approval applied to content they never saw.
   */
  async function onConfirmPublish() {
    if (!detail || !draft || workItemId === null) return;
    setBusy(true);
    setActionError(null);
    try {
      const status = await api.confirmInquiryPublish(workItemId, {
        commandId: crypto.randomUUID(),
        expectedFingerprint: draft.contentFingerprint,
      });
      setPublishStatus(status);
      setConfirming(false);
      setDetail((current) => (current ? { ...current, phase: status.phase } : current));
    } catch (e) {
      const info = classifyPublishError(isAxiosError(e) ? e.response?.status : undefined);
      setActionError(info.message);
      if (info.shouldRefresh) await load();
    } finally {
      setBusy(false);
    }
  }

  /** Verify-only. It re-queries the channel's own status and NEVER resends — that is the whole point. */
  async function onVerifyPublish() {
    if (workItemId === null) return;
    setBusy(true);
    setActionError(null);
    try {
      setPublishStatus(await api.verifyInquiryPublish(workItemId));
    } catch (e) {
      setActionError(classifyPublishError(isAxiosError(e) ? e.response?.status : undefined).message);
    } finally {
      setBusy(false);
    }
  }

  /** Resume a bound-but-undelivered publish. Dispatches only from ACTION_PENDING; never resends. */
  async function onResumePublish() {
    if (workItemId === null) return;
    setBusy(true);
    setActionError(null);
    try {
      setPublishStatus(await api.resumeInquiryPublish(workItemId));
    } catch (e) {
      setActionError(classifyPublishError(isAxiosError(e) ? e.response?.status : undefined).message);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Make an AI reply draft.
   *
   * <p>Two calls, in one press. The composer requires a PROPOSED work item — that transition is what
   * freezes the item into the reply lifecycle — so an OPEN item is proposed first. The seller asked
   * for a draft, not for a state machine, and making them press twice for two backend preconditions
   * would be the product leaking its own sequencing.
   */
  async function onGenerateDraft() {
    if (!detail || workItemId === null) return;
    setBusy(true);
    setActionError(null);
    setUnavailable(null);
    setBasisSaved(false);
    try {
      let phase = detail.phase;
      if (canGenerateProposal(phase)) {
        const proposed = await api.generateInquiryProposal(workItemId);
        phase = proposed.phase;
        setDetail((current) =>
          current ? { ...current, phase: proposed.phase, proposal: proposed.proposal } : current,
        );
      }
      const generated = await api.generateInquiryDraft(workItemId);
      setEvidence(generated.evidence);
      setKnowledgeNote(generated.knowledgeNote);
      setCompanyContextUsed(generated.companyContextUsed === true);
      setKnowledgeGrounded(generated.knowledgeState === "GROUNDED");
      setUnavailable(generated.unavailableMessage);
      // Which of the three states this generate landed in — computed once and applied whether or not
      // something was written. A draft can exist WITH no answer basis: the company's own pre-approved
      // sentence for 「확인 후 안내드리겠습니다」 (AI 답변 스타일). That is a deferral, not an answer,
      // so the seller must still see what is missing and still be able to add it.
      const state = answerStateOf(generated);
      if (!generated.draft) {
        // Nothing was composed, on purpose. Leave whatever the seller had typed exactly as it is —
        // clearing their box because the AI declined would be the worst of both behaviours — and
        // say which basis is missing so the sentence is actionable rather than an apology.
        setAnswerState(state);
        setEditing(true);
        return;
      }
      setAnswerState(state);
      setUnavailable(null);
      const written = generated.draft;
      setDetail((current) => (current ? { ...current, draft: written, phase } : current));
      setReplyTitle(written.title);
      setReplyComments(written.comments);
      setEditing(false);
      // A new version has a new fingerprint, so an open confirm is about content that no longer
      // exists. Close it rather than let a stale approval be pressed.
      setConfirming(false);
    } catch (e) {
      const info = classifyProposeError(isAxiosError(e) ? e.response?.status : undefined);
      setActionError(info.message);
      if (info.shouldRefresh) await load();
    } finally {
      setBusy(false);
    }
  }

  /**
   * The seller wrote the missing answer basis, here, and the screen re-asks with it.
   *
   * Saving is not answering, so this says only what it did. Whether the new sentence covers THIS
   * question is the regenerated state card's answer, and it is perfectly ordinary for it to still be
   * 「답변 기준이 필요합니다」 — knowledge that does not apply is not a failure to save.
   */
  async function onBasisSaved() {
    await onGenerateDraft();
    setBasisSaved(true);
  }

  /**
   * Copy the SAVED draft, never the editor buffer.
   *
   * The screen already told the seller to 「아래 초안을 복사해 판매자센터에서 등록해 주세요」 while
   * offering no way to do it (Demo UX Polish v1) — an instruction pointing at a control that was not
   * there. What it copies is `detail.draft`, the version the server holds, and the button is not
   * offered while the editor is open or dirty: the text on screen would then be one the seller has
   * not saved, and pasting an unsaved keystroke into a public reply is the exact failure the reply
   * lifecycle is built to prevent.
   */
  async function onCopyDraft() {
    if (!draft || draftDirty || editing) {
      return;
    }
    const text = [draft.title, draft.comments].filter(Boolean).join("\n\n");
    setActionError(null);
    const result = await copyText(text);
    if (result.ok) {
      setCopied(true);
      setManualCopy(null);
      return;
    }
    setCopied(false);
    if (result.reason === "UNAVAILABLE") {
      setManualCopy(text);
      return;
    }
    setActionError("복사하지 못했습니다. 다시 시도해 주세요.");
  }


  return {
    workItemId,
    detail,
    setDetail,
    loading,
    error,
    busy,
    actionError,
    capability,
    publishStatus,
    replyTitle,
    setReplyTitle,
    replyComments,
    setReplyComments,
    confirming,
    setConfirming,
    copied,
    manualCopy,
    editing,
    setEditing,
    evidence,
    knowledgeNote,
    companyContextUsed,
    knowledgeGrounded,
    unavailable,
    answerState,
    basisSaved,
    binding,
    setBinding,
    load,
    draft,
    publishable,
    unavailableReason,
    draftDirty,
    draftEditable,
    priorDelivery,
    canDraft,
    onSaveDraft,
    onConfirmPublish,
    onVerifyPublish,
    onResumePublish,
    onGenerateDraft,
    onBasisSaved,
    onCopyDraft,
  };
}

/** 이 화면의 작업대. {@link useInquiryReply}가 돌려주는 것 그대로. */
export type InquiryReplyWorkspace = ReturnType<typeof useInquiryReply>;

/**
 * The state a SAVED version was written in, or null when the row does not record one.
 *
 * <b>Read, not re-derived.</b> Re-running the projection on a reload would need the customer's
 * message and the 규격 verdict, neither of which the row holds; guessing GROUNDED for a draft that
 * was a question is precisely the confident wrong answer this card exists to prevent. A version from
 * before the column simply produces no card — the same screen the seller saw yesterday.
 *
 * The product id comes from the DETAIL, not from the draft: a saved version records which product
 * the retrieval was scoped to, but the errand to add knowledge belongs to the product this inquiry
 * is bound to now.
 */
function storedAnswerState(
  draft: ReplyDraftView | null,
  productId: string | null,
): AnswerStateView | null {
  if (!draft?.answerBasis || !draft.answerBasisNote) return null;
  return {
    basis: draft.answerBasis,
    note: draft.answerBasisNote,
    action: draft.answerBasisAction,
    productId,
    // The stored row records WHAT was decided, not what the customer's words named. So a reload can
    // still offer the product corpus — the row knows the product — and cannot claim an operating
    // topic, because that fact was never written down. Guessing one here would file a shipping rule
    // under a heading nobody chose.
    gapScope: productId ? "PRODUCT" : null,
    topic: null,
    // A reload knows what was decided, not which inbox row a past run created. Guessing one here
    // would let a reload close an ask this screen never established.
    candidateId: null,
    // Same reason: whether this exact ask was already answered is a fact about the inbox, decided at
    // draft time. The stored row does not carry it, so a reload does not claim it.
    previouslyAnswered: false,
  };
}
