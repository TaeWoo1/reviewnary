/**
 * **The reply agent's graph** (Reply Agent v1, 2026-10-28, `docs/standing/reply_agent_v1.md` §1): every path through
 * judge → draft → critique → one revision → save, against a fake backend. The state carries closed words only, and the
 * run is always closed on the backend.
 */
import { describe, expect, it } from "vitest";
import { MAX_REVISIONS, runReply } from "../../src/reply/replyGraph";
import type { Critiqued, Drafted, Judged, ReplyBackend, Saved } from "../../src/reply/ReplySpringClient";
import { ReplyStepError } from "../../src/reply/ReplySpringClient";

class FakeBackend implements ReplyBackend {
  readonly calls: string[] = [];
  finished: { outcome: string; reason: string | null } | null = null;
  constructor(
    private readonly judged: Judged,
    private readonly critiques: Critiqued[],
    private readonly saved: Saved = { saved: true, version: 3, reason: null },
    private readonly throwOn: string | null = null,
    private readonly drafted: Drafted | null = null,
  ) {}
  private step(name: string) {
    this.calls.push(name);
    if (this.throwOn === name) throw new ReplyStepError(502, name);
  }
  async classify(): Promise<Judged> {
    this.step("classify");
    return this.judged;
  }
  async draft(revise: boolean): Promise<Drafted> {
    this.step(revise ? "revise" : "draft");
    return this.drafted ?? { written: true, revisions: revise ? 1 : 0 };
  }
  async critique(): Promise<Critiqued> {
    this.step("critique");
    return this.critiques.shift() ?? { passed: true, failed: [] };
  }
  async save(): Promise<Saved> {
    this.step("save");
    return this.saved;
  }
  async finish(outcome: string, reason: string | null): Promise<void> {
    this.finished = { outcome, reason };
  }
}

const R: Judged = { category: "R", needsResponse: true, handoff: false };

describe("reply graph — the paths a run can take", () => {
  it("passes the first critique and saves", async () => {
    const backend = new FakeBackend(R, [{ passed: true, failed: [] }]);
    const report = await runReply(backend);
    expect(report).toEqual({ outcome: "SAVED", category: "R", reason: null, version: 3 });
    expect(backend.calls).toEqual(["classify", "draft", "critique", "save"]);
    expect(backend.finished).toEqual({ outcome: "SAVED", reason: null });
  });

  it("fails a critique, revises once with what failed, passes, saves", async () => {
    const backend = new FakeBackend(R, [{ passed: false, failed: ["G4"] }, { passed: true, failed: [] }]);
    const report = await runReply(backend);
    expect(report.outcome).toBe("SAVED");
    expect(backend.calls).toEqual(["classify", "draft", "critique", "revise", "critique", "save"]);
  });

  it("still failing after one revision hands the review to a person — never a second revision", async () => {
    expect(MAX_REVISIONS).toBe(1);
    const backend = new FakeBackend(R, [{ passed: false, failed: ["G4"] }, { passed: false, failed: ["N9"] }]);
    const report = await runReply(backend);
    expect(report).toMatchObject({ outcome: "HANDOFF", reason: "STILL_FAILS" });
    expect(backend.calls.filter((c) => c === "revise")).toHaveLength(1);
    expect(backend.calls).not.toContain("save");
  });

  it("a category a person must handle (S · L · X) is handed off before anything is drafted", async () => {
    const backend = new FakeBackend({ category: "S", needsResponse: true, handoff: true }, []);
    const report = await runReply(backend);
    expect(report).toMatchObject({ outcome: "HANDOFF", category: "S", reason: "CATEGORY_S" });
    expect(backend.calls).toEqual(["classify"]);
    expect(backend.finished).toEqual({ outcome: "HANDOFF", reason: "CATEGORY_S" });
  });

  it("「can't tell」 and a question with no fact are handed off with the backend's reason, not failed", async () => {
    const unsure = new FakeBackend({ category: null, needsResponse: true, handoff: true, reason: "UNSURE" }, []);
    expect(await runReply(unsure)).toMatchObject({ outcome: "HANDOFF", category: null, reason: "UNSURE" });
    expect(unsure.calls).toEqual(["classify"]);
    const noFact = new FakeBackend({ category: "D", needsResponse: true, handoff: true, reason: "QUESTION_NO_FACTS" }, []);
    expect(await runReply(noFact)).toMatchObject({ outcome: "HANDOFF", reason: "QUESTION_NO_FACTS" });
    expect(noFact.finished).toEqual({ outcome: "HANDOFF", reason: "QUESTION_NO_FACTS" });
  });

  it("a drafter that finds no fact for a point, or facts that disagree, hands the review over — not a failure", async () => {
    const judged = { category: "Q", needsResponse: true, handoff: false };
    const missing = new FakeBackend(judged, [], undefined, null, { written: false, revisions: 0, reason: "KNOWLEDGE_MISSING" });
    expect(await runReply(missing)).toMatchObject({ outcome: "HANDOFF", reason: "KNOWLEDGE_MISSING" });
    expect(missing.calls).toEqual(["classify", "draft"]);
    const conflict = new FakeBackend(judged, [], undefined, null, { written: false, revisions: 0, reason: "POLICY_CONFLICT" });
    expect(await runReply(conflict)).toMatchObject({ outcome: "HANDOFF", reason: "POLICY_CONFLICT" });
  });

  it("a review that needs nothing, in a band drafted only when needed, ends skipped with no draft", async () => {
    const backend = new FakeBackend({ category: "P", needsResponse: false, handoff: false, skip: true }, []);
    expect(await runReply(backend)).toMatchObject({ outcome: "SKIPPED", reason: "NO_RESPONSE_NEEDED" });
    expect(backend.calls).toEqual(["classify"]);
  });

  it("a critique the day's AI ceiling stopped holds the draft for review — not revised, not saved, not failed", async () => {
    const backend = new FakeBackend({ category: "R", needsResponse: true, handoff: false },
      [{ passed: false, failed: ["REVIEW_PENDING"], pending: true }]);
    expect(await runReply(backend)).toMatchObject({ outcome: "PENDING", reason: "REVIEW_PENDING" });
    expect(backend.calls).toEqual(["classify", "draft", "critique"]);
    expect(backend.finished).toEqual({ outcome: "PENDING", reason: "REVIEW_PENDING" });
  });

  it("a judge with no answer fails the run; a gate closed before the save skips it", async () => {
    const unjudged = new FakeBackend({ category: null, needsResponse: false, handoff: false }, []);
    expect((await runReply(unjudged)).outcome).toBe("FAILED");
    expect(unjudged.calls).toEqual(["classify"]);
    const closed = new FakeBackend(R, [], { saved: false, version: null, reason: "GATE_CLOSED" });
    expect(await runReply(closed)).toMatchObject({ outcome: "SKIPPED", reason: "GATE_CLOSED" });
  });

  it("a step that throws ends the run FAILED with the step's name, and the run is still closed", async () => {
    const backend = new FakeBackend(R, [], undefined, "draft");
    const report = await runReply(backend);
    expect(report).toEqual({ outcome: "FAILED", category: null, reason: "STEP_DRAFT", version: null });
    expect(backend.finished).toEqual({ outcome: "FAILED", reason: "STEP_DRAFT" });
  });
});
