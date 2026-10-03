import { useCallback, useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { isAxiosError } from "axios";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { Disclosure } from "../../components/ui/Disclosure";
import { WorkFlowCard } from "../../components/ui/WorkFlowCard";
import { Facts } from "../../components/ui/ObjectRow";
import { CaseBlock, CaseLayout, CaseQuote, type CaseVariant, type PaneDepth } from "../../components/workspace/CaseLayout";
import { inquiryReading } from "../../lib/inquiryNextAction";
import { api } from "../../lib/apiClient";
import { actionKo, subjectFallback } from "../../lib/customerOperations";
import { COPY, draftSendWord, decisionOf, elapsedLabel, elapsedSource, photoWord, shortDate, sourceLabel, waitDays } from "../../lib/copy/customerOps";
import { Status } from "../../components/ui/Status";
import { WORK_STATE, type WorkStateKey } from "../../lib/workState";
import { plainText } from "../../lib/plainText";
import type { OperationsCaseDetail, OperationsCaseNeed } from "../../lib/customerOperationsTypes";

/** The queue the case was opened from, carried in router state by the Home list — never re-read here. */
export interface CaseQueueState {
  caseIds: string[];
}

type Receipt = { scope: string; content: string };

/**
 * <b>One case</b> (Customer Operations v3.1): 「자동 확인 → 내 확인 필요」 first, then what the customer wrote and
 * what was checked, the evidence folded, and — in the right column — the one thing the seller does here.
 *
 * <b>Nothing here sends anything.</b> Teaching writes company knowledge and re-drafts; editing the draft saves a
 * version; correcting records the seller's judgement. Sending stays on the inquiry/review screen that owns it.
 */
export function OperationsCase() {
  const { caseId = "" } = useParams();
  const location = useLocation();
  const queue = (location.state as CaseQueueState | null)?.caseIds ?? null;
  return <OperationsCaseView caseId={caseId} variant="page" queue={queue} />;
}

/**
 * The case itself, in either reading of {@link CaseLayout}: the full page this route opens, or the right-hand pane
 * of 확인할 일 and 오늘. Same reads, same writes, same words — only the placement differs, so a seller who decides a
 * case in the pane and one who opens it on its own page are looking at the same screen.
 */
export function OperationsCaseView({
  caseId,
  variant,
  depth = "full",
  now,
  queue = null,
}: {
  caseId: string;
  variant: CaseVariant;
  /**
   * The clock this case is read against. Threaded from the list that opened it so one item cannot be
   * dated by two 「now」s — the same reason {@code elapsedSource} exists one layer down. Omitted, it is
   * the real one, which is what every page caller wants.
   */
  now?: Date;
  /**
   * {@link PaneDepth}. 「preview」 shows the recommended draft as TEXT and leaves every form — applying
   * it, correcting the case, teaching the missing fact — to the full case, which is unchanged.
   */
  depth?: PaneDepth;
  queue?: string[] | null;
}) {
  const [detail, setDetail] = useState<OperationsCaseDetail | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  /* <b>Two pieces of state live here now</b> (확인할 일 canonical, 2026-10-03). The controls that open
     the draft editor and the 처리 변경 form moved to the pane's docked floor, and the forms they open
     stayed in the body where they are read. A control and the form it opens cannot both own the flag,
     so the view holds it and hands it to both. Nothing else about either card changed. */
  const [editingDraft, setEditingDraft] = useState(false);
  const [correcting, setCorrecting] = useState(false);

  const load = useCallback(async () => {
    try {
      setDetail(await api.getOperationsCase(caseId));
    } catch {
      setDetail(null);
    }
  }, [caseId]);

  useEffect(() => {
    setDetail(undefined);
    setReceipt(null);
    setError(null);
    void load();
  }, [load]);

  if (detail === undefined) {
    return <p className="text-muted">불러오는 중…</p>;
  }
  if (detail === null) {
    return <p className="text-muted">해당 건을 찾을 수 없습니다.</p>;
  }

  const applied = (next: OperationsCaseDetail) => {
    setDetail(next);
    setError(null);
  };
  const failed = (e: unknown) => {
    const message = isAxiosError(e) ? (e.response?.data as { message?: string } | undefined)?.message : undefined;
    setError(message ?? COPY.saveFailed);
  };

  const body = plainText(detail.body);
  const title = detail.title?.trim() || firstLine(body) || subjectFallback(detail.subjectKind);
  /* <b>The same source and the same word the list used</b> (elapsed-time contract, 2026-10-02). This
     read `receivedOn` alone and the list read `openedAt` alone, so one case said 8일 대기 in the row and
     9일 대기 in this pane. Both sides now hand both facts to `elapsedSource`, and a review says 전. */
  const elapsedAt = elapsedSource(detail.receivedOn, detail.openedAt);
  const wait = elapsedLabel(elapsedAt, detail.subjectKind, now);
  const index = queue ? queue.indexOf(caseId) : -1;
  const showTeach = Boolean(detail.gap) && !receipt;
  const pane = variant === "pane";
  const preview = pane && depth === "preview";
  /**
   * <b>확인할 일's canonical reading</b> (2026-10-02). The full pane is 확인할 일's detail and nothing else:
   * the Home draws this component at {@code preview} depth and the case screen draws it as a page, so this
   * flag changes exactly the surface the canonical mockup is the target for — and leaves the Home's frozen
   * baseline and the full case page untouched.
   */
  const canonical = pane && depth === "full";
  const workState = WORK_STATE[caseWorkState(detail)];
  const why = canonical ? whyNow(detail, now) : [];
  // What 확인 항목 would actually draw — asked here so the block can decline to exist rather than drawing
  // a card around 「조사 기록 없음」. Same predicate `Checks` uses; no second definition of «empty».
  const hasChecks = detail.investigated.length > 0 || Boolean(showTeach && detail.gap?.missingSubject);
  const noteUnderChecks = !detail.summary && Boolean(detail.reasonNote) && !settled(detail);
  // Same question for 근거, and for the same reason: `Evidence` renders nothing when the draft's own
  // citation is already on screen, and a node that renders nothing still holds a grid track open.
  const hasEvidence = detail.knowledgeUsed.length > 0 || (detail.draft?.evidence.length ?? 0) === 0;
  // The title is the customer's first line. When that line IS the whole message, the subject block would print the
  // same sentence a second time one block below — measured on the demo org, 「교환 신청은 언제까지 가능한가요?」 was
  // both the h1 and the only line of 문의 내용. The block stays whenever it adds anything: more text, or photos.
  const bodyAddsSomething = Boolean(body) && body.trim() !== title.trim();
  /* <b>The customer's words ARE the heading</b> (확인할 일 canonical, 2026-10-03). The pane drew the
     subject line as its title and the body as a quotation under it, so a case named 「문의 드립니다」
     opened under the row that had just said 「수령한 상품을 교환하려면…」. {@link inquiryReading} is the
     rule 문의 already ships: the body leads, and the subject line is kept beside it only while it adds
     something a generic 「문의 드립니다」 does not. One rule, two screens — not a second opinion about
     which string is the question. */
  const reading = canonical ? inquiryReading({ title: detail.title, snippet: body }, (t) => (t ?? "").trim()) : null;
  const hasMedia = Boolean(detail.media && detail.media.length > 0);

  return (
    <CaseLayout
      variant={variant}
      depth={depth}
      decisionLabel={COPY.mineLabel}
      nav={
        pane ? undefined : (
          <nav aria-label="위치" className="flex items-center gap-2 text-sm text-muted">
            <Link to="/" className="rounded hover:text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
              {COPY.homeTitle}
            </Link>
            <span aria-hidden="true">›</span>
            <Link
              to="/customer-operations/cases"
              className="rounded font-semibold text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              {COPY.listTitle}
            </Link>
            {queue && index >= 0 ? <Pager queue={queue} index={index} /> : null}
          </nav>
        )
      }
      meta={
        canonical ? (
          /* <b>The state leads, and it owns the word</b> (canonical mockup, 2026-10-02). The header used to
             open with the channel, so the pane said what the item WAS where the row beside it said what to
             do with it. Same badge, same table, same fact as the row's lead column — one object, one word.
             The wait keeps its own mark because it is the other thing the mockup's header carries, and it
             is a claim about urgency rather than about provenance. */
          /* <b>A line, not a badge</b> (visual target, 2026-10-02). The tinted mark was the loudest thing
             in a 576px column whose subject is one customer's sentence, and it names the same state the
             row 40px to its left already names. At metadata size the word still carries it — and the
             wait drops its warn colour with it: 「9일 동안 답변이 등록되지 않았습니다」 stands spelled out
             in 왜 지금 볼 일인가 two blocks below, so colouring the short form made one fact shout twice. */
          <span className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
            <Status tone={workState.tone} variant="quiet">
              {workState.text}
            </Status>
            <span aria-hidden="true" className="h-3 w-px bg-line" />
            <span>{sourceLabel(detail.channelNameKo, detail.subjectKind, detail.rating)}</span>
            {wait ? (
              <>
                <span aria-hidden="true" className="h-3 w-px bg-line" />
                <span className="tabular-nums">{wait}</span>
              </>
            ) : null}
          </span>
        ) : (
          <Facts>
            <span>{sourceLabel(detail.channelNameKo, detail.subjectKind, detail.rating)}</span>
            {wait ? <span className="tabular-nums">{wait}</span> : null}
          </Facts>
        )
      }
      sub={
        reading
          ? [detail.productName, reading.titleContext ? `제목 「${reading.titleContext}」` : null]
              .filter(Boolean)
              .join(" · ") || undefined
          : (detail.productName ?? undefined)
      }
      reading={canonical ? "document" : "default"}
      /* <b>One item, one name</b> (product-owner decision, 2026-10-02). An inquiry's `title` is the
         SUBJECT line, and 확인할 일's row stopped leading with it in the same change that put the
         customer's body on the wire — so a row named 「수령한 상품을 교환하려면…」 opened a pane
         headlined 「문의 드립니다」, and a seller had to read the paragraph under it to be sure they had
         opened what they clicked. In the document reading the customer's own words ARE the heading, so
         the subject line is kept for assistive technology and not drawn a second time above them.

         <p>Only where the body actually adds something: an inquiry whose body IS its title (and every
         review, whose title is its body) keeps its heading, because there the sentence would otherwise
         be drawn nowhere. */
      titleHidden={false}
      title={reading ? reading.question : title}
      headerAction={
        preview ? undefined : pane ? (
          <Link
            to={`/customer-operations/cases/${caseId}`}
            className="rounded font-semibold text-muted hover:text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            전체 화면으로
          </Link>
        ) : (
          <Link
            to={detail.to}
            className="rounded font-semibold text-brand-700 hover:text-brand-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            {COPY.original} ↗
          </Link>
        )
      }
      summary={
        canonical ? (
          why.length > 0 ? (
            /* <b>Sentences under a label, with nothing drawn beside them</b> (visual target, 2026-10-02).
               Each fact used to carry a clock/document/speech icon. They decorate: the facts are already
               three short sentences in a named region, and a glyph that repeats what the sentence says is
               the 「AI 기능처럼 보이는 장식」 the target rules out. The first fact — how long the customer
               has waited — keeps the ink; the rest qualify it. */
            <section aria-label="왜 지금 볼 일인가" className="border-t border-line pt-6">
              <h3 className="text-xs font-semibold text-muted">왜 지금 볼 일인가</h3>
              <ul className="mt-3 space-y-2">
                {why.map((fact, i) => (
                  <li
                    key={fact.text}
                    className={`min-w-0 break-keep text-sm leading-relaxed ${i === 0 ? "text-ink" : "text-muted"}`}
                  >
                    {fact.text}
                  </li>
                ))}
              </ul>
            </section>
          ) : null
        ) : (
          <WorkFlowCard ariaLabel="자동 확인과 내가 확인할 일" {...flowCells(detail, receipt !== null)} />
        )
      }
      notice={
        error ? (
          <p className="break-keep text-sm text-bad" role="alert">
            {error}
          </p>
        ) : null
      }
      subject={
        /* In the canonical reading the body is the heading above, so the quotation block would be the
           same sentence twice. Photos still need somewhere to stand. */
        (canonical ? hasMedia : bodyAddsSomething || hasMedia) ? (
          /* In the canonical reading the customer's words sit directly under their own sentence, with no
             heading between: the title IS the first line of this body, so a 「문의 내용」 rule between the two
             was a boundary drawn through one quotation. The heading stays everywhere else. */
          <CaseBlock
            title={canonical ? undefined : detail.subjectKind === "INQUIRY" ? COPY.inquiryBody : COPY.reviewBody}
            ariaLabel={detail.subjectKind === "INQUIRY" ? COPY.inquiryBody : COPY.reviewBody}
            tone="subject"
          >
            {/* The channel's own markup is stripped HERE and nowhere else — the stored row keeps what the channel
                sent, and the same helper the 문의 화면 has used since Demo UX Polish v1 does the stripping, so the
                two screens cannot show the same customer different words. */}
            {bodyAddsSomething && !canonical ? <CaseQuote>{body}</CaseQuote> : null}
            {hasMedia ? (
              <ul className="mt-4 flex flex-wrap gap-3" aria-label="고객이 올린 사진">
                {detail.media!.map((m) => (
                  <MediaItem key={m.ordinal} caseId={caseId} media={m} />
                ))}
              </ul>
            ) : null}
          </CaseBlock>
        ) : null
      }
      decision={
        preview ? (
          <DraftPreview detail={detail} />
        ) : (
        <>
          {receipt ? <TaughtReceipt receipt={receipt} redrafted={Boolean(detail.draft)} /> : null}
          {showTeach ? (
            <TeachCard
              caseId={caseId}
              detail={detail}
              onTaught={(next, r) => {
                applied(next);
                setReceipt(r);
              }}
              onFailed={failed}
            />
          ) : null}
          {detail.draft ? (
            <DraftCard
              caseId={caseId}
              detail={detail}
              primary={!showTeach}
              flat={canonical}
              docked={canonical}
              editing={editingDraft}
              setEditing={setEditingDraft}
              onApplied={applied}
              onFailed={failed}
            />
          ) : null}
          <CorrectionCard
            caseId={caseId}
            detail={detail}
            flat={canonical}
            docked={canonical}
            open={correcting}
            setOpen={setCorrecting}
            onApplied={applied}
            onFailed={failed}
          />
        </>
        )
      }
      /* <b>확인할 일's docked floor</b> (product-owner decision, 2026-10-03). The same two controls the
         draft carried and the same way out the 처리 변경 line carried — moved, not added, and only on
         this one reading. The full case page and the Home's preview are untouched.

         <p>The warning slot the mockup drew is the inquiry pane's: {@code answerStateNote} comes from
         the inquiry read, and the case read has no field for it. It is left out rather than invented. */
      dock={
        canonical ? (
          <div className="border-t border-line pb-6 pt-4">
            {detail.draft && !editingDraft ? (
              <div className="flex items-center gap-3">
                <Btn variant="outline" onClick={() => setEditingDraft(true)}>
                  {COPY.edit}
                </Btn>
                <BtnLink to={detail.to} className="ml-auto">
                  {COPY.toSend} ↗
                </BtnLink>
              </div>
            ) : null}
            {correcting ? null : (
              <div className={`flex items-center gap-3 text-sm text-muted ${detail.draft && !editingDraft ? "mt-4" : ""}`}>
                <span className="break-keep">{COPY.otherHandling}</span>
                <button
                  type="button"
                  onClick={() => setCorrecting(true)}
                  className="ml-auto whitespace-nowrap rounded font-semibold text-brand-700 hover:text-brand-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                >
                  {COPY.changeHandling}
                </button>
              </div>
            )}
          </div>
        ) : undefined
      }
      context={
        // <b>A card only when there is something in it</b> (Review Decision UX v3.2). On a case with no
        // investigation record and no note this block was a 130px card whose whole content was
        // 「조사 기록 없음」 — a box drawn to say that nothing is in it. The sentence is not a finding and
        // not a failure; it is the ordinary state of a case the rules could answer from stored knowledge,
        // and the 자동 확인 line above already says what was done.
        canonical ? (
          detail.knowledgeUsed.length > 0 ? (
            <CaseBlock title="확인한 내용" flat>
              <CheckedTable detail={detail} />
            </CaseBlock>
          ) : hasChecks || noteUnderChecks ? (
            <CaseBlock title="확인한 내용" flat>
              <Checks detail={detail} gapOpen={showTeach} />
              {noteUnderChecks ? <p className="mt-3 break-keep text-sm text-muted">{detail.reasonNote}</p> : null}
            </CaseBlock>
          ) : null
        ) : hasChecks || noteUnderChecks ? (
          <CaseBlock title={COPY.checks} flat>
            <Checks detail={detail} gapOpen={showTeach} />
            {noteUnderChecks ? <p className="mt-3 break-keep text-sm text-muted">{detail.reasonNote}</p> : null}
          </CaseBlock>
        ) : null
      }
      /* The canonical reading already draws `knowledgeUsed` in full under 확인한 내용, so the folded copy of
         the same list is not drawn under it — one list, once. Where there is no knowledge to draw the block
         behaves exactly as it does everywhere else. */
      more={hasEvidence && !(canonical && detail.knowledgeUsed.length > 0) ? <Evidence detail={detail} /> : null}
    />
  );
}

/**
 * <b>The final case state is the canonical truth of the two top cells.</b> A case that Reviewnary settled — closed as
 * needing nothing, or put under watch — says so, with the latest judgement under it: the Agent's summary when the Agent
 * decided, otherwise the note of the rule that stands. Text written at an earlier stage (the rule's detection before
 * the Agent overruled it, the model's recommendation on a case it then closed) is not shown in its place, and the
 * seller's cell says there is nothing for them to do rather than asking for a decision nobody needs.
 */
function settled(detail: OperationsCaseDetail): "AUTO_RESOLVED" | "MONITORING" | null {
  return detail.disposition === "AUTO_RESOLVED" || detail.disposition === "MONITORING" ? detail.disposition : null;
}

function flowCells(detail: OperationsCaseDetail, taught: boolean) {
  const state = settled(detail);
  if (state) {
    const judgement = detail.decidedBy === "AGENT" ? detail.summary ?? detail.reasonNote : detail.reasonNote ?? detail.summary;
    return {
      done: {
        label: COPY.caseChecked,
        value: state === "AUTO_RESOLVED" ? COPY.resolved : COPY.monitoring,
        phrase: true,
        line: judgement ? <span>{judgement}</span> : undefined,
      },
      mine: { label: COPY.mineLabel, value: COPY.none, phrase: true },
    };
  }
  const why = detail.whyDecisionNeeded ?? detail.recommendedAction;
  return {
    done: {
      label: COPY.caseChecked,
      value: doneHeadline(detail, taught),
      phrase: true,
      line: detail.summary ? <span>{detail.summary}</span> : undefined,
    },
    // <b>Only when nothing below says it better</b> (Review Decision UX v3.2). With a draft written, the
    // draft card IS 「내가 확인할 일」 — it names the recommendation and carries the controls that act on
    // it — so repeating it here was a second statement of the same thing, and the sentence under it
    // (「고객에게 무엇을 말하거나 약속할지는 판매자가 정합니다」) is a fact about the product, not about
    // this customer. With no draft there is no such block, and the cell is the only place the seller is
    // told what they are being asked to decide, so it stands.
    mine: detail.draft
      ? undefined
      : {
          label: COPY.mineLabel,
          value: detail.open
            ? decisionOf(detail.recommendedActionType) ?? actionKo(detail.recommendedActionType) ?? "판단 필요"
            : COPY.closed,
          phrase: true,
          line: detail.open && why ? <span>{why}</span> : undefined,
        },
  };
}

/** The left cell's headline, from the state the case is in — never a sentence Reviewnary writes about itself. */
function doneHeadline(detail: OperationsCaseDetail, taught: boolean): string {
  if (detail.draft) return taught ? COPY.redrafted : "답변 초안 작성";
  if (detail.gap?.missingSubject) return `${detail.gap.missingSubject} 정보 없음`;
  if (detail.gap) return "답변 정보 없음";
  const found = detail.investigated.filter((i) => i.results > 0).length;
  return found > 0 ? `${detail.investigated.length}개 항목 확인` : "확인할 근거 없음";
}

function firstLine(body: string | null): string | null {
  const line = body?.split(/\r?\n/).find((l) => l.trim().length > 0)?.trim();
  if (!line) return null;
  return line.length > 60 ? `${line.slice(0, 60)}…` : line;
}

function Checks({ detail, gapOpen }: { detail: OperationsCaseDetail; gapOpen: boolean }) {
  const missing = gapOpen && detail.gap?.missingSubject ? detail.gap.missingSubject : null;
  if (detail.investigated.length === 0 && !missing) {
    return <p className="text-sm text-muted">{COPY.noInvestigation}</p>;
  }
  return (
    <ul className="flex flex-wrap gap-1.5 text-sm">
      {detail.investigated.map((item) => (
        <li key={item.label}>{item.results > 0 ? <Found>{`${item.label} ${item.results}`}</Found> : <Missing>{`${item.label} 없음`}</Missing>}</li>
      ))}
      {missing ? (
        <li>
          <Missing>{`${missing} 기준 없음`}</Missing>
        </li>
      ) : null}
    </ul>
  );
}

function Found({ children }: { children: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg bg-[#F4F5F7] px-2.5 py-1 text-muted">
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-[#1F9D55]" />
      {children}
    </span>
  );
}

function Missing({ children }: { children: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg bg-[#FFF3E4] px-2.5 py-1 font-semibold text-warn">
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full border-[1.5px] border-[#D97706]" />
      {children}
    </span>
  );
}

/**
 * <b>The leading status of one case — the same contract the row in the list wears</b> (canonical mockup
 * semantic correction, 2026-10-02).
 *
 * <p>The correction it implements: <b>an item that already has a prepared answer is never 「답변 필요」.</b>
 * 답변 필요 is a statement about the CUSTOMER (nobody has answered them) and it was being drawn over items
 * reviewnary had already written an answer for, so the one word a seller reads first told them to start
 * work that was done. The word comes from {@link WORK_STATE} and from the fact that proves it — a draft
 * VERSION, the same {@code draft} this screen renders below — never from a phase and never inferred.
 *
 * <p>Deliberately the same expression as {@code caseWorkRow}'s: the list and the pane are one object seen
 * twice, and a second derivation is how they come to disagree.
 */
function caseWorkState(detail: OperationsCaseDetail): WorkStateKey {
  return detail.draft ? "DRAFT_READY" : "NEEDS_LOOK";
}

/**
 * <b>왜 지금 볼 일인가</b> (canonical mockup, 2026-10-02) — why this item is in front of the seller, as
 * facts rather than as reviewnary's own workflow vocabulary.
 *
 * <p>It replaces the two-cell 자동 확인 / 내가 확인할 일 strip in the full pane. The strip's left cell said
 * 「답변 초안 작성」 — a stage name — above the sentence that actually carried the reason, and its right cell
 * restated the lead badge.
 *
 * <p><b>Every line is optional and drawn only when the case holds it</b> (semantic correction 3). There is
 * no filler line: a case with no summary and no recommendation draws the waiting line alone, and one with
 * nothing at all draws no block. Nothing here is composed out of a number this screen does not have —
 * which is why the mockup's third line (「최근 7일 동안 유사 문의 N건」) is absent: no read on this screen
 * returns it.
 */
/**
 * <b>왜 지금 볼 일인가 states facts the seller can check, and nothing about our own ordering</b>
 * (product-owner decision, 2026-10-02).
 *
 * <p>The case `summary` is composed by {@code CaseFromResolution.summaryFor}, which appends
 * 「(확인한 요청 2건 중 가장 먼저 해결해야 하는 항목 기준입니다.)」 whenever a case resolved more than
 * one of the customer's requests. That clause is bookkeeping about how reviewnary ranked the parts of
 * one question — the seller cannot verify it, cannot act on it, and it is the longest thing in the
 * block. The head sentence it qualifies is the fact.
 *
 * <p><b>Stripped here rather than at the composer.</b> The clause is already stored on every case the
 * demo org holds, so stopping the composer would leave the screen unchanged and need a data
 * correction to finish; and the same `summary` is read by 오늘 and the 문의 screens, which this
 * change is not scoped to. A TRAILING parenthetical only — nothing mid-sentence is touched, and a
 * summary without one is returned exactly as it arrived.
 */
function statedFact(summary: string | null | undefined): string | null {
  const text = summary?.trim();
  if (!text) return null;
  return text.replace(/\s*\([^()]*\)\s*$/, "").trim() || null;
}

function whyNow(detail: OperationsCaseDetail, now?: Date): { icon: "clock" | "doc" | "chat"; text: string }[] {
  const facts: { icon: "clock" | "doc" | "chat"; text: string }[] = [];
  const days = waitDays(elapsedSource(detail.receivedOn, detail.openedAt), now);
  if (days !== null && days > 0) {
    facts.push({
      icon: "clock",
      // 대기 is a claim about somebody waiting for an answer, and it is not true of a review — the same
      // distinction `sinceLabel` draws for the list's right-hand column.
      text:
        detail.subjectKind === "INQUIRY"
          ? `${days.toLocaleString("ko-KR")}일 동안 답변이 등록되지 않았습니다.`
          : `${days.toLocaleString("ko-KR")}일 전에 등록된 리뷰입니다.`,
    });
  }
  const summary = statedFact(detail.summary);
  if (summary) facts.push({ icon: "doc", text: summary });
  /* <b>Not while an answer stands</b> — the same rule {@code flowCells} keeps for the cell this block
     replaces. With a draft written, 「고객에게 무엇을 말하거나 약속할지는 판매자가 정합니다」 is a fact
     about the product rather than about this customer, and the prepared answer below is already the
     thing being decided. Measured at 1600×1000, 2026-10-02: it was the third line of 왜 지금 볼 일인가
     on a case whose answer was already written. */
  const why = detail.whyDecisionNeeded ?? detail.recommendedAction;
  if (detail.open && !detail.draft && why && why !== detail.summary && why !== summary) {
    facts.push({ icon: "chat", text: why });
  }
  return facts;
}

function WhyNowIcon({ name }: { name: "clock" | "doc" | "chat" }) {
  const common = { fill: "none", stroke: "currentColor", strokeWidth: 1.7, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="mt-px h-4 w-4 shrink-0 text-muted">
      {name === "clock" ? (
        <>
          <circle {...common} cx="12" cy="12" r="8.5" />
          <path {...common} d="M12 7.5V12l3 1.8" />
        </>
      ) : null}
      {name === "doc" ? <path {...common} d="M6 3.5h7l5 5v12H6z M13 3.5v5h5 M9 13h6 M9 16.5h4" /> : null}
      {name === "chat" ? <path {...common} d="M4 5.5h16v10H9l-5 4z" /> : null}
    </svg>
  );
}

/**
 * <b>확인한 내용</b> (canonical mockup, 2026-10-02) — what reviewnary read before it wrote anything, as
 * 「무엇을 · 어떻게」 pairs.
 *
 * <p>The pairs are {@code knowledgeUsed}'s own title and excerpt; nothing is summarised or rephrased here.
 * Where the rule lane recorded no knowledge the block falls back to {@code investigated} — the chips this
 * screen has always drawn — and where there is neither it is absent, which is the rule
 * 「a card only when there is something in it」 this screen already keeps.
 */
function CheckedTable({ detail }: { detail: OperationsCaseDetail }) {
  const used = detail.knowledgeUsed;
  if (used.length === 0) return null;
  // 「운영 정책 2개」 — the authority these came under, counted. Mixed authorities say 「확인한 내용 N개」
  // rather than naming one of them for all.
  const authorities = new Set(used.map((u) => u.authority).filter(Boolean));
  const label = authorities.size === 1 ? [...authorities][0] : "확인한 내용";
  return (
    <>
      <dl className="divide-y divide-line border-y border-line">
        {used.map((u) => (
          <div key={`${u.title}-${u.provenance}`} className="flex items-baseline gap-4 py-2.5">
            <dt className="w-[108px] shrink-0 break-keep text-sm font-semibold text-ink">{u.title}</dt>
            <dd className="min-w-0 flex-1 break-keep text-sm leading-relaxed text-muted">{u.excerpt}</dd>
          </div>
        ))}
      </dl>
      <Link
        to="/knowledge"
        className="mt-3 inline-flex items-center gap-1.5 rounded text-sm font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
      >
        <WhyNowIcon name="doc" />
        {`${label} ${used.length.toLocaleString("ko-KR")}개 보기 →`}
      </Link>
    </>
  );
}

function Evidence({ detail }: { detail: OperationsCaseDetail }) {
  if (detail.knowledgeUsed.length === 0) {
    // 「사용한 근거 없음」 is a claim about this case, and the draft standing beside it can already disprove it:
    // measured on the demo org, a case whose draft cites 「운영 정책 · 교환·반품 기준」 rendered 「사용한 근거
    // 없음」 two blocks below that citation. `knowledgeUsed` is empty because the rule lane does not record it,
    // which is a fact about our bookkeeping and not about the case. Where the screen is already showing the
    // evidence, this block says nothing rather than denying it — it does not restate the citation either, since
    // the draft card owns that and a second copy is the next thing to disagree.
    if ((detail.draft?.evidence.length ?? 0) > 0) return null;
    return (
      <p className="rounded-[14px] bg-surface px-5 py-3.5 text-sm text-muted shadow-[0_0_0_1px_#E4E7EC]">{COPY.noEvidence}</p>
    );
  }
  return (
    <div className="rounded-[14px] bg-surface shadow-[0_0_0_1px_#E4E7EC]">
      <Disclosure
        label={COPY.evidence}
        note={<span className="rounded-md bg-[#F1F3F5] px-1.5 text-xs tabular-nums">{detail.knowledgeUsed.length}</span>}
        className="px-3 py-1.5"
      >
        <ul className="divide-y divide-[#EEF0F3] px-2 pb-2">
          {detail.knowledgeUsed.map((used) => (
            <li key={`${used.title}-${used.provenance}`} className="py-3">
              <p className="break-keep text-[15px] font-semibold text-ink">{used.title}</p>
              <p className="mt-0.5 break-keep text-sm leading-relaxed text-muted">{used.excerpt}</p>
              <p className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs text-muted">
                {used.cited ? <Tag tone="blue">{COPY.used}</Tag> : null}
                {used.pastAnswer ? <Tag>{COPY.pastAnswer}</Tag> : null}
                <span>{used.authority}</span>
                <Sep />
                <span>{used.provenance}</span>
                {used.capturedOn ? (
                  <>
                    <Sep />
                    <span>{shortDate(used.capturedOn)}</span>
                  </>
                ) : null}
              </p>
            </li>
          ))}
        </ul>
      </Disclosure>
    </div>
  );
}

function Pager({ queue, index }: { queue: string[]; index: number }) {
  const prev = index > 0 ? queue[index - 1] : null;
  const next = index < queue.length - 1 ? queue[index + 1] : null;
  return (
    <span className="ml-auto flex items-center gap-1.5">
      <span className="mr-1 text-xs tabular-nums text-muted">
        {index + 1} / {queue.length}
      </span>
      <PagerLink to={prev} queue={queue} label="이전 건" dir="left" />
      <PagerLink to={next} queue={queue} label="다음 건" dir="right" />
    </span>
  );
}

function PagerLink({ to, queue, label, dir }: { to: string | null; queue: string[]; label: string; dir: "left" | "right" }) {
  const icon = (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-[15px] w-[15px] fill-none stroke-current stroke-2">
      <path d={dir === "left" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
  if (!to) {
    return (
      <span aria-hidden="true" className="flex h-8 w-8 items-center justify-center rounded-lg border border-line bg-surface text-[#C4CAD3]">
        {icon}
      </span>
    );
  }
  return (
    <Link
      to={`/customer-operations/cases/${to}`}
      state={{ caseIds: queue } satisfies CaseQueueState}
      aria-label={label}
      className="flex h-8 w-8 items-center justify-center rounded-lg border border-line bg-surface text-muted hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {icon}
    </Link>
  );
}

function Sep() {
  return <span aria-hidden="true" className="h-[3px] w-[3px] shrink-0 rounded-full bg-[#B0B8C1]" />;
}

function Tag({ tone = "gray", children }: { tone?: "gray" | "blue" | "line"; children: React.ReactNode }) {
  const cls =
    tone === "blue"
      ? "bg-brand-50 text-brand-700"
      : tone === "line"
        ? "bg-surface text-muted shadow-[inset_0_0_0_1px_#DCE0E6]"
        : "bg-[#F1F3F5] text-muted";
  return <span className={`whitespace-nowrap rounded-md px-1.5 py-px text-xs font-semibold ${cls}`}>{children}</span>;
}

type MediaProps = { caseId: string; media: NonNullable<OperationsCaseDetail["media"]>[number] };

/**
 * One photo as a thumbnail with what Reviewnary saw under it. A photo that was not inspected never gets a
 * description — only its status sentence.
 */
function MediaItem({ caseId, media }: MediaProps) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!media.imagePath) return;
    let alive = true;
    api
      .getOperationsCaseMedia(caseId, media.ordinal)
      .then((url) => alive && setSrc(url))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [caseId, media.imagePath, media.ordinal]);
  const word = media.inspected ? photoWord(media.problemVisible) : null;
  const hit = media.inspected && media.problemVisible === "YES";
  return (
    <li className="w-[168px]">
      <span
        className={`relative block h-[112px] w-[168px] overflow-hidden rounded-[10px] bg-gradient-to-br from-[#E3E8EF] to-[#C9D2DD] ${
          hit ? "ring-2 ring-[#D97706] ring-offset-2" : ""
        }`}
      >
        {src ? (
          <a href={src} target="_blank" rel="noreferrer" className="block h-full w-full focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
            <img src={src} alt={`고객이 올린 사진 ${media.ordinal}`} className="h-full w-full object-cover" />
          </a>
        ) : (
          <span className="flex h-full items-center justify-center px-2 text-center text-xs text-muted">
            {media.imagePath ? (failed ? "불러오기 실패" : "불러오는 중") : "영상"}
          </span>
        )}
        {word ? (
          <span className={`absolute bottom-1.5 left-1.5 rounded bg-surface px-1.5 text-[11px] font-bold ${hit ? "text-warn" : "text-muted"}`}>
            {word}
          </span>
        ) : null}
      </span>
      {media.inspected && media.depicts ? <p className="mt-1.5 break-keep text-xs leading-snug text-muted">분석: {media.depicts}</p> : null}
      {media.inspected && media.problemDescription ? (
        <p className="mt-0.5 break-keep text-xs leading-snug text-ink">{media.problemDescription}</p>
      ) : null}
      {!media.inspected ? <p className="mt-1.5 break-keep text-xs leading-snug text-muted">{media.statusKo}</p> : null}
    </li>
  );
}

/**
 * <b>`flat` draws no card at all</b> (product-owner decision, 2026-10-02).
 *
 * <p>확인할 일's pane is a document, and the prepared answer is the document's own body — not an object
 * set into it. The brand-outlined box was the loudest surface on the screen and it was outlining the
 * thing the seller is most likely to simply read and send; §4's own rule says a card inside a panel
 * inside a page is three borders saying one thing, and this was the third. The heading, the air above it
 * and the hairline that starts the section carry the grouping now, and the accent is left to the button.
 */
function ActionCard({
  primary,
  ariaLabel,
  flat = false,
  children,
}: {
  primary: boolean;
  ariaLabel: string;
  flat?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section
      aria-label={ariaLabel}
      className={
        flat
          ? "border-t border-line pt-6"
          : `rounded-[16px] bg-surface p-5 ${
              primary
                ? "shadow-[0_0_0_1.5px_#1B64DA,0_18px_36px_-22px_rgba(27,100,218,0.55)]"
                : "shadow-[0_0_0_1px_#E4E7EC]"
            }`
      }
    >
      {children}
    </section>
  );
}

type CardProps = {
  caseId: string;
  detail: OperationsCaseDetail;
  onApplied: (next: OperationsCaseDetail) => void;
  onFailed: (e: unknown) => void;
};

/** The Teach loop: the one fact the seller can give so this case — and the next like it — can be answered. */
/**
 * Inquiry Decision v2: what is already confirmed, and only what is still missing — the seller is not asked the whole
 * question again when two of its three parts are already answered.
 */
function NeedLists({ needs }: { needs: OperationsCaseNeed[] }) {
  const covered = needs.filter((n) => n.covered);
  const open = needs.filter((n) => !n.covered);
  return (
    <div className="mt-3 space-y-3 text-sm leading-relaxed text-ink">
      {covered.length > 0 ? (
        <section aria-label={COPY.confirmedNeeds}>
          <h3 className="text-xs font-semibold text-muted">{COPY.confirmedNeeds}</h3>
          <ul className="mt-1 space-y-1">
            {covered.map((n) => (
              <li key={n.ask} className="break-keep">
                <span className="font-semibold">{n.ask}</span>
                {n.askCustomer ? <span className="text-muted"> · 고객에게 확인: {n.askCustomer}</span> : null}
                {n.evidence.length > 0 ? <span className="block text-xs text-muted">{n.evidence.join(" · ")}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {open.length > 0 ? (
        <section aria-label={COPY.missingNeeds}>
          <h3 className="text-xs font-semibold text-muted">{COPY.missingNeeds}</h3>
          <ul className="mt-1 space-y-1">
            {open.map((n) => (
              <li key={n.ask} className="break-keep">
                <span className="font-semibold">{n.ask}</span>
                <span className="text-muted"> · {n.statusKo}</span>
                {n.missing ? <span className="block text-xs text-muted">{n.missing}</span> : null}
                {n.systemWillRead ? <span className="block text-xs text-muted">{COPY.systemWillRead}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

/**
 * <b>추천 — the draft, as text</b> (Home v3.1 preview).
 *
 * <p>The same `detail.draft` the case applies, printed and not offered: no textarea, no 적용, no
 * 다르게 처리, no 가르치기. What the case RECOMMENDS is already said one block above by
 * {@link WorkFlowCard}'s 「내 확인 필요」 cell, so this block says only the thing that cell cannot — the
 * sentence itself — and when there is no draft it says nothing rather than repeating the reason the
 * cell already gave.
 *
 * <p>Every control that changes it stands on the full case, one press away through the pane's docked
 * action. No read and no write differs.
 */
function DraftPreview({ detail }: { detail: OperationsCaseDetail }) {
  const draft = detail.draft;
  if (!draft) return null;
  return (
    <div className="border-t border-line pt-4">
      <div className="mb-2 flex items-center gap-2">
        <h3 className="text-sm font-bold text-muted">{COPY.draftTitle}</h3>
        <Tag tone="line">{draftSendWord(draft.delivery)}</Tag>
      </div>
      <p className="whitespace-pre-wrap break-keep rounded-xl bg-canvas px-4 py-3 text-[15px] leading-[1.8] text-ink [overflow-wrap:anywhere]">
        {draft.body}
      </p>
    </div>
  );
}

function TeachCard({
  caseId,
  detail,
  onTaught,
  onFailed,
}: {
  caseId: string;
  detail: OperationsCaseDetail;
  onTaught: (next: OperationsCaseDetail, receipt: Receipt) => void;
  onFailed: (e: unknown) => void;
}) {
  const gap = detail.gap;
  // Found where nothing current was, the seller's own past answer is where their answer starts. It is only text in a
  // box until they save it — as it is, or edited — through the same Teach path an empty box uses.
  const prefill = gap?.prefill ?? null;
  const [content, setContent] = useState(prefill?.text ?? "");
  useEffect(() => {
    setContent(prefill?.text ?? "");
  }, [caseId, prefill?.text]);
  const [scope, setScope] = useState(detail.productScopeAvailable ? gap?.suggestedScope ?? "ORG" : "ORG");
  const [busy, setBusy] = useState(false);
  if (!gap) return null;
  // A past answer on a similar question is precedent, not today's basis; loading it here is how the seller makes it
  // one — they read it, may edit it, and save it as their own knowledge.
  const precedent = detail.knowledgeUsed.find((used) => used.pastAnswer && used.reusableText);

  const submit = async () => {
    setBusy(true);
    const saved = content;
    try {
      onTaught(await api.teachOperationsCase(caseId, { content: saved, scope }), { scope, content: saved });
      setContent("");
    } catch (e) {
      onFailed(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ActionCard primary ariaLabel={COPY.needInfo}>
      <h2 className="text-lg font-semibold text-ink">{COPY.needInfo}</h2>
      <p className="mt-2 break-keep text-[17px] font-bold leading-snug tracking-tight text-ink">{gap.sentence}</p>
      {gap.needs && gap.needs.length > 0 ? <NeedLists needs={gap.needs} /> : null}
      {prefill ? (
        <div id="teach-prefill" className="mt-3 rounded-xl bg-[#F4F7FB] px-3.5 py-3 text-sm leading-relaxed text-ink">
          <p className="break-keep">{COPY.prefillNote}</p>
          {prefill.strengthKo || prefill.answeredOn ? (
            <p className="mt-1 text-xs text-muted">
              {[COPY.pastAnswer, prefill.strengthKo, prefill.answeredOn].filter(Boolean).join(" · ")}
            </p>
          ) : null}
        </div>
      ) : null}
      <label className="mt-4 block text-xs font-semibold text-muted" htmlFor="teach-content">
        {COPY.guidance}
      </label>
      <textarea
        id="teach-content"
        aria-describedby={prefill ? "teach-prefill" : undefined}
        className="mt-1 min-h-28 w-full rounded-[10px] border border-[#D5DAE1] p-3 text-[15px] leading-relaxed text-ink focus:border-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-700/20"
        value={content}
        onChange={(e) => setContent(e.target.value)}
      />
      <fieldset className="mt-3">
        <legend className="text-xs font-semibold text-muted">{COPY.applyScope}</legend>
        <div className="mt-1 flex rounded-[10px] bg-[#F1F3F5] p-[3px] text-sm">
          {detail.productScopeAvailable ? (
            <ScopeOption checked={scope === "PRODUCT"} onChange={() => setScope("PRODUCT")} label={COPY.thisProduct} />
          ) : null}
          <ScopeOption checked={scope === "ORG"} onChange={() => setScope("ORG")} label={COPY.wholeCompany} />
        </div>
      </fieldset>
      <Btn className="mt-4 min-h-[46px] w-full" onClick={submit} disabled={busy || content.trim().length === 0}>
        {busy ? "저장 중…" : COPY.saveAndRedraft}
      </Btn>
      {precedent && !prefill && !gap.needs ? (
        <Btn variant="ghost" size="sm" className="mt-2 w-full" onClick={() => setContent(precedent.reusableText ?? "")}>
          {COPY.loadPastAnswer}
        </Btn>
      ) : null}
    </ActionCard>
  );
}

function ScopeOption({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <label
      className={`flex-1 cursor-pointer rounded-lg px-2 py-1.5 text-center has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-brand-700 ${
        checked ? "bg-surface font-bold text-ink shadow-[0_1px_2px_rgba(15,25,45,0.1)]" : "font-medium text-muted"
      }`}
    >
      <input type="radio" name="teach-scope" className="sr-only" checked={checked} onChange={onChange} />
      {label}
    </label>
  );
}

function TaughtReceipt({ receipt, redrafted }: { receipt: Receipt; redrafted: boolean }) {
  return (
    <section aria-label={COPY.saved} role="status" className="rounded-[16px] bg-[#F5FAF6] p-5 shadow-[0_0_0_1px_#CFE3D6]">
      <p className="flex items-center gap-2 text-base font-extrabold text-good">
        <span aria-hidden="true" className="flex h-[22px] w-[22px] items-center justify-center rounded-full bg-[#1F9D55]">
          <svg viewBox="0 0 24 24" className="h-[13px] w-[13px] fill-none stroke-white stroke-[3]">
            <path d="M5 12l4 4 10-10" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
        {COPY.saved} · {receipt.scope === "PRODUCT" ? COPY.thisProduct : COPY.wholeCompany}
      </p>
      <p className="mt-3 whitespace-pre-wrap break-keep rounded-xl bg-surface px-4 py-3 text-[15px] leading-relaxed text-ink">
        {receipt.content}
      </p>
      <p className="mt-3 break-keep text-sm text-muted">
        {redrafted ? `${COPY.redrafted} · ` : ""}
        {COPY.sameQuestionUses}
      </p>
    </section>
  );
}

/** The draft, read first; editing is one press away and is where 「유사 건에 재사용」 is offered. */
function DraftCard({
  caseId,
  detail,
  primary,
  flat = false,
  onApplied,
  onFailed,
  docked = false,
  editing,
  setEditing,
}: CardProps & {
  primary: boolean;
  flat?: boolean;
  /** The two controls stand in the pane's dock; this card draws the editor and nothing else. */
  docked?: boolean;
  editing: boolean;
  setEditing: (open: boolean) => void;
}) {
  const draft = detail.draft;
  const [body, setBody] = useState(draft?.body ?? "");
  const [remember, setRemember] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setBody(draft?.body ?? "");
  }, [draft?.version, draft?.body]);
  if (!draft) return null;

  const submit = async () => {
    setBusy(true);
    try {
      onApplied(
        await api.editOperationsCaseDraft(caseId, {
          body,
          remember,
          scope: detail.productScopeAvailable ? "PRODUCT" : "ORG",
        }),
      );
      setRemember(false);
      setEditing(false);
    } catch (e) {
      onFailed(e);
    } finally {
      setBusy(false);
    }
  };

  const cited = citeCounts(draft.evidence);
  /**
   * <b>Read-only is the default, and the basis is said once</b> (canonical mockup, 2026-10-02).
   *
   * <p>The citation used to stand as 「근거 · 운영 정책 2」 chips UNDER the answer, where it read as metadata
   * about a text the seller had already finished reading. The mockup puts it on the heading line, as the
   * sentence it actually is: this answer was prepared out of these. Same counts, same source
   * ({@code draft.evidence}), drawn where it qualifies something.
   *
   * <p>Null when the draft cites nothing — the optional-fact rule: a note that said 「근거 0개」 would be a
   * claim this card has no business making about the rule lane's bookkeeping.
   */
  const basis = cited.length > 0 ? cited.map(([label, n]) => `${label} ${n}개`).join(" · ") : null;

  return (
    <ActionCard primary={primary} flat={flat} ariaLabel={COPY.draftTitle}>
      {/* <b>The heading line is a label line in the document reading</b> (2026-10-02): the section name on
          the left at the size of every other section name on this pane, and the two facts that qualify
          it — whether it has been sent, and what it was written out of — as one muted line on the right.
          The 「미발송」 outline tag and the ⓘ glyph both went: a tag is a mark for something that varies
          against its neighbours and this one never does, and the circled i announced a sentence that is
          plainer without it. */}
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        {flat ? (
          <h2 className="text-xs font-semibold text-muted">{COPY.draftTitle}</h2>
        ) : (
          <h2 className="text-lg font-semibold text-ink">{COPY.draftTitle}</h2>
        )}
        {flat ? null : <Tag tone="line">{draftSendWord(draft.delivery)}</Tag>}
        {!editing && (flat || basis) ? (
          <span className="ml-auto flex items-center gap-1.5 break-keep text-xs text-muted">
            {flat ? null : (
              <svg viewBox="0 0 24 24" aria-hidden="true" className="h-3.5 w-3.5 shrink-0" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round">
                <circle cx="12" cy="12" r="8.5" />
                <path d="M12 11v5.5M12 7.8v.2" />
              </svg>
            )}
            {flat
              ? [draftSendWord(draft.delivery), basis ? `${basis} 근거` : null].filter(Boolean).join(" · ")
              : `${basis}를 근거로 준비한 답변입니다.`}
          </span>
        ) : null}
      </div>
      {editing ? (
        <>
          <textarea
            aria-label={COPY.draftTitle}
            className="mt-3 min-h-40 w-full rounded-xl border border-[#D5DAE1] p-3.5 text-[15px] leading-[1.8] text-ink focus:border-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-700/20"
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
          <label className="mt-3 flex items-center gap-2 text-sm text-ink">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-4 w-4" />
            {COPY.reuse}
          </label>
          <div className="mt-3 flex gap-2">
            <Btn
              variant="outline"
              className="flex-1"
              disabled={busy}
              onClick={() => {
                setBody(draft.body);
                setEditing(false);
              }}
            >
              {COPY.cancel}
            </Btn>
            <Btn className="flex-1" onClick={submit} disabled={busy || body.trim().length === 0}>
              {busy ? "저장 중…" : COPY.save}
            </Btn>
          </div>
        </>
      ) : (
        <>
          {/* <b>Read, not a field</b> (visual target, 2026-10-02). The grey inset said 「textarea」 about a
              paragraph that is read-only until 수정하기 is pressed, and it was the second filled surface
              inside the outlined card around it. On the white canvas the answer is simply the body text —
              `prose`, which §1 reserves for the one thing on a surface that is being read. */}
          <p
            className={
              flat
                ? "mt-3 whitespace-pre-wrap break-keep text-prose text-ink [overflow-wrap:anywhere]"
                : "mt-3 whitespace-pre-wrap break-keep rounded-xl bg-[#F7F8FA] px-4 py-3.5 text-[15px] leading-[1.8] text-ink [overflow-wrap:anywhere]"
            }
          >
            {draft.body}
          </p>
          {/* <b>Content-sized, primary first</b> (product-owner decision, 2026-10-02). The two controls
              were a 50:50 split, then a minimum/remainder split with a ceiling — both of them ways of
              deciding how to divide a width neither button asked for. In a document the actions sit at
              the end of what they act on and take the room their own words need; the primary is marked
              by its colour, which is the one thing the seller is scanning for, and it leads because it
              is the ordinary ending of this pane. Nothing is pinned, so the same rule holds in the 576px
              pane, the 440px pane and the page column.

              <p>The bordered reading keeps the old split: that card is a column of its own and its
              buttons do have a width to divide. */}
          {/* <b>수정하기 → 발송 화면으로, primary on the right</b> (product-owner decision, 2026-10-02).
              A document's controls read left to right as the order they are considered in: the detour
              first, the ending last. The primary was leading because it is the louder of the two, and
              loudness is already its colour's job — it does not also need the first position. */}
          {docked ? null : (
            <div className={`flex items-center gap-2 ${flat ? "mt-6" : "mt-3.5 justify-between"}`}>
              <Btn variant="outline" className={flat ? "" : "shrink-0"} onClick={() => setEditing(true)}>
                {COPY.edit}
              </Btn>
              <BtnLink
                to={detail.to}
                variant={primary ? "solid" : "outline"}
                className={flat ? "" : "min-w-0 max-w-[340px] flex-1"}
              >
                {COPY.toSend} ↗
              </BtnLink>
            </div>
          )}
        </>
      )}
    </ActionCard>
  );
}

/** 「상품 정보 2」 — the draft's own citations, counted by the label they already carry. */
function citeCounts(evidence: { scopeLabel: string }[]): [string, number][] {
  const counts = new Map<string, number>();
  for (const e of evidence) counts.set(e.scopeLabel, (counts.get(e.scopeLabel) ?? 0) + 1);
  return [...counts.entries()];
}

const ACTIONS = [
  "REPLY_TO_CUSTOMER",
  "CONTACT_CUSTOMER",
  "REFUND_OR_COMPENSATION",
  "CANCEL_OR_EXCHANGE",
  "ADD_KNOWLEDGE",
  "REVIEW_PRODUCT_LISTING",
  "MONITOR_REPEAT_ISSUE",
  "NO_ACTION",
];

/** The seller saying a different action was right — recorded as their judgement, never as a rule change. */
function CorrectionCard({
  caseId,
  detail,
  flat = false,
  docked = false,
  open,
  setOpen,
  onApplied,
  onFailed,
}: CardProps & { flat?: boolean; docked?: boolean; open: boolean; setOpen: (open: boolean) => void }) {
  const [action, setAction] = useState("");
  const [note, setNote] = useState("");
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    try {
      onApplied(
        await api.correctOperationsCase(caseId, {
          correctedActionType: action === "" ? null : action,
          note,
          remember,
          scope: detail.productScopeAvailable ? "PRODUCT" : "ORG",
        }),
      );
      setNote("");
      setOpen(false);
    } catch (e) {
      onFailed(e);
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    // Docked: the line stands on the pane's floor, drawn by the view beside the two controls it follows.
    if (docked) return null;
    return (
      /* <b>A line under a rule, not a box</b> (visual target, 2026-10-02). Its whole content is one muted
         sentence and one text control; a bordered surface for that is a card drawn to hold nothing. The
         control is the accent because it is a way out of this pane — §5 spends the brand on actions and
         links — and it is the last thing on the document. */
      <div
        className={
          flat
            ? "mt-6 flex items-center gap-3 border-t border-line pt-4 text-sm text-muted"
            : "flex items-center gap-3 rounded-[14px] bg-surface px-5 py-3.5 text-sm text-muted shadow-[0_0_0_1px_#E4E7EC]"
        }
      >
        <span className="break-keep">{COPY.otherHandling}</span>
        <button
          type="button"
          onClick={() => setOpen(true)}
          className={`ml-auto whitespace-nowrap rounded font-semibold hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
            flat ? "text-brand-700 hover:text-brand-800" : "text-ink"
          }`}
        >
          {COPY.changeHandling}
        </button>
      </div>
    );
  }

  return (
    <section aria-label={COPY.changeHandling} className="space-y-3 rounded-[14px] bg-surface p-5 shadow-[0_0_0_1px_#E4E7EC]">
      <h2 className="text-lg font-semibold text-ink">{COPY.changeHandling}</h2>
      <div>
        <label className="block text-xs font-semibold text-muted" htmlFor="correction-action">
          {COPY.handlingMethod}
        </label>
        <select
          id="correction-action"
          className="mt-1 w-full rounded-[10px] border border-[#D5DAE1] bg-surface p-2.5 text-[15px] text-ink"
          value={action}
          onChange={(e) => setAction(e.target.value)}
        >
          <option value="">선택 안 함</option>
          {ACTIONS.map((token) => (
            <option key={token} value={token}>
              {actionKo(token)}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="block text-xs font-semibold text-muted" htmlFor="correction-note">
          {COPY.memo}
        </label>
        <textarea
          id="correction-note"
          className="mt-1 min-h-20 w-full rounded-[10px] border border-[#D5DAE1] p-2.5 text-[15px] text-ink"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
      </div>
      <label className="flex items-center gap-2 text-sm text-ink">
        <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-4 w-4" />
        {COPY.reuse}
      </label>
      <div className="flex gap-2">
        <Btn variant="outline" className="flex-1" onClick={() => setOpen(false)} disabled={busy}>
          {COPY.cancel}
        </Btn>
        <Btn className="flex-1" onClick={submit} disabled={busy || (action === "" && note.trim().length === 0)}>
          {busy ? "저장 중…" : COPY.save}
        </Btn>
      </div>
    </section>
  );
}
