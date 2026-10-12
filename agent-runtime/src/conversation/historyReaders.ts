/**
 * **What the conversation already has.** Pure reads over a `ConversationView` and its working set —
 * the prior turn, the pending human actions, the last review row of a channel, the inquiry or review
 * a later sentence refers to. Lifted out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Every function here is a lookup: it takes a view (and an id) and returns what is already in it,
 * or `null`. None of them calls the backend, writes anything, or decides anything — a target this
 * module resolves is still judged by the service. That is why they read the same way in a test as in
 * a turn, and why they belong outside a 3,200-line class.
 *
 * <p>Directly under `src/conversation/` on purpose: the write fence walks that tree, and a file in a
 * subdirectory was outside it until 2026-10-12 (`docs/FINDINGS.md` §B10).
 */
import type {
  Artifact, ConversationView, ExecutableIdentity, InquiryItem, PendingHumanAction, ReviewItem,
  SelectedInquiry, SelectedObject, TurnView, WorkingSetView,
} from "./contract";
import type { DraftTarget, ReviewDraftTarget } from "./DraftPreparer";
import type { InquiryActionability } from "./inquiryActionability";
import { actionabilityOf } from "./inquiryActionability";

/**
 * **The shapes these lookups produce.** Both types used to live in `ConversationService.ts`, but the
 * functions that build them are here, so the declaration follows them — the service imports them back.
 * One direction, no cycle.
 */
/** The inquiry a sentence pointed at — by inquiry id; whether a draft can attach to it is a separate fact. */
export interface SelectedInquiryRow extends SelectedInquiry {
  readonly channelNameKo: string | null;
  readonly productName: string | null;
  readonly title: string | null;
  readonly status: string;
  readonly receivedAt: string | null;
  /** transient — the bounded opening of the customer's message the row was drawn with, when it had one. */
  readonly snippet?: string | null;
}

export type ResolvedTarget =
  | {
    readonly kind: "INQUIRY";
    readonly inquiry: SelectedInquiryRow;
    readonly actionability: InquiryActionability;
    /** Present only when the row is DRAFTABLE — the product's draft path needs the work item. */
    readonly target: DraftTarget | null;
    readonly executableIdentity: ExecutableIdentity;
    readonly sourceSubtype: string | null;
  }
  | { readonly kind: "REVIEW"; readonly target: ReviewDraftTarget; readonly executableIdentity: ExecutableIdentity };


export function precedingUserTurn(view: ConversationView, agentTurnId: string): TurnView | null {
  const at = view.turns.findIndex((t) => t.turnId === agentTurnId);
  for (let i = at - 1; i >= 0; i -= 1) {
    if (view.turns[i]!.role === "USER") return view.turns[i]!;
  }
  return null;
}

/** Every pending step the conversation holds — the list when the store has it, else the singular. */
export function pendingActionsOf(view: ConversationView): PendingHumanAction[] {
  if (view.pendingHumanActions && view.pendingHumanActions.length > 0) return [...view.pendingHumanActions];
  return view.pendingHumanAction ? [view.pendingHumanAction] : [];
}

/**
 * The inquiry a reference resolves to — the FOCUS of the conversation.
 *
 * <b>Selection is one way to have a referent, not the only one.</b> The deterministic lanes bound
 * 「이 고객」·「답변 준비해줘」 to `selectedInquiry` alone, so a refine that left exactly ONE row on screen
 * had a referent everybody could see and nobody could name: 「파손 관련 문의 보여줘」 → 「그중 네이버 것만」
 * → 「이 고객한테 뭐라고 답해야 해?」 fell through to the planner, which read the org's whole queue and
 * ended the turn WAITING_HUMAN. The planner's own `resolveTargets` had always treated a one-row set as
 * the target, so the two paths disagreed about what 「이 문의」 means; this is the one answer.
 *
 * A set of two or more has no unique referent and returns null — the sentence is then a selection
 * question, and the lanes that ask it are the ones that run.
 */
export function focusInquiryOf(set: WorkingSetView | null): SelectedInquiry | null {
  if (set?.kind !== "INQUIRIES") return null;
  if (set.selectedInquiry) return set.selectedInquiry;
  if (set.ids.length !== 1) return null;
  return {
    inquiryId: set.ids[0]!, workItemId: set.workItemIds.length === 1 ? set.workItemIds[0]! : null,
    productId: set.productIds.length === 1 ? set.productIds[0]! : null,
    channelCode: set.filters.channelCode ?? null,
  };
}

/** The last REVIEW_LIST row of a channel the thread has shown — the object an explanation is about. */
export function lastReviewItemOf(view: ConversationView, channelCode: string): ReviewItem | null {
  for (let i = view.turns.length - 1; i >= 0; i--) {
    for (const a of view.turns[i]!.artifacts) {
      if (a.type !== "REVIEW_LIST") continue;
      const hit = a.items.find((r) => r.channelCode.toUpperCase() === channelCode);
      if (hit) return hit;
    }
  }
  return null;
}

/** The seller-facing channel name as the thread already printed it, when any artifact carries it. */
export function channelNameOf(view: ConversationView, channelCode: string): string | null {
  for (let i = view.turns.length - 1; i >= 0; i--) {
    for (const a of view.turns[i]!.artifacts) {
      if (a.type === "REVIEW_LIST") {
        const f = a.freshness.find((r) => r.channelCode.toUpperCase() === channelCode);
        if (f?.channelNameKo) return f.channelNameKo;
      }
      if (a.type === "HUMAN_ACTION_REQUIRED" && (a.channelCode ?? "").toUpperCase() === channelCode && a.channelNameKo) return a.channelNameKo;
    }
  }
  return null;
}

/** The period token of the review window a pending human step was asked for — null when none is pending. */
export function pendingHumanWindowOf(view: ConversationView): string | null {
  const pending = view.pendingHumanActions?.length ? view.pendingHumanActions : view.pendingHumanAction ? [view.pendingHumanAction] : [];
  // An offered refresh gates nothing: only a REQUIRED step keeps the window gated on the next question.
  if (!pending.some((p) => p.actionType === "REVIEW_IMPORT" && !p.optional)) return null;
  return view.workingSet?.kind === "REVIEWS" ? view.workingSet.filters.period?.token ?? null : null;
}

/** The working-set line for the planner — closed tokens only, never a count, an id or a customer word. */
export function priorLineOf(view: ConversationView): string | null {
  const set = view.workingSet;
  const parts: string[] = [];
  if (set) {
    parts.push(`직전 작업 집합: ${set.kind} (기간:${set.filters.period?.token ?? "없음"}, 채널:${set.filters.channelCode ?? "전체"}, `
      + `평점:${set.filters.rating ?? "ALL"}, 상태:${set.filters.status ?? "없음"}, 상품 특정:${set.productIds.length > 0 ? "예" : "아니오"})`);
    // New-list Scope Integrity (Conversation Core v1 §9): the set's axes DESCRIBE what is on screen —
    // they are not defaults for the next sentence. A fixed instruction, like `contextLine`'s: closed
    // vocabulary about run state, no data. The deterministic backstop is `scopeOverride`'s axis strip.
    parts.push("집합 규칙: 위 괄호의 값은 지금 화면에 있는 집합의 설명이지 이번 문장의 조건이 아닙니다. "
      + "그 집합을 좁히는 문장(「그중」·「여기서」·「~만」)에서만 filters.scope=\"WORKING_SET\"으로 두고 새 조건만 더하세요. "
      + "새 목록을 요청하는 문장(「최근 문의 N개 보여줘」·「오늘 문의 보여줘」)은 filters.scope=\"ORG\"이고, "
      + "filters의 채널·상태·기간·주제는 이번 문장에 적힌 것만 적으세요 — 위 괄호의 값을 복사하지 마세요.");
  }
  if (set?.kind === "INQUIRIES" && set.selectedInquiry) {
    parts.push("직전 선택: INQUIRY (판매자가 방금 문의 하나를 골랐습니다 — 「이 문의」·「답변 준비해줘」는 그 문의를 가리킵니다)");
  }
  // The same fact for the other two objects: WHICH one is never sent (an id is not a plan input), only
  // that one is standing — so 「이 상품」·「이 리뷰」 is a resolved reference and not a question to ask back.
  if (set?.selectedObject?.kind === "PRODUCT") {
    parts.push("직전 선택: PRODUCT (판매자가 방금 상품 하나를 골랐습니다 — 「이 상품」은 그 상품을 가리킵니다)");
  } else if (set?.selectedObject?.kind === "REVIEW") {
    // Agent Object v1: the exact single-review read exists, so a question about the selected review is
    // answerable — the planner declares a REVIEW_SIGNAL need instead of asking which review it was.
    parts.push("직전 선택: REVIEW (판매자가 방금 리뷰 하나를 골랐습니다 — 「이 리뷰」는 그 리뷰를 가리키며, "
      + "그 리뷰 하나를 정확히 읽는 조회가 있습니다: REVIEW_SIGNAL 필요 정보로 두면 됩니다)");
  }
  if (view.pendingPrepared) {
    parts.push(view.pendingPrepared.kind === "REVIEW_DRAFT"
      ? "직전 준비: REVIEW_DRAFT (판매자가 방금 준비된 리뷰 답글 초안을 보고 있습니다)"
      : "직전 준비: INQUIRY_DRAFT (판매자가 방금 준비된 초안을 보고 있습니다)");
  }
  return parts.length > 0 ? parts.join("\n") : null;
}

/**
 * An inquiry the conversation has shown, by inquiry id OR work item id.
 *
 * <b>`drawn` is the rows THIS turn produced, and it is searched first.</b> The persisted history is the
 * previous turns only — the current turn's artifacts are not saved until it ends — so a turn that both
 * FOUND a row and was asked to act on it could not see its own result: 「케이블 커버에 전선 몇 가닥
 * 들어가는지 물어본 문의 답변 준비해줘」 read the queue, drew exactly one row, and then answered
 * 「어떤 문의의 답변을 준비할지 알려주세요」 with that row printed underneath. The target step must be
 * able to see the objects the same turn put on the table.
 */
export function inquiryFromHistory(view: ConversationView, id: string, drawn: readonly Artifact[] = []): InquiryItem | null {
  const scan = (artifacts: readonly Artifact[]): InquiryItem | null => {
    for (const artifact of artifacts) {
      if (artifact.type !== "INQUIRY_LIST") continue;
      for (const group of artifact.groups) {
        const item = group.items.find((it) => it.inquiryId === id || it.workItemId === id);
        if (item) return item;
      }
    }
    return null;
  };
  const fresh = scan(drawn);
  if (fresh) return fresh;
  for (let i = view.turns.length - 1; i >= 0; i -= 1) {
    const found = scan(view.turns[i]!.artifacts);
    if (found) return found;
  }
  return null;
}

export function inquiryTargetFromHistory(view: ConversationView, id: string, drawn: readonly Artifact[] = []): ResolvedTarget | null {
  const item = inquiryFromHistory(view, id, drawn);
  if (!item) return null;
  const actionability = actionabilityOf({ workItemId: item.workItemId, phase: item.phase, status: item.status });
  return {
    kind: "INQUIRY",
    inquiry: {
      inquiryId: item.inquiryId, workItemId: item.workItemId, productId: item.productId, channelCode: item.channelCode,
      channelNameKo: item.channelNameKo, productName: item.productName, title: item.title ?? null, status: item.status,
      receivedAt: item.receivedAt ?? null, snippet: item.snippet ?? null,
    },
    actionability,
    // Only a DRAFTABLE row has a work item the product's draft path will accept.
    target: actionability === "DRAFTABLE" && item.workItemId ? {
      workItemId: item.workItemId, inquiryId: item.inquiryId, channelCode: item.channelCode,
      channelNameKo: item.channelNameKo, productId: item.productId, productName: item.productName,
    } : null,
    // Absent on a row from an older backend ⇒ NONE: a channel label is not a marketplace binding.
    executableIdentity: item.executableIdentity ?? "NONE",
    sourceSubtype: item.sourceSubtype ?? null,
  };
}

/** A ReviewItem the conversation has shown, from the persisted REVIEW_LIST rows. */
export function reviewFromHistory(view: ConversationView, reviewId: string): ReviewItem | null {
  for (let i = view.turns.length - 1; i >= 0; i -= 1) {
    for (const artifact of view.turns[i]!.artifacts) {
      if (artifact.type !== "REVIEW_LIST") continue;
      const item = artifact.items.find((it) => it.reviewId === reviewId);
      if (item) return item;
    }
  }
  return null;
}

/** A product row the conversation has drawn — the proof that an org-scoped read returned it. */
export function productFromHistory(view: ConversationView, productId: string): { productId: string } | null {
  for (let i = view.turns.length - 1; i >= 0; i -= 1) {
    for (const artifact of view.turns[i]!.artifacts) {
      if (artifact.type !== "PRODUCT_LIST") continue;
      if (artifact.items.some((p) => p.productId === productId)) return { productId };
    }
  }
  return null;
}

/**
 * A clicked REVIEW, verified by the only proof this runtime has.
 *
 * There is no single-review endpoint, so the check is the thread's own record: a review row the
 * conversation drew came back from an org-scoped read of this seller's reviews. An id that appears in
 * no drawn list is refused rather than trusted — and refusing costs the seller nothing, because a row
 * they can click is by definition a row on their screen.
 */
export function verifiedReview(view: ConversationView, reviewId: string): SelectedObject | null {
  const item = reviewFromHistory(view, reviewId);
  if (!item) return null;
  return { kind: "REVIEW", id: item.reviewId, productId: item.productId ?? null, channelCode: item.channelCode ?? null };
}

export function reviewTargetFromHistory(view: ConversationView, reviewId: string): ResolvedTarget | null {
  const item = reviewFromHistory(view, reviewId);
  if (!item) return null;
  return {
    kind: "REVIEW",
    target: {
      reviewId: item.reviewId, accountId: item.accountId, actionRef: `review:${item.reviewId}`,
      channelCode: item.channelCode, channelNameKo: item.channelNameKo, productId: item.productId, productName: item.productName,
    },
    executableIdentity: item.executableIdentity ?? "NONE",
  };
}
