import { count } from "../../lib/format";
import type { OpportunityOutcomeView } from "../../lib/types";
import { Status } from "../ui/Status";

/**
 * 한 일과 그 결과 — what the seller applied, and what the reviews did afterwards.
 *
 * <b>The counts and the reason are drawn whatever the verdict is.</b> 「판단 보류」 on its own reads as a product
 * that lost the measurement; beside 「적용 전 4주 11건 → 뒤 4주 0건 · 그 기간에 들어온 리뷰가 없어 비교할 수
 * 없습니다」 it reads as a product that measured and refused to overclaim — and the refusal is the more valuable
 * of the two outputs, so it is the one that gets the numbers.
 *
 * <b>No sentence here says the act worked.</b> Every word comes from the server
 * ({@link OpportunityOutcomeView}), whose vocabulary is about the EVIDENCE — 근거 줄었습니다, not 해결했습니다.
 * This component chooses the order and the tone and writes no claim of its own.
 *
 * <b>While the window is open it says when it closes.</b> That is the one thing a seller who just applied
 * something wants to know, and a card that only said 「확인 중」 would make them come back to find out.
 */
export function OpportunityOutcome({ outcome }: { outcome: OpportunityOutcomeView }) {
  const tone =
    outcome.verdict === "IMPROVED" ? "good" : outcome.verdict === "WORSENED" ? "bad" : "neutral";
  return (
    <section aria-label="한 일과 그 결과" className="space-y-1">
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Status tone={tone} variant="chip">
          {outcome.verdictLabelKo}
        </Status>
        {outcome.kindLabelKo ? <span className="text-sm text-ink">{outcome.kindLabelKo}</span> : null}
        <span className="text-sm tabular-nums text-muted">{outcome.appliedOn} 적용</span>
      </p>
      <p className="break-keep text-sm leading-relaxed text-muted">
        {outcome.settled ? (
          <>
            <span className="tabular-nums">
              적용 전 4주 {count(outcome.evidenceBefore)}건 → 뒤 4주 {count(outcome.evidenceAfter ?? 0)}건
            </span>
            {" · "}
            {outcome.reasonLabelKo}
          </>
        ) : (
          <>
            <span className="tabular-nums">{outcome.observedThrough}</span>까지 리뷰를 지켜봅니다 · 적용 전 4주{" "}
            <span className="tabular-nums">{count(outcome.evidenceBefore)}건</span>
          </>
        )}
      </p>
    </section>
  );
}

/** The same record, as the list a repeated problem's own surface shows. Nothing is drawn when nothing was done. */
export function OpportunityOutcomeList({ outcomes }: { outcomes: readonly OpportunityOutcomeView[] }) {
  if (outcomes.length === 0) {
    return null;
  }
  return (
    <ul className="space-y-3">
      {outcomes.map((outcome) => (
        <li key={`${outcome.kind ?? "-"}-${outcome.appliedOn}`}>
          <OpportunityOutcome outcome={outcome} />
        </li>
      ))}
    </ul>
  );
}
