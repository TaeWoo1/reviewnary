import { useCallback, useEffect, useId, useRef, useState } from "react";
import { PhraseFindings, phraseBlockSentence } from "./reviews/PhraseFindings";
import { GroundedReviewDraft } from "./GroundedReviewDraft";
import { DelegatedReplyControls } from "./reviews/DelegatedReplyControls";
import { Disclosure } from "./ui/Disclosure";
import { api } from "../lib/apiClient";
import { copyText } from "../lib/clipboard";
import { SecureRandomUnavailableError, newCommandId } from "../lib/commandId";
import type { OperatorOutcomeName, ReviewReplyPrep, TriageDisposition } from "../lib/types";
import {
  startReplySubmission,
  type ReplyBlockerCode,
  type ReplyRunHandle,
  type ReplyRuntime,
} from "../lib/actionWindow/reply/replyRuntime";
import { guidedStopSentence, guidedUnavailableSentence } from "../lib/actionWindow/reply/guidedStopWording";
import { useReplyRuntime } from "../lib/actionWindow/reply/useReplyRuntime";
import { plainText } from "../lib/plainText";

// NO module-level runtime. The hook resolves null in any shipped build without a bridge, and that
// null is the point: before it, this panel fell back to the SIMULATED runtime everywhere, minting a
// `run_<hex>` locally and persisting it as an Action Window run that never happened.

// Prepare a reply to one review: read the redacted body, start from a rule-based
// suggestion, edit, approve (which freezes it), copy.
//
// It stops at the clipboard. There is no send here and no marketplace call behind any of
// it — the operator pastes the text into the seller center themselves, and the copy says
// so. No control on this panel may read as 발송/전송/등록 (Frontend Spec §10.2).
//
// Every affordance is rendered from the SERVER's `capabilities`, never re-derived here.
// The rule depends on the disposition AND whether a draft exists AND whether an approval
// stands; a second copy of it in the client is how the two surfaces drift into disagreeing
// about what is allowed.

/** One approval intent: the state asked for, and the command id identifying it. */
interface ApprovalAttempt {
  state: "APPROVED" | "WITHDRAWN";
  commandId: string;
}

/** How long the editor waits after the last keystroke before it saves itself (mockup ①, 2026-10-25). */
export const AUTOSAVE_AFTER_MS = 1500;

/** 「10:08」 — when a version was written, for the status line. */
function clockOf(iso: string | null | undefined): string {
  if (!iso) return "";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });
}

/** What the panel is currently doing, so only one write is ever in flight. */
type Busy = null | "saving" | "approving" | "withdrawing" | "starting" | "reporting";

/** One guided reply-submission run: the single-use binding + the live run handle it reports through. */
interface GuidedRun {
  submissionRef: string;
  /**
   * The live run this report will terminate through, or null for a MANUAL handoff.
   *
   * <p>Null is not a missing handle — it says no Action Window run exists, so the outcome is the
   * operator's own report and no run ref is recorded. It is the difference between "SellerOps
   * watched a run end" and "the seller told us what they did".
   */
  handle: ReplyRunHandle | null;
}
/** One outcome-report intent: what was reported, and the command id identifying it. */
interface OutcomeAttempt {
  outcome: OperatorOutcomeName;
  commandId: string;
}

/**
 * What the panel says about who wrote the draft sitting in the editor, or null to say nothing.
 *
 * Four author states reach this screen and they are four different reports:
 *
 * - `MODEL` — a model wrote it from the company's own stored knowledge, so it needs reading.
 * - `SELLER` — a person typed or edited it. **No "확인하고 고쳐 주세요" follows**: telling someone to
 *   check a sentence they just wrote is the screen not knowing who it is talking to.
 * - `RULE` — the stored template floor produced it.
 * - `null` — nobody recorded an author. **This says nothing at all.**
 *
 * The null branch is the one worth stating. Until the hand-typed path began stamping SELLER, every
 * author but MODEL fell into one `else` that spoke the template sentence — so a version a person
 * wrote, and a version written before reviewnary recorded authors at all, were both announced as
 * "저장된 문구에서 시작합니다". That is a claim about authorship no row supports, and an empty editor
 * (no stored draft, so no author) got it too. Silence is the honest report for an unrecorded author,
 * and `ReviewReplyPrepView.draftAuthorKind` says the same thing on its own side of the wire.
 */
export function draftProvenanceNote(authorKind: string | null, approved: boolean): string | null {
  void approved;
  // <b>누가 썼는지가 아니라 무엇에 근거했는지</b> (리뷰 canonical mockup, 2026-10-06 — 승인된 copy).
  // 판매자가 이 줄에서 확인할 것은 이 문장이 회사가 적어 둔 것 위에 서 있는가이지, 기계가 썼는가가 아니다.
  // `draftAuthorKind`는 그대로 MODEL이고, 세 갈래가 네 갈래로 늘지도 줄지도 않았다 — 문구 하나만 바뀐다.
  // MODEL no longer means 「저장된 지식 위에」: since 2026-10-26 the model also writes with no evidence (thanks and
  // empathy only), and a past answer was never 「지식」 either. What it stood on is the basis chip's to say, once.
  if (authorKind === "MODEL") return "AI가 썼어요.";
  if (authorKind === "SELLER") return "직접 쓴 답글이에요.";
  if (authorKind === "RULE") return "저장된 문구로 시작했어요.";
  return null;
}

/**
 * The one line beside 「답변 초안」 that says where the draft stands (mockup ①, 2026-10-25): being saved, not saved,
 * changed on the phone, something to fix, or checked. The service saves and checks; the seller only reads this.
 */
function DraftStatus({
  saving,
  saveFailed,
  onRetry,
  hasDraft,
  fromPhoneAt,
  toFix,
}: {
  saving: boolean;
  saveFailed: boolean;
  onRetry: () => void;
  hasDraft: boolean;
  fromPhoneAt: string | null;
  toFix: number;
}) {
  const dot = (tone: string) => <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${tone}`} />;
  let content: React.ReactNode = null;
  if (saveFailed) {
    content = (
      <span className="inline-flex items-center gap-1.5 text-bad">
        {dot("bg-bad")}저장하지 못했어요{" "}
        <button type="button" onClick={onRetry} className="underline underline-offset-2">
          다시 시도
        </button>
      </span>
    );
  } else if (saving) {
    content = <span className="inline-flex items-center gap-1.5 text-muted">{dot("bg-muted")}저장 중…</span>;
  } else if (!hasDraft) {
    content = null;
  } else if (toFix > 0) {
    content = <span className="inline-flex items-center gap-1.5 text-bad">{dot("bg-bad")}고칠 곳 {toFix}</span>;
  } else if (fromPhoneAt != null) {
    content = (
      <span className="inline-flex items-center gap-1.5 text-warn">
        {dot("bg-warn")}휴대폰에서 고침{clockOf(fromPhoneAt) ? ` · ${clockOf(fromPhoneAt)}` : ""}
      </span>
    );
  } else {
    // Checked and nothing to fix: said by saying nothing (§9). 「검사 통과」 was a green word on every reply.
    content = null;
  }
  return (
    <span aria-live="polite" className="text-xs font-semibold" data-testid="draft-status">
      {content}
    </span>
  );
}

export function VocItemReplyPrep({
  accountId,
  actionRef,
  disposition,
  onPrepared,
  onOutcomeRecorded,
  onLocalWork,
  replyRuntime,
  headingLevel = 4,
  subjectShownAbove = false,
  unboxed = false,
  autoDraft = false,
}: {
  accountId: string;
  actionRef: string;
  /**
   * The heading level 「답변 준비」 renders at.
   *
   * The panel is mounted inside three different outlines — a worklist row, the record's 상세 pane, and
   * the 답변 작업 screen where it is the page's only section. A fixed `h4` was right in the first two
   * and skipped h2/h3 in the third, which is a real reading-order defect (axe: heading-order), not a
   * style preference. The level is the caller's to state because only the caller knows the outline.
   */
  headingLevel?: 2 | 3 | 4;
  /**
   * The caller already prints the customer's words — fold them here instead of printing them twice
   * (Review Decision UX v3.2).
   *
   * <p>On the Review Case the review IS the page's headline, and this block repeated it 680px below,
   * costing the one primary action ~64px of the fold it then sat outside. Folded, not dropped: the
   * whole redacted body is one press away and the redaction notice travels with it, because 「the
   * operator cannot answer a complaint they can only glimpse」 is still true — it is only that they
   * are already looking at it.
   *
   * <p><b>And when nothing was redacted, not even the fold.</b> Then this panel's copy is the caller's
   * copy, character for character, and a disclosure whose contents are the sentence six inches above it
   * is a control that cannot pay for the line it takes. The fold appears exactly when the two texts
   * differ — which is the only time there is something behind it to see.
   */
  subjectShownAbove?: boolean;
  /**
   * Drop this panel's own card — the caller already put it in one (Review Decision UX v3.2).
   *
   * <p>On the Review Case it sits inside the brand-outlined `DecisionCard` that marks the next thing to
   * press, so its own rounded tint was a card inside a card inside the page: three borders saying one
   * thing, and 24px the primary action paid for. Everything inside is unchanged.
   */
  unboxed?: boolean;
  /**
   * The reply-submission runtime the guided flow drives, when this build HAS one.
   *
   * <p>Injected so tests can pass a stub — an injected runtime is owned (and disposed) by whoever
   * created it, never by this panel. Otherwise {@code useReplyRuntime} owns it: the live bridge
   * runtime in DEV bridge mode (released, socket and all, on unmount), the simulated one in plain
   * DEV, and null in production. When it is null the panel offers a MANUAL handoff instead of a
   * guided one — different copy, and no run ref recorded — rather than silently simulating a run.
   *
   * <p>When a runtime does exist it remains the SOLE source of the recorded outcome + runId; the FE
   * never fabricates either.
   */
  replyRuntime?: ReplyRuntime;
  /**
   * The row's LIVE triage decision — a conservative gate, never a re-read trigger.
   *
   * This panel reads once and after its own writes; it deliberately does NOT re-read because
   * a sibling control moved the decision. So the `capabilities` in hand were computed under
   * whatever disposition held at read time, and every forward one — canSave, canApprove,
   * canCopy — has `responseNeeded` as a factor. Once the operator moves the review to
   * 지켜보기, those three are stale-TRUE, and acting on them means: a 초안 저장 whose every
   * retry is a byte-identical 409, and a 복사 the server would refuse but cannot, because the
   * approved body is already in this component's memory.
   *
   * So the disposition is AND-ed back in below. That re-states ONE clause of the server's
   * rule, and the direction is what makes it safe: the client can only ever refuse what the
   * server would allow, never allow what the server refuses. A conservative refinement cannot
   * drift into permissiveness — the failure mode of duplicating a rule — and it costs no
   * request, which is the constraint this design is under.
   */
  disposition: TriageDisposition | null;
  /**
   * Announced once a draft has been saved — i.e. once this review carries work the operator
   * must not lose.
   *
   * The row owns whether this panel exists, and its `hasReplyPreparation` came from the list
   * read, which predates any draft written in this session. Without this the operator could
   * save a draft, change their mind to 지켜보기, and watch the panel — and their draft —
   * disappear, reachable again only by re-triaging or reopening the drill-down.
   *
   * Fired after a confirmed save, not optimistically: the row would otherwise pin itself
   * open for work the server never accepted.
   */
  onPrepared?: () => void;
  /**
   * Fired once a SUBMITTED outcome has been RECORDED by the server — the moment the worklist above
   * stops counting this review and the row earns its 답변함으로 기록 badge.
   *
   * <p>Without it the rule is real and invisible: the count and the row are snapshots taken when the
   * page loaded, so a seller who works through their queue watches the number sit still and only
   * discovers the truth by reloading. Fired AFTER the server call, never on the operator's click —
   * announcing work the backend has not recorded would make the queue lie in the other direction.
   *
   * <p>Not fired for an abort: "I did not post it" changes nothing about the queue.
   */
  onOutcomeRecorded?: () => void;
  /**
   * Whether this panel currently holds work that only exists here — an unsaved edit, or a
   * write in flight.
   *
   * The row unmounts this panel when the decision leaves 대응 필요 and no server-side work
   * exists, which is right for an untouched panel and destructive for a typed-in one: the
   * buffer lives nowhere else, so a sibling click would silently take a written reply with
   * no confirm and no undo. `dirty` already exists to stop a server re-read from discarding
   * that text; this stops a sibling from doing the same thing.
   *
   * It also covers a write in flight: unmounting mid-save drops the failure handler, so a
   * save that then fails is never reported and the operator is left believing it landed.
   */
  onLocalWork?: (hasLocalWork: boolean) => void;
  /** The seller asked for an AI draft before this panel mounted (2026-10-26) — {@code GroundedReviewDraft.autoStart}. */
  autoDraft?: boolean;
}) {
  const [prep, setPrep] = useState<ReviewReplyPrep | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState<Busy>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [copied, setCopied] = useState(false);
  // The approved text, revealed for manual copying when this origin has no clipboard API.
  // Holds `approvedBody` and nothing else — never the editor buffer.
  const [manualCopy, setManualCopy] = useState<string | null>(null);
  const [body, setBody] = useState("");
  // True once the operator has touched the editor, so a reload does not silently discard
  // their typing by re-seeding from the server. The editor saves itself after a pause (mockup ①, 2026-10-25), so
  // this is the window between a keystroke and its save — also true for a suggestion no one has saved yet.
  const [dirty, setDirty] = useState(false);
  // The last save failed: the text stays in the box and the status line offers 「다시 시도」.
  const [saveFailed, setSaveFailed] = useState(false);
  // What this screen was typing when the phone's edit landed first (a 409 on save): kept for the seller to choose.
  const [localAside, setLocalAside] = useState<string | null>(null);
  // The latest editor text and dirtiness, for a save that finishes after more typing.
  const bodyRef = useRef("");
  bodyRef.current = body;
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
  const saveRef = useRef<(() => Promise<void>) | null>(null);
  const dispositionRef = useRef(disposition);
  dispositionRef.current = disposition;
  const refreshRef = useRef<(() => Promise<void>) | null>(null);
  // A guided reply-submission run in progress (after 네이버에서 직접 답변하기, before the operator
  // reports). Local and re-enterable: the ref is single-use, so backing out just re-mints on reopen.
  const [guided, setGuided] = useState<GuidedRun | null>(null);
  // Why the guided run stopped, when it stopped. A run that has ended cannot be reported on, so this also
  // decides which controls the panel shows: before this slice a stopped run kept offering 답변함/답변 안 함
  // and said nothing, and a locate that failed on the seller center reached the seller as silence.
  const [stopped, setStopped] = useState<{ code: ReplyBlockerCode | null; recoverable: boolean } | null>(null);

  const attempt = useRef<ApprovalAttempt | null>(null);
  const reportAttempt = useRef<OutcomeAttempt | null>(null);
  const inFlight = useRef(false);

  // Announced, not inferred: the row cannot see a buffer that lives in this component.
  const hasLocalWork = dirty || busy != null;
  useEffect(() => {
    onLocalWork?.(hasLocalWork);
  }, [hasLocalWork, onLocalWork]);
  // Owned by the hook: injected (tests, untouched) > live bridge runtime (DEV, disposed with its
  // socket on unmount) > simulated (DEV) > null. Null in every shipped build without a bridge:
  // this build cannot guide, and the panel says so rather than simulating a run nobody ran.
  const runtime = useReplyRuntime(replyRuntime);
  const canGuide = runtime != null;

  // Listen to the run this panel started. `observe` carries the run's own sanitized events; a runtime with
  // no event stream simply has none, and the panel then behaves exactly as it did before.
  const runId = guided?.handle?.runId ?? null;
  useEffect(() => {
    if (runtime?.observe == null || runId == null) return;
    const unsubscribe = runtime.observe((signal) => {
      if (signal.type !== "RUN_BLOCKED" && signal.type !== "RUN_FAILED") return;
      // RUN_BLOCKED carries the recoverability; RUN_FAILED follows it with the code alone. Keep the first
      // reading rather than letting the second overwrite it with a weaker one.
      setStopped((current) =>
        current ?? { code: signal.code ?? null, recoverable: signal.recoverable === true },
      );
    });
    return unsubscribe;
  }, [runtime, runId]);

  const headingId = useId();
  const editorId = useId();
  const editorRef = useRef<HTMLTextAreaElement>(null);

  const load = useCallback(async () => {
    try {
      const next = await api.getReviewReplyPrep(accountId, actionRef);
      setPrep(next);
      setLoadFailed(false);
      return next;
    } catch {
      setLoadFailed(true);
      return null;
    }
  }, [accountId, actionRef]);

  useEffect(() => {
    let active = true;
    void (async () => {
      const next = await load();
      if (!active || next == null) {
        return;
      }
      // Seed the editor once: the saved draft if there is one, else the suggestion. Never
      // over an edit in progress — see `dirty`.
      setBody((current) => (current === "" ? (next.draft?.body ?? next.suggestion.body) : current));
      // A suggestion nobody saved is saved like any edit: the service prepares the draft, the seller only judges it.
      if (next.draft == null && next.capabilities.canSave && dispositionRef.current === "RESPONSE_NEEDED"
          && next.suggestion.body.trim() !== "") {
        setDirty(true);
      }
    })();
    return () => {
      active = false;
    };
  }, [load]);

  // Saves itself a moment after the last keystroke — no 「초안 저장」 to press (mockup ①, 2026-10-25).
  useEffect(() => {
    if (!dirty) return;
    const timer = setTimeout(() => void saveRef.current?.(), AUTOSAVE_AFTER_MS);
    return () => clearTimeout(timer);
  }, [body, dirty]);

  // The same draft is on the seller's phone: a quiet panel re-reads now and then, so an edit made there shows here.
  const linked = prep?.delegation != null;
  useEffect(() => {
    if (!linked) return;
    const timer = setInterval(() => {
      if (!dirtyRef.current && !inFlight.current) void refreshRef.current?.();
    }, 30_000);
    return () => clearInterval(timer);
  }, [linked]);

  if (loadFailed) {
    return (
      <p role="alert" className="text-sm text-bad">
        답글 칸을 불러오지 못했어요. 새로고침해 주세요.
      </p>
    );
  }
  if (prep == null) {
    return (
      <p aria-live="polite" className="text-sm text-muted">
        불러오는 중…
      </p>
    );
  }

  const { capabilities, approval, draft } = prep;
  const approved = approval?.state === "APPROVED";
  const baseVersion = draft?.version ?? 0;
  const working = busy != null;

  // Every forward capability, refined by the LIVE decision — see the `disposition` prop.
  // Withdrawal is deliberately absent: `canWithdraw` is `approved` alone, so the decision
  // moving cannot stale it, and gating the one operation that reduces commitment is how a
  // review gets stranded in APPROVED.
  const responseNeeded = disposition === "RESPONSE_NEEDED";
  const canSave = capabilities.canSave && responseNeeded;
  const canCopy = capabilities.canCopy && responseNeeded;
  // The guided post is the copy step performed in the seller center, so it carries the same
  // conservative refinement as copy. It authorizes no send — the operator posts the reply themselves.
  const canStart = capabilities.canStartSubmissionRun && responseNeeded;
  // The channel already has a reply on this review. The server has already withheld
  // `canStartSubmissionRun` and would 409 the call anyway; this only lets the panel SAY why, instead
  // of hiding the control with no reason. Copy stays available — the clipboard is the operator's.
  // The one sentence that explains a missing guided step, chosen by the server's own reason.
  const guidedUnavailable = guidedUnavailableSentence(prep.guidedUnavailableReason);
  // The vendor delegate lane, when this review can use it (§4). Its own press is the panel's primary then — the
  // delegate posts — so 승인 (the copy road) steps down to an outline, and disappears while the lane holds the
  // review: the server has already shut that road (`canApprove` false) and a dead 승인 beside the lane's status
  // would only ask why.
  const delegation = prep.delegation ?? null;
  const reviewId = actionRef.startsWith("review:") ? actionRef.slice("review:".length) : null;
  const laneHolds = delegation != null && !capabilities.canApprove && delegation.taskState != null
    && !["FAILED", "CANCELLED", "INVALIDATED"].includes(delegation.taskState);

  /**
   * Approving binds the last SAVED version — never what is in the box.
   *
   * So an unsaved edit must block it. Otherwise the operator types a correction, clicks
   * 승인, is told it is approved, copies, and pastes the text they just replaced: the
   * approval bound `draft.version`, which their typing never reached. That is the same
   * failure the approved-head copy rule exists to prevent, arriving through the client
   * instead of the wire, and the server cannot see it — only this side knows the buffer
   * diverged. The fix is to refuse rather than to guess which text they meant.
   */
  // The company's phrases in the SAVED draft (2026-10-23). A ban keeps approval and sending shut until it is fixed;
  // the server refuses both on the same check, and this is the screen saying so before the press.
  const findings = approved || dirty ? [] : (prep.phraseFindings ?? []);
  const phraseBlock = phraseBlockSentence(findings);
  const approvable = capabilities.canApprove && responseNeeded && !dirty && phraseBlock == null;

  async function refresh() {
    const next = await load();
    if (next != null && !dirtyRef.current) {
      setBody(next.draft?.body ?? next.suggestion.body);
    }
  }
  refreshRef.current = refresh;

  /**
   * Saves the editor — or {@code text}, a change the seller pressed for in 걸린 표현 or the set-aside words they chose,
   * which also becomes the editor's. Typing that arrived during the save stays unsaved and saves itself next.
   */
  async function save(text?: string) {
    if (inFlight.current || !canSave || approved) {
      return;
    }
    const sending = text ?? bodyRef.current;
    if (sending.trim() === "") return;
    inFlight.current = true;
    setBusy("saving");
    setFailed(null);
    setSaveFailed(false);
    try {
      if (text != null) {
        setBody(text);
        bodyRef.current = text;
      }
      await api.saveReviewReplyDraft(accountId, actionRef, { body: sending, baseVersion });
      const still = bodyRef.current !== sending;
      setDirty(still);
      dirtyRef.current = still;
      // Before the re-read, so the row pins itself open on the save itself rather than on
      // the read that follows it — a failed refresh must not cost the operator their panel.
      onPrepared?.();
      await refresh();
    } catch (e) {
      const response = (e as { response?: { status?: number; data?: { message?: string } } })?.response;
      if (response?.status === 409 && (response.data?.message ?? "").includes("최신 초안")) {
        // The phone's edit landed first. Its words lead; this screen's are kept aside, not thrown away.
        setLocalAside(sending);
        setDirty(false);
        dirtyRef.current = false;
        await refresh();
      } else {
        // No detail: the actionable fact is "it did not save". A status code tells the
        // operator nothing, and a server message risks surfacing what this UI should not.
        setSaveFailed(true);
      }
    } finally {
      inFlight.current = false;
      setBusy(null);
    }
  }
  saveRef.current = () => save();

  // The words this screen was writing when the phone's edit led — this session's, or a saved version the server kept.
  const aside = dirty ? null : (localAside ?? delegation?.setAside?.body ?? null);

  /** 「휴대폰 문장 쓰기」: the phone's words stay the draft; the set-aside ones are let go. */
  async function keepPhoneWords() {
    if (inFlight.current) return;
    setLocalAside(null);
    if (delegation?.setAside != null && reviewId != null) {
      try {
        await api.dropReplySetAside(reviewId);
      } catch {
        setFailed("처리하지 못했어요. 다시 눌러 주세요.");
      }
      await refresh();
    }
  }

  /** 「이 PC 문장 쓰기」: the set-aside words become the newest draft, and go to the phone like any other edit. */
  async function useAsideWords(words: string) {
    if (inFlight.current) return;
    setLocalAside(null);
    await save(words);
  }

  async function decide(state: "APPROVED" | "WITHDRAWN") {
    if (inFlight.current || unavailable) {
      return;
    }
    if (state === "APPROVED" ? !approvable : !capabilities.canWithdraw) {
      // Enforced, not merely advertised by aria-disabled: the buttons stay focusable, so
      // they still receive clicks.
      return;
    }
    // Reuse the command id ONLY when retrying the same failed intent — a retry is one
    // decision reaching the server twice, which it answers as a replay. A fresh id would
    // make it a second, independent decision. Changing the intent takes a new id.
    const reuse = attempt.current?.state === state && failed != null;
    let commandId: string;
    try {
      commandId = reuse ? attempt.current!.commandId : newCommandId();
    } catch (e) {
      if (e instanceof SecureRandomUnavailableError) {
        setUnavailable(true);
        setFailed(null);
        return;
      }
      throw e;
    }
    attempt.current = { state, commandId };

    inFlight.current = true;
    setBusy(state === "APPROVED" ? "approving" : "withdrawing");
    setFailed(null);
    setCopied(false);
    setManualCopy(null);
    try {
      await api.decideReviewReplyApproval(accountId, actionRef, {
        commandId,
        state,
        baseVersion: state === "APPROVED" ? baseVersion : null,
      });
      attempt.current = null;
      // Re-read rather than trust the write's echo: the approved BODY only ever comes from
      // the prep read, so there is exactly one way to obtain copyable text.
      await refresh();
    } catch {
      setFailed(
        state === "APPROVED"
          ? "확정하지 못했어요. 다시 눌러 주세요."
          : "확정을 풀지 못했어요. 다시 눌러 주세요.",
      );
    } finally {
      inFlight.current = false;
      setBusy(null);
    }
  }

  async function copy() {
    // Refused while any write is in flight — the same guard `save` and `decide` carry, and
    // for the same reason: the buttons are aria-disabled rather than natively disabled, so
    // they still receive clicks, and only the ref updates synchronously enough to stop one.
    //
    // Load-bearing here in a way it is not on the others. A withdrawal in flight has already
    // cleared `copied`/`manualCopy` and set `busy`, but has NOT yet refreshed `prep` — so
    // `approved` and `canCopy` both still read true, and a click landing in that window
    // copies the body and re-sets `copied` AFTER the withdrawal cleared it. The panel then
    // finishes withdrawing and tells the operator "복사했습니다. 채널에 직접 붙여넣으세요"
    // about a reply that no longer stands.
    if (inFlight.current) {
      return;
    }
    // The server sends `approvedBody` only when copying is allowed, so this cannot reach
    // for the editor buffer even by mistake — there is nothing else to reach for. That is
    // the point: an unsaved keystroke nobody approved must never land in a public reply.
    // Gated here too, not only on the button: the approved body is already in this
    // component's state, so unlike a save there is no server call to refuse a copy the rule
    // no longer allows. This is the whole enforcement.
    const text = canCopy ? approval?.approvedBody : null;
    if (text == null) {
      return;
    }
    setFailed(null);
    const result = await copyText(text);
    if (result.ok) {
      setCopied(true);
      setManualCopy(null);
      return;
    }
    setCopied(false);
    if (result.reason === "UNAVAILABLE") {
      // No clipboard on this origin, and no retry will change that. Reveal the approved
      // text so the operator can copy it by hand — never a claim that it was copied.
      setManualCopy(text);
      return;
    }
    setFailed("복사하지 못했어요. 다시 눌러 주세요.");
  }

  async function startHandoff(options?: { restart?: boolean }) {
    // Named for what it does in BOTH modes: it starts a handoff, which is a guided run only when this
    // build has a runtime. Calling it startGuided while it also opened the manual path would be the
    // same overclaim in code that "(가이드)" was in the label.
    //
    // Reuse an UNSPENT handoff: one already in flight keeps its single-use submissionRef rather
    // than minting another. A fresh mint happens only after a terminal report spends it (setGuided(null)).
    // `restart` is the one case that may replace a run already on screen: the run STOPPED, so there is
    // nothing to preserve, and its single-use ref was spent reaching that stop. Everything else still
    // reuses an unspent handoff rather than minting a second one.
    if (inFlight.current || !canStart || (guided != null && options?.restart !== true)) {
      return;
    }
    if (options?.restart === true) {
      setGuided(null);
    }
    inFlight.current = true;
    setBusy("starting");
    setFailed(null);
    setStopped(null);
    try {
      // The submissionRef is minted by the SERVER either way — a real single-use binding, in both
      // the guided and the manual path. Only the RUN is conditional.
      const run = await api.startReviewReplySubmissionRun(accountId, actionRef);
      // With a runtime, it assigns the opaque runId (never the FE), using the same secure randomness
      // as a command id so a non-secure origin fails closed exactly like approval does. Without one,
      // there is no run to start and nothing to mint — the handoff is manual and says so.
      const handle = runtime
        ? await startReplySubmission(runtime, { channelCode: "naver", submissionRef: run.submissionRef })
        : null;
      setGuided({ submissionRef: run.submissionRef, handle });
    } catch (e) {
      if (e instanceof SecureRandomUnavailableError) {
        setUnavailable(true);
        setFailed(null);
      } else {
        setFailed("시작하지 못했어요. 다시 눌러 주세요.");
      }
    } finally {
      inFlight.current = false;
      setBusy(null);
    }
  }

  async function report(outcome: OperatorOutcomeName) {
    if (inFlight.current || guided == null) {
      return;
    }
    // Reuse the command id ONLY when retrying the same report — a retry is one report reaching the
    // server twice. A changed report (submitted vs aborted) takes a fresh id.
    const reuse = reportAttempt.current?.outcome === outcome && failed != null;
    let commandId: string;
    try {
      commandId = reuse ? reportAttempt.current!.commandId : newCommandId();
    } catch (e) {
      if (e instanceof SecureRandomUnavailableError) {
        setUnavailable(true);
        setFailed(null);
        return;
      }
      throw e;
    }
    reportAttempt.current = { outcome, commandId };
    inFlight.current = true;
    setBusy("reporting");
    setFailed(null);
    try {
      // Drive the run to its OPERATOR_REPORTED terminal; the recorded outcome + runId come FROM the
      // terminal (the sole source), never fabricated on the client.
      // With a run, the terminal is the sole source of both the outcome and the runId. Without one,
      // the outcome is the operator's own report and there is NO run ref — production may not mint a
      // run identity for a run that did not happen, and an absent ref is the honest record of that.
      const terminal = guided.handle
        ? outcome === "OPERATOR_REPORTED_SUBMITTED"
          ? await guided.handle.reportSubmitted()
          : await guided.handle.abortSubmission()
        : null;
      await api.recordReviewReplyOutcome(accountId, actionRef, {
        commandId,
        submissionRef: guided.submissionRef,
        operatorOutcome: terminal ? terminal.operatorOutcome : outcome,
        ...(terminal ? { awRunRef: terminal.runId } : {}),
      });
      reportAttempt.current = null;
      setGuided(null);
      // Only a reported SUBMISSION changes the worklist. An abort is a normal ending that leaves the
      // review exactly where it was, so telling the list to refetch would spend a request to redraw
      // an identical page.
      if ((terminal ? terminal.operatorOutcome : outcome) === "OPERATOR_REPORTED_SUBMITTED") {
        onOutcomeRecorded?.();
      }
      // Re-read so the recorded outcome (operatorOutcome + verification, as a pair) shows.
      await refresh();
    } catch {
      setFailed("기록하지 못했어요. 다시 눌러 주세요.");
    } finally {
      inFlight.current = false;
      setBusy(null);
    }
  }

  const Heading = `h${headingLevel}` as "h2" | "h3" | "h4";

  return (
    /* <b>간격 한 칸</b> (리뷰 canonical, 2026-10-06). 1366에서 이 상자가 첫 화면을 과하게 먹지 않도록
       블록 사이를 12 → 8로 좁힌다. 글도 컨트롤도 그대로이고 여백만 한 단계다. */
    <section aria-labelledby={headingId} className={`flex flex-col gap-2 ${unboxed ? "" : "rounded-xl bg-canvas p-3"}`}>
      <Heading id={headingId} className="text-sm font-semibold text-ink">
        답글
      </Heading>

      {/* The review, in full. Not the list's 60-char preview — an operator cannot answer a
          complaint they can only glimpse. Sensitive spans arrive already tokenized by the
          server; this renders, it never redacts. */}
      {subjectShownAbove ? (
        prep.bodyRedacted ? (
        <Disclosure label="고객 리뷰 다시 보기" summaryClassName="-ml-2">
          <div className="flex flex-col gap-1 pt-2">
            <p className="whitespace-pre-wrap text-sm text-ink">
              {prep.redactedBody ? plainText(prep.redactedBody) : <span className="text-muted">내용이 없어요</span>}
            </p>
            {prep.bodyRedacted ? (
              <p className="text-sm text-muted">개인정보로 보이는 부분은 가렸어요.</p>
            ) : null}
          </div>
        </Disclosure>
        ) : null
      ) : (
      <div className="flex flex-col gap-1">
        <p className="text-sm font-semibold text-muted">고객 리뷰</p>
        <p className="whitespace-pre-wrap text-sm text-ink">
          {prep.redactedBody ? (
            plainText(prep.redactedBody)
          ) : (
            <span className="text-muted">내용이 없어요</span>
          )}
        </p>
        {prep.bodyRedacted ? (
          // Said out loud rather than left as a mystery: the operator is about to send this
          // to a customer, and an unexplained [번호] in their source text is worth a
          // sentence.
          <p className="text-sm text-muted">개인정보로 보이는 부분은 가렸어요.</p>
        ) : null}
      </div>
      )}


      <div className="flex flex-col gap-1">
        {/* <b>승인된 답변은 읽을 것이지 고칠 것이 아니다</b> (Front conversation detail, 2026-10-06).
            Front의 제안된 답장은 상자 안에서 그냥 문장으로 서 있다 — 편집기 테두리도, 회색 입력면도
            없다. 우리 쪽도 승인이 서 있는 동안에는 저장할 수 없으므로 편집기를 그리는 것은 누를 수 없는
            컨트롤을 그리는 것이고, 그 테두리가 화면에서 가장 먼저 보이는 모양이 된다. 승인을 해제하면
            같은 자리에 같은 편집기가 돌아온다 — 아무 능력도 사라지지 않는다. */}
        {/* <b>승인된 상태에서는 이름을 그리지 않는다.</b> 카드가 이미 「답변 준비」라고 말하고 바로 아래
            출처 한 줄이 「아래 초안은 …」이라고 가리킨다. 편집기가 있을 때는 입력칸에 이름이 필요하므로
            `<label>`이 그대로 선다. 읽어 주는 이름은 양쪽 다 「답변 초안」으로 남는다. */}
        {approved ? null : (
          <div className="flex flex-wrap items-center gap-2">
            {/* Named for a screen reader only: the card's own 「답글」 head already says what the box is, and a second
                visible name over the same box was the third stacked line above the reply. */}
            <label htmlFor={editorId} className="sr-only">
              답글 내용
            </label>
            {canSave ? (
              <DraftStatus
                saving={dirty || busy === "saving"}
                saveFailed={saveFailed}
                onRetry={() => void save()}
                hasDraft={draft != null}
                fromPhoneAt={delegation?.draftFromPhone ? (draft?.createdAt ?? null) : null}
                toFix={findings.filter((f) => f.kind === "FORBIDDEN").length}
              />
            ) : null}
          </div>
        )}
        {draftProvenanceNote(prep.draftAuthorKind, approved) ? (
          <p className="text-sm text-muted">{draftProvenanceNote(prep.draftAuthorKind, approved)}</p>
        ) : null}
        {approved ? (
          <p
            aria-label="답글 내용"
            className="mt-1 whitespace-pre-wrap break-keep text-base leading-relaxed text-ink"
          >
            {body}
          </p>
        ) : (
          <textarea
            id={editorId}
            ref={editorRef}
            value={body}
            onChange={(e) => {
              setBody(e.target.value);
              setDirty(true);
              setSaveFailed(false);
            }}
            onBlur={() => {
              // Leaving the box is a pause too: save now rather than after the timer.
              if (dirtyRef.current) void save();
            }}
            // Typing goes on while the panel saves itself: a save only takes what was in the box when it began.
            readOnly={!canSave || (working && busy !== "saving")}
            rows={5}
            className="w-full rounded-lg border border-line bg-surface p-2 text-base leading-relaxed text-ink"
          />
        )}
        {!approved && !canSave && prep.channelReplyState !== "ANSWERED" ? (
          // Why the editor is inert, rather than a dead control with no explanation. The operator's
          // own decision is what closed it, and they can reverse it.
          //
          // TWO closures now reach `canSave === false`, and this sentence is only true of one. When
          // the CHANNEL has already answered, telling someone looking at a review that IS 대응 필요
          // that only 대응 필요 reviews may be prepared sends them to press a button already pressed.
          // That closure is stated once, above this panel by `ChannelAnsweredState`, which
          // is mounted on every reply surface and mounted BEFORE this panel is. Saying it twice on
          // one screen is the other way to get it wrong.
          <p className="text-sm text-muted">
            「답글 달기」로 정한 리뷰에만 답글을 쓸 수 있어요.
          </p>
        ) : null}
      </div>

      {/* Grounded Review Drafting v1, under the reply since 2026-10-27 (§12-4): the reply is the thing on this card, and
          what it leaned on, 「다시 쓰기」 and the guidance picker are one quiet row below it. It writes into the editor
          above and does nothing else. */}
      {canSave ? (
        <GroundedReviewDraft
          accountId={accountId}
          actionRef={actionRef}
          storedEvidence={prep.draftEvidence ?? []}
          storedBasis={prep.draftAnswerBasis ?? null}
          storedBasisNote={prep.draftAnswerBasisNote ?? null}
          hasStoredDraft={prep.draft !== null}
          autoStart={autoDraft}
          onDrafted={(generated) => {
            setBody(generated);
            setDirty(false);
            // The panel re-reads its own state (the head version, its author, its citations) beside
            // the draft rather than in front of it.
            void refresh();
          }}
        />
      ) : null}

      {findings.length > 0 && canSave ? (
        <PhraseFindings
          body={body}
          findings={findings}
          disabled={working}
          onApply={(next) => void save(next)}
          onEdit={(phrase) => {
            const editor = editorRef.current;
            if (!editor) return;
            const at = editor.value.indexOf(phrase);
            editor.focus();
            if (at >= 0) editor.setSelectionRange(at, at + phrase.length);
          }}
        />
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        {/* <b>승인된 동안에는 그리지 않는다.</b> 이 단추는 「지금은 못 누른다, 그러나 되돌릴 수 있다」를
            말하려고 비활성으로 남아 있었고 그 이유는 지켜보기일 때 옳다. 승인일 때의 이유는 다르다 —
            초안이 얼어 있는 것이고, 그 사실은 바로 아래 한 줄이 말하며 승인 해제가 되돌린다. 기본 화면에서
            눌리지 않는 회색 단추 하나가 줄어든다. */}
        {/* No 「초안 저장」: the editor saves itself after a pause, and the status line beside its label says where
            it stands (mockup ①, 2026-10-25). */}
        {/* Rendered inert rather than removed once a draft exists. The card removes a
            control when the row can NEVER carry the action (no ref); here the truth is
            "not while this review is 지켜보기" — a reason, and one the operator can reverse.
            The explanation is the '대응 필요로 기록된 리뷰만…' line above. */}
        {(capabilities.canApprove || (!approved && draft != null)) && !laneHolds && delegation == null ? (
          <button
            type="button"
            aria-disabled={!approvable || working || unavailable}
            aria-busy={busy === "approving"}
            onClick={() => void decide("APPROVED")}
            /* THE primary action of every reply-work surface (Approval Path v1 §4). It was a tint —
               `bg-brand/10 text-brand-700` — beside a neutral 초안 저장, so the one irreversible-ish
               decision on the screen carried no more weight than saving text, and on the 리뷰 detail it
               was the quietest control in the panel. Solid `brand-700` is the product's measured
               primary (5.41:1, 7.38:1 on hover). It never competes with 복사: 복사 exists only once an
               approval stands, and this button is gone by then. */
            className={`rounded-lg px-3 py-1.5 text-sm font-semibold ${
              approvable && !working
                ? delegation != null
                  ? "bg-surface text-ink ring-1 ring-line"
                  : "bg-brand-700 text-white hover:bg-brand-800"
                : "bg-canvas text-muted opacity-40"
            }`}
          >
            답글 확정
          </button>
        ) : null}
        {approved ? (
          /* 되돌리기이지 다음에 할 일이 아니다. 면을 가진 단추로 그리면 복사 옆에서 같은 무게를 갖고,
             기본 화면의 「지금 할 일」이 둘로 읽힌다. 글자 단추로 내린다 — 같은 write, 같은 자리. */
          <button
            type="button"
            aria-disabled={!capabilities.canWithdraw || working || unavailable}
            aria-busy={busy === "withdrawing"}
            onClick={() => void decide("WITHDRAWN")}
            className="rounded px-1 py-1 text-sm font-semibold text-muted underline-offset-2 hover:text-ink hover:underline"
          >
            확정 풀기
          </button>
        ) : null}
        {approved ? (
          <button
            type="button"
            aria-disabled={!canCopy || working}
            onClick={() => void copy()}
            className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${
              canCopy && !working ? "bg-brand-700 text-white" : "bg-canvas text-muted opacity-40"
            }`}
          >
            복사
          </button>
        ) : null}
        {/* The guided post. NOT a send: SellerOps foregrounds the seller center and the operator
            posts the reply themselves. No 발송/전송/등록 label (Frontend Spec §10.2). Rendered only
            while no run is in progress; the run's own panel is below. */}
        {canStart && guided == null ? (
          <button
            type="button"
            aria-disabled={!canStart || working}
            aria-busy={busy === "starting"}
            onClick={() => void startHandoff()}
            className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${
              canStart && !working ? "bg-canvas text-ink ring-1 ring-line" : "bg-canvas text-muted opacity-40"
            }`}
          >
            {canGuide ? "네이버에서 직접 답변하기(가이드)" : "직접 답변하고 기록하기"}
          </button>
        ) : null}
        {/* WHY the guided step is missing, when the seller has an approved reply and would otherwise
            be looking for it. The reason is the SERVER's — the same rule that decides whether the run
            may start — so the screen stops re-deriving it from `channelReplyState` and stops offering
            a control the mint would refuse. Absent reason ⇒ nothing rendered. */}
        {/* 컨트롤 줄에 섞여 있던 긴 설명 둘은 아래 각주 묶음으로 내려갔다. 여기 남는 것은 눌렀을 때
            무엇이 막혔는지를 말하는 짧은 상태들뿐이다. */}

        {approved && !canCopy ? (
          <span className="text-sm text-muted">
            「답글 달기」로 되돌리면 복사할 수 있어요.
          </span>
        ) : null}
      </div>
      {phraseBlock != null && delegation == null ? <p className="break-keep text-sm text-muted">{phraseBlock}</p> : null}

      {aside != null && !approved ? (
        <div
          role="group"
          aria-label="이 PC에서 쓰던 문장"
          className="flex flex-col gap-2 rounded-lg border border-line bg-white p-2"
        >
          <p className="text-sm font-semibold text-muted">이 PC에서 쓰던 문장</p>
          <p className="whitespace-pre-wrap break-keep text-sm text-muted">{aside}</p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              aria-disabled={working}
              onClick={() => void keepPhoneWords()}
              className="rounded-lg bg-white px-2.5 py-1 text-sm font-semibold text-ink ring-1 ring-line"
            >
              휴대폰 문장 쓰기
            </button>
            <button
              type="button"
              aria-disabled={working}
              onClick={() => void useAsideWords(aside)}
              className="rounded-lg bg-white px-2.5 py-1 text-sm font-semibold text-ink ring-1 ring-line"
            >
              이 PC 문장 쓰기
            </button>
          </div>
        </div>
      ) : null}

      {delegation != null && reviewId != null ? (
        <DelegatedReplyControls
          delegation={delegation}
          reviewId={reviewId}
          baseVersion={baseVersion}
          dirty={dirty || aside != null}
          disabled={working}
          blocked={phraseBlock}
          onChanged={refresh}
        />
      ) : null}

      {/* The copy road, where the delegate's road stands: kept — it is the seller's own way to answer — but under the
          one press, never above it (mockup ①, 2026-10-25). */}
      {delegation != null && !approved && !laneHolds && (capabilities.canApprove || draft != null) ? (
        <p className="flex flex-wrap items-center gap-1 text-xs text-muted">
          직접 복사해 답할 때는
          <button
            type="button"
            aria-disabled={!approvable || working || unavailable}
            aria-busy={busy === "approving"}
            onClick={() => void decide("APPROVED")}
            className={`rounded px-1 py-0.5 font-semibold underline underline-offset-2 ${
              approvable && !working ? "text-ink" : "text-muted opacity-40"
            }`}
          >
            답글 확정
          </button>
        </p>
      ) : null}

      {/* <b>사실은 그대로, 무게만 한 단계 아래로</b> (리뷰 canonical, 2026-10-06). 둘은 바로 위 두 컨트롤이
          각각 왜 그렇게 생겼는지를 말하는 각주다 — 복사뿐인 이유, 그리고 본문이 문장으로 서 있는 이유.
          컨트롤과 같은 크기로 줄줄이 서 있으면 설명이 두 번 반복되는 것처럼 읽힌다. 문장은 한 글자도
          바뀌지 않았고, 하나도 빠지지 않았다. */}
      {guidedUnavailable != null || approved ? (
        <div className="flex flex-col gap-0.5 text-xs leading-relaxed text-muted">
          {guidedUnavailable != null ? (
            <p
              data-testid={
                prep.guidedUnavailableReason === "CHANNEL_ALREADY_ANSWERED"
                  ? "channel-answered-notice"
                  : "guided-unavailable-notice"
              }
            >
              {guidedUnavailable}
            </p>
          ) : null}
          {approved ? <p>확정한 답글은 고칠 수 없어요. 고치려면 확정을 풀어 주세요.</p> : null}
        </div>
      ) : null}

      {/* The guided run in progress. The operator posts the reply in the seller center themselves;
          reviewnary only guides and records what they report. Two reports, both honest: 답변함 /
          답변 안 함 — never a claim reviewnary posted anything, and never a 완료. */}
      {guided != null ? (
        <div
          role="group"
          aria-label="네이버에서 직접 답변하기"
          className="flex flex-col gap-2 rounded-lg border border-line bg-white p-2"
        >
          <p className="text-sm font-semibold text-ink">네이버에서 직접 답변하기</p>
          <p className="text-sm text-muted">
            복사한 답변을 네이버 판매자센터 답변란에 붙여넣고 <strong className="font-semibold">직접</strong>{" "}
            답변해 주세요. reviewnary가 대신 하지 않으며, 답변 여부도 확인하지 않습니다.
          </p>
          {/* The overclaim this slice removes. Without a runtime nothing opens the seller center,
              nothing finds the row, nothing watches the post — so the panel must not imply it does.
              The locating facts below are what SellerOps CAN offer instead. */}
          {!canGuide ? (
            <p className="text-sm text-muted">
              이 화면은 안내(가이드)를 제공하지 않아요. 아래 정보로 네이버에서 리뷰를 찾아 주세요.
            </p>
          ) : null}
          {/* The narrowing facts: what the seller scans a review list by. Display name only — never
              a SKU or 상품번호 — resolved by the same shared rule the attention row uses. Each is
              omitted rather than guessed when the review does not carry it. */}
          <dl className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
            {prep.productName != null ? (
              <div className="flex gap-1">
                <dt className="font-semibold">상품</dt>
                <dd>{prep.productName}</dd>
              </div>
            ) : null}
            {prep.reviewDate != null ? (
              <div className="flex gap-1">
                <dt className="font-semibold">작성일</dt>
                <dd>{prep.reviewDate}</dd>
              </div>
            ) : null}
            {prep.rating != null ? (
              <div className="flex gap-1">
                <dt className="font-semibold">평점</dt>
                <dd>{"★".repeat(prep.rating)}</dd>
              </div>
            ) : null}
          </dl>
          {/* The precondition the guided locate cannot solve for itself, said BEFORE it bites rather than
              after. The seller center's review list shows one period at a time, and a review older than
              that period is not on the page at any scroll position — so a run looking for it waits for the
              seller to widen the period and press that screen's own 조회. Stated as a fact about the
              screen, not as a claim about this run's state, because the panel cannot see that state. */}
          {canGuide && stopped == null ? (
            <p className="text-sm text-muted">
              네이버 리뷰 목록의 조회 기간에{" "}
              {prep.reviewDate != null ? (
                <strong className="font-semibold">이 리뷰의 작성일({prep.reviewDate})</strong>
              ) : (
                <strong className="font-semibold">이 리뷰의 작성일</strong>
              )}
              이 포함돼 있어야 찾을 수 있어요. 목록이 최근 기간으로 좁혀져 있으면 기간을 넓히고 조회해
              주세요.
            </p>
          ) : null}
          {/* A run that stopped says so, says why, and offers the one thing that can follow. Reporting
              답변함 / 답변 안 함 belongs to a run that is still open, so those controls are not offered
              here — there is nothing left to report on. */}
          {stopped != null ? (
            <p role="alert" className="text-sm text-warn">
              {guidedStopSentence(stopped.code, prep.reviewDate)}
            </p>
          ) : null}
          <div className="flex flex-wrap items-center gap-2">
            {stopped != null ? (
              <button
                type="button"
                aria-disabled={working}
                aria-busy={busy === "starting"}
                onClick={() => void startHandoff({ restart: true })}
                className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${
                  working ? "bg-canvas text-muted opacity-40" : "bg-brand-700 text-white"
                }`}
              >
                다시 시도
              </button>
            ) : (
              <>
                <button
                  type="button"
                  aria-disabled={working}
                  aria-busy={busy === "reporting"}
                  onClick={() => void report("OPERATOR_REPORTED_SUBMITTED")}
                  className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${
                    working ? "bg-canvas text-muted opacity-40" : "bg-brand/10 text-brand-700"
                  }`}
                >
                  답변함으로 기록
                </button>
                <button
                  type="button"
                  aria-disabled={working}
                  onClick={() => void report("SUBMISSION_ABORTED")}
                  className="rounded-lg bg-canvas px-2.5 py-1 text-sm font-semibold text-ink"
                >
                  답변 안 함으로 기록
                </button>
              </>
            )}
            <button
              type="button"
              aria-disabled={working}
              onClick={() => {
                setGuided(null);
                setStopped(null);
              }}
              className="rounded-lg px-2.5 py-1 text-sm text-muted"
            >
              닫기
            </button>
          </div>
        </div>
      ) : null}

      {/* The recorded outcome — outcome AND verification shown as a PAIR. Never the verification
          ("확인 안 함") alone (it would read as a system failure, not an operator report), and never
          a "완료". */}
      {prep.outcome != null ? (
        <div role="status" className="flex flex-col gap-1 rounded-lg bg-canvas p-2">
          <p className="text-sm text-ink">
            {prep.outcome.operatorOutcome === "OPERATOR_REPORTED_SUBMITTED"
              ? "채널에 직접 답변한 것으로 기록했어요."
              : "답변하지 않은 것으로 기록했어요."}
          </p>
          <p className="text-sm text-muted">reviewnary는 답변 여부를 확인하지 않습니다(확인 안 함).</p>
        </div>
      ) : null}

      {/* Mounted always, text toggling — a live region has to be in the accessibility tree
          before its content changes or assistive tech never registers on it. Empty renders
          as nothing, so there is no visual cost. */}
      <p aria-live="polite" className="text-sm text-muted">
        {busy === "approving" ? "승인 중…" : ""}
        {busy === "withdrawing" ? "승인 해제 중…" : ""}
        {/* Honest about who sends. The product does not post this anywhere, and the moment
            it would be easiest to imply otherwise is the moment the copy succeeds. */}
        {!working && copied ? "복사했습니다. 채널에 직접 붙여넣으세요." : ""}
      </p>

      {manualCopy != null && canCopy ? (
        // A capability limit, not a failure — and NOT a claim that anything was copied.
        // The text is the approved body, selectable, so the operator can take it by hand.
        //
        // Gated on `canCopy`, not just on having text: nothing clears `manualCopy` when a
        // sibling moves the decision, and on a no-clipboard origin THIS is the copy path —
        // so without the gate the panel would keep offering "직접 선택해 복사하세요" beside a
        // button that has just refused the same action. That inverts the one invariant this
        // design rests on: the client may refuse what the server allows, never the reverse.
        //
        // Withheld rather than cleared, so restoring 대응 필요 restores the fallback the
        // operator was mid-way through using.
        <div role="group" aria-label="승인된 답변" className="flex flex-col gap-1">
          <p className="text-sm text-bad">
            이 환경에서는 자동 복사를 할 수 없습니다. 아래 내용을 직접 선택해 복사하세요.
          </p>
          <textarea
            readOnly
            value={manualCopy}
            rows={4}
            aria-label="승인된 답변 (직접 복사)"
            className="w-full rounded-lg border border-line bg-white p-2 text-sm text-ink"
          />
        </div>
      ) : null}

      {unavailable ? (
        <p role="alert" className="text-sm text-bad">
          이 환경에서는 승인을 기록할 수 없습니다. 보안 연결(HTTPS)로 접속해 주세요.
        </p>
      ) : null}
      {failed != null && !unavailable ? (
        <p role="alert" className="text-sm text-bad">
          {failed}
        </p>
      ) : null}
    </section>
  );
}
