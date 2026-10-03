import { Btn } from "../../ui/Btn";
import { nextActionKo, waitingNoteKo } from "../../../lib/reviewIssuesView";
import type { IssueLifecycleState } from "../../../lib/types";
import { OpportunityList } from "../../opportunity/OpportunityList";
import { PaneBlock } from "./PaneBlock";

/**
 * <b>판단과 조치</b> — what can be done about this problem, and the seller's decision in their own words.
 *
 * <b>The note is the point of this section.</b> `startActing` and `markRemediated` have taken an
 * operator note since the lifecycle existed, and the screen passed none — so every transition a
 * seller made was recorded as a state change by a person who said nothing about it. The record then
 * answered 「when did this move」 and not 「what did we do」, which is the question a 조치 기록 exists
 * for. The field is optional: a decision without a sentence is still a decision, and demanding prose
 * before a state change would make the trail worse by making people skip it.
 *
 * <b>The button is not here</b> (canonical, 2026-10-03). It is docked at the floor of the pane, where it
 * stays reachable however far the reading is scrolled — this block is the fifth of five and the one
 * thing to press may not be the thing that scrolls away. The field stays where it is written; the state
 * both of them share lives in `useRepeatedIssue`.
 *
 * <b>No 해결 처리 control at any state, unchanged.</b> 해결됨 is reached by observing quiet weeks
 * after recorded remediation; a button would let an assertion stand in for that evidence.
 *
 * <b>Where there is no action, the screen says what it is waiting for rather than nothing.</b>
 * `nextActionKo` returns null in three states, and in those the honest report is what reviewnary is
 * doing — silence reads as a screen that has forgotten the problem.
 */
export function IssueDecision({
  issueId,
  state,
  note,
  onNoteChange,
  busy,
  error,
  onSubmit,
}: {
  issueId: string;
  state: IssueLifecycleState;
  note: string;
  onNoteChange: (note: string) => void;
  busy: boolean;
  error: string | null;
  /** null when the screen docks the action at the floor of the pane and this block draws the field only. */
  onSubmit: (() => void) | null;
}) {
  const waiting = waitingNoteKo(state);
  const actionLabel = nextActionKo(state);
  const actionable = actionLabel !== null;

  return (
    <PaneBlock label="판단과 조치">
      {/* Opportunity Engine v1 — what can be done about this. Always drawn, so the seller learns the
          product HAS this layer even on a problem that yields nothing. It keeps its own region name and
          loses its heading: 판단과 조치 is the block, and a second title inside it named the mechanism
          rather than the question. */}
      <section aria-label="개선 기회">
        <OpportunityList issueId={issueId} />
      </section>

      {waiting ? <p className="break-keep leading-relaxed text-muted">{waiting}</p> : null}

      {actionable ? (
        <div className="space-y-2">
          <label htmlFor="issue-decision-note" className="block text-xs font-semibold text-muted">
            무엇을 하기로 하셨나요 (선택)
          </label>
          <textarea
            id="issue-decision-note"
            value={note}
            onChange={(event) => onNoteChange(event.target.value)}
            rows={2}
            placeholder="예) 접착 테이프 공급처를 바꾸고 8월 출고분부터 적용합니다."
            className="w-full break-keep rounded-xl border border-line bg-surface p-3 text-sm leading-relaxed text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          />
          <p className="break-keep text-xs leading-relaxed text-muted">
            남기신 내용은 아래 기록에 그대로 남습니다.
          </p>
          {onSubmit ? (
            <Btn size="sm" onClick={onSubmit} disabled={busy}>
              {busy ? "기록 중…" : actionLabel}
            </Btn>
          ) : null}
        </div>
      ) : null}

      {error ? <p className="text-sm text-bad">{error}</p> : null}
    </PaneBlock>
  );
}
