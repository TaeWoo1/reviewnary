import { AnswerBasisQuickAdd } from "./AnswerBasisQuickAdd";
import { waitedLabel } from "../../lib/inquiryWorkflow";
import {
  canVerifyPublish,
  canResumePublish,
  deliveryVerificationLabel,
  publishCategoryLabel,
} from "../../lib/inquiryPublish";
import type { InquiryDetail, DraftEvidenceView, OrderContextView } from "../../lib/types";
import { bindingLabel, canBindProduct, productLabel } from "../../lib/inquiryProductBinding";
import { InquiryProductBinder } from "./InquiryProductBinder";
import { answerStateIsGood, type AnswerStateView } from "../../lib/answerState";
import { Btn } from "../ui/Btn";
import { Facts } from "../ui/ObjectRow";
import { Disclosure } from "../ui/Disclosure";
import { plainText } from "../../lib/plainText";
import { draftProvenanceLine, draftSectionHeading } from "../../lib/draftProvenance";
import { Link } from "react-router-dom";
import type { InquiryReplyWorkspace } from "./useInquiryReply";


/**
 * The inquiry response workflow, in the inbox detail panel. The engine (`inquiryWorkflow`) is reused
 * unchanged; the publish decisions live in `inquiryPublish`, and this component renders what they say.
 *
 * ## What the seller sees, in order (Inquiry Action Flow v1)
 *
 * 고객 문의 → AI 답변 → actions. Three blocks, one primary control. The previous layout put a
 * response-TYPE suggestion, a two-field editor and a send behind four same-weight buttons and three
 * explanatory paragraphs, so the screen never said what to do next.
 *
 * A draft now has a BODY, because `InquiryDraftComposer` writes one — grounded in the seller's own
 * 상품 지식 when the inquiry resolves to a product that has any. What it was grounded in is shown as
 * citations; what it could NOT be grounded in is one sentence ABOVE it (`knowledgeNote`), read before
 * the text it qualifies rather than discovered after. The response-TYPE suggestion still exists (it
 * is what moves the work item OPEN → PROPOSED) but it is one word in the meta line, not a section:
 * it was never why the seller opened this item.
 *
 * EDITING MAKES IT THEIRS. A save appends a version with a new fingerprint, which is exactly why a
 * prior approval can no longer be spent on it — the backend binds to the fingerprint, so "이전
 * 승인은 무효" is a property of the data rather than a rule this component has to remember. The
 * citations are dropped on that save for the same reason: the sentences are now the seller's, and
 * attributing them to a knowledge passage that may no longer support them would be a false claim.
 *
 * ## The send, and why it exists here now
 *
 * The backend has carried a complete, fail-closed inquiry reply-publish chain for some time — approval
 * bound immutably to an exact draft version and fingerprint, a `commandId` idempotency key, a
 * dispatch-recovery runner that reclassifies an abandoned DISPATCHING to DELIVERY_UNKNOWN and NEVER
 * resends, two independent flags, and a per-channel adapter registry that resolves empty by default.
 * It had **no caller in the product at all**: `confirm-publish` was reachable only by hand. A WRITE
 * path that exists and cannot be reached is not a safety property, it is an untested one.
 *
 * So the send is here, and every guard the backend enforces is mirrored in what the seller sees:
 *
 *  - **it is not offered unless the backend says it can be done** — `canPublishReply` reads the
 *    capability endpoint (execution flag + this channel's adapter) BEFORE any control is rendered, and
 *    fails closed when that read did not land. A channel without an adapter gets the manual hand-off
 *    it always had, with the reason said out loud;
 *  - **the approval binds to what was on screen** — the confirm sends the exact `contentFingerprint`
 *    of the draft version the seller read, so a draft that moved underneath them is a 409 and not a
 *    reply nobody reviewed;
 *  - **the press is deliberate** — a single click cannot send. The seller opens the confirm block,
 *    reads back what will be posted and where, and presses again. The second press is the ONLY thing
 *    in this product that writes to a marketplace;
 *  - **nothing is retried blindly** — "다시 시도" appears only for a retryable/publishing outcome, and
 *    DELIVERY_UNKNOWN offers verify-only, because an unobserved delivery must never be resent.
 */
export function InquiryReply({
  workspace,
  docked = false,
}: {
  workspace: InquiryReplyWorkspace;
  /**
   * <b>누를 것 하나는 pane 바닥에 있다</b> (문의 canonical, 2026-10-03).
   *
   * <p>true이면 이 블록은 1차 행동을 그리지 않는다 — {@code CustomerInbox}가 같은 작업대를 읽어
   * {@code MasterDetail}의 dock에 그린다. 초안이 길어도 눌러야 할 것이 스크롤 밖으로 나가지 않는다.
   * 「답변 보내기」의 두 번째 누름(등록 확인 블록)은 <b>옮기지 않았다</b>: 보낼 내용을 다시 읽는 자리가
   * 그 블록이고, 이 제품에서 유일하게 되돌릴 수 없는 누름은 그 블록 안에 남는다.
   */
  docked?: boolean;
}) {
  const {
    detail,
    setDetail,
    loading,
    error,
    busy,
    actionError,
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
    draft,
    publishable,
    unavailableReason,
    draftDirty,
    draftEditable,
    priorDelivery,
    canDraft,
    publishStatus,
    onSaveDraft,
    onConfirmPublish,
    onVerifyPublish,
    onResumePublish,
    onGenerateDraft,
    onBasisSaved,
    onCopyDraft,
  } = workspace;

  if (loading) {
    return <p className="text-base text-muted">문의 내용을 불러오는 중…</p>;
  }

  // Fail closed. Without the detail there is nothing honest to offer, so the panel says so and
  // offers no controls rather than presenting an empty workflow.
  if (error || !detail) {
    return <p className="text-base text-muted">{error ?? "문의 내용을 불러오지 못했습니다."}</p>;
  }

  return (
    <div className="space-y-4">
      {/*
        1 — THE CUSTOMER'S QUESTION. First, largest, and never competing with a control.

        The label steps DOWN and the question steps UP (Executive-friendly UX Redesign v1). 「고객
        문의」 used to be the bold 17px line and the customer's actual sentence was set at body size
        under it, so the largest words on the most important screen in the product were the ones the
        seller already knew.
      */}
      <section>
        {/* The 「고객 문의」 label is gone (Executive Readiness Fix v1). It cost a line at the very top
            of the pane to name something the reader had just clicked out of a list titled 문의, and
            the block below is unambiguous once the answer beneath it is labelled 「AI가 준비한 답변」.
            The section keeps its accessible name. */}
        <h3 className="sr-only">고객 문의</h3>
        {/*
          <b>질문은 한 번만 그린다.</b> dock을 쓰는 화면(문의)에서는 호출부의 header가 고객의 질문 전문을
          제목으로 세우고 채널·상품·받은 날을 meta로 그린다 — 그래서 여기서는 다시 그리지 않는다. 남는 것은
          그 header가 모르는 것 둘뿐이다: 상품을 지정하는 길과, 이 문의가 가리킨 주문의 상태.
          dock이 없는 호출부(확인할 일의 pane)는 header가 없으므로 예전 그대로 전부 그린다.
        */}
        {docked ? null : (
          <>
            {detail.title ? (
              <p className="break-keep text-lg font-bold leading-snug text-ink">
                {plainText(detail.title)}
              </p>
            ) : null}
            <p className="mt-2 whitespace-pre-wrap break-keep text-lg leading-relaxed text-ink">
              {plainText(detail.details) || "본문이 없습니다."}
            </p>
          </>
        )}
        {docked ? null : (
          <InquiryMeta
            detail={detail}
            onBind={canBindProduct(detail) ? () => setBinding(true) : undefined}
          />
        )}
        {binding ? (
          <InquiryProductBinder
            detail={detail}
            onCancel={() => setBinding(false)}
            onBound={(productId, productName) => {
              setBinding(false);
              // The bound product is what the next draft will be grounded in, so the panel must show
              // it immediately — a seller who binds and then presses "AI 답변 초안 만들기" is entitled
              // to see which product they are about to lean on.
              setDetail((current) =>
                current
                  ? { ...current, productId, productName, productBinding: "USER_CONFIRMED" }
                  : current,
              );
            }}
          />
        ) : null}
        <OperationalContext context={detail.orderContext} />
      </section>

      {/* 2 — THE ANSWER. One section, whatever state it is in. */}
      {/*
        <b>카드가 아니다</b> (문의 canonical, 2026-10-03). 이 블록은 canvas 카드였고, 그 안에 상태 카드,
        그 안에 흰 초안 카드, 그 안에 또 근거 상자가 들어 있었다 — 한 pane에 네 겹. 이제 이 pane에서 면을
        가진 것은 <b>초안 하나</b>뿐이다: 판매자가 복사해서 내보낼 바로 그 문장. 나머지는 구분선과 글자다.
      */}
      <section>
        {/*
          <b>The heading names the version's AUTHOR, not the section's intent</b> (Pilot QA, 2026-09-06).
          It said 「AI가 준비한 답변」 unconditionally, so a version the seller had rewritten was handed
          back to them as the assistant's work — and on the live org that version carried an
          operational promise (「전화로 문의 주시면」) no registered knowledge supports. The ledger has
          always recorded `authorKind`; this screen simply did not read it.
        */}
        <h3 className="text-sm font-semibold text-muted">
          {draftSectionHeading(draft?.authorKind, Boolean(draft))}
        </h3>

        {/*
          Three states, not two. There was no draft and there was a draft; a generate that
          DECLINED to write one is a third, and folding it into the first would put the seller back
          in front of the same button with no answer to what they just pressed.
        */}
        {!draft && !answerState && !unavailable ? (
          <>
            <p className="mt-1.5 break-keep text-sm leading-relaxed text-muted">
              문의 내용과 등록된 상품 지식을 근거로 초안을 씁니다. 보내는 것은 확인 후 따로 누릅니다.
            </p>
            <div className="mt-4">
              <Btn onClick={onGenerateDraft} disabled={busy || !canDraft}>
                {busy ? "쓰는 중…" : "AI 답변 초안 만들기"}
              </Btn>
            </div>
            {!canDraft ? (
              <p className="mt-3 break-keep text-sm text-muted">
                지금 상태에서는 초안을 만들 수 없습니다. 목록에서 상태를 확인해 주세요.
              </p>
            ) : null}
          </>
        ) : (
          <>
            {/*
              2 — WHAT THE AI DECIDED, before the text it decided it about.

              The three states are the point of this screen (Core Daily Loop UX Integration v1 §5).
              Until 2026-08-27 only one of them had a card: GROUNDED was rendered as the same orange
              caution strip a failure got, and NEEDS_CLARIFICATION — where the draft is a question
              back to the customer — appeared nowhere, so a seller read a polite request for the
              규격 as an answer that had come out short.
            */}
            {/*
              Said once, on the pass right after a save, and OUTSIDE the state card.

              It used to live inside it, which meant a save whose regenerate could not run — the AI
              draft capability off, the day's budget spent, a vendor that did not answer — rendered
              no card and so no acknowledgement at all: the seller wrote a fact, pressed save, and
              the screen said only that the machinery was unavailable. Saving is what THEY did, and
              it happened whether or not the regenerate produced anything.

              Which sentence depends on what actually followed. A state card means the regenerate
              produced a verdict; no card means it did not, and claiming 「답변을 다시 만들었습니다」
              would be the screen reporting work that nobody did.
            */}
            {basisSaved ? (
              <p
                className="mt-3 break-keep text-sm font-medium leading-relaxed text-good"
                role="status"
                data-testid="basis-saved"
              >
                {answerState
                  ? "답변 기준을 저장했습니다. 저장한 내용으로 답변을 다시 만들었습니다."
                  : "답변 기준을 저장했습니다."}
              </p>
            ) : null}
            <AnswerStateCard
              state={answerState}
              justSaved={basisSaved}
              onSavedBasis={onBasisSaved}
            />

            {/*
              What the library could and could not offer — and ONLY when the card above is absent.

              Three reasons it is not repeated under the card (§11). On GROUNDED and
              NEEDS_CLARIFICATION it says the same thing the card just said. On NO_ANSWER_BASIS its
              longer form — 「아래 과거 답변은 참고용이며」 — points at citations that are not on this
              screen: a state with no answer basis records no evidence rows, so there is no 아래 to
              look at. What survives is the reload case, where nothing computed a state this session
              and the stored sentence is the only thing that can speak.
            */}
            {knowledgeNote && !answerState ? (
              <p
                className={`mt-2 break-keep border-l-2 pl-3 text-base leading-relaxed text-ink ${
                  knowledgeGrounded ? "border-line" : "border-warn"
                }`}
              >
                {knowledgeNote}
              </p>
            ) : null}
            {/*
              THE MACHINERY DID NOT RUN — a different card from the one above, on purpose.

              「답변 기준이 필요합니다」 sends the seller off to write product knowledge. A vendor
              timeout on a fully grounded question sent them there too, until 2026-08-27, and the
              knowledge they were told to add already existed. This card says what did not run and
              leaves their library alone.
            */}
            {unavailable ? (
              <div className="mt-3 border-l-2 border-warn pl-3">
                <p className="break-keep text-lg font-semibold leading-relaxed text-ink">
                  {unavailable}
                </p>
                <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
                  아래에 직접 작성하실 수 있습니다.
                </p>
              </div>
            ) : null}

            {editing || !draft ? (
              <div className="mt-4">
                <label className="block text-sm font-medium text-ink" htmlFor="reply-title">
                  제목
                </label>
                <input
                  id="reply-title"
                  className="mt-1 w-full rounded-lg border border-line bg-surface p-2 text-ink"
                  value={replyTitle}
                  onChange={(e) => setReplyTitle(e.target.value)}
                  disabled={busy}
                />
                <label className="mt-3 block text-sm font-medium text-ink" htmlFor="reply-comments">
                  내용
                </label>
                <textarea
                  id="reply-comments"
                  className="mt-1 w-full rounded-lg border border-line bg-surface p-2 text-ink"
                  rows={6}
                  value={replyComments}
                  onChange={(e) => setReplyComments(e.target.value)}
                  disabled={busy}
                />
                <div className="mt-3 flex flex-wrap gap-2">
                  <Btn size="sm" onClick={onSaveDraft} disabled={busy || !replyComments.trim()}>
                    {busy ? "저장 중…" : "초안 저장"}
                  </Btn>
                  {draft ? (
                    <Btn
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditing(false);
                        setReplyTitle(draft.title);
                        setReplyComments(draft.comments);
                      }}
                      disabled={busy}
                    >
                      취소
                    </Btn>
                  ) : null}
                </div>
              </div>
            ) : (
              <div className="mt-3 rounded-2xl bg-canvas p-4">
                {/*
                  THE COPY BUTTON SITS WITH THE TEXT IT COPIES (Executive Readiness Fix v1).

                  It used to be below the draft, after the evidence fold — measured at y=901 with the
                  fold at 900, and 181px below it at 125% zoom. Pinning it to the bottom of the pane
                  was tried and was worse: a 199px bar covered the draft and the gap note it was
                  supposed to accompany. A block's own copy control belongs in the block's header,
                  where it is visible exactly when the thing it copies is, at any zoom and for any
                  length of question.

                  Only when copying IS the action. Where SellerOps can register the answer itself,
                  「답변 보내기」 is the primary and it stays below with its confirm step — the one
                  irreversible control in the product does not move next to the text it would send.
                */}
                <div className="flex items-start justify-between gap-3">
                  <p className="min-w-0 break-keep font-semibold text-ink">{draft.title}</p>
                  {!docked && !publishable && !draftDirty ? (
                    /*
                      THE GUARANTEE TRAVELS WITH THE BUTTON (Executive Readiness Fix v1).

                      「이 환경에서는 reviewnary가 답변을 대신 등록하지 않습니다」 lives at the bottom of
                      the section, and at 125% zoom it is the FIRST thing to leave the screen — so a
                      reader enlarging the type, which is exactly what a 50-year-old operator does,
                      was left pressing a button on a customer's inquiry with no visible promise about
                      where the text goes. A reader called that a trust accident, not a crop. The full
                      sentence still stands below; this is the short form, attached to the control.

                      Only where copying is all that happens. On a channel SellerOps can post to, this
                      would be a false promise, and 「답변 보내기」 owns that path with its own confirm.
                    */
                    <div className="shrink-0 text-right">
                      <Btn onClick={onCopyDraft} disabled={busy}>
                        {copied ? "복사했습니다" : "초안 복사"}
                      </Btn>
                      <p className="mt-1 break-keep text-sm text-good">고객에게 나가지 않습니다</p>
                    </div>
                  ) : null}
                </div>
                <p className="mt-1.5 whitespace-pre-wrap break-keep text-lg leading-relaxed text-ink">
                  {draft.comments}
                </p>
                {/* Which version this is and who wrote it — the ledger is append-only, so both are facts. */}
                {draftProvenanceLine(draft.authorKind, draft.version) ? (
                  <p className="mt-2 break-keep text-sm text-muted" data-testid="draft-provenance">
                    {draftProvenanceLine(draft.authorKind, draft.version)}
                  </p>
                ) : null}
                {companyContextUsed ? (
                  <p className="mt-2 break-keep text-sm text-muted" aria-label="회사 정보 참고">
                    회사 정보를 참고해 표현했습니다. 배송·환불·규격 같은 사실의 근거는 아닙니다.
                  </p>
                ) : null}
              </div>
            )}


            {/*
              The send, or the honest reason there is none. `publishUnavailableReason` names WHICH of
              the three conditions failed, because "이 채널은 판매자센터에서 직접" and "이 환경에서는
              대신 등록하지 않습니다" are different things for the seller to do about it.
            */}
            {/* A send that already happened hides the send block — whether this session performed it
                (publishStatus) or a previous one did and only the record remains (priorDelivery).
                Without the second half, a reload after a DELIVERY_UNKNOWN or a refused send offered
                「답변 보내기」 again with no trace of the first attempt on screen. */}
            {publishStatus || priorDelivery ? null : (
              <div className="mt-5 border-t border-line pt-4">
                {/*
                  Said BEFORE the press, not recorded after it. On a channel whose collection is stale
                  SellerOps cannot prove this inquiry is still unanswered, and the person who accepts
                  that risk has to be the person who was told about it.
                */}
                {/*
                  <b>값이 있으면 말한다</b> (product-owner decision, 2026-10-03). 전에는
                  {@code publishable}일 때만 그렸다 — 그런데 이 문장이 말하는 위험(「이미 답변이 달렸는지
                  지금은 확인할 수 없다」)은 보내기로 등록하든 복사해서 손으로 등록하든 똑같다. 등록 경로가
                  꺼져 있다는 이유로 경고를 숨기면, 중복 답변을 다는 쪽은 그 사실을 모른 채로 단다.
                  dock이 있는 화면에서는 dock이 같은 문장을 1차 행동 바로 앞에서 말하므로 여기서는 빠진다.
                */}
                {!docked && detail.answerStateNote ? (
                  <p className="mb-3 break-keep text-sm leading-relaxed text-warn">
                    {detail.answerStateNote}
                  </p>
                ) : null}

                {!confirming || !draft ? (
                  /*
                    ONE PRIMARY, THEN THE REST (Executive-friendly UX Redesign v1).

                    These four controls used to sit in one wrapping row at the same small size, so
                    「답변 보내기」 — the only irreversible action in the product — and 「다시 작성」
                    were the same object to a reader scanning it. The action the seller came here to
                    take is now alone on its line at full size; 수정 and 다시 작성 are text under it.

                    Which control IS the primary still depends on the channel, not on this component's
                    preference: where SellerOps cannot register the answer itself, copying is the
                    action and it takes the emphasis 답변 보내기 would have had.
                  */
                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-2">
                      {!docked && publishable && !draftDirty && !editing ? (
                        <Btn onClick={() => setConfirming(true)} disabled={busy}>
                          답변 보내기
                        </Btn>
                      ) : null}
                      {/* Not repeated here when it is the primary — it is in the draft's own header. */}
                      {!docked && publishable && draft && !editing && !draftDirty ? (
                        <Btn size="sm" variant="outline" onClick={onCopyDraft} disabled={busy}>
                          {copied ? "복사했습니다" : "초안 복사"}
                        </Btn>
                      ) : null}
                    </div>
                    {!editing && draftEditable ? (
                      <div className="flex flex-wrap items-center gap-1">
                        <Btn size="sm" variant="ghost" onClick={() => setEditing(true)} disabled={busy}>
                          수정
                        </Btn>
                        {/* 다시 작성 is gated on the SAME condition as the first generate. It was not,
                            so an inquiry already answered on the channel — and any item past PROPOSED,
                            which the composer refuses — still offered the button, and pressing it
                            produced an error where the seller had been promised a draft. 수정 stays:
                            their own text is theirs to change whatever the channel did. */}
                        {canDraft ? (
                          <Btn size="sm" variant="ghost" onClick={onGenerateDraft} disabled={busy}>
                            다시 작성
                          </Btn>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                ) : (
                  /*
                    The second press. Everything that is about to happen is restated here — where it
                    goes, which saved version, and that it cannot be taken back — because this is the
                    only control in SellerOps that writes to a marketplace, and a seller should never
                    discover afterwards which text was sent.
                  */
                  <div
                    className="rounded-xl border border-line bg-surface p-4"
                    role="group"
                    aria-label="답변 등록 확인"
                  >
                    <p className="break-keep text-sm font-semibold text-ink">
                      {detail.channelNameKo ?? "채널"}에 아래 내용을 등록합니다. 등록 후에는 취소할 수
                      없습니다.
                    </p>
                    <p className="mt-2 text-sm text-muted">저장된 버전 {draft.version}</p>
                    <p className="mt-2 whitespace-pre-wrap break-keep rounded-lg bg-canvas p-3 text-sm leading-relaxed text-ink">
                      {draft.comments}
                    </p>
                    <div className="mt-3 flex gap-2">
                      <Btn size="sm" onClick={onConfirmPublish} disabled={busy}>
                        {busy ? "등록 중…" : "확인, 등록합니다"}
                      </Btn>
                      <Btn size="sm" variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>
                        취소
                      </Btn>
                    </div>
                  </div>
                )}

                {manualCopy !== null ? (
                  <div className="mt-3">
                    <p className="break-keep text-sm leading-relaxed text-muted">
                      이 주소에서는 자동 복사를 쓸 수 없습니다. 아래 내용을 직접 복사해 주세요.
                    </p>
                    <textarea
                      readOnly
                      aria-label="복사할 초안"
                      className="mt-2 w-full rounded-xl border border-line bg-canvas p-3 text-sm text-ink"
                      rows={5}
                      value={manualCopy}
                    />
                  </div>
                ) : null}

                {!publishable ? (
                  /* The sentence that tells the seller what finishing this looks like. It was the
                     smallest text on the screen while being the only instruction on it. */
                  <p className="mt-3 break-keep text-base leading-relaxed text-muted">
                    {unavailableReason}
                  </p>
                ) : draftDirty ? (
                  // A dirty editor means the fingerprint on screen is not the one that would be sent.
                  // Said while the editor is open too — that is when the seller can act on it.
                  <p className="mt-3 text-sm text-muted">
                    편집한 내용을 먼저 저장해 주세요. 저장된 버전만 등록할 수 있습니다.
                  </p>
                ) : null}
              </div>
            )}

            {/*
              근거는 초안 <b>안</b>이 아니라, 초안과 그 초안에 대해 할 수 있는 일 <b>다음</b>에 온다.
              순서가 곧 질문의 순서다: 무엇이라 답할 것인가 → 그걸 어떻게 내보내는가 → 이 답이 무엇 위에
              서 있는가. 초안 면 안에 들어 있던 동안에는 근거가 보낼 문장의 일부처럼 읽혔다.
            */}
            {editing || !draft ? null : <DraftEvidence evidence={evidence} />}

            {/* The outcome of the one marketplace WRITE this product performs, read back from the rows
                rather than remembered from the press. The local inquiry stays UNANSWERED until a
                verified read-back or the next collection, so this window is exactly when a seller
                comes back to ask whether their answer went out. */}
            {!publishStatus && priorDelivery ? (
              <div className="mt-5 rounded-xl border border-line bg-surface p-4">
                <p className="break-keep text-sm text-ink">{publishCategoryLabel(priorDelivery.category)}</p>
                {deliveryVerificationLabel(priorDelivery) ? (
                  <p className="mt-1 break-keep text-sm text-muted">{deliveryVerificationLabel(priorDelivery)}</p>
                ) : null}
                <div className="mt-3 flex flex-wrap gap-2">
                  {canVerifyPublish(priorDelivery) ? (
                    <Btn size="sm" variant="ghost" onClick={onVerifyPublish} disabled={busy}>
                      상태 다시 확인
                    </Btn>
                  ) : null}
                  {canResumePublish(priorDelivery) ? (
                    <Btn size="sm" variant="ghost" onClick={onResumePublish} disabled={busy}>
                      이어서 등록
                    </Btn>
                  ) : null}
                </div>
              </div>
            ) : null}

            {publishStatus ? (
              <div className="mt-5 rounded-xl border border-line bg-surface p-4">
                <p className="break-keep text-sm text-ink">{publishCategoryLabel(publishStatus.category)}</p>
                {publishStatus.approvedDraftVersion !== null ? (
                  <p className="mt-1 text-sm text-muted">등록한 버전 {publishStatus.approvedDraftVersion}</p>
                ) : null}
                {publishStatus.presendStateProven === false ? (
                  <p className="mt-1 break-keep text-sm text-muted">
                    보낼 당시 이 채널의 문의 수집이 최신이 아니었습니다.
                  </p>
                ) : null}
                <div className="mt-3 flex flex-wrap gap-2">
                  {canVerifyPublish(publishStatus) ? (
                    <Btn size="sm" variant="ghost" onClick={onVerifyPublish} disabled={busy}>
                      상태 다시 확인
                    </Btn>
                  ) : null}
                  {canResumePublish(publishStatus) ? (
                    <Btn size="sm" variant="ghost" onClick={onResumePublish} disabled={busy}>
                      이어서 등록
                    </Btn>
                  ) : null}
                </div>
              </div>
            ) : null}
          </>
        )}

        {actionError ? <p className="mt-3 text-sm text-bad">{actionError}</p> : null}
      </section>
    </div>
  );
}

/**
 * <b>What the AI decided about this question</b> — one card, three shapes.
 *
 * <p>Position 2 of the five the seller reads (Core Daily Loop UX Integration v1 §4): the customer's
 * question, then this, then the evidence and the draft, then the one thing to press. It is rendered
 * ONLY for a generate that happened in this session — a reload cannot tell a clarification draft
 * from a grounded one, and guessing would be the kind of confident wrong label this screen exists to
 * avoid.
 *
 * <p><b>GROUNDED is the only good-news shape.</b> NEEDS_CLARIFICATION is a real and correct reply,
 * and it still wants the seller's eye before it goes out: the customer is about to be asked a
 * question rather than answered, and a seller who skims past that sends a question they could have
 * answered themselves. NO_ANSWER_BASIS keeps the way out that was added to it — the box that writes
 * the missing sentence without leaving this screen.
 *
 * <p>The sentences are the backend's, in every shape. This component chooses the border, the order,
 * and which controls belong under which state.
 */
function AnswerStateCard({
  state,
  justSaved,
  onSavedBasis,
}: {
  state: AnswerStateView | null;
  justSaved: boolean;
  onSavedBasis: () => void | Promise<void>;
}) {
  if (!state) return null;
  const good = answerStateIsGood(state.basis);
  const noBasis = state.basis === "NO_ANSWER_BASIS";
  return (
    <div
      data-testid="answer-state"
      data-basis={state.basis}
      className={`mt-3 border-l-2 pl-3 ${good ? "border-good" : "border-warn"}`}
    >
      {/*
        WHAT ALREADY HAPPENED COMES FIRST (Inquiry Operations Workspace v1 §6).

        A seller who added a 답변 기준 for this exact question, and then watched the same screen say
        「답변 기준이 필요합니다」 again, has been told their work did not happen. It did — it just does
        not answer THIS question yet, and those are two different sentences. The distinction is a fact
        the backend already decided on and used to swallow: `noteGap` returns null for an ask this org
        has already ACCEPTED, by the same identity it files by. It says so now.

        The way out is unchanged and still offered, because adding more IS the next step. What changes
        is that the screen stops asking for something it was already given.
      */}
      <p className="break-keep text-lg font-semibold leading-relaxed text-ink">
        {noBasis && state.previouslyAnswered
          ? "답변 기준은 추가하셨습니다."
          : state.note}
      </p>
      {noBasis && state.previouslyAnswered ? (
        <p className="mt-1.5 break-keep text-base leading-relaxed text-ink">
          다만 이 질문에 그대로 적용할 수 있는 내용은 아직 찾지 못했습니다.
        </p>
      ) : state.action ? (
        <p className="mt-1.5 break-keep text-base leading-relaxed text-ink">{state.action}</p>
      ) : null}
      {noBasis ? (
        <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
          근거가 없는 답변은 만들지 않습니다. 아래에 직접 작성하실 수 있습니다.
        </p>
      ) : null}
      {/*
        THE WAY OUT, on the screen that named the gap.

        Saying what is missing and offering nothing to do about it is where this state stopped until
        2026-08-27: the seller read 「답변 기준이 필요합니다」, and the next identical question read it
        again. The corpus is chosen by what the question was about (`gapScope`) — a product when the
        inquiry resolved to one, the company's rules when it did not but the question named an
        operating topic. Until Knowledge Setup & Inbox UX v1 only the first case had a way out, so
        「제주도인데 배송이 며칠 걸리나요?」 named what was missing and offered nothing.

        `gapScope` is null when neither: no product to attach a sentence to and no topic to file it
        under. A box with nowhere to save is worse than no box, and the line above already says
        binding a product is the first thing to fix.
      */}
      {noBasis && state.gapScope ? (
        <AnswerBasisQuickAdd
          scope={state.gapScope}
          productId={state.productId}
          topic={state.topic}
          candidateId={state.candidateId}
          label={state.previouslyAnswered ? "답변 기준 더 채우기" : undefined}
          onSaved={onSavedBasis}
        />
      ) : null}
      {/*
        Where the sentence they just wrote now lives (§12). Offered after a save rather than always:
        it is the answer to "그래서 어디에 저장된 거지", and on a grounded draft nobody asked.
      */}
      {justSaved && state.productId ? (
        <p className="mt-3">
          <Link
            className="rounded text-sm font-medium text-brand-700 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            to={`/products/${state.productId}`}
          >
            이 상품에 등록된 답변 기준 보기
          </Link>
        </p>
      ) : null}
    </div>
  );
}

/**
 * Channel · 상품 · 상태 · 경과 — one line, in the order a seller triages by.
 *
 * The response-TYPE suggestion sits here rather than in a section of its own: it is a hint about how
 * to answer, and it was never why the seller opened this item. 상품 is stated as an absence when there
 * is none, because "(미지정 상품)" is the reason a draft could not be grounded and hiding it would
 * make the limitation above the draft look arbitrary.
 */
/**
 * 운영 정보 — the state of the order this inquiry names.
 *
 * <p><b>It renders nothing when the inquiry names no order</b>, which is most of them. An empty card
 * with three "확인되지 않음" rows would read as "we looked up the order and it has no state", and the
 * seller would learn to distrust the card everywhere else.
 *
 * <p>Three rows rather than one status line, because payment does not imply dispatch — and each row
 * says "확인되지 않음" on its own rather than borrowing certainty from the row above it.
 */
function OperationalContext({ context }: { context: OrderContextView | null }) {
  if (!context || !context.present) return null;
  return (
    <div className="mt-4 border-t border-line pt-3">
      <p className="text-sm font-semibold text-muted">운영 정보</p>
      {context.summaryKo ? (
        <p className="mt-1.5 break-keep text-sm leading-relaxed text-ink">{context.summaryKo}</p>
      ) : null}
      <dl className="mt-2.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted">결제</dt>
        <dd className="text-ink">{context.paymentKo}</dd>
        <dt className="text-muted">배송</dt>
        <dd className="text-ink">{context.fulfillmentKo}</dd>
        <dt className="text-muted">취소</dt>
        <dd className="text-ink">{context.cancellationKo}</dd>
      </dl>
      {context.observedKo ? (
        <p className="mt-2 text-sm text-muted">{context.observedKo}</p>
      ) : null}
    </div>
  );
}

function InquiryMeta({
  detail,
  onBind,
}: {
  detail: InquiryDetail;
  onBind?: () => void;
}) {
  const waited = waitedLabel(detail.receivedAt);
  const provenance = bindingLabel(detail);
  return (
    <Facts className="mt-3 text-sm text-muted">
      {detail.channelNameKo ? <span>{detail.channelNameKo}</span> : null}
      {/* 「상품 미지정」 STAYS (Executive-friendly UX Redesign v1 — considered and rejected). Hiding
          the absence and offering only the control that fixes it reads cleaner and is less honest:
          the missing product is WHY a draft could not be grounded, and the gap line that explains
          that in full does not exist until a draft has been generated. */}
      <span>{productLabel(detail)}</span>
      {provenance ? <span className="text-muted">({provenance})</span> : null}
      {onBind ? (
        <button
          type="button"
          className="rounded px-1 text-sm font-medium text-brand-700 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          onClick={onBind}
        >
          {detail.productId ? "상품 바꾸기" : "상품 지정"}
        </button>
      ) : null}
      {waited ? <span>{waited}</span> : null}
      {/* The workflow phase and the proposal's own category used to sit here too, so this one line
          read 「카페24 자사몰 · 상품 미지정 · 상품 지정 · 제안 생성됨 · 1시간째 · 일반 응답」. Neither told
          the seller anything they could act on: what state the work is in is what the 답변 block
          below RENDERS, and 「일반 응답」 is the classifier talking to itself. `phaseLabel` also
          passes unmapped phases through verbatim, so a completed item put a raw APPROVED /
          COMPLETED on screen — removing its only render site removes that leak (Demo UX Polish v1). */}
    </Facts>
  );
}

/**
 * What the draft was grounded in.
 *
 * Titles and provenance, never the passage text: the reply above already says the thing, and a
 * citation is a pointer back to the seller's own words so the claim can be checked. An empty list
 * renders nothing at all — an empty "근거" heading would read as a failure rather than as a draft
 * that never claimed grounding (the sentence above it already said which).
 */
function DraftEvidence({ evidence }: { evidence: DraftEvidenceView[] }) {
  if (evidence.length === 0) return null;
  // Grouped by where it came from, in the order the retrieval returned it. A seller who disagrees
  // with the reply needs to know WHICH thing to go and fix — a wrong spec is fixed in 상품 지식, a
  // wrong shipping promise in 운영 정책, and neither fix reaches the other.
  const groups: Array<{ label: string; items: DraftEvidenceView[] }> = [];
  for (const item of evidence) {
    const label = item.scopeLabel ?? item.kind;
    const last = groups.length > 0 ? groups[groups.length - 1] : undefined;
    if (last && last.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
  }
  const [lead, ...rest] = evidence;
  const leadLabel = lead.scopeLabel ?? lead.kind;
  // 「상품 정보 2개 · 과거 답변 1개」 — the summary the fold is decided from.
  const summary = groups.map((group) => `${group.label} ${group.items.length}개`).join(" · ");
  return (
    /*
      THE FIRST CITATION IS OPEN; THE REST FOLD (2026-08-26).

      Executive-friendly UX Redesign v1 closed this list entirely, on the reasoning that a seller
      needs to know HOW MANY sources an answer stands on and can open it to see which. The NAVER live
      case showed what that costs. The reply answered 「전선이 몇 가닥까지 들어가나요?」 and the one
      citation read 「AI가 확인한 내용 · 상품 정보 1개」 — closed. Behind it was a source titled
      「자주 묻는 질문 - 접착과 재부착」, whose text contained that exact question and its answer. Neither
      the count nor the title could tell the seller whether the draft had any basis, and checking cost
      a click they had no reason to spend.

      So a grounded draft shows its lead citation in full — kind, title, excerpt — and everything
      after it folds. Opening all of them would be the other failure: four passages of quoted
      knowledge under every draft is noise, and the excerpt is the thing worth one card, not four.

      The locator stays off the screen entirely: it identified a chunk, and no seller acts on a
      chunk id.
    */
    <div className="mt-4 border-t border-line pt-3">
      <p className="text-sm font-medium text-muted">답변에 사용한 근거</p>
      <div className="mt-1.5 border-l-2 border-line pl-3">
        <p className="text-sm text-muted">{leadLabel}</p>
        <p className="mt-0.5 break-keep font-medium text-ink">{lead.title ?? leadLabel}</p>
        {lead.snippet ? (
          <p className="mt-1 break-keep text-sm leading-relaxed text-ink">{lead.snippet}</p>
        ) : null}
      </div>
      {rest.length > 0 ? (
        <Disclosure className="mt-2" label={`나머지 근거 ${rest.length}개 · ${summary}`}>
          <div className="mt-2 space-y-2">
            {rest.map((item, index) => (
              <div
                key={`${item.chunkId ?? item.sourceId ?? "evidence"}-${index}`}
                className="border-l-2 border-line pl-3"
              >
                <p className="text-sm text-muted">{item.scopeLabel ?? item.kind}</p>
                <p className="mt-0.5 break-keep font-medium text-ink">
                  {item.title ?? item.scopeLabel ?? item.kind}
                </p>
                {item.snippet ? (
                  <p className="mt-1 break-keep text-sm leading-relaxed text-ink">{item.snippet}</p>
                ) : null}
              </div>
            ))}
          </div>
        </Disclosure>
      ) : null}
    </div>
  );
}

/**
 * <b>pane 바닥에 고정된 1차 행동</b> — 문의 canonical(2026-10-03).
 *
 * <p>초안은 길다. 교환 기준 하나를 설명하는 답변이 pane에서 여섯 줄이고, 긴 문의에서는 눌러야 할 것이
 * 접힌 근거와 기록 아래로 내려간다. 그래서 리뷰·반복 문제가 쓰는 장치를 그대로 쓴다: 읽는 것은 스크롤하고,
 * <b>누르는 것은 바닥에 남는다</b>.
 *
 * <p><b>무엇이 1차인가는 채널이 정한다</b>, 이 컴포넌트의 취향이 아니다. SellerOps가 등록할 수 있는
 * 채널이면 「답변 보내기」이고, 아니면 복사가 행동이라 「초안 복사」가 그 자리를 갖는다.
 *
 * <p><b>되돌릴 수 없는 누름은 여기 없다.</b> 「답변 보내기」는 본문의 등록 확인 블록을 열 뿐이고, 두 번째
 * 누름은 보낼 내용을 다시 적어 둔 그 블록 안에 있다. 확인 블록이 열려 있는 동안 dock은 그리지 않는다 —
 * 같은 순간에 두 개의 1차 행동이 있는 화면이 되기 때문이다.
 */
export function InquiryReplyDock({ workspace }: { workspace: InquiryReplyWorkspace }) {
  const {
    detail,
    draft,
    busy,
    copied,
    editing,
    confirming,
    draftDirty,
    publishable,
    publishStatus,
    priorDelivery,
    onCopyDraft,
    setConfirming,
  } = workspace;
  // 보낼 것이 없거나(초안 없음), 이미 보냈거나, 저장되지 않은 편집 중이거나, 확인 블록이 열려 있으면
  // dock은 아무것도 주장하지 않는다. 저장되지 않은 버퍼를 복사하거나 보내는 길은 여기에도 없다.
  if (!detail || !draft) return null;
  if (publishStatus || priorDelivery) return null;
  if (confirming || editing || draftDirty) return null;
  return (
    <div className="-mb-6 flex items-center justify-between gap-4 border-t border-line bg-surface pb-6 pt-4">
      <div className="min-w-0">
        {/* 값이 있으면 등록 경로가 켜져 있든 아니든 말한다 — 중복 답변의 위험은 둘 다 같다. */}
        {detail.answerStateNote ? (
          <p className="break-keep text-xs leading-snug text-warn">{detail.answerStateNote}</p>
        ) : null}
        {!publishable ? <p className="break-keep text-xs text-good">고객에게 나가지 않습니다</p> : null}
      </div>
      <span className="shrink-0 whitespace-nowrap">
        {publishable ? (
          <Btn size="sm" onClick={() => setConfirming(true)} disabled={busy}>
            답변 보내기
          </Btn>
        ) : (
          <Btn size="sm" onClick={onCopyDraft} disabled={busy}>
            {copied ? "복사했습니다" : "초안 복사"}
          </Btn>
        )}
      </span>
    </div>
  );
}
