import {
  CHANGE_EXPLANATION_KO,
  SEVERITY_LABEL_KO,
  changeBadges,
  investigationHintKo,
  provenanceKo,
  suppressedQuoteCount,
  surgeLine,
} from "../../lib/reviewIssuesView";
import { kstDate, kstToday } from "../../lib/format";
import type { ReviewIssueView } from "../../lib/types";
import { Facts } from "../ui/ObjectRow";
import { Disclosure } from "../ui/Disclosure";
import { CaseLayout } from "../workspace/CaseLayout";
import { EvidenceQuote } from "./repeat/EvidenceQuote";
import { EvidenceTrend } from "./repeat/EvidenceTrend";
import { IssueDecision } from "./repeat/IssueDecision";
import { IssueGrounding } from "./repeat/IssueGrounding";
import { PaneBlock } from "./repeat/PaneBlock";
import { RatingSpread } from "./repeat/RatingSpread";
import { RepeatByProduct } from "./repeat/RepeatByProduct";
import type { RepeatedIssueWorkspace } from "./useRepeatedIssue";

/**
 * <b>Repeated Issue workspace</b> — judging one repeated problem end to end, without leaving it.
 *
 * <p><b>Five blocks, in the seller's reading order</b> (canonical, 2026-10-03):
 * 변화와 신호 → 근거 → 우리가 써 둔 것 → 판단과 조치 → 기록. The decision used to stand second, straight
 * under 왜 올라왔나요, which was right while it was the only way to reach it from a reading three screens
 * long; the action is now docked at the floor of the pane, so it is reachable from anywhere and the
 * reading can end where a decision belongs — after the evidence, not before it.
 *
 * <p><b>Two reads, and they fail apart.</b> The detail read carries the problem, its evidence and its
 * record; the repeat-context read carries where it repeats and what the library says. A screen that
 * could not reach one still shows the other, and neither renders a placeholder number in the other's
 * place — the way a workspace like this goes wrong is a plausible figure nobody measured.
 *
 * <p><b>No count is derived here.</b> Every number on this screen came from a read that measured it. The
 * per-product line prints a pair and never a percentage (`lib/repeatedIssue.ts`), and the evidence trend
 * is a count of the dates the detail read already returned — not a second opinion about the trend, which
 * is the server's (`IssueChangeView`).
 *
 * <p><b>The evidence is representative, not poured out.</b> One product and two quotes stand in the
 * reading; everything the two reads returned is one disclosure away. Eighteen quotes above a decision is
 * not more evidence, it is a reading nobody finishes.
 *
 * <p><b>The decision is the issue lifecycle, unchanged.</b> There is no second decision vocabulary for a
 * repeated problem, and still no 해결 처리 control at any state.
 */
export function IssueReading({
  issue,
  workspace,
  docked = false,
}: {
  issue: ReviewIssueView;
  workspace: RepeatedIssueWorkspace;
  /**
   * <b>The action is docked at the floor of the pane by the screen</b> — so this reading draws the note
   * field and no button (반복 문제 canonical).
   *
   * <p>Opt-in, and false everywhere else: 오늘's problem pane has no docked footer, so leaving it out
   * keeps exactly the button that pane shipped with, under the field it submits.
   */
  docked?: boolean;
}) {
  const { detail, context, loading, failed, contextFailed, note, setNote, busy, error, submit } = workspace;

  const surge = surgeLine(issue.change);
  const hint = investigationHintKo(issue);
  const badges = changeBadges(issue.change);
  const evidence = detail?.evidence ?? [];
  const quotable = evidence.filter((row) => row.quote && row.quote.trim().length > 0);
  const shownQuotes = quotable.slice(0, 2);
  const restQuotes = quotable.slice(2);
  const suppressed = detail ? suppressedQuoteCount(evidence) : 0;
  const productCount = context?.evidence.byProduct.length ?? 0;
  const moreToSee = restQuotes.length > 0 || productCount > 1;

  return (
    <CaseLayout
      variant="pane"
      label="선택한 문제"
      decisionLabel="문제 읽기"
      meta={
        <Facts>
          <span className="font-semibold text-ink">{issue.lifecycleLabelKo}</span>
          <span>심각도 {SEVERITY_LABEL_KO[issue.severity]}</span>
        </Facts>
      }
      title={issue.title}
      sub={
        [
          `근거 ${issue.evidenceCount.toLocaleString("ko-KR")}건`,
          productCount > 0 ? `상품 ${productCount.toLocaleString("ko-KR")}곳` : null,
          issue.firstEvidenceOn ? `${issue.firstEvidenceOn}부터` : null,
        ]
          .filter(Boolean)
          .join(" · ")
      }
      decision={
        <div className="space-y-2">
          <PaneBlock label="변화와 신호">
            <ul className="space-y-1.5">
              {badges.map((badge) => (
                <li key={badge.kind} className="break-keep leading-relaxed text-muted">
                  <span className="font-semibold text-ink">{badge.labelKo}</span> — {CHANGE_EXPLANATION_KO[badge.kind]}
                </li>
              ))}
              {badges.length === 0 ? (
                <li className="break-keep leading-relaxed text-muted">
                  최근 판단된 변화는 없지만 관련 리뷰가 기록되어 있습니다.
                </li>
              ) : null}
            </ul>
            {surge ? <p className="text-sm tabular-nums text-muted">{surge}</p> : null}
            {hint ? <p className="break-keep leading-relaxed text-ink">{hint}</p> : null}
            {/* The shape of the repetition, from the dates the detail read already returned. */}
            {detail ? <EvidenceTrend evidence={evidence} throughMonth={kstToday().slice(0, 7)} /> : null}
          </PaneBlock>

          <PaneBlock label="근거">
            <RepeatByProduct evidence={context?.evidence ?? null} failed={contextFailed} limit={1} />
            <RatingSpread distribution={context?.evidence.ratingDistribution ?? null} failed={contextFailed} />

            {loading ? (
              <p className="text-muted">근거를 불러오는 중…</p>
            ) : failed ? (
              <p className="text-muted">근거를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
            ) : shownQuotes.length > 0 ? (
              <ul className="space-y-2 border-l-2 border-line pl-3">
                {shownQuotes.map((row) => (
                  <EvidenceQuote key={`${row.reviewId}-${row.unitOrdinal}`} row={row} />
                ))}
              </ul>
            ) : (
              <p className="text-muted">표시할 수 있는 인용이 없습니다.</p>
            )}

            {suppressed > 0 ? (
              <p className="text-xs text-muted">인용을 표시할 수 없는 근거가 {suppressed}건 더 있습니다.</p>
            ) : null}

            {moreToSee ? (
              <Disclosure
                label={`근거 ${issue.evidenceCount.toLocaleString("ko-KR")}건${
                  productCount > 0 ? ` · 상품 ${productCount.toLocaleString("ko-KR")}곳` : ""
                } 모두 보기`}
                className="-ml-2"
              >
                <div className="space-y-2 pl-2 pt-2">
                  <RepeatByProduct evidence={context?.evidence ?? null} failed={contextFailed} skip={1} region={false} />
                  {restQuotes.length > 0 ? (
                    <ul className="space-y-2 border-l-2 border-line pl-3">
                      {restQuotes.map((row) => (
                        <EvidenceQuote key={`${row.reviewId}-${row.unitOrdinal}`} row={row} />
                      ))}
                    </ul>
                  ) : null}
                </div>
              </Disclosure>
            ) : null}
          </PaneBlock>

          <IssueGrounding knowledge={context?.knowledge ?? null} failed={contextFailed} />

          <IssueDecision
            issueId={issue.id}
            state={issue.lifecycleState}
            note={note}
            onNoteChange={setNote}
            busy={busy}
            error={error}
            onSubmit={docked ? null : submit}
          />

          <PaneBlock label="기록">
            {detail && detail.history.length > 0 ? (
              <ul className="space-y-2">
                {[...detail.history].reverse().map((event) => (
                  <li key={`${event.at}-${event.toState}`} className="text-xs tabular-nums text-muted">
                    <span className="font-semibold text-ink">{event.toStateLabelKo}</span>
                    {" · "}
                    {event.actor === "OPERATOR" ? "운영자" : "reviewnary"}
                    {" · "}
                    {kstDate(event.at)}
                    {event.note ? (
                      <span className="mt-0.5 block break-keep text-sm leading-relaxed text-ink">{event.note}</span>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-muted">아직 기록된 상태 변화가 없습니다.</p>
            )}
            <p className="break-keep text-xs leading-relaxed text-muted">{provenanceKo(issue)}</p>
          </PaneBlock>
        </div>
      }
    />
  );
}
