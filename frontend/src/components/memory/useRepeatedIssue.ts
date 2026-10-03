import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/apiClient";
import type { RepeatedIssueContext, ReviewIssueDetailView, ReviewIssueView } from "../../lib/types";

/**
 * The states a seller may START remediation from — the client's half of
 * `IssueLifecycleState.sellerMayStartActing()`, which refuses anything this set does not allow. Kept as a
 * set beside the call that branches on it so widening one state cannot silently turn a start into a
 * completion.
 */
const ACTING_STATES = new Set<ReviewIssueView["lifecycleState"]>(["OBSERVING", "NEEDS_REVIEW"]);

export interface RepeatedIssueWorkspace {
  detail: ReviewIssueDetailView | null;
  context: RepeatedIssueContext | null;
  loading: boolean;
  /** The detail read failed — the evidence and the record are unknown, the rest of the pane still stands. */
  failed: boolean;
  /** The repeat-context read failed — where it repeats and what the library says are unknown. */
  contextFailed: boolean;
  note: string;
  setNote: (note: string) => void;
  busy: boolean;
  error: string | null;
  submit: () => void;
}

/**
 * <b>One repeated problem's reads and its one write, owned in one place.</b>
 *
 * <p>The pane used to own all of this inside the component that drew it, which worked while the button
 * stood inside the same component. The canonical docks the action at the floor of the pane — outside the
 * scroller, rendered by the screen — and the seller's note stays up in 판단과 조치 where it is written. A
 * button and the field it submits cannot live in two components each holding its own copy of the state,
 * so the state moved out of both of them.
 *
 * <p><b>Two reads, settled independently.</b> Neither block may be held back by the other's latency, and
 * neither may be blanked by the other's failure. Unchanged from the panel this came out of.
 *
 * <p><b>No new call.</b> The same detail read, the same repeat-context read, the same two lifecycle
 * transitions the API has always accepted. There is still no 해결 처리 at any state.
 */
export function useRepeatedIssue(
  issue: ReviewIssueView | null,
  onIssueChanged: (next: ReviewIssueView) => void,
): RepeatedIssueWorkspace {
  const issueId = issue?.id ?? null;
  const [detail, setDetail] = useState<ReviewIssueDetailView | null>(null);
  const [context, setContext] = useState<RepeatedIssueContext | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [contextFailed, setContextFailed] = useState(false);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!issueId) {
      setDetail(null);
      setContext(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setFailed(false);
    setContextFailed(false);
    const [detailResult, contextResult] = await Promise.allSettled([
      api.getReviewIssueDetailStrict(issueId),
      api.getRepeatedIssueContextStrict(issueId),
    ]);
    if (detailResult.status === "fulfilled") {
      setDetail(detailResult.value);
    } else {
      setDetail(null);
      setFailed(true);
    }
    if (contextResult.status === "fulfilled") {
      setContext(contextResult.value);
    } else {
      setContext(null);
      setContextFailed(true);
    }
    setLoading(false);
  }, [issueId]);

  useEffect(() => {
    // A different problem is a different note: carrying the sentence across would file one problem's
    // decision under another's record.
    setNote("");
    setError(null);
    void load();
  }, [load]);

  const submit = useCallback(async () => {
    if (!issue) return;
    setBusy(true);
    setError(null);
    try {
      const trimmed = note.trim();
      // Which transition this is, asked of the STATE rather than compared to one constant — see
      // ACTING_STATES. The moment OBSERVING joined NEEDS_REVIEW, a `=== "NEEDS_REVIEW"` test made an
      // observed problem's 조치 시작 button call 조치 완료로 기록.
      const next = ACTING_STATES.has(issue.lifecycleState)
        ? await api.startReviewIssueAction(issue.id, trimmed || undefined)
        : await api.markReviewIssueRemediated(issue.id, trimmed || undefined);
      onIssueChanged(next);
      setNote("");
      await load();
    } catch {
      setError("상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  }, [issue, note, onIssueChanged, load]);

  return { detail, context, loading, failed, contextFailed, note, setNote, busy, error, submit };
}
