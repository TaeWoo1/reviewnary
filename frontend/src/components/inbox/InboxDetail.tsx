import { Link } from "react-router-dom";
import type { FeedItem, ItemAnalysis } from "../../lib/types";
import { TYPE_LABEL, itemTitle, needsCheck } from "../../lib/inboxWorkspace";
import { sentimentChip, urgencyChip } from "../../lib/inboxView";
import { relativeTime } from "../../lib/format";
import { draftUnavailableReason, inquiryNextAction } from "../../lib/inquiryNextAction";
import { Chip } from "../ui/Chip";
import { Status } from "../ui/Status";
import { Disclosure } from "../ui/Disclosure";
import { InquiryResponsePanel } from "./InquiryResponsePanel";
import { plainText } from "../../lib/plainText";

/**
 * Detail panel for one inbox row.
 *
 * WHAT IT SHOWS AND WHAT IT CALLS THINGS. For an inquiry, the full body comes from the inquiry
 * detail read inside `InquiryResponsePanel`, so this block shows context only. For a review, the
 * feed carries a `snippet` and nothing more — so it is labelled 발췌, not 원문. Labelling a
 * fragment as the original text would tell the seller they had read the whole review.
 *
 * The response workflow renders only when a work item resolves for this inquiry. When it does not
 * — a review, or an inquiry outside the queue — nothing about drafting appears at all, rather than
 * a disabled control.
 *
 * <b>이 블록은 header를 그리지 않는다.</b> 예전에는 그렸고, 그래서 답변 대기 목록에 없는 문의에서
 * 제목·채널·상품·시각이 한 화면에 두 번씩 나왔다(본문까지 합치면 같은 문장이 세 번). 「무엇인가」는
 * {@code CaseLayout}의 몫이고 {@code InquiryCasePane}이 거기서 한 번 그린다. 이 블록의 몫은 그 아래
 * 세 가지다: <b>지금 무슨 상태이고 내가 뭘 하면 되는지</b>, 답변 작업, 그리고 근거.
 *
 * <b>「할 수 없습니다」에는 이유가 붙는다.</b> 실제 live 데이터에서 판매자가 읽은 문장은
 * 「이 문의에는 reviewnary가 답변 방향을 제안할 수 없습니다」였다 — 사실이지만 이유가 없어서, 제품이
 * 이 문의를 다루지 못한다는 뜻으로 읽힌다. 진짜 이유는 규칙이다: 초안은 <b>답변을 기다리는 문의</b>에만
 * 쓴다. {@code draftUnavailableReason}이 그 규칙을 말하고, 이미 답변된 문의에는 그 사실을 말한다.
 */
export function InboxDetail({
  item,
  analysis,
  workItemId,
  bodyOnly,
}: {
  item: FeedItem;
  analysis?: ItemAnalysis;
  workItemId: string | null;
  /**
   * 제목과 별개로 그릴 본문. {@code inquiryHeadline}이 정한다 — 제목이 본문에서 온 경우 null이고, 그때
   * 본문은 이미 제목 자리에 있으므로 다시 그리지 않는다. 호출부가 header를 소유한다는 신호이기도 하다.
   */
  bodyOnly?: string | null;
}) {
  const urgency = analysis ? urgencyChip(analysis.urgency) : null;
  const sentiment = analysis ? sentimentChip(analysis.sentiment) : null;
  const isInquiry = item.type === "INQUIRY";
  // The panel owns 고객 문의 — title, body, channel, product and the 상품 지정 control — whenever it
  // renders. Repeating any of it here would be the same fact twice on one screen.
  const panelOwnsTheQuestion = !!workItemId && isInquiry;
  const next = isInquiry ? inquiryNextAction(item, workItemId) : null;
  const reason = isInquiry ? draftUnavailableReason(item, workItemId, item.channelNameKo) : null;

  return (
    <article aria-label="선택한 항목" className="space-y-4">
      {/*
        1 — 다음 행동. 상태를 말하고 바로 그래서 뭘 하면 되는지를 말한다. 예전에는 상태 단어들이 pane 맨
        위에 늘어서 있었고(유형 · 답변 필요 · 확인 필요 · 시각), 그중 「유형」은 페이지 제목이고 「시각」은
        meta 줄에 있어서 같은 사실의 반복이었다. 남은 것은 이 화면에서만 알 수 있는 하나 — 무엇을 하면
        되는가.
      */}
      {next ? (
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <Status tone={next.state.tone} variant="word">{next.state.text}</Status>
          {needsCheck(item) ? <span className="text-sm font-semibold text-bad">확인 필요</span> : null}
          <span className="min-w-0 break-keep text-sm leading-relaxed text-muted">{next.sentence}</span>
        </div>
      ) : (
        // A review has no work item and no next action this screen owns; it keeps the plain context line.
        <p className="flex flex-wrap items-center gap-x-2 text-sm text-muted">
          <span>{TYPE_LABEL[item.type]}</span>
          {needsCheck(item) ? <span className="font-semibold text-bad">확인 필요</span> : null}
          <span>{relativeTime(item.receivedAt)}</span>
        </p>
      )}

      {/* A review is not drawn inside an inquiry case, so nothing above it named it. */}
      {isInquiry ? null : (
        <h2 className="break-keep text-lg font-bold leading-snug text-ink">{itemTitle(item)}</h2>
      )}

      {/*
        2 — 본문. 리뷰는 feed의 snippet뿐이고, 문의는 work item이 있을 때 답변 패널이 전체 본문을 읽어
        온다. 그 어느 쪽도 아닐 때 — 답변 대기 목록에 없는 문의 — 여기 있는 발췌가 판매자가 읽을 수 있는
        전부라서 그때는 반드시 그린다. 제목이 이미 그 문장이면 `bodyOnly`가 null이고 아무것도 그리지
        않는다. 어느 쪽이든 이름은 「발췌」이고 「원문」이 아니다.
      */}
      {panelOwnsTheQuestion ? null : (
        <BodyExcerpt label={isInquiry ? "문의 발췌" : "리뷰 발췌"} text={isInquiry ? bodyOnly : item.snippet} />
      )}

      {workItemId ? <InquiryResponsePanel workItemId={workItemId} /> : null}

      {/* 3 — 왜 초안이 없는지. 규칙이라서 말할 수 있고, 규칙이라서 「못 한다」가 아니다. */}
      {reason ? (
        <p className="break-keep border-t border-line pt-4 text-sm leading-relaxed text-muted">
          {reason}
        </p>
      ) : null}

      {/* Classification is a hint about the row, not a finding — and it is not why the seller opened
          it. Closed by default: three chips, a summary sentence and a disclaimer were four lines of
          the same grey competing with 고객 문의 and the draft above them. */}
      {analysis ? (
        <Disclosure className="border-t border-line pt-4" label={`자동 분류 · ${analysis.category}`}>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {urgency ? <Chip>긴급도 {urgency.label}</Chip> : null}
            {sentiment ? <Chip>{sentiment.label}</Chip> : null}
          </div>
          <p className="mt-2 break-keep text-sm leading-relaxed text-muted">{analysis.summary}</p>
          {/* Seller language, not the analyzer's name and version: the fact that matters is that this
              is an automatic keyword classification that may be wrong. */}
          <p className="mt-1 text-sm text-muted">키워드로 자동 분류한 것이라 정확하지 않을 수 있습니다.</p>
        </Disclosure>
      ) : null}

      <footer className="border-t border-line pt-4">
        <Link
          to="/memory"
          className="inline-flex rounded-lg text-sm font-semibold text-brand-700 transition hover:text-brand-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
        >
          같은 문제가 반복되는지 보기
        </Link>
      </footer>
    </article>
  );
}

/** 발췌 한 덩이. 그릴 것이 없으면 자리도 차지하지 않는다. */
function BodyExcerpt({ label, text }: { label: string; text?: string | null }) {
  if (!text) return null;
  return (
    <section>
      <h3 className="text-sm font-semibold text-muted">{label}</h3>
      <p className="mt-1.5 whitespace-pre-wrap break-keep leading-relaxed text-ink">{plainText(text)}</p>
    </section>
  );
}
