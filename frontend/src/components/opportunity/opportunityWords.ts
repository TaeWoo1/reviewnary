import type { KnowledgeTopicValue } from "../../lib/knowledgeWords";
import type { AppliedArtifact, OpportunityKind, OpportunityStatus, OpportunityView } from "../../lib/types";
import type { StatusTone } from "../ui/Status";

/**
 * The seller's words for an opportunity's kind and status, and where an accepted draft can go.
 *
 * <b>Two destinations, and both already exist.</b> An FAQ or an operating-rule draft becomes knowledge
 * through the quick-add the inquiry and review screens use (a seller-owned write, no model, indexed on
 * save). A detail-page note or an improvement memo is text the seller carries elsewhere — reviewnary
 * has no marketplace write for a detail page and wants none, so the action is a copy. Nothing here
 * publishes.
 */
export const KIND_TONE: Record<OpportunityKind, StatusTone> = {
  FAQ_SUPPLEMENT: "info",
  PRODUCT_GUIDE_SUPPLEMENT: "info",
  OPERATING_POLICY_SUPPLEMENT: "info",
  PRODUCT_IMPROVEMENT_REVIEW: "warn",
};

export const STATUS_TONE: Record<OpportunityStatus, StatusTone> = {
  OPEN: "neutral",
  ACCEPTED: "info",
  // 적용했습니다 — the seller did it. `good` and not `info`: this is the only status on the card that reports
  // something finished rather than something prepared. It still says nothing about whether it worked; that is
  // the outcome line, and it has its own words.
  APPLIED: "good",
  DISMISSED: "neutral",
};

export type DraftDestination =
  | { kind: "KNOWLEDGE"; scope: "PRODUCT" | "ORG"; topic: KnowledgeTopicValue; label: string }
  | { kind: "COPY"; label: string };

/** Where this opportunity's draft goes once the seller is done with it. */
export function destinationOf(o: OpportunityView): DraftDestination {
  switch (o.kind) {
    case "FAQ_SUPPLEMENT":
      return { kind: "KNOWLEDGE", scope: "PRODUCT", topic: "FAQ", label: "답변 기준으로 저장" };
    case "OPERATING_POLICY_SUPPLEMENT":
      return {
        kind: "KNOWLEDGE",
        scope: "ORG",
        // The backend names the rule this guidance is about; the screen only carries it.
        topic: (o.knowledge?.type as KnowledgeTopicValue | undefined) ?? "GENERAL_CS_FAQ",
        label: "운영 기준으로 저장",
      };
    case "PRODUCT_GUIDE_SUPPLEMENT":
      return { kind: "COPY", label: "안내문 복사" };
    case "PRODUCT_IMPROVEMENT_REVIEW":
      return { kind: "COPY", label: "메모 복사" };
  }
}

/**
 * Opportunities a seller can still act on — what a count on a product page may say.
 *
 * `APPLIED` is excluded since Learning & Outcome Loop v1, for the same reason `DISMISSED` always was: a count
 * that included it would keep asking for work the seller has already done. The record of having done it is on
 * the problem's own surface (한 일과 그 결과), which is where a finished thing belongs.
 */
export function actionable(list: readonly OpportunityView[]): OpportunityView[] {
  return list.filter((o) => o.status !== "DISMISSED" && o.status !== "APPLIED");
}

/** The artifact a draft landed in, read off where the draft was going. */
export function artifactOf(destination: DraftDestination): AppliedArtifact {
  if (destination.kind === "KNOWLEDGE") {
    return destination.scope === "ORG" ? "ORG_KNOWLEDGE" : "PRODUCT_KNOWLEDGE";
  }
  // A draft the seller carries off has no artifact here, so applying it is their own word — recorded as that
  // rather than as a document this product can point at.
  return "SELLER_DECLARED";
}
