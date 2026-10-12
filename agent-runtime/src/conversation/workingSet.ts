/**
 * **What the conversation is standing on.** The working set — which rows the next sentence narrows,
 * which object it is anchored to, whether this turn drew a fresh list or carried one forward. Lifted
 * out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Pure: every function takes the view (and what this turn drew) and returns the set, or `null`.
 * Nothing reads the backend or decides what to say. The distinction this module exists to keep is the
 * one `SET_ARTIFACT_TYPES` in `./turnArtifacts` notes from the other side — "what is the conversation
 * standing on now" has a carried-forward answer, "did this turn draw it" must not.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import type {
  Artifact, ConversationView, InquiryItem, SelectedInquiry, SelectedObject, WorkingSetView,
} from "./contract";
import { WORKING_SET_MAX_IDS } from "./contract";
import type { SelectedInquiryRow } from "./historyReaders";
import { primaryOf, SET_ARTIFACT_TYPES } from "./turnArtifacts";
import type { VisibleFilter } from "./taskInterpreter";
import { TOPIC_LABEL } from "./taskInterpreter";
import type { OperatorAnswer } from "../operator/state/OperatorState";
import type { OperatorRunResult } from "../operator/operatorRuntime";
import { conversationAxisOf } from "../operator/plan/InvestigationPlan";


/** The seller's words for a visible filter's predicate axes (channel · topic · status), trailing space. */
export const FILTER_CHANNEL_WORD: Record<string, string> = { NAVER: "네이버", COUPANG: "쿠팡", CAFE24: "카페24" };

export function filterWords(filter: VisibleFilter): string {
  const parts: string[] = [];
  if (filter.channel) parts.push(FILTER_CHANNEL_WORD[filter.channel] ?? filter.channel);
  if (filter.topic) parts.push(TOPIC_LABEL[filter.topic]);
  // The seller's own subject word, said back verbatim — a 0 under it is a 0 about that subject.
  if (filter.term) parts.push(`${filter.term} 관련`);
  if (filter.status) parts.push(filter.status === "UNANSWERED" ? "답변 안 한" : "답변한");
  return parts.length > 0 ? `${parts.join(" ")} ` : "";
}

/** The all-null filter a PRIORITIZE reuses: no narrowing, only an order. */
export const EMPTY_FILTER: VisibleFilter = { channel: null, topic: null, term: null, status: null, limit: null, order: null, urgency: false };

/**
 * The set a draft/send sentence indexes into — the one the seller was LOOKING AT when they said it
 * (the conversation's), and only when there is none, the list this turn happened to draw. Found live:
 * a PREPARE plan that also read the org queue made 「첫 번째 거」 the first row of that queue, not of the
 * three rows on screen (Conversation Object Integrity v1).
 */
/**
 * <b>Is the conversation still standing on the same object it was?</b>
 *
 * Grounded Conversation Lane v1 §4. The step in flight is carried when the anchor did not move, and
 * the test for that used to read {@code selectedInquiry} alone — so a thread anchored on a REVIEW or a
 * PRODUCT lost its task on the first conversational turn: 「답글 준비해줘」 leaves PREPARE_REPLY on a
 * review, 「왜 이렇게 썼어?」 answers about that same review, and the context bar stops saying what the
 * conversation is doing with it. The anchor is one of three kinds — {@code selectedObject} clears
 * {@code selectedInquiry} and back — so the comparison has to be over the ANCHOR, not over one of its
 * shapes.
 */
export function sameAnchor(next: WorkingSetView | null, previous: WorkingSetView | null): boolean {
  const key = (set: WorkingSetView | null): string | null =>
    set?.selectedObject ? `${set.selectedObject.kind}:${set.selectedObject.id}`
      : set?.selectedInquiry ? `INQUIRY:${set.selectedInquiry.inquiryId}` : null;
  const a = key(next);
  return a != null && a === key(previous);
}

export function pickSet(workingSet: WorkingSetView | null, view: ConversationView): WorkingSetView | null {
  const candidates = [view.workingSet, workingSet];
  return candidates.find((s) => s?.kind === "INQUIRIES" || s?.kind === "REVIEWS") ?? null;
}

/** The working set the primary artifact leaves behind — ids and closed filters, bounded. */
export function workingSetOf(
  artifacts: readonly Artifact[], axis: ReturnType<typeof conversationAxisOf>, view: ConversationView,
): WorkingSetView | null {
  const primary = primaryOf(artifacts.filter((a) => a.type !== "DRAFT" && a.type !== "APPROVAL_REQUIRED" && a.type !== "APPROVAL" && a.type !== "GUIDED_EXECUTION"
    && a.type !== "WORKSPACE_LINK" && a.type !== "SUMMARY"));
  const bounded = (ids: readonly string[]): string[] => ids.slice(0, WORKING_SET_MAX_IDS);
  switch (primary?.type) {
    case "REVIEW_LIST": {
      const productIds = [...new Set(primary.items.map((i) => i.productId).filter((p): p is string => p != null))];
      return {
        kind: "REVIEWS", label: primary.title, count: primary.totalCount,
        ids: bounded(primary.items.map((i) => i.reviewId)),
        filters: {
          period: primary.scope.period, channelCode: primary.scope.channelCode, rating: primary.scope.rating,
          ...(primary.scope.productId ? { productIds: [primary.scope.productId] } : {}),
        },
        productIds: bounded(productIds), workItemIds: [], turnId: "",
      };
    }
    case "INQUIRY_LIST": {
      const items: InquiryItem[] = primary.groups.flatMap((g) => g.items);
      const productIds = [...new Set(items.map((i) => i.productId).filter((p): p is string => p != null))];
      const workItemIds = items.map((i) => i.workItemId).filter((w): w is string => w != null);
      // Query Accuracy v1: a ROWS list is anchored by inquiry ids and remembers the spec it was read
      // with, so the next sentence refines the same read; a WORKLOAD list is anchored by work items.
      const rows = primary.scope != null && !(primary.more?.to ?? "").includes("NEEDS_REPLY");
      return {
        kind: "INQUIRIES", label: primary.title, count: primary.totalCount,
        ids: bounded(rows ? items.map((i) => i.inquiryId) : workItemIds),
        filters: {
          ...(primary.scope?.topic ?? axis.filters.topic ? { topic: primary.scope?.topic ?? axis.filters.topic } : {}),
          ...(primary.scope?.term ? { term: primary.scope.term } : {}),
          ...(primary.scope ? {
            period: primary.scope.period, channelCode: primary.scope.channelCode, status: primary.scope.status,
            order: primary.scope.order,
          } : {}),
          inquiryIntent: rows ? "ROWS" : "WORKLOAD",
        },
        productIds: bounded(productIds), workItemIds: bounded(workItemIds), turnId: "",
      };
    }
    case "PRODUCT_LIST":
      return {
        kind: "PRODUCTS", label: primary.title, count: primary.items.length,
        ids: bounded(primary.items.map((i) => i.productId)), filters: {},
        productIds: bounded(primary.items.map((i) => i.productId)), workItemIds: [], turnId: "",
      };
    case "ORDER_SUMMARY":
      return {
        kind: "ORDERS", label: primary.title, count: primary.totals.orders, ids: [],
        filters: { period: primary.period, channelCode: primary.channelCode }, productIds: [], workItemIds: [], turnId: "",
      };
    case "ISSUE_LIST":
      return {
        kind: "ISSUES", label: primary.title, count: primary.items.length,
        ids: bounded(primary.items.map((i) => i.issueId)), filters: {},
        productIds: bounded([...new Set(primary.items.map((i) => i.productId).filter((p): p is string => p != null))]),
        workItemIds: [], turnId: "",
      };
    default:
      return axis.requestedAction === "NONE" && artifacts.length > 0 ? null : view.workingSet;
  }
}

/**
 * The working set anchored on one inquiry: the INQUIRIES kind the follow-up rules already read, exactly
 * one id, the previous set's closed filters (so 「그중 …」 still refines the same read), the products
 * beside it, and the selection itself. The label is the selection, never a customer word.
 */
export function anchoredSet(row: SelectedInquiry, previous: WorkingSetView | null, productIds: readonly string[]): WorkingSetView {
  // The list the selection was made from stays the set (「세 번째 거」 after 「첫 번째 거」 still counts on
  // the same three rows; 「그중 네이버만」 still refines the same read); the selection rides beside it.
  //
  // A set is keyed by ONE of two identities and which one depends on the read that produced it: a ROWS
  // list is inquiry ids, a work-queue list is work-item ids (`ids: bounded(rows ? … : workItemIds)`).
  // Testing only the inquiry id therefore never matched a work-queue list, so clicking a row of the
  // queue silently collapsed the set to that one row — and 「세 번째 거」 or 「그중 네이버만」 after the
  // click acted on a set of one. The row carries both ids; recognise the set by either.
  const inSet = (set: WorkingSetView): boolean =>
    set.ids.includes(row.inquiryId) || (row.workItemId != null && set.workItemIds.includes(row.workItemId));
  const list = previous?.kind === "INQUIRIES" && inSet(previous) ? previous : null;
  const inherited = previous?.kind === "INQUIRIES" ? previous.filters : {};
  const selectedInquiry: SelectedInquiry = {
    inquiryId: row.inquiryId, workItemId: row.workItemId, productId: row.productId, channelCode: row.channelCode,
  };
  return {
    kind: "INQUIRIES", label: list?.label ?? "선택한 문의", count: list?.count ?? 1, ids: list ? [...list.ids] : [row.inquiryId],
    filters: { ...inherited, inquiryIntent: inherited.inquiryIntent ?? "ROWS" },
    productIds: [...new Set(productIds)].slice(0, WORKING_SET_MAX_IDS),
    workItemIds: list ? [...list.workItemIds] : row.workItemId ? [row.workItemId] : [],
    // One anchor at a time: standing on an inquiry leaves whatever product or review was anchored.
    selectedInquiry, selectedObject: null, turnId: "",
  };
}

/**
 * The working set anchored on a product or a review — the same shape {@link anchoredSet} builds for an
 * inquiry: the list the selection was made from stays the set (「그중 …」 still refines the same rows),
 * the object rides beside it, and the OTHER anchor is dropped because there is only ever one.
 */
export function anchoredObjectSet(object: SelectedObject, previous: WorkingSetView | null): WorkingSetView {
  const kind: WorkingSetView["kind"] = object.kind === "PRODUCT" ? "PRODUCTS" : "REVIEWS";
  const list = previous?.kind === kind && previous.ids.includes(object.id) ? previous : null;
  return {
    kind,
    label: list?.label ?? (object.kind === "PRODUCT" ? "선택한 상품" : "선택한 리뷰"),
    count: list?.count ?? 1,
    ids: list ? [...list.ids] : [object.id],
    filters: list?.filters ?? {},
    productIds: [...new Set([...(list?.productIds ?? []), ...(object.productId ? [object.productId] : [])])].slice(0, WORKING_SET_MAX_IDS),
    workItemIds: list ? [...list.workItemIds] : [],
    selectedInquiry: null,
    selectedObject: object,
    turnId: "",
  };
}

/** The inquiry a screen launch resolved inside the graph — read back from the ref the runtime minted for it. */
export function selectedFromEntities(result: Extract<OperatorRunResult, { status: "DONE" }>, answer: OperatorAnswer, workItemId: string): SelectedInquiryRow | null {
  const entity = result.entities.find((e) => e.kind === "INQUIRY" && e.id === workItemId);
  if (!entity) return null;
  const ref = answer.evidence.find((e) => e.kind === "INQUIRY" && e.locator.workItemId === workItemId);
  const inquiryId = typeof ref?.locator.inquiryId === "string" ? ref.locator.inquiryId : null;
  if (!inquiryId) return null;
  return {
    inquiryId, workItemId,
    productId: typeof ref?.locator.productId === "string" ? ref.locator.productId : null,
    channelCode: typeof ref?.locator.channelCode === "string" ? ref.locator.channelCode : null,
    channelNameKo: null,
    productName: typeof ref?.locator.productName === "string" ? ref.locator.productName : null,
    title: null,
    status: typeof ref?.locator.status === "string" ? ref.locator.status : "",
    receivedAt: ref?.events?.from ?? null,
  };
}

/** Did THIS turn's own specialists draw a list the seller asked for? (An R4 product grouping is not one.) */
export function drewFreshList(turnArtifacts: readonly Artifact[], anchor: SelectedInquiry | null): boolean {
  const list = turnArtifacts.find((a) => a.type === "INQUIRY_LIST" || a.type === "REVIEW_LIST" || a.type === "ISSUE_LIST" || a.type === "ORDER_SUMMARY");
  if (!list) return false;
  // A refine that narrowed the list to the anchored inquiry itself is still about that inquiry.
  if (anchor && list.type === "INQUIRY_LIST") {
    const ids = list.groups.flatMap((g) => g.items.map((i) => i.inquiryId));
    if (ids.length === 1 && ids[0] === anchor.inquiryId) return false;
  }
  return true;
}

/**
 * Did this turn draw a set that leaves the anchored PRODUCT or REVIEW behind?
 *
 * The object analogue of {@link drewFreshList}, and the same rule: a list that still holds the anchored
 * object (「비슷한 리뷰도 있어?」 returns this review among its product's rows) has not moved the seller
 * off it, so the anchor stands and 「이 리뷰」 still means the same review.
 */
export function drewFreshObjectList(turnArtifacts: readonly Artifact[], object: SelectedObject): boolean {
  const list = turnArtifacts.find((a) => SET_ARTIFACT_TYPES.includes(a.type));
  if (!list) return false;
  if (object.kind === "REVIEW" && list.type === "REVIEW_LIST") {
    return !list.items.some((i) => i.reviewId === object.id);
  }
  if (object.kind === "PRODUCT" && list.type === "PRODUCT_LIST") {
    return !list.items.some((i) => i.productId === object.id);
  }
  return true;
}
