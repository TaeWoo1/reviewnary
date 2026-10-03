/**
 * <b>Who wrote the version on screen, and what the section may therefore be called.</b>
 *
 * Pilot QA pass 1 (2026-09-06) found the inquiry panel heading 「AI가 준비한 답변」 standing over a
 * version the SELLER had written. On the live org the model's v1 ended
 * 「추가 정보 주시면 최대한 정확히 추천드리겠습니다」 and the seller's v2 replaced that sentence with
 * 「전화로 문의 주시면 바로 확인해 드리겠습니다」 — a support channel no registered knowledge mentions.
 * The stored row said `author_kind = SELLER`; the screen simply never read it, so the seller's own
 * sentence was presented back to them as the assistant's work.
 *
 * That is the failure `Agent.tsx`'s `draftKindLabel` already names in its docblock — "calling a
 * template AI" — for the OTHER seam (a run's provenance). This is the same rule for the seam the
 * inquiry screen reads: the append-only draft ledger's own `authorKind`.
 *
 * <b>Nothing is derived.</b> The word comes from the stored value; an author this build does not
 * know gets the neutral heading and no claim about who wrote it, because a version whose author we
 * cannot name is not a version we may attribute.
 */

/** The author kinds the draft ledger records. `null` = no version yet, or one written before v1. */
export type DraftAuthor = "MODEL" | "RULE" | "SELLER" | "SELLER_APPROVED_FALLBACK" | null | undefined;

/** The neutral name for the answer section — used whenever nothing may be attributed to the AI. */
const NEUTRAL_HEADING = "답변 초안";

/**
 * The heading for the answer section.
 *
 * <b>누가 썼는지는 여전히 읽지만, 「AI」를 제목에 걸지는 않는다</b> (문의 canonical, 2026-10-03,
 * product-owner decision). 판매자가 이 블록에서 알아야 할 것은 <b>보낼 수 있는 답변이 준비돼 있는가</b>이고,
 * 그것을 모델이 썼다는 사실은 그 다음이다 — 작성자는 사라지지 않고 {@link draftAuthorLine}으로 기록에 남는다.
 * 이 파일이 막으려던 결함(판매자가 고쳐 쓴 버전을 AI 작품으로 돌려주는 것)은 그대로 막힌다: 판매자가 쓴
 * 버전은 여전히 「내가 쓴 답변」이고, 알 수 없는 작성자는 여전히 아무것도 주장하지 않는다.
 *
 * `null`(아직 아무것도 없음)은 「답변」이다 — 없는 초안을 「준비된」이라고 부르지 않는다.
 */
export function draftSectionHeading(author: DraftAuthor, hasDraft: boolean): string {
  if (!hasDraft) return "답변";
  switch (author) {
    case "MODEL":
      return "준비된 답변";
    case "SELLER":
      return "내가 쓴 답변";
    case "SELLER_APPROVED_FALLBACK":
      return "등록한 안내 문구";
    // A rule-written version is not AI, and saying so is the whole point of this file.
    case "RULE":
      return NEUTRAL_HEADING;
    default:
      return NEUTRAL_HEADING;
  }
}

/**
 * 초안 옆에 붙는 줄: <b>몇 번째 버전인가</b>.
 *
 * <p>작성자는 여기서 빠졌다(문의 canonical, 2026-10-03). 초안 바로 옆은 판매자가 <b>이 문장을 보낼지</b>
 * 판단하는 자리이고, 거기서 「AI 작성」은 판단을 돕는 사실이 아니라 제품이 자기 자랑을 하는 자리였다.
 * 작성자는 {@link draftAuthorLine}이 기록에서 말한다 — 없어진 것이 아니라 제자리로 갔다.
 */
export function draftProvenanceLine(_author: DraftAuthor, version: number | null | undefined): string | null {
  if (version == null) return null;
  return `버전 ${version}`;
}

/**
 * <b>누가 이 버전을 썼나</b> — 기록이 묻는 질문의 답.
 *
 * <p>작성자를 모르면 `null`이고, 그때 기록은 작성자 줄을 아예 그리지 않는다. 모르는 작성자를 지어내는 것이
 * 이 파일이 존재하는 이유인 결함 그 자체다.
 */
export function draftAuthorLine(author: DraftAuthor): string | null {
  switch (author) {
    case "MODEL":
      return "AI 작성";
    case "SELLER":
      return "판매자 수정";
    case "SELLER_APPROVED_FALLBACK":
      return "판매자가 등록한 문구";
    case "RULE":
      return "기본 문구";
    default:
      return null;
  }
}
