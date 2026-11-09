import type { InquiryDetail } from "./types";

/**
 * <b>What the screen actually knows about the answer on the channel</b> (2026-11-06 sixteenth review: 「화면이 무엇을 실제로
 * 확인했는지 말하는 부분」). Three facts, and the screen may say only what it has:
 *
 * <ul>
 *   <li>{@code COLLECTED} — a collection read the answer's text off the channel. That text is what the customer got; our
 *       draft is shown beside it, folded, and never in its place.</li>
 *   <li>{@code STATUS_ONLY} — the channel says it is answered (or our own read-back after a send says so), but no text came
 *       with it.</li>
 *   <li>{@code NONE} — not even that.</li>
 * </ul>
 *
 * {@code sentByUs}: SellerOps registered the approved draft itself and read the registration back — then the registered
 * text is ours, unless a collection shows something else.
 *
 * {@code latest} (2026-11-06 seventeenth review): the last collection carried this text. When it did not, the text is the
 * last one collected — shown as such, with its own read time ({@code bodyCheckedAt}, null when never recorded) apart from
 * the time the status was last seen ({@code statusCheckedAt}).
 */
export type AnswerRecord =
  | {
      kind: "COLLECTED";
      body: string;
      latest: boolean;
      bodyCheckedAt: string | null;
      statusCheckedAt: string | null;
      sameAsDraft: boolean | null;
      sentByUs: boolean;
    }
  | { kind: "STATUS_ONLY"; sentByUs: boolean }
  | { kind: "NONE" };

export function answerRecordOf(detail: InquiryDetail | null): AnswerRecord {
  if (!detail) return { kind: "NONE" };
  const sentByUs = detail.delivery?.verified === true;
  const a = detail.channelAnswer;
  if (a && a.body && a.body.trim()) {
    return {
      kind: "COLLECTED",
      body: a.body,
      latest: a.latest === true,
      bodyCheckedAt: a.bodyCheckedAt ?? null,
      statusCheckedAt: a.statusCheckedAt ?? null,
      sameAsDraft: a.sameAsDraft,
      sentByUs,
    };
  }
  if (sentByUs || detail.status === "ANSWERED") return { kind: "STATUS_ONLY", sentByUs };
  return { kind: "NONE" };
}

/**
 * Whether the screen knows the registered answer says what our draft says — the only case in which it may tell the seller
 * what the customer was told (「진행 상황을 안내했어요」). A collected text that matches, or our own send with no collected
 * text saying otherwise. A text that matched at an earlier read is only as good as that read: without our own send behind
 * it, an answer edited on the seller centre since then is not something the screen would know about.
 */
export function registeredTextIsOurs(r: AnswerRecord): boolean {
  if (r.kind === "COLLECTED") return r.sameAsDraft === true && (r.latest || r.sentByUs);
  if (r.kind === "STATUS_ONLY") return r.sentByUs;
  return false;
}

/**
 * Whether our draft steps aside for what the channel shows: a collected text, or a registration whose text we did not send.
 * Our draft is then kept, folded, as 「Reviewnary에서 준비한 초안」.
 */
export function draftStepsAside(r: AnswerRecord): boolean {
  return r.kind === "COLLECTED" || (r.kind === "STATUS_ONLY" && !r.sentByUs);
}
