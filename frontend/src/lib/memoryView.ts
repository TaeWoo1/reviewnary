// Pure view logic for 고객운영 메모리. No React, no I/O.
//
// Sits on top of `reviewIssuesView`, which owns the wording rules (no cause, 해결됨 is never
// described as disappearance). This module adds only what the two-pane surface needs: grouping,
// deep-link resolution, and the one rule that decides whether an evidence row may link anywhere.

import type { ReviewIssueView } from "./types";
import { changedIssues, improvedIssues, steadyIssues } from "./reviewIssuesView";

export type IssueSelection =
  | { kind: "NONE" }
  | { kind: "FOUND"; issue: ReviewIssueView }
  | { kind: "MISSING"; issueId: string };

/**
 * Resolves the deep-linked issue against every loaded issue — not against the visible group — so a
 * shared link opens its issue regardless of how the reader's list happens to be arranged.
 */
export function resolveIssueSelection(
  issues: readonly ReviewIssueView[],
  issueId: string | undefined,
): IssueSelection {
  if (!issueId) {
    return { kind: "NONE" };
  }
  const issue = issues.find((candidate) => candidate.id === issueId);
  return issue ? { kind: "FOUND", issue } : { kind: "MISSING", issueId };
}

export interface IssueGroup {
  key: "changed" | "steady" | "improved";
  heading: string;
  issues: ReviewIssueView[];
}

/**
 * Three mutually exclusive groups, worst first.
 *
 * An issue carrying both a warning judgement and IMPROVED belongs under 확인 필요: the surface is a
 * call to look, and filing a still-warning issue under good news would bury it.
 */
export function groupIssues(issues: readonly ReviewIssueView[]): IssueGroup[] {
  const list = [...issues];
  const changed = changedIssues(list);
  const changedIds = new Set(changed.map((issue) => issue.id));
  const improved = improvedIssues(list).filter((issue) => !changedIds.has(issue.id));

  return [
    { key: "changed", heading: "확인 필요", issues: changed },
    { key: "steady", heading: "지켜보는 중", issues: steadyIssues(list) },
    { key: "improved", heading: "개선됨", issues: improved },
  ].filter((group) => group.issues.length > 0) as IssueGroup[];
}

/** "마지막 확인 2026-08-02", or null when nothing has been seen. */
export function lastSeenLabel(issue: ReviewIssueView): string | null {
  return issue.lastEvidenceOn ? `마지막 확인 ${issue.lastEvidenceOn}` : null;
}

/** Evidence-count line. Always a real count — the server sends it. */
export function evidenceCountLabel(issue: ReviewIssueView): string {
  return `근거 ${issue.evidenceCount}건`;
}

export type IssueGroupKey = IssueGroup["key"];

/** Worst first, and the same three groups {@link groupIssues} files into — as a closed, always-present set. */
export const ISSUE_GROUP_ORDER: readonly IssueGroupKey[] = ["changed", "steady", "improved"] as const;

export const ISSUE_GROUP_LABEL: Record<IssueGroupKey, string> = {
  changed: "확인 필요",
  steady: "지켜보는 중",
  improved: "개선됨",
};

/**
 * The issues in one group, by the same rule {@link groupIssues} uses — including the one that decides a
 * warning issue carrying IMPROVED belongs under 확인 필요 and not under good news.
 *
 * <b>Why this exists beside `groupIssues`.</b> The canonical list draws the three groups as tabs rather
 * than as headings, and a tab has to state its count even when it is empty: 「확인 필요 0」 is the thing a
 * seller opens this screen to learn, and a heading that simply does not appear cannot say it.
 */
export function issuesInGroup(
  issues: readonly ReviewIssueView[],
  key: IssueGroupKey,
): ReviewIssueView[] {
  const list = [...issues];
  if (key === "changed") return changedIssues(list);
  if (key === "steady") return steadyIssues(list);
  const changedIds = new Set(changedIssues(list).map((issue) => issue.id));
  return improvedIssues(list).filter((issue) => !changedIds.has(issue.id));
}

/**
 * The 분류 values present in the loaded issues, in the order they appear.
 *
 * <b>This is the issue's own aspect, never an item-analysis category.</b> `ReviewIssueView.aspect` is one
 * half of the issue signature the memory groups by (aspect + problem); `item_analyses.category` is the
 * per-review keyword tally that the 리뷰 screen deliberately stopped showing. Mixing them would put a
 * review-level classifier's vocabulary on a screen whose objects are issues.
 */
export function aspectChoices(issues: readonly ReviewIssueView[]): string[] {
  const seen: string[] = [];
  for (const issue of issues) {
    if (issue.aspect && !seen.includes(issue.aspect)) seen.push(issue.aspect);
  }
  return seen;
}

/**
 * The honest provenance line for the whole list.
 *
 * `provenanceKo` speaks for one issue; a list footer speaks for all of them, and the two sentences differ
 * only in whether every candidate came from the rule-based extractor. Claiming 「규칙 기반」 over a list
 * that holds something else would be the footer overstating what it knows.
 */
export function listProvenanceKo(issues: readonly ReviewIssueView[]): string {
  const allRuleBased = issues.length > 0 && issues.every((issue) => issue.extractorKind === "RULE_BASED");
  return allRuleBased
    ? "규칙 기반 분석으로 모은 이슈 후보입니다. 최종 진단이 아닙니다."
    : "이슈 후보입니다. 최종 진단이 아닙니다.";
}

/**
 * 「조치 중 · 2026-09-12부터」 — the state the problem is in and when it entered it.
 *
 * <b>The date comes from the trail, never from now.</b> It is the most recent recorded transition INTO
 * the state the issue is in; when the detail read failed or holds no such entry the sentence is the
 * state alone, because a 「…부터」 with a date nothing recorded would be the screen inventing a history.
 */
export function lifecycleSinceKo(
  issue: ReviewIssueView,
  history: readonly { toState: string; at: string }[] | null,
  formatDate: (iso: string) => string,
): string {
  const entering = (history ?? []).filter((event) => event.toState === issue.lifecycleState);
  const entered = entering.length > 0 ? entering[entering.length - 1] : null;
  return entered ? `${issue.lifecycleLabelKo} · ${formatDate(entered.at)}부터` : issue.lifecycleLabelKo;
}
