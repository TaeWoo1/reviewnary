import type { CommentCheckView, InquiryDetail } from "./types";

/**
 * <b>Cafe24 댓글 답변을 확인했는가 — 화면이 말하는 것</b> (2026-11-08, V184 저장 다음 단계).
 *
 * 서버(`CommentCheckView`)가 주는 네 단어를 판매자의 말로 옮긴다. 지키는 것:
 * <ul>
 *   <li>「확인 못 함」(`NOT_CHECKED`)은 조회가 실패했거나 다 보지 못했다는 기록이다. 「답변 없음」으로도, 판매자의 잘못으로도
 *       말하지 않는다.</li>
 *   <li>「확인 기록 없음」(`NO_RECORD`)은 한 번도 기록되지 않은 것 — 실패와 다른 문장이다.</li>
 *   <li>과거에 성공한 확인이 있으면 그 시각을 함께 말한다. 실패 시각을 성공 시각으로 쓰지 않는다.</li>
 *   <li>안내일 뿐이다. 이 파일의 어떤 값도 버튼을 막지 않는다 — 기존 차단 규칙은 그대로다.</li>
 * </ul>
 */

/** The time as the seller reads it, or null when the server gave none or it does not parse. */
export function checkTimeKo(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** Whether the answer is already known to be there — then a missing look is nothing to warn about. */
function answerKnown(detail: InquiryDetail): boolean {
  return detail.status === "ANSWERED" || detail.delivery?.verified === true || detail.phase === "COMPLETED";
}

export type CommentCheckFact = {
  /** Which of the stored states this sentence is about — also the test id suffix. */
  kind: "NOT_CHECKED" | "NOT_CHECKED_AFTER_SUCCESS" | "NO_RECORD" | "NO_SHOP_COMMENT" | "SHOP_ANSWERED";
  tone: "warn" | "muted";
  lead: string;
  /** When, in the seller's words; null when there is no time worth saying. */
  when: string | null;
};

/**
 * The fact about the look, for the inquiry itself (문의 상세). Null where nothing is worth saying: another channel, a thread
 * reply, or an answer already known to be there — except a proven shop comment, which says where that answer came from.
 */
export function commentCheckFact(detail: InquiryDetail | null): CommentCheckFact | null {
  const v: CommentCheckView | null | undefined = detail?.commentCheck;
  if (!detail || !v) return null;
  const attempted = checkTimeKo(v.attemptedAt);
  const last = checkTimeKo(v.lastCheckedAt);
  if (v.state === "SHOP_ANSWERED") {
    return {
      kind: "SHOP_ANSWERED",
      tone: "muted",
      lead: "판매자 댓글로 답변한 것을 확인했어요.",
      when: last ? `${last} 확인` : null,
    };
  }
  if (answerKnown(detail)) return null;
  switch (v.state) {
    case "NOT_CHECKED":
      return last
        ? {
            kind: "NOT_CHECKED_AFTER_SUCCESS",
            tone: "warn",
            lead: "최근 수집에서 댓글로 이미 답했는지 확인하지 못했어요.",
            when: `마지막으로 확인한 때 ${last}${attempted ? ` · 확인하지 못한 수집 ${attempted}` : ""}`,
          }
        : {
            kind: "NOT_CHECKED",
            tone: "warn",
            lead: "댓글로 이미 답했는지 확인하지 못했어요.",
            when: `${attempted ? `확인을 시도한 때 ${attempted} · ` : ""}확인에 성공한 기록은 아직 없어요`,
          };
    case "NO_RECORD":
      return {
        kind: "NO_RECORD",
        tone: "muted",
        lead: "댓글로 이미 답했는지 확인한 기록이 아직 없어요.",
        when: null,
      };
    case "NO_SHOP_COMMENT":
      return {
        kind: "NO_SHOP_COMMENT",
        tone: "muted",
        lead: "댓글로 단 판매자 답변은 없었어요.",
        when: last ? `${last} 확인` : null,
      };
    default:
      // A word this screen does not know is not a look it can describe.
      return null;
  }
}

/**
 * The sentence that stands before approval and registration — 「보내기」, 「초안 복사」, the second press. Only where the look
 * failed or never happened, and only while the answer is not already known to be there. Null otherwise.
 */
export function commentCheckWarning(detail: InquiryDetail | null): string | null {
  const v = detail?.commentCheck;
  if (!detail || !v || answerKnown(detail)) return null;
  if (v.state === "NOT_CHECKED") {
    const last = checkTimeKo(v.lastCheckedAt);
    return (
      "댓글로 이미 답했는지 확인하지 못했어요. 판매자센터에서 등록 여부를 확인해 주세요." +
      (last ? ` 마지막으로 확인한 때는 ${last}이에요.` : "")
    );
  }
  if (v.state === "NO_RECORD") {
    return "댓글로 이미 답했는지 확인한 기록이 없어요. 판매자센터에서 등록 여부를 확인해 주세요.";
  }
  return null;
}
