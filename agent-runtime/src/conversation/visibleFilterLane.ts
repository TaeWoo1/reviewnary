/**
 * **A visible filter applied to the set on the table.** The seller narrowed what is already on screen
 * (「그중 네이버만」 · 「답변 안 한 것만」) or asked for it in urgency order; this lane answers from the
 * rows the conversation already drew and composes the turn that says so.
 *
 * <p>Lifted out of `ConversationService.ts` on 2026-10-12. It used `this` once — for the clock — and
 * that is now a required `referenceDate` argument, so the lane has no hidden input: the same rows and
 * the same date give the same turn, in a test as in a run.
 *
 * <p>Directly under `src/conversation/` on purpose — the write fence walks that tree
 * (`docs/FINDINGS.md` §B10).
 */
import { randomUUID } from "node:crypto";
import type {
  ConversationView, InquiryItem, InquiryListArtifact, WorkingSetView,
} from "./contract";
import type { VisibleFilter } from "./taskInterpreter";
import { WORKING_SET_MAX_IDS } from "./contract";
import type { SpringClientBundle } from "../http/AgentRunService";
import { log } from "../log";
import { matchesTerm, matchesTopic } from "../operator/tools/inquiryWorkload";
import type { Composed } from "./composedTurn";
import { rankByUrgency, URGENCY_CRITERION, URGENCY_LIMIT, waitingDaysOf } from "./urgency";
import { inquiryFromHistory } from "./historyReaders";
import { promptChip } from "./turnArtifacts";
import { FILTER_DETAIL_CAP, filterWords } from "./workingSet";


/**
 * FILTER (Conversation Core v1): narrow the VISIBLE inquiry rows by closed axes — channel · topic ·
 * status · order · limit — with no planner and no org re-read. Topic matching reads the row's own
 * persisted title/product name first; a row that cannot answer from its title spends one bounded
 * detail READ (in-process comparison, never persisted — the same rule the workload filter follows).
 * `null` ⇒ the sentence asks for more than the set can carry (a larger limit) and the planner decides.
 */
export async function applyVisibleFilter(
  view: ConversationView, set: WorkingSetView, filter: VisibleFilter, bundle: SpringClientBundle,
  urgency: boolean, referenceDate: string,
): Promise<Composed | null> {
  const started = Date.now();
  const rows = set.ids
    .map((id) => inquiryFromHistory(view, id))
    .filter((item): item is InquiryItem => item != null);
  if (rows.length === 0) return null;
  // Persistence bounds the rows a thread keeps; a set this lane cannot fully see is the planner's
  // WORKING_SET re-read — filtering a fifth of the rows would be a silently wrong answer.
  if (rows.length < set.ids.length) return null;
  // A limit larger than the set cannot be a refine of it (scopeOverride's NEW_LIMIT, same rule here).
  if (filter.limit != null && filter.limit > rows.length) return null;

  let detailReads = 0;
  const matched: InquiryItem[] = [];
  for (const row of rows) {
    if (filter.channel && (row.channelCode ?? "").toUpperCase() !== filter.channel) continue;
    if (filter.status === "UNANSWERED" && row.status.toUpperCase() === "ANSWERED") continue;
    if (filter.status === "ANSWERED" && row.status.toUpperCase() !== "ANSWERED") continue;
    if (filter.topic) {
      let hit = matchesTopic(filter.topic, [row.title, row.productName]);
      if (!hit && row.workItemId && detailReads < FILTER_DETAIL_CAP) {
        detailReads += 1;
        try {
          const detail = await bundle.inquiry.getInquiryDetail(row.workItemId);
          hit = matchesTopic(filter.topic, [detail.title, detail.details]);
        } catch {
          hit = false;
        }
      }
      if (!hit) continue;
    }
    // The seller's own subject word over the rows on screen — the row's own text first (its subject
    // line, its product, the snippet it was drawn with), then one bounded detail read.
    if (filter.term) {
      let hit = matchesTerm(filter.term, [row.title, row.productName, row.snippet]);
      if (!hit && row.workItemId && detailReads < FILTER_DETAIL_CAP) {
        detailReads += 1;
        try {
          const detail = await bundle.inquiry.getInquiryDetail(row.workItemId);
          hit = matchesTerm(filter.term, [detail.title, detail.details]);
        } catch {
          hit = false;
        }
      }
      if (!hit) continue;
    }
    matched.push(row);
  }
  if (urgency) {
    // The longest wait first — the one urgency signal this product holds (`urgency.ts`).
    matched.splice(0, matched.length, ...rankByUrgency(matched));
  } else if (filter.order) {
    matched.sort((a, b) => filter.order === "OLDEST"
      ? a.receivedAt.localeCompare(b.receivedAt)
      : b.receivedAt.localeCompare(a.receivedAt));
  }
  const kept = filter.limit != null ? matched.slice(0, filter.limit) : matched;

  const budget = { toolCalls: detailReads, llmCalls: 0, elapsedMs: Date.now() - started, stopReason: urgency ? "VISIBLE_PRIORITY" : "VISIBLE_FILTER" };
  const desc = filterWords(filter);
  const orderWord = filter.order === "OLDEST" ? "가장 오래된" : "가장 최근";
  const today = referenceDate.slice(0, 10);
  log("conversation_visible_filter", {
    axes: [filter.channel && "channel", filter.topic && "topic", filter.term && "term", filter.status && "status", filter.limit != null && "limit", filter.order && "order", urgency && "urgency"].filter(Boolean).join(","),
    of: rows.length, matched: kept.length, detailReads,
  });
  if (kept.length === 0) {
    // R2: a filter that left nothing keeps the previous set as the anchor — 「없습니다」 is the answer.
    return {
      status: "DONE", message: `방금 본 문의 ${rows.length}건 중 ${desc}문의는 없습니다.`,
      artifacts: [], suggestedActions: [promptChip("답변 안 한 문의만 보여줘")],
      workingSet: view.workingSet, pendingHumanActions: [], pendingPrepared: view.pendingPrepared, budget,
    };
  }
  const message = urgency
    ? `방금 본 문의 ${kept.length}건을 먼저 볼 순서로 정리했습니다. ${URGENCY_CRITERION} ${URGENCY_LIMIT}`
    : desc.length === 0 && filter.limit != null
      ? `방금 본 문의 ${rows.length}건 중 ${orderWord} ${kept.length}건입니다.`
      : filter.limit != null && matched.length > kept.length
        ? `방금 본 문의 ${rows.length}건 중 ${desc}문의는 ${matched.length}건이고, 그중 ${orderWord} ${kept.length}건입니다.`
        : `방금 본 문의 ${rows.length}건 중 ${desc}문의는 ${kept.length}건입니다.`;

  // Consecutive runs of the same answered state become one group each — the rows step's own rule.
  const groups: Array<{ key: "UNANSWERED" | "ANSWERED"; label: string; items: InquiryItem[] }> = [];
  for (const row of kept) {
    const key = row.status.toUpperCase() === "ANSWERED" ? "ANSWERED" as const : "UNANSWERED" as const;
    const shown: InquiryItem = urgency ? { ...row, waitingDays: waitingDaysOf(row.receivedAt, today) } : row;
    const last = groups.at(-1);
    if (last && last.key === key) last.items.push(shown);
    else groups.push({ key, label: key === "UNANSWERED" ? "답변 필요" : "답변함", items: [shown] });
  }
  const title = urgency
    ? "먼저 볼 문의"
    : desc.length === 0 && filter.limit != null
      ? `방금 본 문의 중 ${orderWord} ${kept.length}건`
      : `방금 본 문의 중 ${desc}문의${filter.limit != null && matched.length > kept.length ? ` · ${orderWord} ${kept.length}건` : ""}`;
  const artifact: InquiryListArtifact = {
    artifactId: `a-filter-${randomUUID()}`, type: "INQUIRY_LIST", title, groups, totalCount: kept.length,
    scope: {
      period: set.filters.period ?? null,
      channelCode: filter.channel ?? set.filters.channelCode ?? null,
      status: filter.status ?? set.filters.status ?? "ALL",
      order: filter.order ?? set.filters.order ?? "NEWEST",
      limit: filter.limit,
      ...(filter.term ? { term: filter.term } : {}),
      ...(urgency ? { rank: "URGENCY" as const } : {}),
    },
    more: { label: "문의 화면에서 보기", to: "/inquiries", count: kept.length },
  };
  const workingSet: WorkingSetView = {
    kind: "INQUIRIES", label: title, count: kept.length,
    ids: kept.map((i) => i.inquiryId).slice(0, WORKING_SET_MAX_IDS),
    filters: {
      ...(set.filters.period ? { period: set.filters.period } : {}),
      channelCode: filter.channel ?? set.filters.channelCode ?? null,
      ...(filter.status ? { status: filter.status } : set.filters.status ? { status: set.filters.status } : {}),
      order: filter.order ?? set.filters.order ?? "NEWEST",
      ...(filter.topic ? { topic: filter.topic } : set.filters.topic ? { topic: set.filters.topic } : {}),
      ...(filter.term ? { term: filter.term } : set.filters.term ? { term: set.filters.term } : {}),
      inquiryIntent: set.filters.inquiryIntent ?? "ROWS",
    },
    productIds: [...new Set(kept.map((i) => i.productId).filter((p): p is string => p != null))].slice(0, WORKING_SET_MAX_IDS),
    workItemIds: kept.map((i) => i.workItemId).filter((w): w is string => w != null).slice(0, WORKING_SET_MAX_IDS),
    turnId: "",
  };
  return {
    status: "DONE",
    message,
    artifacts: [artifact],
    suggestedActions: [
      promptChip("첫 번째 거 답변 준비해줘"),
      ...(filter.status !== "UNANSWERED" ? [promptChip("답변 안 한 것만 보여줘")] : []),
    ],
    workingSet, pendingHumanActions: [], pendingPrepared: view.pendingPrepared, activeTask: null, budget,
  };
}

/**
 * INSPECT for the OTHER object (Agent Object v1 §1): the selected review, read exactly.
 *
 * The same shape as {@link inspect} — one read, the object's own card, the anchor kept, zero planner
 * and zero model calls. `null` when the read failed: a card assembled from the anchor's own fields
 * would be this runtime describing a review it could not read, so the turn falls through to the
 * planner instead of inventing one.
 */
