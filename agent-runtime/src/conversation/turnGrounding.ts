/**
 * **What the turn shows as its grounding, and what it leaves the seller to do.** The evidence rows
 * behind an answer, the workspace link that carries the work on, the product list the answer is
 * about, the checklist of what is still waiting. Lifted out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Pure selectors over an answer and the artifacts a turn drew. They decide what is SHOWN as
 * grounding, never what is true — retrieval and judging happen before this, in the operator lane.
 * The sentences these pick between live in `./sellerSentences`.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import type {
  Artifact, ConversationView, EvidenceArtifact, WorkingSetView, WorkspaceLinkArtifact,
} from "./contract";
import type { InvestigationPlan } from "../operator/plan/InvestigationPlan";
import type { OperatorAnswer } from "../operator/state/OperatorState";
import { isSellerSafeLabel, SOURCE_LABEL } from "../operator/wording/sellerWording";
import { pendingActionsOf } from "./historyReaders";
import { FACT_LABEL } from "./sellerSentences";
import { humanItem, WORKSPACE_OF } from "./turnArtifacts";

/** How much of the answer's evidence the EVIDENCE artifact shows. */
const EVIDENCE_ITEMS_MAX = 20;

/** Evidence kinds whose locator label is a seller-authored title (a rule, a document, a remembered answer, an issue). */
export const EVIDENCE_DETAIL_KINDS = new Set(["ORG_POLICY", "PRODUCT_KNOWLEDGE_DOC", "PAST_ANSWER", "REVIEW_ISSUE", "ISSUE_EVIDENCE", "REPEATED_INQUIRY", "IMPROVEMENT_OPPORTUNITY"]);

export function evidenceOf(
  answer: OperatorAnswer, extra: ReadonlyArray<EvidenceArtifact["items"][number]> = [],
): EvidenceArtifact | null {
  if (answer.evidence.length === 0 && extra.length === 0) return null;
  // The seller's word for the source, then the row's own title when it reads as words (a document
  // title, a strength label) — never a token, a stamp or an id (Response Hygiene v1 §1).
  const labelOf = (kind: string, detail: string | null | undefined): string => {
    const source = SOURCE_LABEL[kind] ?? "자료";
    if (!EVIDENCE_DETAIL_KINDS.has(kind) || !isSellerSafeLabel(detail) || !/[가-힣]/.test(detail) || detail === source) return source;
    return detail.includes(source) ? detail : `${source} · ${detail}`;
  };
  return {
    artifactId: "a-evidence", type: "EVIDENCE", title: "확인한 자료",
    items: answer.evidence.slice(0, EVIDENCE_ITEMS_MAX).map((e) => ({
      label: labelOf(e.kind, e.locator.label),
      count: e.locator.count ?? null,
      from: e.events?.from ?? null, to: e.events?.to ?? null, asOf: e.asOf,
      covered: e.coverage === "COVERED",
      ...(e.locator.productId ? { link: `/products/${e.locator.productId}` }
        : e.locator.inquiryId ? { link: `/inquiries/${e.locator.inquiryId}` } : {}),
    })).concat(extra),
  };
}

export function workspaceFor(plan: InvestigationPlan | null, workingSet: WorkingSetView | null): WorkspaceLinkArtifact {
  const kinds = new Set((plan?.informationNeeds ?? []).map((n) => n.kind));
  // A navigation-only plan names no need; the screen the seller asked for is then the entity kind the
  // planner heard (「문의 화면」 ⇒ INQUIRY), and only after that the working set.
  const mentioned = new Set((plan?.entities.unresolved ?? []).map((e) => e.kind));
  const target = kinds.has("INQUIRY_VOLUME") || kinds.has("CUSTOMER_HISTORY") ? WORKSPACE_OF.INQUIRIES
    : kinds.has("REVIEW_SIGNAL") ? WORKSPACE_OF.REVIEWS
      : kinds.has("ORDER_HISTORY") ? WORKSPACE_OF.ORDERS
        : kinds.has("PRODUCT_FACT") || kinds.has("PRODUCT_LISTING") || kinds.has("PRODUCT_VARIANT") || kinds.has("PRODUCT_KNOWLEDGE_DOC")
          ? WORKSPACE_OF.PRODUCTS
          : mentioned.has("INQUIRY") ? WORKSPACE_OF.INQUIRIES
            : mentioned.has("ORDER") ? WORKSPACE_OF.ORDERS
              : mentioned.has("PRODUCT") ? WORKSPACE_OF.PRODUCTS
                : workingSet ? WORKSPACE_OF[workingSet.kind] : WORKSPACE_OF.INQUIRIES;
  return { artifactId: "a-workspace", type: "WORKSPACE_LINK", title: `${target.label} 화면`, link: { label: target.label, to: target.to } };
}

/** The backend's generic basis note — the card's title already says it, so the prose does not. */
export const GENERIC_BASIS_NOTE = /^답변 기준이 필요합니다\.?$/;

/**
 * <b>Was the company profile the whole question, or one input among several?</b> — asked of the plan,
 * which already answered it (Agent Semantic Ownership v1 §3).
 *
 * The 「회사 정보에는…」 finding is produced in exactly one place, the `COMPANY_PROFILE` branch of
 * InquiryOps, so its existence proves the planner wanted the profile. What it does NOT prove is that
 * the seller asked to READ it: traced live 2026-09-06, 「우리 회사 특성 고려하면 배송 문의에 어떻게
 * 답하는 게 좋을까」 declares `COMPANY_PROFILE` beside `POLICY` and `PAST_ANSWER`, and reading the whole
 * introduction back on that turn is the noise §3 was written to stop. Being the run's ONLY need is the
 * difference, and it is the planner's own token — no sentence is read.
 *
 * <b>It replaces a regex that got this wrong in the seller's favour and against it.</b> The old
 * `/(회사|우리|저희).{0,12}(어떤 곳|소개|…)/` missed 「우리 회사는 어떤 회사야?」 and 「우리 회사에 대해
 * 알려줘」 — both planned as the sole `COMPANY_PROFILE` need — and replaced the seller's own summary
 * with 「등록된 회사 정보를 참고했습니다」 on the two most ordinary ways to ask.
 */
export function companyIsTheQuestion(answer: OperatorAnswer): boolean {
  return answer.needs.length === 1 && answer.needs[0]!.kind === "COMPANY_PROFILE";
}

/** A PRODUCT_LIST from product-scoped evidence refs, first appearance first — or from one resolved entity. */
export function productListOf(answer: OperatorAnswer, entities: readonly { kind: string; id: string; label: string }[]): Artifact | null {
  const rows = new Map<string, { name: string; facts: Map<string, number> }>();
  for (const ref of answer.evidence) {
    const productId = ref.locator.productId;
    if (!productId) continue;
    // A ref that carries the id without the name is named by the entity the run resolved (found live:
    // a remembered answer's product read as 「(이름 없는 상품)」 under the product it was about).
    const resolvedName = entities.find((e) => e.kind === "PRODUCT" && e.id === productId)?.label ?? null;
    const row = rows.get(productId) ?? { name: ref.locator.productName ?? resolvedName ?? "(이름 없는 상품)", facts: new Map() };
    if (ref.locator.productName && row.name === "(이름 없는 상품)") row.name = ref.locator.productName;
    if (ref.locator.count != null) {
      const label = FACT_LABEL[ref.kind] ?? ref.locator.label ?? "근거";
      row.facts.set(label, Math.max(row.facts.get(label) ?? 0, ref.locator.count));
    }
    rows.set(productId, row);
  }
  if (rows.size === 0) {
    const product = entities.filter((e) => e.kind === "PRODUCT");
    if (product.length !== 1) return null;
    rows.set(product[0]!.id, { name: product[0]!.label, facts: new Map() });
  }
  return {
    artifactId: "a-products", type: "PRODUCT_LIST", title: "이 답변이 가리키는 상품",
    items: [...rows.entries()].map(([productId, row]) => ({
      productId, productName: row.name,
      facts: [...row.facts.entries()].map(([label, count]) => ({ label, count })),
      to: `/products/${productId}`,
    })),
  };
}

/** The seller's to-do list, from what this turn showed and what the conversation is waiting on. */
export function checklistOf(artifacts: readonly Artifact[], view: ConversationView): Extract<Artifact, { type: "CHECKLIST" }> {
  const items: { label: string; detail?: string; to?: string }[] = [];
  const seenHuman = new Set<string>();
  for (const a of artifacts) {
    if (a.type !== "HUMAN_ACTION_REQUIRED") continue;
    const key = `${a.actionType}:${a.channelCode ?? ""}`;
    if (seenHuman.has(key)) continue;
    seenHuman.add(key);
    items.push(humanItem(a.actionType, a.channelNameKo ?? a.channelCode, a.to));
  }
  for (const pending of pendingActionsOf(view)) {
    if (seenHuman.has(`${pending.actionType}:${pending.channelCode ?? ""}`)) continue;
    seenHuman.add(`${pending.actionType}:${pending.channelCode ?? ""}`);
    items.push(humanItem(pending.actionType, pending.channelCode, null));
  }
  for (const a of artifacts) {
    if (a.type === "INQUIRY_LIST") {
      const ready = a.groups.find((g) => g.key === "DRAFT_READY")?.items.length ?? 0;
      const open = a.groups.find((g) => g.key === "UNANSWERED")?.items.length ?? 0;
      if (ready > 0) items.push({ label: `초안 준비된 문의 ${ready}건 확인하고 보내기`, to: "/inquiries?state=NEEDS_REPLY" });
      if (open > 0) items.push({ label: `답변이 필요한 문의 ${open}건`, to: "/inquiries?state=NEEDS_REPLY" });
    }
    if (a.type === "REVIEW_LIST") {
      const negative = a.items.filter((i) => i.negative).length;
      if (negative > 0) items.push({ label: `낮은 평점 리뷰 ${negative}건 확인`, to: "/reviews" });
    }
    if (a.type === "ORDER_SUMMARY" && a.totals.salesDeltaPercent != null) {
      items.push({ label: `매출 변화 확인 (${a.totals.salesDeltaPercent > 0 ? "+" : ""}${a.totals.salesDeltaPercent}%)`, to: a.to });
    }
  }
  return { artifactId: "a-checklist", type: "CHECKLIST", title: "지금 하실 일", items };
}
