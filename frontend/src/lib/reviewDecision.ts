import { kstDate } from "./format";
import type { ReviewDecisionLogEntry, ReviewTriageTier, TriageDisposition } from "./types";
import { TRIAGE_TIER_LABEL } from "./reviewTriage";
import { TRIAGE_OPTIONS } from "./vocItems";

/**
 * How the Decision Workspace reads — the words for the decisions the product already records.
 *
 * <b>No new vocabulary.</b> Every label here maps a value that already exists on the wire onto a
 * sentence: the response decision is `TriageDisposition`, the seller's judgment is
 * `ReviewTriageTier`, the recorded act is `TriageActionKind`, the approval is
 * `ReviewReplyApprovalState`, the outcome is `OperatorOutcomeName`. A workspace that invented its
 * own words for these would be a second taxonomy for the same five facts, and the second taxonomy is
 * the one that drifts.
 */

/**
 * What the 조치 선택 step promises, and it depends on the channel.
 *
 * <b>No second label vocabulary.</b> The three buttons keep the words they already had
 * (`TRIAGE_OPTIONS`: 대응 필요 / 두고 보기 / 조치 불필요) — a workspace that renamed them would leave the
 * worklist, the record and the audit trail describing the same three values in different words. What
 * the workspace adds is the sentence UNDER them, and that sentence depends on why a draft is or is
 * not on offer. There are three reasons and they are three different facts: the channel has a reply
 * flow; the channel has none at all (Coupang 상품평, per the capability table); or the channel has one
 * and this org has no account on it, which is a fact about this seller's setup and not about the
 * marketplace. Saying the second when the third is true tells a seller their channel cannot do
 * something it can — which is exactly the sentence they would stop trying to fix.
 */
export const DECISION_ACTION_NOTE = {
  withReply:
    "「대응 필요」로 정하면 아래에서 답변 초안을 준비하고 승인할 수 있습니다. 승인한 답변은 판매자님이 판매자센터에 직접 올리십니다.",
  withoutReply:
    "이 채널에서는 reviewnary가 답변을 작성하지 않습니다. 조치는 판매자님이 직접 하시고, 여기에는 무엇으로 정했는지만 기록됩니다.",
  withoutAccount:
    "이 채널에 연결된 판매 계정이 없어 답변 초안은 준비할 수 없습니다. 조치는 판매자님이 직접 하시고, 여기에는 무엇으로 정했는지만 기록됩니다.",
} as const;

/** The short word for a recorded decision — the same three the worklist and the record use. */
export const DECISION_ACTION_WORD: Record<TriageDisposition, string> =
  Object.fromEntries(TRIAGE_OPTIONS.map((o) => [o.value, o.label])) as Record<TriageDisposition, string>;

/**
 * 완료 기록 — what the seller did off this screen.
 *
 * <b>조치 불필요 is deliberately absent.</b> `TriageActionKind.ACTION_NOT_NEEDED` exists and is still
 * written by the pilot's controls on the record screen, but the workspace's own 조치 선택 step already
 * records exactly that statement as `NO_ACTION`, with its own audit trail. Offering both would put one
 * press into two spines at two evidential strengths — the thing the triage contract §5-C says must not
 * happen — so this screen records the two acts the decision spine has no word for, and no more.
 */
export const DECISION_DONE_LABEL = {
  ACTION_STARTED: "조치 시작함",
  ACTION_COMPLETED: "조치 완료함",
} as const;

export type DecisionDoneKind = keyof typeof DECISION_DONE_LABEL;

/** The recorded act as the log says it. Covers the pilot's third value, which the log can still read. */
const ACTION_KIND_WORD: Record<string, string> = {
  ACTION_STARTED: "조치를 시작했다고 기록",
  ACTION_COMPLETED: "조치를 완료했다고 기록",
  ACTION_NOT_NEEDED: "조치가 필요 없다고 기록",
  REPLY_DRAFTED: "답변 초안을 작성했다고 기록",
  REPLY_SUBMITTED: "답변을 올렸다고 기록",
};

const APPROVAL_WORD: Record<string, string> = {
  APPROVED: "답변을 승인",
  WITHDRAWN: "답변 승인을 되돌림",
};

const OUTCOME_WORD: Record<string, string> = {
  OPERATOR_REPORTED_SUBMITTED: "판매자센터에 올렸다고 기록",
  SUBMISSION_ABORTED: "올리지 않고 중단했다고 기록",
};

/**
 * What reviewnary itself did, and what it could confirm — `review_reply_execution` (Review Delivery Truth
 * Spine v1). The `to` token of a `REPLY_EXECUTION` entry: the verification where there was one to make, and the
 * status where there was not.
 *
 * <b>Every sentence here says exactly how much was proven, and no more.</b> `VERIFIED` is the only one that
 * claims the channel holds the approved text, because a hash read-back is the only thing that shows it. The two
 * guided-lane words stop at what the collector saw — a composer that holds the text, a submit that was pressed —
 * and `SUBMISSION_OBSERVED_CONTENT_UNVERIFIED` names the gap out loud rather than rounding it up to 등록 완료,
 * which is the whole reason NAVER's lane has a ceiling.
 */
const EXECUTION_WORD: Record<string, string> = {
  // Verifications (`to`, where reviewnary could confirm something)
  VERIFIED: "채널에 답변을 등록하고 내용까지 확인함",
  STATUS_UNRESOLVED: "채널에 답변을 등록했지만 내용이 일치하는지 확인하지 못함",
  DELIVERY_UNKNOWN: "채널에 답변을 보냈지만 등록됐는지 확인하지 못함",
  UNVERIFIABLE: "채널에 답변을 보냈지만 확인 자체를 하지 못함",
  COMPOSER_FILLED: "판매자센터 답변창에 승인한 답변을 넣음",
  SELLER_SUBMISSION_OBSERVED: "판매자센터에서 판매자가 등록을 누른 것을 확인함",
  SUBMISSION_OBSERVED_CONTENT_UNVERIFIED: "채널에 답변이 생긴 것을 확인함 — 내용은 확인하지 못함",
  // Statuses (`to`, where there was nothing to confirm)
  REFUSED: "답변을 보내지 않음",
};

function tierWord(value: string | null): string | null {
  if (!value) return null;
  return TRIAGE_TIER_LABEL[value as ReviewTriageTier] ?? null;
}

/**
 * One log entry as a sentence, or null when nothing honest can be said about it.
 *
 * <b>Null is a real outcome and must render as nothing.</b> Every value here is a closed enum, but the
 * server's vocabulary can grow ahead of this screen — and a row rendered as its own raw token
 * (`ACTION_RECORDED · REPLY_DRAFTED`) is the internal-word leak this product removes from screens
 * everywhere else. An entry this build cannot name is an entry it does not draw.
 */
export function decisionLogSentence(entry: ReviewDecisionLogEntry): string | null {
  switch (entry.kind) {
    case "SELLER_JUDGMENT_SET": {
      const to = tierWord(entry.to);
      if (!to) return null;
      const from = tierWord(entry.from);
      return from ? `판매자 판단을 ${from}에서 ${to}로 바꿈` : `판매자 판단을 ${to}(으)로 기록`;
    }
    case "SELLER_JUDGMENT_WITHDRAWN":
      return "판매자 판단을 되돌림";
    case "ACTION_CHOSEN": {
      const to = entry.to ? DECISION_ACTION_WORD[entry.to as TriageDisposition] : null;
      return to ? `조치를 ${to}(으)로 정함` : null;
    }
    case "ACTION_RECORDED":
      return entry.to ? (ACTION_KIND_WORD[entry.to] ?? null) : null;
    case "REPLY_APPROVAL":
      return entry.to ? (APPROVAL_WORD[entry.to] ?? null) : null;
    case "REPLY_OUTCOME":
      return entry.to ? (OUTCOME_WORD[entry.to] ?? null) : null;
    case "REPLY_EXECUTION":
      return entry.to ? (EXECUTION_WORD[entry.to] ?? null) : null;
    default:
      return null;
  }
}

/**
 * <b>The clauses that qualify the evidence</b>, said once wherever the evidence is drawn.
 *
 * <p>Both readings need them and they are the same claim in both: the full case prints them under their own
 * blocks, the preview collects them into the one footnote under 근거. A second copy of either would be the copy
 * that stops matching.
 */
export const EVIDENCE_NOTE = {
  /** Why 「기록된 반복 문제 없음」 is not 「반복된 적 없음」, and what would change it. */
  repeatCriterion: "같은 문제를 말한 리뷰가 쌓이면 반복 문제로 모입니다 — 위의 자동 분류와는 다른 기준입니다.",
  /** What the counts count: what is FILED, not what a draft used. */
  countsAreFiled: "여기 있는 숫자는 등록된 자료의 수입니다.",
} as const;

/**
 * <b>현재 판단 — where this review stands, as one or two state tokens</b> (reference-based hierarchy v2).
 *
 * <p>It was 「처리 방법을 아직 정하지 않았습니다. 기록된 판단도 아직 없습니다.」 — 37 characters of prose at the
 * same weight as the customer's sentence, for two absences. Linear's Peek says the equivalent as 「● In Review ·
 * No priority」 and nothing more. So does this: the decision that stands, and how far the trail behind it goes.
 *
 * <p><b>The newest entry's own sentence moves to the full case</b>, where {@link DecisionLog} prints the whole
 * trail newest-first. What this keeps of it is what a preview is asked: is there a record, when was the last
 * one, and how many more. Nothing summarises the record away — the case the one CTA opens holds all of it.
 *
 * <p><b>A log that could not be read says nothing about the log.</b> Then this reports the decision alone,
 * exactly as {@link DecisionLog} renders nothing rather than an empty state it cannot vouch for.
 */
export function previewJudgmentTokens(
  decision: TriageDisposition | null,
  entries: ReviewDecisionLogEntry[],
  failed: boolean,
): string {
  const chosen = decision ? DECISION_ACTION_WORD[decision] : "처리 방법 미정";
  if (failed) return chosen;
  const dated = entries
    .map((entry) => ({ at: entry.at, sentence: decisionLogSentence(entry) }))
    .filter((row): row is { at: string; sentence: string } => row.sentence !== null);
  if (dated.length === 0) return `${chosen} · 판단 기록 없음`;
  const [newest, ...rest] = dated;
  return `${chosen} · 최근 기록 ${kstDate(newest.at)}${rest.length > 0 ? ` 외 ${rest.length}건` : ""}`;
}

/**
 * The one sentence the whole workspace rests on, said once at the bottom.
 *
 * A seller who has just recorded four things is entitled to know that none of them left the building.
 *
 * <b>It is not unconditional, and pretending it is would be the only lie on this screen.</b> On a channel with
 * API execution (Cafe24) reviewnary does post the approved reply, and a log that now shows that act
 * (`REPLY_EXECUTION`) cannot be captioned "마켓플레이스에는 아무것도 전송되지 않습니다". Use
 * {@link decisionLogDisclosure} and let the entries decide which sentence is true; this constant stays as the
 * no-execution wording it has always been.
 */
export const DECISION_LOG_DISCLOSURE =
  "여기 기록한 판단과 조치는 reviewnary 안에만 남습니다. 마켓플레이스에는 아무것도 전송되지 않습니다.";

/** Said instead, once a reply has actually been executed at the channel from this screen's own record. */
export const DECISION_LOG_DISCLOSURE_EXECUTED =
  "여기 기록한 판단은 reviewnary 안에만 남습니다. 채널로 전송된 것은 아래에 기록된 답변뿐입니다.";

/**
 * Which of the two sentences is true for this review.
 *
 * <p>Decided from the log rather than from a channel capability flag, because the claim is about what HAPPENED
 * on this review and not about what the channel can do: a Cafe24 review nobody replied to has had nothing sent,
 * and saying otherwise would make the safety line vaguer for every review in order to be accurate for a few.
 */
export function decisionLogDisclosure(entries: ReviewDecisionLogEntry[]): string {
  return entries.some((entry) => entry.kind === "REPLY_EXECUTION" && entry.to !== "REFUSED")
    ? DECISION_LOG_DISCLOSURE_EXECUTED
    : DECISION_LOG_DISCLOSURE;
}

/**
 * <b>The preview's one safety line</b> — the claim the whole workspace rests on, and only that.
 *
 * <p>This was 114 characters carrying four claims: that reviewnary does not write on this channel, that the
 * seller acts, that the record stays inside, and that nothing is sent. The first two are facts about the channel
 * and they are rendered in the full case in two places already ({@link DecisionActionStep} and the 「왜 초안이
 * 없는가」 block); a preview has no draft area whose absence they would explain. The last two are the same
 * boundary from two sides, and the operative one — the one a seller checks before recording anything — is that
 * nothing leaves. Said once, at 12px, under everything.
 *
 * <p><b>This is narrower than the sentence it replaces</b>, and deliberately so rather than by omission:
 * 「reviewnary 안에만 남습니다」 also rules out destinations other than the marketplace. The full case keeps
 * {@link DECISION_LOG_DISCLOSURE} whole.
 */
export const PREVIEW_SAFETY_LINE = "마켓플레이스로는 아무것도 전송되지 않습니다.";
