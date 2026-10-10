import { useCallback, useEffect, useState } from "react";
import { api } from "../../../lib/apiClient";
import type { OpportunityOutcomeView } from "../../../lib/types";
import { OpportunityOutcomeList } from "../../opportunity/OpportunityOutcome";
import { PaneBlock } from "./PaneBlock";

/**
 * <b>한 일과 그 결과</b> — the improvements the seller applied to this problem, and what the reviews did.
 *
 * <b>Why this is read by the PROBLEM and not by the suggestion.</b> An improvement opportunity is derived on
 * every read and stops being derived once the issue resolves, once the seller dismisses it, or once their
 * library starts mentioning the aspect — which is precisely what saving the applied draft does. So the card
 * that carried a 적용 can be gone by the next page load, while the record of having applied it has to outlive
 * that. Same rows, a different address.
 *
 * <b>It fetches itself, so a failure here costs nothing else on the page.</b> The same shape
 * {@link OpportunityList} uses on this screen: a read that fails says so, and a problem with nothing applied
 * renders nothing at all rather than an empty block with a name on it — a named empty block reads as 「we
 * measured and found nothing」, which is a claim.
 */
export function IssueOutcomes({ issueId }: { issueId: string }) {
  const [outcomes, setOutcomes] = useState<OpportunityOutcomeView[] | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setFailed(false);
    try {
      setOutcomes(await api.getOpportunityOutcomes(issueId));
    } catch {
      setOutcomes(null);
      setFailed(true);
    }
  }, [issueId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (failed) {
    return (
      <PaneBlock label="한 일과 그 결과">
        <p className="text-xs text-muted">적용 기록을 불러오지 못했습니다.</p>
      </PaneBlock>
    );
  }
  if (outcomes == null || outcomes.length === 0) {
    return null;
  }
  return (
    <PaneBlock label="한 일과 그 결과">
      <OpportunityOutcomeList outcomes={outcomes} />
    </PaneBlock>
  );
}
