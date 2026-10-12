/**
 * **Which object the sentence pointed at.** 「이거」 over a prepared draft, an ordinal into the set on
 * the table, an explicit id from the caller, or nothing. Lifted out of `ConversationService.ts` on
 * 2026-10-12 — it used `this` nowhere, so it was already a function and only looked like a method.
 *
 * <p>It reads the view, the working set and what this turn drew, and asks the backend only to confirm
 * an identity it already has a candidate for. It resolves; it never decides what to do with what it
 * resolved — that stays in the service.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import type {
  Artifact, ConversationView, StartTurnRequest, WorkingSetView,
} from "./contract";
import type { SpringClientBundle } from "../http/AgentRunService";
import { actionabilityOf } from "./inquiryActionability";
import type { ResolvedTarget } from "./historyReaders";
import { inquiryTargetFromHistory, reviewTargetFromHistory } from "./historyReaders";
import { pickSet } from "./workingSet";

/** 「이 두 문의」 — at most this many drafts from one sentence. */
const ALL_TARGETS_MAX = 2;


/** Which objects a draft/send sentence points at — an index into what the previous turn showed. */
export async function resolveTargets(
  selector: string, index: number | null, view: ConversationView, hints: StartTurnRequest,
  bundle: SpringClientBundle, workingSet: WorkingSetView | null, drawn: readonly Artifact[] = [],
): Promise<ResolvedTarget[]> {
  const set = pickSet(workingSet, view);
  // 「이거」 over a prepared draft: the draft's own object, whatever the current set is.
  const prepared = view.pendingPrepared;
  if ((selector === "THIS" || selector === "NONE") && !hints.workItemId && prepared) {
    const known = prepared.kind === "REVIEW_DRAFT"
      ? reviewTargetFromHistory(view, prepared.workItemId)
      : inquiryTargetFromHistory(view, prepared.workItemId);
    if (known) return [known];
  }
  if (set?.kind === "REVIEWS") {
    const pick = (ids: readonly string[]) => ids.map((id) => reviewTargetFromHistory(view, id)).filter((t): t is ResolvedTarget => t != null);
    switch (selector) {
      case "FIRST": return pick(set.ids.slice(0, 1));
      case "NTH": return index != null && index >= 1 ? pick(set.ids.slice(index - 1, index)) : [];
      case "ALL": return pick(set.ids.slice(0, ALL_TARGETS_MAX));
      default: return set.ids.length === 1 ? pick(set.ids) : [];
    }
  }
  // Inquiry identity ≠ work-item identity: a ROWS set is indexed by inquiry id, a WORKLOAD set by
  // work item id — both are looked up in the shown rows by either key, so 「첫 번째 거」 resolves whether
  // or not the row has a work item. Whether a draft can attach is the row's own actionability.
  const ids = set?.kind === "INQUIRIES" ? set.ids : [];
  // <b>An ordinal indexes what the seller was LOOKING AT; a singular target may be what this turn
  // found.</b> 「첫 번째 거」 said with nothing on screen points at nothing, and resolving it against
  // rows the same turn drew would answer an ordinal the seller could not have counted. So the ordinal
  // selectors read the persisted set only, and `drawn` is available to the THIS/NONE branch — where
  // the question is not "which of these" but "the one this turn is about".
  const pickShown = (rowIds: readonly string[]): ResolvedTarget[] =>
    rowIds.map((id) => inquiryTargetFromHistory(view, id)).filter((t): t is ResolvedTarget => t != null);
  const pick = (rowIds: readonly string[]): ResolvedTarget[] =>
    rowIds.map((id) => inquiryTargetFromHistory(view, id, drawn)).filter((t): t is ResolvedTarget => t != null);
  // The selected inquiry, when the set is anchored on one — 「이 문의」 / 「이 상품 기준으로」 / a bare verb.
  const anchored = set?.kind === "INQUIRIES" && set.selectedInquiry ? pick([set.selectedInquiry.inquiryId]) : [];
  switch (selector) {
    case "FIRST":
      return pickShown(ids.slice(0, 1));
    case "NTH":
      return index != null && index >= 1 ? pickShown(ids.slice(index - 1, index)) : [];
    case "ALL":
      return pickShown(ids.slice(0, ALL_TARGETS_MAX));
    case "THIS":
    default: {
      // The inquiry the seller is standing on (the screen's hint), the draft just prepared, the
      // anchored inquiry, or the one inquiry in the set. A hint is verified by the same org-scoped
      // read the runtime uses.
      if (hints.workItemId) {
        const known = inquiryTargetFromHistory(view, hints.workItemId, drawn);
        if (known) return [known];
        try {
          const detail = await bundle.inquiry.getInquiryDetail(hints.workItemId);
          const row = {
            workItemId: detail.workItemId, inquiryId: detail.inquiryId, channelCode: detail.channelCode,
            channelNameKo: detail.channelNameKo, productId: detail.productId ?? null, productName: detail.productName ?? null,
          };
          const actionability = actionabilityOf({ workItemId: detail.workItemId, phase: detail.phase, status: detail.status });
          return [{
            kind: "INQUIRY",
            inquiry: { ...row, title: detail.title ?? null, status: detail.status, receivedAt: detail.receivedAt ?? null, snippet: detail.details ?? null },
            actionability,
            target: actionability === "DRAFTABLE" ? row : null,
            executableIdentity: detail.executableIdentity ?? "NONE",
            sourceSubtype: detail.sourceSubtype ?? null,
          }];
        } catch {
          return [];
        }
      }
      if (anchored.length === 1) return anchored;
      return ids.length === 1 ? pick(ids) : [];
    }
  }
}

/**
 * The closed intents the lane answers without the planner. `null` ⇒ not one of them.
 * Tone revision: same inquiry (or review), same evidence, one new draft version, zero reads.
 * Ordinal / label / pronoun selection (Agent Interaction Model v2 §2/§4): the row the sentence
 * points at inside the VISIBLE set becomes the anchor and is INSPECTED — never a re-query of the
 * org, never the same list printed again. Ambiguity shows the candidates; it never guesses.
 * Direct PREPARE (§13): 「뭐라고 답하면 좋을까」 over the anchored inquiry runs the product's own
 * draft path with no planner call.
 */
