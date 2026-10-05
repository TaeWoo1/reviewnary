import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link, Navigate, useParams, useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { Empty } from "../../components/ui/Empty";
import { Facts } from "../../components/ui/ObjectRow";
import { Section } from "../../components/ui/Section";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { VocItemReplyPrep } from "../../components/VocItemReplyPrep";
import { SellerCorrectionControls } from "../../components/reviews/SellerCorrectionControls";
import { ChannelAnsweredState } from "../../components/reviews/ChannelAnsweredState";
import { TriageTierChip } from "../../components/reviews/TriageTierChip";
import { ReviewProblemCard } from "../../components/reviews/decision/ReviewProblemCard";
import { RepeatedSignal } from "../../components/reviews/decision/RepeatedSignal";
import { GroundingOnHand, OpenAskNote } from "../../components/reviews/decision/GroundingOnHand";
import { EvidencePreview } from "../../components/reviews/decision/EvidencePreview";
import { DecisionActionStep } from "../../components/reviews/decision/DecisionActionStep";
import { api } from "../../lib/apiClient";
import { reviewRecordPath } from "../../lib/reviewRecord";
import { reviewWord } from "../../lib/channelVocabulary";
import { plainText } from "../../lib/plainText";
import { COPY, sourceLabel } from "../../lib/copy/customerOps";
import { Disclosure } from "../../components/ui/Disclosure";
import { CaseLayout, DecisionCard, Eyebrow, type CaseVariant, type PaneDepth } from "../../components/workspace/CaseLayout";
import { ReviewLocate } from "../../components/reviews/ReviewLocate";
import {
  DECISION_LOG_DISCLOSURE,
  PREVIEW_SAFETY_LINE,
  decisionLogSentence,
  previewJudgmentTokens,
} from "../../lib/reviewDecision";
import { SEVERITY_LABEL_KO } from "../../lib/reviewIssuesView";
import { kstDate } from "../../lib/format";
import { triageDispositionLabel } from "../../lib/vocItems";
import { TRIAGE_TIER_LABEL } from "../../lib/reviewTriage";
import type {
  ReviewChannelCapabilityView,
  TriageBehaviorEvent,
  ChannelReviewDetailView,
  IssueSeverity,
  ReviewDecisionContext,
  ReviewDecisionLogEntry,
  ReviewDecisionProblem,
  TriageDisposition,
} from "../../lib/types";

/**
 * <b>리뷰 처리 — the Review Decision Workspace.</b>
 *
 * <p>One review, and everything needed to decide it, in the order a person decides:
 * <ol>
 *   <li>what the customer said, and</li>
 *   <li>why it is in front of them ({@link ReviewProblemCard});</li>
 *   <li>whether anyone has said it before ({@link RepeatedSignal});</li>
 *   <li>what a reply would stand on ({@link GroundingOnHand});</li>
 *   <li>what the SELLER thinks ({@link SellerCorrectionControls});</li>
 *   <li>what they will DO ({@link DecisionActionStep});</li>
 *   <li>the draft that follows from that choice, and only from 대응 필요;</li>
 *   <li>what has already been decided — the page draws it as a flat trail ({@code RecordTrail}).</li>
 * </ol>
 *
 * <p><b>What this screen was before.</b> Review Approval Path v1 built it as 답변 작업 — an address for
 * one review's approve button, which was the right fix for the problem it had (the button sat at
 * y=5,425 of a 5,587px record). But it opened on a draft: the customer's sentence and the reason the
 * review was ranked were folded away under 「이 리뷰의 자동 분류」, the seller's own judgment lived on a
 * different screen, the repeated problems were one muted line with no examples, and what the company
 * had written down was not mentioned at all. A seller could approve here; they could not DECIDE here.
 * Everything this page now shows already existed — on five screens, none of them this one.
 *
 * <p><b>Reuse, not replacement.</b> The reply lane is untouched: the same {@link VocItemReplyPrep}
 * panel, the same append-only draft versions, the same approval boundary and fingerprint, the same
 * copy-then-post handoff. The decision is the same `TriageDisposition` through the same endpoint with
 * the same idempotency key and audit trail. The seller's judgment is T-07's correction, unchanged. The
 * log invents no store: it reads trails that were already being written.
 *
 * <p><b>Three reads open it</b> — the review, the decision context, the decision log — and the last two
 * are deliberately separate from the first: neither is needed to answer a customer, so neither may
 * delay or fail the panel that does. A failed context or log renders nothing rather than an absence
 * claim about something the screen never saw.
 *
 * <p><b>Addressed by the review alone</b> (Agent-native Core Boundary v1). `/reviews/reply/:reviewId`
 * IS this page now — it used to resolve an account and redirect, and a review with no account to
 * resolve hit a dead end that said so in as many words. Every read and write above is org-scoped; the
 * account arrives as a FACT on the detail (`sellerAccountId`) and is used for exactly the two things
 * that are genuinely account-shaped: the reply panel, and the link back to that channel's record.
 *
 * <p><b>Nothing on this page posts to a marketplace.</b> The only writes are the ones the product
 * already had, and every one of them lands in reviewnary's own database.
 */
export function ReviewReplyTask() {
  const { reviewId = "" } = useParams();
  const [params] = useSearchParams();
  const from = params.get("from");
  return (
    <ReviewCaseView
      reviewId={reviewId}
      variant="page"
      from={from === "chat" || from === "work" || from === "record" ? from : null}
    />
  );
}

/**
 * The Review Case in either reading of {@link CaseLayout} — its own page, or the right-hand pane of 확인할 일 and
 * 오늘. The reads, the writes and the reply lane are the same in both; only the placement differs.
 *
 * <p><b>Two judgments, numbered, because they are two.</b> 「이 리뷰의 중요도」 is T-07's correction of the
 * triage tier; 「처리 방법」 is the `TriageDisposition`. They used to stand one under the other with the same word —
 * 「지켜보기」 — on a button in each, recorded in two different stores. The stored values are unchanged; the
 * disposition's word is now 「두고 보기」 (`TRIAGE_OPTIONS`) so the seller can tell which question a press answers.
 */
export function ReviewCaseView({
  reviewId,
  variant,
  depth = "full",
  from = null,
}: {
  reviewId: string;
  variant: CaseVariant;
  /**
   * {@link PaneDepth}. 「preview」 leaves the two judgments and the record control to the full case and
   * says instead what stands — see {@link DecisionSoFar}.
   */
  depth?: PaneDepth;
  /** Where a full page was opened from — decides which way back it offers. */
  from?: "chat" | "work" | "record" | null;
}) {
  const pane = variant === "pane";
  const preview = pane && depth === "preview";
  /** 확인할 일's reading of this pane — the one that docks its primary at the column's floor. */
  const docked = pane && depth === "full";
  /* <b>확인할 일's pane does not fold its evidence</b> (canonical mockup, 2026-10-03). The folds were
     bought with the decision forms' place on the first screen of a 556px column; this reading has no
     forms — the judgement moved to the Review workspace the dock opens — so there is nothing to buy
     and the evidence is simply read. The page and 오늘's preview are unchanged. */
  const paneEvidenceFolded = pane && !preview && !docked;

  const [detail, setDetail] = useState<ChannelReviewDetailView | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  // Bumped by this page's own writes so the reads that describe them run again. The panel refreshes
  // itself; this exists so a decision recorded here is not described by a read taken before it.
  const [version, setVersion] = useState(0);
  const bump = useCallback(() => setVersion((v) => v + 1), []);

  // The context and the log: SECOND and THIRD reads, and deliberately separate ones. Null covers both
  // "not read yet" and "read failed", and the two are the same on screen on purpose — a screen that
  // could not see the issue memory has nothing to say about it, and 「반복된 적 없습니다」 from a read
  // that never returned would be an invention.
  const [context, setContext] = useState<ReviewDecisionContext | null>(null);
  const [contextFailed, setContextFailed] = useState(false);
  const [log, setLog] = useState<ReviewDecisionLogEntry[] | null>(null);
  const [logFailed, setLogFailed] = useState(false);
  /**
   * <b>이 채널이 원문 화면을 열어 줄 수 있는가, 그리고 이 조직이 pilot인가</b> — 둘 다 서버가 말한다.
   * 채널 코드에서 추론하지 않는다: 쿠팡만 locate를 가진다는 것은 오늘의 사실이지 계약이 아니고, 화면이
   * 그것을 외워 두면 서버가 바뀐 날 눌러도 거절당하는 단추가 남는다. 조용히 실패한다 — 못 읽었으면
   * 아무것도 그리지 않는다(`ReviewLocate`가 접힌 설명으로 떨어진다).
   */
  const [facts, setFacts] = useState<{ capability: ReviewChannelCapabilityView | null; pilot: boolean }>({
    capability: null,
    pilot: false,
  });

  // The live decision. `context.currentDecision` is a snapshot from the last read; a choice made in
  // this session must open the draft step immediately rather than after a reload.
  const [decision, setDecision] = useState<TriageDisposition | null>(null);
  // Whether the review carries reply work that must stay reachable whatever the decision now says.
  // Monotonic within a session: a draft written while the review was 대응 필요 must stay readable — and
  // any approval withdrawable — after the seller moves it to 두고 보기.
  const [prepared, setPrepared] = useState(false);
  const [localWork, setLocalWork] = useState(false);

  const channelCode = context?.channelCode ?? null;
  /** Silver: never fails the screen, and never fires without the account the event is scoped to. */
  const recordLocateBehavior = useCallback(
    (events: TriageBehaviorEvent[]) => {
      const account = detail?.sellerAccountId;
      if (!account || events.length === 0) return;
      try {
        void Promise.resolve(api.recordChannelReviewTriageBehavior(account, events)).catch(() => undefined);
      } catch {
        // silver
      }
    },
    [detail?.sellerAccountId],
  );
  useEffect(() => {
    if (!channelCode) return;
    let live = true;
    Promise.resolve()
      .then(() => api.getReviewRecordStrict({ channel: channelCode, size: 1 }))
      .then((view) => {
        if (!live) return;
        setFacts({
          capability: view.channelFacts?.find((f) => f.channelCode === channelCode)?.capability ?? null,
          pilot: view.aiPilotEnabled,
        });
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [channelCode]);

  useEffect(() => {
    // A pane that switches from one review to the next must not carry the last one's session state.
    setDecision(null);
    setPrepared(false);
    setLocalWork(false);
  }, [reviewId]);

  useEffect(() => {
    if (!reviewId) return;
    let live = true;
    setLoading(true);
    api
      .getReviewWorkspace(reviewId)
      .then((view) => {
        if (!live) return;
        setDetail(view);
        setFailed(false);
        setPrepared((was) => was || (view.replyWork?.hasReplyPreparation ?? false));
        if (view.replyWork?.triageDisposition) setDecision(view.replyWork.triageDisposition);
      })
      .catch(() => {
        if (!live) return;
        setDetail(null);
        setFailed(true);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [reviewId, version]);

  useEffect(() => {
    if (!reviewId) return;
    let live = true;
    api
      .getReviewDecisionContext(reviewId)
      .then((view) => {
        if (!live) return;
        setContext(view);
        setContextFailed(false);
        // The server's answer wins over a decision this session has not made. It does not overwrite a
        // choice made here: `version` bumps on every write, so by the time this lands it has read it.
        setDecision(view.currentDecision);
      })
      .catch(() => {
        if (!live) return;
        setContext(null);
        setContextFailed(true);
      });
    return () => {
      live = false;
    };
  }, [reviewId, version]);

  useEffect(() => {
    if (!reviewId) return;
    let live = true;
    api
      .getReviewDecisionLog(reviewId)
      .then((rows) => {
        if (!live) return;
        setLog(rows);
        setLogFailed(false);
      })
      .catch(() => {
        if (!live) return;
        setLog(null);
        setLogFailed(true);
      });
    return () => {
      live = false;
    };
  }, [reviewId, version]);

  const word = reviewWord(context?.channelCode ?? null);

  // The channel record is an ACCOUNT-shaped surface: it lists what one connected account holds. A
  // review this org uploaded has no such page, so the way back is the index rather than a link into a
  // record that does not exist.
  const recordPath = detail?.sellerAccountId ? reviewRecordPath(detail.sellerAccountId) : "/reviews";

  const back = pane ? undefined : (
    <div className="flex flex-wrap items-center gap-3">
      {/* The way back is the way in. A review opened from 확인할 일 goes back there — sending the seller to one
          channel's record would drop them into a list they never came from. */}
      {from === "work" ? (
        <Link to="/customer-operations/cases" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          ← {COPY.listTitle}
        </Link>
      ) : from === "record" ? (
        <Link to="/reviews" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          ← 리뷰
        </Link>
      ) : (
        <Link to={recordPath} className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          ← 리뷰 기록으로
        </Link>
      )}
      {/* The conversation that sent the seller here is still the one at `/` — the pointer is stored per
          org and restored on mount, so this link returns to the same thread rather than starting one.
          Rendered only when a conversation actually sent them. */}
      {from === "chat" ? (
        <Link to="/" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          대화로 돌아가기
        </Link>
      ) : null}
    </div>
  );

  if (loading && !detail) {
    return (
      <div className="space-y-4">
        {back}
        {pane ? null : <PageHead title="리뷰 처리" compact />}
        <p className="text-sm text-muted">불러오는 중…</p>
      </div>
    );
  }

  if (failed || !detail) {
    return (
      <div className="space-y-4">
        {back}
        {pane ? null : <PageHead title="리뷰 처리" compact />}
        <Empty
          title="이 리뷰를 불러오지 못했습니다"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요. 불러오지 못한 리뷰를 임의로 채우지는 않습니다."
          action={<Btn size="sm" onClick={bump}>다시 시도</Btn>}
        />
      </div>
    );
  }

  const replyWork = detail.replyWork;
  // The draft belongs to the chosen action: it opens on 대응 필요, and it stays open for work that
  // already exists so an approved reply can never be stranded where the seller can neither read nor
  // withdraw it.
  const showDraft = replyWork !== null && (decision === "RESPONSE_NEEDED" || prepared || localWork);
  // The reply lane's own address. Reply work is only ever handed out with an account behind it, so
  // this is non-null exactly when the panel below mounts.
  const replyAccountId = detail.sellerAccountId ?? "";
  const body = detail.body ? plainText(detail.body).trim() : "";
  const title = detail.textless || body.length === 0 ? `별점만 남긴 ${word}` : body;

  return (
    <CaseLayout
      variant={variant}
      /* <b>페이지에 둘째 열은 없다</b> (리뷰 canonical mockup, 2026-10-06 — Front conversation detail).
         240px rail에 남아 있던 것은 사실 두 개였다. 사실 두 개를 담으려고 페이지 높이만큼 세로줄을 긋는
         것은, 제목 아래 한 줄이 할 일을 기둥으로 하는 것이고, 「면을 가진 물건은 하나」인 화면에 두 번째
         모서리를 그리는 것이다. 두 사실은 머리말의 평문 속성으로 올라가고 읽는 열은 792 → 900을 되찾는다. */
      rail={variant === "page" ? "none" : undefined}
      decisionLabel="판매자의 결정"
      nav={back}
      /* <b>페이지에서는 제목 위에 아무것도 없다</b> (리뷰 canonical mockup, 2026-10-06). 고객이 쓴
         문장이 먼저 오고, 그것이 어떤 리뷰인지는 바로 아래 한 줄이 말한다 — Front가 제목과 chip strip을
         두는 순서다. pane은 그대로 위에 둔다: 440px 열에서는 무엇에 대한 pane인지가 먼저다. */
      meta={
        pane ? (
          <Facts>
            <span>{sourceLabel(context?.channelCode ?? null, "REVIEW", detail.rating)}</span>
            <span className="tabular-nums">{detail.writtenOn ?? "날짜 없음"}</span>
          </Facts>
        ) : undefined
      }
      sub={
        pane ? (
          detail.productName ? (
            context?.productId ? (
              <Link to={`/products/${context.productId}`} className="hover:text-ink hover:underline">
                {detail.productName}
              </Link>
            ) : (
              detail.productName
            )
          ) : undefined
        ) : (
          <>
            <Facts>
              <span>{sourceLabel(context?.channelCode ?? null, "REVIEW", detail.rating)}</span>
              <span className="tabular-nums">{detail.writtenOn ?? "날짜 없음"}</span>
              {detail.productName ? (
                context?.productId ? (
                  <Link to={`/products/${context.productId}`} className="hover:text-ink hover:underline">
                    {detail.productName}
                  </Link>
                ) : (
                  <span>{detail.productName}</span>
                )
              ) : null}
            </Facts>
            {/* rail이 들고 있던 두 사실. 여기서는 읽는 것이고, 바꾸는 컨트롤은 아래 판매자의 결정에 있다. */}
            <JudgementProperties detail={detail} decision={decision} className="mt-1.5" />
          </>
        )
      }
      depth={depth}
      title={title}
      /* <b>확인할 일's docked floor, for a review</b> (product-owner decision, 2026-10-03). The queue's
         other two kinds end in a primary at the pane's floor; a review ended in nothing, because the
         judgement this pane holds is recorded in several steps rather than pressed once.

         <p>So the dock carries the way INTO the place those steps belong — the Review workspace this
         same component draws as a page — and no new workflow is built beside it. It is the quiet
         「전체 화면으로」 link this header used to carry, moved to the floor and given the weight of a
         primary: one destination, one control, where the eye already is at the end of the column. */
      dock={
        docked ? (
          <div className="flex items-center border-t border-line pb-6 pt-4">
            <BtnLink to={`/reviews/reply/${detail.id}?from=work`} className="ml-auto">
              {COPY.handleReview}
            </BtnLink>
          </div>
        ) : undefined
      }
      headerAction={
        docked || preview ? undefined : pane ? (
          <Link to={`/reviews/reply/${detail.id}?from=work`} className="rounded font-semibold text-muted hover:text-ink hover:underline">
            전체 화면으로
          </Link>
        ) : detail.sellerAccountId ? (
          <Link
            to={`${reviewRecordPath(detail.sellerAccountId)}?review=${detail.id}`}
            className="rounded font-semibold text-muted hover:text-ink hover:underline"
          >
            리뷰 기록에서 보기
          </Link>
        ) : undefined
      }
      subject={
        // The customer's sentence is the title above; this block is what the RULES said about it.
        //
        // Folded in the pane (Home v3), open on the full page. The order the seller needs is 고객 요청 →
        // 확인한 사실 → 내가 결정할 것 → 그 결정의 실행, and in a 556px column the middle step was costing
        // ~150px of the only fold there is: measured at 1440×900, the filled button that actually does
        // something sat at y=960 while a classification control sat at y=405. Nothing is removed and
        // nothing is summarised away — the facts are one press from where they always were, and the page
        // variant, which has a second column for them, is unchanged.
        // <b>The preview does not fold the one thing it exists to answer.</b> The fold was written for the full
        // pane, where it buys the decision forms their place on the first screen; this reading has no forms, and
        // the fold condition was simply never moved off `pane` when the preview depth arrived. Measured at
        // 1440×900 the panel opened with 「왜 올라왔나요 ›」 closed over ~40px of content while 215 characters of
        // system explanation stood open below it, and 처리 방법 — the question the seller came with — began at
        // y=557 of 900.
        paneEvidenceFolded ? (
          <Disclosure label="왜 올라왔나요" summaryClassName="-ml-2">
            <div className="pt-2">
              <ReviewProblemCard detail={detail} word={word} showBody={false} verdict="controls" />
            </div>
          </Disclosure>
        ) : preview ? (
          // <b>No heading over the answer to the panel's own question.</b> A preview that opens on the
          // customer's sentence and then says 「확인 필요 · 1점」 and one line about it has already said 「왜
          // 확인해야 하는가」; a bold title and a rule over two lines is the weight of a page section.
          <ReviewProblemCard detail={detail} word={word} showBody={false} />
        ) : docked ? (
          /* `verdict="controls"` without the controls, and that is the same ownership rule reading
             correctly: the block that STATES the tier is the one that can change it, and in this
             reading nothing can — 판단과 조치 below prints what is stored. So this block keeps the
             reason and drops the conclusion, exactly as it does beside the forms.

             <p><b>And no label over it any more</b> (canonical mockup, 2026-10-03 — Linear issue
             detail). 「왜 올라왔나요」 named two lines that sit directly under the customer's sentence
             and answer it; a bold label in a 112px column was a step the eye took before the thing it
             came for, and it made the pane's first object a question rather than the evidence. The
             region keeps the name for anything that cannot see the layout. */
          <section aria-label="왜 올라왔나요">
            <ReviewProblemCard detail={detail} word={word} showBody={false} verdict="controls" flat />
          </section>
        ) : (
          /* <b>왜 지금인가는 한 줄이고, 머리말을 갖지 않는다</b> (리뷰 canonical mockup, 2026-10-06).
             문장은 서버가 쓴다 — `ReviewTriageWhyNow`. 화면이 `triage.reason`과 `recommendedAction`을
             이어 붙이면 어느 쪽도 하려던 적 없는 주장이 생기고, 그 문장을 검사할 자리가 없다. 밑줄 하나로
             여기까지가 「무엇이고 왜 지금인가」임을 닫고, 그 아래부터가 준비된 답변이다. */
          <>
            <section aria-label="왜 올라왔나요" className="border-b border-line pb-5">
              <ReviewProblemCard
                detail={detail}
                word={word}
                showBody={false}
                verdict="controls"
                whyNow={detail.whyNow}
                flat
              />
            </section>
            {/* <b>첫 화면의 중심 물건</b> (리뷰 canonical mockup, 2026-10-06 — Front conversation detail).
                Front의 대화 상세에서 면을 가진 것은 제안된 답장 하나이고, 그것은 한 줄짜리 요약 바로 아래
                선다. 같은 자리다: 리뷰 → 왜 지금 한 줄 → 준비된 답변. 아직 아무것도 정하지 않은 리뷰에는
                초안이 없고, 그때는 아래 판단 컨트롤이 그 자리에 온다 — 둘 중 하나는 늘 거기 있다.
                슬롯이 아니라 이 블록 안에 두는 이유: CaseLayout은 자리만 소유하므로(그 docblock) 한
                화면을 위해 새 자리를 파기보다 이미 있는 자리의 순서를 쓰는 편이 가볍다. */}
            {showDraft && replyWork ? (
              <DecisionCard primary>
                <VocItemReplyPrep
                  key={`prep-${replyWork.actionRef}`}
                  accountId={replyAccountId}
                  actionRef={replyWork.actionRef}
                  disposition={decision}
                  onPrepared={() => setPrepared(true)}
                  onOutcomeRecorded={bump}
                  onLocalWork={setLocalWork}
                  headingLevel={2}
                  subjectShownAbove
                  unboxed
                />
                {detail.sellerAccountId ? (
                  <SetAsideFromWork accountId={detail.sellerAccountId} actionRef={replyWork.actionRef} onDone={bump} />
                ) : null}
              </DecisionCard>
            ) : null}
          </>
        )
      }
      decision={
        docked ? (
          /*
            <b>확인할 일's reading records nothing</b> (product-owner decision, 2026-10-03).
            <p>It carried the two judgement controls, the draft panel and 작업에서 제외 — four writes in a
            pane whose own primary is a way out to the screen that owns them. What stands here now is
            what is STORED, read-only, and the one control is the dock's: every change is made in the
            Review workspace, which is this same component as a page and is unchanged.
            <p>The channel's own statement stays. It is a fact about whether this review can be
            answered at all, not a control, and a seller about to press 리뷰 처리하기 is entitled to it
            before they go.
          */
          <>
            <ChannelAnsweredState state={replyWork?.channelReplyState ?? null} />
            <RecordedStatus detail={detail} decision={decision} />
          </>
        ) : preview ? (
          <>
            <ChannelAnsweredState state={replyWork?.channelReplyState ?? null} />
            <section aria-label="현재 판단" className="space-y-1.5">
              <Eyebrow>현재 판단</Eyebrow>
              <p className="break-keep text-sm leading-relaxed text-ink">
                {previewJudgmentTokens(decision, log ?? [], logFailed || log === null)}
              </p>
            </section>
            <p className="break-keep pt-2 text-[12px] leading-relaxed text-muted">{PREVIEW_SAFETY_LINE}</p>
          </>
        ) : (
        <>
          {/* The channel's own statement, said BEFORE anyone decides anything. */}
          <ChannelAnsweredState state={replyWork?.channelReplyState ?? null} />

          {/* <b>Two questions, one card</b> (Review Decision UX v3.2). They were a card each, which cost
              40px of padding and a 14px gap for a separation a hairline makes, and pushed 「AI 초안 준비」
              — the thing this screen exists for — outside the fold at every width the product is used at.

              <p><b>The numerals are gone</b> (product-owner decision, 2026-10-01). 「①」 and 「②」 claimed a
              sequence of two, and the sequence is three: 중요도 → 처리 방법 → 답변 준비, 판단 → 결정 →
              실행 준비. The third step never had a numeral, so the numbering was not describing the flow
              it appeared to describe — it was labelling the two steps that happen to share a card. Order
              and the card boundary say the same thing truthfully, and a structural device that encodes
              nothing true is decoration (docs/ui/reviewnary_ui_system_audit_v1.md §10). Neither control
              moved and neither heading changed its noun. */}
          {/* <b>오른쪽 열은 속성이지 카드가 아니다</b> (리뷰 canonical mockup, 2026-10-05). 240px 열의
              전부가 이 덩어리이므로 테두리는 아무것도 나누지 못하고, 준비된 답변과 같은 모양을 한 번 더
              그릴 뿐이다. 확인할 일의 pane은 그 안에 다른 것들과 함께 서므로 상자를 유지한다. */}
          <DecisionCard primary={decision === null} boxed={!!pane}>
            <div className="space-y-3">
              {/* 판단 — the seller's own judgment of the tier, which does not replace the system's.
                  <b>Folded in the pane.</b> It is a different judgment from 처리 방법 and a secondary one: the
                  triage contract §5-C says it stands BESIDE the system's and changes no ordering, so
                  nothing downstream waits on it. In a 556px column it was 160px standing between
                  확인한 사실 and the action; the page, which has a whole second column for it, keeps it
                  open. The fold's own summary names the tier that stands, so what it holds is visible
                  without opening it. */}
              {paneEvidenceFolded ? (
                <Disclosure
                  label="이 리뷰의 중요도"
                  note={<TriageTierChip tier={detail.sellerCorrection?.correctedTier ?? detail.triage.tier} />}
                  summaryClassName="-ml-2"
                >
                  <div className="pt-1">
                    <SellerCorrectionControls
                      reviewId={detail.id}
                      word={word}
                      systemTier={detail.triage.tier}
                      aiMarked={detail.aiMark !== null}
                      correction={detail.sellerCorrection}
                      onCorrected={bump}
                      headingLevel={3}
                    />
                  </div>
                </Disclosure>
              ) : (
                <Section title="이 리뷰의 중요도" ariaLabel="판매자 판단 영역">
                  <SellerCorrectionControls
                    reviewId={detail.id}
                    word={word}
                    systemTier={detail.triage.tier}
                    aiMarked={detail.aiMark !== null}
                    correction={detail.sellerCorrection}
                    onCorrected={bump}
                    headingLevel={3}
                  />
                </Section>
              )}

              <div className="border-t border-line pt-3">
                {/* 결정 — what to do. Stands on every review the workspace can open: a channel with no reply
                    flow, and a review no account acquired. */}
                <DecisionActionStep
                  reviewId={detail.id}
                  decision={decision}
                  replySupported={replyWork !== null}
                  replyUnavailableReason={detail.replyUnavailableReason}
                  title="처리 방법"
                  onDecided={(next) => {
                    setDecision(next);
                    bump();
                  }}
                  onRecorded={bump}
                />
              </div>
            </div>
          </DecisionCard>

          {/* The draft that follows from 대응 필요. The same panel as every other reply surface: no second
              reply flow, no write this page owns, and the approval boundary untouched.

              <b>On the PAGE it is not here</b> (리뷰 canonical mockup, 2026-10-05): the prepared reply is
              the one object the screen is about, so it stands in the reading column after the evidence it
              was written from, where the eye arrives at it rather than beside it. See `more` below. */}
          {showDraft && replyWork && pane ? (
            <DecisionCard primary>
              {/* The panel names itself 「답변 준비」 — a Section around it said the same word twice (Phase 4). */}
              <VocItemReplyPrep
                key={`prep-${replyWork.actionRef}`}
                accountId={replyAccountId}
                actionRef={replyWork.actionRef}
                disposition={decision}
                onPrepared={() => setPrepared(true)}
                onOutcomeRecorded={bump}
                onLocalWork={setLocalWork}
                headingLevel={2}
                subjectShownAbove
                unboxed
              />
              {/* 작업에서 제외 lived only on the 리뷰 screen's 「내 답변 작업」 list; that list is gone and its rows are
                  확인할 일's now, so the one exit from the to-do stands with the work it takes out. */}
              {detail.sellerAccountId ? (
                <SetAsideFromWork accountId={detail.sellerAccountId} actionRef={replyWork.actionRef} onDone={bump} />
              ) : null}
            </DecisionCard>
          ) : null}

          {/* Why there is no draft — and the two reasons are not the same sentence. The server decides which. */}
          {replyWork === null ? (
            <div className="space-y-1">
              <p className="break-keep text-sm leading-relaxed text-muted">
                {detail.replyUnavailableReason === "NO_SELLER_ACCOUNT"
                  ? "이 채널에 연결된 판매 계정이 없어 답변을 준비할 수 없습니다."
                  : "이 채널에서는 reviewnary가 답변을 작성하지 않습니다."}
              </p>
              <p className="break-keep text-sm leading-relaxed text-muted">판단과 조치는 위에 기록됩니다.</p>
            </div>
          ) : null}
        </>
        )
      }
      context={
        // <b>Folded in the full pane, open on the page and in the preview.</b> 확인한 사실 comes before
        // 판매자 판단 now (Review Decision UX v3.2), and in a 556px column these two sections measure
        // 644px — so keeping them open moved 「AI 초안 준비」 to y=1,351, three folds down. Folded they are
        // 72px and the decision is back on the first screen, which is the promise the new order was
        // allowed to make. Same blocks, same reads, one press; 왜 올라왔나요 has been folded here since
        // Home v3 for the same reason. The PAGE has a second column for them and the Home preview offers
        // no forms to push down, so neither folds.
        paneEvidenceFolded ? (
          <>
            <Disclosure
              label="반복 신호"
              note={
                (context?.repeatedProblems.length ?? 0) > 0 ? `${context!.repeatedProblems.length}` : undefined
              }
              summaryClassName="-ml-2"
            >
              <div className="pt-1">
                <RepeatedSignal
                  problems={context?.repeatedProblems ?? []}
                  failed={contextFailed || context === null}
                  titled={false}
                />
              </div>
            </Disclosure>
            {context ? (
              <Disclosure label="이 상품에 대해 우리가 아는 것" summaryClassName="-ml-2">
                <div className="pt-1">
                  <GroundingOnHand context={context} titled={false} flat />
                </div>
              </Disclosure>
            ) : null}
          </>
        ) : preview ? (
          // <b>근거 — five figures and the way to them.</b> One component draws the whole group, because the
          // numbers it lines up come from two reads (issue memory and the knowledge library) and a grid composed
          // by two components is a grid whose columns can disagree. A failed context read renders nothing at
          // all: a panel that could not see the evidence has nothing to say about it.
          context ? <EvidencePreview context={context} /> : null
        ) : docked ? (
          <>
            <RepeatedSignalObject
              problems={context?.repeatedProblems ?? []}
              failed={contextFailed || context === null}
            />
            {context ? <ProductContextLines context={context} /> : null}
          </>
        ) : (
          /* <b>근거는 접히고, 지금 중요한 사실만 밖에 남는다</b> (리뷰 canonical mockup, 2026-10-06 —
              Front의 `Sources (2)`). 준비된 답변이 이미 「저장된 지식을 근거로 준비했습니다」라고 말하므로
              그 아래 숫자·제목·반복 신호는 그 주장을 펼쳐 보는 것이다. 펼치면 전과 똑같은 블록이고, 접어도
              사라지는 사실은 없다 — 단 하나, 「아직 답하지 않은 확인 필요」는 지금 할 일을 바꾸므로 밖에 선다. */
          <div className="space-y-2">
            <Disclosure
              label="근거와 상품 정보"
              note={context ? `등록된 지식 ${context.knowledge.productSources + context.knowledge.orgSources}건` : undefined}
              summaryClassName="-ml-2"
            >
              <div className="space-y-3 pt-1">
                <RepeatedSignal
                  problems={context?.repeatedProblems ?? []}
                  failed={contextFailed || context === null}
                />
                {context ? <GroundingOnHand context={context} flat showOpenAsk={false} /> : null}
              </div>
            </Disclosure>
            {context ? <OpenAskNote knowledge={context.knowledge} /> : null}
          </div>
        )
      }
      // 기록 stands inside 지금 판단할 것 in a preview, so there is no trailing block for it here.
      more={
        preview ? undefined : docked ? (
          <RecordTrail entries={log ?? []} failed={logFailed || log === null} />
        ) : (
          <>
            {/* 준비된 답변은 위로 올라갔다 — 왜-지금 바로 아래가 그것의 자리다. 여기 남는 것은 조용한
                꼬리다: 이미 일어난 일, 그리고 원문으로 가는 길.

                <b>그리고 그것은 카드가 아니다</b> (리뷰 canonical mockup, 2026-10-06). 실측에서 이 화면의
                「면을 가진 물건」은 둘이었다 — 준비된 답변, 그리고 `DecisionLog`의 상자. 기록은 이 리뷰에
                대해 이미 일어난 일이지 결정할 것이 아니고, 상자가 둘이면 어느 쪽이 이 화면의 물건인지
                눈이 고르지 못한다. 확인할 일의 pane이 쓰던 평평한 꼬리를 페이지도 쓴다 — 같은 행, 같은
                문장(`decisionLogSentence`), 같은 보존 안내. 상자와 세어 붙인 제목만 빠진다. */}
            <RecordTrail entries={log ?? []} failed={logFailed || log === null} titled />
            {/* <b>원문은 어디서 보나 — 리뷰 기록의 pane이 가지고 있던 것</b> (리뷰 canonical mockup,
                2026-10-05). 그 pane은 없어졌고, 능력은 따라왔다. 읽는 흐름의 맨 끝인 이유는 순서가
                그렇기 때문이다: 무엇인지 읽고, 판단하고, 답을 준비한 다음에야 「그런데 원문은」이 온다. */}
            <ReviewLocate
              className="border-t border-line pt-4"
              reviewId={detail.id}
              accountId={detail.sellerAccountId ?? null}
              word={word}
              capability={facts.capability}
              pilotOn={facts.pilot && (facts.capability?.aiTriage ?? false)}
              raised={detail.aiMark !== null || detail.triage.tier === "NEEDS_ATTENTION"}
              recordBehavior={recordLocateBehavior}
            />
          </>
        )
      }
    />
  );
}

/**
 * `/reviews/:accountId/reply/:reviewId` — the address this page used to live at.
 *
 * <b>A redirect, and nothing else.</b> The canonical address is `/reviews/reply/:reviewId`: the
 * account was never part of the authorization, and keeping two live copies of one screen is how the
 * two eventually disagree about what a review is. Links that already exist — a bookmark, a record
 * screen, an old chat artifact — land on the same workspace with their query string intact.
 *
 * The account id is not checked and not passed on. If it named an account this org does not own, the
 * page it used to open answered 404; now the review it names decides, which is the same answer for an
 * id from another org and a better one for every id that was simply redundant.
 */
export function ReviewReplyTaskLegacyEntry() {
  const { reviewId = "" } = useParams();
  const [params] = useSearchParams();
  const search = params.toString();
  return <Navigate replace to={`/reviews/reply/${reviewId}${search ? `?${search}` : ""}`} />;
}

/**
 * 「작업에서 제외」 — the reply to-do's one exit, moved here from the 리뷰 screen's 「내 답변 작업」 (UI/UX v2 Phase 3).
 *
 * <p>The same write, the same idempotency key and the same confirmation sentence it always had: the review leaves
 * the to-do (확인할 일) and nothing else happens — no draft is deleted, nothing is recorded as answered — and it can
 * be restored from 확인할 일's 「지난 답변 작업」.
 */
function SetAsideFromWork({ accountId, actionRef, onDone }: { accountId: string; actionRef: string; onDone: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState<"idle" | "done" | "failed">("idle");

  if (state === "done") {
    return (
      <p role="status" className="mt-3 border-t border-line pt-3 text-sm text-ink" data-testid="reply-work-dismissed-notice">
        리뷰를 답변 작업 목록에서 제외했어요. 저장한 초안과 기록은 그대로 있습니다.
      </p>
    );
  }
  return (
    <div className="mt-3 border-t border-line pt-3">
      {confirming ? (
        <div role="group" aria-label="작업에서 제외 확인" className="space-y-2" data-testid="reply-work-dismiss-confirm">
          <p className="break-keep text-sm text-muted">
            이 리뷰를 확인할 일에서만 제외합니다. 저장한 초안과 기록은 그대로 남고, 답변한 것으로 기록되지 않습니다.
            제외한 리뷰는 확인할 일 아래 &apos;제외한 작업&apos;에서 다시 확인하고 복원할 수 있어요.
          </p>
          <div className="flex flex-wrap gap-2">
            <Btn
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await api.dismissReplyWork(accountId, actionRef, { commandId: crypto.randomUUID() });
                  setState("done");
                  onDone();
                } catch {
                  setState("failed");
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? "제외하는 중…" : "제외하기"}
            </Btn>
            <Btn size="sm" variant="ghost" disabled={busy} onClick={() => setConfirming(false)}>
              취소
            </Btn>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="text-sm text-muted underline underline-offset-2 hover:text-ink"
          data-testid="reply-work-dismiss"
        >
          작업에서 제외
        </button>
      )}
      {state === "failed" ? <p className="mt-2 text-sm text-bad">제외하지 못했습니다. 잠시 후 다시 시도해 주세요.</p> : null}
    </div>
  );
}


/**
 * <b>반복 신호 — the central object of 확인할 일's review pane</b> (canonical mockup, 2026-10-03;
 * reference: Linear's issue detail).
 *
 * <p>The same problems, the same quotes and the same link this pane has always drawn — as ONE bounded
 * object instead of a paragraph. Linear gives a sub-issue group a rule above and below, a header bar
 * carrying the group's name, the thing it is about and its counts, and then one row per item with its
 * metadata right-aligned; the reader takes the whole group in without reading a sentence. What this
 * pane had instead was 「반복 신호」 in a label column beside three quotes stacked over three date lines
 * — six lines of prose for what is, structurally, a list of three.
 *
 * <p><b>Nothing is summarised and no number is composed here.</b> `evidenceCount` is the issue memory's
 * org-wide count, `similar` is the bounded set the context read sends, and the tail line states the
 * relation between the two rather than implying the list is the whole of it. The lifecycle state is NOT
 * drawn: its Korean label is the server's (`ReviewIssueView.lifecycleLabelKo`) and this payload does not
 * carry it, so the alternative would be a raw enum or a map invented on the screen.
 */
function RepeatedSignalObject({ problems, failed }: { problems: ReviewDecisionProblem[]; failed: boolean }) {
  // A screen that could not see the issue memory has nothing to say about it — see {@link RepeatedSignal}.
  if (failed) return null;

  if (problems.length === 0) {
    return (
      <section aria-label="반복 신호" className="border-t border-line pt-4">
        <h3 className="text-sm font-bold text-muted">반복 신호</h3>
        {/* A statement about our RECORDS, never 「반복된 적 없습니다」. */}
        <p className="mt-1 break-keep text-sm leading-relaxed text-muted">아직 반복 문제의 근거로 기록되지 않았습니다.</p>
      </section>
    );
  }

  return (
    <section aria-label="반복 신호" className="border-y border-line" data-testid="pane-repeated-signal">
      {problems.map((problem, index) => {
        const severity =
          problem.severity && problem.severity in SEVERITY_LABEL_KO
            ? SEVERITY_LABEL_KO[problem.severity as IssueSeverity]
            : null;
        // The span the issue memory actually recorded. One date when only one end is known, and nothing
        // at all when neither is — a 「→」 with a missing side would be a range nobody measured.
        const span =
          problem.firstEvidenceOn && problem.lastEvidenceOn
            ? `${problem.firstEvidenceOn} → ${problem.lastEvidenceOn}`
            : (problem.lastEvidenceOn ?? problem.firstEvidenceOn);
        return (
          <div key={problem.issueId} className={index === 0 ? undefined : "border-t border-line"}>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line py-3">
              {/* The group's name sits in the bar, once, so the object reads as one thing. */}
              {index === 0 ? <h3 className="shrink-0 text-sm font-bold text-ink">반복 신호</h3> : null}
              <Link
                to={`/memory/${problem.issueId}`}
                className="break-keep rounded text-sm font-bold text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {problem.title}
                <span className="ml-1 text-brand-700" aria-hidden="true">›</span>
              </Link>
              {severity ? <SignalPill>심각도 {severity}</SignalPill> : null}
              <SignalPill>근거 {problem.evidenceCount}건</SignalPill>
              {problem.dismissed ? <SignalPill>보지 않기로 한 문제</SignalPill> : null}
              {span ? <span className="ml-auto shrink-0 text-xs tabular-nums text-muted">{span}</span> : null}
            </div>

            {problem.similar.length === 0 ? (
              <p className="break-keep py-3 text-sm text-muted">이 문제의 근거는 지금 보고 계신 리뷰뿐입니다.</p>
            ) : (
              <>
                <ul>
                  {problem.similar.map((similar) => (
                    <li
                      key={`${similar.reviewId}-${similar.occurredOn ?? ""}`}
                      className="flex items-center gap-3 border-b border-line py-2 pl-6"
                    >
                      {/* <b>The rows carry no bullet</b> (product-owner decision, 2026-10-04). An empty
                          ring in front of every row is the shape a radio or a checkbox has, and these
                          rows select nothing — they are evidence. The indent that stood behind it does
                          the aligning on its own, which is all it was ever doing.
                          <p>One line per review. The quote is masked at read time and null when masking
                          took the whole of it — then the row says so rather than drawing an empty cell. */}
                      <span className={`min-w-0 flex-1 truncate text-sm ${similar.quote ? "text-ink" : "text-muted"}`}>
                        {similar.quote ?? "내용이 가려진 근거입니다"}
                      </span>
                      <span className="shrink-0 text-xs tabular-nums text-muted">
                        {`${similar.rating === null ? "평점 없음" : `★${similar.rating}`} · ${similar.occurredOn ?? "날짜 없음"}`}
                      </span>
                    </li>
                  ))}
                </ul>
                {/* What the rows are, against what the memory holds — so the list is never read as the set. */}
                <p className="break-keep py-2 text-xs leading-relaxed text-muted">
                  근거 {problem.evidenceCount}건 가운데 최근 {problem.similar.length}건입니다.
                </p>
              </>
            )}
          </div>
        );
      })}
    </section>
  );
}

/** One count or qualifier in the signal object's header bar. Quiet by design: the bar's subject is the problem. */
function SignalPill({ children }: { children: ReactNode }) {
  return (
    <span className="shrink-0 whitespace-nowrap rounded-lg bg-canvas px-2 py-0.5 text-xs tabular-nums text-muted">
      {children}
    </span>
  );
}

/**
 * <b>상품 맥락 — secondary evidence, read as context rather than as a table</b> (canonical mockup,
 * 2026-10-03).
 *
 * <p>The same figures {@link GroundingOnHand} prints, in sentences, at a reading measure, one step
 * quieter than the object above: four label/value rows beside a 112px gutter made the pane's second
 * half look like the specification of the first.
 *
 * <p><b>What this reading leaves to other surfaces.</b> The product's name is the header's (one fact,
 * one place — the same rule the preview reading follows), and 답변 기준 보기 / 상품 화면 열기 are the
 * full case's: this pane's one door is the dock, and 지식 and 상품 are both one press from the rail.
 * 「여기 있는 숫자는 등록된 자료의 수입니다」 is not repeated because the sentence itself now says
 * 등록된.
 */
function ProductContextLines({ context }: { context: ReviewDecisionContext }) {
  const { knowledge, productSignal } = context;
  // 이름은 있는데 상품이 없다 — see {@link GroundingOnHand}. Why the figures above are absent.
  const unlinked = context.productId == null && context.productName != null;
  return (
    <section aria-label="이 상품에 대해 우리가 아는 것" className="border-t border-line pt-4">
      <h3 className="text-sm font-bold text-muted">상품 맥락</h3>
      <p className="mt-1 max-w-thread break-keep text-sm leading-relaxed text-muted">
        {/* Null is not zero: a review bound to no product has no product to count for. */}
        {productSignal ? (
          <>
            리뷰 <strong className="font-semibold tabular-nums text-ink">{productSignal.reviews}건</strong> 가운데 부정이{" "}
            <strong className="font-semibold tabular-nums text-ink">{productSignal.negativeReviews}건</strong>입니다.{" "}
          </>
        ) : null}
        답할 때 쓸 수 있는 자료로 등록된 상품 지식 {knowledge.productSources}건과 회사 운영 기준{" "}
        {knowledge.orgSources}건이 있습니다.
        {knowledge.productTitles.length > 0 ? ` ${knowledge.productTitles.join(" · ")}` : ""}
      </p>
      {unlinked ? (
        <p className="mt-2 max-w-thread break-keep text-sm leading-relaxed text-muted">
          판매 채널에서 읽은 상품명입니다. 아직 상품 목록의 상품과 연결되지 않아 상품별 수치는 표시하지 않습니다.
        </p>
      ) : null}
      {/* The honest half: a thin draft usually has a reason, and it is one click from being fixed. */}
      {knowledge.openAsks > 0 ? (
        <p className="mt-2 max-w-thread break-keep text-sm leading-relaxed text-warn">
          아직 답하지 않은 확인 필요가 {knowledge.openAsks}건 있습니다. 채우면 다음 초안이 더 말할 수 있습니다.
        </p>
      ) : null}
    </section>
  );
}

/**
 * <b>판단과 조치 — a short status summary, and nothing to press</b> (product-owner decision,
 * 2026-10-03).
 *
 * <p>Three terms in a `dl` beside a 112px gutter were the same table 상품 맥락 was, directly under it;
 * two lines say the same three stored values and read as a status rather than as a record sheet. The
 * values are unchanged: the seller's corrected tier when there is one, the stored disposition with
 * 판단 전 when there is none, and the system's own tier beside the correction that stands with it.
 *
 * <p>The channel's answered state is NOT part of this summary — it is {@link ChannelAnsweredState}'s,
 * rendered for ANSWERED alone, and 「채널 답변은 아직 등록되지 않았습니다」 would be the channel's
 * silence dressed as a statement.
 */
function RecordedStatus({
  detail,
  decision,
}: {
  detail: ChannelReviewDetailView;
  decision: TriageDisposition | null;
}) {
  const correction = detail.sellerCorrection;
  return (
    <section aria-label="판단과 조치" className="border-t border-line pt-4">
      <h3 className="text-sm font-bold text-muted">판단과 조치</h3>
      <JudgementProperties detail={detail} decision={decision} className="mt-1 text-base leading-snug" />
      <p className="mt-1 max-w-thread break-keep text-sm leading-relaxed text-muted">
        시스템 판단은 {TRIAGE_TIER_LABEL[detail.triage.tier]}입니다.
        {correction ? ` 판매자 수정으로 ${TRIAGE_TIER_LABEL[correction.correctedTier]}가 함께 기록돼 있습니다.` : ""}
      </p>
    </section>
  );
}

/**
 * <b>이 리뷰에 대해 서 있는 두 가지</b> — 중요도와 처리 상태, 한 줄.
 *
 * <p>두 자리에서 읽힌다. 리뷰 페이지의 머리말에서는 rail이 들고 있던 속성으로(리뷰 canonical mockup,
 * 2026-10-06), 확인할 일의 pane에서는 「판단과 조치」의 첫 줄로. 한 문장을 두 벌로 쓰면 둘 중 하나만
 * 고쳐지는 날이 오고, 같은 리뷰에 대해 두 화면이 다른 말을 한다.
 *
 * <p><b>읽는 것이지 바꾸는 것이 아니다.</b> 바꾸는 컨트롤은 {@code SellerCorrectionControls}와
 * {@code DecisionActionStep}이고, 둘 다 「판매자의 결정」 안에 그대로 있다. 보이는 값이 판매자가 고친
 * 것이면 그렇다고 말한다 — 시스템이 무엇이라고 했는지는 그 컨트롤이 제 자리에서 말한다.
 */
function JudgementProperties({
  detail,
  decision,
  className = "",
}: {
  detail: ChannelReviewDetailView;
  decision: TriageDisposition | null;
  className?: string;
}) {
  const correction = detail.sellerCorrection;
  return (
    <p className={`break-keep text-sm leading-relaxed text-muted ${className}`}>
      중요도 <strong className="font-bold text-ink">{TRIAGE_TIER_LABEL[correction?.correctedTier ?? detail.triage.tier]}</strong>{" "}
      {correction ? "판매자 수정" : "시스템 판단"}
      <span aria-hidden="true"> · </span>
      처리 상태 <strong className="font-bold text-ink">{triageDispositionLabel(decision)}</strong>
    </p>
  );
}

/**
 * <b>기록 — the trail as activity</b> (canonical mockup, 2026-10-03; reference: Linear's issue detail).
 *
 * <p>The same rows {@code DecisionLog} draws, from the same `decisionLogSentence`, without the box and
 * the counted heading: an issue's history is the quietest thing on its screen and it closes the column.
 * Newest first, an entry this build cannot name is not drawn, and empty is a real state that says so —
 * all three are the log's rules and none of them changes here.
 */
function RecordTrail({
  entries,
  failed,
  titled = false,
}: {
  entries: ReviewDecisionLogEntry[];
  failed: boolean;
  /**
   * 이름을 그린다. pane에서는 이 블록이 열의 마지막이라 제목 없이도 무엇인지 읽히지만, 페이지에서는
   * 위에 접힌 근거와 아래 원문 보기 사이에 서므로 이름이 있어야 활동 기록으로 읽힌다 — 승인된 mockup의
   * 작은 「기록」 라벨이 그 자리다. 숫자는 붙이지 않는다: 개수는 행이 이미 말한다.
   */
  titled?: boolean;
}) {
  if (failed) return null;
  const rows = entries
    .map((entry) => ({ entry, sentence: decisionLogSentence(entry) }))
    .filter((row): row is { entry: ReviewDecisionLogEntry; sentence: string } => row.sentence !== null);

  return (
    <section aria-label="기록" className="border-t border-line pt-3">
      {titled ? <h2 className="mb-1 text-sm font-bold text-muted">기록</h2> : null}
      {rows.length === 0 ? (
        <p className="break-keep text-sm leading-relaxed text-muted">아직 이 리뷰에 기록된 판단이 없습니다.</p>
      ) : (
        <ul>
          {rows.map(({ entry, sentence }, index) => (
            <li key={`${entry.kind}-${entry.at}-${index}`} className="flex items-baseline gap-3 py-1">
              <span aria-hidden="true" className="h-3 w-3 shrink-0 translate-y-0.5 rounded-full bg-canvas" />
              <span className="min-w-0 flex-1 break-keep text-sm leading-relaxed text-muted">{sentence}</span>
              <span className="shrink-0 text-xs tabular-nums text-muted">{kstDate(entry.at)}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-2 max-w-thread break-keep text-xs leading-relaxed text-muted">{DECISION_LOG_DISCLOSURE}</p>
    </section>
  );
}
