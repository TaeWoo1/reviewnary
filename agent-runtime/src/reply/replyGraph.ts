/**
 * **The reply agent** (Reply Agent v1, 2026-10-28, `docs/standing/reply_agent_v1.md` §1) — a LangGraph graph over the
 * backend's run steps: judge → (hand to a person for S·L·X) → draft → critique → one revision → critique → save.
 *
 * The graph orders; the backend does. Each node is one backend step, and the state carries only what the steps
 * answered — a category, closed rule ids, counts. No review text, no draft, no token is ever in it. Compiled without a
 * checkpointer: a run is minutes long, ends in this process, and a person picks up a handed-off review on the review's
 * own screen, not by resuming this graph.
 */
import { Annotation, END, START, StateGraph } from "@langchain/langgraph";
import type { ReplyBackend } from "./ReplySpringClient";

/** How a run ends. SAVED and HANDOFF are the product outcomes; SKIPPED = a gate closed before the save; PENDING = the
 * day's AI ceiling stopped the critique and the draft waits for review. */
export type ReplyOutcome = "SAVED" | "HANDOFF" | "SKIPPED" | "FAILED" | "PENDING";

/** At most one revision — a draft that still fails after it is a person's to fix (§1). */
export const MAX_REVISIONS = 1;

export const ReplyStateAnnotation = Annotation.Root({
  category: Annotation<string | null>({ reducer: (_a, b) => b, default: () => null }),
  failed: Annotation<readonly string[]>({ reducer: (_a, b) => b, default: () => [] }),
  revisions: Annotation<number>({ reducer: (_a, b) => b, default: () => 0 }),
  version: Annotation<number | null>({ reducer: (_a, b) => b, default: () => null }),
  outcome: Annotation<ReplyOutcome | null>({ reducer: (_a, b) => b, default: () => null }),
  reason: Annotation<string | null>({ reducer: (_a, b) => b, default: () => null }),
});

export type ReplyState = typeof ReplyStateAnnotation.State;

const ended = (outcome: ReplyOutcome, reason: string | null): Partial<ReplyState> => ({ outcome, reason });

export function buildReplyGraph(backend: ReplyBackend) {
  async function judge(): Promise<Partial<ReplyState>> {
    const judged = await backend.classify();
    // A handoff first: 「can't tell」 has no category and is a person's, not a failure (UNSURE is not 「no problem」).
    if (judged.handoff) {
      const reason = judged.reason && /^[A-Z_]{1,40}$/.test(judged.reason) ? judged.reason : `CATEGORY_${judged.category ?? "UNSURE"}`;
      return { category: judged.category, ...ended("HANDOFF", reason) };
    }
    if (!judged.category) return ended("FAILED", "JUDGE_FAILED");
    // The AI proposed nothing to do and the seller drafts only when needed: no draft, by the seller's own rule.
    if (judged.skip) return { category: judged.category, ...ended("SKIPPED", "NO_RESPONSE_NEEDED") };
    return { category: judged.category };
  }

  const handoffWord = (reason: string | null | undefined): string | null =>
    reason && /^[A-Z_]{1,40}$/.test(reason) ? reason : null;

  async function draft(): Promise<Partial<ReplyState>> {
    const drafted = await backend.draft(false);
    if (drafted.written) return {};
    // The drafter found no fact for a point, or facts that disagree: the seller decides — a handoff, not a failure.
    const reason = handoffWord(drafted.reason);
    return reason ? ended("HANDOFF", reason) : ended("FAILED", "DRAFT_FAILED");
  }

  async function critique(): Promise<Partial<ReplyState>> {
    const critiqued = await backend.critique();
    // Held for review: nothing to fix, nothing to save yet — the next run checks the same text.
    if (critiqued.pending) return { failed: critiqued.failed, ...ended("PENDING", "REVIEW_PENDING") };
    return { failed: critiqued.passed ? [] : critiqued.failed };
  }

  async function revise(state: ReplyState): Promise<Partial<ReplyState>> {
    const drafted = await backend.draft(true);
    if (!drafted.written) return ended("HANDOFF", handoffWord(drafted.reason) ?? "STILL_FAILS");
    return { revisions: state.revisions + 1 };
  }

  async function save(): Promise<Partial<ReplyState>> {
    const saved = await backend.save();
    return saved.saved ? { version: saved.version, ...ended("SAVED", null) } : ended("SKIPPED", saved.reason ?? "GATE_CLOSED");
  }

  function stillFails(): Partial<ReplyState> {
    return ended("HANDOFF", "STILL_FAILS");
  }

  return new StateGraph(ReplyStateAnnotation)
    .addNode("judge", judge)
    .addNode("draft", draft)
    .addNode("critique", critique)
    .addNode("revise", revise)
    .addNode("save", save)
    .addNode("stillFails", stillFails)
    .addEdge(START, "judge")
    .addConditionalEdges("judge", (s: ReplyState) => (s.outcome ? END : "draft"), ["draft", END])
    .addConditionalEdges("draft", (s: ReplyState) => (s.outcome ? END : "critique"), ["critique", END])
    .addConditionalEdges(
      "critique",
      (s: ReplyState) =>
        s.outcome ? END : s.failed.length === 0 ? "save" : s.revisions < MAX_REVISIONS ? "revise" : "stillFails",
      ["save", "revise", "stillFails", END],
    )
    .addConditionalEdges("revise", (s: ReplyState) => (s.outcome ? END : "critique"), ["critique", END])
    .addEdge("save", END)
    .addEdge("stillFails", END)
    .compile();
}

export interface ReplyRunReport {
  readonly outcome: ReplyOutcome;
  readonly category: string | null;
  readonly reason: string | null;
  readonly version: number | null;
}

/**
 * Run the graph for one run and close it on the backend, whatever happened. A step that throws ends the run FAILED with
 * the step's name — never its body.
 */
export async function runReply(backend: ReplyBackend): Promise<ReplyRunReport> {
  let report: ReplyRunReport;
  try {
    const final = (await buildReplyGraph(backend).invoke({})) as ReplyState;
    report = {
      outcome: final.outcome ?? "FAILED",
      category: final.category,
      reason: final.reason ?? (final.failed.length ? final.failed.join(",") : null),
      version: final.version,
    };
  } catch (err) {
    const step = err && typeof err === "object" && "step" in err ? String((err as { step: unknown }).step) : "UNKNOWN";
    report = { outcome: "FAILED", category: null, reason: `STEP_${step.toUpperCase()}`.slice(0, 40), version: null };
  }
  try {
    await backend.finish(report.outcome, report.reason && /^[A-Z_]{1,40}$/.test(report.reason) ? report.reason : null);
  } catch {
    // The backend closes an unfinished run itself when the token expires or the starter returns.
  }
  return report;
}
