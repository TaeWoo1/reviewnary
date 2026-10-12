/**
 * **The artifacts a turn puts on screen, and the small decisions about their shape.** Which artifact
 * is the primary one, whether this turn drew a set at all, one inquiry as a card, the closed summary
 * lines. Lifted out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Pure builders: each takes closed facts and returns an artifact or a string. Nothing here reads
 * state, calls the backend or judges anything — WHAT to say is decided in the service, and this
 * module only shapes it. The seller-facing sentences themselves live in `./sellerSentences`.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import type { Artifact, InquiryDetailArtifact, SuggestedAction, SummaryArtifact, WorkingSetKind } from "./contract";

/** Each working-set kind's own screen — the one place a 「전체 N건 처리하기」 link and a workspace
 * artifact agree on where the work continues. Shared by the service and `./turnGrounding`. */
export const WORKSPACE_OF: Record<WorkingSetKind, { label: string; to: string }> = {
  REVIEWS: { label: "리뷰", to: "/reviews" },
  INQUIRIES: { label: "문의", to: "/inquiries?state=NEEDS_REPLY" },
  PRODUCTS: { label: "상품", to: "/products" },
  ORDERS: { label: "주문", to: "/orders" },
  ISSUES: { label: "반복 문제", to: "/memory" },
};

import type { InquiryActionability } from "./inquiryActionability";
import type { ReviewDraftTarget } from "./DraftPreparer";
import type { SelectedInquiryRow } from "./historyReaders";
import { COPY_ONLY_SENTENCE, NOT_EXECUTABLE_SENTENCE, STATUS_WORD, summaryLineFor } from "./sellerSentences";

export const PRIMARY_ORDER: readonly Artifact["type"][] = [
  "DRAFT", "APPROVAL_REQUIRED", "APPROVAL", "GUIDED_EXECUTION", "REVIEW_LIST", "INQUIRY_LIST", "PRODUCT_LIST", "ORDER_SUMMARY", "ISSUE_LIST",
  "OPPORTUNITY_LIST",
  "WORKSPACE_LINK", "CHART", "METRIC", "TABLE", "LIST", "SUMMARY", "CHECKLIST",
];

/**
 * The artifact types that ARE a working set on screen — the same five {@link workingSetOf} builds one
 * from. Listed here rather than derived from that switch because the two answer different questions:
 * that one asks "what is the conversation standing on now", which has a carried-forward answer, and
 * this one asks "did this turn draw it", which must not.
 */
export const SET_ARTIFACT_TYPES: readonly Artifact["type"][] =
  ["INQUIRY_LIST", "REVIEW_LIST", "PRODUCT_LIST", "ORDER_SUMMARY", "ISSUE_LIST"];

export function drewSetOf(artifacts: readonly Artifact[]): boolean {
  return artifacts.some((a) => SET_ARTIFACT_TYPES.includes(a.type));
}

export function primaryOf(artifacts: readonly Artifact[]): Artifact | null {
  for (const type of PRIMARY_ORDER) {
    const found = artifacts.find((a) => a.type === type);
    if (found) return found;
  }
  return null;
}

/** Same-type list artifacts with the same title collapse to the last one; everything else is kept in order. */
export function dedupeLists(artifacts: readonly Artifact[]): Artifact[] {
  const LISTS = new Set(["INQUIRY_LIST", "REVIEW_LIST", "PRODUCT_LIST", "ISSUE_LIST", "OPPORTUNITY_LIST", "ORDER_SUMMARY", "CHART"]);
  const lastIndex = new Map<string, number>();
  artifacts.forEach((a, i) => { if (LISTS.has(a.type)) lastIndex.set(`${a.type}:${a.title}`, i); });
  return artifacts.filter((a, i) => !LISTS.has(a.type) || lastIndex.get(`${a.type}:${a.title}`) === i);
}

export function promptChip(label: string): SuggestedAction {
  return { label, kind: "PROMPT", prompt: label };
}

export function inquiryLine(row: SelectedInquiryRow): string {
  const parts = [row.channelNameKo ?? row.channelCode ?? "채널", STATUS_WORD[row.status.toUpperCase()] ?? "상태 미확인"];
  if (row.receivedAt) parts.push(`${row.receivedAt.slice(0, 10)} 접수`);
  if (row.productName) parts.push(row.productName);
  return parts.join(" · ");
}

/**
 * ONE inquiry as a card: the row's closed facts and, when it is known, the customer's own sentence.
 * Shared by the INSPECT lane and by a planner turn that resolved exactly one row — the two must not
 * draw the selected inquiry in two different shapes.
 */
export function inquiryDetailArtifact(row: SelectedInquiryRow, actionability: InquiryActionability, excerptText: string | null): InquiryDetailArtifact {
  return {
    artifactId: `a-inspect-${row.inquiryId}`, type: "INQUIRY_DETAIL",
    title: row.title ?? "제목 없는 문의",
    inquiryId: row.inquiryId, workItemId: row.workItemId,
    channelCode: row.channelCode, channelNameKo: row.channelNameKo,
    status: row.status, receivedAt: row.receivedAt,
    productId: row.productId, productName: row.productName,
    stateLabel: STATUS_WORD[row.status.toUpperCase()] ?? "상태 미확인",
    excerpt: excerptText,
    actionability,
    to: `/inquiries/${row.inquiryId}`,
  };
}

/** The state of a non-draftable inquiry, as an artifact the seller can act on — the row, the sentence, the link. */
export function inquiryStateSummary(row: SelectedInquiryRow, line: string): SummaryArtifact {
  return {
    artifactId: `a-state-${row.inquiryId}`, type: "SUMMARY", title: "초안을 만들지 않은 문의",
    lines: [row.title ? `「${row.title}」` : inquiryLine(row), ...(row.title ? [inquiryLine(row)] : []), line],
  };
}

/** The selected inquiry, shown so the seller sees WHICH row the next verb will act on. */
export function selectionSummary(row: SelectedInquiryRow): SummaryArtifact {
  return {
    artifactId: `a-selected-${row.inquiryId}`, type: "SUMMARY", title: "선택한 문의",
    lines: [row.title ? `「${row.title}」` : "제목 없는 문의", inquiryLine(row)],
  };
}

export function reviewUnsupportedSummary(t: ReviewDraftTarget): SummaryArtifact {
  return {
    artifactId: `a-unsupported-${t.reviewId}`, type: "SUMMARY", title: "리뷰 답글을 보낼 수 없는 채널",
    lines: [summaryLineFor(t.channelCode), "대신 이 리뷰로 무엇을 확인할지 아래에서 고르실 수 있습니다."],
  };
}

export function copyOnlySummary(kind: "INQUIRY" | "REVIEW", id: string, to?: string): SummaryArtifact {
  return {
    artifactId: `a-send-${id}`, type: "SUMMARY", title: kind === "INQUIRY" ? "채널로 보낼 수 없는 문의" : "채널로 보낼 수 없는 리뷰",
    lines: [NOT_EXECUTABLE_SENTENCE[kind], COPY_ONLY_SENTENCE],
    ...(to ? { note: to } : {}),
  };
}

export function reasonSummary(artifactId: string, title: string, lines: string[]): SummaryArtifact {
  return { artifactId, type: "SUMMARY", title, lines };
}

/** Artifacts that report ROWS — the ones whose emptiness is a claim about the seller's store. */
export const ROW_BEARING: ReadonlySet<Artifact["type"]> = new Set<Artifact["type"]>([
  "INQUIRY_LIST", "REVIEW_LIST", "LIST", "PRODUCT_LIST", "OPPORTUNITY_LIST",
]);

/** Whether one row-bearing artifact came back with nothing in it. */
export function isEmptyRowArtifact(a: Artifact): boolean {
  if (a.type === "INQUIRY_LIST") return a.groups.every((g) => g.items.length === 0);
  if (a.type === "REVIEW_LIST" || a.type === "LIST" || a.type === "PRODUCT_LIST" || a.type === "OPPORTUNITY_LIST") {
    return a.items.length === 0;
  }
  return false;
}

export function humanItem(actionType: string, channel: string | null, to: string | null): { label: string; to?: string } {
  const label = actionType === "REVIEW_IMPORT" ? `${channel ?? "채널"} 리뷰 가져오기`
    : actionType === "KNOWLEDGE_ENTRY" ? "답변 기준 등록하기"
      : actionType === "CHANNEL_CONNECT" ? `${channel ?? "채널"} 연결하기` : "규격 확인하기";
  return { label, ...(to ? { to } : {}) };
}
