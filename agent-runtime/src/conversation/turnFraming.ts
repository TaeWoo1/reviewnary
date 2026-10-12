/**
 * **The frame around what a turn drew.** The first sentence the seller reads, and the next moves the
 * turn offers. Lifted out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Both take the artifacts this turn produced and return what wraps them — a deterministic headline
 * from the primary artifact, and a bounded set of chips and links. Neither decides what is true and
 * neither writes prose: the headline is assembled from closed facts, and a chip is only offered for a
 * sentence the planner already serves. The wording itself lives in `./sellerSentences`.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import type {
  Artifact, ConversationView, HumanActionRequiredArtifact, SuggestedAction, WorkingSetView,
} from "./contract";
import type { OperatorAnswer } from "../operator/state/OperatorState";
import { conversationAxisOf } from "../operator/plan/InvestigationPlan";
import { rowsSentence } from "../operator/graph/reviewRows";
import { inquiryRowsSentence } from "../operator/graph/inquiryRowsStep";
import { urgencySentence } from "../operator/graph/inquiryWorkloadStep";
import { periodLabel } from "./period";
import { URGENCY_CRITERION } from "./urgency";
import { won } from "./sellerSentences";
import { promptChip, WORKSPACE_OF } from "./turnArtifacts";


/** The first sentence, from the primary artifact. Deterministic; never model prose. */
export function headlineOf(
  primary: Artifact | null, artifacts: readonly Artifact[], view: ConversationView,
  axis: ReturnType<typeof conversationAxisOf>, answer: OperatorAnswer,
): string {
  switch (primary?.type) {
    case "REVIEW_LIST": {
      const rating = primary.scope.rating === "LOW" ? "낮은 평점 " : "";
      const previous = axis.filters.scope === "WORKING_SET" && view.workingSet?.kind === "REVIEWS" ? view.workingSet : null;
      const token = primary.scope.period?.token ?? null;
      // The same sentence the rows path wrote: the result first, 「지금까지 확인한」 as the bound when some
      // channel is not proven current, never 「0건」 under a stale channel. Which channel and since when is
      // the message's own per-channel sentence and the card's footer — not repeated here.
      const anyStale = previous == null && primary.freshness.some((f) => f.verdict === "UNPROVEN" || f.verdict === "NOT_COLLECTED");
      // A label only when a period was named — the same rule the rows path applies, so the headline and
      // the finding are one sentence and the dedupe can see they are.
      return rowsSentence(previous?.count ?? null, rating, token ? periodLabel(token, primary.scope.period?.days) : "",
        primary.totalCount, anyStale, token);
    }
    case "PRODUCT_LIST": {
      // 「방금 본 리뷰를 …묶었습니다」 is true of exactly one shape: a grouping follow-up over a REVIEWS set.
      // Any other product answer (a policy read on a product, a knowledge miss) says its first finding.
      const groupedReviews = axis.filters.scope === "WORKING_SET" && view.workingSet?.kind === "REVIEWS";
      if (groupedReviews) return `방금 본 리뷰를 상품 ${primary.items.length}개로 묶었습니다.`;
      const first = answer.findings.find((f) => f.confidence === "SUPPORTED") ?? answer.findings.find((f) => f.confidence === "NEEDS_REVIEW");
      return first?.statement ?? `상품 ${primary.items.length}개를 확인했습니다.`;
    }
    case "INQUIRY_LIST": {
      // A ranked list says the order and its criterion — never 「N건입니다」, which is the answer to a
      // question about how many, not about which one first. The sentence is the READ's own (it knows
      // the queue's real size); recomposing it here from the card's title said the same fact twice in
      // two shapes, which is exactly the repetition this package exists to remove.
      if (primary.scope?.rank === "URGENCY") {
        const ranked = answer.findings.find((f) => f.statement.includes(URGENCY_CRITERION));
        if (ranked) return ranked.statement;
        const shown = primary.groups.reduce((n, g) => n + g.items.length, 0);
        const head = primary.groups.flatMap((g) => g.items)[0] ?? null;
        return urgencySentence(primary.title, Math.max(primary.totalCount, shown), shown,
          head ? { title: head.title ?? null, waitingDays: head.waitingDays ?? null } : null);
      }
      const previousReviews = axis.filters.scope === "WORKING_SET" && view.workingSet?.kind === "REVIEWS";
      if (primary.scope && !(primary.more?.to ?? "").includes("NEEDS_REPLY")) {
        // Query Accuracy v1: a ROWS list is said in the spec's own words — the set it refines, the status
        // it was read with, the count the predicate found, and how many of those are on screen.
        const refine = axis.filters.scope === "WORKING_SET" && view.workingSet?.kind === "INQUIRIES";
        const count = (key: string) => primary.groups.filter((g) => g.key === key).reduce((n, g) => n + g.items.length, 0);
        return inquiryRowsSentence(primary.scope, count("UNANSWERED") + count("ANSWERED"), primary.totalCount, refine,
          { unanswered: count("UNANSWERED"), answered: count("ANSWERED") });
      }
      if (primary.totalCount === 0) {
        return previousReviews ? "같은 상품에 대한 미답변 문의는 없습니다." : `${primary.title}는 없습니다.`;
      }
      const groups = primary.groups.map((g) => `${g.label} ${g.items.length}건`).join(" · ");
      return `${primary.title}가 ${primary.totalCount}건입니다 (${groups}).`;
    }
    case "ORDER_SUMMARY": {
      const t = primary.totals;
      const label = periodLabel(primary.period.token, primary.period.days);
      const name = primary.channelCode
        ? (primary.channels[0]?.channelNameKo ?? primary.channelCode) + " " : "";
      const head = `${label} ${name}매출은 ${won(t.sales)}(주문 ${t.orders}건)`;
      if (t.salesDeltaPercent == null) return `${head}입니다.`;
      return `${head}으로 직전 기간보다 ${Math.abs(t.salesDeltaPercent)}% ${t.salesDeltaPercent < 0 ? "줄었습니다" : "늘었습니다"}.`;
    }
    case "ISSUE_LIST":
      return `반복되는 문제 ${primary.items.length}건을 확인했습니다.`;
    case "OPPORTUNITY_LIST":
      return primary.items.length === 0
        ? "지금 제안할 개선 기회가 없습니다."
        : `개선할 만한 기회 ${primary.items.length}건을 확인했습니다.`;
    case "DRAFT":
      return primary.unavailableMessage ?? (primary.version ? "답변 초안을 준비했습니다." : (primary.answerBasisNote ?? "답변 기준이 필요합니다."));
    case "WORKSPACE_LINK":
      return `${primary.link.label} 화면을 열어 드립니다.`;
    default: {
      const first = answer.findings.find((f) => f.confidence === "SUPPORTED")
        ?? answer.findings.find((f) => f.confidence === "NEEDS_REVIEW");
      return first?.statement ?? answer.note ?? "확인한 내용입니다.";
    }
  }
}

/**
 * Up to three prompt chips from the set's kind, plus the workspace link. Examples, never capabilities.
 *
 * @param drewSet whether THIS turn put the set on screen. A conversation stays anchored on the last set
 *     it saw so that 「그중…」 keeps a referent, and that carried-forward anchor used to reach here: an
 *     org-scope answer about a shipping policy came with 「첫 번째 거 답변 준비해줘」 attached, naming
 *     rows the answer had nothing to do with. Chips about a LIST need the list; chips about the
 *     ANCHORED inquiry do not, because the context bar is still naming it on screen.
 */
export function suggestionsFor(
  primary: Artifact | null, workingSet: WorkingSetView | null, human: HumanActionRequiredArtifact | null,
  artifacts: readonly Artifact[], drewSet = true,
): SuggestedAction[] {
  const chips: SuggestedAction[] = [];
  if (human) {
    // 「계속 확인하기」 re-checks a step's own record; a step with nothing to re-check (registering a
    // rule) must not offer it. And a turn that is WAITING on the seller offers that step and nothing
    // else — a chip repeating the request that just stopped is the loop the seller is already in.
    if (human.resumable) chips.push({ label: "계속 확인하기", kind: "RESUME" });
    if (human.actionType === "REVIEW_IMPORT" && artifacts.some((a) => a.type === "REVIEW_LIST" && a.items.length > 0)) {
      chips.push({ label: "일단 확인된 리뷰 보기", kind: "LINK", to: "/reviews" });
    }
    return chips;
  }
  if (primary?.type === "DRAFT" && primary.version) {
    // The draft card carries 「말투 다듬기」 and 「보내기 준비」 as its own controls; a chip row repeating
    // them under the card is the same two actions twice (Conversation UX v2 §D).
  } else if (primary?.type === "APPROVAL_REQUIRED" || primary?.type === "APPROVAL" || primary?.type === "GUIDED_EXECUTION") {
    // The next move is the artifact's own control; no prompt competes with it.
  } else if (workingSet) {
    switch (workingSet.kind) {
      case "REVIEWS": {
        // Agent Object v1: standing ON one review, the next moves are about THAT review — and the
        // reply move is offered only when the channel actually takes one (the card's own capability).
        const detail = artifacts.find((a) => a.type === "REVIEW_DETAIL");
        if (workingSet.selectedObject?.kind === "REVIEW") {
          if (detail?.type === "REVIEW_DETAIL" && detail.replyCapability === "DRAFTABLE") {
            chips.push(promptChip("답글 초안 준비해줘"));
          }
          chips.push(promptChip("같은 상품의 비슷한 리뷰도 보여줘"));
          break;
        }
        if (drewSet) chips.push(promptChip("안 좋은 것만 봐줘"), promptChip("상품별로 묶어줘"), promptChip("문의에서도 같은 문제가 있는지 봐줘"));
        break;
      }
      case "INQUIRIES":
        if (workingSet.selectedInquiry) {
          chips.push(promptChip("답변 준비해줘"));
          if (workingSet.selectedInquiry.productId) chips.push(promptChip("이 상품 기준으로 답변 준비해줘"));
          chips.push(promptChip("답변 안 한 문의만 보여줘"));
        } else if (drewSet) {
          // The chips are examples of what to say NEXT about what is on screen, so they follow the set:
          // one row has no 「첫 번째」 and no narrowing left to offer, and a set already ordered by
          // urgency is not re-offered a 「가장 최근 1개」 (Conversation UX v2 §D).
          const one = workingSet.count === 1;
          const ranked = artifacts.some((a) => a.type === "INQUIRY_LIST" && a.scope?.rank === "URGENCY");
          if (!one && !ranked) {
            chips.push(promptChip(workingSet.filters.inquiryIntent === "ROWS" ? "답변 안 한 것만 보여줘" : "배송 관련부터"));
            if (workingSet.filters.inquiryIntent === "ROWS") chips.push(promptChip("그중 가장 최근 1개만"));
          }
          // A RANKED list opens the row it judged first, and that row carries 「답변 준비」 as its own
          // control (Agentic Experience v2 §5). A chip under the card offering the same move is that
          // action twice, six inches apart, with the seller deciding which one is the real one.
          if (!ranked) chips.push(promptChip(one ? "답변 준비해줘" : "첫 번째 거 답변 준비해줘"));
        }
        break;
      case "ORDERS":
        if (drewSet) chips.push(promptChip("카페24만 봐봐"), promptChip("그때 리뷰나 문의에도 변화 있었어?"));
        break;
      case "PRODUCTS":
        if (drewSet) chips.push(promptChip("문의에서도 같은 문제가 있는지 봐줘"));
        break;
      case "ISSUES":
        if (drewSet) chips.push(promptChip("어느 상품이 제일 많아?"));
        break;
    }
  }
  const links = chips.slice(0, 3);
  // A chip that repeats a control already on screen is a second copy of one action (Conversation UX
  // v2 §D): the list card carries its own 「문의 화면에서 …」 link, and 「전체 N건」 beside a RANKED list
  // would also contradict it — the set is the few rows the answer named, not the queue behind them.
  const alreadyLinked = artifacts.some((a) =>
    (a.type === "INQUIRY_LIST" || a.type === "REVIEW_LIST" || a.type === "LIST")
    && a.more?.to === WORKSPACE_OF[workingSet?.kind ?? "INQUIRIES"].to);
  const ranked = artifacts.some((a) => a.type === "INQUIRY_LIST" && a.scope?.rank === "URGENCY");
  // …and a single row needs no 「전체 1건 처리하기」: the card in front of the seller IS that one row.
  if (workingSet && workingSet.count > 1 && !alreadyLinked && !ranked) {
    const ws = WORKSPACE_OF[workingSet.kind];
    links.push({ label: `전체 ${workingSet.count}건 처리하기`, kind: "LINK", to: ws.to });
  }
  return links;
}
