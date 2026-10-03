import type { ReviewIssueView } from "../../lib/types";
import { IssueReading } from "./IssueReading";
import { useRepeatedIssue } from "./useRepeatedIssue";

/**
 * The repeated-problem reading, reading its own two endpoints — for a screen that shows one problem in a
 * pane without docking an action under it (오늘's 반복 문제 selection).
 *
 * <p>반복 문제 itself does not use this: that screen owns the workspace state because its primary action
 * is docked at the floor of the pane, outside the scroller the field lives in, and a button and the field
 * it submits cannot each hold their own copy.
 */
export function IssueDetailPanel({
  issue,
  onIssueChanged,
}: {
  issue: ReviewIssueView;
  onIssueChanged: (next: ReviewIssueView) => void;
}) {
  const workspace = useRepeatedIssue(issue, onIssueChanged);
  return <IssueReading issue={issue} workspace={workspace} />;
}
