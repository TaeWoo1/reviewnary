import { Chip } from "../../ui/Chip";
import { usePaneDepth } from "../../workspace/CaseLayout";
import { Disclosure } from "../../ui/Disclosure";
import { AiMarkChip, TriageTierChip } from "../TriageTierChip";
import { TRIAGE_TAG_DISCLOSURE } from "../../../lib/reviewTriage";
import { plainText } from "../../../lib/plainText";
import type { ChannelReviewDetailView } from "../../../lib/types";

/**
 * <b>What the problem is, and why it is in front of you</b> — the first two questions of the Decision
 * Workspace, answered in one block.
 *
 * <b>The customer's sentence is the largest text on the page.</b> It was not, before: the reply task
 * screen opened on a draft and folded the customer away under 「이 리뷰의 자동 분류」, which is the
 * right fold for a screen whose only question is «approve this text» and the wrong one for a screen
 * whose question is «is this worth your hands». A seller cannot decide what to do about a complaint
 * they have to expand to read.
 *
 * <b>The tier and the reason are shown, not folded.</b> They are the answer to 「왜 확인해야 하는가」 —
 * the second question — and the one thing kept behind a fold is the keyword classification, whose
 * accuracy has never been measured and which the disclosure says so about.
 *
 * <b>Nothing here is composed by this component.</b> The reason sentence is the backend's
 * (`ReviewTriageNote`), the recommended action is null for 참고 and renders as nothing rather than as
 * a reassuring filler, and the body is the server's redacted full text.
 */
export function ReviewProblemCard({
  detail,
  word,
  showBody = true,
  verdict = "self",
  flat = false,
  whyNow,
}: {
  detail: ChannelReviewDetailView;
  word: string;
  /**
   * <b>Who owns the tier verdict on this surface.</b>
   *
   * <p><b>`"self"`</b> — nothing else on screen states it, so this block leads with it. That is the
   * preview reading, where the judgment forms are left to the full screen.
   *
   * <p><b>`"controls"`</b> — {@code SellerCorrectionControls} is on screen, and there the verdict is not
   * a label but the context for a choice: three tier buttons are the seller's, and 「시스템 판단 …」 is the
   * only thing that tells them which one the machine picked. That line therefore stays unconditional
   * THERE, and this block — which answers 「왜 올라왔나요」 — keeps the reason and drops the conclusion.
   *
   * <p>Measured at 1600×1000 (2026-10-01): 리뷰 처리 said 「확인 필요」 in 왜 올라왔나요, again in
   * 이 리뷰의 중요도 (the numerals were dropped 2026-10-01), and a third time as a button label —
   * one verdict, three places, one screen.
   * The rule that removes it without losing anything is ownership, not visibility: the block that can
   * CHANGE a fact owns stating it.
   */
  verdict?: "self" | "controls";
  /**
   * False when the screen already prints the customer's sentence as its title (CaseLayout, UI/UX v2 Phase 1): a
   * one-line review would otherwise be read twice, one block apart, and the second copy is the one that is skipped.
   */
  showBody?: boolean;
  /**
   * <b>No card edge, and the reason flows</b> (확인할 일's review pane, 2026-10-03). The left rule was a
   * card's edge inside a band that already has a hairline above it and a label beside it — three
   * boundaries for one group. Same content, same order; only the chrome goes.
   */
  flat?: boolean;
  /**
   * <b>서버가 쓴 한 문장</b> (리뷰 canonical mockup, 2026-10-06). 주면 {@code triage.reason} +
   * {@code triage.recommendedAction} 두 조각 대신 이것 하나를 그린다.
   *
   * <p>조립하지 않고 받아 적는 이유: 「2점」은 규칙이 인용한 사실이고 「내용을 읽고 상품 상태를 확인해
   * 보세요」는 목록이 쓰는 권유다. 둘을 접속사로 이으면 어느 쪽도 하려던 적 없는 주장이 생기고, 그
   * 문장을 검사할 수 있는 자리가 저장소 어디에도 없다. 서버의 {@code ReviewTriageWhyNow}가 소유한다.
   *
   * <p>나머지는 그대로다 — 등급 칩, AI 표시, 새 리뷰 표시, 가림 안내, 자동 분류 접힘. 이 한 줄만 바뀐다.
   */
  whyNow?: string | null;
}) {
  const body = detail.body ? plainText(detail.body) : "";
  // <b>In a preview the state and the reason are one line, and the reason has no rule beside it.</b> The left
  // border was a card's edge in a 440px column that is already a card; Linear's Peek answers 「what state is
  // this in」 with a coloured mark and a word flowing beside the rest, and nothing is drawn around it.
  const preview = usePaneDepth() === "preview" || flat;
  /**
   * <b>왜-지금을 서버 문장 하나가 소유한다.</b> 그러면 reason은 이 블록에서 한 번도 그려지지 않는다 —
   * 칩 줄로도 아니다. 「· 4점」은 「무엇을 인용해 이 등급이 됐나」를 좁은 열에서 말하던 것이고, 상세
   * 페이지에는 그 사실이 제목 아래 신원 줄에 이미 있다. 두 번 말하면 둘 중 하나는 안 읽힌다.
   */
  const whyOwned = whyNow !== undefined;
  return (
    <section aria-label="고객이 남긴 내용" className={preview ? "space-y-1.5" : "space-y-3"}>
      <div className="flex flex-wrap items-center gap-2">
        {verdict === "self" ? <TriageTierChip tier={detail.triage.tier} /> : null}
        {detail.aiMark ? <AiMarkChip /> : null}
        {detail.isNew ? <Chip tone="accent">새 {word}</Chip> : null}
        {/* <b>State and criterion on one line.</b> Linear's Peek answers 「what state is this in」 with a mark,
            a word and whatever qualifies it, flowing — 「● In Review · No priority」. `triage.reason` is what the
            rules cited (here: 「1점」) and it is the whole of the why; nothing richer is invented for it. */}
        {!whyOwned && preview && detail.triage.reason ? (
          <>
            <span aria-hidden="true" className="text-sm text-muted">
              ·
            </span>
            <span className="break-keep text-sm text-muted">{detail.triage.reason}</span>
          </>
        ) : null}
      </div>

      {/* The customer's own words. `lg` — larger than the page's prose, because this is the object the
          seller opened the screen to read. A textless review says what it is rather than implying that
          reviewnary lost something. */}
      {!showBody ? null : detail.textless || body.length === 0 ? (
        <p className="break-keep text-base leading-relaxed text-muted">별점만 남긴 {word}입니다.</p>
      ) : (
        <p className="whitespace-pre-wrap break-keep text-lg leading-relaxed text-ink">{body}</p>
      )}
      {detail.bodyRedacted ? (
        <p className="break-keep text-sm text-muted">
          개인정보로 보이는 부분은 가려서 보여드립니다. 원문은 판매자센터에서 확인하실 수 있습니다.
        </p>
      ) : null}

      {/* Why it is here. The tier was decided by the rating and whether there is text, and by nothing
          else; the reason cites what else the row carries. */}
      {/* <b>The recommended action is the full case's.</b> In a preview it stood at the same weight as the
          customer's sentence and the judgment line, and it answers 「what should I do」 — the question the one CTA
          exists to open, not the one 「should I open this」 needs. The page below renders it unchanged. */}
      {whyOwned ? (
        whyNow ? <p className="break-keep text-base leading-relaxed text-ink">{whyNow}</p> : null
      ) : (
        <>
          {preview ? null : (
            <div className="space-y-1 border-l-2 border-line pl-3">
              <p className="break-keep text-sm text-muted">{detail.triage.reason}</p>
              {detail.triage.recommendedAction ? (
                <p className="break-keep text-sm leading-relaxed text-ink">{detail.triage.recommendedAction}</p>
              ) : null}
            </div>
          )}
          {flat && detail.triage.recommendedAction ? (
            <p className="break-keep text-base leading-relaxed text-ink">{detail.triage.recommendedAction}</p>
          ) : null}
        </>
      )}

      {detail.triage.tags.length > 0 ? (
        <Disclosure label="자동 분류" note={detail.triage.tags.join(" · ")}>
          <p className="break-keep px-2 pb-2 text-sm leading-relaxed text-muted">{TRIAGE_TAG_DISCLOSURE}</p>
        </Disclosure>
      ) : null}
    </section>
  );
}
