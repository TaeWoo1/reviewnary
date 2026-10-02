import type { StatusTone } from "../components/ui/Status";

/**
 * The one set of words this product uses for 「이 일이 어디까지 왔나」.
 *
 * <b>Why it is one table.</b> The surfaces grew their words separately, and by 2026-09-04 a single
 * review row in 내 답변 작업 wore four of them at once — 승인 대기 · 상태 미상 · 대응 필요 · 기타 —
 * while the 문의 list next door said 초안 준비됨 for the same idea and 설정 called one screen's rules
 * by a name that screen no longer used. A seller cannot learn a vocabulary that is re-invented per
 * page, so every seller-facing work word is declared here, once, next to the fact that proves it.
 *
 * <b>Each word names a fact, never an inference.</b> That is the rule this table exists to keep: a
 * screen may only say one of these when the field it points at says so. It is how 「초안 준비됨」 was
 * caught — it was being read off the work-item PHASE, and a phase is written when a proposal is
 * recorded, which stores no reply text at all. Eight of the demo org's ten 「초안 준비됨」 rows had no
 * draft.
 *
 * <b>What is deliberately NOT merged.</b> 답변 필요 and 초안 필요 look like one state and are two:
 * the first is about the CUSTOMER (nobody has answered them), the second about the SELLER'S OWN WORK
 * (they put this review on their list and have not written anything yet). Collapsing them would erase
 * the difference between an inbox and a worklist.
 */
export type WorkStateKey =
  /** 고객이 아직 답을 받지 못했다 — the channel's own answered/unanswered state. */
  | "REPLY_NEEDED"
  /** 확인 필요 — the stored triage tier says look at this. Never computed here. */
  | "NEEDS_LOOK"
  /** 초안이 실제로 쓰여 있다 — a draft VERSION exists (`hasDraft`), not a phase. */
  | "DRAFT_READY"
  /** 판매자가 답변하기로 했고 아직 아무것도 쓰지 않았다 — reply work committed, no draft. */
  | "DRAFT_NEEDED"
  /** 저장된 초안이 판매자의 승인을 기다린다 — a saved draft with no standing approval. */
  | "AWAITING_APPROVAL"
  /** 승인이 서 있다. 「완료」가 아니다 — the next step happens in the seller center. */
  | "APPROVED"
  /** 답변했다 — proven answered, by the channel or by a verified send. */
  | "ANSWERED";

export interface WorkStateWord {
  text: string;
  tone: StatusTone;
}

/**
 * Tones follow `Status`: `warn` = 「행동하기 전에 보라」, `info` = 「reviewnary가 무언가 준비해 뒀다」,
 * `bad` = 부정·실패, `neutral` = 참고. Nothing here is `good`: none of these words is a proven-finished
 * state, and 승인됨 least of all.
 *
 * <b>No work state is `bad`</b> (product-owner decision, 2026-10-01). 확인 필요 carried it, and 답변 필요
 * carried `warn`, which put the loudest tone in the palette on the most common row in the product: on the
 * live org 확인할 일 drew 46 rows and 25 of them were red. Red stopped meaning anything, and the thing it
 * is actually needed for — a collection that is blocked, an approval that failed, a channel that will not
 * connect — had no tone left that a seller would look twice at.
 *
 * <p>So the axis is restated: <b>`bad` is reserved for 실패 · 차단 · 실제 위험</b>, none of which is a
 * state a customer item passes through, and the two tones these words may take are
 * 「판매자가 봐야 한다」 (`warn`, amber) and 「reviewnary가 뭔가 해 뒀다」 (`info`, blue).
 *
 * <p>Per word, and why each one:
 * <ul>
 *   <li><b>확인 필요</b> — `warn`. The stored triage tier says look at this. That is exactly
 *       「행동하기 전에 보라」, which is what amber has always meant here.</li>
 *   <li><b>답변 필요</b> — `info`. A customer is waiting and nothing has failed; it is the ordinary
 *       state of an inbox row, and it is the state reviewnary's own queue put it in.</li>
 *   <li><b>초안 필요</b> — `warn`. The seller committed to replying and has written nothing: their own
 *       unfinished work, which is a 「봐야 한다」 and not a 「준비돼 있다」.</li>
 *   <li><b>초안 준비됨 · 승인 대기</b> — `info`. Both are 「reviewnary가 준비해 뒀다」 verbatim.</li>
 *   <li><b>승인됨 · 답변함</b> — `neutral`. Reference; neither is a thing waiting for anyone here.</li>
 * </ul>
 */
export const WORK_STATE: Record<WorkStateKey, WorkStateWord> = {
  REPLY_NEEDED: { text: "답변 필요", tone: "info" },
  NEEDS_LOOK: { text: "확인 필요", tone: "warn" },
  DRAFT_READY: { text: "초안 준비됨", tone: "info" },
  DRAFT_NEEDED: { text: "초안 필요", tone: "warn" },
  AWAITING_APPROVAL: { text: "승인 대기", tone: "info" },
  APPROVED: { text: "승인됨", tone: "neutral" },
  ANSWERED: { text: "답변함", tone: "neutral" },
};

export function workStateWord(key: WorkStateKey): WorkStateWord {
  return WORK_STATE[key];
}
