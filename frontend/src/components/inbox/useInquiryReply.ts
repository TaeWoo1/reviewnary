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
  REPLY_TOO_LONG,
  publishUnavailableReason,
} from "../../lib/inquiryPublish";
import type {
  DraftEvidenceView,
  InquiryDetail,
  PublishCapabilityView,
  PublishStatusView,
  GeneratedDraftView,
  ReplyDraftView,
  ReplyHold,
  SellerCheckOutcome,
  InquiryKindName,
  SellerCheckRecordResponse,
  SellerCheckState,
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

  // A hold the recheck put on this answer (2026-11-01): drawn above it with its reasons and what to do. Read with the
  // detail, and again after a refused 확정 or 재시도 — the press itself records a hold the sweep had not yet.
  const [hold, setHold] = useState<ReplyHold | null>(null);
  // Required questions a partial draft left open (2026-11-01 tenth review) — the send waits for each; the backend refuses too.
  const [openLeft, setOpenLeft] = useState(0);
  const loadHold = useCallback(async () => {
    if (workItemId === null) {
      setHold(null);
      return;
    }
    try {
      setHold(await api.getReplyHold("INQUIRY", workItemId));
    } catch {
      // Unreadable leaves the screen as it was; the server still refuses what it holds.
    }
  }, [workItemId]);
  const holdKey = `${detail?.draft?.version ?? ""}|${detail?.phase ?? ""}`;
  useEffect(() => {
    void loadHold();
  }, [loadHold, holdKey]);

  // The seller's record of what they found on the seller centre, and what they are asked to check (2026-11-02). Read
  // beside the detail; unreadable draws no panel, and the server still refuses a reply the record does not back.
  const [sellerCheck, setSellerCheck] = useState<SellerCheckState | null>(null);
  /** The record could not be read — said on screen with a way to read it again, never drawn as 「no record」. */
  const [sellerCheckLoadFailed, setSellerCheckLoadFailed] = useState(false);
  /** How the last record or redraft press went in this session — saved and drafted are two answers. */
  const [sellerDraftState, setSellerDraftState] = useState<"READY" | "NOT_WRITTEN" | "FAILED" | "KEPT" | null>(null);
  const [sellerDraftReason, setSellerDraftReason] = useState<string | null>(null);
  const [sellerCheckError, setSellerCheckError] = useState<string | null>(null);
  const loadSellerCheck = useCallback(async () => {
    if (workItemId === null) {
      setSellerCheck(null);
      return;
    }
    try {
      setSellerCheck(await api.getSellerCheck(workItemId));
      setSellerCheckLoadFailed(false);
    } catch {
      setSellerCheck(null);
      setSellerCheckLoadFailed(true);
    }
  }, [workItemId]);
  useEffect(() => {
    void loadSellerCheck();
  }, [loadSellerCheck]);

  /** The answer is held and not yet decided — it is neither sent nor copied out. */
  // Written before the seller corrected what kind of inquiry this is (2026-11-05): the server refuses to approve it, and
  // the screen does not hand it out by copy either.
  const kindStale = sellerCheck?.kind?.draftMatches === false;
  const copyBlocked = (hold != null && (hold.needs === "FIX" || !hold.confirmed)) || kindStale;

  /** A 409 that is a recheck hold: its reasons are above the answer, so the line beside the button points there. */
  async function heldBy(e: unknown): Promise<boolean> {
    const data = isAxiosError(e) ? (e.response?.data as { code?: string; message?: string } | undefined) : undefined;
    // A length refusal is not a hold: nothing needs re-checking, the answer only has to be shorter.
    if (data?.code === REPLY_TOO_LONG) return false;
    const message = isAxiosError(e) && e.response?.status === 409 ? (data?.message ?? "") : "";
    if (!message.includes("다시 확인") && !message.includes("확인하고 진행") && !message.includes("고친 뒤")) {
      return false;
    }
    await loadHold();
    setActionError("다시 확인이 필요해 보내지 않았어요. 위의 안내를 확인해 주세요.");
    return true;
  }

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
  // A decided reply is kept as it was approved (2026-11-03 sixth review): reopening it never offers a new version.
  const draftEditable =
    canEditDraft(publishStatus) && !(detail && ["APPROVED", "ACTION_PENDING", "EXECUTED", "COMPLETED"].includes(detail.phase));
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
        // The record the seller has in front of them now — the version names it (2026-11-03).
        sellerCheckId: sellerCheck?.check?.id ?? null,
        // And the kind correction it is shown under — a later save time is not having seen it (2026-11-05).
        kindCorrectionId: sellerCheck?.kind?.correctionId ?? null,
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
      if (sellerCheck?.check || sellerCheck?.kind?.correctionId) void loadSellerCheck();
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
      if (await heldBy(e)) return;
      const info = isAxiosError(e)
        ? classifyPublishError(e.response?.status, e.response?.data)
        : classifyPublishError(undefined);
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
      if (await heldBy(e)) return;
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
      applyGenerated(generated, phase);
      // A decided draft wrote the service's reading of what kind of inquiry this is (2026-11-05): read it with the record.
      void loadSellerCheck();
    } catch (e) {
      const info = classifyProposeError(isAxiosError(e) ? e.response?.status : undefined);
      setActionError(info.message);
      if (info.shouldRefresh) await load();
    } finally {
      setBusy(false);
    }
  }

  /** What one generate wrote — or why it wrote nothing — onto this screen. Shared by 「AI 답변 초안 만들기」 and the record. */
  function applyGenerated(generated: GeneratedDraftView, phase: string) {
    setSellerCheck((current) => ({
      check: current?.check ?? null,
      ask: generated.sellerCheckAsk ?? null,
      draftMatches: current?.draftMatches ?? null,
      answered: current?.answered ?? null,
      kind: current?.kind ?? null,
    }));
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
  }

  /**
   * 「기록하고 답변 만들기」 — the record is kept first, then a draft is written from it. Returns whether the record was
   * kept. A record changed on another device meanwhile is refused (409) and the record that stands is read again.
   */
  async function onRecordSellerCheck(outcome: SellerCheckOutcome, note: string): Promise<boolean> {
    if (!detail || workItemId === null || busy) return false;
    setBusy(true);
    setSellerCheckError(null);
    setActionError(null);
    try {
      let phase = detail.phase;
      if (canGenerateProposal(phase)) {
        const proposed = await api.generateInquiryProposal(workItemId);
        phase = proposed.phase;
        setDetail((current) =>
          current ? { ...current, phase: proposed.phase, proposal: proposed.proposal } : current,
        );
      }
      const result = await api.recordSellerCheck(workItemId, {
        outcome,
        note,
        replaces: sellerCheck?.check?.id ?? null,
      });
      await settleSellerCheck(result, phase);
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /** 「이 기록으로 답변 다시 만들기」 — from the record on screen; writes no record. */
  async function onRedraftSellerCheck() {
    const checkId = sellerCheck?.check?.id;
    if (!detail || workItemId === null || !checkId || busy) return;
    setBusy(true);
    setSellerCheckError(null);
    setActionError(null);
    try {
      await settleSellerCheck(await api.redraftFromSellerCheck(workItemId, checkId), detail.phase);
    } catch (e) {
      await refusedSellerCheck(e);
    } finally {
      setBusy(false);
    }
  }

  /**
   * 「이 날로 정하기」 — the seller's next look at an action still in progress. The record stays the record (no new version,
   * nothing held, nothing sent); only its day moves. Returns whether it moved.
   */
  async function onSetNextCheck(nextCheckOn: string): Promise<boolean> {
    const checkId = sellerCheck?.check?.id;
    if (workItemId === null || !checkId || busy) return false;
    setBusy(true);
    setSellerCheckError(null);
    try {
      const moved = await api.setSellerCheckNextCheck(workItemId, checkId, nextCheckOn);
      setSellerCheck((current) => (current ? { ...current, check: moved } : current));
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /**
   * 「이 판단으로 답변 다시 만들기」 (2026-11-05 seventh review) — the seller says what kind of inquiry this is, and the reply
   * is drafted again under it. The correction is kept even when drafting fails. Returns whether it was kept.
   */
  async function onCorrectKind(kind: InquiryKindName): Promise<boolean> {
    if (!detail || workItemId === null || busy) return false;
    setBusy(true);
    setSellerCheckError(null);
    setActionError(null);
    try {
      let phase = detail.phase;
      if (canGenerateProposal(phase)) {
        const proposed = await api.generateInquiryProposal(workItemId);
        phase = proposed.phase;
        setDetail((current) =>
          current ? { ...current, phase: proposed.phase, proposal: proposed.proposal } : current,
        );
      }
      const result = await api.correctInquiryKind(workItemId, {
        kind,
        replaces: sellerCheck?.kind?.correctionId ?? null,
      });
      if (result.draft) applyGenerated(result.draft, phase);
      setSellerCheck((current) => ({
        check: current?.check ?? null,
        ask: result.ask ?? null,
        // A record on screen is matched by the new draft only when one was written from it.
        draftMatches: current?.check ? result.draftState === "READY" : (current?.draftMatches ?? null),
        answered: current?.answered ?? null,
        kind: result.kind,
      }));
      setHold(result.hold);
      if (result.draftState === "FAILED") {
        setSellerCheckError("판단은 고쳤어요. 답변은 아직 준비하지 못했어요. 「AI 답변 초안 만들기」로 다시 만들어 주세요.");
        await load();
      }
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /** Set once an approval was taken back in this session: the new answer waits for the seller's own approval. */
  const [reopened, setReopened] = useState(false);

  /**
   * 「승인 거두고 이 기록으로 새 답변 만들기」 (2026-11-05 seventh review) — an approved answer the record has outrun, not
   * on the channel, and the seller confirmed they did not post it by hand. The approval is kept on the server as taken
   * back; the reply returns to the seller and is drafted from the record on screen. Nothing is sent.
   */
  async function onReopenSellerCheck(): Promise<boolean> {
    const checkId = sellerCheck?.check?.id;
    const approvedFingerprint = sellerCheck?.answered?.fingerprint;
    if (!detail || workItemId === null || !checkId || !approvedFingerprint || busy) return false;
    setBusy(true);
    setSellerCheckError(null);
    setActionError(null);
    try {
      const result = await api.reopenFromSellerCheck(workItemId, { checkId, approvedFingerprint, notRegistered: true });
      setReopened(true);
      setPublishStatus(null);
      setConfirming(false);
      setDetail((current) => (current ? { ...current, phase: "PROPOSED" } : current));
      await settleSellerCheck(result, "PROPOSED");
      setSellerCheck((current) => (current ? { ...current, answered: null } : current));
      // The detail (phase, delivery, the version now at the head) is the server's: read it again.
      await load();
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /**
   * 「승인 거두고 고치기」 (2026-11-05 tenth review) — an approved answer held for something a person must fix (a policy
   * conflict, a forbidden phrase, a changed figure), not on the channel, and the seller confirmed they did not post it by
   * hand. Nothing is drafted and nothing is resolved: the answer is the seller's again with its reason, and approving it
   * again runs every check.
   */
  async function onWithdrawHeld(): Promise<boolean> {
    const approvedFingerprint = sellerCheck?.answered?.fingerprint;
    const holdCheckId = sellerCheck?.answered?.holdCheckId;
    if (!detail || workItemId === null || !approvedFingerprint || !holdCheckId || busy) return false;
    setBusy(true);
    setSellerCheckError(null);
    setActionError(null);
    try {
      const result = await api.withdrawHeldApproval(workItemId, { approvedFingerprint, holdCheckId, notRegistered: true });
      setReopened(true);
      setPublishStatus(null);
      setConfirming(false);
      setDetail((current) => (current ? { ...current, phase: result.phase ?? "PROPOSED" } : current));
      setHold(result.hold ?? null);
      setSellerCheck((current) => (current ? { ...current, answered: null } : current));
      await load();
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /** 「따로 안내함」 · 「안내 필요 없음」 — closes 「완료됐지만 고객에게는 … 안내했어요」 for the record on screen. Sends nothing. */
  async function onCloseToldBehind(closure: "TOLD_SEPARATELY" | "NOT_NEEDED"): Promise<boolean> {
    const checkId = sellerCheck?.check?.id;
    if (workItemId === null || !checkId) return false;
    setSellerCheckError(null);
    try {
      const closed = await api.closeToldBehind(workItemId, { checkId, closure });
      setSellerCheck((current) =>
        current?.answered
          ? { ...current, answered: { ...current.answered, closure: closed.closure, closedAt: closed.closedAt } }
          : current,
      );
      return true;
    } catch (e) {
      await refusedSellerCheck(e);
      return false;
    }
  }

  async function settleSellerCheck(
    result: SellerCheckRecordResponse,
    phase: string,
  ) {
    setSellerDraftState(result.draftState);
    // Why nothing was written, when the machinery says why. Never the knowledge-library sentence (「기준을 등록하면…」):
    // that is the errand this flow replaced (FINDINGS §B95, 2026-11-03 real-screen check).
    setSellerDraftReason(result.draft?.unavailableMessage ?? null);
    if (result.draft) {
      applyGenerated(result.draft, phase);
      // Nothing new was written: the version on screen is the earlier one, shown as text under its hold — not opened
      // for editing as if it were the answer to this record (2026-11-03 real-screen check).
      if (!result.draft.draft && detail?.draft) setEditing(false);
    }
    setSellerCheck((current) => ({
      check: result.check,
      ask: result.draft ? (result.draft.sellerCheckAsk ?? null) : (current?.ask ?? null),
      draftMatches: result.draftState === "READY",
      answered: current?.answered ?? null,
      kind: current?.kind ?? null,
    }));
    setSellerCheckLoadFailed(false);
    // Approved already: which record the customer's answer was written from, against the record just kept, is the
    // server's to say (2026-11-05) — read it again rather than keep what was on screen before the record changed.
    if (result.draftState === "KEPT") void loadSellerCheck();
    // The answer waiting below is checked against the record now: an older draft written from another record is held,
    // and the hold says so — it is never shown as the new result.
    setHold(result.hold);
    if (result.draftState === "FAILED") {
      // Nothing came back to draw: read the answer that waits now, which the hold above speaks for.
      await load();
    }
  }

  async function refusedSellerCheck(e: unknown) {
    const status = isAxiosError(e) ? e.response?.status : undefined;
    const message = isAxiosError(e) ? (e.response?.data as { message?: string } | undefined)?.message : undefined;
    if (status === 409 || status === 400) {
      setSellerCheckError(message ?? "기록하지 못했어요. 다시 시도해 주세요.");
      if (status === 409) await loadSellerCheck();
      return;
    }
    setSellerCheckError("기록하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.");
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
    // A held answer is not copied out either (2026-11-03 real-screen check): pasting it into the seller centre by hand
    // would send exactly what the hold stopped.
    if (copyBlocked) {
      setActionError("다시 확인이 필요한 답변이라 복사하지 않았어요. 위의 안내를 먼저 확인해 주세요.");
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
    setActionError("복사하지 못했어요. 다시 시도해 주세요.");
  }


  return {
    workItemId,
    openLeft,
    setOpenLeft,
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
    hold,
    setHold,
    copyBlocked,
    sellerCheck,
    sellerCheckLoadFailed,
    loadSellerCheck,
    sellerDraftState,
    sellerDraftReason,
    sellerCheckError,
    onRecordSellerCheck,
    onRedraftSellerCheck,
    onSetNextCheck,
    onReopenSellerCheck,
    onWithdrawHeld,
    onCloseToldBehind,
    reopened,
    onCorrectKind,
    kindStale,
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
